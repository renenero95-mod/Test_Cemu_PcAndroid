package dev.console2android.core.library

import dev.console2android.core.fx1.Fx1
import dev.console2android.core.xex.SourceAnalyzer
import dev.console2android.core.xex.XexHeaderProbe
import dev.console2android.core.xex.XexProbeResult
import dev.console2android.core.xex.describe
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Properties
import java.util.UUID

/** Coarse format detected from the file magic only. */
enum class SourceKind {
    XEX2, XEX1, /** The project's own synthetic fixture container (not an Xbox 360 format). */ C2AFX1, UNKNOWN;

    companion object {
        fun of(p: XexProbeResult): SourceKind = when (p) {
            is XexProbeResult.Parsed -> XEX2
            is XexProbeResult.Malformed -> if (p.magic == "XEX1") XEX1 else XEX2
            is XexProbeResult.UnsupportedVariant -> if (p.magic == "XEX1") XEX1 else XEX2
            XexProbeResult.NotXex -> UNKNOWN
        }
    }
}

/** One immutable source in the library. The SHA-256 of the stored bytes is its identity. */
data class LibraryEntry(
    val sha256: String,
    val displayName: String,
    val sizeBytes: Long,
    val importedAtEpochMs: Long,
    val kind: SourceKind,
    val probeNote: String,
)

enum class ImportError { EMPTY, TOO_LARGE, INSUFFICIENT_SPACE, CANCELLED, IO }

sealed interface ImportResult {
    data class Added(val entry: LibraryEntry) : ImportResult
    data class AlreadyPresent(val entry: LibraryEntry) : ImportResult
    data class Failed(val error: ImportError, val detail: String? = null) : ImportResult
}

internal val SHA256_NAME = Regex("^[0-9a-f]{64}$")

/** Display names are metadata only: control chars and path separators are removed, length is capped. */
fun sanitizeDisplayName(raw: String?): String {
    val cleaned = (raw ?: "").map { c -> if (c.isISOControl() || c == '/' || c == '\\') '_' else c }
        .joinToString("").trim().take(120)
    return if (cleaned.isBlank() || cleaned.all { it == '.' || it == '_' }) "source" else cleaned
}

/**
 * Immutable, content-addressed storage of user-selected source files plus a small persistent index.
 *
 * Layout under [root]: `sources/<sha256>.bin` (read-only after import), `library/<sha256>.properties` (metadata),
 * `tmp/` (in-flight `.part` copies, removed at startup). A copy is streamed once (bounded memory), hashed while
 * written, and atomically renamed into place only when complete; failures and cancellation leave nothing behind.
 * Identity is the hash of the stored bytes, so importing the same file twice is a no-op. Nothing is executed or modified.
 */
class SourceLibrary(
    private val root: File,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val minFreeBytes: Long = 64L * 1024 * 1024,
) {
    private val sourcesDir = File(root, "sources")
    private val metaDir = File(root, "library")
    private val tmpDir = File(root, "tmp")

    init {
        for (d in listOf(sourcesDir, metaDir, tmpDir)) {
            d.mkdirs()
            check(d.isDirectory) { "cannot create library directory" }
        }
        tmpDir.listFiles()?.filter { it.name.endsWith(".part") }?.forEach { it.delete() }
    }

    fun importSource(
        input: InputStream,
        displayName: String?,
        maxBytes: Long = Long.MAX_VALUE,
        cancelled: () -> Boolean = { false },
    ): ImportResult {
        if (tmpDir.usableSpace < minFreeBytes) return ImportResult.Failed(ImportError.INSUFFICIENT_SPACE)
        val tmp = File(tmpDir, UUID.randomUUID().toString() + ".part")
        try {
            val md = MessageDigest.getInstance("SHA-256")
            val prefix = ByteArrayOutputStream()
            var total = 0L
            var sinceSpaceCheck = 0L
            FileOutputStream(tmp).use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    if (cancelled()) return ImportResult.Failed(ImportError.CANCELLED)
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > maxBytes) return ImportResult.Failed(ImportError.TOO_LARGE)
                    sinceSpaceCheck += n
                    if (sinceSpaceCheck >= 8L * 1024 * 1024) {
                        sinceSpaceCheck = 0
                        if (tmpDir.usableSpace < minFreeBytes) return ImportResult.Failed(ImportError.INSUFFICIENT_SPACE)
                    }
                    out.write(buf, 0, n)
                    md.update(buf, 0, n)
                    if (prefix.size() < SourceAnalyzer.PREFIX_BYTES) prefix.write(buf, 0, minOf(n, SourceAnalyzer.PREFIX_BYTES - prefix.size()))
                }
                out.flush()
                out.channel.force(true)
            }
            if (total == 0L) return ImportResult.Failed(ImportError.EMPTY)

            val sha = md.digest().joinToString("") { "%02x".format(it) }
            val probe = XexHeaderProbe.probe(prefix.toByteArray(), total)
            val head = prefix.toByteArray()
            val isFx1 = head.size >= Fx1.MAGIC.size && head.copyOfRange(0, Fx1.MAGIC.size).contentEquals(Fx1.MAGIC)
            val entry = LibraryEntry(
                sha, sanitizeDisplayName(displayName), total, clock(),
                if (isFx1) SourceKind.C2AFX1 else SourceKind.of(probe),
                if (isFx1) "C2A-FX1 synthetic fixture (convertible; not an Xbox 360 executable)." else probe.describe(),
            )

            val dest = File(sourcesDir, "$sha.bin")
            val existing = readEntry(sha)
            if (dest.isFile && dest.length() == total) {
                if (existing != null) return ImportResult.AlreadyPresent(existing)
                writeMeta(entry) // content present but index entry lost: restore it
                return ImportResult.AlreadyPresent(entry)
            }
            if (dest.exists()) { dest.setWritable(true); dest.delete() } // wrong-size leftover: replace
            move(tmp, dest)
            dest.setReadOnly()
            writeMeta(entry)
            return ImportResult.Added(entry)
        } catch (e: IOException) {
            return ImportResult.Failed(ImportError.IO, e.javaClass.simpleName)
        } finally {
            tmp.delete()
        }
    }

    /** Entries newest first. Corrupt metadata or entries without their source file are skipped, never thrown. */
    fun list(): List<LibraryEntry> =
        (metaDir.listFiles() ?: emptyArray()).mapNotNull { f ->
            val sha = f.name.removeSuffix(".properties")
            if (!f.name.endsWith(".properties") || !SHA256_NAME.matches(sha)) null else readEntry(sha)
        }.sortedWith(compareByDescending<LibraryEntry> { it.importedAtEpochMs }.thenBy { it.sha256 })

    fun get(sha256: String): LibraryEntry? = if (SHA256_NAME.matches(sha256)) readEntry(sha256) else null

    /** The stored (read-only) file, or null. Callers must not modify it. */
    fun sourceFile(sha256: String): File? =
        if (!SHA256_NAME.matches(sha256)) null else File(sourcesDir, "$sha256.bin").takeIf { it.isFile }

    /** Re-hashes the stored bytes and compares with the identity and recorded size. */
    fun verify(sha256: String): Boolean {
        val f = sourceFile(sha256) ?: return false
        return try {
            f.inputStream().use { SourceAnalyzer.readFacts(it) }.let { it.sha256 == sha256 && it.sizeBytes == f.length() }
        } catch (e: IOException) {
            false
        }
    }

    /** User-initiated removal of the stored copy and its index entry. */
    fun remove(sha256: String): Boolean {
        if (!SHA256_NAME.matches(sha256)) return false
        val src = File(sourcesDir, "$sha256.bin")
        val meta = File(metaDir, "$sha256.properties")
        src.setWritable(true)
        val a = !src.exists() || src.delete()
        val b = !meta.exists() || meta.delete()
        return a && b
    }

    private fun readEntry(sha: String): LibraryEntry? {
        val meta = File(metaDir, "$sha.properties")
        if (!meta.isFile || !File(sourcesDir, "$sha.bin").isFile) return null
        return try {
            val p = Properties()
            meta.reader(StandardCharsets.UTF_8).use { p.load(it) }
            LibraryEntry(
                sha256 = sha,
                displayName = sanitizeDisplayName(p.getProperty("name")),
                sizeBytes = p.getProperty("size").toLong(),
                importedAtEpochMs = p.getProperty("importedAt").toLong(),
                kind = SourceKind.valueOf(p.getProperty("kind")),
                probeNote = p.getProperty("probe") ?: "",
            )
        } catch (e: Exception) { // IOException, NumberFormatException, IllegalArgumentException, NPE from a missing key
            null
        }
    }

    private fun writeMeta(e: LibraryEntry) {
        val p = Properties()
        p.setProperty("name", e.displayName)
        p.setProperty("size", e.sizeBytes.toString())
        p.setProperty("importedAt", e.importedAtEpochMs.toString())
        p.setProperty("kind", e.kind.name)
        p.setProperty("probe", e.probeNote)
        val tmp = File(tmpDir, UUID.randomUUID().toString() + ".part")
        try {
            tmp.writer(StandardCharsets.UTF_8).use { p.store(it, null) }
            move(tmp, File(metaDir, "${e.sha256}.properties"))
        } finally {
            tmp.delete()
        }
    }

    private fun move(from: File, to: File) {
        try {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
