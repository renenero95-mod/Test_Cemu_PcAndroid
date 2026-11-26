package dev.console2android.core.library

import dev.console2android.core.job.Checkpoint
import dev.console2android.core.job.CheckpointStatus
import dev.console2android.core.job.ConversionJob
import dev.console2android.core.job.ConversionMode
import dev.console2android.core.job.JobState
import dev.console2android.core.xex.IdComparison
import dev.console2android.core.xex.XexComparison
import dev.console2android.core.xex.XexIdentifierSummary
import dev.console2android.core.xex.XexIdentifiers
import dev.console2android.core.xex.XexReferenceCheck
import dev.console2android.core.xex.XexReferenceSummary
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.UUID

/** Creates jobs from library entries. The backend is not chosen yet (open decision D2), so jobs honestly say so. */
object JobFactory {
    const val BACKEND_UNSELECTED = "unselected"

    fun create(
        entry: LibraryEntry,
        mode: ConversionMode,
        ids: XexIdentifiers?,
        idGen: () -> String = { UUID.randomUUID().toString() },
    ): ConversionJob = ConversionJob(
        id = idGen(),
        backend = BACKEND_UNSELECTED,
        sourceSha256 = entry.sha256,
        mode = mode,
        reference = ids?.let { XexReferenceCheck.summarize(it, entry.sha256) },
    )
}

/**
 * Persists jobs as one `java.util.Properties` file each under `<root>/jobs/`, written atomically. Raw XEX values are
 * never stored here (the job only holds fingerprints). Corrupt files are skipped on load.
 */
class JobStore(private val root: File) {
    private val dir = File(root, "jobs").also { it.mkdirs(); check(it.isDirectory) { "cannot create jobs directory" } }

    fun save(job: ConversionJob) {
        require(ID.matches(job.id)) { "invalid job id" }
        val p = Properties()
        p["id"] = job.id; p["backend"] = job.backend; p["source"] = job.sourceSha256
        p["mode"] = job.mode.name; p["state"] = job.state.name
        job.resumeState?.let { p["resume"] = it.name }
        job.toolchain.entries.sortedBy { it.key }.forEachIndexed { i, (k, v) -> p["tc.$i.k"] = k; p["tc.$i.v"] = v }
        job.checkpoints.forEachIndexed { i, c ->
            p["cp.$i.stage"] = c.stage.name; p["cp.$i.in"] = c.inputSha256; p["cp.$i.tool"] = c.toolVersion; p["cp.$i.status"] = c.status.name
            c.outputSha256?.let { p["cp.$i.out"] = it }
        }
        job.reference?.let { r ->
            p["ref.l1"] = r.ids.xex1Length.toString(); p["ref.f1"] = r.ids.xex1Fingerprint
            p["ref.l2"] = r.ids.xex2Length.toString(); p["ref.f2"] = r.ids.xex2Fingerprint
            p["ref.same"] = r.ids.identical.toString()
            r.comparison?.let { c -> p["ref.c1"] = c.xex1.name; p["ref.c2"] = c.xex2.name; p["ref.sha"] = c.sourceSha256 }
        }
        val tmp = File(dir, "${job.id}.tmp")
        try {
            tmp.writer(StandardCharsets.UTF_8).use { p.store(it, null) }
            val dest = File(dir, "${job.id}.properties")
            try {
                Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            tmp.delete()
        }
    }

    fun load(id: String): ConversionJob? = if (!ID.matches(id)) null else read(File(dir, "$id.properties"))

    /** Jobs ordered by id for determinism; unreadable files are ignored. */
    fun list(): List<ConversionJob> =
        (dir.listFiles() ?: emptyArray()).filter { it.name.endsWith(".properties") }.mapNotNull { read(it) }.sortedBy { it.id }

    /** Per-job scratch directory (stage outputs, reports): `<root>/work/<id>/`. Created on demand. */
    fun workDir(id: String): File {
        require(ID.matches(id)) { "invalid job id" }
        return File(root, "work/$id").also { it.mkdirs() }
    }

    fun delete(id: String): Boolean {
        if (!ID.matches(id)) return false
        File(root, "work/$id").deleteRecursively()
        return File(dir, "$id.properties").let { !it.exists() || it.delete() }
    }

    private fun read(f: File): ConversionJob? {
        if (!f.isFile) return null
        return try {
            val p = Properties()
            f.reader(StandardCharsets.UTF_8).use { p.load(it) }
            fun req(k: String) = p.getProperty(k) ?: throw IllegalArgumentException(k)
            val id = req("id")
            require(ID.matches(id) && "$id.properties" == f.name)
            val checkpoints = generateSequence(0) { it + 1 }.takeWhile { p.containsKey("cp.$it.stage") }.map { i ->
                Checkpoint(
                    JobState.valueOf(req("cp.$i.stage")), req("cp.$i.in"), p.getProperty("cp.$i.out"), req("cp.$i.tool"),
                    CheckpointStatus.valueOf(req("cp.$i.status")),
                )
            }.toList()
            val toolchain = generateSequence(0) { it + 1 }.takeWhile { p.containsKey("tc.$it.k") }
                .associate { req("tc.$it.k") to req("tc.$it.v") }
            val reference = if (!p.containsKey("ref.f1")) null else XexReferenceSummary(
                XexIdentifierSummary(req("ref.l1").toInt(), req("ref.f1"), req("ref.l2").toInt(), req("ref.f2"), req("ref.same").toBoolean()),
                if (p.containsKey("ref.c1")) XexComparison(IdComparison.valueOf(req("ref.c1")), IdComparison.valueOf(req("ref.c2")), req("ref.sha")) else null,
            )
            ConversionJob(
                id = id, backend = req("backend"), sourceSha256 = req("source"), mode = ConversionMode.valueOf(req("mode")),
                state = JobState.valueOf(req("state")), resumeState = p.getProperty("resume")?.let { JobState.valueOf(it) },
                checkpoints = checkpoints, toolchain = toolchain, reference = reference,
            )
        } catch (e: IOException) {
            null
        } catch (e: RuntimeException) { // IllegalArgument/NumberFormat/IllegalState: corrupt file
            null
        }
    }

    private companion object { val ID = Regex("^[A-Za-z0-9-]{1,64}$") }
}
