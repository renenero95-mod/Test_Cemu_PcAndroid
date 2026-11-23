package dev.console2android.core

import dev.console2android.core.job.Checkpoint
import dev.console2android.core.job.CheckpointStatus
import dev.console2android.core.job.ConversionJob
import dev.console2android.core.job.ConversionMode
import dev.console2android.core.job.InvalidTransitionException
import dev.console2android.core.job.JobState
import dev.console2android.core.validation.FcvOverall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class JobTest {
    private val sha = "a".repeat(64)
    private fun job() = ConversionJob("j1", "fixture", sha, ConversionMode.STRICT)

    private fun atFinalValidation(): ConversionJob {
        var j = job()
        listOf(
            JobState.VALIDATING, JobState.ANALYZING, JobState.EXTRACTING, JobState.TRANSLATING,
            JobState.PACKAGING, JobState.FINAL_VALIDATION,
        ).forEach { j = j.transition(it) }
        return j
    }

    @Test fun happyPathReachesReady() {
        val j = atFinalValidation().complete(FcvOverall.PASS_WITH_WARNINGS)
        assertEquals(JobState.READY, j.state)
    }

    @Test fun fcvFailMakesJobFailedNotReady() {
        assertEquals(JobState.FAILED, atFinalValidation().complete(FcvOverall.FAIL).state)
    }

    @Test fun completeOnlyFromFinalValidation() {
        assertThrows(InvalidTransitionException::class.java) { job().complete(FcvOverall.PASS) }
    }

    @Test fun cannotSkipStages() {
        assertThrows(InvalidTransitionException::class.java) { job().transition(JobState.READY) }
        assertThrows(InvalidTransitionException::class.java) { job().transition(JobState.TRANSLATING) }
    }

    @Test fun recoveringLoopsBackToTranslating() {
        val j = atFinalValidation()
        var k = job()
        listOf(JobState.VALIDATING, JobState.ANALYZING, JobState.EXTRACTING, JobState.TRANSLATING,
            JobState.RECOVERING, JobState.TRANSLATING, JobState.PACKAGING).forEach { k = k.transition(it) }
        assertEquals(JobState.PACKAGING, k.state)
        assertEquals(JobState.FINAL_VALIDATION, j.state)
    }

    @Test fun terminalStatesHaveNoExit() {
        val ready = atFinalValidation().complete(FcvOverall.PASS)
        assertThrows(InvalidTransitionException::class.java) { ready.transition(JobState.FAILED) }
        assertThrows(InvalidTransitionException::class.java) { ready.pause() }
        val cancelled = job().cancel()
        assertThrows(InvalidTransitionException::class.java) { cancelled.transition(JobState.VALIDATING) }
    }

    @Test fun pauseAndResumeReturnToSameStage() {
        val j = job().transition(JobState.VALIDATING).transition(JobState.ANALYZING)
        val paused = j.pause()
        assertEquals(JobState.PAUSED, paused.state)
        val resumed = paused.resume()
        assertEquals(JobState.ANALYZING, resumed.state)
        assertNull(resumed.resumeState)
    }

    @Test fun userActionCanBeResumedOrCancelled() {
        val j = job().transition(JobState.VALIDATING).requestUserAction()
        assertEquals(JobState.NEEDS_USER_ACTION, j.state)
        assertEquals(JobState.VALIDATING, j.resume().state)
        assertEquals(JobState.CANCELLED, j.cancel().state)
    }

    @Test fun suspendedJobCannotAdvanceOrDoublePause() {
        val p = job().pause()
        assertThrows(InvalidTransitionException::class.java) { p.transition(JobState.VALIDATING) }
        assertThrows(InvalidTransitionException::class.java) { p.pause() }
        assertThrows(InvalidTransitionException::class.java) { job().resume() }
    }

    @Test fun pausedStatesCannotBeEnteredViaTransition() {
        assertThrows(InvalidTransitionException::class.java) { job().transition(JobState.PAUSED) }
    }

    private fun cp(status: CheckpointStatus, input: String = "in1", tool: String = "t1", out: String? = "out1") =
        Checkpoint(JobState.ANALYZING, input, out, tool, status)

    @Test fun checkpointReusableOnlyWhenVerifiedAndMatching() {
        val ok = cp(CheckpointStatus.COMPLETED_VERIFIED)
        assertTrue(ok.isReusable("in1", "t1"))
        assertTrue(!ok.isReusable("in2", "t1"))
        assertTrue(!ok.isReusable("in1", "t2"))
        assertTrue(!cp(CheckpointStatus.COMPLETED_UNVERIFIED).isReusable("in1", "t1"))
        assertTrue(!cp(CheckpointStatus.COMPLETED_VERIFIED, out = null).isReusable("in1", "t1"))
    }

    @Test fun failedStageDoesNotOverwriteVerifiedCheckpoint() {
        val verified = cp(CheckpointStatus.COMPLETED_VERIFIED)
        val j = job().withCheckpoint(verified).withCheckpoint(cp(CheckpointStatus.FAILED, out = null))
        assertEquals(listOf(verified), j.checkpoints)
        assertNotNull(j.reusableCheckpoint(JobState.ANALYZING, "in1", "t1"))
    }

    @Test fun newInputsReplaceOldCheckpoint() {
        val j = job().withCheckpoint(cp(CheckpointStatus.COMPLETED_VERIFIED))
            .withCheckpoint(cp(CheckpointStatus.FAILED, input = "in2", out = null))
        assertEquals(1, j.checkpoints.size)
        assertEquals("in2", j.checkpoints[0].inputSha256)
        assertNull(j.reusableCheckpoint(JobState.ANALYZING, "in1", "t1"))
    }
}
