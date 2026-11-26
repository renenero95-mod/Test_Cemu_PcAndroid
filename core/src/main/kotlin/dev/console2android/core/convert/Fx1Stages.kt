package dev.console2android.core.convert

import dev.console2android.core.diag.DiagnosticEvent
import dev.console2android.core.diag.FailureDomain
import dev.console2android.core.diag.RecoveryRoute
import dev.console2android.core.diag.Severity
import dev.console2android.core.fx1.Fx1
import dev.console2android.core.fx1.Fx1Image
import dev.console2android.core.fx1.Fx1ParseResult
import dev.console2android.core.ir.IrText
import dev.console2android.core.ir.PpcTranslator
import dev.console2android.core.job.JobState
import dev.console2android.core.job.StageResult
import dev.console2android.core.library.SourceKind
import java.io.File

/**
 * The conversion pipeline for the documented proof-of-concept path: a C2A-FX1 synthetic fixture (PowerPC subset) is
 * validated, parsed, extracted, translated to C2A-IR, packaged (cache-addressed) and independently validated.
 *
 * Every stage output is a SHA-256 that chains to the previous one, so a resumed job proves that nothing changed
 * underneath it. Real Xbox 360 executables (XEX1/XEX2) are NOT converted by this pipeline: the validate stage rejects
 * them with a clear `FORMAT-UNSUPPORTED` event (they can still be imported, analysed and compared in the library).
 */
object Fx1Pipeline {
    const val V_VALIDATE = "c2a-fx1-validate/1"
    const val V_ANALYZE = "c2a-fx1-analyze/1"
    const val V_EXTRACT = "c2a-fx1-extract/1"
    const val V_PACKAGE = "c2a-fx1-package/1"
    const val V_FINAL = "c2a-fx1-final/1"
    val V_TRANSLATE = "${ToolVersions.TRANSLATOR}+${ToolVersions.IR_FORMAT}"

    /** Source files above this are rejected before being read (header + two maximal sections). */
    const val MAX_SOURCE_BYTES = Fx1.HEADER_SIZE + 2 * Fx1.MAX_SECTION

    fun stages(): List<Stage> = listOf(ValidateStage, AnalyzeStage, ExtractStage, TranslateStage, PackageStage, FinalValidationStage)

    internal fun ev(code: String, sev: Severity, stage: String, msg: String, domain: FailureDomain = FailureDomain.UNKNOWN, subject: String? = null, route: RecoveryRoute = RecoveryRoute.NONE) =
        DiagnosticEvent(code, sev, domain, "fx1-pipeline", stage, msg, route, subject)

    internal fun imageHash(entry: Int, code: ByteArray, data: ByteArray): String =
        sha256("c2a-fx1-image/1|$entry|${sha256(code)}|${sha256(data)}")

    internal fun extractHash(imageHash: String): String = sha256("c2a-extract/1|$imageHash")

    internal const val EXTRACT_PROPS = "extract.properties"

    /** Reads the extracted image back from the job work directory (null when missing or inconsistent). */
    internal fun loadExtracted(dir: File): Fx1Image? {
        val p = readProps(File(dir, EXTRACT_PROPS))
        val entry = p["entry"]?.toIntOrNull() ?: return null
        val codeF = File(dir, PackageFiles.CODE); val dataF = File(dir, PackageFiles.DATA)
        if (!codeF.isFile || !dataF.isFile) return null
        val code = codeF.readBytes(); val data = dataF.readBytes()
        if (sha256(code) != p["codeSha256"] || sha256(data) != p["dataSha256"]) return null
        return Fx1Image(entry, code, data)
    }
}

private fun fail(stage: String, code: String, msg: String, subject: String? = null) =
    StageResult.Failure(Fx1Pipeline.ev(code, Severity.ERROR, stage, msg, subject = subject))

internal object ValidateStage : Stage {
    override val state = JobState.VALIDATING
    override val toolVersion = Fx1Pipeline.V_VALIDATE

    override fun execute(ctx: PipelineContext, input: String): StageResult {
        val entry = ctx.source
        if (entry.kind != SourceKind.C2AFX1) {
            return StageResult.Unsupported(listOf(Fx1Pipeline.ev(
                "FORMAT-UNSUPPORTED", Severity.ERROR, "validate",
                "Conversion of ${entry.kind} sources is not implemented: only the C2A-FX1 synthetic fixture can be converted. " +
                    "The source stays in the library and can still be analysed and compared with the XEX1/XEX2 reference identifiers.",
                FailureDomain.INPUT_VALIDATION, route = RecoveryRoute.UNSUPPORTED,
            )))
        }
        if (entry.sizeBytes > Fx1Pipeline.MAX_SOURCE_BYTES) return fail("validate", "FX1-TOO-LARGE", "Source is larger than ${Fx1Pipeline.MAX_SOURCE_BYTES} bytes.")
        if (!ctx.library.verify(entry.sha256)) return fail("validate", "SOURCE-CORRUPT", "The stored source no longer matches its SHA-256.", entry.sha256.take(16))
        return StageResult.Success(sha256("c2a-validated/1|$input|${entry.kind}"))
    }
}

internal object AnalyzeStage : Stage {
    override val state = JobState.ANALYZING
    override val toolVersion = Fx1Pipeline.V_ANALYZE

    override fun execute(ctx: PipelineContext, input: String): StageResult {
        val file = ctx.library.sourceFile(ctx.source.sha256) ?: return fail("analyze", "SOURCE-MISSING", "The source file is missing.")
        val bytes = file.readBytes()
        return when (val r = Fx1.parse(bytes)) {
            is Fx1ParseResult.Failure -> StageResult.Unsupported(r.events)
            is Fx1ParseResult.Success -> {
                val img = r.image
                ctx.events += Fx1Pipeline.ev("FX1-PARSED", Severity.INFO, "analyze",
                    "C2A-FX1 parsed: ${img.code.size / 4} code words, ${img.data.size} data bytes.", FailureDomain.INPUT_VALIDATION)
                StageResult.Success(Fx1Pipeline.imageHash(img.entryOffset, img.code, img.data))
            }
        }
    }
}

internal object ExtractStage : Stage {
    override val state = JobState.EXTRACTING
    override val toolVersion = Fx1Pipeline.V_EXTRACT

    override fun outputStillValid(ctx: PipelineContext, outputSha256: String): Boolean {
        val img = Fx1Pipeline.loadExtracted(ctx.workDir) ?: return false
        return Fx1Pipeline.extractHash(Fx1Pipeline.imageHash(img.entryOffset, img.code, img.data)) == outputSha256
    }

    override fun execute(ctx: PipelineContext, input: String): StageResult {
        val file = ctx.library.sourceFile(ctx.source.sha256) ?: return fail("extract", "SOURCE-MISSING", "The source file is missing.")
        val img = (Fx1.parse(file.readBytes()) as? Fx1ParseResult.Success)?.image
            ?: return fail("extract", "FX1-REPARSE", "The source no longer parses.")
        if (Fx1Pipeline.imageHash(img.entryOffset, img.code, img.data) != input) {
            return fail("extract", "INPUT-CHANGED", "The source differs from what the previous stage analysed.")
        }
        File(ctx.workDir, PackageFiles.CODE).writeBytes(img.code)
        File(ctx.workDir, PackageFiles.DATA).writeBytes(img.data)
        writeAtomic(File(ctx.workDir, Fx1Pipeline.EXTRACT_PROPS), canonicalProps(linkedMapOf(
            "entry" to img.entryOffset.toString(), "codeSha256" to sha256(img.code), "dataSha256" to sha256(img.data),
        )))
        return StageResult.Success(Fx1Pipeline.extractHash(input))
    }
}

internal object TranslateStage : Stage {
    override val state = JobState.TRANSLATING
    override val toolVersion = Fx1Pipeline.V_TRANSLATE

    override fun outputStillValid(ctx: PipelineContext, outputSha256: String): Boolean {
        val f = File(ctx.workDir, PackageFiles.PROGRAM)
        return f.isFile && sha256(f.readText(Charsets.UTF_8)) == outputSha256
    }

    override fun execute(ctx: PipelineContext, input: String): StageResult {
        val img = Fx1Pipeline.loadExtracted(ctx.workDir) ?: return fail("translate", "EXTRACT-MISSING", "Extracted sections are missing or damaged.")
        if (Fx1Pipeline.extractHash(Fx1Pipeline.imageHash(img.entryOffset, img.code, img.data)) != input) {
            return fail("translate", "INPUT-CHANGED", "Extracted sections differ from the previous stage's output.")
        }
        val tr = PpcTranslator.translate(img.code, Fx1.CODE_BASE, Fx1.CODE_BASE + img.entryOffset)
        val text = IrText.write(tr.program)
        writeAtomic(File(ctx.workDir, PackageFiles.PROGRAM), text)
        ctx.events += Fx1Pipeline.ev("TRANSLATED", Severity.INFO, "translate",
            "${tr.instructionCount} reachable instructions in ${tr.program.blocks.size} blocks; ${tr.unsupported.size} unsupported, ${tr.externalTargets.size} external target(s).",
            FailureDomain.CPU_SEMANTICS)
        val out = sha256(text)
        return if (tr.unsupported.isEmpty() && tr.externalTargets.isEmpty()) StageResult.Success(out) else StageResult.Partial(out, tr.events)
    }
}

internal object PackageStage : Stage {
    override val state = JobState.PACKAGING
    override val toolVersion = Fx1Pipeline.V_PACKAGE

    private fun outHash(key: String, program: String) = sha256("c2a-pkg/1|$key|$program")

    override fun outputStillValid(ctx: PipelineContext, outputSha256: String): Boolean {
        val key = CacheKey.of(ctx.job.sourceSha256, ctx.job.mode)
        val pkg = ctx.packages.lookupIntact(key) ?: return false
        ctx.packageId = key
        return outHash(key, pkg.manifest.getValue("programSha256")) == outputSha256
    }

    override fun execute(ctx: PipelineContext, input: String): StageResult {
        val programFile = File(ctx.workDir, PackageFiles.PROGRAM)
        if (!programFile.isFile) return fail("package", "PROGRAM-MISSING", "The translated program is missing.")
        val programText = programFile.readText(Charsets.UTF_8)
        if (sha256(programText) != input) return fail("package", "INPUT-CHANGED", "The translated program differs from the previous stage's output.")
        val img = Fx1Pipeline.loadExtracted(ctx.workDir) ?: return fail("package", "EXTRACT-MISSING", "Extracted sections are missing or damaged.")

        val key = CacheKey.of(ctx.job.sourceSha256, ctx.job.mode)
        val hit = ctx.packages.lookupIntact(key)
        if (hit != null && hit.manifest["programSha256"] == input) {
            ctx.packageId = key
            ctx.events += Fx1Pipeline.ev("PACKAGE-CACHE-HIT", Severity.INFO, "package", "An identical, intact package already exists; it is reused.", subject = key.take(16))
            return StageResult.Success(outHash(key, input))
        }
        if (hit != null) ctx.packages.remove(key) // same key but different program: never trust it

        // Counts come from a fresh translation, which also proves the program text is reproducible.
        val tr = PpcTranslator.translate(img.code, Fx1.CODE_BASE, Fx1.CODE_BASE + img.entryOffset)
        if (sha256(IrText.write(tr.program)) != input) return fail("package", "NOT-REPRODUCIBLE", "Re-translation does not reproduce the program.")
        val pkg = try {
            ctx.packages.write(PackageInput(
                cacheKey = key, sourceSha256 = ctx.job.sourceSha256, mode = ctx.job.mode, programText = programText,
                code = img.code, data = img.data, entry = Fx1.CODE_BASE + img.entryOffset, codeBase = Fx1.CODE_BASE,
                translatedInstructions = tr.instructionCount, unsupportedCount = tr.unsupported.size,
                externalTargets = tr.externalTargets.size, createdAtEpochMs = ctx.nowMs(),
            ))
        } catch (e: java.io.IOException) {
            return fail("package", "PACKAGE-WRITE", "The package could not be written (${e.javaClass.simpleName}).")
        }
        ctx.packageId = pkg.id
        ctx.events += Fx1Pipeline.ev("PACKAGE-WRITTEN", Severity.INFO, "package", "Package written.", subject = key.take(16))
        return StageResult.Success(outHash(key, input))
    }
}

/** Always runs (never checkpoint-reused): the validator must judge the package as it is on disk right now. */
internal object FinalValidationStage : Stage {
    override val state = JobState.FINAL_VALIDATION
    override val toolVersion = Fx1Pipeline.V_FINAL
    override val reusable: Boolean get() = false

    override fun execute(ctx: PipelineContext, input: String): StageResult {
        val key = CacheKey.of(ctx.job.sourceSha256, ctx.job.mode)
        ctx.packageId = key
        val pkg = ctx.packages.get(key) ?: return fail("final-validation", "PACKAGE-MISSING", "The package to validate does not exist.", key.take(16))
        val outcome = PackageValidator.validate(pkg, ctx.job.mode, ctx.job.reference)
        runCatching { pkg.writeStatus(outcome.toStatusMap(ctx.nowMs())) }
        ctx.validation = outcome
        ctx.events += outcome.events
        return StageResult.Success(sha256("c2a-final/1|$key|${outcome.fcv}|${outcome.status}"))
    }
}
