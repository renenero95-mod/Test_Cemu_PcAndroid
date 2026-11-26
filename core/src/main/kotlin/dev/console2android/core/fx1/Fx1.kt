package dev.console2android.core.fx1

import dev.console2android.core.diag.DiagnosticEvent
import dev.console2android.core.diag.FailureDomain
import dev.console2android.core.diag.Severity
import dev.console2android.core.ppc.GuestMemory
import dev.console2android.core.ppc.GuestRegion
import dev.console2android.core.ppc.PpcState

/** The parsed C2A-FX1 synthetic fixture: our own container, NOT an Xbox 360 executable. */
class Fx1Image(val entryOffset: Int, val code: ByteArray, val data: ByteArray)

sealed interface Fx1ParseResult {
    data class Success(val image: Fx1Image) : Fx1ParseResult
    data class Failure(val events: List<DiagnosticEvent>) : Fx1ParseResult
}

/**
 * Container: magic `C2AFX1\0\0`, then little-endian u32: entryOffset (within code), codeOffset, codeSize, dataOffset, dataSize,
 * then the sections (file offsets). Code = big-endian PowerPC words (documented subset). Guest map: code (r-x) at [CODE_BASE],
 * data (rw-) at [DATA_BASE], stack (rw-) ending at [STACK_TOP]; r1 starts at STACK_TOP-0x20.
 */
object Fx1 {
    val MAGIC = byteArrayOf('C'.code.toByte(), '2'.code.toByte(), 'A'.code.toByte(), 'F'.code.toByte(), 'X'.code.toByte(), '1'.code.toByte(), 0, 0)
    const val HEADER_SIZE = 28
    const val MAX_SECTION = 1 shl 20
    const val CODE_BASE = 0x1000_0000L
    const val DATA_BASE = 0x2000_0000L
    const val STACK_TOP = 0x3000_0000L
    const val STACK_SIZE = 0x4000

    private fun err(code: String, msg: String) =
        DiagnosticEvent(code, Severity.ERROR, FailureDomain.INPUT_VALIDATION, "fx1", "validate", msg)

    private fun u32(b: ByteArray, o: Int): Long =
        (b[o].toLong() and 0xFF) or ((b[o + 1].toLong() and 0xFF) shl 8) or ((b[o + 2].toLong() and 0xFF) shl 16) or ((b[o + 3].toLong() and 0xFF) shl 24)

    fun build(entryOffset: Int, code: ByteArray, data: ByteArray = ByteArray(0)): ByteArray {
        fun le(v: Int) = byteArrayOf(v.toByte(), (v ushr 8).toByte(), (v ushr 16).toByte(), (v ushr 24).toByte())
        val codeOff = HEADER_SIZE
        val dataOff = codeOff + code.size
        return MAGIC + le(entryOffset) + le(codeOff) + le(code.size) + le(dataOff) + le(data.size) + code + data
    }

    fun parse(bytes: ByteArray): Fx1ParseResult {
        if (bytes.size < HEADER_SIZE) return Fx1ParseResult.Failure(listOf(err("FX1-TRUNCATED", "File is shorter than the $HEADER_SIZE-byte header.")))
        if (!bytes.copyOfRange(0, 8).contentEquals(MAGIC)) return Fx1ParseResult.Failure(listOf(err("FX1-MAGIC", "Not a C2A-FX1 file (bad magic).")))
        val entry = u32(bytes, 8); val codeOff = u32(bytes, 12); val codeSize = u32(bytes, 16)
        val dataOff = u32(bytes, 20); val dataSize = u32(bytes, 24)
        val size = bytes.size.toLong()
        val e = mutableListOf<DiagnosticEvent>()
        if (codeSize == 0L || codeSize % 4 != 0L || codeSize > MAX_SECTION) e += err("FX1-CODE-SIZE", "Code size $codeSize is invalid (must be 4..$MAX_SECTION, multiple of 4).")
        if (dataSize > MAX_SECTION) e += err("FX1-DATA-SIZE", "Data size $dataSize exceeds $MAX_SECTION.")
        if (codeOff < HEADER_SIZE || codeOff + codeSize > size) e += err("FX1-CODE-RANGE", "Code section lies outside the file.")
        if (dataSize > 0 && (dataOff < HEADER_SIZE || dataOff + dataSize > size)) e += err("FX1-DATA-RANGE", "Data section lies outside the file.")
        if (e.isEmpty() && dataSize > 0 && codeOff < dataOff + dataSize && dataOff < codeOff + codeSize) e += err("FX1-OVERLAP", "Code and data sections overlap.")
        if (entry % 4 != 0L || entry >= codeSize) e += err("FX1-ENTRY", "Entry offset $entry is not an aligned offset inside the code section.")
        if (e.isNotEmpty()) return Fx1ParseResult.Failure(e)
        return Fx1ParseResult.Success(
            Fx1Image(entry.toInt(), bytes.copyOfRange(codeOff.toInt(), (codeOff + codeSize).toInt()),
                if (dataSize == 0L) ByteArray(0) else bytes.copyOfRange(dataOff.toInt(), (dataOff + dataSize).toInt())),
        )
    }

    /** Fresh memory (copies of the sections, so repeated runs never see earlier writes). */
    fun newMemory(img: Fx1Image): GuestMemory = GuestMemory(buildList {
        add(GuestRegion(CODE_BASE, img.code.copyOf(), writable = false, executable = true))
        if (img.data.isNotEmpty()) add(GuestRegion(DATA_BASE, img.data.copyOf(), writable = true, executable = false))
        add(GuestRegion(STACK_TOP - STACK_SIZE, ByteArray(STACK_SIZE), writable = true, executable = false))
    })

    fun initialState(img: Fx1Image): PpcState = PpcState().also {
        it.pc = CODE_BASE + img.entryOffset
        it.gpr[1] = STACK_TOP - 0x20
    }
}
