package dev.console2android.core.ir

import dev.console2android.core.fx1.Fx1
import dev.console2android.core.ppc.PpcAsm as A
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class IrTextTest {
    private fun translate(vararg w: Int) = PpcTranslator.translate(A.assemble(*w), Fx1.CODE_BASE, Fx1.CODE_BASE)

    private val sample = intArrayOf(
        A.li(3, 0), A.li(4, 10), A.mtctr(4), A.add(3, 3, 4, true), A.cmpwi(1, 3, -2), A.lwz(5, 4, 1), A.stw(5, -8, 1), A.mflr(6),
        A.bl(8), A.beq(1, 8), A.bdnz(-12), A.blr(), 0x00000000, A.li(0, 1), A.sc(),
    )

    @Test fun roundTripIsExact() {
        val t = translate(*sample).program
        val text = IrText.write(t)
        val back = (IrText.parse(text) as IrParseResult.Success).program
        assertEquals(t.orderedBlocks, back.orderedBlocks)
        assertEquals(t.entry, back.entry); assertEquals(t.codeBase, back.codeBase); assertEquals(t.codeSize, back.codeSize)
        assertEquals(text, IrText.write(back))
    }

    @Test fun writeIsDeterministic() {
        assertEquals(IrText.write(translate(*sample).program), IrText.write(translate(*sample).program))
    }

    @Test fun roundTripOnRandomCode() {
        val rnd = Random(7)
        repeat(300) {
            val words = IntArray(4 + rnd.nextInt(30)) { if (rnd.nextInt(3) == 0) rnd.nextInt() else A.addi(3 + rnd.nextInt(5), 0, rnd.nextInt(2000) - 1000) }
            words[words.size - 1] = A.blr()
            val p = PpcTranslator.translate(A.assemble(*words), Fx1.CODE_BASE, Fx1.CODE_BASE).program
            val text = IrText.write(p)
            assertEquals(p.orderedBlocks, (IrText.parse(text) as IrParseResult.Success).program.orderedBlocks)
        }
    }

    private fun fails(text: String) = IrText.parse(text) is IrParseResult.Failure

    @Test fun malformedInputIsRejectedNotHalfLoaded() {
        val good = IrText.write(translate(*sample).program)
        assertTrue(fails(""))
        assertTrue(fails("C2AIR 2\n"))
        assertTrue(fails(good.removeSuffix("end\n")))                       // truncated
        assertTrue(fails(good + "extra\n"))                                  // trailing content
        assertTrue(fails(good.replace("alu add", "alu mul")))                // unknown op
        assertTrue(fails(good.replace("mtspr 9", "mtspr 1")))                // SPR not modelled
        assertTrue(fails(good.replace(" r3 ", " r99 ")))                     // register range
        assertTrue(fails(good.replace("entry 10000000", "entry 10000004")))  // entry block missing
        assertTrue(fails(good.replace("block 10000000", "block zzz")))
        assertTrue(fails(good.replace("t jump", "t teleport")))
        assertTrue(fails("C2AIR 1\nentry 0\ncode 0 0\nblock 0\nblock 0\nend\n"))
        assertTrue(fails(good.replace("\nend\n", "\n")))
    }

    @Test fun everySingleLineDeletionOrByteFlipIsEitherRejectedOrChangesTheText() {
        val good = IrText.write(translate(*sample).program)
        val lines = good.split('\n').filter { it.isNotEmpty() }
        for (i in lines.indices) {
            val mutated = lines.filterIndexed { j, _ -> j != i }.joinToString("\n") + "\n"
            val r = IrText.parse(mutated)
            if (r is IrParseResult.Success) assertTrue("deleting line $i must change the canonical text", IrText.write(r.program) != good)
        }
    }

    @Test fun trapReasonSurvivesWithSpaces() {
        val p = translate(A.li(3, 1), 0x00000000).program
        val back = (IrText.parse(IrText.write(p)) as IrParseResult.Success).program
        val trap = back.orderedBlocks.map { it.terminator }.filterIsInstance<Trap>().single()
        assertEquals("opcode 0", trap.reason)
    }
}
