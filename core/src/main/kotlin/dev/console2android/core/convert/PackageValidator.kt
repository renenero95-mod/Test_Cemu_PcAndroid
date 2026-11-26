package dev.console2android.core.convert

import dev.console2android.core.diag.DiagnosticEvent
import dev.console2android.core.diag.FailureDomain
import dev.console2android.core.diag.Severity
import dev.console2android.core.ir.CondBranch
import dev.console2android.core.ir.FallThrough
import dev.console2android.core.ir.Jump
import dev.console2android.core.ir.PpcTranslator
import dev.console2android.core.ir.ReturnLr
import dev.console2android.core.ir.Trap
import dev.console2android.core.ir.isUnconditionalBo
import dev.console2android.core.job.ConversionMode
import dev.console2android.core.ppc.PpcInterpreter
import dev.console2android.core.ppc.StopKind
import dev.console2android.core.validation.CheckResult
import dev.console2android.core.validation.Fcv
import dev.console2android.core.validation.FcvCheck
import dev.console2android.core.validation.FcvOverall
import dev.console2android.core.validation.ResultStatus
import dev.console2android.core.validation.StatusInput
import dev.console2android.core.validation.StatusPolicy
import dev.console2android.core.xex.XexReferenceCheck
import dev.console2android.core.xex.XexReferenceSummary

class ValidationOutcome(
    val checks: List<FcvCheck>,
    val fcv: FcvOverall,
    val status: ResultStatus,
    val unsupportedInstructions: Int,
    val smokeSteps: Long?,
    val fallbackSteps: Long?,
    val events: List<DiagnosticEvent>,
) {
    /** Values stored in the package's `status.properties` (machine-derived only; never a user annotation). */
    fun toStatusMap(nowEpochMs: Long): Map<String, String> = linkedMapOf(
        "fcv" to fcv.name, "status" to status.name, "validatedAt" to nowEpochMs.toString(),
        "unsupported" to unsupportedInstructions.toString(), "smokeSteps" to (smokeSteps?.toString() ?: "n/a"),
        "fallbackSteps" to (fallbackSteps?.toString() ?: "n/a"), "runtime" to ToolVersions.RUNTIME,
        // The status describes what the implemented checks exercised, never game compatibility.
        "scope" to "synthetic-fixture-checks",
        "failedChecks" to checks.filter { it.result == CheckResult.FAIL }.joinToString(",") { it.id },
    )
}

/**
 * Final Conversion Validator for C2A-FX1 packages. Independent of the conversion stages: it re-reads the package from
 * disk, recomputes hashes, re-parses the IR, re-translates the code to prove reproducibility, checks dependency
 * closure and runs a differential smoke test (reference interpreter vs. package runtime). It does not claim more
 * than that: a PASS says nothing about any program beyond what these checks exercise.
 */
object PackageValidator {
    private const val MODULE = "fcv"
    private const val STAGE = "final-validation"

    fun validate(
        pkg: ConvertedPackage,
        mode: ConversionMode,
        reference: XexReferenceSummary? = null,
        maxSmokeSteps: Long = PpcInterpreter.DEFAULT_MAX_STEPS,
    ): ValidationOutcome {
        val checks = ArrayList<FcvCheck>()
        val events = ArrayList<DiagnosticEvent>()
        fun add(id: String, mandatory: Boolean, r: CheckResult, why: String, ev: String? = null) {
            checks += FcvCheck(id, mandatory, r, why, ev)
            if (r == CheckResult.FAIL || r == CheckResult.WARN) {
                events += DiagnosticEvent(
                    "FCV-${id.uppercase()}", if (r == CheckResult.FAIL && mandatory) Severity.ERROR else Severity.WARNING,
                    FailureDomain.UNKNOWN, MODULE, STAGE, why, subject = id,
                )
            }
        }

        // 1. manifest + provenance
        val m = pkg.manifest
        add("manifest", true, CheckResult.PASS, "Manifest present with all required fields.", pkg.id)
        val versionsOk = m["translator"] == ToolVersions.TRANSLATOR && m["irFormat"] == ToolVersions.IR_FORMAT
        add("tool-versions", false, if (versionsOk) CheckResult.PASS else CheckResult.WARN,
            if (versionsOk) "Package was produced by the current translator/IR versions." else "Package was produced by different tool versions (${m["translator"]}); re-convert to refresh it.")
        add("cache-key", true, if (m["cacheKey"] == CacheKey.of(pkg.sourceSha256, pkg.mode)) CheckResult.PASS else CheckResult.FAIL,
            "Cache key must equal the hash of source + tool versions + mode.", m["cacheKey"])

        // 2. integrity
        val problems = pkg.integrityProblems()
        add("artifact-hashes", true, if (problems.isEmpty()) CheckResult.PASS else CheckResult.FAIL,
            if (problems.isEmpty()) "program, code and data hashes match the manifest." else problems.joinToString("; "), m["programSha256"])

        // 3. loadability (parse + structure)
        val loaded = if (problems.isEmpty()) PackageRuntime.load(pkg) else null
        val ok = loaded as? PackageRuntime.LoadResult.Ok
        add("ir-loadable", true, if (ok != null) CheckResult.PASS else CheckResult.FAIL,
            if (ok != null) "Program parses and matches its code section." else ((loaded as? PackageRuntime.LoadResult.Failed)?.reason ?: "not loadable because the artifacts are corrupt"))
        add("target-architecture", false, CheckResult.NOT_APPLICABLE, "C2A-IR is architecture independent (no native ARM64 code is produced yet).")

        var smokeSteps: Long? = null; var fallback: Long? = null; var unsupported = 0
        if (ok != null) {
            val prog = ok.loaded.program
            // 4. reproducibility
            val re = PpcTranslator.translate(ok.loaded.image.code, prog.codeBase, prog.entry)
            val reproducible = sha256(dev.console2android.core.ir.IrText.write(re.program)) == m["programSha256"]
            add("reproducible", true, if (reproducible) CheckResult.PASS else CheckResult.FAIL,
                "Re-translating the packaged code must give the identical program (traceable to inputs and tool version).", m["programSha256"])

            // 5. dependency closure + unresolved operations
            val blocks = prog.blocks
            var dangling = 0; var external = 0
            fun target(t: Long) { if (t !in blocks) { if (t >= prog.codeBase && t < prog.codeBase + prog.codeSize) dangling++ else external++ } }
            for (b in prog.orderedBlocks) when (val t = b.terminator) {
                is Jump -> target(t.target)
                is CondBranch -> { target(t.target); if (t.link != null || !isUnconditionalBo(t.bo)) target(t.fallthrough) }
                is ReturnLr -> if (t.link != null || !isUnconditionalBo(t.bo)) target(t.fallthrough)
                is FallThrough -> target(t.target)
                is Trap -> unsupported++
            }
            add("dependency-closure", true, if (dangling == 0) CheckResult.PASS else CheckResult.FAIL,
                if (dangling == 0) "Every direct branch target inside the code range has a translated block." else "$dangling direct branch target(s) inside the code range have no translated block.")
            add("unresolved-operations", mode == ConversionMode.STRICT,
                if (unsupported == 0 && external == 0) CheckResult.PASS else CheckResult.FAIL,
                if (unsupported == 0 && external == 0) "No unsupported instructions and no external branch targets."
                else "$unsupported unsupported instruction(s) and $external external branch target(s) remain (reached at run time: execution stops, never approximated).")

            // 6. differential smoke test
            val ref = PackageRuntime.runReference(ok.loaded, maxSmokeSteps)
            val ir = PackageRuntime.run(ok.loaded, maxSmokeSteps)
            smokeSteps = ir.run.steps; fallback = ir.fallbackSteps
            val same = ref == ir.run
            val smoke = when {
                !same -> CheckResult.FAIL
                ir.run.stop == StopKind.EXIT -> CheckResult.PASS
                else -> CheckResult.WARN
            }
            add("smoke-differential", true, smoke, when (smoke) {
                CheckResult.PASS -> "Reference interpreter and package runtime agree (registers, memory, output, exit code); ${ir.run.steps} steps, ${ir.fallbackSteps} via interpreter fallback."
                CheckResult.WARN -> "Both engines agree but the program did not exit normally: ${ir.run.stop} ${ir.run.detail ?: ""}".trim()
                else -> "Reference interpreter and package runtime DIFFER (stop ${ref.stop} vs ${ir.run.stop})."
            })
        }

        // 7. user-supplied XEX reference identifiers (metadata comparison, non-mandatory)
        reference?.comparison?.let { c -> checks += XexReferenceCheck.fcvChecks(c) }

        val overall = Fcv.overall(checks)
        val smokePassed = checks.any { it.id == "smoke-differential" && it.result == CheckResult.PASS }
        val status = StatusPolicy.classify(StatusInput(
            mode = mode, fcv = overall, launchGatePassed = ok != null && overall != FcvOverall.FAIL,
            criticalIssues = unsupported, unverifiedOrApproximatePaths = unsupported,
            coverageDocumented = true, automatedSmokePassed = smokePassed,
        ))
        return ValidationOutcome(checks, overall, status, unsupported, smokeSteps, fallback, events)
    }
}
