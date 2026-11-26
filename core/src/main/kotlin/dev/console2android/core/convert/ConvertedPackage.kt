package dev.console2android.core.convert

import dev.console2android.core.fx1.Fx1
import dev.console2android.core.fx1.Fx1Image
import dev.console2android.core.ir.ExecutionOutcome
import dev.console2android.core.ir.IrExecutor
import dev.console2android.core.ir.IrParseResult
import dev.console2android.core.ir.IrProgram
import dev.console2android.core.ir.IrText
import dev.console2android.core.job.ConversionMode
import dev.console2android.core.ppc.PpcInterpreter
import java.io.File
import java.io.IOException
import java.io.StringReader
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Properties
import java.util.UUID

object ToolVersions {
    const val TRANSLATOR = "c2a-ppc-ir/1"
    const val IR_FORMAT = "c2air/1"
    const val PACKAGE_FORMAT = "c2a-cgp/1"
    const val RUNTIME = "c2a-runtime-ir/1"
}

internal fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

internal fun sha256(text: String): String = sha256(text.toByteArray(StandardCharsets.UTF_8))

object CacheKey {
    /** Same source + same tool versions + same mode => same key (cache hit); anything else changes it. */
    fun of(sourceSha256: String, mode: ConversionMode): String =
        sha256("c2a-cache/1|$sourceSha256|${ToolVersions.TRANSLATOR}|${ToolVersions.IR_FORMAT}|${mode.name}")
}

object PackageFiles {
    const val MANIFEST = "manifest.properties"
    const val PROGRAM = "program.c2air"
    const val CODE = "code.bin"
    const val DATA = "data.bin"
    const val STATUS = "status.properties"
    val ARTIFACTS = listOf(PROGRAM, CODE, DATA)
}

/** Everything the packager needs; hashes are computed from the bytes, never taken on trust. */
class PackageInput(
    val cacheKey: String, val sourceSha256: String, val mode: ConversionMode, val programText: String,
    val code: ByteArray, val data: ByteArray, val entry: Long, val codeBase: Long,
    val translatedInstructions: Int, val unsupportedCount: Int, val externalTargets: Int, val createdAtEpochMs: Long,
)

/** A Converted Game Package directory: artifacts + manifest (+ status after the Final Conversion Validator ran). */
class ConvertedPackage(val dir: File, val manifest: Map<String, String>) {
    val id: String get() = dir.name
    val sourceSha256: String get() = manifest.getValue("sourceSha256")
    val mode: ConversionMode get() = ConversionMode.valueOf(manifest.getValue("mode"))

    /** `status.properties` (written by the validator): fcv, status, smoke details. Empty if not validated yet. */
    fun status(): Map<String, String> = readProps(File(dir, PackageFiles.STATUS))

    fun writeStatus(values: Map<String, String>) = writeAtomic(File(dir, PackageFiles.STATUS), canonicalProps(values))

    /** Recomputes artifact hashes. Returns the list of problems (empty = intact). */
    fun integrityProblems(): List<String> {
        val problems = ArrayList<String>()
        val expected = mapOf(
            PackageFiles.PROGRAM to "programSha256", PackageFiles.CODE to "codeSha256", PackageFiles.DATA to "dataSha256",
        )
        for ((file, key) in expected) {
            val f = File(dir, file)
            if (!f.isFile) { problems += "missing $file"; continue }
            val actual = try { sha256(f.readBytes()) } catch (e: IOException) { problems += "unreadable $file"; continue }
            if (actual != manifest[key]) problems += "$file hash differs from manifest"
        }
        return problems
    }

    companion object {
        fun open(dir: File): ConvertedPackage? {
            val m = readProps(File(dir, PackageFiles.MANIFEST))
            val required = listOf("schema", "cacheKey", "sourceSha256", "programSha256", "codeSha256", "dataSha256", "entry", "codeBase", "translator", "irFormat", "mode")
            if (m["schema"] != "1" || required.any { it !in m } || m["cacheKey"] != dir.name) return null
            if (runCatching { ConversionMode.valueOf(m.getValue("mode")) }.isFailure) return null
            return ConvertedPackage(dir, m)
        }
    }
}

internal fun canonicalProps(m: Map<String, String>): String =
    m.toSortedMap().entries.joinToString("") { (k, v) ->
        require(k.all { it.isLetterOrDigit() || it == '.' } && '\n' !in v && '\r' !in v) { "unsafe property" }
        "$k=$v\n"
    }

internal fun readProps(f: File): Map<String, String> {
    if (!f.isFile) return emptyMap()
    return try {
        val p = Properties(); p.load(StringReader(f.readText(StandardCharsets.UTF_8)))
        p.stringPropertyNames().associateWith { p.getProperty(it) }
    } catch (e: IOException) { emptyMap() } catch (e: IllegalArgumentException) { emptyMap() }
}

internal fun writeAtomic(dest: File, text: String) {
    val tmp = File(dest.parentFile, dest.name + "." + UUID.randomUUID() + ".tmp")
    try {
        tmp.writeText(text, StandardCharsets.UTF_8)
        try { Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.ATOMIC_MOVE) }
        catch (e: AtomicMoveNotSupportedException) { Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING) }
    } finally { tmp.delete() }
}

/** Packages live in `<root>/packages/<cacheKey>/`; the cache key is the package id (content/tool/mode addressed). */
class PackageStore(root: File) {
    private val dir = File(root, "packages").also { it.mkdirs(); check(it.isDirectory) { "cannot create packages directory" } }

    init { dir.listFiles()?.filter { it.name.startsWith(".tmp-") }?.forEach { it.deleteRecursively() } }

    fun get(id: String): ConvertedPackage? =
        if (!Regex("^[0-9a-f]{64}$").matches(id)) null else File(dir, id).takeIf { it.isDirectory }?.let { ConvertedPackage.open(it) }

    /** Cache lookup: returns only an intact package; a corrupt one is removed (treated as a miss, never trusted). */
    fun lookupIntact(cacheKey: String): ConvertedPackage? {
        val p = get(cacheKey) ?: return null
        if (p.integrityProblems().isEmpty()) return p
        remove(cacheKey)
        return null
    }

    /** Packages whose manifest parses (cheap: no re-hash). Newest first. */
    fun list(): List<ConvertedPackage> =
        (dir.listFiles() ?: emptyArray()).filter { it.isDirectory && !it.name.startsWith(".") }.mapNotNull { ConvertedPackage.open(it) }
            .sortedWith(compareByDescending<ConvertedPackage> { it.manifest["createdAt"]?.toLongOrNull() ?: 0L }.thenBy { it.id })

    fun remove(id: String): Boolean = Regex("^[0-9a-f]{64}$").matches(id) && File(dir, id).let { !it.exists() || it.deleteRecursively() }

    /** Writes a new package atomically (temp dir then rename). An existing package is never overwritten here. */
    fun write(input: PackageInput): ConvertedPackage {
        require(Regex("^[0-9a-f]{64}$").matches(input.cacheKey)) { "bad cache key" }
        val tmp = File(dir, ".tmp-" + UUID.randomUUID())
        check(tmp.mkdirs())
        try {
            File(tmp, PackageFiles.PROGRAM).writeText(input.programText, StandardCharsets.UTF_8)
            File(tmp, PackageFiles.CODE).writeBytes(input.code)
            File(tmp, PackageFiles.DATA).writeBytes(input.data)
            val manifest = linkedMapOf(
                "schema" to "1", "cacheKey" to input.cacheKey, "sourceSha256" to input.sourceSha256,
                "programSha256" to sha256(input.programText), "codeSha256" to sha256(input.code), "dataSha256" to sha256(input.data),
                "entry" to input.entry.toString(16), "codeBase" to input.codeBase.toString(16),
                "translator" to ToolVersions.TRANSLATOR, "irFormat" to ToolVersions.IR_FORMAT, "packageFormat" to ToolVersions.PACKAGE_FORMAT,
                "mode" to input.mode.name, "instructions" to input.translatedInstructions.toString(),
                "unsupported" to input.unsupportedCount.toString(), "external" to input.externalTargets.toString(),
                "createdAt" to input.createdAtEpochMs.toString(),
            )
            File(tmp, PackageFiles.MANIFEST).writeText(canonicalProps(manifest), StandardCharsets.UTF_8)
            val dest = File(dir, input.cacheKey)
            if (dest.exists()) throw IOException("package already exists")
            try { Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.ATOMIC_MOVE) }
            catch (e: AtomicMoveNotSupportedException) { Files.move(tmp.toPath(), dest.toPath()) }
            return checkNotNull(ConvertedPackage.open(dest)) { "written package does not reopen" }
        } finally {
            if (tmp.exists()) tmp.deleteRecursively()
        }
    }
}

/** Loads an intact package and runs it in the IR runtime (translated blocks; explicit counted interpreter fallback). */
object PackageRuntime {
    class Loaded(val program: IrProgram, val image: Fx1Image)

    sealed interface LoadResult {
        data class Ok(val loaded: Loaded) : LoadResult
        data class Failed(val reason: String) : LoadResult
    }

    fun load(pkg: ConvertedPackage): LoadResult {
        val problems = pkg.integrityProblems()
        if (problems.isNotEmpty()) return LoadResult.Failed("Package is corrupt: ${problems.joinToString("; ")}")
        val parsed = IrText.parse(File(pkg.dir, PackageFiles.PROGRAM).readText(StandardCharsets.UTF_8))
        val program = (parsed as? IrParseResult.Success)?.program
            ?: return LoadResult.Failed("Program does not parse (line ${(parsed as IrParseResult.Failure).line}: ${parsed.message}).")
        val code = File(pkg.dir, PackageFiles.CODE).readBytes()
        val data = File(pkg.dir, PackageFiles.DATA).readBytes()
        if (program.codeBase != Fx1.CODE_BASE || program.codeSize != code.size) return LoadResult.Failed("Program does not match the code section.")
        val entryOffset = program.entry - program.codeBase
        if (entryOffset < 0 || entryOffset >= code.size) return LoadResult.Failed("Entry lies outside the code section.")
        return LoadResult.Ok(Loaded(program, Fx1Image(entryOffset.toInt(), code, data)))
    }

    fun run(l: Loaded, maxSteps: Long = PpcInterpreter.DEFAULT_MAX_STEPS): ExecutionOutcome =
        IrExecutor(l.program, Fx1.newMemory(l.image)).run(Fx1.initialState(l.image), maxSteps)

    fun runReference(l: Loaded, maxSteps: Long = PpcInterpreter.DEFAULT_MAX_STEPS) =
        PpcInterpreter(Fx1.newMemory(l.image)).run(Fx1.initialState(l.image), maxSteps)
}
