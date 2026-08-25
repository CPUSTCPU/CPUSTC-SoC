package chisel

import chisel3._
import chisel3.experimental.{Analog, ExtModule, attach}

class RawMigAxi32 extends ExtModule {
  override def desiredName: String = "mig_axi_32"

  val ddr3_dq:             Analog = IO(Analog(16.W))
  val ddr3_dqs_n:          Analog = IO(Analog(2.W))
  val ddr3_dqs_p:          Analog = IO(Analog(2.W))
  val ddr3_addr:           UInt   = IO(Output(UInt(13.W)))
  val ddr3_ba:             UInt   = IO(Output(UInt(3.W)))
  val ddr3_ras_n:          Bool   = IO(Output(Bool()))
  val ddr3_cas_n:          Bool   = IO(Output(Bool()))
  val ddr3_we_n:           Bool   = IO(Output(Bool()))
  val ddr3_reset_n:        Bool   = IO(Output(Bool()))
  val ddr3_ck_p:           UInt   = IO(Output(UInt(1.W)))
  val ddr3_ck_n:           UInt   = IO(Output(UInt(1.W)))
  val ddr3_cke:            UInt   = IO(Output(UInt(1.W)))
  val ddr3_dm:             UInt   = IO(Output(UInt(2.W)))
  val ddr3_odt:            UInt   = IO(Output(UInt(1.W)))

  val sys_clk_i:           Clock = IO(Input(Clock()))
  val clk_ref_i:           Clock = IO(Input(Clock()))
  val ui_clk:              Clock = IO(Output(Clock()))
  val ui_clk_sync_rst:     Bool  = IO(Output(Bool()))
  val mmcm_locked:         Bool  = IO(Output(Bool()))
  val aresetn:             Bool  = IO(Input(Bool()))

  val app_sr_req:          Bool = IO(Input(Bool()))
  val app_ref_req:         Bool = IO(Input(Bool()))
  val app_zq_req:          Bool = IO(Input(Bool()))
  val app_sr_active:       Bool = IO(Output(Bool()))
  val app_ref_ack:         Bool = IO(Output(Bool()))
  val app_zq_ack:          Bool = IO(Output(Bool()))

  val s_axi_awid:          UInt = IO(Input(UInt(8.W)))
  val s_axi_awaddr:        UInt = IO(Input(UInt(27.W)))
  val s_axi_awlen:         UInt = IO(Input(UInt(8.W)))
  val s_axi_awsize:        UInt = IO(Input(UInt(3.W)))
  val s_axi_awburst:       UInt = IO(Input(UInt(2.W)))
  val s_axi_awlock:        UInt = IO(Input(UInt(1.W)))
  val s_axi_awcache:       UInt = IO(Input(UInt(4.W)))
  val s_axi_awprot:        UInt = IO(Input(UInt(3.W)))
  val s_axi_awqos:         UInt = IO(Input(UInt(4.W)))
  val s_axi_awvalid:       Bool = IO(Input(Bool()))
  val s_axi_awready:       Bool = IO(Output(Bool()))

  val s_axi_wdata:         UInt = IO(Input(UInt(32.W)))
  val s_axi_wstrb:         UInt = IO(Input(UInt(4.W)))
  val s_axi_wlast:         Bool = IO(Input(Bool()))
  val s_axi_wvalid:        Bool = IO(Input(Bool()))
  val s_axi_wready:        Bool = IO(Output(Bool()))

  val s_axi_bready:        Bool = IO(Input(Bool()))
  val s_axi_bid:           UInt = IO(Output(UInt(8.W)))
  val s_axi_bresp:         UInt = IO(Output(UInt(2.W)))
  val s_axi_bvalid:        Bool = IO(Output(Bool()))

  val s_axi_arid:          UInt = IO(Input(UInt(8.W)))
  val s_axi_araddr:        UInt = IO(Input(UInt(27.W)))
  val s_axi_arlen:         UInt = IO(Input(UInt(8.W)))
  val s_axi_arsize:        UInt = IO(Input(UInt(3.W)))
  val s_axi_arburst:       UInt = IO(Input(UInt(2.W)))
  val s_axi_arlock:        UInt = IO(Input(UInt(1.W)))
  val s_axi_arcache:       UInt = IO(Input(UInt(4.W)))
  val s_axi_arprot:        UInt = IO(Input(UInt(3.W)))
  val s_axi_arqos:         UInt = IO(Input(UInt(4.W)))
  val s_axi_arvalid:       Bool = IO(Input(Bool()))
  val s_axi_arready:       Bool = IO(Output(Bool()))

  val s_axi_rready:        Bool = IO(Input(Bool()))
  val s_axi_rid:           UInt = IO(Output(UInt(8.W)))
  val s_axi_rdata:         UInt = IO(Output(UInt(32.W)))
  val s_axi_rresp:         UInt = IO(Output(UInt(2.W)))
  val s_axi_rlast:         Bool = IO(Output(Bool()))
  val s_axi_rvalid:        Bool = IO(Output(Bool()))

  val init_calib_complete: Bool = IO(Output(Bool()))
  val device_temp:         UInt = IO(Output(UInt(12.W)))
  val sys_rst:             Bool = IO(Input(Bool()))
}

class MigAxi32Status extends Bundle {
  val uiClk:             Clock = Output(Clock())
  val uiClkSyncRst:      Bool  = Output(Bool())
  val mmcmLocked:        Bool  = Output(Bool())
  val initCalibComplete: Bool  = Output(Bool())
  val appSrActive:       Bool  = Output(Bool())
  val appRefAck:         Bool  = Output(Bool())
  val appZqAck:          Bool  = Output(Bool())
  val deviceTemp:        UInt  = Output(UInt(12.W))
}

class MigAxi32IO extends Bundle {
  val ddr3:    DDR3Port      = new DDR3Port
  val sysClk:  Clock         = Input(Clock())
  val clkRef:  Clock         = Input(Clock())
  val sysRst:  Bool          = Input(Bool())
  val aresetn: Bool          = Input(Bool())
  val axi:     AXI4IO = Flipped(new AXI4IO(idWidth = 8, addrWidth = 27, lenWidth = 8, lockWidth = 1))
  val status:  MigAxi32Status = new MigAxi32Status
  val ddr3_dq:      Analog = Analog(16.W)
  val ddr3_dqs_p:   Analog = Analog(2.W)
  val ddr3_dqs_n:   Analog = Analog(2.W)
}

class MigAxi32 extends RawModule {
  val io: MigAxi32IO = IO(new MigAxi32IO)

  val raw: RawMigAxi32 = Module(new RawMigAxi32)

  attach(io.ddr3_dq, raw.ddr3_dq)
  attach(io.ddr3_dqs_n, raw.ddr3_dqs_n)
  attach(io.ddr3_dqs_p, raw.ddr3_dqs_p)

  io.ddr3.ddr3_addr    := raw.ddr3_addr
  io.ddr3.ddr3_ba      := raw.ddr3_ba
  io.ddr3.ddr3_ras_n   := raw.ddr3_ras_n
  io.ddr3.ddr3_cas_n   := raw.ddr3_cas_n
  io.ddr3.ddr3_we_n    := raw.ddr3_we_n
  io.ddr3.ddr3_reset_n := raw.ddr3_reset_n
  io.ddr3.ddr3_ck_p    := raw.ddr3_ck_p(0)
  io.ddr3.ddr3_ck_n    := raw.ddr3_ck_n(0)
  io.ddr3.ddr3_cke     := raw.ddr3_cke(0)
  io.ddr3.ddr3_dm      := raw.ddr3_dm
  io.ddr3.ddr3_odt     := raw.ddr3_odt(0)

  raw.sys_clk_i := io.sysClk
  raw.clk_ref_i := io.clkRef
  raw.sys_rst   := io.sysRst
  raw.aresetn   := io.aresetn

  raw.app_sr_req  := false.B
  raw.app_ref_req := false.B
  raw.app_zq_req  := false.B

  io.status.uiClk             := raw.ui_clk
  io.status.uiClkSyncRst      := raw.ui_clk_sync_rst
  io.status.mmcmLocked        := raw.mmcm_locked
  io.status.initCalibComplete := raw.init_calib_complete
  io.status.appSrActive       := raw.app_sr_active
  io.status.appRefAck         := raw.app_ref_ack
  io.status.appZqAck          := raw.app_zq_ack
  io.status.deviceTemp        := raw.device_temp

  raw.s_axi_awid    := io.axi.awid
  raw.s_axi_awaddr  := io.axi.awaddr
  raw.s_axi_awlen   := io.axi.awlen
  raw.s_axi_awsize  := io.axi.awsize
  raw.s_axi_awburst := io.axi.awburst
  raw.s_axi_awlock  := io.axi.awlock
  raw.s_axi_awcache := io.axi.awcache
  raw.s_axi_awprot  := io.axi.awprot
  // MIG uses the default QoS value and has no REGION pins.
  raw.s_axi_awqos   := 0.U
  raw.s_axi_awvalid := io.axi.awvalid
  io.axi.awready    := raw.s_axi_awready

  raw.s_axi_wdata   := io.axi.wdata
  raw.s_axi_wstrb   := io.axi.wstrb
  raw.s_axi_wlast   := io.axi.wlast
  raw.s_axi_wvalid  := io.axi.wvalid
  io.axi.wready     := raw.s_axi_wready

  raw.s_axi_bready  := io.axi.bready
  io.axi.bid        := raw.s_axi_bid
  io.axi.bresp      := raw.s_axi_bresp
  io.axi.bvalid     := raw.s_axi_bvalid

  raw.s_axi_arid    := io.axi.arid
  raw.s_axi_araddr  := io.axi.araddr
  raw.s_axi_arlen   := io.axi.arlen
  raw.s_axi_arsize  := io.axi.arsize
  raw.s_axi_arburst := io.axi.arburst
  raw.s_axi_arlock  := io.axi.arlock
  raw.s_axi_arcache := io.axi.arcache
  raw.s_axi_arprot  := io.axi.arprot
  raw.s_axi_arqos   := 0.U
  raw.s_axi_arvalid := io.axi.arvalid
  io.axi.arready    := raw.s_axi_arready

  raw.s_axi_rready  := io.axi.rready
  io.axi.rid        := raw.s_axi_rid
  io.axi.rdata      := raw.s_axi_rdata
  io.axi.rresp      := raw.s_axi_rresp
  io.axi.rlast      := raw.s_axi_rlast
  io.axi.rvalid     := raw.s_axi_rvalid
}
