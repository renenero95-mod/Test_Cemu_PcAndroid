package dev.console2android.core.validation

import dev.console2android.core.job.ConversionMode

enum class CheckResult { PASS, WARN, FAIL, NOT_APPLICABLE }

/** One independent Final Conversion Validator check. [evidenceRef] points to the evidence (file/hash/log id). */
data class FcvCheck(
    val id: String,
    val mandatory: Boolean,
    val result: CheckResult,
    val reason: String,
    val evidenceRef: String? = null,
)

enum class FcvOverall(val label: String) {
    PASS("PASS"),
    PASS_WITH_WARNINGS("PASS WITH WARNINGS"),
    FAIL("FAIL"),
}

object Fcv {
    /**
     * - No applicable check at all => FAIL (absence of evidence is not a pass).
     * - Any mandatory FAIL => FAIL.
     * - Any WARN, or a non-mandatory FAIL => PASS WITH WARNINGS.
     * - Otherwise PASS.
     */
    fun overall(checks: List<FcvCheck>): FcvOverall {
        val applicable = checks.filter { it.result != CheckResult.NOT_APPLICABLE }
        if (applicable.isEmpty()) return FcvOverall.FAIL
        if (applicable.any { it.mandatory && it.result == CheckResult.FAIL }) return FcvOverall.FAIL
        if (applicable.any { it.result == CheckResult.WARN || it.result == CheckResult.FAIL }) {
            return FcvOverall.PASS_WITH_WARNINGS
        }
        return FcvOverall.PASS
    }
}

enum class ResultStatus(val label: String) {
    STABLE("STABLE"),
    PLAYABLE("PLAYABLE"),
    EXPERIMENTAL("EXPERIMENTAL"),
    FAILED("FAILED"),
}

/**
 * Inputs of the machine-derived status. Deliberately contains NO conversion percentage and NO user
 * annotation: neither may decide the status.
 */
data class StatusInput(
    val mode: ConversionMode,
    val fcv: FcvOverall,
    val launchGatePassed: Boolean,
    val criticalIssues: Int,
    val unverifiedOrApproximatePaths: Int,
    val coverageDocumented: Boolean,
    val automatedSmokePassed: Boolean,
)

object StatusPolicy {
    fun classify(i: StatusInput): ResultStatus = when {
        i.fcv == FcvOverall.FAIL || !i.launchGatePassed -> ResultStatus.FAILED
        i.mode == ConversionMode.EXPERIMENTAL -> ResultStatus.EXPERIMENTAL // never Stable
        i.criticalIssues > 0 -> ResultStatus.EXPERIMENTAL
        i.fcv == FcvOverall.PASS && i.unverifiedOrApproximatePaths == 0 &&
            i.coverageDocumented && i.automatedSmokePassed -> ResultStatus.STABLE
        i.automatedSmokePassed -> ResultStatus.PLAYABLE
        else -> ResultStatus.EXPERIMENTAL
    }
}
