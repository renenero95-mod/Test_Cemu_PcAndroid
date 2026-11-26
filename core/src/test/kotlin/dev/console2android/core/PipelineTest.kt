package dev.console2android.core

import dev.console2android.core.convert.CacheKey
import dev.console2android.core.convert.Fx1Pipeline
import dev.console2android.core.convert.JobRunner
import dev.console2android.core.convert.PackageRuntime
import dev.console2android.core.convert.PackageStore
import dev.console2android.core.convert.PipelineContext
import dev.console2android.core.convert.PipelineOutcome
import dev.console2android.core.convert.Stage
import dev.console2android.core.diag.DiagnosticEvent
import dev.console2android.core.diag.Severity
import dev.console2android.core.fx1.Fx1
import dev.console2android.core.fx1.Fx1Samples
import dev.console2android.core.job.ConversionJob
import dev.console2android.core.job.ConversionMode
import dev.console2android.core.job.JobState
import dev.console2android.core.job.StageResult
import dev.console2android.core.library.ImportResult
import dev.console2android.core.library.JobFactory
import dev.console2android.core.library.JobStore
import dev.console2android.core.library.LibraryEntry
import dev.console2android.core.library.SourceKind
import dev.console2android.core.library.SourceLibrary
import dev.console2android.core.ppc.PpcAsm as A
import dev.console2android.core.ppc.StopKind
import dev.console2android.core.report.DeviceInfo
import dev.console2android.core.validation.FcvOverall
import dev.console2android.core.validation.ResultStatus
import dev.console2android.core.xex.XexIdentifiers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files

class PipelineTest {
    private lateinit var root: File
    private lateinit var library: SourceLibrary
    private lateinit var packages: PackageStore
    private lateinit var store: JobStore
    private var now = 1_000L
    private val device = DeviceInfo("test-device", "0", "none")

    @Before fun setUp() {
        root = Files.createTempDirectory("c2a-pipe").toFile()
        library = SourceLibrary(root, { now }, 0L); packages = PackageStore(root); store = JobStore(root)
    }
    @After fun tearDown() { root.deleteRecursively() }

    private fun runner(stages: List<Stage> = Fx1Pipeline.stages()) = JobRunner(library, packages, store, device, stages) { now }

    private fun import(bytes: ByteArray, name: String = "s.bin"): LibraryEntry =
        (library.importSource(ByteArrayInputStream(bytes), name) as ImportResult.Added).entry

    private var jobCounter = 0
    private fun job(e: LibraryEntry, mode: ConversionMode = ConversionMode.STRICT, ids: XexIdentifiers? = null): ConversionJob =
        JobFactory.create(e, mode, ids) { "job-${++jobCounter}" }

    private fun codes(o: PipelineOutcome) = o.events.map { it.event.code }

    /** Program with an instruction outside the documented subset on a reachable path (after one valid instruction). */
    private fun unsupportedFixture() = Fx1.build(0, A.assemble(A.li(3, 7), 0xFC000000.toInt(), A.li(0, 2), A.sc()))

    @Test fun happyPathReachesReadyAndPackageRuns() {
        val e = import(Fx1Samples.sumAndDouble(), Fx1Samples.SUM_AND_DOUBLE_NAME)
        assertEquals(SourceKind.C2AFX1, e.kind)
        val o = runner().run(job(e))
        assertEquals(codes(o).toString(), JobState.READY, o.job.state)
        assertEquals(JobState.READY, store.load(o.job.id)!!.state)
        val v = checkNotNull(o.validation)
        assertTrue(v.fcv != FcvOverall.FAIL)
        assertTrue(v.status != ResultStatus.FAILED)
        assertEquals(0, v.unsupportedInstructions)
        assertEquals(CacheKey.of(e.sha256, ConversionMode.STRICT), o.packageId)
        val pkg = checkNotNull(packages.get(checkNotNull(o.packageId)))
        assertTrue(pkg.integrityProblems().isEmpty())
        assertEquals(v.status.name, pkg.status()["status"])
        assertEquals("synthetic-fixture-checks", pkg.status()["scope"])
        // running the converted package
        val loaded = (PackageRuntime.load(pkg) as PackageRuntime.LoadResult.Ok).loaded
        val r = PackageRuntime.run(loaded)
        assertEquals(StopKind.EXIT, r.run.stop); assertEquals(0, r.run.exitCode)
        assertEquals(Fx1Samples.SUM_AND_DOUBLE_OUTPUT, r.run.output)
        assertEquals(PackageRuntime.runReference(loaded), r.run)
        // reports are written and contain no raw content
        val dir = store.workDir(o.job.id)
        assertTrue(File(dir, "report.txt").readText().contains("sha256"))
        assertTrue(File(dir, "report.json").isFile)
    }

    @Test fun identicalSourceAndModeIsACacheHit() {
        val e = import(Fx1Samples.sumAndDouble())
        val a = runner().run(job(e)); val b = runner().run(job(e))
        assertEquals(JobState.READY, b.job.state)
        assertEquals(a.packageId, b.packageId)
        assertTrue("PACKAGE-WRITTEN" in codes(a)); assertFalse("PACKAGE-CACHE-HIT" in codes(a))
        assertTrue("PACKAGE-CACHE-HIT" in codes(b)); assertFalse("PACKAGE-WRITTEN" in codes(b))
        assertEquals(1, packages.list().size)
    }

    @Test fun differentModeGivesDifferentPackage() {
        val e = import(Fx1Samples.sumAndDouble())
        val a = runner().run(job(e, ConversionMode.STRICT)); val b = runner().run(job(e, ConversionMode.BALANCED))
        assertNotEquals(a.packageId, b.packageId)
        assertEquals(2, packages.list().size)
    }

    @Test fun corruptCachedPackageIsNeverTrustedAndIsRebuilt() {
        val e = import(Fx1Samples.sumAndDouble())
        val a = runner().run(job(e))
        File(checkNotNull(packages.get(checkNotNull(a.packageId))).dir, "code.bin").appendBytes(byteArrayOf(1))
        val b = runner().run(job(e))
        assertEquals(JobState.READY, b.job.state)
        assertTrue("PACKAGE-WRITTEN" in codes(b)); assertFalse("PACKAGE-CACHE-HIT" in codes(b))
        assertTrue(checkNotNull(packages.get(checkNotNull(b.packageId))).integrityProblems().isEmpty())
    }

    @Test fun pausedJobResumesFromCheckpointsWithoutRepeatingStages() {
        val e = import(Fx1Samples.sumAndDouble())
        var calls = 0
        val first = runner().run(job(e), pauseRequested = { ++calls == 5 }) // pause before PACKAGING
        assertEquals(JobState.PAUSED, first.job.state)
        assertTrue("FX1-PARSED" in codes(first)); assertNull(first.packageId)
        val saved = checkNotNull(store.load(first.job.id))
        assertEquals(JobState.PAUSED, saved.state)
        val second = runner().run(saved)
        assertEquals(codes(second).toString(), JobState.READY, second.job.state)
        assertFalse("FX1-PARSED" in codes(second)); assertFalse("TRANSLATED" in codes(second))
        assertTrue("PACKAGE-WRITTEN" in codes(second))
    }

    @Test fun verifiedCheckpointIsReusedWhenStateWasNotAdvanced() {
        val e = import(Fx1Samples.sumAndDouble())
        var calls = 0
        val first = runner().run(job(e), pauseRequested = { ++calls == 5 })
        // simulate a crash right after TRANSLATING stored its checkpoint but before the state moved on
        val crashed = first.job.resume().let { it.copy(state = JobState.TRANSLATING) }
        val second = runner().run(crashed)
        assertEquals(JobState.READY, second.job.state)
        assertTrue("CHECKPOINT-REUSED" in codes(second)); assertFalse("TRANSLATED" in codes(second))
    }

    @Test fun damagedStageOutputInvalidatesResume() {
        val e = import(Fx1Samples.sumAndDouble())
        var calls = 0
        val first = runner().run(job(e), pauseRequested = { ++calls == 5 })
        File(store.workDir(first.job.id), "program.c2air").appendText("tampered\n")
        val second = runner().run(checkNotNull(store.load(first.job.id)))
        assertEquals(JobState.FAILED, second.job.state)
        assertTrue("CHECKPOINT-STALE" in codes(second))
        assertNull(second.packageId); assertTrue(packages.list().isEmpty())
    }

    @Test fun cancelStopsBeforeAnyPackageExists() {
        val e = import(Fx1Samples.sumAndDouble())
        val o = runner().run(job(e), cancelled = { true })
        assertEquals(JobState.CANCELLED, o.job.state)
        assertTrue("JOB-CANCELLED" in codes(o)); assertTrue(packages.list().isEmpty())
    }

    @Test fun strictRejectsUnsupportedInstructionButBalancedContinuesHonestly() {
        val e = import(unsupportedFixture())
        val strict = runner().run(job(e, ConversionMode.STRICT))
        assertEquals(JobState.FAILED, strict.job.state)
        assertTrue("PPC-UNSUPPORTED-INSN" in codes(strict)); assertTrue("STRICT-PARTIAL" in codes(strict))
        assertTrue(packages.list().isEmpty())

        val balanced = runner().run(job(e, ConversionMode.BALANCED))
        assertTrue("PPC-UNSUPPORTED-INSN" in codes(balanced))
        val v = checkNotNull(balanced.validation)
        assertEquals(1, v.unsupportedInstructions)
        assertNotEquals(ResultStatus.STABLE, v.status)
        // the package runs but stops explicitly at the unsupported instruction (never approximated)
        val pkg = checkNotNull(packages.get(checkNotNull(balanced.packageId)))
        val r = PackageRuntime.run((PackageRuntime.load(pkg) as PackageRuntime.LoadResult.Ok).loaded)
        assertEquals(StopKind.UNSUPPORTED, r.run.stop)
    }

    @Test fun xexSourceIsRejectedWithExplicitFormatUnsupported() {
        val xex = "XEX2".toByteArray() + ByteArray(60)
        val e = import(xex, "game.xex")
        assertEquals(SourceKind.XEX2, e.kind)
        val o = runner().run(job(e))
        assertEquals(JobState.FAILED, o.job.state)
        assertTrue("FORMAT-UNSUPPORTED" in codes(o))
        assertTrue(packages.list().isEmpty())
        // it stays analysable in the library
        assertNotNull(library.get(e.sha256))
    }

    @Test fun malformedFx1FailsWithStructuredEvents() {
        val truncated = Fx1.MAGIC + byteArrayOf(0, 0, 0, 0)
        val o = runner().run(job(import(truncated)))
        assertEquals(JobState.FAILED, o.job.state)
        assertTrue("FX1-TRUNCATED" in codes(o))
        val badEntry = Fx1.build(400, A.assemble(A.li(0, 2), A.sc()))
        val o2 = runner().run(job(import(badEntry)))
        assertEquals(JobState.FAILED, o2.job.state); assertTrue("FX1-ENTRY" in codes(o2))
    }

    @Test fun finalValidatorFailureMakesTheJobFailedNotReady() {
        class Tamper(val inner: Stage) : Stage by inner {
            override fun execute(ctx: PipelineContext, input: String): StageResult {
                File(checkNotNull(ctx.packages.get(CacheKey.of(ctx.job.sourceSha256, ctx.job.mode))).dir, "data.bin").appendBytes(byteArrayOf(9))
                return inner.execute(ctx, input)
            }
        }
        val stages = Fx1Pipeline.stages().map { if (it.state == JobState.FINAL_VALIDATION) Tamper(it) else it }
        val o = runner(stages).run(job(import(Fx1Samples.sumAndDouble())))
        assertEquals(JobState.FAILED, o.job.state)
        assertEquals(FcvOverall.FAIL, checkNotNull(o.validation).fcv)
        assertTrue("FCV-ARTIFACT-HASHES" in codes(o)); assertTrue("FCV-FAILED" in codes(o))
        assertEquals(ResultStatus.FAILED, o.validation!!.status)
    }

    @Test fun exceptionInStageBecomesFailedJobWithoutLeakingDetails() {
        val boom = object : Stage {
            override val state = JobState.VALIDATING
            override val toolVersion = "t"
            override fun execute(ctx: PipelineContext, input: String): StageResult = throw IllegalStateException("secret /path/xyz")
        }
        val o = runner(listOf(boom)).run(job(import(Fx1Samples.sumAndDouble())))
        assertEquals(JobState.FAILED, o.job.state)
        val ev = o.events.single { it.event.code == "STAGE-EXCEPTION" }.event
        assertEquals(Severity.CRITICAL, ev.severity)
        assertFalse(ev.message.contains("secret"))
    }

    @Test fun missingSourceFailsCleanly() {
        val e = import(Fx1Samples.sumAndDouble())
        val j = job(e)
        library.remove(e.sha256)
        val o = runner().run(j)
        assertEquals(JobState.FAILED, o.job.state); assertTrue("SOURCE-MISSING" in codes(o))
    }

    @Test fun reportsNeverContainRawReferenceIdentifiers() {
        val e = import(Fx1Samples.sumAndDouble())
        val ids = XexIdentifiers("RAWSECRETVALUE-one", "RAWSECRETVALUE-two")
        val o = runner().run(job(e, ids = ids))
        val dir = store.workDir(o.job.id)
        for (f in listOf("report.txt", "report.json")) assertFalse(File(dir, f).readText().contains("RAWSECRET"))
        assertFalse(File(root, "jobs").walkTopDown().filter { it.isFile }.any { it.readText().contains("RAWSECRET") })
    }

    @Test fun deletingAJobRemovesItsWorkDirectoryButNotThePackage() {
        val e = import(Fx1Samples.sumAndDouble())
        val o = runner().run(job(e))
        val dir = store.workDir(o.job.id)
        assertTrue(dir.isDirectory)
        assertTrue(store.delete(o.job.id))
        assertFalse(dir.exists()); assertNull(store.load(o.job.id))
        assertNotNull(packages.get(checkNotNull(o.packageId)))
    }

    @Test fun sampleFixtureMatchesItsDocumentedOutputOnBothEngines() {
        val img = (Fx1.parse(Fx1Samples.sumAndDouble()) as dev.console2android.core.fx1.Fx1ParseResult.Success).image
        val ref = dev.console2android.core.ppc.PpcInterpreter(Fx1.newMemory(img)).run(Fx1.initialState(img))
        assertEquals(Fx1Samples.SUM_AND_DOUBLE_OUTPUT, ref.output)
        assertEquals(StopKind.EXIT, ref.stop)
    }
}
