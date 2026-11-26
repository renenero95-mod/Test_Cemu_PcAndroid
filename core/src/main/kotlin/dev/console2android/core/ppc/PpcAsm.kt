package dev.console2android.core.ppc

/** Minimal encoder for the documented subset. Used by the sample-fixture generator and by tests. */
object PpcAsm {
    private fun reg(r: Int): Int { require(r in 0..31) { "register out of range" }; return r }
    private fun d(op: Int, rt: Int, ra: Int, imm: Int): Int {
        require(imm in -32768..65535) { "immediate out of 16-bit range" }
        return (op shl 26) or (reg(rt) shl 21) or (reg(ra) shl 16) or (imm and 0xFFFF)
    }
    private fun x(rt: Int, ra: Int, rb: Int, xo: Int, rc: Boolean) =
        (31 shl 26) or (reg(rt) shl 21) or (reg(ra) shl 16) or (reg(rb) shl 11) or (xo shl 1) or (if (rc) 1 else 0)
    private fun sprEnc(spr: Int) = ((spr and 0x1F) shl 5) or (spr ushr 5)

    fun addi(rd: Int, ra: Int, simm: Int) = d(14, rd, ra, simm).also { require(simm in -32768..32767) }
    fun li(rd: Int, simm: Int) = addi(rd, 0, simm)
    fun addis(rd: Int, ra: Int, simm: Int) = d(15, rd, ra, simm).also { require(simm in -32768..32767) }
    fun lis(rd: Int, simm: Int) = addis(rd, 0, simm)
    fun ori(ra: Int, rs: Int, uimm: Int) = d(24, rs, ra, uimm).also { require(uimm in 0..65535) }
    fun nop() = ori(0, 0, 0)
    fun add(rd: Int, ra: Int, rb: Int, rc: Boolean = false) = x(rd, ra, rb, 266, rc)
    fun subf(rd: Int, ra: Int, rb: Int, rc: Boolean = false) = x(rd, ra, rb, 40, rc)
    fun and(ra: Int, rs: Int, rb: Int, rc: Boolean = false) = x(rs, ra, rb, 28, rc)
    fun or(ra: Int, rs: Int, rb: Int, rc: Boolean = false) = x(rs, ra, rb, 444, rc)
    fun mr(ra: Int, rs: Int) = or(ra, rs, rs)
    fun lwz(rd: Int, disp: Int, ra: Int) = d(32, rd, ra, disp).also { require(disp in -32768..32767) }
    fun stw(rs: Int, disp: Int, ra: Int) = d(36, rs, ra, disp).also { require(disp in -32768..32767) }
    fun cmpwi(crf: Int, ra: Int, simm: Int) = d(11, crf shl 2, ra, simm).also { require(crf in 0..7 && simm in -32768..32767) }
    fun cmplwi(crf: Int, ra: Int, uimm: Int) = d(10, crf shl 2, ra, uimm).also { require(crf in 0..7 && uimm in 0..65535) }
    fun cmpw(crf: Int, ra: Int, rb: Int) = x(crf shl 2, ra, rb, 0, false).also { require(crf in 0..7) }
    fun cmplw(crf: Int, ra: Int, rb: Int) = x(crf shl 2, ra, rb, 32, false).also { require(crf in 0..7) }

    fun b(offset: Int, link: Boolean = false): Int {
        require(offset % 4 == 0 && offset in -(1 shl 25)..((1 shl 25) - 4)) { "bad branch offset" }
        return (18 shl 26) or (offset and 0x03FFFFFC) or (if (link) 1 else 0)
    }
    fun bl(offset: Int) = b(offset, true)
    fun bc(bo: Int, bi: Int, offset: Int, link: Boolean = false): Int {
        require(bo in 0..31 && bi in 0..31 && offset % 4 == 0 && offset in -32768..32764) { "bad bc operands" }
        return (16 shl 26) or (bo shl 21) or (bi shl 16) or (offset and 0xFFFC) or (if (link) 1 else 0)
    }
    fun beq(crf: Int, offset: Int) = bc(12, crf * 4 + 2, offset)
    fun bne(crf: Int, offset: Int) = bc(4, crf * 4 + 2, offset)
    fun blt(crf: Int, offset: Int) = bc(12, crf * 4, offset)
    fun bge(crf: Int, offset: Int) = bc(4, crf * 4, offset)
    fun bgt(crf: Int, offset: Int) = bc(12, crf * 4 + 1, offset)
    fun ble(crf: Int, offset: Int) = bc(4, crf * 4 + 1, offset)
    fun bdnz(offset: Int) = bc(16, 0, offset)
    fun bclr(bo: Int, bi: Int, link: Boolean = false) =
        (19 shl 26) or (bo shl 21) or (bi shl 16) or (16 shl 1) or (if (link) 1 else 0)
    fun blr() = bclr(20, 0)
    fun mfspr(rd: Int, spr: Int) = (31 shl 26) or (reg(rd) shl 21) or (sprEnc(spr) shl 11) or (339 shl 1)
    fun mtspr(spr: Int, rs: Int) = (31 shl 26) or (reg(rs) shl 21) or (sprEnc(spr) shl 11) or (467 shl 1)
    fun mflr(rd: Int) = mfspr(rd, Spr.LR)
    fun mtlr(rs: Int) = mtspr(Spr.LR, rs)
    fun mtctr(rs: Int) = mtspr(Spr.CTR, rs)
    fun sc() = 0x44000002

    /** Big-endian bytes of the given instruction words. */
    fun assemble(vararg words: Int): ByteArray {
        val out = ByteArray(words.size * 4)
        words.forEachIndexed { i, w ->
            out[i * 4] = (w ushr 24).toByte(); out[i * 4 + 1] = (w ushr 16).toByte()
            out[i * 4 + 2] = (w ushr 8).toByte(); out[i * 4 + 3] = w.toByte()
        }
        return out
    }
}
