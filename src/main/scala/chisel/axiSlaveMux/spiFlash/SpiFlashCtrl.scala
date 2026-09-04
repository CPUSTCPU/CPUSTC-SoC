package chisel.axiSlaveMux.spiFlash

import chisel3._
import chisel3.experimental.ExtModule
import chisel.SpiFlashPort
import chisel.common.bus.AXI3IO

class RawSpiFlashCtrl extends ExtModule {
  override def desiredName: String = "spi_flash_ctrl"

  val aclk:           Clock = IO(Input(Clock()))
  val aresetn:        Bool  = IO(Input(Bool()))
  val spi_addr:       UInt  = IO(Input(UInt(16.W)))
  val power_down_req: Bool  = IO(Input(Bool()))
  val power_down_ack: Bool  = IO(Output(Bool()))
  val fast_startup:   Bool  = IO(Input(Bool()))

  val s_awlen:        UInt = IO(Input(UInt(4.W)))
  val s_awcache:      UInt = IO(Input(UInt(4.W)))
  val s_awid:         UInt = IO(Input(UInt(4.W)))
  val s_awaddr:       UInt = IO(Input(UInt(32.W)))
  val s_awsize:       UInt = IO(Input(UInt(3.W)))
  val s_awprot:       UInt = IO(Input(UInt(3.W)))
  val s_awburst:      UInt = IO(Input(UInt(2.W)))
  val s_awlock:       UInt = IO(Input(UInt(2.W)))
  val s_awvalid:      Bool = IO(Input(Bool()))
  val s_awready:      Bool = IO(Output(Bool()))

  val s_wid:          UInt = IO(Input(UInt(4.W)))
  val s_wdata:        UInt = IO(Input(UInt(32.W)))
  val s_wstrb:        UInt = IO(Input(UInt(4.W)))
  val s_wlast:        Bool = IO(Input(Bool()))
  val s_wvalid:       Bool = IO(Input(Bool()))
  val s_wready:       Bool = IO(Output(Bool()))

  val s_bid:          UInt = IO(Output(UInt(4.W)))
  val s_bresp:        UInt = IO(Output(UInt(2.W)))
  val s_bvalid:       Bool = IO(Output(Bool()))
  val s_bready:       Bool = IO(Input(Bool()))

  val s_arlen:        UInt = IO(Input(UInt(4.W)))
  val s_arcache:      UInt = IO(Input(UInt(4.W)))
  val s_arid:         UInt = IO(Input(UInt(4.W)))
  val s_araddr:       UInt = IO(Input(UInt(32.W)))
  val s_arsize:       UInt = IO(Input(UInt(3.W)))
  val s_arprot:       UInt = IO(Input(UInt(3.W)))
  val s_arburst:      UInt = IO(Input(UInt(2.W)))
  val s_arlock:       UInt = IO(Input(UInt(2.W)))
  val s_arvalid:      Bool = IO(Input(Bool()))
  val s_arready:      Bool = IO(Output(Bool()))

  val s_rid:          UInt = IO(Output(UInt(4.W)))
  val s_rdata:        UInt = IO(Output(UInt(32.W)))
  val s_rresp:        UInt = IO(Output(UInt(2.W)))
  val s_rlast:        Bool = IO(Output(Bool()))
  val s_rvalid:       Bool = IO(Output(Bool()))
  val s_rready:       Bool = IO(Input(Bool()))

  val csn_o:          UInt = IO(Output(UInt(4.W)))
  val csn_en:         UInt = IO(Output(UInt(4.W)))
  val sck_o:          Bool = IO(Output(Bool()))
  val sdo_i:          Bool = IO(Input(Bool()))
  val sdo_o:          Bool = IO(Output(Bool()))
  val sdo_en:         Bool = IO(Output(Bool()))
  val sdi_i:          Bool = IO(Input(Bool()))
  val sdi_o:          Bool = IO(Output(Bool()))
  val sdi_en:         Bool = IO(Output(Bool()))
  val inta_o:         Bool = IO(Output(Bool()))
}


class SpiFlashCtrlIO extends Bundle {
  val aclk:         Clock            = Input(Clock())
  val aresetn:      Bool             = Input(Bool())
  val spiAddr:      UInt             = Input(UInt(16.W))
  val powerDownReq: Bool             = Input(Bool())
  val powerDownAck: Bool             = Output(Bool())
  val fastStartup:  Bool             = Input(Bool())
  val axi:          AXI3IO = Flipped(new AXI3IO)
  val spi:          SpiFlashPort = new SpiFlashPort
}

class SpiFlashCtrl extends RawModule {
  val io: SpiFlashCtrlIO = IO(new SpiFlashCtrlIO)

  val raw: RawSpiFlashCtrl = Module(new RawSpiFlashCtrl)

  raw.aclk           := io.aclk
  raw.aresetn        := io.aresetn
  raw.spi_addr       := io.spiAddr
  raw.power_down_req := io.powerDownReq
  io.powerDownAck    := raw.power_down_ack
  raw.fast_startup   := io.fastStartup

  raw.s_awlen   := io.axi.awlen
  raw.s_awcache := io.axi.awcache
  raw.s_awid    := io.axi.awid
  raw.s_awaddr  := io.axi.awaddr
  raw.s_awsize  := io.axi.awsize
  raw.s_awprot  := io.axi.awprot
  raw.s_awburst := io.axi.awburst
  raw.s_awlock  := io.axi.awlock
  raw.s_awvalid := io.axi.awvalid
  io.axi.awready := raw.s_awready

  raw.s_wid    := io.axi.wid
  raw.s_wdata  := io.axi.wdata
  raw.s_wstrb  := io.axi.wstrb
  raw.s_wlast  := io.axi.wlast
  raw.s_wvalid := io.axi.wvalid
  io.axi.wready := raw.s_wready

  io.axi.bid    := raw.s_bid
  io.axi.bresp  := raw.s_bresp
  io.axi.bvalid := raw.s_bvalid
  raw.s_bready  := io.axi.bready

  raw.s_arlen   := io.axi.arlen
  raw.s_arcache := io.axi.arcache
  raw.s_arid    := io.axi.arid
  raw.s_araddr  := io.axi.araddr
  raw.s_arsize  := io.axi.arsize
  raw.s_arprot  := io.axi.arprot
  raw.s_arburst := io.axi.arburst
  raw.s_arlock  := io.axi.arlock
  raw.s_arvalid := io.axi.arvalid
  io.axi.arready := raw.s_arready

  io.axi.rid    := raw.s_rid
  io.axi.rdata  := raw.s_rdata
  io.axi.rresp  := raw.s_rresp
  io.axi.rlast  := raw.s_rlast
  io.axi.rvalid := raw.s_rvalid
  raw.s_rready  := io.axi.rready

  io.spi.csn_o  := raw.csn_o
  io.spi.csn_en := raw.csn_en
  io.spi.sck_o  := raw.sck_o
  raw.sdo_i     := io.spi.sdo_i
  io.spi.sdo_o  := raw.sdo_o
  io.spi.sdo_en := raw.sdo_en
  raw.sdi_i     := io.spi.sdi_i
  io.spi.sdi_o  := raw.sdi_o
  io.spi.sdi_en := raw.sdi_en
  io.spi.inta_o := raw.inta_o
}
