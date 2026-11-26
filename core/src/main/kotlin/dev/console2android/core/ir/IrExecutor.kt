package dev.console2android.core.ir

import dev.console2android.core.ppc.GuestFault
import dev.console2android.core.ppc.GuestMemory
import dev.console2android.core.ppc.HostCalls
import dev.console2android.core.ppc.HostEnv
import dev.console2android.core.ppc.PpcInterpreter
import dev.console2android.core.ppc.PpcState
import dev.console2android.core.ppc.RunMessages
import dev.console2android.core.ppc.RunResult
import dev.console2android.core.ppc.Spr
import dev.console2android.core.ppc.StopKind
import dev.console2android.core.ppc.UnsupportedHostCall
import dev.console2android.core.ppc.UnsupportedInsn

/** [fallbackSteps] = guest instructions that had no translated block and ran in the reference interpreter. */
data class ExecutionOutcome(val run: RunResult, val translatedSteps: Long, val fallbackSteps: Long, val fallbackPcs: List<Long>)

/**
 * Runs an [IrProgram]. Independent of the reference interpreter's instruction semantics (it only shares memory, host
 * calls and message texts), so differential tests are meaningful. When control reaches an address with no translated
 * block, exactly one instruction is executed by the interpreter (explicit, counted fallback; never silent).
 */
class IrExecutor(private val program: IrProgram, private val mem: GuestMemory, private val host: HostEnv = HostEnv()) {
    private val fallback = PpcInterpreter(mem, host)

    fun run(s: PpcState, maxSteps: Long = PpcInterpreter.DEFAULT_MAX_STEPS): ExecutionOutcome {
        var irSteps = 0L
        val fbPcs = ArrayList<Long>()
        fun total() = irSteps + fallback.steps
        fun end(stop: StopKind, code: Int? = null, detail: String? = null) = ExecutionOutcome(
            RunResult(stop, code, detail, total(), s.snapshot(), host.output.toList(), mem.writableDigest()),
            irSteps, fallback.steps, fbPcs,
        )
        try {
            while (true) {
                val block = program.blocks[s.pc]
                if (block == null) {
                    if (total() >= maxSteps) return end(StopKind.STEP_LIMIT, detail = RunMessages.stepLimit(maxSteps))
                    fbPcs.add(s.pc)
                    val exit = fallback.step(s) // may throw the same typed exceptions as the interpreter
                    if (exit != null) return end(StopKind.EXIT, code = exit)
                    continue
                }
                for (step in block.steps) {
                    if (total() >= maxSteps) { return end(StopKind.STEP_LIMIT, detail = RunMessages.stepLimit(maxSteps)) }
                    s.pc = step.pc
                    irSteps++
                    for (op in step.ops) {
                        if (op === HostCall) {
                            val code = HostCalls.handle(s.gpr[0], s.gpr[3], host)
                            if (code != null) { s.pc = step.pc + 4; return end(StopKind.EXIT, code = code) }
                        } else exec(op, s)
                    }
                    s.pc = step.pc + 4
                }
                val t = block.terminator
                if (t is FallThrough) { s.pc = t.target; continue }
                if (total() >= maxSteps) return end(StopKind.STEP_LIMIT, detail = RunMessages.stepLimit(maxSteps))
                when (t) {
                    is Trap -> { s.pc = t.pc; throw UnsupportedInsn(t.pc, t.word, t.reason) }
                    is Jump -> { s.pc = t.pc; irSteps++; if (t.link != null) s.lr = t.link; s.pc = t.target }
                    is CondBranch -> {
                        s.pc = t.pc; irSteps++
                        val taken = taken(s, t.bo, t.bi)
                        if (t.link != null) s.lr = t.link
                        s.pc = if (taken) t.target else t.fallthrough
                    }
                    is ReturnLr -> {
                        s.pc = t.pc; irSteps++
                        val target = s.lr and 3L.inv()
                        val taken = taken(s, t.bo, t.bi)
                        if (t.link != null) s.lr = t.link
                        s.pc = if (taken) target else t.fallthrough
                    }
                    is FallThrough -> error("handled above")
                }
            }
        } catch (f: GuestFault) {
            return end(StopKind.FAULT, detail = RunMessages.fault(f, s.pc))
        } catch (u: UnsupportedInsn) {
            return end(StopKind.UNSUPPORTED, detail = RunMessages.unsupported(u.pc, u.word, u.reason))
        } catch (h: UnsupportedHostCall) {
            return end(StopKind.UNSUPPORTED, detail = RunMessages.hostCall(s.pc, h.number))
        }
    }

    private fun value(o: Operand, s: PpcState): Long = when (o) { is Reg -> s.gpr[o.n]; is Imm -> o.v }

    private fun setCr(s: PpcState, field: Int, lt: Boolean, gt: Boolean, eq: Boolean) {
        val nib = (if (lt) 8 else 0) or (if (gt) 4 else 0) or (if (eq) 2 else 0)
        val sh = 28 - 4 * field
        s.cr = (s.cr and (0xF shl sh).inv()) or (nib shl sh)
    }

    private fun exec(op: IrOp, s: PpcState) {
        when (op) {
            is AluOp -> {
                val a = value(op.a, s); val b = value(op.b, s)
                s.gpr[op.dst] = when (op.op) { BinOp.ADD -> a + b; BinOp.SUB -> a - b; BinOp.AND -> a and b; BinOp.OR -> a or b }
            }
            is Load32 -> s.gpr[op.dst] = mem.load32(value(op.base, s) + op.disp)
            is Store32 -> mem.store32(value(op.base, s) + op.disp, s.gpr[op.src])
            is Cmp32 -> {
                val x = value(op.a, s).toInt(); val y = value(op.b, s).toInt()
                if (op.signed) setCr(s, op.crf, (x < y), (x > y), (x == y))
                else {
                    val ux = x.toLong() and 0xFFFFFFFFL; val uy = y.toLong() and 0xFFFFFFFFL
                    setCr(s, op.crf, (ux < uy), (ux > uy), (ux == uy))
                }
            }
            is SetCr0 -> { val v = s.gpr[op.src]; setCr(s, 0, (v < 0L), (v > 0L), (v == 0L)) }
            is GetSpr -> s.gpr[op.dst] = if (op.spr == Spr.LR) s.lr else s.ctr
            is SetSpr -> { val v = s.gpr[op.src]; if (op.spr == Spr.LR) s.lr = v else s.ctr = v }
            HostCall -> error("handled by the caller")
        }
    }

    /** BO/BI evaluation, written from the architecture description (not copied from the interpreter). */
    private fun taken(s: PpcState, bo: Int, bi: Int): Boolean {
        val testCr = (bo and 16) == 0
        val crWanted = (bo and 8) != 0
        val decrement = (bo and 4) == 0
        val ctrZeroWanted = (bo and 2) != 0
        var ok = true
        if (decrement) {
            s.ctr -= 1
            val ctrIsZero = s.ctr == 0L
            ok = ok && (ctrIsZero == ctrZeroWanted)
        }
        if (testCr) {
            val bitSet = ((s.cr ushr (31 - bi)) and 1) == 1
            ok = ok && (bitSet == crWanted)
        }
        return ok
    }
}
