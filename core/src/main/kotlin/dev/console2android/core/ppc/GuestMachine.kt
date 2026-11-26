package dev.console2android.core.ppc

import java.security.MessageDigest

/** Architectural state of the modelled subset. CR is kept as one 32-bit value, bit 0 (MSB) = CR0[LT]. */
class PpcState {
    val gpr = LongArray(32)
    var lr = 0L
    var ctr = 0L
    var cr = 0
    var pc = 0L

    fun copy(): PpcState = PpcState().also {
        gpr.copyInto(it.gpr); it.lr = lr; it.ctr = ctr; it.cr = cr; it.pc = pc
    }

    fun snapshot(): StateSnapshot = StateSnapshot(gpr.toList(), lr, ctr, cr, pc)
}

data class StateSnapshot(val gpr: List<Long>, val lr: Long, val ctr: Long, val cr: Int, val pc: Long)

class GuestRegion(val base: Long, val bytes: ByteArray, val writable: Boolean, val executable: Boolean) {
    val end: Long get() = base + bytes.size
}

enum class FaultKind { UNMAPPED_READ, UNMAPPED_WRITE, WRITE_TO_READ_ONLY, UNALIGNED, BAD_FETCH }

class GuestFault(val kind: FaultKind, val address: Long) : RuntimeException(null, null, false, false)

/** Flat, big-endian guest memory made of non-overlapping regions. Natural alignment is required (documented, strict). */
class GuestMemory(regions: List<GuestRegion>) {
    private val regions: List<GuestRegion> = regions.sortedBy { it.base }

    init {
        for (i in 1 until this.regions.size) require(this.regions[i - 1].end <= this.regions[i].base) { "overlapping regions" }
    }

    private fun find(addr: Long, len: Int): GuestRegion? =
        regions.firstOrNull { addr >= it.base && addr + len <= it.end }

    fun fetch(addr: Long): Int {
        if (addr and 3L != 0L) throw GuestFault(FaultKind.UNALIGNED, addr)
        val r = find(addr, 4)?.takeIf { it.executable } ?: throw GuestFault(FaultKind.BAD_FETCH, addr)
        return get32(r, addr)
    }

    fun load32(addr: Long): Long {
        if (addr and 3L != 0L) throw GuestFault(FaultKind.UNALIGNED, addr)
        val r = find(addr, 4) ?: throw GuestFault(FaultKind.UNMAPPED_READ, addr)
        return get32(r, addr).toLong() and 0xFFFFFFFFL
    }

    fun store32(addr: Long, value: Long) {
        if (addr and 3L != 0L) throw GuestFault(FaultKind.UNALIGNED, addr)
        val r = find(addr, 4) ?: throw GuestFault(FaultKind.UNMAPPED_WRITE, addr)
        if (!r.writable) throw GuestFault(FaultKind.WRITE_TO_READ_ONLY, addr)
        val o = (addr - r.base).toInt()
        r.bytes[o] = (value ushr 24).toByte(); r.bytes[o + 1] = (value ushr 16).toByte()
        r.bytes[o + 2] = (value ushr 8).toByte(); r.bytes[o + 3] = value.toByte()
    }

    private fun get32(r: GuestRegion, addr: Long): Int {
        val o = (addr - r.base).toInt()
        return ((r.bytes[o].toInt() and 0xFF) shl 24) or ((r.bytes[o + 1].toInt() and 0xFF) shl 16) or
            ((r.bytes[o + 2].toInt() and 0xFF) shl 8) or (r.bytes[o + 3].toInt() and 0xFF)
    }

    /** SHA-256 over all writable regions (base + content), for state-equivalence checks. */
    fun writableDigest(): String {
        val md = MessageDigest.getInstance("SHA-256")
        for (r in regions.filter { it.writable }) {
            md.update(r.base.toString().toByteArray()); md.update(r.bytes)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun isExecutable(addr: Long): Boolean = find(addr, 4)?.executable == true
}

/** Host-call surface (`sc`): r0=1 print r3 as signed 32-bit integer; r0=2 exit with r3 as signed 32-bit code. */
class HostEnv {
    val output = ArrayList<Int>()
}

enum class StopKind { EXIT, FAULT, UNSUPPORTED, STEP_LIMIT }

/** Why execution stopped. Never a silent success: only EXIT with a code is a normal end. */
data class RunResult(
    val stop: StopKind,
    val exitCode: Int?,
    val detail: String?,
    val steps: Long,
    val state: StateSnapshot,
    val output: List<Int>,
    val memoryDigest: String,
)

object HostCalls {
    const val PRINT_INT = 1L
    const val EXIT = 2L

    /** Returns exit code when the program exits, `null` to continue, throws [UnsupportedHostCall] otherwise. */
    fun handle(r0: Long, r3: Long, host: HostEnv): Int? = when (r0) {
        PRINT_INT -> { host.output.add(r3.toInt()); null }
        EXIT -> r3.toInt()
        else -> throw UnsupportedHostCall(r0)
    }
}

class UnsupportedHostCall(val number: Long) : RuntimeException(null, null, false, false)

class UnsupportedInsn(val pc: Long, val word: Int, val reason: String) : RuntimeException(null, null, false, false)

/** Single source of the stop-reason texts, so that every execution engine reports identical details. */
object RunMessages {
    private fun hex(v: Long) = "0x" + v.toString(16)
    fun stepLimit(max: Long) = "step limit $max reached"
    fun fault(f: GuestFault, pc: Long) = "${f.kind} at ${hex(f.address)} (pc=${hex(pc)})"
    fun unsupported(pc: Long, word: Int, reason: String) = "pc=${hex(pc)} word=0x${"%08x".format(word)}: $reason"
    fun hostCall(pc: Long, n: Long) = "pc=${hex(pc)}: host call $n is not supported"
}
