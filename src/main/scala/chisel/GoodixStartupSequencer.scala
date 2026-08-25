package chisel

import chisel3._
import chisel3.util._

class GoodixStartupSequencerIO extends Bundle {
  val resetn: Bool = Output(Bool())
  val interruptOut: Bool = Output(Bool())
  val interruptOutputEnable: Bool = Output(Bool())
  val ready: Bool = Output(Bool())
}

/** 在系统启动时固定选择 Goodix 的 0x5d I2C 地址。
  *
  * 状态机先保持 RESET 和 INT 为低，随后释放 RESET 并继续保持 INT 为低，
  * 最后将 INT 切换为输入。它只负责启动地址选择，不处理运行时触摸复位。
  */
class GoodixStartupSequencer(resetHoldCycles: Int, interruptHoldCycles: Int) extends Module {
  require(resetHoldCycles >= 1)
  require(interruptHoldCycles >= 1)

  val io: GoodixStartupSequencerIO = IO(new GoodixStartupSequencerIO)

  private val maximumCycles = math.max(resetHoldCycles, interruptHoldCycles)
  private val counterWidth = math.max(1, log2Ceil(maximumCycles))
  private val holdReset :: holdInterrupt :: running :: Nil = Enum(3)

  val state = RegInit(holdReset)
  val counter = RegInit(0.U(counterWidth.W))

  io.resetn := state =/= holdReset
  io.interruptOut := false.B
  io.interruptOutputEnable := state =/= running
  io.ready := state === running

  switch(state) {
    is(holdReset) {
      when(counter === (resetHoldCycles - 1).U) {
        counter := 0.U
        state := holdInterrupt
      }.otherwise {
        counter := counter + 1.U
      }
    }
    is(holdInterrupt) {
      when(counter === (interruptHoldCycles - 1).U) {
        counter := 0.U
        state := running
      }.otherwise {
        counter := counter + 1.U
      }
    }
    is(running) {
      counter := 0.U
    }
  }
}
