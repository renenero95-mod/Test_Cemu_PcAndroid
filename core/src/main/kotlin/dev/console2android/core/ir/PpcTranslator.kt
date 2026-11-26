package dev.console2android.core.ir

import dev.console2android.core.diag.DiagnosticEvent
import dev.console2android.core.diag.FailureDomain
import dev.console2android.core.diag.RecoveryRoute
import dev.console2android.core.diag.Severity
import dev.console2android.core.ppc.Addi
import dev.console2android.core.ppc.Addis
import dev.console2android.core.ppc.Alu
import dev.console2android.core.ppc.Branch
import dev.console2android.core.ppc.BranchCond
import dev.console2android.core.ppc.BranchLr
import dev.console2android.core.ppc.CmpImm
import dev.console2android.core.ppc.CmpReg
import dev.console2android.core.ppc.Insn
import dev.console2android.core.ppc.Lwz
import dev.console2android.core.ppc.Mfspr
import dev.console2android.core.ppc.Mtspr
import dev.console2android.core.ppc.Ori
import dev.console2android.core.ppc.PpcDecoder
import dev.console2android.core.ppc.Sc
import dev.console2android.core.ppc.Stw
import dev.console2android.core.ppc.Unsupported
import dev.console2android.core.ppc.AluOp as PAluOp

/** Translation outcome. [unsupported] lists every reachable un-modelled instruction (each becomes a [Trap]). */
class TranslationResult(
    val program: IrProgram,
    val events: List<DiagnosticEvent>,
    val instructionCount: Int,
    val unsupported: List<Trap>,
    /** Statically visible direct branch targets outside the translated code range (resolved at run time or fault). */
    val externalTargets: Set<Long>,
)

/**
 * Ahead-of-time translation of the *reachable* code (static control-flow discovery from the entry point through direct
 * branches) of the documented PowerPC subset into C2A-IR. Unreachable words are not translated. Indirect targets
 * (`blr` to a computed LR) that were not discovered are handled at run time by the explicit interpreter fallback.
 */
object PpcTranslator {
    private const val MODULE = "ppc-translator"
    private const val STAGE = "translate"

    fun translate(code: ByteArray, codeBase: Long, entry: Long): TranslationResult {
        require(code.size % 4 == 0) { "code size must be a multiple of 4" }
        val end = codeBase + code.size
        fun inRange(a: Long) = a >= codeBase && a < end && (a and 3L) == 0L
        fun word(a: Long): Int {
            val o = (a - codeBase).toInt()
            return ((code[o].toInt() and 0xFF) shl 24) or ((code[o + 1].toInt() and 0xFF) shl 16) or
                ((code[o + 2].toInt() and 0xFF) shl 8) or (code[o + 3].toInt() and 0xFF)
        }

        // Pass 1: discover reachable instructions and block leaders.
        val leaders = sortedSetOf<Long>()
        val reachable = sortedMapOf<Long, Insn>()
        val external = sortedSetOf<Long>()
        val work = ArrayDeque<Long>()
        fun lead(a: Long) { if (inRange(a)) { if (leaders.add(a)) work.addLast(a) } else external.add(a) }
        lead(entry)
        while (work.isNotEmpty()) {
            var pc = work.removeFirst()
            while (inRange(pc) && pc !in reachable) {
                val insn = PpcDecoder.decode(word(pc))
                reachable[pc] = insn
                when (insn) {
                    is Branch -> { lead(pc + insn.offset); if (insn.link) lead(pc + 4); break }
                    is BranchCond -> { lead(pc + insn.offset); if (insn.link || !isUnconditionalBo(insn.bo)) lead(pc + 4); break }
                    is BranchLr -> { if (insn.link || !isUnconditionalBo(insn.bo)) lead(pc + 4); break }
                    is Unsupported -> break
                    else -> pc += 4
                }
            }
        }

        // Pass 2: build blocks.
        val blocks = ArrayList<IrBlock>()
        val traps = ArrayList<Trap>()
        for (start in leaders) {
            val steps = ArrayList<IrStep>()
            var pc = start
            var term: Terminator? = null
            while (term == null) {
                val insn = reachable[pc]
                if (insn == null) { term = FallThrough(pc); break } // ran past the code range: resolves to a run-time fetch fault
                when (insn) {
                    is Branch -> term = Jump(pc, pc + insn.offset, if (insn.link) pc + 4 else null)
                    is BranchCond -> term = CondBranch(pc, insn.bo, insn.bi, pc + insn.offset, pc + 4, if (insn.link) pc + 4 else null)
                    is BranchLr -> term = ReturnLr(pc, insn.bo, insn.bi, pc + 4, if (insn.link) pc + 4 else null)
                    is Unsupported -> term = Trap(pc, insn.word, insn.reason).also { traps += it }
                    else -> {
                        steps += IrStep(pc, lower(insn))
                        pc += 4
                        if (pc in leaders) term = FallThrough(pc)
                    }
                }
            }
            blocks += IrBlock(start, steps, checkNotNull(term))
        }

        val events = ArrayList<DiagnosticEvent>()
        for (t in traps) {
            events += DiagnosticEvent(
                "PPC-UNSUPPORTED-INSN", Severity.ERROR, FailureDomain.CPU_SEMANTICS, MODULE, STAGE,
                "0x${t.pc.toString(16)}: ${t.reason}", RecoveryRoute.UNSUPPORTED, subject = "0x%08x".format(t.word),
            )
        }
        for (a in external) {
            events += DiagnosticEvent(
                "PPC-EXTERNAL-TARGET", Severity.WARNING, FailureDomain.MEMORY_MODEL, MODULE, STAGE,
                "Direct branch target 0x${a.toString(16)} lies outside the translated code range.", subject = "0x${a.toString(16)}",
            )
        }
        return TranslationResult(IrProgram(entry, codeBase, code.size, blocks), events, reachable.size, traps, external)
    }

    private fun base(ra: Int): Operand = if (ra == 0) Imm(0) else Reg(ra)

    private fun lower(i: Insn): List<IrOp> = when (i) {
        is Addi -> listOf(AluOp(BinOp.ADD, i.rd, base(i.ra), Imm(i.simm.toLong())))
        is Addis -> listOf(AluOp(BinOp.ADD, i.rd, base(i.ra), Imm(i.simm.toLong() shl 16)))
        is Ori -> listOf(AluOp(BinOp.OR, i.ra, Reg(i.rs), Imm(i.uimm.toLong())))
        is Alu -> {
            val main = when (i.op) {
                PAluOp.ADD -> AluOp(BinOp.ADD, i.dst, Reg(i.a), Reg(i.b))
                PAluOp.SUBF -> AluOp(BinOp.SUB, i.dst, Reg(i.b), Reg(i.a))
                PAluOp.AND -> AluOp(BinOp.AND, i.dst, Reg(i.a), Reg(i.b))
                PAluOp.OR -> AluOp(BinOp.OR, i.dst, Reg(i.a), Reg(i.b))
            }
            if (i.rc) listOf(main, SetCr0(i.dst)) else listOf(main)
        }
        is Lwz -> listOf(Load32(i.rd, base(i.ra), i.disp.toLong()))
        is Stw -> listOf(Store32(i.rs, base(i.ra), i.disp.toLong()))
        is CmpImm -> listOf(Cmp32(i.crf, i.signed, Reg(i.ra), Imm(i.imm.toLong())))
        is CmpReg -> listOf(Cmp32(i.crf, i.signed, Reg(i.ra), Reg(i.rb)))
        is Mfspr -> listOf(GetSpr(i.rd, i.spr))
        is Mtspr -> listOf(SetSpr(i.spr, i.rs))
        Sc -> listOf(HostCall)
        is Branch, is BranchCond, is BranchLr, is Unsupported -> error("terminators are lowered by the block builder")
    }
}
