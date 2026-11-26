package dev.console2android.core.ppc

/**
 * Documented guest instruction subset (big-endian 32-bit words, 64-bit GPRs, "64-bit mode" semantics):
 *
 * `addi addis ori add subf and or lwz stw cmpwi cmplwi cmpw cmplw b bl bc bclr(blr) mfspr/mtspr(LR,CTR) sc`
 *
 * Not modelled (decoded as [Unsupported], never approximated): OE=1 forms (XER overflow), absolute branches (AA=1),
 * 64-bit compares (L=1), reserved BO encodings, all other opcodes and SPRs, and `sc` forms other than 0x44000002.
 */
sealed interface Insn

enum class AluOp { ADD, SUBF, AND, OR }

/** `dst = a OP b` (SUBF: `b - a`, matching `subf rD,rA,rB`). For and/or, dst is the rA field and a is rS. */
data class Alu(val op: AluOp, val dst: Int, val a: Int, val b: Int, val rc: Boolean) : Insn
data class Addi(val rd: Int, val ra: Int, val simm: Int) : Insn // ra == 0 => li
data class Addis(val rd: Int, val ra: Int, val simm: Int) : Insn // ra == 0 => lis
data class Ori(val ra: Int, val rs: Int, val uimm: Int) : Insn
data class Lwz(val rd: Int, val ra: Int, val disp: Int) : Insn
data class Stw(val rs: Int, val ra: Int, val disp: Int) : Insn
/** 32-bit compare against immediate; for signed [imm] is sign-extended, for unsigned zero-extended (0..65535). */
data class CmpImm(val crf: Int, val signed: Boolean, val ra: Int, val imm: Int) : Insn
data class CmpReg(val crf: Int, val signed: Boolean, val ra: Int, val rb: Int) : Insn
data class Branch(val offset: Int, val link: Boolean) : Insn
data class BranchCond(val bo: Int, val bi: Int, val offset: Int, val link: Boolean) : Insn
data class BranchLr(val bo: Int, val bi: Int, val link: Boolean) : Insn
data class Mfspr(val rd: Int, val spr: Int) : Insn
data class Mtspr(val spr: Int, val rs: Int) : Insn
data object Sc : Insn
data class Unsupported(val word: Int, val reason: String) : Insn

object Spr { const val LR = 8; const val CTR = 9 }

object PpcDecoder {
    /** PowerPC bit numbering: bit 0 is the most significant. Inclusive range. */
    private fun f(w: Int, from: Int, to: Int): Int = (w ushr (31 - to)) and ((1 shl (to - from + 1)) - 1)
    private fun bit(w: Int, n: Int): Boolean = ((w ushr (31 - n)) and 1) != 0
    private fun sext(v: Int, bits: Int): Int = (v shl (32 - bits)) shr (32 - bits)

    private fun bad(w: Int, why: String) = Unsupported(w, why)

    /** BO encodings with set reserved ("z") bits are invalid. Hint bit is ignored. */
    private fun boValid(bo: Int): Boolean = !((bo and 16 != 0 && bo and 8 != 0) || (bo and 4 != 0 && bo and 2 != 0))

    fun decode(w: Int): Insn {
        val rt = f(w, 6, 10)
        val ra = f(w, 11, 15)
        return when (val op = w ushr 26) {
            14 -> Addi(rt, ra, w.toShort().toInt())
            15 -> Addis(rt, ra, w.toShort().toInt())
            24 -> Ori(ra = ra, rs = rt, uimm = w and 0xFFFF)
            32 -> Lwz(rt, ra, w.toShort().toInt())
            36 -> Stw(rt, ra, w.toShort().toInt())
            10, 11 -> {
                if (bit(w, 9)) bad(w, "reserved bit set in compare-immediate")
                else if (bit(w, 10)) bad(w, "64-bit compare (L=1) is not modelled")
                else if (op == 11) CmpImm(f(w, 6, 8), true, ra, w.toShort().toInt())
                else CmpImm(f(w, 6, 8), false, ra, w and 0xFFFF)
            }
            18 -> if (bit(w, 30)) bad(w, "absolute branch (AA=1) is not modelled")
            else Branch(sext(f(w, 6, 29), 24) shl 2, bit(w, 31))
            16 -> if (bit(w, 30)) bad(w, "absolute branch (AA=1) is not modelled")
            else if (!boValid(rt)) bad(w, "reserved BO encoding")
            else BranchCond(rt, ra, sext(f(w, 16, 29), 14) shl 2, bit(w, 31))
            17 -> if (w == 0x44000002) Sc else bad(w, "sc with non-zero reserved fields")
            19 -> if (f(w, 21, 30) == 16) {
                if (!boValid(rt)) bad(w, "reserved BO encoding") else BranchLr(rt, ra, bit(w, 31))
            } else bad(w, "opcode 19 extended op ${f(w, 21, 30)}")
            31 -> decode31(w, rt, ra)
            else -> bad(w, "opcode $op")
        }
    }

    private fun decode31(w: Int, rt: Int, ra: Int): Insn {
        val rb = f(w, 16, 20)
        val xo10 = f(w, 21, 30)
        val rc = bit(w, 31)
        return when (xo10) {
            0, 32 -> when {
                bit(w, 9) || rc -> bad(w, "reserved bits set in compare")
                bit(w, 10) -> bad(w, "64-bit compare (L=1) is not modelled")
                else -> CmpReg(f(w, 6, 8), xo10 == 0, ra, rb)
            }
            28 -> Alu(AluOp.AND, dst = ra, a = rt, b = rb, rc = rc)
            444 -> Alu(AluOp.OR, dst = ra, a = rt, b = rb, rc = rc)
            266 -> Alu(AluOp.ADD, dst = rt, a = ra, b = rb, rc = rc)
            40 -> Alu(AluOp.SUBF, dst = rt, a = ra, b = rb, rc = rc)
            339, 467 -> {
                val spr = (f(w, 16, 20) shl 5) or f(w, 11, 15) // the two 5-bit halves are swapped in the encoding
                when {
                    rc -> bad(w, "reserved bit set in mfspr/mtspr")
                    spr != Spr.LR && spr != Spr.CTR -> bad(w, "SPR $spr is not modelled")
                    xo10 == 339 -> Mfspr(rt, spr)
                    else -> Mtspr(spr, rt)
                }
            }
            else -> if ((xo10 and 0x1FF) == 266 || (xo10 and 0x1FF) == 40) bad(w, "OE=1 (XER overflow) is not modelled")
            else bad(w, "opcode 31 extended op $xo10")
        }
    }
}
