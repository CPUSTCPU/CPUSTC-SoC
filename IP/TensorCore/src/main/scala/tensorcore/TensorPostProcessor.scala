package tensorcore

import chisel3._
import chisel3.util._
import fudian.FMUL

object TensorFloat32 {
  /** IEEE-754-aware maximum for finite inference values.
    *
    * A single NaN loses to a numeric operand; two NaNs keep the accumulator.
    * Positive zero wins over negative zero.
    */
  def maximum(value: UInt, accumulator: UInt): UInt = {
    val valueNaN = value(30, 23).andR && value(22, 0).orR
    val accumulatorNaN = accumulator(30, 23).andR && accumulator(22, 0).orR
    val bothZero = !value(30, 0).orR && !accumulator(30, 0).orR
    val valueGreater = Mux(
      valueNaN,
      false.B,
      Mux(
        accumulatorNaN,
        true.B,
        Mux(
          bothZero,
          !value(31) && accumulator(31),
          Mux(
            value(31) =/= accumulator(31),
            !value(31),
            Mux(!value(31), value(30, 0) > accumulator(30, 0), value(30, 0) < accumulator(30, 0))
          )
        )
      )
    )
    Mux(valueGreater, value, accumulator)
  }
}

/** Scalar FP32 PReLU and optional max accumulation.
  *
  * Positive values are multiplied by 1.0, while negative values use alpha.
  * The shared scalar pipeline keeps the post-processing footprint small; the
  * GEMM controller invokes it once per output column.
  */
class TensorPostProcessor(expWidth: Int = 8, precision: Int = 24) extends Module {
  require(expWidth + precision == 32)

  val io = IO(new Bundle {
    val start = Input(Bool())
    val value = Input(UInt(32.W))
    val alpha = Input(UInt(32.W))
    val preluEnable = Input(Bool())
    val accumulateMax = Input(Bool())
    val accumulator = Input(UInt(32.W))
    val roundMode = Input(UInt(3.W))

    val busy = Output(Bool())
    val done = Output(Bool())
    val result = Output(UInt(32.W))
  })

  private val valueReg = RegInit(0.U(32.W))
  private val factorReg = RegInit("h3f800000".U(32.W))
  private val accumulatorReg = RegInit(0.U(32.W))
  private val accumulateReg = RegInit(false.B)
  private val roundModeReg = RegInit(0.U(3.W))
  private val resultReg = RegInit(0.U(32.W))
  private val doneReg = RegInit(false.B)

  private val sIdle :: sIssue :: sWait0 :: sCapture :: Nil = Enum(4)
  private val state = RegInit(sIdle)
  private val multiplier = Module(new FMUL(expWidth, precision))
  multiplier.io.a := valueReg
  multiplier.io.b := factorReg
  multiplier.io.rm := roundModeReg

  io.busy := state =/= sIdle
  io.done := doneReg
  io.result := resultReg
  doneReg := false.B

  when(state === sIdle && io.start) {
    valueReg := io.value
    factorReg := Mux(io.preluEnable && io.value(31), io.alpha, "h3f800000".U)
    accumulatorReg := io.accumulator
    accumulateReg := io.accumulateMax
    roundModeReg := io.roundMode
    state := sIssue
  }.elsewhen(state === sIssue) {
    state := sWait0
  }.elsewhen(state === sWait0) {
    state := sCapture
  }.elsewhen(state === sCapture) {
    resultReg := Mux(
      accumulateReg,
      TensorFloat32.maximum(multiplier.io.result, accumulatorReg),
      multiplier.io.result
    )
    doneReg := true.B
    state := sIdle
  }
}
