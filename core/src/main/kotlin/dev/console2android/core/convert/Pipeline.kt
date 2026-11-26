package dev.console2android.core.convert

import dev.console2android.core.diag.BoundedEventLog
import dev.console2android.core.diag.DiagnosticEvent
import dev.console2android.core.diag.FailureDomain
import dev.console2android.core.diag.LoggedEvent
import dev.console2android.core.diag.Severity
import dev.console2android.core.job.Checkpoint
import dev.console2android.core.job.CheckpointStatus
import dev.console2android.core.job.ConversionJob
import dev.console2android.core.job.ConversionMode
import dev.console2android.core.job.InvalidTransitionException
import dev.console2android.core.job.JobState
import dev.console2android.core.job.StageResult
import dev.console2android.core.library.JobStore
import dev.console2android.core.library.LibraryEntry
import dev.console2android.core.library.SourceLibrary
import dev.console2android.core.report.DeviceInfo
import dev.console2android.core.report.DiagnosticReport
import dev.console2android.core.report.RecoverySummary
import dev.console2android.core.report.ReportFormatter
import dev.console2android.core.validation.FcvOverall
import java.io.File

/** Mutable per-run scratch shared by the stages of one pipeline run. */
class PipelineContext(
    val job: ConversionJob,
    val source: LibraryEntry,
    val library: SourceLibrary,
    val packages: PackageStore,
    val workDir: File,
    val nowMs: () -> Long,
) {
    val events = ArrayList<DiagnosticEvent>()
    var validation: ValidationOutcome? = null
    var packageId: String? = null
}

/**
 * One pipeline stage. [execute] receives the previous stage's output hash (or the source hash) and must be deterministic
 * for the same input and [toolVersion]. Outputs that live on disk must be re-checkable via [outputStillValid] so a resumed
 * job never trusts a stale or damaged checkpoint.
 */
interface Stage {
    val state: JobState
    val toolVersion: String
    /** Stages that must always run (the independent validator) are not checkpoint-reusable. */
    val reusable: Boolean get() = true
    fun outputStillValid(ctx: PipelineContext, outputSha256: String): Boolean = true
    fun execute(ctx: PipelineContext, input: String): StageResult
}

class PipelineOutcome(
    val job: ConversionJob,
    val events: List<LoggedEvent>,
    val droppedEvents: Long,
    val validation: ValidationOutcome?,
    val packageId: String?,
    val report: DiagnosticReport,
)

/**
 * Resumable, checkpointed conversion runner (master plan §5.2/§7). The job is persisted after every transition, so a
 * process death leaves a job that [run] can continue. Rules enforced here, not in the stages:
 * - a failed stage never overwrites a verified checkpoint (see [ConversionJob.withCheckpoint]);
 * - `Unsupported`/`Failure` end the job as FAILED with structured events; `Partial` is accepted only outside STRICT mode;
 * - READY is only reachable through the Final Conversion Validator (`ConversionJob.complete`);
 * - exceptions escaping a stage are converted into a FAILED job with a class-name-only event (no paths, no content).
 */
class JobRunner(
    private val library: SourceLibrary,
    private val packages: PackageStore,
    private val store: JobStore,
    private val device: DeviceInfo,
    private val stages: List<Stage> = Fx1Pipeline.stages(),
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val order = listOf(
        JobState.QUEUED, JobState.VALIDATING, JobState.ANALYZING, JobState.EXTRACTING, JobState.TRANSLATING,
        JobState.RECOVERING, JobState.PACKAGING, JobState.FINAL_VALIDATION, JobState.READY,
    )

    private fun ev(code: String, sev: Severity, msg: String, subject: String? = null) =
        DiagnosticEvent(code, sev, FailureDomain.UNKNOWN, "pipeline", "run", msg, subject = subject)

    fun run(
        start: ConversionJob,
        cancelled: () -> Boolean = { false },
        pauseRequested: () -> Boolean = { false },
    ): PipelineOutcome {
        val log = BoundedEventLog(128)
        var j = start
        var ctxRef: PipelineContext? = null

        fun finish(): PipelineOutcome {
            store.save(j)
            val c = ctxRef
            val v = c?.validation
            val manifest = c?.packageId?.let { packages.get(it)?.manifest }
            val report = DiagnosticReport(
                reportId = j.id, gameId = "sha256:" + j.sourceSha256.take(16), packageVersion = if (manifest != null) ToolVersions.PACKAGE_FORMAT else null,
                runtimeVersion = ToolVersions.RUNTIME, sourceSha256 = j.sourceSha256, device = device,
                status = v?.status ?: if (j.state == JobState.FAILED) dev.console2android.core.validation.ResultStatus.FAILED else null,
                fcv = v?.fcv, fcvChecks = v?.checks ?: emptyList(), events = log.snapshot(), droppedEvents = log.droppedCount(),
                recovery = RecoverySummary(
                    aot = manifest?.get("instructions")?.toIntOrNull(), interpreter = v?.fallbackSteps?.toInt(),
                    unresolved = v?.unsupportedInstructions,
                ),
                createdAtEpochMs = clock(), xexReference = j.reference,
            )
            runCatching {
                val dir = store.workDir(j.id)
                dir.resolve("report.txt").writeText(ReportFormatter.toText(report), Charsets.UTF_8)
                dir.resolve("report.json").writeText(ReportFormatter.toJson(report), Charsets.UTF_8)
            }
            return PipelineOutcome(j, log.snapshot(), log.droppedCount(), v, c?.packageId, report)
        }

        fun fail(e: DiagnosticEvent): PipelineOutcome {
            log.record(e)
            if (!j.state.isTerminal) j = j.fail()
            return finish()
        }

        if (j.state.isTerminal) return finish()
        if (j.state.isSuspended) j = j.resume()

        val entry = library.get(j.sourceSha256)
            ?: return fail(ev("SOURCE-MISSING", Severity.ERROR, "The source is no longer in the library.", j.sourceSha256.take(16)))
        val ctx = PipelineContext(j, entry, library, packages, store.workDir(j.id), clock)
        ctxRef = ctx
        var prev = j.sourceSha256

        for (stage in stages) {
            if (order.indexOf(j.state) > order.indexOf(stage.state)) {
                // Completed in an earlier run: its checkpoint supplies the input of the next stage.
                val cp = j.checkpoints.firstOrNull { it.stage == stage.state && it.status != CheckpointStatus.FAILED && it.outputSha256 != null }
                    ?: return fail(ev("CHECKPOINT-MISSING", Severity.ERROR, "Checkpoint for ${stage.state} is missing; start a new job.", stage.state.name))
                if (stage.reusable && !stage.outputStillValid(ctx, cp.outputSha256!!)) {
                    return fail(ev("CHECKPOINT-STALE", Severity.ERROR, "Stored output of ${stage.state} is missing or changed; start a new job.", stage.state.name))
                }
                prev = cp.outputSha256!!
                continue
            }
            try {
                if (j.state != stage.state) { j = j.transition(stage.state); store.save(j) }
            } catch (e: InvalidTransitionException) {
                return fail(ev("BAD-TRANSITION", Severity.ERROR, "Job cannot enter ${stage.state} from ${j.state}.", stage.state.name))
            }
            if (cancelled()) { j = j.cancel(); log.record(ev("JOB-CANCELLED", Severity.INFO, "Cancelled before ${stage.state}.")); return finish() }
            if (pauseRequested()) { j = j.pause(); log.record(ev("JOB-PAUSED", Severity.INFO, "Paused before ${stage.state}; it can be resumed.")); return finish() }

            val input = prev
            val ctxNow = ctx
            val reuse = if (stage.reusable) j.reusableCheckpoint(stage.state, input, stage.toolVersion) else null
            if (reuse != null && stage.outputStillValid(ctxNow, reuse.outputSha256!!)) {
                log.record(ev("CHECKPOINT-REUSED", Severity.INFO, "${stage.state} reused a verified checkpoint.", stage.state.name))
                prev = reuse.outputSha256
                continue
            }

            val result = try {
                stage.execute(ctxNow, input)
            } catch (e: Exception) {
                StageResult.Failure(ev("STAGE-EXCEPTION", Severity.CRITICAL, "${stage.state} failed unexpectedly (${e.javaClass.simpleName}).", stage.state.name))
            }
            ctxNow.events.forEach(log::record); ctxNow.events.clear()

            when (result) {
                is StageResult.Success -> {
                    j = j.withCheckpoint(Checkpoint(stage.state, input, result.outputSha256, stage.toolVersion, CheckpointStatus.COMPLETED_VERIFIED))
                    prev = result.outputSha256
                }
                is StageResult.Partial -> {
                    result.issues.forEach(log::record)
                    if (j.mode == ConversionMode.STRICT) {
                        j = j.withCheckpoint(Checkpoint(stage.state, input, null, stage.toolVersion, CheckpointStatus.FAILED))
                        return fail(ev("STRICT-PARTIAL", Severity.ERROR, "${stage.state} produced a partial result, which Strict mode does not accept.", stage.state.name))
                    }
                    j = j.withCheckpoint(Checkpoint(stage.state, input, result.outputSha256, stage.toolVersion, CheckpointStatus.COMPLETED_UNVERIFIED))
                    prev = result.outputSha256
                }
                is StageResult.Unsupported -> {
                    result.issues.forEach(log::record)
                    j = j.withCheckpoint(Checkpoint(stage.state, input, null, stage.toolVersion, CheckpointStatus.FAILED))
                    return fail(ev("STAGE-UNSUPPORTED", Severity.ERROR, "${stage.state} cannot handle this input; see the events above.", stage.state.name))
                }
                is StageResult.Failure -> {
                    j = j.withCheckpoint(Checkpoint(stage.state, input, null, stage.toolVersion, CheckpointStatus.FAILED))
                    return fail(result.issue)
                }
            }
            store.save(j)
        }

        if (j.state == JobState.FINAL_VALIDATION) {
            val fcv = ctx.validation?.fcv ?: FcvOverall.FAIL
            j = j.complete(fcv)
            if (j.state == JobState.FAILED) log.record(ev("FCV-FAILED", Severity.ERROR, "The Final Conversion Validator failed; no package is offered."))
        }
        return finish()
    }
}
