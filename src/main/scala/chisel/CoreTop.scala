package chisel

import chisel3._
import chisel3.experimental.ExtModule

/** CPUSTCore 与 SoC 之间的纯功能 SystemVerilog 适配层。 */
class RawCoreTop extends ExtModule {
  override def desiredName: String = "core_top"

  val aclk:    Clock = IO(Input(Clock()))
  val aresetn: Bool  = IO(Input(Bool()))
  val intrpt:  UInt  = IO(Input(UInt(8.W)))
  val break_point: Bool = IO(Input(Bool()))
  val infor_flag: Bool = IO(Input(Bool()))
  val reg_num: UInt = IO(Input(UInt(5.W)))

  val arid:    UInt = IO(Output(UInt(4.W)))
  val araddr:  UInt = IO(Output(UInt(32.W)))
  val arlen:   UInt = IO(Output(UInt(4.W)))
  val arsize:  UInt = IO(Output(UInt(3.W)))
  val arburst: UInt = IO(Output(UInt(2.W)))
  val arlock:  UInt = IO(Output(UInt(2.W)))
  val arcache: UInt = IO(Output(UInt(4.W)))
  val arprot:  UInt = IO(Output(UInt(3.W)))
  val arvalid: Bool = IO(Output(Bool()))
  val arready: Bool = IO(Input(Bool()))
  val rid:     UInt = IO(Input(UInt(4.W)))
  val rdata:   UInt = IO(Input(UInt(32.W)))
  val rresp:   UInt = IO(Input(UInt(2.W)))
  val rlast:   Bool = IO(Input(Bool()))
  val rvalid:  Bool = IO(Input(Bool()))
  val rready:  Bool = IO(Output(Bool()))
  val ws_valid: Bool = IO(Output(Bool()))
  val rf_rdata: UInt = IO(Output(UInt(32.W)))
  val debug0_wb_pc: UInt = IO(Output(UInt(32.W)))
  val debug0_wb_rf_wen: UInt = IO(Output(UInt(4.W)))
  val debug0_wb_rf_wnum: UInt = IO(Output(UInt(5.W)))
  val debug0_wb_rf_wdata: UInt = IO(Output(UInt(32.W)))
  val debug0_wb_inst: UInt = IO(Output(UInt(32.W)))

  val awid:    UInt = IO(Output(UInt(4.W)))
  val awaddr:  UInt = IO(Output(UInt(32.W)))
  val awlen:   UInt = IO(Output(UInt(4.W)))
  val awsize:  UInt = IO(Output(UInt(3.W)))
  val awburst: UInt = IO(Output(UInt(2.W)))
  val awlock:  UInt = IO(Output(UInt(2.W)))
  val awcache: UInt = IO(Output(UInt(4.W)))
  val awprot:  UInt = IO(Output(UInt(3.W)))
  val awvalid: Bool = IO(Output(Bool()))
  val awready: Bool = IO(Input(Bool()))
  val wid:     UInt = IO(Output(UInt(4.W)))
  val wdata:   UInt = IO(Output(UInt(32.W)))
  val wstrb:   UInt = IO(Output(UInt(4.W)))
  val wlast:   Bool = IO(Output(Bool()))
  val wvalid:  Bool = IO(Output(Bool()))
  val wready:  Bool = IO(Input(Bool()))
  val bid:     UInt = IO(Input(UInt(4.W)))
  val bresp:   UInt = IO(Input(UInt(2.W)))
  val bvalid:  Bool = IO(Input(Bool()))
  val bready:  Bool = IO(Output(Bool()))
}

class CoreTopIO extends Bundle {
  val aclk:    Clock  = Input(Clock())
  val aresetn: Bool   = Input(Bool())
  val intrpt:  UInt   = Input(UInt(8.W))
  val debug0_wb_pc: UInt = Output(UInt(32.W))
  val debug0_wb_rf_wen: UInt = Output(UInt(4.W))
  val debug0_wb_rf_wnum: UInt = Output(UInt(5.W))
  val debug0_wb_rf_wdata: UInt = Output(UInt(32.W))
  val debug0_wb_inst: UInt = Output(UInt(32.W))
  val axi:     AXI3IO = new AXI3IO
}

/** 将扁平的 `core_top.sv` 端口整理为 SoC 使用的 AXI3 Bundle。 */
class CoreTop extends RawModule {
  val io: CoreTopIO = IO(new CoreTopIO)

  val raw: RawCoreTop = Module(new RawCoreTop)

  raw.aclk    := io.aclk
  raw.aresetn := io.aresetn
  raw.intrpt  := io.intrpt
  raw.break_point := false.B
  raw.infor_flag := false.B
  raw.reg_num := 0.U
  io.debug0_wb_pc := raw.debug0_wb_pc
  io.debug0_wb_rf_wen := raw.debug0_wb_rf_wen
  io.debug0_wb_rf_wnum := raw.debug0_wb_rf_wnum
  io.debug0_wb_rf_wdata := raw.debug0_wb_rf_wdata
  io.debug0_wb_inst := raw.debug0_wb_inst

  io.axi.awid    := raw.awid
  io.axi.awaddr  := raw.awaddr
  io.axi.awlen   := raw.awlen
  io.axi.awsize  := raw.awsize
  io.axi.awburst := raw.awburst
  io.axi.awlock  := raw.awlock
  io.axi.awcache := raw.awcache
  io.axi.awprot  := raw.awprot
  io.axi.awvalid := raw.awvalid
  raw.awready    := io.axi.awready

  io.axi.wid    := raw.wid
  io.axi.wdata  := raw.wdata
  io.axi.wstrb  := raw.wstrb
  io.axi.wlast  := raw.wlast
  io.axi.wvalid := raw.wvalid
  raw.wready    := io.axi.wready

  raw.bid       := io.axi.bid
  raw.bresp     := io.axi.bresp
  raw.bvalid    := io.axi.bvalid
  io.axi.bready := raw.bready

  io.axi.arid    := raw.arid
  io.axi.araddr  := raw.araddr
  io.axi.arlen   := raw.arlen
  io.axi.arsize  := raw.arsize
  io.axi.arburst := raw.arburst
  io.axi.arlock  := raw.arlock
  io.axi.arcache := raw.arcache
  io.axi.arprot  := raw.arprot
  io.axi.arvalid := raw.arvalid
  raw.arready    := io.axi.arready

  raw.rid       := io.axi.rid
  raw.rdata     := io.axi.rdata
  raw.rresp     := io.axi.rresp
  raw.rlast     := io.axi.rlast
  raw.rvalid    := io.axi.rvalid
  io.axi.rready := raw.rready
}
