package dev.console2android.core.diag

/** Ordered by increasing importance (ordinal is used for comparison). */
enum class Severity { INFO, WARNING, ERROR, CRITICAL }

/** Failure classification. Do not route every problem to the CPU interpreter. */
enum class FailureDomain {
    INPUT_VALIDATION, CPU_SEMANTICS, MEMORY_MODEL, SYSTEM_API, GRAPHICS, AUDIO, ASSET_FORMAT, TIMING_SYNC, UNKNOWN
}

/** Route finally used (or attempted) for an execution unit; mirrors the recovery order in the master plan. */
enum class RecoveryRoute { NONE, AOT_NATIVE, ALTERNATIVE_LOWERING, COMPATIBILITY_LAYER, INTERPRETER, JIT, UNSUPPORTED }

/**
 * A structured diagnostic event. Messages must never contain personal data, secrets or game content;
 * use hashes / stable identifiers in [subject].
 */
data class DiagnosticEvent(
    val code: String,
    val severity: Severity,
    val domain: FailureDomain,
    val module: String,
    val stage: String,
    val message: String,
    val route: RecoveryRoute = RecoveryRoute.NONE,
    val subject: String? = null,
) {
    /** Events with the same key are deduplicated and counted. */
    val dedupKey: String get() = "$code|$module|$stage|${subject ?: ""}"
}

data class LoggedEvent(val event: DiagnosticEvent, val count: Int)

/**
 * Bounded, deduplicating event log.
 * - Repeated events only increment a counter (no unbounded growth).
 * - When full, a new event only gets in by evicting a strictly less severe one; otherwise it is dropped.
 * - Every dropped occurrence is counted and must be reported.
 */
class BoundedEventLog(private val maxDistinct: Int) {
    init {
        require(maxDistinct > 0) { "maxDistinct must be > 0" }
    }

    private class Counter(var event: DiagnosticEvent, var count: Int)

    private val entries = LinkedHashMap<String, Counter>()
    private var dropped = 0L

    @Synchronized
    fun record(e: DiagnosticEvent) {
        val existing = entries[e.dedupKey]
        if (existing != null) {
            if (existing.count < Int.MAX_VALUE) existing.count++
            if (e.severity > existing.event.severity) existing.event = e
            return
        }
        if (entries.size >= maxDistinct) {
            val victim = entries.values.minByOrNull { it.event.severity.ordinal }
            if (victim != null && victim.event.severity < e.severity) {
                entries.remove(victim.event.dedupKey)
                dropped += victim.count.toLong()
            } else {
                dropped++
                return
            }
        }
        entries[e.dedupKey] = Counter(e, 1)
    }

    /** Most severe first; insertion order is kept within a severity. */
    @Synchronized
    fun snapshot(): List<LoggedEvent> =
        entries.values.map { LoggedEvent(it.event, it.count) }.sortedByDescending { it.event.severity.ordinal }

    @Synchronized
    fun droppedCount(): Long = dropped
}
