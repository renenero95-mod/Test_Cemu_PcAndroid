package dev.console2android.core.ir

sealed interface IrParseResult {
    data class Success(val program: IrProgram) : IrParseResult
    data class Failure(val line: Int, val message: String) : IrParseResult
}

/**
 * Canonical text form of C2A-IR v1 (deterministic: blocks sorted by address). The SHA-256 of this text identifies the
 * translated output. The parser is strict: any unknown token, out-of-range field or structural problem is a [IrParseResult.Failure]
 * naming the line (never the offending content beyond the token kind), so a corrupted package cannot be half-loaded.
 */
object IrText {
    const val HEADER = "C2AIR 1"
    const val MAX_LINES = 400_000

    fun write(p: IrProgram): String = buildString {
        append(HEADER).append('\n')
        append("entry ").append(hex(p.entry)).append('\n')
        append("code ").append(hex(p.codeBase)).append(' ').append(p.codeSize).append('\n')
        for (b in p.orderedBlocks) {
            append("block ").append(hex(b.start)).append('\n')
            for (s in b.steps) append("s ").append(hex(s.pc)).append(' ').append(s.ops.joinToString(" ; ") { op(it) }).append('\n')
            append("t ").append(term(b.terminator)).append('\n')
        }
        append("end\n")
    }

    private fun hex(v: Long) = v.toString(16)
    private fun opd(o: Operand) = when (o) { is Reg -> "r${o.n}"; is Imm -> "#${o.v}" }
    private fun lnk(l: Long?) = l?.let { hex(it) } ?: "-"

    private fun op(o: IrOp): String = when (o) {
        is AluOp -> "alu ${o.op.name.lowercase()} ${o.dst} ${opd(o.a)} ${opd(o.b)}"
        is Load32 -> "ld ${o.dst} ${opd(o.base)} ${o.disp}"
        is Store32 -> "st ${o.src} ${opd(o.base)} ${o.disp}"
        is Cmp32 -> "cmp ${o.crf} ${if (o.signed) "s" else "u"} ${opd(o.a)} ${opd(o.b)}"
        is SetCr0 -> "rec ${o.src}"
        is GetSpr -> "mfspr ${o.dst} ${o.spr}"
        is SetSpr -> "mtspr ${o.spr} ${o.src}"
        HostCall -> "host"
    }

    private fun term(t: Terminator): String = when (t) {
        is Jump -> "jump ${hex(t.pc)} ${hex(t.target)} ${lnk(t.link)}"
        is CondBranch -> "cond ${hex(t.pc)} ${t.bo} ${t.bi} ${hex(t.target)} ${hex(t.fallthrough)} ${lnk(t.link)}"
        is ReturnLr -> "ret ${hex(t.pc)} ${t.bo} ${t.bi} ${hex(t.fallthrough)} ${lnk(t.link)}"
        is FallThrough -> "fall ${hex(t.target)}"
        is Trap -> "trap ${hex(t.pc)} ${"%08x".format(t.word)} ${t.reason}"
    }

    private class Bad(val msg: String) : RuntimeException(null, null, false, false)

    fun parse(text: String): IrParseResult {
        val lines = text.split('\n')
        if (lines.size > MAX_LINES) return IrParseResult.Failure(1, "too many lines")
        var lineNo = 0
        try {
            fun bad(m: String): Nothing = throw Bad(m)
            fun long16(s: String): Long = s.toLongOrNull(16) ?: bad("bad hex number")
            fun int10(s: String, lo: Int, hi: Int): Int = s.toIntOrNull()?.takeIf { it in lo..hi } ?: bad("number out of range")
            fun addr(s: String): Long = long16(s).also { if (it < 0) bad("negative address") }
            fun operand(s: String): Operand = when {
                s.startsWith("r") -> Reg(int10(s.substring(1), 0, 31))
                s.startsWith("#") -> Imm(s.substring(1).toLongOrNull() ?: bad("bad immediate"))
                else -> bad("bad operand")
            }
            fun link(s: String): Long? = if (s == "-") null else addr(s)

            fun parseOp(t: String): IrOp {
                val a = t.trim().split(' ')
                return when (a[0]) {
                    "alu" -> if (a.size != 5) bad("alu arity") else AluOp(
                        BinOp.entries.firstOrNull { it.name.lowercase() == a[1] } ?: bad("bad alu op"), int10(a[2], 0, 31), operand(a[3]), operand(a[4]),
                    )
                    "ld" -> if (a.size != 4) bad("ld arity") else Load32(int10(a[1], 0, 31), operand(a[2]), a[3].toLongOrNull() ?: bad("bad displacement"))
                    "st" -> if (a.size != 4) bad("st arity") else Store32(int10(a[1], 0, 31), operand(a[2]), a[3].toLongOrNull() ?: bad("bad displacement"))
                    "cmp" -> if (a.size != 5 || (a[2] != "s" && a[2] != "u")) bad("cmp form") else Cmp32(int10(a[1], 0, 7), a[2] == "s", operand(a[3]), operand(a[4]))
                    "rec" -> if (a.size != 2) bad("rec arity") else SetCr0(int10(a[1], 0, 31))
                    "mfspr" -> if (a.size != 3) bad("mfspr arity") else GetSpr(int10(a[1], 0, 31), int10(a[2], 8, 9))
                    "mtspr" -> if (a.size != 3) bad("mtspr arity") else SetSpr(int10(a[1], 8, 9), int10(a[2], 0, 31))
                    "host" -> if (a.size != 1) bad("host arity") else HostCall
                    else -> bad("unknown op")
                }
            }

            fun parseTerm(rest: String): Terminator {
                val a = rest.split(' ', limit = 4)
                return when (a[0]) {
                    "jump" -> rest.split(' ').let { x -> if (x.size != 4) bad("jump arity") else Jump(addr(x[1]), addr(x[2]), link(x[3])) }
                    "cond" -> rest.split(' ').let { x -> if (x.size != 7) bad("cond arity") else CondBranch(addr(x[1]), int10(x[2], 0, 31), int10(x[3], 0, 31), addr(x[4]), addr(x[5]), link(x[6])) }
                    "ret" -> rest.split(' ').let { x -> if (x.size != 6) bad("ret arity") else ReturnLr(addr(x[1]), int10(x[2], 0, 31), int10(x[3], 0, 31), addr(x[4]), link(x[5])) }
                    "fall" -> rest.split(' ').let { x -> if (x.size != 2) bad("fall arity") else FallThrough(addr(x[1])) }
                    "trap" -> rest.split(' ', limit = 4).let { x ->
                        if (x.size != 4) bad("trap arity") else Trap(addr(x[1]), (x[2].toLongOrNull(16) ?: bad("bad word")).toInt(), x[3])
                    }
                    else -> bad("unknown terminator")
                }
            }

            fun next(): String? { while (lineNo < lines.size) { val l = lines[lineNo++]; if (l.isNotEmpty()) return l }; return null }

            if (next() != HEADER) { return IrParseResult.Failure(lineNo, "bad header") }
            var l = next() ?: bad("missing entry")
            if (!l.startsWith("entry ")) bad("expected entry")
            val entry = addr(l.removePrefix("entry "))
            l = next() ?: bad("missing code line")
            val cp = l.split(' ')
            if (cp.size != 3 || cp[0] != "code") bad("expected code line")
            val codeBase = addr(cp[1]); val codeSize = int10(cp[2], 0, 1 shl 24)

            val blocks = ArrayList<IrBlock>()
            val seen = HashSet<Long>()
            while (true) {
                l = next() ?: bad("missing end marker")
                if (l == "end") break
                if (!l.startsWith("block ")) bad("expected block")
                val start = addr(l.removePrefix("block "))
                if (!seen.add(start)) bad("duplicate block")
                val steps = ArrayList<IrStep>()
                var term: Terminator? = null
                while (term == null) {
                    l = next() ?: bad("unterminated block")
                    when {
                        l.startsWith("s ") -> {
                            val body = l.removePrefix("s ")
                            val sp = body.indexOf(' ')
                            if (sp < 0) bad("empty step")
                            val pc = addr(body.substring(0, sp))
                            if (pc and 3L != 0L) bad("unaligned pc")
                            steps += IrStep(pc, body.substring(sp + 1).split(" ; ").map { parseOp(it) })
                        }
                        l.startsWith("t ") -> term = parseTerm(l.removePrefix("t "))
                        else -> bad("expected step or terminator")
                    }
                }
                blocks += IrBlock(start, steps, term)
            }
            if (next() != null) bad("trailing content")
            if (entry !in seen) bad("entry block missing")
            return IrParseResult.Success(IrProgram(entry, codeBase, codeSize, blocks))
        } catch (b: Bad) {
            return IrParseResult.Failure(lineNo, b.msg)
        }
    }
}
