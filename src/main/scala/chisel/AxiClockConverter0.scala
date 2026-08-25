package chisel

import chisel3._
import chisel3.experimental.ExtModule

class RawAxiClockConverter0 extends ExtModule {
  override def desiredName: String = "axi_clock_converter_0"

  val s_axi_awid:     UInt  = IO(Input(UInt(4.W)))
  val s_axi_awaddr:   UInt  = IO(Input(UInt(32.W)))
  val s_axi_awlen:    UInt  = IO(Input(UInt(4.W)))
  val s_axi_awsize:   UInt  = IO(Input(UInt(3.W)))
  val s_axi_awburst:  UInt  = IO(Input(UInt(2.W)))
  val s_axi_awlock:   UInt  = IO(Input(UInt(2.W)))
  val s_axi_awcache:  UInt  = IO(Input(UInt(4.W)))
  val s_axi_awprot:   UInt  = IO(Input(UInt(3.W)))
  val s_axi_awqos:    UInt  = IO(Input(UInt(4.W)))
  val s_axi_awvalid:  Bool  = IO(Input(Bool()))
  val s_axi_awready:  Bool  = IO(Output(Bool()))
  val s_axi_wid:      UInt  = IO(Input(UInt(4.W)))
  val s_axi_wdata:    UInt  = IO(Input(UInt(32.W)))
  val s_axi_wstrb:    UInt  = IO(Input(UInt(4.W)))
  val s_axi_wlast:    Bool  = IO(Input(Bool()))
  val s_axi_wvalid:   Bool  = IO(Input(Bool()))
  val s_axi_wready:   Bool  = IO(Output(Bool()))
  val s_axi_bid:      UInt  = IO(Output(UInt(4.W)))
  val s_axi_bresp:    UInt  = IO(Output(UInt(2.W)))
  val s_axi_bvalid:   Bool  = IO(Output(Bool()))
  val s_axi_bready:   Bool  = IO(Input(Bool()))
  val s_axi_arid:     UInt  = IO(Input(UInt(4.W)))
  val s_axi_araddr:   UInt  = IO(Input(UInt(32.W)))
  val s_axi_arlen:    UInt  = IO(Input(UInt(4.W)))
  val s_axi_arsize:   UInt  = IO(Input(UInt(3.W)))
  val s_axi_arburst:  UInt  = IO(Input(UInt(2.W)))
  val s_axi_arlock:   UInt  = IO(Input(UInt(2.W)))
  val s_axi_arcache:  UInt  = IO(Input(UInt(4.W)))
  val s_axi_arprot:   UInt  = IO(Input(UInt(3.W)))
  val s_axi_arqos:    UInt  = IO(Input(UInt(4.W)))
  val s_axi_arvalid:  Bool  = IO(Input(Bool()))
  val s_axi_arready:  Bool  = IO(Output(Bool()))
  val s_axi_rid:      UInt  = IO(Output(UInt(4.W)))
  val s_axi_rdata:    UInt  = IO(Output(UInt(32.W)))
  val s_axi_rresp:    UInt  = IO(Output(UInt(2.W)))
  val s_axi_rlast:    Bool  = IO(Output(Bool()))
  val s_axi_rvalid:   Bool  = IO(Output(Bool()))
  val s_axi_rready:   Bool  = IO(Input(Bool()))
  val s_axi_aclk:     Clock = IO(Input(Clock()))
  val s_axi_aresetn:  Bool  = IO(Input(Bool()))

  val m_axi_awid:     UInt  = IO(Output(UInt(4.W)))
  val m_axi_awaddr:   UInt  = IO(Output(UInt(32.W)))
  val m_axi_awlen:    UInt  = IO(Output(UInt(4.W)))
  val m_axi_awsize:   UInt  = IO(Output(UInt(3.W)))
  val m_axi_awburst:  UInt  = IO(Output(UInt(2.W)))
  val m_axi_awlock:   UInt  = IO(Output(UInt(2.W)))
  val m_axi_awcache:  UInt  = IO(Output(UInt(4.W)))
  val m_axi_awprot:   UInt  = IO(Output(UInt(3.W)))
  val m_axi_awqos:    UInt  = IO(Output(UInt(4.W)))
  val m_axi_awvalid:  Bool  = IO(Output(Bool()))
  val m_axi_awready:  Bool  = IO(Input(Bool()))
  val m_axi_wid:      UInt  = IO(Output(UInt(4.W)))
  val m_axi_wdata:    UInt  = IO(Output(UInt(32.W)))
  val m_axi_wstrb:    UInt  = IO(Output(UInt(4.W)))
  val m_axi_wlast:    Bool  = IO(Output(Bool()))
  val m_axi_wvalid:   Bool  = IO(Output(Bool()))
  val m_axi_wready:   Bool  = IO(Input(Bool()))
  val m_axi_bid:      UInt  = IO(Input(UInt(4.W)))
  val m_axi_bresp:    UInt  = IO(Input(UInt(2.W)))
  val m_axi_bvalid:   Bool  = IO(Input(Bool()))
  val m_axi_bready:   Bool  = IO(Output(Bool()))
  val m_axi_arid:     UInt  = IO(Output(UInt(4.W)))
  val m_axi_araddr:   UInt  = IO(Output(UInt(32.W)))
  val m_axi_arlen:    UInt  = IO(Output(UInt(4.W)))
  val m_axi_arsize:   UInt  = IO(Output(UInt(3.W)))
  val m_axi_arburst:  UInt  = IO(Output(UInt(2.W)))
  val m_axi_arlock:   UInt  = IO(Output(UInt(2.W)))
  val m_axi_arcache:  UInt  = IO(Output(UInt(4.W)))
  val m_axi_arprot:   UInt  = IO(Output(UInt(3.W)))
  val m_axi_arqos:    UInt  = IO(Output(UInt(4.W)))
  val m_axi_arvalid:  Bool  = IO(Output(Bool()))
  val m_axi_arready:  Bool  = IO(Input(Bool()))
  val m_axi_rid:      UInt  = IO(Input(UInt(4.W)))
  val m_axi_rdata:    UInt  = IO(Input(UInt(32.W)))
  val m_axi_rresp:    UInt  = IO(Input(UInt(2.W)))
  val m_axi_rlast:    Bool  = IO(Input(Bool()))
  val m_axi_rvalid:   Bool  = IO(Input(Bool()))
  val m_axi_rready:   Bool  = IO(Output(Bool()))
  val m_axi_aclk:     Clock = IO(Input(Clock()))
  val m_axi_aresetn:  Bool  = IO(Input(Bool()))
}

class AxiClockConverter0IO extends Bundle {
  val sAxiClock:  Clock          = Input(Clock())
  val sAxiResetn: Bool           = Input(Bool())
  val sAxi:       AXI3IO = Flipped(new AXI3IO)
  val mAxiClock:  Clock          = Input(Clock())
  val mAxiResetn: Bool           = Input(Bool())
  val mAxi:       AXI3IO = new AXI3IO
}

class AxiClockConverter0 extends RawModule {
  val io: AxiClockConverter0IO = IO(new AxiClockConverter0IO)

  val raw: RawAxiClockConverter0 = Module(new RawAxiClockConverter0)

  raw.s_axi_aclk    := io.sAxiClock
  raw.s_axi_aresetn := io.sAxiResetn
  raw.m_axi_aclk    := io.mAxiClock
  raw.m_axi_aresetn := io.mAxiResetn

  raw.s_axi_awid    := io.sAxi.awid
  raw.s_axi_awaddr  := io.sAxi.awaddr
  raw.s_axi_awlen   := io.sAxi.awlen
  raw.s_axi_awsize  := io.sAxi.awsize
  raw.s_axi_awburst := io.sAxi.awburst
  raw.s_axi_awlock  := io.sAxi.awlock
  raw.s_axi_awcache := io.sAxi.awcache
  raw.s_axi_awprot  := io.sAxi.awprot
  raw.s_axi_awqos   := 0.U
  raw.s_axi_awvalid := io.sAxi.awvalid
  io.sAxi.awready   := raw.s_axi_awready

  raw.s_axi_wid     := io.sAxi.wid
  raw.s_axi_wdata   := io.sAxi.wdata
  raw.s_axi_wstrb   := io.sAxi.wstrb
  raw.s_axi_wlast   := io.sAxi.wlast
  raw.s_axi_wvalid  := io.sAxi.wvalid
  io.sAxi.wready    := raw.s_axi_wready

  io.sAxi.bid       := raw.s_axi_bid
  io.sAxi.bresp     := raw.s_axi_bresp
  io.sAxi.bvalid    := raw.s_axi_bvalid
  raw.s_axi_bready  := io.sAxi.bready

  raw.s_axi_arid    := io.sAxi.arid
  raw.s_axi_araddr  := io.sAxi.araddr
  raw.s_axi_arlen   := io.sAxi.arlen
  raw.s_axi_arsize  := io.sAxi.arsize
  raw.s_axi_arburst := io.sAxi.arburst
  raw.s_axi_arlock  := io.sAxi.arlock
  raw.s_axi_arcache := io.sAxi.arcache
  raw.s_axi_arprot  := io.sAxi.arprot
  raw.s_axi_arqos   := 0.U
  raw.s_axi_arvalid := io.sAxi.arvalid
  io.sAxi.arready   := raw.s_axi_arready

  io.sAxi.rid       := raw.s_axi_rid
  io.sAxi.rdata     := raw.s_axi_rdata
  io.sAxi.rresp     := raw.s_axi_rresp
  io.sAxi.rlast     := raw.s_axi_rlast
  io.sAxi.rvalid    := raw.s_axi_rvalid
  raw.s_axi_rready  := io.sAxi.rready

  io.mAxi.awid      := raw.m_axi_awid
  io.mAxi.awaddr    := raw.m_axi_awaddr
  io.mAxi.awlen     := raw.m_axi_awlen
  io.mAxi.awsize    := raw.m_axi_awsize
  io.mAxi.awburst   := raw.m_axi_awburst
  io.mAxi.awlock    := raw.m_axi_awlock
  io.mAxi.awcache   := raw.m_axi_awcache
  io.mAxi.awprot    := raw.m_axi_awprot
  io.mAxi.awvalid   := raw.m_axi_awvalid
  raw.m_axi_awready := io.mAxi.awready

  io.mAxi.wid       := raw.m_axi_wid
  io.mAxi.wdata     := raw.m_axi_wdata
  io.mAxi.wstrb     := raw.m_axi_wstrb
  io.mAxi.wlast     := raw.m_axi_wlast
  io.mAxi.wvalid    := raw.m_axi_wvalid
  raw.m_axi_wready  := io.mAxi.wready

  raw.m_axi_bid     := io.mAxi.bid
  raw.m_axi_bresp   := io.mAxi.bresp
  raw.m_axi_bvalid  := io.mAxi.bvalid
  io.mAxi.bready    := raw.m_axi_bready

  io.mAxi.arid      := raw.m_axi_arid
  io.mAxi.araddr    := raw.m_axi_araddr
  io.mAxi.arlen     := raw.m_axi_arlen
  io.mAxi.arsize    := raw.m_axi_arsize
  io.mAxi.arburst   := raw.m_axi_arburst
  io.mAxi.arlock    := raw.m_axi_arlock
  io.mAxi.arcache   := raw.m_axi_arcache
  io.mAxi.arprot    := raw.m_axi_arprot
  io.mAxi.arvalid   := raw.m_axi_arvalid
  raw.m_axi_arready := io.mAxi.arready

  raw.m_axi_rid     := io.mAxi.rid
  raw.m_axi_rdata   := io.mAxi.rdata
  raw.m_axi_rresp   := io.mAxi.rresp
  raw.m_axi_rlast   := io.mAxi.rlast
  raw.m_axi_rvalid  := io.mAxi.rvalid
  io.mAxi.rready    := raw.m_axi_rready
}
