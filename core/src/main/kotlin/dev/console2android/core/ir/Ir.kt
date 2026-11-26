package dev.console2android.core.ir

/**
 * C2A-IR v1: a small, explicit intermediate representation of translated guest code (register-transfer form over
 * the 32 guest GPRs). One [IrStep] = exactly one guest instruction, so step counts, faults and `pc` are comparable
 * 1:1 with the reference interpreter. Control flow is carried by block [Terminator]s.
 */
sealed interface Operand
data class Reg(val n: Int) : Operand
data class Imm(val v: Long) : Operand

enum class BinOp { ADD, SUB, AND, OR }

sealed interface IrOp
data class AluOp(val op: BinOp, val dst: Int, val a: Operand, val b: Operand) : IrOp
data class Load32(val dst: Int, val base: Operand, val disp: Long) : IrOp
data class Store32(val src: Int, val base: Operand, val disp: Long) : IrOp
/** Sets CR field [crf] from comparing the low 32 bits of [a] and [b]. */
data class Cmp32(val crf: Int, val signed: Boolean, val a: Operand, val b: Operand) : IrOp
/** Record form: CR0 from the signed 64-bit value of GPR [src] compared with zero. */
data class SetCr0(val src: Int) : IrOp
data class GetSpr(val dst: Int, val spr: Int) : IrOp
data class SetSpr(val spr: Int, val src: Int) : IrOp
data object HostCall : IrOp

/** One guest instruction at [pc] lowered to one or more ops. */
data class IrStep(val pc: Long, val ops: List<IrOp>)

/** [link] = value to store in LR when the branch instruction has LK=1 (always the address after it). */
sealed interface Terminator
/** Unconditional direct branch (a guest instruction at [pc]). */
data class Jump(val pc: Long, val target: Long, val link: Long?) : Terminator
data class CondBranch(val pc: Long, val bo: Int, val bi: Int, val target: Long, val fallthrough: Long, val link: Long?) : Terminator
/** Branch to LR (`bclr`); the target is read from LR at run time. */
data class ReturnLr(val pc: Long, val bo: Int, val bi: Int, val fallthrough: Long, val link: Long?) : Terminator
/** Block ends because the next address is another block's start; not a guest instruction. */
data class FallThrough(val target: Long) : Terminator
/** Guest instruction that is not modelled: execution stops with UNSUPPORTED when reached. */
data class Trap(val pc: Long, val word: Int, val reason: String) : Terminator

data class IrBlock(val start: Long, val steps: List<IrStep>, val terminator: Terminator)

/** [codeBase]/[codeSize] describe the guest code range the program was translated from. */
class IrProgram(val entry: Long, val codeBase: Long, val codeSize: Int, blocks: List<IrBlock>) {
    val blocks: Map<Long, IrBlock> = blocks.associateBy { it.start }.also { require(it.size == blocks.size) { "duplicate block start" } }
    val orderedBlocks: List<IrBlock> get() = blocks.values.sortedBy { it.start }
}

/** `bo` encodes "branch always" (ignores CTR and condition) when bits 0x14 are both set. */
fun isUnconditionalBo(bo: Int): Boolean = (bo and 0x14) == 0x14
