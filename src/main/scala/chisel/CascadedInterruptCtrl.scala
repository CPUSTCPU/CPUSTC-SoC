package chisel

import chisel3._
import chisel3.experimental.ExtModule

/** SpinalHDL 级联中断控制器的原始 Verilog 端口声明，不改变接口方向和位宽。 */
class RawCascadedInterruptCtrl extends ExtModule {
  override def desiredName: String = "CascadedInterruptCtrl"

  val io_apb_PADDR: UInt = IO(Input(UInt(4.W)))
  val io_apb_PSEL: UInt = IO(Input(UInt(1.W)))
  val io_apb_PENABLE: Bool = IO(Input(Bool()))
  val io_apb_PREADY: Bool = IO(Output(Bool()))
  val io_apb_PWRITE: Bool = IO(Input(Bool()))
  val io_apb_PWDATA: UInt = IO(Input(UInt(32.W)))
  val io_apb_PRDATA: UInt = IO(Output(UInt(32.W)))
  val io_apb_PSLVERROR: Bool = IO(Output(Bool()))
  val io_inputs: UInt = IO(Input(UInt(8.W)))
  val io_pendings: UInt = IO(Output(UInt(8.W)))
  val io_interrupt: Bool = IO(Output(Bool()))
  val clk: Clock = IO(Input(Clock()))
  val reset: Bool = IO(Input(Bool()))
}

/** 级联中断控制器的 Chisel 边界，只整理 APB3、时钟、复位和中断信号。 */
class CascadedInterruptCtrlIO extends Bundle {
  val clock: Clock = Input(Clock())
  val reset: Bool = Input(Bool())
  val apb: APB3IO = Flipped(new APB3IO(addrWidth = 4))
  val inputs: UInt = Input(UInt(8.W))
  val pendings: UInt = Output(UInt(8.W))
  val interrupt: Bool = Output(Bool())
}

/** 将 SpinalHDL 原始端口映射到本项目 APB3 Bundle，不实现额外寄存器或中断逻辑。 */
class CascadedInterruptCtrl extends RawModule {
  override def desiredName: String = "CascadedInterruptCtrlChisel"

  val io: CascadedInterruptCtrlIO = IO(new CascadedInterruptCtrlIO)
  val raw: RawCascadedInterruptCtrl = Module(new RawCascadedInterruptCtrl)

  raw.clk := io.clock
  raw.reset := io.reset
  raw.io_apb_PADDR := io.apb.paddr
  raw.io_apb_PSEL := io.apb.psel
  raw.io_apb_PENABLE := io.apb.penable
  io.apb.pready := raw.io_apb_PREADY
  raw.io_apb_PWRITE := io.apb.pwrite
  raw.io_apb_PWDATA := io.apb.pwdata
  io.apb.prdata := raw.io_apb_PRDATA
  io.apb.pslverr := raw.io_apb_PSLVERROR
  raw.io_inputs := io.inputs
  io.pendings := raw.io_pendings
  io.interrupt := raw.io_interrupt
}
