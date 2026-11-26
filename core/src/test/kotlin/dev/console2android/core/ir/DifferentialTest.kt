package dev.console2android.core.ir

import dev.console2android.core.fx1.Fx1
import dev.console2android.core.fx1.Fx1Image
import dev.console2android.core.ppc.PpcAsm as A
import dev.console2android.core.ppc.PpcInterpreter
import dev.console2android.core.ppc.PpcState
import dev.console2android.core.ppc.RunResult
import dev.console2android.core.ppc.StopKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/** Interpreter (oracle) vs. translated IR must be observably identical: stop, exit code, detail, steps, registers, memory, output. */
class DifferentialTest {
    class Pair2(val ref: RunResult, val ir: ExecutionOutcome, val tr: TranslationResult)

    private fun both(words: IntArray, data: ByteArray = ByteArray(0), maxSteps: Long = 20_000, init: (PpcState) -> Unit = {}): Pair2 {
        val img = Fx1Image(0, A.assemble(*words), data)
        val s1 = Fx1.initialState(img).also(init)
        val ref = PpcInterpreter(Fx1.newMemory(img)).run(s1, maxSteps)
        val tr = PpcTranslator.translate(img.code, Fx1.CODE_BASE, Fx1.CODE_BASE + img.entryOffset)
        val s2 = Fx1.initialState(img).also(init)
        val ir = IrExecutor(tr.program, Fx1.newMemory(img)).run(s2, maxSteps)
        return Pair2(ref, ir, tr)
    }

    private fun assertSame(p: Pair2, label: String = "") {
        assertEquals("$label stop", p.ref.stop, p.ir.run.stop)
        assertEquals("$label exit", p.ref.exitCode, p.ir.run.exitCode)
        assertEquals("$label detail", p.ref.detail, p.ir.run.detail)
        assertEquals("$label steps", p.ref.steps, p.ir.run.steps)
        assertEquals("$label state", p.ref.state, p.ir.run.state)
        assertEquals("$label output", p.ref.output, p.ir.run.output)
        assertEquals("$label memory", p.ref.memoryDigest, p.ir.run.memoryDigest)
    }

    private val exit = intArrayOf(A.li(0, 2), A.sc())

    @Test fun sumLoop() {
        val p = both(intArrayOf(
            A.li(3, 0), A.li(4, 10), A.mtctr(4), A.li(5, 0), A.addi(5, 5, 1), A.add(3, 3, 5), A.bdnz(-8),
            A.li(0, 1), A.sc(), A.li(3, 0), A.li(0, 2), A.sc(),
        ))
        assertSame(p); assertEquals(listOf(55), p.ir.run.output); assertEquals(0L, p.ir.fallbackSteps)
    }

    @Test fun callReturn() {
        val p = both(intArrayOf(A.li(3, 20), A.bl(20), A.li(0, 1), A.sc(), A.li(0, 2), A.sc(), A.addi(3, 3, 22), A.blr()))
        assertSame(p); assertEquals(42, p.ir.run.exitCode); assertEquals(0L, p.ir.fallbackSteps)
    }

    @Test fun fibonacciWithMemory() {
        // data[0..] = results; computes fib(1..12) into memory and prints the last
        val p = both(intArrayOf(
            A.lis(11, 0x2000), A.li(3, 0), A.li(4, 1), A.li(6, 12), A.mtctr(6), A.li(7, 0),
            /*loop*/ A.add(5, 3, 4), A.mr(3, 4), A.mr(4, 5), A.stw(5, 0, 11), A.addi(11, 11, 4), A.bdnz(-20),
            A.mr(3, 5), A.li(0, 1), A.sc(), A.li(3, 0), A.li(0, 2), A.sc(),
        ), data = ByteArray(64))
        assertSame(p); assertEquals(listOf(233), p.ir.run.output)
    }

    @Test fun reachableUnsupportedInstructionTraps() {
        val p = both(intArrayOf(A.li(3, 1), 0x00000000, *exit))
        assertSame(p); assertEquals(StopKind.UNSUPPORTED, p.ir.run.stop)
        assertEquals(1, p.tr.unsupported.size)
        assertTrue(p.tr.events.any { it.code == "PPC-UNSUPPORTED-INSN" })
    }

    @Test fun unreachableUnsupportedWordIsNotTranslatedAndNotReported() {
        val p = both(intArrayOf(A.li(3, 5), A.b(12), 0x00000000, 0x00000000, *exit))
        assertSame(p); assertEquals(StopKind.EXIT, p.ir.run.stop); assertTrue(p.tr.unsupported.isEmpty())
    }

    @Test fun faultsMatch() {
        for (w in listOf(
            intArrayOf(A.li(3, 0), A.lwz(4, 0, 3)), intArrayOf(A.li(3, 0), A.stw(4, 0, 3)), intArrayOf(A.lis(3, 0x1000), A.stw(4, 0, 3)),
            intArrayOf(A.lis(3, 0x2000), A.lwz(4, 2, 3)), intArrayOf(A.b(0x100000)), intArrayOf(A.nop()), intArrayOf(A.li(0, 7), A.sc()),
        )) assertSame(both(w, ByteArray(16)), w.joinToString { "%08x".format(it) })
    }

    @Test fun stepLimitMatchesExactly() {
        for (limit in listOf(1L, 2L, 3L, 7L, 100L)) assertSame(both(intArrayOf(A.li(3, 0), A.addi(3, 3, 1), A.b(-4)), maxSteps = limit), "limit=$limit")
        // limit exactly at a terminator and exactly at an op
        for (limit in 1L..12L) assertSame(both(intArrayOf(A.li(3, 3), A.mtctr(3), A.bdnz(0), *exit), maxSteps = limit), "limit=$limit")
    }

    @Test fun indirectReturnToUntranslatedAddressFallsBackToInterpreterExplicitly() {
        // mtlr to an address that is code but not statically reachable; blr then lands there.
        val target = Fx1.CODE_BASE + 24
        val p = both(intArrayOf(
            A.lis(3, (target ushr 16).toInt()), A.ori(3, 3, (target and 0xFFFF).toInt()), A.mtlr(3), A.blr(), A.nop(), A.nop(),
            /*24*/ A.li(3, 9), A.li(0, 1), A.sc(), A.li(3, 0), A.li(0, 2), A.sc(),
        ))
        assertSame(p)
        assertTrue("fallback must be counted, not silent", p.ir.fallbackSteps > 0)
        assertEquals(p.ir.fallbackSteps, p.ir.fallbackPcs.size.toLong())
        assertEquals(listOf(9), p.ir.run.output)
    }

    @Test fun fixedConditionMatrix() {
        val cmps = listOf(A.cmpwi(0, 4, 5), A.cmpwi(0, 4, 6), A.cmpwi(0, 4, 4), A.cmplwi(0, 4, 5), A.cmpw(0, 4, 3), A.cmplw(0, 3, 4))
        val brs = listOf(A.beq(0, 8), A.bne(0, 8), A.blt(0, 8), A.bge(0, 8), A.bgt(0, 8), A.ble(0, 8), A.bdnz(8), A.bc(0, 2, 8), A.bc(2, 2, 8), A.bc(8, 2, 8), A.bc(10, 2, 8), A.bc(20, 0, 8))
        for (c in cmps) for (b in brs) for (ctr in listOf(0L, 1L, 2L)) {
            assertSame(both(intArrayOf(A.li(3, -1), A.li(4, 5), c, b, A.li(6, 1), *exit)) { it.ctr = ctr }, "c=%08x b=%08x ctr=$ctr".format(c, b))
        }
    }

    // -------- randomized differential testing (seeded, reproducible) --------

    private fun randomProgram(rnd: Random): IntArray {
        val n = 8 + rnd.nextInt(40)
        val out = ArrayList<Int>()
        out += A.lis(11, 0x2000) // r11 = data base (some programs clobber it on purpose later)
        fun r() = 3 + rnd.nextInt(8)  // r3..r10
        fun simm() = rnd.nextInt(65536) - 32768
        for (i in 0 until n) {
            val remaining = n - i
            out += when (rnd.nextInt(16)) {
                0 -> A.addi(r(), if (rnd.nextBoolean()) 0 else r(), simm())
                1 -> A.addis(r(), if (rnd.nextBoolean()) 0 else r(), simm())
                2 -> A.ori(r(), r(), rnd.nextInt(65536))
                3 -> A.add(r(), r(), r(), rnd.nextBoolean())
                4 -> A.subf(r(), r(), r(), rnd.nextBoolean())
                5 -> A.and(r(), r(), r(), rnd.nextBoolean())
                6 -> A.or(r(), r(), r(), rnd.nextBoolean())
                7 -> A.cmpwi(rnd.nextInt(8), r(), simm())
                8 -> A.cmplwi(rnd.nextInt(8), r(), rnd.nextInt(65536))
                9 -> if (rnd.nextBoolean()) A.cmpw(rnd.nextInt(8), r(), r()) else A.cmplw(rnd.nextInt(8), r(), r())
                10 -> A.lwz(r(), 4 * rnd.nextInt(16), if (rnd.nextInt(12) == 0) r() else 11)
                11 -> A.stw(r(), 4 * rnd.nextInt(16), if (rnd.nextInt(12) == 0) r() else 11)
                12 -> A.stw(r(), -4 * (1 + rnd.nextInt(8)), 1)
                13 -> if (rnd.nextBoolean()) A.mtctr(r()) else A.mtlr(r())
                14 -> if (rnd.nextBoolean()) A.mflr(r()) else A.mfspr(r(), 9)
                else -> { // forward conditional/unconditional branch within the program (always terminates)
                    val skip = 1 + rnd.nextInt(minOf(3, remaining))
                    when (rnd.nextInt(4)) {
                        0 -> A.b(4 * (skip + 1))
                        1 -> A.bc(listOf(12, 4, 16, 18, 8, 0, 10, 2, 20)[rnd.nextInt(9)], rnd.nextInt(32), 4 * (skip + 1), rnd.nextInt(5) == 0)
                        2 -> A.beq(rnd.nextInt(8), 4 * (skip + 1))
                        else -> A.bgt(rnd.nextInt(8), 4 * (skip + 1))
                    }
                }
            }
        }
        out.addAll(listOf(A.li(0, 1), A.mr(3, r()), A.sc(), A.li(0, 2), A.sc()))
        return out.toIntArray()
    }

    @Test fun randomPrograms() {
        var exits = 0; var faults = 0
        for (seed in 1L..600L) {
            val rnd = Random(seed)
            val words = randomProgram(rnd)
            val regs = LongArray(32) { rnd.nextLong() }
            val p = both(words, ByteArray(64) { rnd.nextInt().toByte() }, maxSteps = 5_000) { s -> for (i in 3..10) s.gpr[i] = regs[i]; s.ctr = regs[0] and 7; s.lr = regs[1] }
            assertSame(p, "seed=$seed")
            if (p.ir.run.stop == StopKind.EXIT) exits++ else faults++
        }
        assertTrue("generator should reach both normal exits and faults ($exits/$faults)", exits > 50 && faults > 20)
    }

    @Test fun randomWordsDecodeAndExecuteIdentically() {
        val rnd = Random(2026)
        var unsupported = 0
        repeat(4000) { i ->
            val w = if (i % 2 == 0) rnd.nextInt() else (rnd.nextInt(64) shl 26) or (rnd.nextInt() and 0x03FFFFFF)
            val regs = LongArray(32) { rnd.nextLong() }
            val p = both(intArrayOf(w, *exit), ByteArray(64), maxSteps = 100) { s -> for (k in 1..31) s.gpr[k] = regs[k]; s.gpr[1] = Fx1.STACK_TOP - 0x20; s.ctr = regs[2] and 3 }
            assertSame(p, "word=%08x".format(w))
            if (p.ir.run.stop == StopKind.UNSUPPORTED) unsupported++
        }
        assertTrue(unsupported > 1000)
    }
}
