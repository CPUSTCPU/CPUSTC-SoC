package chisel.axiSlaveMux.apb.uart

import chisel3._
import chisel3.experimental.ExtModule
import chisel.axiSlaveMux.apb.LegacyApb8Port

class RawUartTop extends ExtModule {
  override def desiredName: String = "UART_TOP"

  val PCLK: Clock = IO(Input(Clock()))
  val PRST_ : Bool = IO(Input(Bool()))
  val PSEL: Bool = IO(Input(Bool()))
  val PENABLE: Bool = IO(Input(Bool()))
  val PADDR: UInt = IO(Input(UInt(8.W)))
  val PWRITE: Bool = IO(Input(Bool()))
  val PWDATA: UInt = IO(Input(UInt(8.W)))
  val URT_PRDATA: UInt = IO(Output(UInt(8.W)))
  val INT: Bool = IO(Output(Bool()))
  val clk_carrier: Bool = IO(Input(Bool()))
  val TXD_i: Bool = IO(Input(Bool()))
  val TXD_o: Bool = IO(Output(Bool()))
  val TXD_oe: Bool = IO(Output(Bool()))
  val RXD_i: Bool = IO(Input(Bool()))
  val RXD_o: Bool = IO(Output(Bool()))
  val RXD_oe: Bool = IO(Output(Bool()))
  val RTS: Bool = IO(Output(Bool()))
  val CTS: Bool = IO(Input(Bool()))
  val DSR: Bool = IO(Input(Bool()))
  val DCD: Bool = IO(Input(Bool()))
  val DTR: Bool = IO(Output(Bool()))
  val RI: Bool = IO(Input(Bool()))
}

class ApbDevUart0Port extends Bundle {
  val txd_i: Bool = Input(Bool())
  val txd_o: Bool = Output(Bool())
  val txd_oe: Bool = Output(Bool())
  val rxd_i: Bool = Input(Bool())
  val rxd_o: Bool = Output(Bool())
  val rxd_oe: Bool = Output(Bool())
  val rts_o: Bool = Output(Bool())
  val dtr_o: Bool = Output(Bool())
  val cts_i: Bool = Input(Bool())
  val dsr_i: Bool = Input(Bool())
  val dcd_i: Bool = Input(Bool())
  val ri_i: Bool = Input(Bool())
}

class UartTopIO extends Bundle {
  val clk: Clock = Input(Clock())
  val resetn: Bool = Input(Bool())
  val apb: LegacyApb8Port = Flipped(new LegacyApb8Port)
  val uart: ApbDevUart0Port = new ApbDevUart0Port
  val interrupt: Bool = Output(Bool())
}

class UartTop extends RawModule {
  val io: UartTopIO = IO(new UartTopIO)
  val raw: RawUartTop = Module(new RawUartTop)

  raw.PCLK := io.clk
  raw.PRST_ := io.resetn
  raw.PSEL := io.apb.psel
  raw.PENABLE := io.apb.penable
  raw.PADDR := io.apb.addr(7, 0)
  raw.PWRITE := io.apb.write
  raw.PWDATA := io.apb.writeData
  io.apb.readData := raw.URT_PRDATA
  io.apb.acknowledge := io.apb.penable
  raw.clk_carrier := false.B
  io.interrupt := raw.INT

  raw.TXD_i := io.uart.txd_i
  io.uart.txd_o := raw.TXD_o
  io.uart.txd_oe := raw.TXD_oe
  raw.RXD_i := io.uart.rxd_i
  io.uart.rxd_o := raw.RXD_o
  io.uart.rxd_oe := raw.RXD_oe
  io.uart.rts_o := raw.RTS
  raw.CTS := io.uart.cts_i
  raw.DSR := io.uart.dsr_i
  raw.DCD := io.uart.dcd_i
  io.uart.dtr_o := raw.DTR
  raw.RI := io.uart.ri_i
}
