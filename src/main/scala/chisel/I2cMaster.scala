package chisel

import chisel3._
import chisel3.experimental.ExtModule

class RawI2cMaster extends ExtModule {
  override def desiredName: String = "i2c_master_top"

  val wb_clk_i: Clock = IO(Input(Clock()))
  val wb_rst_i: Bool = IO(Input(Bool()))
  val arst_i: Bool = IO(Input(Bool()))
  val wb_adr_i: UInt = IO(Input(UInt(3.W)))
  val wb_dat_i: UInt = IO(Input(UInt(8.W)))
  val wb_dat_o: UInt = IO(Output(UInt(8.W)))
  val wb_we_i: Bool = IO(Input(Bool()))
  val wb_stb_i: Bool = IO(Input(Bool()))
  val wb_cyc_i: Bool = IO(Input(Bool()))
  val wb_ack_o: Bool = IO(Output(Bool()))
  val wb_inta_o: Bool = IO(Output(Bool()))

  val scl_pad_i: Bool = IO(Input(Bool()))
  val scl_pad_o: Bool = IO(Output(Bool()))
  val scl_padoen_o: Bool = IO(Output(Bool()))
  val sda_pad_i: Bool = IO(Input(Bool()))
  val sda_pad_o: Bool = IO(Output(Bool()))
  val sda_padoen_o: Bool = IO(Output(Bool()))
}

class I2cPadPort extends Bundle {
  val sclPadI: Bool = Input(Bool())
  val sclPadO: Bool = Output(Bool())
  val sclPadOenO: Bool = Output(Bool())
  val sdaPadI: Bool = Input(Bool())
  val sdaPadO: Bool = Output(Bool())
  val sdaPadOenO: Bool = Output(Bool())
}

class I2cMasterIO extends Bundle {
  val clk: Clock = Input(Clock())
  val resetn: Bool = Input(Bool())
  val wishbone: WishboneMasterPort = Flipped(new WishboneMasterPort)
  val pads: I2cPadPort = new I2cPadPort
  val interrupt: Bool = Output(Bool())
}

/** 封装 Wishbone I²C 控制器，IOBUF 留在板级顶层。 */
class I2cMaster extends RawModule {
  val io: I2cMasterIO = IO(new I2cMasterIO)
  val raw: RawI2cMaster = Module(new RawI2cMaster)

  raw.wb_clk_i := io.clk
  raw.wb_rst_i := !io.resetn
  raw.arst_i := !io.resetn
  raw.wb_adr_i := io.wishbone.addr
  raw.wb_dat_i := io.wishbone.writeData
  io.wishbone.readData := raw.wb_dat_o
  raw.wb_we_i := io.wishbone.write
  raw.wb_stb_i := io.wishbone.strobe
  raw.wb_cyc_i := io.wishbone.cycle
  io.wishbone.acknowledge := raw.wb_ack_o
  io.interrupt := raw.wb_inta_o

  raw.scl_pad_i := io.pads.sclPadI
  io.pads.sclPadO := raw.scl_pad_o
  io.pads.sclPadOenO := raw.scl_padoen_o
  raw.sda_pad_i := io.pads.sdaPadI
  io.pads.sdaPadO := raw.sda_pad_o
  io.pads.sdaPadOenO := raw.sda_padoen_o
}
