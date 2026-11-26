package dev.console2android.core.xex

import dev.console2android.core.diag.DiagnosticEvent
import dev.console2android.core.diag.FailureDomain
import dev.console2android.core.diag.Severity
import dev.console2android.core.validation.CheckResult
import dev.console2android.core.validation.FcvCheck

enum class IdComparison { MATCHES_SOURCE_SHA256, DIFFERS_FROM_SOURCE_SHA256, NOT_COMPARABLE }

data class XexComparison(val xex1: IdComparison, val xex2: IdComparison, val sourceSha256: String)

/** Report-safe view: fingerprints/lengths plus the comparison outcome (if a source file was analysed). */
data class XexReferenceSummary(val ids: XexIdentifierSummary, val comparison: XexComparison?)

/**
 * Compares the user-supplied identifiers with facts obtained by static analysis. Only a SHA-256 value
 * (64 hex chars, optionally separated by `:` or `-`) is comparable with the source SHA-256; any other
 * shape is reported honestly as [IdComparison.NOT_COMPARABLE] (never as a match or as a mismatch).
 */
object XexReferenceCheck {
    private val HEX64 = Regex("^[0-9a-f]{64}$")

    private fun one(value: String, sourceSha256: String): IdComparison {
        val norm = value.replace(":", "").replace("-", "").lowercase()
        if (!HEX64.matches(norm)) return IdComparison.NOT_COMPARABLE
        return if (norm == sourceSha256.lowercase()) IdComparison.MATCHES_SOURCE_SHA256 else IdComparison.DIFFERS_FROM_SOURCE_SHA256
    }

    fun compare(ids: XexIdentifiers, sourceSha256: String): XexComparison =
        XexComparison(one(ids.xex1, sourceSha256), one(ids.xex2, sourceSha256), sourceSha256)

    fun summarize(ids: XexIdentifiers, sourceSha256: String?): XexReferenceSummary =
        XexReferenceSummary(ids.summary(), sourceSha256?.let { compare(ids, it) })

    /** Non-mandatory FCV checks: a mismatch yields PASS WITH WARNINGS, never a silent pass. */
    fun fcvChecks(c: XexComparison): List<FcvCheck> = listOf(
        check("xex1-reference", c.xex1, c.sourceSha256),
        check("xex2-reference", c.xex2, c.sourceSha256),
    )

    private fun check(id: String, r: IdComparison, sha: String) = when (r) {
        IdComparison.MATCHES_SOURCE_SHA256 ->
            FcvCheck(id, false, CheckResult.PASS, "Reference identifier equals the source SHA-256.", sha)
        IdComparison.DIFFERS_FROM_SOURCE_SHA256 ->
            FcvCheck(id, false, CheckResult.FAIL, "Reference identifier looks like a SHA-256 but differs from the source SHA-256.", sha)
        IdComparison.NOT_COMPARABLE ->
            FcvCheck(id, false, CheckResult.NOT_APPLICABLE, "Reference identifier is not a SHA-256; no static comparison possible.", sha)
    }

    fun events(c: XexComparison): List<DiagnosticEvent> = buildList {
        fun add(key: String, r: IdComparison) {
            when (r) {
                IdComparison.DIFFERS_FROM_SOURCE_SHA256 -> add(
                    DiagnosticEvent("XEX-REF-MISMATCH", Severity.WARNING, FailureDomain.INPUT_VALIDATION, "xex-reference", "validate",
                        "$key differs from the SHA-256 of the analysed source.", subject = key),
                )
                IdComparison.NOT_COMPARABLE -> add(
                    DiagnosticEvent("XEX-REF-NOT-COMPARABLE", Severity.INFO, FailureDomain.INPUT_VALIDATION, "xex-reference", "validate",
                        "$key is not a SHA-256; not compared.", subject = key),
                )
                IdComparison.MATCHES_SOURCE_SHA256 -> Unit
            }
        }
        add(XexIdentifierParser.KEY_1, c.xex1)
        add(XexIdentifierParser.KEY_2, c.xex2)
    }
}
