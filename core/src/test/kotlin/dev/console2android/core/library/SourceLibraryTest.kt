package dev.console2android.core.library

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest

class SourceLibraryTest {
    private lateinit var root: File
    private var now = 1000L

    @Before fun setUp() { root = Files.createTempDirectory("c2a-lib").toFile() }
    @After fun tearDown() { root.walkBottomUp().forEach { it.setWritable(true); it.delete() } }

    private fun lib(minFree: Long = 0) = SourceLibrary(root, { now++ }, minFree)
    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    private fun be(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    private fun xex2(size: Int = 0x200): ByteArray {
        val b = ByteArray(size)
        ("XEX2".toByteArray() + be(1) + be(0x100) + be(0) + be(0x80) + be(0)).copyInto(b)
        return b
    }
    private fun stored(l: SourceLibrary, data: ByteArray, name: String? = "game.xex") =
        l.importSource(ByteArrayInputStream(data), name)

    @Test fun importsXexAndDetectsKind() {
        val data = xex2()
        val r = stored(lib(), data) as ImportResult.Added
        assertEquals(sha(data), r.entry.sha256)
        assertEquals(SourceKind.XEX2, r.entry.kind)
        assertEquals(data.size.toLong(), r.entry.sizeBytes)
        assertTrue(r.entry.probeNote.contains("XEX2"))
    }

    @Test fun storedCopyIsIdentical() {
        val l = lib(); val data = xex2(70_000)
        val e = (stored(l, data) as ImportResult.Added).entry
        val f = l.sourceFile(e.sha256)!!
        assertTrue(f.readBytes().contentEquals(data))
        assertTrue(l.verify(e.sha256))
    }

    @Test fun storedCopyIsMarkedReadOnly() {
        org.junit.Assume.assumeTrue("permission bits are ignored for root", System.getProperty("user.name") != "root")
        val l = lib(); val e = (stored(l, xex2()) as ImportResult.Added).entry
        assertFalse(l.sourceFile(e.sha256)!!.canWrite())
    }

    @Test fun unknownFormatIsUnknownNotRejected() {
        val e = (stored(lib(), "hello world".toByteArray()) as ImportResult.Added).entry
        assertEquals(SourceKind.UNKNOWN, e.kind)
    }

    @Test fun duplicateImportIsNoOp() {
        val l = lib(); val data = xex2()
        val first = (stored(l, data) as ImportResult.Added).entry
        val second = stored(l, data, "other-name.xex") as ImportResult.AlreadyPresent
        assertEquals(first, second.entry)
        assertEquals(1, l.list().size)
    }

    @Test fun lostIndexEntryIsRestoredFromContent() {
        val l = lib(); val data = xex2()
        val e = (stored(l, data) as ImportResult.Added).entry
        File(root, "library/${e.sha256}.properties").delete()
        assertTrue(l.list().isEmpty())
        assertTrue(stored(l, data) is ImportResult.AlreadyPresent)
        assertEquals(1, l.list().size)
    }

    @Test fun emptyTooLargeAndCancelledLeaveNothingBehind() {
        val l = lib()
        assertEquals(ImportResult.Failed(ImportError.EMPTY), l.importSource(ByteArrayInputStream(ByteArray(0)), "x"))
        assertEquals(ImportResult.Failed(ImportError.TOO_LARGE), l.importSource(ByteArrayInputStream(ByteArray(1000)), "x", maxBytes = 999))
        assertEquals(ImportResult.Failed(ImportError.CANCELLED), l.importSource(ByteArrayInputStream(ByteArray(1000)), "x", cancelled = { true }))
        assertTrue(l.list().isEmpty())
        assertEquals(0, File(root, "tmp").listFiles()!!.size)
        assertEquals(0, File(root, "sources").listFiles()!!.size)
    }

    @Test fun cancelMidStreamCleansUp() {
        val l = lib(); var reads = 0
        val r = l.importSource(ByteArrayInputStream(ByteArray(500_000)), "x", cancelled = { reads++ >= 3 })
        assertEquals(ImportResult.Failed(ImportError.CANCELLED), r)
        assertEquals(0, File(root, "tmp").listFiles()!!.size)
    }

    @Test fun ioErrorIsReportedWithoutPathAndCleansUp() {
        val broken = object : InputStream() {
            override fun read(): Int = throw IOException("/secret/path")
            override fun read(b: ByteArray): Int = throw IOException("/secret/path")
        }
        val r = lib().importSource(broken, "x") as ImportResult.Failed
        assertEquals(ImportError.IO, r.error)
        assertFalse(r.detail!!.contains("secret"))
        assertEquals(0, File(root, "tmp").listFiles()!!.size)
    }

    @Test fun insufficientSpaceRefusedBeforeCopy() {
        val r = lib(minFree = Long.MAX_VALUE).importSource(ByteArrayInputStream(xex2()), "x")
        assertEquals(ImportResult.Failed(ImportError.INSUFFICIENT_SPACE), r)
    }

    @Test fun staleTempFilesRemovedAtStartup() {
        lib()
        File(root, "tmp/leftover.part").writeText("x")
        lib()
        assertEquals(0, File(root, "tmp").listFiles()!!.size)
    }

    @Test fun verifyDetectsTampering() {
        val l = lib(); val e = (stored(l, xex2()) as ImportResult.Added).entry
        val f = l.sourceFile(e.sha256)!!
        f.setWritable(true); f.writeBytes(xex2().also { it[0x50] = 9 })
        assertFalse(l.verify(e.sha256))
    }

    @Test fun listIsNewestFirstAndSkipsCorruptEntries() {
        val l = lib()
        val a = (stored(l, "aaa".toByteArray(), "a") as ImportResult.Added).entry
        val b = (stored(l, "bbb".toByteArray(), "b") as ImportResult.Added).entry
        val c = (stored(l, "ccc".toByteArray(), "c") as ImportResult.Added).entry
        assertEquals(listOf(c.sha256, b.sha256, a.sha256), l.list().map { it.sha256 })
        File(root, "library/${b.sha256}.properties").writeText("garbage")
        assertEquals(listOf(c.sha256, a.sha256), l.list().map { it.sha256 })
        File(root, "library/not-a-sha.properties").writeText("name=x")
        assertEquals(2, l.list().size)
    }

    @Test fun persistsAcrossInstances() {
        val e = (stored(lib(), xex2(), "My Game.xex") as ImportResult.Added).entry
        assertEquals(e, lib().get(e.sha256))
    }

    @Test fun removeDeletesBothAndRejectsBadIds() {
        val l = lib(); val e = (stored(l, xex2()) as ImportResult.Added).entry
        assertFalse(l.remove("../../etc/passwd"))
        assertTrue(l.remove(e.sha256))
        assertNull(l.get(e.sha256)); assertNull(l.sourceFile(e.sha256))
        assertTrue(l.list().isEmpty())
    }

    @Test fun pathTraversalIdsAreInert() {
        val l = lib()
        assertNull(l.sourceFile("../x")); assertNull(l.get("..")); assertFalse(l.verify("../../x"))
    }

    @Test fun displayNameIsSanitized() {
        assertEquals("a_b_c", sanitizeDisplayName("a/b\\c"))
        assertEquals("source", sanitizeDisplayName(null))
        assertEquals("source", sanitizeDisplayName("  .. "))
        assertEquals("x_y", sanitizeDisplayName("x\u0000y"))
        assertEquals(120, sanitizeDisplayName("n".repeat(500)).length)
        val e = (stored(lib(), xex2(), "../../evil") as ImportResult.Added).entry
        assertEquals(".._.._evil", e.displayName)
        assertNotNull(lib().sourceFile(e.sha256))
    }

    @Test fun largeFileStreamsWithBoundedPrefix() {
        val data = ByteArray(3 * 1024 * 1024) { (it % 251).toByte() }
        val e = (stored(lib(), data) as ImportResult.Added).entry
        assertEquals(sha(data), e.sha256)
    }
}
