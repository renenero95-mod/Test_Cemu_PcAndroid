package dev.console2android.core

import dev.console2android.core.diag.BoundedEventLog
import dev.console2android.core.diag.DiagnosticEvent
import dev.console2android.core.diag.FailureDomain
import dev.console2android.core.diag.Severity
import dev.console2android.core.job.ConversionMode
import dev.console2android.core.validation.CheckResult
import dev.console2android.core.validation.Fcv
import dev.console2android.core.validation.FcvCheck
import dev.console2android.core.validation.FcvOverall
import dev.console2android.core.validation.ResultStatus
import dev.console2android.core.validation.StatusInput
import dev.console2android.core.validation.StatusPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

class DiagValidationTest {
    private fun ev(code: String, sev: Severity = Severity.WARNING, subject: String? = null) =
        DiagnosticEvent(code, sev, FailureDomain.GRAPHICS, "gpu", "translate", "msg $code", subject = subject)

    @Test fun duplicatesAreCountedNotStored() {
        val log = BoundedEventLog(10)
        repeat(1000) { log.record(ev("A")) }
        val s = log.snapshot()
        assertEquals(1, s.size)
        assertEquals(1000, s[0].count)
        assertEquals(0L, log.droppedCount())
    }

    @Test fun differentSubjectIsDifferentEvent() {
        val log = BoundedEventLog(10)
        log.record(ev("A", subject = "x")); log.record(ev("A", subject = "y"))
        assertEquals(2, log.snapshot().size)
    }

    @Test fun fullLogDropsLessSevereAndCounts() {
        val log = BoundedEventLog(2)
        log.record(ev("A", Severity.WARNING)); log.record(ev("B", Severity.WARNING))
        log.record(ev("C", Severity.WARNING)) // same severity, no eviction
        assertEquals(2, log.snapshot().size)
        assertEquals(1L, log.droppedCount())
    }

    @Test fun criticalEvictsLessSevereEvent() {
        val log = BoundedEventLog(2)
        log.record(ev("A", Severity.INFO)); log.record(ev("A", Severity.INFO)); log.record(ev("B", Severity.WARNING))
        log.record(ev("C", Severity.CRITICAL))
        val s = log.snapshot()
        assertEquals(listOf("C", "B"), s.map { it.event.code })
        assertEquals(2L, log.droppedCount()) // both occurrences of the evicted INFO event
    }

    @Test fun severityEscalatesOnDuplicate() {
        val log = BoundedEventLog(5)
        log.record(ev("A", Severity.WARNING)); log.record(ev("A", Severity.ERROR))
        assertEquals(Severity.ERROR, log.snapshot()[0].event.severity)
    }

    @Test fun snapshotIsMostSevereFirst() {
        val log = BoundedEventLog(5)
        log.record(ev("W", Severity.WARNING)); log.record(ev("C", Severity.CRITICAL)); log.record(ev("E", Severity.ERROR))
        assertEquals(listOf("C", "E", "W"), log.snapshot().map { it.event.code })
    }

    private fun chk(r: CheckResult, mandatory: Boolean = true) = FcvCheck("c", mandatory, r, "why")

    @Test fun fcvRules() {
        assertEquals(FcvOverall.FAIL, Fcv.overall(emptyList()))
        assertEquals(FcvOverall.FAIL, Fcv.overall(listOf(chk(CheckResult.NOT_APPLICABLE))))
        assertEquals(FcvOverall.PASS, Fcv.overall(listOf(chk(CheckResult.PASS), chk(CheckResult.NOT_APPLICABLE))))
        assertEquals(FcvOverall.PASS_WITH_WARNINGS, Fcv.overall(listOf(chk(CheckResult.PASS), chk(CheckResult.WARN))))
        assertEquals(FcvOverall.PASS_WITH_WARNINGS, Fcv.overall(listOf(chk(CheckResult.PASS), chk(CheckResult.FAIL, false))))
        assertEquals(FcvOverall.FAIL, Fcv.overall(listOf(chk(CheckResult.PASS), chk(CheckResult.FAIL, true))))
    }

    private fun input(
        mode: ConversionMode = ConversionMode.STRICT, fcv: FcvOverall = FcvOverall.PASS, launch: Boolean = true,
        critical: Int = 0, unverified: Int = 0, coverage: Boolean = true, smoke: Boolean = true,
    ) = StatusInput(mode, fcv, launch, critical, unverified, coverage, smoke)

    @Test fun statusPolicy() {
        assertEquals(ResultStatus.STABLE, StatusPolicy.classify(input()))
        assertEquals(ResultStatus.STABLE, StatusPolicy.classify(input(mode = ConversionMode.BALANCED)))
        assertEquals(ResultStatus.FAILED, StatusPolicy.classify(input(fcv = FcvOverall.FAIL)))
        assertEquals(ResultStatus.FAILED, StatusPolicy.classify(input(launch = false)))
        assertEquals(ResultStatus.EXPERIMENTAL, StatusPolicy.classify(input(mode = ConversionMode.EXPERIMENTAL)))
        assertEquals(ResultStatus.EXPERIMENTAL, StatusPolicy.classify(input(critical = 1)))
        assertEquals(ResultStatus.PLAYABLE, StatusPolicy.classify(input(unverified = 2)))
        assertEquals(ResultStatus.PLAYABLE, StatusPolicy.classify(input(fcv = FcvOverall.PASS_WITH_WARNINGS)))
        assertEquals(ResultStatus.PLAYABLE, StatusPolicy.classify(input(coverage = false)))
        assertEquals(ResultStatus.EXPERIMENTAL, StatusPolicy.classify(input(smoke = false)))
    }
}
