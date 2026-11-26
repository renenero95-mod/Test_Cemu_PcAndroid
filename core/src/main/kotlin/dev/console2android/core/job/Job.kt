package dev.console2android.core.job

import dev.console2android.core.diag.DiagnosticEvent
import dev.console2android.core.validation.FcvOverall
import dev.console2android.core.xex.XexReferenceSummary

enum class ConversionMode { STRICT, BALANCED, EXPERIMENTAL }

enum class JobState {
    QUEUED, VALIDATING, ANALYZING, EXTRACTING, TRANSLATING, RECOVERING, PACKAGING, FINAL_VALIDATION, READY,
    PAUSED, CANCELLED, FAILED, NEEDS_USER_ACTION;

    val isTerminal: Boolean get() = this == READY || this == CANCELLED || this == FAILED
    val isSuspended: Boolean get() = this == PAUSED || this == NEEDS_USER_ACTION

    companion object {
        /** Nominal order for display; RECOVERING is optional. */
        val PIPELINE: List<JobState> =
            listOf(QUEUED, VALIDATING, ANALYZING, EXTRACTING, TRANSLATING, RECOVERING, PACKAGING, FINAL_VALIDATION, READY)

        internal val FORWARD: Map<JobState, Set<JobState>> = mapOf(
            QUEUED to setOf(VALIDATING),
            VALIDATING to setOf(ANALYZING),
            ANALYZING to setOf(EXTRACTING),
            EXTRACTING to setOf(TRANSLATING),
            TRANSLATING to setOf(RECOVERING, PACKAGING),
            RECOVERING to setOf(TRANSLATING, PACKAGING),
            PACKAGING to setOf(FINAL_VALIDATION),
            FINAL_VALIDATION to setOf(READY),
        )
    }
}

class InvalidTransitionException(val from: JobState, val to: JobState, detail: String = "") :
    IllegalStateException("Invalid job transition $from -> $to" + if (detail.isEmpty()) "" else ": $detail")

enum class CheckpointStatus { COMPLETED_VERIFIED, COMPLETED_UNVERIFIED, FAILED }

/** A stage checkpoint. Reusable only if inputs and tool version still match and it was verified. */
data class Checkpoint(
    val stage: JobState,
    val inputSha256: String,
    val outputSha256: String?,
    val toolVersion: String,
    val status: CheckpointStatus,
) {
    fun isReusable(currentInputSha256: String, currentToolVersion: String): Boolean =
        status == CheckpointStatus.COMPLETED_VERIFIED && outputSha256 != null &&
            inputSha256 == currentInputSha256 && toolVersion == currentToolVersion
}

/** Typed stage result; `Unsupported` and `Partial` are never reported as success. */
sealed interface StageResult {
    data class Success(val outputSha256: String) : StageResult
    data class Partial(val outputSha256: String, val issues: List<DiagnosticEvent>) : StageResult
    data class Unsupported(val issues: List<DiagnosticEvent>) : StageResult
    data class Failure(val issue: DiagnosticEvent) : StageResult
}

/** Immutable job model; every operation returns a new instance. */
data class ConversionJob(
    val id: String,
    val backend: String,
    val sourceSha256: String,
    val mode: ConversionMode,
    val state: JobState = JobState.QUEUED,
    val resumeState: JobState? = null,
    val checkpoints: List<Checkpoint> = emptyList(),
    val toolchain: Map<String, String> = emptyMap(),
    /** User-supplied XEX1/XEX2 reference identifiers (fingerprints only) and their static comparison. Metadata: never a cache-key input. */
    val reference: XexReferenceSummary? = null,
) {
    fun withReference(r: XexReferenceSummary?): ConversionJob = copy(reference = r)

    fun transition(to: JobState): ConversionJob {
        if (to == JobState.PAUSED || to == JobState.NEEDS_USER_ACTION) {
            throw InvalidTransitionException(state, to, "use pause()/requestUserAction()")
        }
        val allowed: Set<JobState> = when {
            state.isTerminal -> emptySet()
            state.isSuspended -> setOf(JobState.CANCELLED, JobState.FAILED) // use resume() to continue
            else -> JobState.FORWARD[state].orEmpty() + setOf(JobState.CANCELLED, JobState.FAILED)
        }
        if (to !in allowed) throw InvalidTransitionException(state, to)
        return copy(state = to, resumeState = null)
    }

    fun pause(): ConversionJob = suspendAs(JobState.PAUSED)

    fun requestUserAction(): ConversionJob = suspendAs(JobState.NEEDS_USER_ACTION)

    private fun suspendAs(target: JobState): ConversionJob {
        if (state.isTerminal || state.isSuspended) throw InvalidTransitionException(state, target)
        return copy(state = target, resumeState = state)
    }

    fun resume(): ConversionJob {
        val back = resumeState
        if (!state.isSuspended || back == null) throw InvalidTransitionException(state, state, "job is not suspended")
        return copy(state = back, resumeState = null)
    }

    fun cancel(): ConversionJob = transition(JobState.CANCELLED)

    fun fail(): ConversionJob = transition(JobState.FAILED)

    /** The job can only become READY if the independent validator did not FAIL. */
    fun complete(fcv: FcvOverall): ConversionJob {
        if (state != JobState.FINAL_VALIDATION) throw InvalidTransitionException(state, JobState.READY)
        return if (fcv == FcvOverall.FAIL) transition(JobState.FAILED) else transition(JobState.READY)
    }

    /**
     * Records a checkpoint. A verified checkpoint for the same inputs/tool version is never replaced by a
     * non-verified one (a failed stage must not corrupt completed work).
     */
    fun withCheckpoint(cp: Checkpoint): ConversionJob {
        val existing = checkpoints.firstOrNull { it.stage == cp.stage }
        if (existing != null && existing.status == CheckpointStatus.COMPLETED_VERIFIED &&
            cp.status != CheckpointStatus.COMPLETED_VERIFIED &&
            existing.inputSha256 == cp.inputSha256 && existing.toolVersion == cp.toolVersion
        ) {
            return this
        }
        return copy(checkpoints = checkpoints.filterNot { it.stage == cp.stage } + cp)
    }

    fun reusableCheckpoint(stage: JobState, currentInputSha256: String, currentToolVersion: String): Checkpoint? =
        checkpoints.firstOrNull { it.stage == stage && it.isReusable(currentInputSha256, currentToolVersion) }
}
