package chisel.common.bus

import chisel3._
import chisel3.util.Cat

object SocConfig {
  val axiIdWidth:    Int = 4
  val axiAddrWidth:  Int = 32
  val axiLenWidth:   Int = 4
  val axiSizeWidth:  Int = 3
  val axiBurstWidth: Int = 2
  val axiLockWidth:  Int = 2
  val axiCacheWidth: Int = 4
  val axiProtWidth:  Int = 3
  val axiDataWidth:  Int = 32
  val axiStrbWidth:  Int = axiDataWidth / 8
  val axiRespWidth:  Int = 2

  val apbAddrWidth: Int = 32
  val apbDataWidth: Int = 32
}

class AXI3IO(
  idWidth:    Int = SocConfig.axiIdWidth,
  addrWidth:  Int = SocConfig.axiAddrWidth,
  lenWidth:   Int = SocConfig.axiLenWidth,
  sizeWidth:  Int = SocConfig.axiSizeWidth,
  burstWidth: Int = SocConfig.axiBurstWidth,
  lockWidth:  Int = SocConfig.axiLockWidth,
  cacheWidth: Int = SocConfig.axiCacheWidth,
  protWidth:  Int = SocConfig.axiProtWidth,
  dataWidth:  Int = SocConfig.axiDataWidth,
  strbWidth:  Int = SocConfig.axiStrbWidth,
  respWidth:  Int = SocConfig.axiRespWidth
) extends Bundle {
  val awid:     UInt = Output(UInt(idWidth.W))
  val awaddr:   UInt = Output(UInt(addrWidth.W))
  val awlen:    UInt = Output(UInt(lenWidth.W))
  val awsize:   UInt = Output(UInt(sizeWidth.W))
  val awburst:  UInt = Output(UInt(burstWidth.W))
  val awlock:   UInt = Output(UInt(lockWidth.W))
  val awcache:  UInt = Output(UInt(cacheWidth.W))
  val awprot:   UInt = Output(UInt(protWidth.W))
  val awvalid:  Bool = Output(Bool())
  val awready:  Bool = Input(Bool())

  val wid:      UInt = Output(UInt(idWidth.W))
  val wdata:    UInt = Output(UInt(dataWidth.W))
  val wstrb:    UInt = Output(UInt(strbWidth.W))
  val wlast:    Bool = Output(Bool())
  val wvalid:   Bool = Output(Bool())
  val wready:   Bool = Input(Bool())

  val bid:      UInt = Input(UInt(idWidth.W))
  val bresp:    UInt = Input(UInt(respWidth.W))
  val bvalid:   Bool = Input(Bool())
  val bready:   Bool = Output(Bool())

  val arid:     UInt = Output(UInt(idWidth.W))
  val araddr:   UInt = Output(UInt(addrWidth.W))
  val arlen:    UInt = Output(UInt(lenWidth.W))
  val arsize:   UInt = Output(UInt(sizeWidth.W))
  val arburst:  UInt = Output(UInt(burstWidth.W))
  val arlock:   UInt = Output(UInt(lockWidth.W))
  val arcache:  UInt = Output(UInt(cacheWidth.W))
  val arprot:   UInt = Output(UInt(protWidth.W))
  val arvalid:  Bool = Output(Bool())
  val arready:  Bool = Input(Bool())

  val rid:      UInt = Input(UInt(idWidth.W))
  val rdata:    UInt = Input(UInt(dataWidth.W))
  val rresp:    UInt = Input(UInt(respWidth.W))
  val rlast:    Bool = Input(Bool())
  val rvalid:   Bool = Input(Bool())
  val rready:   Bool = Output(Bool())
}

object AXI3IO {
  def tieOffInputs(axi: AXI3IO): Unit = {
    axi.awready := false.B
    axi.wready  := false.B

    axi.bid     := 0.U
    axi.bresp   := 0.U
    axi.bvalid  := false.B

    axi.arready := false.B

    axi.rid     := 0.U
    axi.rdata   := 0.U
    axi.rresp   := 0.U
    axi.rlast   := false.B
    axi.rvalid  := false.B
  }

  def tieOffOutputs(axi: AXI3IO): Unit = {
    axi.awid    := 0.U
    axi.awaddr  := 0.U
    axi.awlen   := 0.U
    axi.awsize  := 0.U
    axi.awburst := 0.U
    axi.awlock  := 0.U
    axi.awcache := 0.U
    axi.awprot  := 0.U
    axi.awvalid := false.B

    axi.wid     := 0.U
    axi.wdata   := 0.U
    axi.wstrb   := 0.U
    axi.wlast   := false.B
    axi.wvalid  := false.B

    axi.bready  := false.B

    axi.arid    := 0.U
    axi.araddr  := 0.U
    axi.arlen   := 0.U
    axi.arsize  := 0.U
    axi.arburst := 0.U
    axi.arlock  := 0.U
    axi.arcache := 0.U
    axi.arprot  := 0.U
    axi.arvalid := false.B

    axi.rready  := false.B
  }

}


/** AXI3 Bundle 之间的简单连线适配工具。
  *
  * 该适配器只负责连接同协议形态的 AXI3 通道，并在位宽不一致时截断高位或高位补零。
  * 它不做协议转换、跨时钟域处理、缓冲、仲裁或数据位宽转换。
  */
object AXI3PortAdapter {
  private def adapt(source: UInt, width: Int): UInt = {
    if (source.getWidth > width) {
      source(width - 1, 0)
    } else if (source.getWidth < width) {
      Cat(0.U((width - source.getWidth).W), source)
    } else {
      source
    }
  }

  /** 连接一个 AXI3 master-facing Bundle 和一个 AXI3 slave-facing Bundle。
    *
    * 仅在两侧 AXI3 协议形态相同、但 ID 或 sideband 位宽可能不同时使用。
    * 地址、数据、字节使能和响应位宽仍必须符合被连接 IP 期望的硬件契约。
    */
  def connectMasterToSlave(master: AXI3IO, slave: AXI3IO): Unit = {
    slave.awid    := adapt(master.awid, slave.awid.getWidth)
    slave.awaddr  := adapt(master.awaddr, slave.awaddr.getWidth)
    slave.awlen   := adapt(master.awlen, slave.awlen.getWidth)
    slave.awsize  := adapt(master.awsize, slave.awsize.getWidth)
    slave.awburst := adapt(master.awburst, slave.awburst.getWidth)
    slave.awlock  := adapt(master.awlock, slave.awlock.getWidth)
    slave.awcache := adapt(master.awcache, slave.awcache.getWidth)
    slave.awprot  := adapt(master.awprot, slave.awprot.getWidth)
    slave.awvalid := master.awvalid
    master.awready := slave.awready

    slave.wid     := adapt(master.wid, slave.wid.getWidth)
    slave.wdata   := adapt(master.wdata, slave.wdata.getWidth)
    slave.wstrb   := adapt(master.wstrb, slave.wstrb.getWidth)
    slave.wlast   := master.wlast
    slave.wvalid  := master.wvalid
    master.wready := slave.wready

    master.bid    := adapt(slave.bid, master.bid.getWidth)
    master.bresp  := adapt(slave.bresp, master.bresp.getWidth)
    master.bvalid := slave.bvalid
    slave.bready  := master.bready

    slave.arid    := adapt(master.arid, slave.arid.getWidth)
    slave.araddr  := adapt(master.araddr, slave.araddr.getWidth)
    slave.arlen   := adapt(master.arlen, slave.arlen.getWidth)
    slave.arsize  := adapt(master.arsize, slave.arsize.getWidth)
    slave.arburst := adapt(master.arburst, slave.arburst.getWidth)
    slave.arlock  := adapt(master.arlock, slave.arlock.getWidth)
    slave.arcache := adapt(master.arcache, slave.arcache.getWidth)
    slave.arprot  := adapt(master.arprot, slave.arprot.getWidth)
    slave.arvalid := master.arvalid
    master.arready := slave.arready

    master.rid    := adapt(slave.rid, master.rid.getWidth)
    master.rdata  := adapt(slave.rdata, master.rdata.getWidth)
    master.rresp  := adapt(slave.rresp, master.rresp.getWidth)
    master.rlast  := slave.rlast
    master.rvalid := slave.rvalid
    slave.rready  := master.rready
  }
}

class AXI4IO(
  idWidth:    Int = SocConfig.axiIdWidth,
  addrWidth:  Int = SocConfig.axiAddrWidth,
  lenWidth:   Int = SocConfig.axiLenWidth,
  sizeWidth:  Int = SocConfig.axiSizeWidth,
  burstWidth: Int = SocConfig.axiBurstWidth,
  lockWidth:  Int = SocConfig.axiLockWidth,
  cacheWidth: Int = SocConfig.axiCacheWidth,
  protWidth:  Int = SocConfig.axiProtWidth,
  dataWidth:  Int = SocConfig.axiDataWidth,
  strbWidth:  Int = SocConfig.axiStrbWidth,
  respWidth:  Int = SocConfig.axiRespWidth
) extends Bundle {
  val awid:     UInt = Output(UInt(idWidth.W))
  val awaddr:   UInt = Output(UInt(addrWidth.W))
  val awlen:    UInt = Output(UInt(lenWidth.W))
  val awsize:   UInt = Output(UInt(sizeWidth.W))
  val awburst:  UInt = Output(UInt(burstWidth.W))
  val awlock:   UInt = Output(UInt(lockWidth.W))
  val awcache:  UInt = Output(UInt(cacheWidth.W))
  val awprot:   UInt = Output(UInt(protWidth.W))
  val awqos:    UInt = Output(UInt(4.W))
  val awregion: UInt = Output(UInt(4.W))
  val awvalid:  Bool = Output(Bool())
  val awready:  Bool = Input(Bool())

  val wdata:    UInt = Output(UInt(dataWidth.W))
  val wstrb:    UInt = Output(UInt(strbWidth.W))
  val wlast:    Bool = Output(Bool())
  val wvalid:   Bool = Output(Bool())
  val wready:   Bool = Input(Bool())

  val bid:      UInt = Input(UInt(idWidth.W))
  val bresp:    UInt = Input(UInt(respWidth.W))
  val bvalid:   Bool = Input(Bool())
  val bready:   Bool = Output(Bool())

  val arid:     UInt = Output(UInt(idWidth.W))
  val araddr:   UInt = Output(UInt(addrWidth.W))
  val arlen:    UInt = Output(UInt(lenWidth.W))
  val arsize:   UInt = Output(UInt(sizeWidth.W))
  val arburst:  UInt = Output(UInt(burstWidth.W))
  val arlock:   UInt = Output(UInt(lockWidth.W))
  val arcache:  UInt = Output(UInt(cacheWidth.W))
  val arprot:   UInt = Output(UInt(protWidth.W))
  val arqos:    UInt = Output(UInt(4.W))
  val arregion: UInt = Output(UInt(4.W))
  val arvalid:  Bool = Output(Bool())
  val arready:  Bool = Input(Bool())

  val rid:      UInt = Input(UInt(idWidth.W))
  val rdata:    UInt = Input(UInt(dataWidth.W))
  val rresp:    UInt = Input(UInt(respWidth.W))
  val rlast:    Bool = Input(Bool())
  val rvalid:   Bool = Input(Bool())
  val rready:   Bool = Output(Bool())
}

object AXI4IO {
  def tieOffInputs(axi: AXI4IO): Unit = {
    axi.awready := false.B
    axi.wready  := false.B

    axi.bid     := 0.U
    axi.bresp   := 0.U
    axi.bvalid  := false.B

    axi.arready := false.B

    axi.rid     := 0.U
    axi.rdata   := 0.U
    axi.rresp   := 0.U
    axi.rlast   := false.B
    axi.rvalid  := false.B
  }

  def tieOffOutputs(axi: AXI4IO): Unit = {
    axi.awid     := 0.U
    axi.awaddr   := 0.U
    axi.awlen    := 0.U
    axi.awsize   := 0.U
    axi.awburst  := 0.U
    axi.awlock   := 0.U
    axi.awcache  := 0.U
    axi.awprot   := 0.U
    axi.awqos    := 0.U
    axi.awregion := 0.U
    axi.awvalid  := false.B

    axi.wdata    := 0.U
    axi.wstrb    := 0.U
    axi.wlast    := false.B
    axi.wvalid   := false.B

    axi.bready   := false.B

    axi.arid     := 0.U
    axi.araddr   := 0.U
    axi.arlen    := 0.U
    axi.arsize   := 0.U
    axi.arburst  := 0.U
    axi.arlock   := 0.U
    axi.arcache  := 0.U
    axi.arprot   := 0.U
    axi.arqos    := 0.U
    axi.arregion := 0.U
    axi.arvalid  := false.B

    axi.rready   := false.B
  }
}


/** AXI4 Bundle 之间的简单连线适配工具。
  *
  * 该适配器只负责连接同协议形态的 AXI4 通道，并在位宽不一致时截断高位或高位补零。
  * 它不做协议转换、跨时钟域处理、缓冲、仲裁或数据位宽转换。
  */
object AXI4PortAdapter {
  private def adapt(source: UInt, width: Int): UInt = {
    if (source.getWidth > width) {
      source(width - 1, 0)
    } else if (source.getWidth < width) {
      Cat(0.U((width - source.getWidth).W), source)
    } else {
      source
    }
  }

  /** 连接一个 AXI4 master-facing Bundle 和一个 AXI4 slave-facing Bundle。 */
  def connectMasterToSlave(master: AXI4IO, slave: AXI4IO): Unit = {
    slave.awid     := adapt(master.awid, slave.awid.getWidth)
    slave.awaddr   := adapt(master.awaddr, slave.awaddr.getWidth)
    slave.awlen    := adapt(master.awlen, slave.awlen.getWidth)
    slave.awsize   := adapt(master.awsize, slave.awsize.getWidth)
    slave.awburst  := adapt(master.awburst, slave.awburst.getWidth)
    slave.awlock   := adapt(master.awlock, slave.awlock.getWidth)
    slave.awcache  := adapt(master.awcache, slave.awcache.getWidth)
    slave.awprot   := adapt(master.awprot, slave.awprot.getWidth)
    slave.awqos    := adapt(master.awqos, slave.awqos.getWidth)
    slave.awregion := adapt(master.awregion, slave.awregion.getWidth)
    slave.awvalid  := master.awvalid
    master.awready := slave.awready

    slave.wdata   := adapt(master.wdata, slave.wdata.getWidth)
    slave.wstrb   := adapt(master.wstrb, slave.wstrb.getWidth)
    slave.wlast   := master.wlast
    slave.wvalid  := master.wvalid
    master.wready := slave.wready

    master.bid    := adapt(slave.bid, master.bid.getWidth)
    master.bresp  := adapt(slave.bresp, master.bresp.getWidth)
    master.bvalid := slave.bvalid
    slave.bready  := master.bready

    slave.arid     := adapt(master.arid, slave.arid.getWidth)
    slave.araddr   := adapt(master.araddr, slave.araddr.getWidth)
    slave.arlen    := adapt(master.arlen, slave.arlen.getWidth)
    slave.arsize   := adapt(master.arsize, slave.arsize.getWidth)
    slave.arburst  := adapt(master.arburst, slave.arburst.getWidth)
    slave.arlock   := adapt(master.arlock, slave.arlock.getWidth)
    slave.arcache  := adapt(master.arcache, slave.arcache.getWidth)
    slave.arprot   := adapt(master.arprot, slave.arprot.getWidth)
    slave.arqos    := adapt(master.arqos, slave.arqos.getWidth)
    slave.arregion := adapt(master.arregion, slave.arregion.getWidth)
    slave.arvalid  := master.arvalid
    master.arready := slave.arready

    master.rid    := adapt(slave.rid, master.rid.getWidth)
    master.rdata  := adapt(slave.rdata, master.rdata.getWidth)
    master.rresp  := adapt(slave.rresp, master.rresp.getWidth)
    master.rlast  := slave.rlast
    master.rvalid := slave.rvalid
    slave.rready  := master.rready
  }
}


class APB3IO(
  addrWidth: Int = SocConfig.apbAddrWidth,
  dataWidth: Int = SocConfig.apbDataWidth
) extends Bundle {
  val psel:    Bool = Output(Bool())
  val penable: Bool = Output(Bool())
  val pwrite:  Bool = Output(Bool())
  val paddr:   UInt = Output(UInt(addrWidth.W))
  val pwdata:  UInt = Output(UInt(dataWidth.W))
  val prdata:  UInt = Input(UInt(dataWidth.W))
  val pready:  Bool = Input(Bool())
  val pslverr: Bool = Input(Bool())
}

object APB3IO {
  def tieOffInputs(apb: APB3IO): Unit = {
    apb.prdata  := 0.U
    apb.pready  := false.B
    apb.pslverr := false.B
  }

  def tieOffOutputs(apb: APB3IO): Unit = {
    apb.psel    := false.B
    apb.penable := false.B
    apb.pwrite  := false.B
    apb.paddr   := 0.U
    apb.pwdata  := 0.U
  }

  private def adapt(source: UInt, width: Int): UInt = {
    if (source.getWidth > width) {
      source(width - 1, 0)
    } else if (source.getWidth < width) {
      Cat(0.U((width - source.getWidth).W), source)
    } else {
      source
    }
  }

  /** 连接一个 APB3 master-facing Bundle 和一个 APB3 slave-facing Bundle。
    *
    * 仅在两侧 APB3 协议形态相同、但地址或数据位宽可能不同时使用。
    * 地址写数据位宽不匹配时截断高位或高位补零；读数据返回 master 侧时同样处理。
    * 它不做协议转换、跨时钟域处理或缓冲。
    */
  def connectMasterToSlave(master: APB3IO, slave: APB3IO): Unit = {
    slave.psel    := master.psel
    slave.penable := master.penable
    slave.pwrite  := master.pwrite
    slave.paddr   := adapt(master.paddr, slave.paddr.getWidth)
    slave.pwdata  := adapt(master.pwdata, slave.pwdata.getWidth)
    master.pready := slave.pready
    master.prdata := adapt(slave.prdata, master.prdata.getWidth)
    master.pslverr := slave.pslverr
  }
}
