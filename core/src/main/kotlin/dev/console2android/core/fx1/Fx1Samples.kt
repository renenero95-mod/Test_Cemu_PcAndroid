package dev.console2android.core.fx1

import dev.console2android.core.ppc.PpcAsm as A

/**
 * Built-in synthetic fixtures (our own container, hand-assembled from the documented PowerPC subset). They let the whole
 * import -> convert -> validate -> run path be exercised without any third-party content.
 */
object Fx1Samples {
    const val SUM_AND_DOUBLE_NAME = "sample-sum.c2afx1"

    /** Guest output of [sumAndDouble]: doubled sum (via bl/blr), then the sum reloaded from data memory. */
    val SUM_AND_DOUBLE_OUTPUT: List<Int> = listOf(110, 55)

    /**
     * Sums 1..10 in a loop, stores the sum to data memory, calls a subroutine that doubles it (bl/blr), prints the doubled
     * value and the value reloaded from memory, then exits with code 0.
     */
    fun sumAndDouble(): ByteArray {
        val code = A.assemble(
            A.li(3, 0),             // 0  sum = 0
            A.li(4, 1),             // 1  i = 1
            A.cmpwi(0, 4, 10),      // 2  loop: i ? 10
            A.bgt(0, 16),           // 3  if i > 10 goto 7
            A.add(3, 3, 4),         // 4  sum += i
            A.addi(4, 4, 1),        // 5  i++
            A.b(-16),               // 6  goto 2
            A.lis(5, 0x2000),       // 7  r5 = DATA_BASE
            A.stw(3, 0, 5),         // 8  [data] = sum
            A.bl(36),               // 9  call double (index 18)
            A.li(0, 1),             // 10 print r3
            A.sc(),                 // 11
            A.lwz(3, 0, 5),         // 12 r3 = [data]
            A.li(0, 1),             // 13 print r3
            A.sc(),                 // 14
            A.li(3, 0),             // 15
            A.li(0, 2),             // 16 exit(0)
            A.sc(),                 // 17
            A.add(3, 3, 3),         // 18 double: r3 *= 2
            A.blr(),                // 19
        )
        return Fx1.build(0, code, ByteArray(8))
    }
}
