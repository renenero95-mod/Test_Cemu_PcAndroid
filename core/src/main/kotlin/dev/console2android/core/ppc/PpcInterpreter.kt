package dev.console2android.core.ppc

/**
 * Reference interpreter for the documented subset. It is the semantic oracle that translated code is compared against.
 * Unsupported instructions, faults and host calls stop execution with a typed [RunResult]; nothing is approximated.
 */
class PpcInterpreter(private val mem: GuestMemory, private val host: HostEnv = HostEnv()) {

    /** Instructions executed (incremented before execution, like a retired-instruction counter that includes faulting ones). */
    var steps = 0L
        private set

    /** Executes one instruction. Returns an exit code if the program exited. Throws [GuestFault], [UnsupportedInsn], [UnsupportedHostCall]. */
    fun step(state: PpcState): Int? {
        val insn = PpcDecoder.decode(mem.fetch(state.pc))
        if (insn is Unsupported) throw UnsupportedInsn(state.pc, insn.word, insn.reason)
        steps++
        return execute(insn, state)
    }

    fun run(state: PpcState, maxSteps: Long = DEFAULT_MAX_STEPS): RunResult {
        steps = 0L
        fun end(stop: StopKind, code: Int? = null, detail: String? = null) =
            RunResult(stop, code, detail, steps, state.snapshot(), host.output.toList(), mem.writableDigest())
        try {
            while (true) {
                if (steps >= maxSteps) return end(StopKind.STEP_LIMIT, detail = RunMessages.stepLimit(maxSteps))
                val exit = step(state)
                if (exit != null) return end(StopKind.EXIT, code = exit)
            }
        } catch (f: GuestFault) {
            return end(StopKind.FAULT, detail = RunMessages.fault(f, state.pc))
        } catch (u: UnsupportedInsn) {
            return end(StopKind.UNSUPPORTED, detail = RunMessages.unsupported(u.pc, u.word, u.reason))
        } catch (h: UnsupportedHostCall) {
            return end(StopKind.UNSUPPORTED, detail = RunMessages.hostCall(state.pc, h.number))
        }
    }

    private fun base(s: PpcState, ra: Int): Long = if (ra == 0) 0L else s.gpr[ra]

    private fun setCrField(s: PpcState, crf: Int, lt: Boolean, gt: Boolean, eq: Boolean) {
        val nibble = (if (lt) 8 else 0) or (if (gt) 4 else 0) or (if (eq) 2 else 0) // SO = 0 (XER not modelled)
        val shift = 28 - 4 * crf
        s.cr = (s.cr and (0xF shl shift).inv()) or (nibble shl shift)
    }

    private fun crBit(s: PpcState, i: Int): Int = (s.cr ushr (31 - i)) and 1

    /** Evaluates BO/BI; decrements CTR when required. */
    private fun branchTaken(s: PpcState, bo: Int, bi: Int): Boolean {
        var ctrOk = true
        if (bo and 4 == 0) {
            s.ctr -= 1
            ctrOk = (s.ctr != 0L) != (bo and 2 != 0)
        }
        val condOk = (bo and 16 != 0) || crBit(s, bi) == ((bo ushr 3) and 1)
        return ctrOk && condOk
    }

    /** Executes one instruction; returns an exit code if the program exited. */
    private fun execute(i: Insn, s: PpcState): Int? {
        val next = s.pc + 4
        when (i) {
            is Addi -> { s.gpr[i.rd] = base(s, i.ra) + i.simm.toLong(); s.pc = next }
            is Addis -> { s.gpr[i.rd] = base(s, i.ra) + (i.simm.toLong() shl 16); s.pc = next }
            is Ori -> { s.gpr[i.ra] = s.gpr[i.rs] or i.uimm.toLong(); s.pc = next }
            is Alu -> {
                val a = s.gpr[i.a]; val b = s.gpr[i.b]
                val r = when (i.op) { AluOp.ADD -> a + b; AluOp.SUBF -> b - a; AluOp.AND -> a and b; AluOp.OR -> a or b }
                s.gpr[i.dst] = r
                if (i.rc) setCrField(s, 0, (r < 0), (r > 0), (r == 0L))
                s.pc = next
            }
            is Lwz -> { s.gpr[i.rd] = mem.load32(base(s, i.ra) + i.disp); s.pc = next }
            is Stw -> { mem.store32(base(s, i.ra) + i.disp, s.gpr[i.rs]); s.pc = next }
            is CmpImm -> {
                val a = s.gpr[i.ra].toInt()
                if (i.signed) setCrField(s, i.crf, (a < i.imm), (a > i.imm), (a == i.imm))
                else {
                    val ua = a.toLong() and 0xFFFFFFFFL; val ub = i.imm.toLong()
                    setCrField(s, i.crf, (ua < ub), (ua > ub), (ua == ub))
                }
                s.pc = next
            }
            is CmpReg -> {
                val a = s.gpr[i.ra].toInt(); val b = s.gpr[i.rb].toInt()
                if (i.signed) setCrField(s, i.crf, (a < b), (a > b), (a == b))
                else {
                    val ua = a.toLong() and 0xFFFFFFFFL; val ub = b.toLong() and 0xFFFFFFFFL
                    setCrField(s, i.crf, (ua < ub), (ua > ub), (ua == ub))
                }
                s.pc = next
            }
            is Branch -> { if (i.link) s.lr = next; s.pc = s.pc + i.offset }
            is BranchCond -> {
                val target = s.pc + i.offset
                val taken = branchTaken(s, i.bo, i.bi)
                if (i.link) s.lr = next
                s.pc = if (taken) target else next
            }
            is BranchLr -> {
                val target = s.lr and 3L.inv()
                val taken = branchTaken(s, i.bo, i.bi)
                if (i.link) s.lr = next
                s.pc = if (taken) target else next
            }
            is Mfspr -> { s.gpr[i.rd] = if (i.spr == Spr.LR) s.lr else s.ctr; s.pc = next }
            is Mtspr -> { if (i.spr == Spr.LR) s.lr = s.gpr[i.rs] else s.ctr = s.gpr[i.rs]; s.pc = next }
            Sc -> {
                val code = HostCalls.handle(s.gpr[0], s.gpr[3], host)
                s.pc = next
                return code
            }
            is Unsupported -> error("unreachable: filtered before execute")
        }
        return null
    }

    companion object { const val DEFAULT_MAX_STEPS = 1_000_000L }
}
