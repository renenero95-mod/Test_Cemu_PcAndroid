package dev.console2android.core.report

import dev.console2android.core.diag.LoggedEvent
import dev.console2android.core.diag.RecoveryRoute
import dev.console2android.core.diag.Severity
import dev.console2android.core.validation.CheckResult
import dev.console2android.core.validation.FcvCheck
import dev.console2android.core.validation.FcvOverall
import dev.console2android.core.validation.ResultStatus
import dev.console2android.core.xex.IdComparison
import dev.console2android.core.xex.XexReferenceSummary

data class DeviceInfo(val model: String, val androidVersion: String, val abi: String)

/** Counts must be measured. `null` means "not measured" and is printed as such (never as 0). */
data class RecoverySummary(
    val aot: Int? = null,
    val compatibilityLayer: Int? = null,
    val interpreter: Int? = null,
    val jit: Int? = null,
    val unresolved: Int? = null,
)

/** User annotation. Kept separate from machine results; it never feeds StatusPolicy. */
enum class UserAnnotation(val label: String) {
    USER_TESTED("User tested"),
    PLAYABLE_WITH_ISSUES("Playable with issues"),
    FAILED_DURING_GAMEPLAY("Failed during gameplay"),
}

data class DiagnosticReport(
    val reportId: String,
    val gameId: String?,
    val packageVersion: String?,
    val runtimeVersion: String,
    val sourceSha256: String?,
    val device: DeviceInfo,
    val status: ResultStatus?,
    val fcv: FcvOverall?,
    val fcvChecks: List<FcvCheck> = emptyList(),
    val events: List<LoggedEvent> = emptyList(),
    val droppedEvents: Long = 0,
    val recovery: RecoverySummary = RecoverySummary(),
    val userAnnotation: UserAnnotation? = null,
    val createdAtEpochMs: Long,
    val xexReference: XexReferenceSummary? = null,
)

object ReportFormatter {
    /** Shown to the user before export. Nothing else is included. */
    val EXPORTED_METADATA: List<String> = listOf(
        "Report ID and timestamp",
        "Game/package identifier, package and runtime version, source SHA-256",
        "Device model, Android version, ABI",
        "Validation results and status",
        "Important diagnostic events (warnings and above)",
        "Recovery counts (only if measured)",
        "XEX1/XEX2 reference identifiers: length, short fingerprint and comparison result only (never the raw values)",
        "Optional user annotation",
    )

    fun toText(r: DiagnosticReport): String = buildString {
        fun count(s: Severity) = r.events.count { it.event.severity == s }
        appendLine("CONSOLE2ANDROID — GAME DIAGNOSTIC REPORT")
        appendLine("Game: ${r.gameId ?: "(none — environment report)"}")
        appendLine("Package: ${r.packageVersion ?: "n/a"}")
        appendLine("Runtime: ${r.runtimeVersion}")
        appendLine("Device: ${r.device.model}")
        appendLine("Android: ${r.device.androidVersion} / ${r.device.abi}")
        appendLine()
        appendLine("Overall: ${r.status?.label ?: "NOT APPLICABLE"}")
        appendLine("FCV: ${r.fcv?.label ?: "NOT APPLICABLE"}")
        r.userAnnotation?.let { appendLine("User annotation (user-provided, not machine-verified): ${it.label}") }

        val nonPass = r.fcvChecks.filter { it.result != CheckResult.PASS }
        if (nonPass.isNotEmpty()) {
            appendLine()
            appendLine("FCV CHECKS (not PASS)")
            nonPass.forEach { appendLine("[${it.result}] ${it.id}${if (it.mandatory) " (mandatory)" else ""} — ${it.reason}") }
        }

        r.xexReference?.let { x ->
            appendLine()
            appendLine("XEX REFERENCE IDS (user-supplied, opaque; values not exported)")
            fun cmp(c: IdComparison?) = when (c) {
                null -> "not compared (no source analysed)"
                IdComparison.MATCHES_SOURCE_SHA256 -> "matches source SHA-256"
                IdComparison.DIFFERS_FROM_SOURCE_SHA256 -> "DIFFERS from source SHA-256"
                IdComparison.NOT_COMPARABLE -> "not comparable (not a SHA-256)"
            }
            appendLine("XEX1: ${x.ids.xex1Length} chars, fp ${x.ids.xex1Fingerprint} — ${cmp(x.comparison?.xex1)}")
            appendLine("XEX2: ${x.ids.xex2Length} chars, fp ${x.ids.xex2Fingerprint} — ${cmp(x.comparison?.xex2)}")
        }

        val important = r.events.filter { it.event.severity >= Severity.WARNING }
        appendLine()
        appendLine("IMPORTANT ISSUES")
        if (important.isEmpty()) appendLine("None") else important.forEach {
            val e = it.event
            val route = if (e.route != RecoveryRoute.NONE) " [route: ${e.route}]" else ""
            val times = if (it.count > 1) " (x${it.count})" else ""
            appendLine("[${e.severity}] ${e.domain} — ${e.message} {${e.module}/${e.stage}${e.subject?.let { s -> " $s" } ?: ""}}$route$times")
        }

        fun n(v: Int?) = v?.toString() ?: "not measured"
        appendLine()
        appendLine("RECOVERY SUMMARY")
        appendLine("AOT: ${n(r.recovery.aot)}")
        appendLine("Compatibility layer: ${n(r.recovery.compatibilityLayer)}")
        appendLine("Interpreter: ${n(r.recovery.interpreter)}")
        appendLine("JIT: ${n(r.recovery.jit)}")
        appendLine("Unresolved: ${n(r.recovery.unresolved)}")
        appendLine()
        appendLine("CRITICAL ERRORS: ${count(Severity.CRITICAL)}")
        appendLine("Errors: ${count(Severity.ERROR)}")
        appendLine("Warnings: ${count(Severity.WARNING)}")
        appendLine("Dropped diagnostic events: ${r.droppedEvents}")
        appendLine()
        appendLine("Report ID: ${r.reportId}")
    }

    fun toJson(r: DiagnosticReport): String {
        val doc = linkedMapOf<String, Any?>(
            "schemaVersion" to 1,
            "reportId" to r.reportId,
            "createdAtEpochMs" to r.createdAtEpochMs,
            "gameId" to r.gameId,
            "packageVersion" to r.packageVersion,
            "runtimeVersion" to r.runtimeVersion,
            "sourceSha256" to r.sourceSha256,
            "device" to linkedMapOf(
                "model" to r.device.model,
                "androidVersion" to r.device.androidVersion,
                "abi" to r.device.abi,
            ),
            "machineResult" to linkedMapOf(
                "status" to r.status?.name,
                "fcv" to r.fcv?.name,
                "fcvChecks" to r.fcvChecks.map {
                    linkedMapOf(
                        "id" to it.id,
                        "mandatory" to it.mandatory,
                        "result" to it.result.name,
                        "reason" to it.reason,
                        "evidenceRef" to it.evidenceRef,
                    )
                },
            ),
            "xexReference" to r.xexReference?.let { x ->
                linkedMapOf(
                    "xex1" to linkedMapOf("length" to x.ids.xex1Length, "fingerprint" to x.ids.xex1Fingerprint, "comparison" to x.comparison?.xex1),
                    "xex2" to linkedMapOf("length" to x.ids.xex2Length, "fingerprint" to x.ids.xex2Fingerprint, "comparison" to x.comparison?.xex2),
                    "identical" to x.ids.identical,
                    "comparedSourceSha256" to x.comparison?.sourceSha256,
                )
            },
            "userAnnotation" to r.userAnnotation?.name,
            "events" to r.events.map {
                linkedMapOf(
                    "code" to it.event.code,
                    "severity" to it.event.severity.name,
                    "domain" to it.event.domain.name,
                    "module" to it.event.module,
                    "stage" to it.event.stage,
                    "route" to it.event.route.name,
                    "subject" to it.event.subject,
                    "message" to it.event.message,
                    "count" to it.count,
                )
            },
            "droppedEvents" to r.droppedEvents,
            "recovery" to linkedMapOf(
                "aot" to r.recovery.aot,
                "compatibilityLayer" to r.recovery.compatibilityLayer,
                "interpreter" to r.recovery.interpreter,
                "jit" to r.recovery.jit,
                "unresolved" to r.recovery.unresolved,
            ),
        )
        return JsonWriter.write(doc)
    }
}

/** Minimal dependency-free JSON writer (2-space indent). Supports Map, Iterable, String, Number, Boolean, Enum, null. */
object JsonWriter {
    fun write(value: Any?): String = StringBuilder().also { append(it, value, 0) }.append('\n').toString()

    private fun append(sb: StringBuilder, v: Any?, depth: Int) {
        when (v) {
            null -> sb.append("null")
            is String -> quote(sb, v)
            is Boolean -> sb.append(v.toString())
            is Int, is Long -> sb.append(v.toString())
            is Enum<*> -> quote(sb, v.name)
            is Map<*, *> -> {
                if (v.isEmpty()) { sb.append("{}"); return }
                sb.append("{\n")
                var first = true
                for ((k, x) in v) {
                    if (!first) sb.append(",\n")
                    first = false
                    indent(sb, depth + 1)
                    quote(sb, k.toString())
                    sb.append(": ")
                    append(sb, x, depth + 1)
                }
                sb.append('\n'); indent(sb, depth); sb.append('}')
            }
            is Iterable<*> -> {
                val items = v.toList()
                if (items.isEmpty()) { sb.append("[]"); return }
                sb.append("[\n")
                items.forEachIndexed { i, x ->
                    if (i > 0) sb.append(",\n")
                    indent(sb, depth + 1)
                    append(sb, x, depth + 1)
                }
                sb.append('\n'); indent(sb, depth); sb.append(']')
            }
            else -> throw IllegalArgumentException("Unsupported JSON value type: ${v::class.java.name}")
        }
    }

    private fun indent(sb: StringBuilder, depth: Int) {
        repeat(depth) { sb.append("  ") }
    }

    private fun quote(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c.code < 0x20 -> sb.append("\\u").append(c.code.toString(16).padStart(4, '0'))
                else -> sb.append(c)
            }
        }
        sb.append('"')
    }
}
