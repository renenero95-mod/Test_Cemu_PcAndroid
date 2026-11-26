package dev.console2android.core.ppc

import dev.console2android.core.fx1.Fx1
import dev.console2android.core.fx1.Fx1Image
import dev.console2android.core.fx1.Fx1ParseResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import dev.console2android.core.ppc.PpcAsm as A

class PpcInterpreterTest {
    /** Runs words as a program at Fx1.CODE_BASE with optional data. */
    private fun run(vararg words: Int, data: ByteArray = ByteArray(0), maxSteps: Long = 100_000, setup: (PpcState) -> Unit = {}): RunResult {
        val img = Fx1Image(0, A.assemble(*words), data)
        val st = Fx1.initialState(img); setup(st)
        return PpcInterpreter(Fx1.newMemory(img)).run(st, maxSteps)
    }

    private fun exit() = intArrayOf(A.li(0, 2), A.sc())

    // ---- encodings checked against well-known real PowerPC words ----
    @Test fun knownEncodings() {
        assertEquals(0x4E800020, A.blr())
        assertEquals(0x7C0802A6, A.mflr(0))
        assertEquals(0x7C0803A6, A.mtlr(0))
        assertEquals(0x7C6903A6, A.mtctr(3))
        assertEquals(0x60000000, A.nop())
        assertEquals(0x38600001, A.li(3, 1))
        assertEquals(0x3C600001, A.lis(3, 1))
        assertEquals(0x44000002, A.sc())
        assertEquals(0x7C632214, A.add(3, 3, 4))
        assertEquals(0x7C641850, A.subf(3, 4, 3))
        assertEquals(0x80610008.toInt(), A.lwz(3, 8, 1))
        assertEquals(0x90610008.toInt(), A.stw(3, 8, 1))
        assertEquals(0x2C030000, A.cmpwi(0, 3, 0))
    }

    @Test fun decodeEncodeRoundTrip() {
        val cases = mapOf(
            A.addi(3, 4, -5) to Addi(3, 4, -5), A.addis(3, 0, 0x7FFF) to Addis(3, 0, 0x7FFF),
            A.ori(5, 6, 0xFFFF) to Ori(5, 6, 0xFFFF), A.lwz(7, -8, 1) to Lwz(7, 1, -8), A.stw(7, 12, 1) to Stw(7, 1, 12),
            A.add(1, 2, 3, true) to Alu(AluOp.ADD, 1, 2, 3, true), A.subf(1, 2, 3) to Alu(AluOp.SUBF, 1, 2, 3, false),
            A.and(1, 2, 3) to Alu(AluOp.AND, 1, 2, 3, false), A.or(1, 2, 3, true) to Alu(AluOp.OR, 1, 2, 3, true),
            A.cmpwi(3, 4, -1) to CmpImm(3, true, 4, -1), A.cmplwi(2, 4, 65535) to CmpImm(2, false, 4, 65535),
            A.cmpw(1, 2, 3) to CmpReg(1, true, 2, 3), A.cmplw(7, 2, 3) to CmpReg(7, false, 2, 3),
            A.b(-8) to Branch(-8, false), A.bl(16) to Branch(16, true), A.b((1 shl 25) - 4) to Branch((1 shl 25) - 4, false),
            A.beq(1, -12) to BranchCond(12, 6, -12, false), A.bdnz(-4) to BranchCond(16, 0, -4, false),
            A.blr() to BranchLr(20, 0, false), A.bclr(20, 0, true) to BranchLr(20, 0, true),
            A.mflr(5) to Mfspr(5, Spr.LR), A.mtctr(6) to Mtspr(Spr.CTR, 6), A.sc() to Sc,
        )
        for ((w, expected) in cases) assertEquals("0x${"%08x".format(w)}", expected, PpcDecoder.decode(w))
    }

    @Test fun unsupportedAreExplicit() {
        fun why(w: Int) = (PpcDecoder.decode(w) as Unsupported).reason
        assertTrue(why(0x00000000).contains("opcode 0"))
        assertTrue(why(A.add(1, 2, 3) or (1 shl 10)).contains("OE"))           // add. with OE
        assertTrue(why(A.b(8) or 2).contains("absolute"))                         // AA=1
        assertTrue(why(A.bc(12, 2, 8) or 2).contains("absolute"))
        assertTrue(why(A.cmpwi(0, 1, 1) or (1 shl 21)).contains("64-bit"))        // L=1
        assertTrue(why(A.bc(0b11010, 0, 8)).contains("BO"))                       // reserved BO
        assertTrue(why(A.mfspr(3, 1)).contains("SPR"))                            // XER
        assertTrue(why(0x44000022).contains("sc"))
        assertTrue(why((19 shl 26) or (528 shl 1)).contains("opcode 19"))         // bcctr
        assertTrue(why((31 shl 26) or (23 shl 1)).contains("opcode 31"))          // lwzx
    }

    // ---- instruction semantics ----
    @Test fun addiAddisLiLis() {
        val r = run(A.li(3, -1), A.lis(4, 0x1234), A.addi(5, 4, 0x56), A.addis(6, 4, -1), *exit())
        assertEquals(-1L, r.state.gpr[3])
        assertEquals(0x12340000L, r.state.gpr[4])
        assertEquals(0x12340056L, r.state.gpr[5])
        assertEquals(0x12340000L - 0x10000L, r.state.gpr[6])
    }

    @Test fun raZeroMeansLiteralZeroNotR0() {
        val r = run(A.li(0, 100), A.addi(3, 0, 7), *exit(), setup = {})
        // r0 is overwritten by exit li, but addi 3,0,7 executed before with r0=100: must still be 7
        assertEquals(7L, r.state.gpr[3])
    }

    @Test fun aluAndSixtyFourBitWidth() {
        val r = run(
            A.li(3, 5), A.li(4, 3), A.add(5, 3, 4), A.subf(6, 4, 3), A.and(7, 3, 4), A.or(8, 3, 4),
            A.lis(9, -1), A.ori(9, 9, 0xFFFF), A.add(10, 9, 9), // r9 = 0xFFFF_FFFF_FFFF_FFFF (sign-extended lis) | ... = -1
            *exit(),
        )
        assertEquals(8L, r.state.gpr[5]); assertEquals(2L, r.state.gpr[6]) // subf rD,rA,rB = rB - rA = r3 - r4 = 5 - 3
        assertEquals(1L, r.state.gpr[7]); assertEquals(7L, r.state.gpr[8])
        assertEquals(-1L, r.state.gpr[9]); assertEquals(-2L, r.state.gpr[10])
    }

    @Test fun oriDoesNotSignExtend() {
        val r = run(A.li(3, 0), A.ori(3, 3, 0x8000), *exit())
        assertEquals(0x8000L, r.state.gpr[3])
    }

    @Test fun rcRecordFormSetsCr0() {
        val r = run(A.li(3, 1), A.li(4, -1), A.add(5, 3, 4, true), *exit())     // 0 -> EQ
        assertEquals(0b0010 shl 28, r.state.cr)
        val n = run(A.li(3, -4), A.li(4, 1), A.add(5, 3, 4, true), *exit())     // negative -> LT
        assertEquals(0b1000 shl 28, n.state.cr)
        val p = run(A.li(3, 4), A.li(4, 1), A.or(5, 3, 4, true), *exit())       // positive -> GT
        assertEquals(0b0100 shl 28, p.state.cr)
        val none = run(A.li(3, 4), A.add(5, 3, 3), *exit())                      // no Rc -> CR untouched
        assertEquals(0, none.state.cr)
    }

    @Test fun compareSignedUnsignedAndFields() {
        val r = run(
            A.li(3, -1), A.li(4, 1),
            A.cmpw(0, 3, 4),   // signed: -1 < 1 -> LT
            A.cmplw(1, 3, 4),  // unsigned: 0xFFFFFFFF > 1 -> GT
            A.cmpwi(2, 4, 1),  // EQ
            A.cmplwi(7, 4, 2), // unsigned 1 < 2 -> LT in field 7
            *exit(),
        )
        assertEquals(0b1000, (r.state.cr ushr 28) and 0xF)
        assertEquals(0b0100, (r.state.cr ushr 24) and 0xF)
        assertEquals(0b0010, (r.state.cr ushr 20) and 0xF)
        assertEquals(0b1000, r.state.cr and 0xF)
    }

    @Test fun compareUsesLow32BitsOnly() {
        val big = run(A.lis(3, 0x7FFF), A.ori(3, 3, 0xFFFF), A.add(3, 3, 3), A.add(3, 3, 3), A.cmpwi(0, 3, -4), *exit())
        // r3 = 0x7FFFFFFF*4 = 0x1_FFFF_FFFC ; low 32 bits = 0xFFFFFFFC = -4 signed -> EQ
        assertEquals(0x1_FFFF_FFFCL, big.state.gpr[3])
        assertEquals(0b0010, (big.state.cr ushr 28) and 0xF)
    }

    @Test fun loadStoreBigEndianAndZeroExtend() {
        val data = byteArrayOf(0x80.toByte(), 0x00, 0x00, 0x01, 0, 0, 0, 0)
        val r = run(
            A.lis(3, 0x2000), A.lwz(4, 0, 3), A.stw(4, 4, 3), A.lwz(5, 4, 3), *exit(), data = data,
        )
        assertEquals(0x80000001L, r.state.gpr[4]) // zero-extended, not negative
        assertEquals(0x80000001L, r.state.gpr[5])
        assertEquals(StopKind.EXIT, r.stop)
    }

    @Test fun stackIsUsable() {
        val r = run(A.li(3, 77), A.stw(3, -8, 1), A.lwz(4, -8, 1), *exit())
        assertEquals(77L, r.state.gpr[4])
    }

    // ---- control flow ----
    @Test fun countedLoopWithBdnzSumsOneToTen() {
        val r = run(
            A.li(3, 0), A.li(4, 10), A.mtctr(4), A.li(5, 0),
            /*loop:*/ A.addi(5, 5, 1), A.add(3, 3, 5), A.bdnz(-8),
            A.li(0, 1), A.sc(), A.li(3, 0), A.li(0, 2), A.sc(),
        )
        assertEquals(StopKind.EXIT, r.stop); assertEquals(0, r.exitCode)
        assertEquals(listOf(55), r.output)
        assertEquals(0L, r.state.ctr)
    }

    @Test fun callReturnExplicitTrace() {
        val r = run(
            /*00*/ A.li(3, 20),
            /*04*/ A.bl(20),          // -> 0x18, LR = 0x08
            /*08*/ A.li(0, 1),
            /*0C*/ A.sc(),            // print 42
            /*10*/ A.li(0, 2),
            /*14*/ A.sc(),            // exit(r3=42)
            /*18*/ A.addi(3, 3, 22),  // callee
            /*1C*/ A.blr(),
        )
        assertEquals(StopKind.EXIT, r.stop); assertEquals(42, r.exitCode); assertEquals(listOf(42), r.output)
        assertEquals(Fx1.CODE_BASE + 8, r.state.lr)
    }

    @Test fun conditionalBranchesAllSenses() {
        fun branchy(cmp: Int, br: Int): Long {
            // r3 = 0 ; cmp ; br -> +8 skips 'li r3,1' ; li r3,1 ; exit
            val r = run(A.li(3, 0), A.li(4, 5), cmp, br, A.li(3, 1), *exit())
            return r.state.gpr[3]
        }
        assertEquals(0L, branchy(A.cmpwi(0, 4, 5), A.beq(0, 8)))
        assertEquals(1L, branchy(A.cmpwi(0, 4, 6), A.beq(0, 8)))
        assertEquals(0L, branchy(A.cmpwi(0, 4, 6), A.bne(0, 8)))
        assertEquals(0L, branchy(A.cmpwi(0, 4, 6), A.blt(0, 8)))
        assertEquals(1L, branchy(A.cmpwi(0, 4, 5), A.blt(0, 8)))
        assertEquals(0L, branchy(A.cmpwi(0, 4, 4), A.bgt(0, 8)))
        assertEquals(0L, branchy(A.cmpwi(0, 4, 5), A.ble(0, 8)))
        assertEquals(0L, branchy(A.cmpwi(0, 4, 5), A.bge(0, 8)))
        assertEquals(1L, branchy(A.cmpwi(0, 4, 6), A.bge(0, 8)))
        assertEquals(0L, branchy(A.cmpwi(5, 4, 5), A.beq(5, 8))) // another CR field
    }

    @Test fun bdnzDecrementsFirstAndFallsThroughAtZero() {
        val r = run(A.li(3, 1), A.mtctr(3), A.bdnz(8), A.li(4, 9), *exit())
        assertEquals(0L, r.state.ctr)
        assertEquals(9L, r.state.gpr[4]) // ctr 1 -> 0 : not taken, falls through
    }

    @Test fun bcLinkSetsLrOnlyAsDocumented() {
        val r = run(A.li(3, 0), A.cmpwi(0, 3, 0), A.bc(12, 2, 8, link = true), A.nop(), *exit())
        assertEquals(Fx1.CODE_BASE + 12, r.state.lr)
    }

    // ---- failure modes: never a silent success ----
    @Test fun unsupportedInstructionStopsWithDiagnostic() {
        val r = run(A.li(3, 1), 0x00000000)
        assertEquals(StopKind.UNSUPPORTED, r.stop)
        assertTrue(r.detail!!.contains("opcode 0")); assertEquals(null, r.exitCode)
        assertEquals(1L, r.state.gpr[3]) // state up to the failing instruction is preserved
    }

    @Test fun unknownHostCallIsUnsupported() {
        val r = run(A.li(0, 99), A.sc())
        assertEquals(StopKind.UNSUPPORTED, r.stop); assertTrue(r.detail!!.contains("host call 99"))
    }

    @Test fun faultsAreTypedAndLeaveStateConsistent() {
        assertTrue(run(A.li(3, 0), A.lwz(4, 0, 3)).detail!!.contains("UNMAPPED_READ"))
        assertTrue(run(A.li(3, 0), A.stw(4, 0, 3)).detail!!.contains("UNMAPPED_WRITE"))
        assertTrue(run(A.lis(3, 0x1000), A.stw(4, 0, 3)).detail!!.contains("WRITE_TO_READ_ONLY"))
        assertTrue(run(A.lis(3, 0x2000), A.lwz(4, 2, 3), data = ByteArray(16)).detail!!.contains("UNALIGNED"))
        assertTrue(run(A.li(3, 1), A.mtlr(3), A.blr()).detail!!.contains("BAD_FETCH")) // LR & ~3 = 0 is unmapped
        assertTrue(run(A.b(0x100000)).detail!!.contains("BAD_FETCH"))
        assertEquals(StopKind.FAULT, run(A.nop()).stop) // runs off the end of code
    }

    @Test fun readNearRegionEndIsBoundsChecked() {
        val r = run(A.lis(3, 0x2000), A.lwz(4, 16, 3), data = ByteArray(16)) // reads [16,20) of a 16-byte region
        assertEquals(StopKind.FAULT, r.stop)
        val ok = run(A.lis(3, 0x2000), A.lwz(4, 12, 3), *exit(), data = ByteArray(16))
        assertEquals(StopKind.EXIT, ok.stop)
    }

    @Test fun stepLimitStopsInfiniteLoops() {
        val r = run(A.b(0), maxSteps = 1000)
        assertEquals(StopKind.STEP_LIMIT, r.stop); assertEquals(1000L, r.steps)
    }

    @Test fun exitCodeIsSigned32() {
        assertEquals(-3, run(A.li(3, -3), *exit()).exitCode)
    }

    @Test fun memoryDigestReflectsWrites() {
        val a = run(A.li(3, 1), A.stw(3, -4, 1), *exit()).memoryDigest
        val b = run(A.li(3, 2), A.stw(3, -4, 1), *exit()).memoryDigest
        val c = run(A.li(3, 1), A.stw(3, -4, 1), *exit()).memoryDigest
        assertTrue(a != b); assertEquals(a, c)
    }

    @Test fun fx1ContainerRoundTripAndValidation() {
        val code = A.assemble(A.li(3, 7), A.li(0, 2), A.sc())
        val bytes = Fx1.build(0, code, byteArrayOf(1, 2, 3, 4))
        val img = (Fx1.parse(bytes) as Fx1ParseResult.Success).image
        assertTrue(img.code.contentEquals(code)); assertEquals(4, img.data.size)
        fun codes(b: ByteArray) = (Fx1.parse(b) as Fx1ParseResult.Failure).events.map { it.code }
        assertEquals(listOf("FX1-TRUNCATED"), codes(ByteArray(10)))
        assertEquals(listOf("FX1-MAGIC"), codes(ByteArray(40)))
        assertEquals(listOf("FX1-DATA-RANGE"), codes(bytes.copyOf(bytes.size - 2))) // truncated data section
        assertTrue("FX1-ENTRY" in codes(Fx1.build(2, code)))
        assertTrue("FX1-ENTRY" in codes(Fx1.build(12, code)))
        assertTrue("FX1-CODE-SIZE" in codes(Fx1.build(0, ByteArray(6))))
        assertTrue("FX1-CODE-SIZE" in codes(Fx1.build(0, ByteArray(0))))
        val tampered = bytes.copyOf().also { it[16] = 0x7F; it[17] = 0x7F } // huge code size
        assertTrue(codes(tampered).isNotEmpty())
    }
}
