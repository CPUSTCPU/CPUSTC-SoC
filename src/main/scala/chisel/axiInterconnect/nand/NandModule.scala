package chisel.axiInterconnect.nand

import chisel3._
import chisel3.experimental.ExtModule
import chisel.axiSlaveMux.apb.LegacyApb32Port

class RawNandModule extends ExtModule {
  override def desiredName: String = "nand_module"

  val nand_type: UInt = IO(Input(UInt(2.W)))
  val clk: Clock = IO(Input(Clock()))
  val rst_n: Bool = IO(Input(Bool()))
  val apb_psel: Bool = IO(Input(Bool()))
  val apb_enab: Bool = IO(Input(Bool()))
  val apb_rw: Bool = IO(Input(Bool()))
  val apb_addr: UInt = IO(Input(UInt(20.W)))
  val apb_datai: UInt = IO(Input(UInt(32.W)))
  val apb_datao: UInt = IO(Output(UInt(32.W)))
  val apb_ack: Bool = IO(Output(Bool()))
  val nand_dma_req_o: Bool = IO(Output(Bool()))
  val nand_dma_ack_i: Bool = IO(Input(Bool()))
  val nand_ce: UInt = IO(Output(UInt(4.W)))
  val nand_dat_i: UInt = IO(Input(UInt(8.W)))
  val nand_dat_o: UInt = IO(Output(UInt(8.W)))
  val nand_dat_oe: Bool = IO(Output(Bool()))
  val nand_ale: Bool = IO(Output(Bool()))
  val nand_cle: Bool = IO(Output(Bool()))
  val nand_wr: Bool = IO(Output(Bool()))
  val nand_rd: Bool = IO(Output(Bool()))
  val nand_rdy: UInt = IO(Input(UInt(4.W)))
  val nand_int: Bool = IO(Output(Bool()))
}

class NandSplitPort extends Bundle {
  val nandType: UInt = Input(UInt(2.W))
  val cle: Bool = Output(Bool())
  val ale: Bool = Output(Bool())
  val rdy: UInt = Input(UInt(4.W))
  val rd: Bool = Output(Bool())
  val ce: UInt = Output(UInt(4.W))
  val wr: Bool = Output(Bool())
  val dat_i: UInt = Input(UInt(8.W))
  val dat_o: UInt = Output(UInt(8.W))
  val dat_oe: Bool = Output(Bool())
}

class NandModuleIO extends Bundle {
  val clk: Clock = Input(Clock())
  val resetn: Bool = Input(Bool())
  val apb: LegacyApb32Port = Flipped(new LegacyApb32Port)
  val dmaRequest: Bool = Output(Bool())
  val dmaAcknowledge: Bool = Input(Bool())
  val nand: NandSplitPort = new NandSplitPort
  val interrupt: Bool = Output(Bool())
}

class NandModule extends RawModule {
  val io: NandModuleIO = IO(new NandModuleIO)
  val raw: RawNandModule = Module(new RawNandModule)

  raw.clk := io.clk
  raw.rst_n := io.resetn
  raw.apb_psel := io.apb.psel
  raw.apb_enab := io.apb.penable
  raw.apb_rw := io.apb.write
  raw.apb_addr := io.apb.addr
  raw.apb_datai := io.apb.writeData
  io.apb.readData := raw.apb_datao
  io.apb.acknowledge := raw.apb_ack
  io.dmaRequest := raw.nand_dma_req_o
  raw.nand_dma_ack_i := io.dmaAcknowledge

  raw.nand_type := io.nand.nandType
  io.nand.ce := raw.nand_ce
  raw.nand_dat_i := io.nand.dat_i
  io.nand.dat_o := raw.nand_dat_o
  io.nand.dat_oe := raw.nand_dat_oe
  io.nand.ale := raw.nand_ale
  io.nand.cle := raw.nand_cle
  io.nand.wr := raw.nand_wr
  io.nand.rd := raw.nand_rd
  raw.nand_rdy := io.nand.rdy
  io.interrupt := raw.nand_int
}
