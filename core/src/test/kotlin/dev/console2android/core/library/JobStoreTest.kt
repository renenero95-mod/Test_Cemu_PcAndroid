package dev.console2android.core.library

import dev.console2android.core.job.Checkpoint
import dev.console2android.core.job.CheckpointStatus
import dev.console2android.core.job.ConversionMode
import dev.console2android.core.job.JobState
import dev.console2android.core.xex.XexIdentifiers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class JobStoreTest {
    private lateinit var root: File
    @Before fun setUp() { root = Files.createTempDirectory("c2a-jobs").toFile() }
    @After fun tearDown() { root.deleteRecursively() }

    private val entry = LibraryEntry("a".repeat(64), "g.xex", 10, 1, SourceKind.XEX2, "n")

    @Test fun factoryCreatesQueuedJobWithUnselectedBackendAndReference() {
        val ids = XexIdentifiers("a".repeat(64), "other")
        val j = JobFactory.create(entry, ConversionMode.STRICT, ids) { "job-1" }
        assertEquals("job-1", j.id)
        assertEquals(JobState.QUEUED, j.state)
        assertEquals(JobFactory.BACKEND_UNSELECTED, j.backend)
        assertEquals(entry.sha256, j.sourceSha256)
        assertNotNull(j.reference!!.comparison)
        assertNull(JobFactory.create(entry, ConversionMode.BALANCED, null).reference)
    }

    @Test fun roundTripsFullJob() {
        val ids = XexIdentifiers("a".repeat(64), "zzz")
        val cp = Checkpoint(JobState.VALIDATING, "in", "out", "t1", CheckpointStatus.COMPLETED_VERIFIED)
        val cp2 = Checkpoint(JobState.ANALYZING, "in2", null, "t1", CheckpointStatus.FAILED)
        val j = JobFactory.create(entry, ConversionMode.EXPERIMENTAL, ids) { "j-2" }
            .transition(JobState.VALIDATING).withCheckpoint(cp).withCheckpoint(cp2).pause()
            .copy(toolchain = mapOf("kotlin" to "2.0.21", "gradle" to "8.9"))
        val s = JobStore(root)
        s.save(j)
        assertEquals(j.copy(toolchain = j.toolchain), s.load("j-2"))
        assertEquals(listOf(j), s.list())
    }

    @Test fun roundTripsJobWithoutReferenceOrComparison() {
        val s = JobStore(root)
        val plain = JobFactory.create(entry, ConversionMode.STRICT, null) { "p" }
        s.save(plain); assertEquals(plain, s.load("p"))
        val noCmp = plain.copy(id = "q", reference = XexIdentifiers("a", "b").let { dev.console2android.core.xex.XexReferenceCheck.summarize(it, null) })
        s.save(noCmp); assertEquals(noCmp, s.load("q"))
    }

    @Test fun saveOverwritesAndNeverStoresRawValues() {
        val s = JobStore(root)
        val j = JobFactory.create(entry, ConversionMode.STRICT, XexIdentifiers("RAWSECRET1", "RAWSECRET2")) { "r" }
        s.save(j); s.save(j.transition(JobState.VALIDATING))
        assertEquals(JobState.VALIDATING, s.load("r")!!.state)
        assertFalse(File(root, "jobs/r.properties").readText().contains("RAWSECRET"))
        assertEquals(0, File(root, "jobs").listFiles()!!.count { it.name.endsWith(".tmp") })
    }

    @Test fun corruptAndForeignFilesAreSkipped() {
        val s = JobStore(root)
        s.save(JobFactory.create(entry, ConversionMode.STRICT, null) { "ok" })
        File(root, "jobs/bad.properties").writeText("id=bad\nstate=NOPE")
        File(root, "jobs/mismatch.properties").writeText(File(root, "jobs/ok.properties").readText())
        assertEquals(listOf("ok"), s.list().map { it.id })
        assertNull(s.load("bad"))
    }

    @Test fun badIdsRejected() {
        val s = JobStore(root)
        assertNull(s.load("../x")); assertFalse(s.delete("../x"))
        val j = JobFactory.create(entry, ConversionMode.STRICT, null) { "../evil" }
        try { s.save(j); throw AssertionError("expected failure") } catch (e: IllegalArgumentException) { /* expected */ }
    }

    @Test fun deleteRemovesJob() {
        val s = JobStore(root)
        s.save(JobFactory.create(entry, ConversionMode.STRICT, null) { "d" })
        assertTrue(s.delete("d")); assertNull(s.load("d")); assertTrue(s.delete("d"))
    }
}
