package chisel.axiInterconnect.ethernet

import chisel3._
import chisel3.experimental.ExtModule
import chisel.MacPort
import chisel.common.bus.AXI3IO

class RawEthernetTop extends ExtModule {
  override def desiredName: String = "ethernet_top"

  val hclk:        Clock = IO(Input(Clock()))
  val hrst_ :      Bool  = IO(Input(Bool()))

  val mawid_o:     UInt = IO(Output(UInt(4.W)))
  val mawaddr_o:   UInt = IO(Output(UInt(32.W)))
  val mawlen_o:    UInt = IO(Output(UInt(4.W)))
  val mawsize_o:   UInt = IO(Output(UInt(3.W)))
  val mawburst_o:  UInt = IO(Output(UInt(2.W)))
  val mawlock_o:   UInt = IO(Output(UInt(2.W)))
  val mawcache_o:  UInt = IO(Output(UInt(4.W)))
  val mawprot_o:   UInt = IO(Output(UInt(3.W)))
  val mawvalid_o:  Bool = IO(Output(Bool()))
  val mawready_i:  Bool = IO(Input(Bool()))

  val mwid_o:      UInt = IO(Output(UInt(4.W)))
  val mwdata_o:    UInt = IO(Output(UInt(32.W)))
  val mwstrb_o:    UInt = IO(Output(UInt(4.W)))
  val mwlast_o:    Bool = IO(Output(Bool()))
  val mwvalid_o:   Bool = IO(Output(Bool()))
  val mwready_i:   Bool = IO(Input(Bool()))

  val mbid_i:      UInt = IO(Input(UInt(4.W)))
  val mbresp_i:    UInt = IO(Input(UInt(2.W)))
  val mbvalid_i:   Bool = IO(Input(Bool()))
  val mbready_o:   Bool = IO(Output(Bool()))

  val marid_o:     UInt = IO(Output(UInt(4.W)))
  val maraddr_o:   UInt = IO(Output(UInt(32.W)))
  val marlen_o:    UInt = IO(Output(UInt(4.W)))
  val marsize_o:   UInt = IO(Output(UInt(3.W)))
  val marburst_o:  UInt = IO(Output(UInt(2.W)))
  val marlock_o:   UInt = IO(Output(UInt(2.W)))
  val marcache_o:  UInt = IO(Output(UInt(4.W)))
  val marprot_o:   UInt = IO(Output(UInt(3.W)))
  val marvalid_o:  Bool = IO(Output(Bool()))
  val marready_i:  Bool = IO(Input(Bool()))

  val mrid_i:      UInt = IO(Input(UInt(4.W)))
  val mrdata_i:    UInt = IO(Input(UInt(32.W)))
  val mrresp_i:    UInt = IO(Input(UInt(2.W)))
  val mrlast_i:    Bool = IO(Input(Bool()))
  val mrvalid_i:   Bool = IO(Input(Bool()))
  val mrready_o:   Bool = IO(Output(Bool()))

  val sawid_i:     UInt = IO(Input(UInt(4.W)))
  val sawaddr_i:   UInt = IO(Input(UInt(32.W)))
  val sawlen_i:    UInt = IO(Input(UInt(4.W)))
  val sawsize_i:   UInt = IO(Input(UInt(3.W)))
  val sawburst_i:  UInt = IO(Input(UInt(2.W)))
  val sawlock_i:   UInt = IO(Input(UInt(2.W)))
  val sawcache_i:  UInt = IO(Input(UInt(4.W)))
  val sawprot_i:   UInt = IO(Input(UInt(3.W)))
  val sawvalid_i:  Bool = IO(Input(Bool()))
  val sawready_o:  Bool = IO(Output(Bool()))

  val swid_i:      UInt = IO(Input(UInt(4.W)))
  val swdata_i:    UInt = IO(Input(UInt(32.W)))
  val swstrb_i:    UInt = IO(Input(UInt(4.W)))
  val swlast_i:    Bool = IO(Input(Bool()))
  val swvalid_i:   Bool = IO(Input(Bool()))
  val swready_o:   Bool = IO(Output(Bool()))

  val sbid_o:      UInt = IO(Output(UInt(4.W)))
  val sbresp_o:    UInt = IO(Output(UInt(2.W)))
  val sbvalid_o:   Bool = IO(Output(Bool()))
  val sbready_i:   Bool = IO(Input(Bool()))

  val sarid_i:     UInt = IO(Input(UInt(4.W)))
  val saraddr_i:   UInt = IO(Input(UInt(32.W)))
  val sarlen_i:    UInt = IO(Input(UInt(4.W)))
  val sarsize_i:   UInt = IO(Input(UInt(3.W)))
  val sarburst_i:  UInt = IO(Input(UInt(2.W)))
  val sarlock_i:   UInt = IO(Input(UInt(2.W)))
  val sarcache_i:  UInt = IO(Input(UInt(4.W)))
  val sarprot_i:   UInt = IO(Input(UInt(3.W)))
  val sarvalid_i:  Bool = IO(Input(Bool()))
  val sarready_o:  Bool = IO(Output(Bool()))

  val srid_o:      UInt = IO(Output(UInt(4.W)))
  val srdata_o:    UInt = IO(Output(UInt(32.W)))
  val srresp_o:    UInt = IO(Output(UInt(2.W)))
  val srlast_o:    Bool = IO(Output(Bool()))
  val srvalid_o:   Bool = IO(Output(Bool()))
  val srready_i:   Bool = IO(Input(Bool()))

  val interrupt_0: Bool = IO(Output(Bool()))

  val mtxclk_0:    Clock = IO(Input(Clock()))
  val mtxen_0:     Bool  = IO(Output(Bool()))
  val mtxd_0:      UInt  = IO(Output(UInt(4.W)))
  val mtxerr_0:    Bool  = IO(Output(Bool()))
  val mrxclk_0:    Clock = IO(Input(Clock()))
  val mrxdv_0:     Bool  = IO(Input(Bool()))
  val mrxd_0:      UInt  = IO(Input(UInt(4.W)))
  val mrxerr_0:    Bool  = IO(Input(Bool()))
  val mcoll_0:     Bool  = IO(Input(Bool()))
  val mcrs_0:      Bool  = IO(Input(Bool()))
  val mdc_0:       Bool  = IO(Output(Bool()))
  val md_i_0:      Bool  = IO(Input(Bool()))
  val md_o_0:      Bool  = IO(Output(Bool()))
  val md_oe_0:     Bool  = IO(Output(Bool()))
}

class EthernetTopIO extends Bundle {
  val hclk:      Clock          = Input(Clock())
  val hrst:      Bool           = Input(Bool())
  val axiMaster: AXI3IO = new AXI3IO
  val axiSlave:  AXI3IO = Flipped(new AXI3IO)
  val interrupt: Bool           = Output(Bool())
  val mac:       MacPort        = new MacPort
}

class EthernetTop extends RawModule {
  val io: EthernetTopIO = IO(new EthernetTopIO)

  val raw: RawEthernetTop = Module(new RawEthernetTop)

  private def connectAxiMaster(axi: AXI3IO, raw: RawEthernetTop): Unit = {
    axi.awid       := raw.mawid_o
    axi.awaddr     := raw.mawaddr_o
    axi.awlen      := raw.mawlen_o
    axi.awsize     := raw.mawsize_o
    axi.awburst    := raw.mawburst_o
    axi.awlock     := raw.mawlock_o
    axi.awcache    := raw.mawcache_o
    axi.awprot     := raw.mawprot_o
    axi.awvalid    := raw.mawvalid_o
    raw.mawready_i := axi.awready

    axi.wid        := raw.mwid_o
    axi.wdata      := raw.mwdata_o
    axi.wstrb      := raw.mwstrb_o
    axi.wlast      := raw.mwlast_o
    axi.wvalid     := raw.mwvalid_o
    raw.mwready_i  := axi.wready

    raw.mbid_i     := axi.bid
    raw.mbresp_i   := axi.bresp
    raw.mbvalid_i  := axi.bvalid
    axi.bready     := raw.mbready_o

    axi.arid       := raw.marid_o
    axi.araddr     := raw.maraddr_o
    axi.arlen      := raw.marlen_o
    axi.arsize     := raw.marsize_o
    axi.arburst    := raw.marburst_o
    axi.arlock     := raw.marlock_o
    axi.arcache    := raw.marcache_o
    axi.arprot     := raw.marprot_o
    axi.arvalid    := raw.marvalid_o
    raw.marready_i := axi.arready

    raw.mrid_i     := axi.rid
    raw.mrdata_i   := axi.rdata
    raw.mrresp_i   := axi.rresp
    raw.mrlast_i   := axi.rlast
    raw.mrvalid_i  := axi.rvalid
    axi.rready     := raw.mrready_o
  }

  private def connectAxiSlave(axi: AXI3IO, raw: RawEthernetTop): Unit = {
    raw.sawid_i    := axi.awid
    raw.sawaddr_i  := axi.awaddr
    raw.sawlen_i   := axi.awlen
    raw.sawsize_i  := axi.awsize
    raw.sawburst_i := axi.awburst
    raw.sawlock_i  := axi.awlock
    raw.sawcache_i := axi.awcache
    raw.sawprot_i  := axi.awprot
    raw.sawvalid_i := axi.awvalid
    axi.awready    := raw.sawready_o

    raw.swid_i     := axi.wid
    raw.swdata_i   := axi.wdata
    raw.swstrb_i   := axi.wstrb
    raw.swlast_i   := axi.wlast
    raw.swvalid_i  := axi.wvalid
    axi.wready     := raw.swready_o

    axi.bid        := raw.sbid_o
    axi.bresp      := raw.sbresp_o
    axi.bvalid     := raw.sbvalid_o
    raw.sbready_i  := axi.bready

    raw.sarid_i    := axi.arid
    raw.saraddr_i  := axi.araddr
    raw.sarlen_i   := axi.arlen
    raw.sarsize_i  := axi.arsize
    raw.sarburst_i := axi.arburst
    raw.sarlock_i  := axi.arlock
    raw.sarcache_i := axi.arcache
    raw.sarprot_i  := axi.arprot
    raw.sarvalid_i := axi.arvalid
    axi.arready    := raw.sarready_o

    axi.rid        := raw.srid_o
    axi.rdata      := raw.srdata_o
    axi.rresp      := raw.srresp_o
    axi.rlast      := raw.srlast_o
    axi.rvalid     := raw.srvalid_o
    raw.srready_i  := axi.rready
  }

  private def connectMac(mac: MacPort, raw: RawEthernetTop): Unit = {
    raw.mtxclk_0  := mac.mtxclk_0
    mac.mtxen_0   := raw.mtxen_0
    mac.mtxd_0    := raw.mtxd_0
    mac.mtxerr_0  := raw.mtxerr_0

    raw.mrxclk_0  := mac.mrxclk_0
    raw.mrxdv_0   := mac.mrxdv_0
    raw.mrxd_0    := mac.mrxd_0
    raw.mrxerr_0  := mac.mrxerr_0
    raw.mcoll_0   := mac.mcoll_0
    raw.mcrs_0    := mac.mcrs_0

    mac.mdc_0     := raw.mdc_0
    raw.md_i_0    := mac.md_i_0
    mac.md_o_0    := raw.md_o_0
    mac.md_oe_0   := raw.md_oe_0
    mac.phy_rstn  := io.hrst
  }

  raw.hclk      := io.hclk
  raw.hrst_     := io.hrst
  io.interrupt  := raw.interrupt_0

  connectAxiMaster(io.axiMaster, raw)
  connectAxiSlave(io.axiSlave, raw)
  connectMac(io.mac, raw)
}
