package chisel

import chisel3._
import chisel3.util.log2Ceil

class RetirementStallWatchdogIO(laneCount: Int, countWidth: Int) extends Bundle {
  val clear: Bool = Input(Bool())
  val commitValid: UInt = Input(UInt(laneCount.W))
  val commitPc: Vec[UInt] = Input(Vec(laneCount, UInt(32.W)))
  val noRetireCount: UInt = Output(UInt(countWidth.W))
  val trigger: Bool = Output(Bool())
  val lastCommitPc: UInt = Output(UInt(32.W))
}

/** Detects a sustained absence of architectural retirement.
  *
  * The watchdog remains workload-agnostic. Hardware Manager combines its
  * trigger with the last-retired PC needed by a specific experiment.
  */
class RetirementStallWatchdog(
    laneCount: Int = 3,
    thresholdCycles: Int = 512
) extends Module {
  require(laneCount > 0)
  require(thresholdCycles >= 2)

  private val countWidth = log2Ceil(thresholdCycles + 1)

  val io: RetirementStallWatchdogIO =
    IO(new RetirementStallWatchdogIO(laneCount, countWidth))

  val noRetireCountReg: UInt = RegInit(0.U(countWidth.W))
  val lastCommitPcReg: UInt = RegInit(0.U(32.W))

  when(io.clear) {
    noRetireCountReg := 0.U
    lastCommitPcReg := 0.U
  }.elsewhen(io.commitValid.orR) {
    noRetireCountReg := 0.U
    for (lane <- 0 until laneCount) {
      when(io.commitValid(lane)) {
        lastCommitPcReg := io.commitPc(lane)
      }
    }
  }.elsewhen(noRetireCountReg < thresholdCycles.U) {
    noRetireCountReg := noRetireCountReg + 1.U
  }

  io.noRetireCount := noRetireCountReg
  io.trigger := noRetireCountReg === thresholdCycles.U
  io.lastCommitPc := lastCommitPcReg
}
