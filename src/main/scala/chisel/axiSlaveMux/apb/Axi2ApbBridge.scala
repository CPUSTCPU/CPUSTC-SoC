package chisel.axiSlaveMux.apb

import chisel3._
import chisel3.experimental.ExtModule
import chisel.common.bus.AXI3IO

class RawAxi2ApbBridge extends ExtModule {
  override def desiredName: String = "axi2apb_bridge"

  val clk: Clock = IO(Input(Clock()))
  val rst_n: Bool = IO(Input(Bool()))

  val axi_s_awid: UInt = IO(Input(UInt(4.W)))
  val axi_s_awaddr: UInt = IO(Input(UInt(32.W)))
  val axi_s_awlen: UInt = IO(Input(UInt(4.W)))
  val axi_s_awsize: UInt = IO(Input(UInt(3.W)))
  val axi_s_awburst: UInt = IO(Input(UInt(2.W)))
  val axi_s_awlock: UInt = IO(Input(UInt(2.W)))
  val axi_s_awcache: UInt = IO(Input(UInt(4.W)))
  val axi_s_awprot: UInt = IO(Input(UInt(3.W)))
  val axi_s_awvalid: Bool = IO(Input(Bool()))
  val axi_s_awready: Bool = IO(Output(Bool()))
  val axi_s_wid: UInt = IO(Input(UInt(4.W)))
  val axi_s_wdata: UInt = IO(Input(UInt(32.W)))
  val axi_s_wstrb: UInt = IO(Input(UInt(4.W)))
  val axi_s_wlast: Bool = IO(Input(Bool()))
  val axi_s_wvalid: Bool = IO(Input(Bool()))
  val axi_s_wready: Bool = IO(Output(Bool()))
  val axi_s_bid: UInt = IO(Output(UInt(4.W)))
  val axi_s_bresp: UInt = IO(Output(UInt(2.W)))
  val axi_s_bvalid: Bool = IO(Output(Bool()))
  val axi_s_bready: Bool = IO(Input(Bool()))
  val axi_s_arid: UInt = IO(Input(UInt(4.W)))
  val axi_s_araddr: UInt = IO(Input(UInt(32.W)))
  val axi_s_arlen: UInt = IO(Input(UInt(4.W)))
  val axi_s_arsize: UInt = IO(Input(UInt(3.W)))
  val axi_s_arburst: UInt = IO(Input(UInt(2.W)))
  val axi_s_arlock: UInt = IO(Input(UInt(2.W)))
  val axi_s_arcache: UInt = IO(Input(UInt(4.W)))
  val axi_s_arprot: UInt = IO(Input(UInt(3.W)))
  val axi_s_arvalid: Bool = IO(Input(Bool()))
  val axi_s_arready: Bool = IO(Output(Bool()))
  val axi_s_rid: UInt = IO(Output(UInt(4.W)))
  val axi_s_rdata: UInt = IO(Output(UInt(32.W)))
  val axi_s_rresp: UInt = IO(Output(UInt(2.W)))
  val axi_s_rlast: Bool = IO(Output(Bool()))
  val axi_s_rvalid: Bool = IO(Output(Bool()))
  val axi_s_rready: Bool = IO(Input(Bool()))

  val apb_valid_cpu: Bool = IO(Output(Bool()))
  val cpu_grant: Bool = IO(Input(Bool()))
  val apb_word_trans: Bool = IO(Input(Bool()))
  val apb_high_24b_rd: UInt = IO(Input(UInt(24.W)))
  val apb_high_24b_wr: UInt = IO(Output(UInt(24.W)))
/*
* 这两个端口在原RTL中也是未使用的冗余输出。
  axi2apb_bridge内部定义：
  assign apb_clk     = clk;
  assign apb_reset_n = rst_n;
  在原 IP/APB_DEV/apb_dev_top_with_nand.v 中虽然连接到：
  wire apb_clk_cpu;
  wire apb_reset_n_cpu;
  但后续模块直接使用顶层的clk和rst_n：
  apb_mux2 (
      .clk   (clk),
      .rst_n (rst_n)
  );
  UART_TOP uart0 (
      .PCLK  (clk),
      .PRST_ (rst_n)
  );
  nand_module (
      .clk   (clk),
      .rst_n (rst_n)
  );
*/
  val apb_clk: Clock = IO(Output(Clock()))
  val apb_reset_n: Bool = IO(Output(Bool()))
  val reg_psel: Bool = IO(Output(Bool()))
  val reg_enable: Bool = IO(Output(Bool()))
  val reg_rw: Bool = IO(Output(Bool()))
  val reg_addr: UInt = IO(Output(UInt(20.W)))
  val reg_datai: UInt = IO(Output(UInt(8.W)))
  val reg_ready_1: Bool = IO(Input(Bool()))
  val reg_error_1: Bool = IO(Input(Bool()))
  val reg_datao: UInt = IO(Input(UInt(8.W)))
}

class Axi2ApbCpuPort extends Bundle {
  val valid: Bool = Output(Bool())
  val grant: Bool = Input(Bool())
  val wordTrans: Bool = Input(Bool())
  val high24Read: UInt = Input(UInt(24.W))
  val high24Write: UInt = Output(UInt(24.W))
  val psel: Bool = Output(Bool())
  val penable: Bool = Output(Bool())
  val write: Bool = Output(Bool())
  val addr: UInt = Output(UInt(20.W))
  val writeData: UInt = Output(UInt(8.W))
  val readData: UInt = Input(UInt(8.W))
  val ready: Bool = Input(Bool())
  val error: Bool = Input(Bool())
}

class Axi2ApbBridgeIO extends Bundle {
  val clk: Clock = Input(Clock())
  val resetn: Bool = Input(Bool())
  val axi: AXI3IO = Flipped(new AXI3IO)
  val apb: Axi2ApbCpuPort = new Axi2ApbCpuPort
}

class Axi2ApbBridge extends RawModule {
  val io: Axi2ApbBridgeIO = IO(new Axi2ApbBridgeIO)
  val raw: RawAxi2ApbBridge = Module(new RawAxi2ApbBridge)

  raw.clk := io.clk
  raw.rst_n := io.resetn

  raw.axi_s_awid := io.axi.awid
  raw.axi_s_awaddr := io.axi.awaddr
  raw.axi_s_awlen := io.axi.awlen
  raw.axi_s_awsize := io.axi.awsize
  raw.axi_s_awburst := io.axi.awburst
  raw.axi_s_awlock := io.axi.awlock
  raw.axi_s_awcache := io.axi.awcache
  raw.axi_s_awprot := io.axi.awprot
  raw.axi_s_awvalid := io.axi.awvalid
  io.axi.awready := raw.axi_s_awready
  raw.axi_s_wid := io.axi.wid
  raw.axi_s_wdata := io.axi.wdata
  raw.axi_s_wstrb := io.axi.wstrb
  raw.axi_s_wlast := io.axi.wlast
  raw.axi_s_wvalid := io.axi.wvalid
  io.axi.wready := raw.axi_s_wready
  io.axi.bid := raw.axi_s_bid
  io.axi.bresp := raw.axi_s_bresp
  io.axi.bvalid := raw.axi_s_bvalid
  raw.axi_s_bready := io.axi.bready
  raw.axi_s_arid := io.axi.arid
  raw.axi_s_araddr := io.axi.araddr
  raw.axi_s_arlen := io.axi.arlen
  raw.axi_s_arsize := io.axi.arsize
  raw.axi_s_arburst := io.axi.arburst
  raw.axi_s_arlock := io.axi.arlock
  raw.axi_s_arcache := io.axi.arcache
  raw.axi_s_arprot := io.axi.arprot
  raw.axi_s_arvalid := io.axi.arvalid
  io.axi.arready := raw.axi_s_arready
  io.axi.rid := raw.axi_s_rid
  io.axi.rdata := raw.axi_s_rdata
  io.axi.rresp := raw.axi_s_rresp
  io.axi.rlast := raw.axi_s_rlast
  io.axi.rvalid := raw.axi_s_rvalid
  raw.axi_s_rready := io.axi.rready

  io.apb.valid := raw.apb_valid_cpu
  raw.cpu_grant := io.apb.grant
  raw.apb_word_trans := io.apb.wordTrans
  raw.apb_high_24b_rd := io.apb.high24Read
  io.apb.high24Write := raw.apb_high_24b_wr
  io.apb.psel := raw.reg_psel
  io.apb.penable := raw.reg_enable
  io.apb.write := raw.reg_rw
  io.apb.addr := raw.reg_addr
  io.apb.writeData := raw.reg_datai
  raw.reg_datao := io.apb.readData
  raw.reg_ready_1 := io.apb.ready
  raw.reg_error_1 := io.apb.error
}
