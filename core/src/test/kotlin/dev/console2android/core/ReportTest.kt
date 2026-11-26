package dev.console2android.core

import dev.console2android.core.diag.DiagnosticEvent
import dev.console2android.core.diag.FailureDomain
import dev.console2android.core.diag.LoggedEvent
import dev.console2android.core.diag.RecoveryRoute
import dev.console2android.core.diag.Severity
import dev.console2android.core.report.DeviceInfo
import dev.console2android.core.report.DiagnosticReport
import dev.console2android.core.report.JsonWriter
import dev.console2android.core.report.RecoverySummary
import dev.console2android.core.report.ReportFormatter
import dev.console2android.core.report.UserAnnotation
import dev.console2android.core.validation.FcvOverall
import dev.console2android.core.validation.ResultStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportTest {
    private val warn = DiagnosticEvent(
        "GFX-001", Severity.WARNING, FailureDomain.GRAPHICS, "gpu", "translate",
        "Shader \"fog\" approximated", RecoveryRoute.ALTERNATIVE_LOWERING, "shader#12",
    )

    private fun report(annotation: UserAnnotation? = null, recovery: RecoverySummary = RecoverySummary()) = DiagnosticReport(
        reportId = "r-1", gameId = "FX1-demo", packageVersion = "1.0", runtimeVersion = "0.1.0",
        sourceSha256 = "ab".repeat(32), device = DeviceInfo("Pixel", "15", "arm64-v8a"),
        status = ResultStatus.PLAYABLE, fcv = FcvOverall.PASS_WITH_WARNINGS,
        events = listOf(LoggedEvent(warn, 3), LoggedEvent(warn.copy(code = "I", severity = Severity.INFO), 9)),
        droppedEvents = 4, recovery = recovery, userAnnotation = annotation, createdAtEpochMs = 1L,
    )

    @Test fun textShowsMeasuredAndNotMeasured() {
        val t = ReportFormatter.toText(report(recovery = RecoverySummary(aot = 7)))
        assertTrue(t.contains("AOT: 7"))
        assertTrue(t.contains("JIT: not measured"))
        assertFalse(t.contains("JIT: 0"))
    }

    @Test fun textListsOnlyWarningsAndAbove() {
        val t = ReportFormatter.toText(report())
        assertTrue(t.contains("[WARNING] GRAPHICS"))
        assertTrue(t.contains("(x3)"))
        assertFalse(t.contains("msg I"))
        assertTrue(t.contains("Warnings: 1"))
        assertTrue(t.contains("CRITICAL ERRORS: 0"))
        assertTrue(t.contains("Dropped diagnostic events: 4"))
        assertTrue(t.contains("Overall: PLAYABLE"))
        assertTrue(t.contains("FCV: PASS WITH WARNINGS"))
    }

    @Test fun annotationIsLabelledAsUserProvidedAndSeparateInJson() {
        val r = report(UserAnnotation.PLAYABLE_WITH_ISSUES)
        assertTrue(ReportFormatter.toText(r).contains("user-provided, not machine-verified"))
        val json = ReportFormatter.toJson(r)
        assertTrue(json.contains("\"userAnnotation\": \"PLAYABLE_WITH_ISSUES\""))
        assertTrue(json.contains("\"machineResult\": {"))
        assertFalse(ReportFormatter.toText(report()).contains("User annotation"))
    }

    @Test fun environmentReportHasNoStatus() {
        val r = DiagnosticReport("r", null, null, "none", null, DeviceInfo("m", "14", "arm64-v8a"), null, null, createdAtEpochMs = 0)
        val t = ReportFormatter.toText(r)
        assertTrue(t.contains("Overall: NOT APPLICABLE"))
        assertTrue(t.contains("(none — environment report)"))
        assertTrue(ReportFormatter.toJson(r).contains("\"status\": null"))
    }

    @Test fun jsonEscaping() {
        val s = JsonWriter.write(mapOf("k" to "a\"b\\c\nd\u0001"))
        assertEquals("{\n  \"k\": \"a\\\"b\\\\c\\nd\\u0001\"\n}\n", s)
    }

    @Test fun jsonEmptyContainersAndUnsupportedType() {
        assertEquals("{\n  \"a\": [],\n  \"b\": {}\n}\n", JsonWriter.write(linkedMapOf("a" to emptyList<Any>(), "b" to emptyMap<String, Any>())))
        assertThrows(IllegalArgumentException::class.java) { JsonWriter.write(1.5) }
    }

    @Test fun exportedMetadataIsDeclared() {
        assertTrue(ReportFormatter.EXPORTED_METADATA.isNotEmpty())
    }
}
