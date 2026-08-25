package chisel

import chisel3._

class Axi4MasterArbiter2IO extends Bundle {
  val masters: Vec[AXI4IO] = Vec(2, Flipped(new AXI4IO(
    idWidth = 3,
    addrWidth = 32,
    lenWidth = 8,
    lockWidth = 1,
    dataWidth = 32,
    strbWidth = 4
  )))
  val slave: AXI4IO = new AXI4IO(
    idWidth = 3,
    addrWidth = 32,
    lenWidth = 8,
    lockWidth = 1,
    dataWidth = 32,
    strbWidth = 4
  )
}

/** 两路 AXI4 master 到一路 DDR interconnect 端口的事务级仲裁器。
  *
  * 读写通道分别采用轮转仲裁；写事务从选择 master 起锁定到 B 响应，读事务锁定到
  * `RVALID && RREADY && RLAST`。本模块不改变ID、突发或响应内容，也不提供跨时钟域转换。
  */
class Axi4MasterArbiter2 extends Module {
  val io: Axi4MasterArbiter2IO = IO(new Axi4MasterArbiter2IO)

  for (master <- io.masters) {
    master.awready := false.B
    master.wready := false.B
    master.bid := 0.U
    master.bresp := 0.U
    master.bvalid := false.B
    master.arready := false.B
    master.rid := 0.U
    master.rdata := 0.U
    master.rresp := 0.U
    master.rlast := false.B
    master.rvalid := false.B
  }

  private val writeActive = RegInit(false.B)
  private val writeOwner = RegInit(false.B)
  private val writeAddressDone = RegInit(false.B)
  private val writeDataDone = RegInit(false.B)
  private val lastWriteOwner = RegInit(false.B)

  private val writeRequest0 = io.masters(0).awvalid || io.masters(0).wvalid
  private val writeRequest1 = io.masters(1).awvalid || io.masters(1).wvalid
  private val nextWriteOwner = Mux(
    writeRequest0 && writeRequest1,
    !lastWriteOwner,
    writeRequest1
  )

  private def selectWrite(a: UInt, b: UInt): UInt = Mux(writeOwner, b, a)
  private def selectWrite(a: Bool, b: Bool): Bool = Mux(writeOwner, b, a)

  io.slave.awid := selectWrite(io.masters(0).awid, io.masters(1).awid)
  io.slave.awaddr := selectWrite(io.masters(0).awaddr, io.masters(1).awaddr)
  io.slave.awlen := selectWrite(io.masters(0).awlen, io.masters(1).awlen)
  io.slave.awsize := selectWrite(io.masters(0).awsize, io.masters(1).awsize)
  io.slave.awburst := selectWrite(io.masters(0).awburst, io.masters(1).awburst)
  io.slave.awlock := selectWrite(io.masters(0).awlock, io.masters(1).awlock)
  io.slave.awcache := selectWrite(io.masters(0).awcache, io.masters(1).awcache)
  io.slave.awprot := selectWrite(io.masters(0).awprot, io.masters(1).awprot)
  io.slave.awqos := selectWrite(io.masters(0).awqos, io.masters(1).awqos)
  io.slave.awregion := selectWrite(io.masters(0).awregion, io.masters(1).awregion)
  io.slave.awvalid := writeActive && !writeAddressDone &&
    selectWrite(io.masters(0).awvalid, io.masters(1).awvalid)

  io.slave.wdata := selectWrite(io.masters(0).wdata, io.masters(1).wdata)
  io.slave.wstrb := selectWrite(io.masters(0).wstrb, io.masters(1).wstrb)
  io.slave.wlast := selectWrite(io.masters(0).wlast, io.masters(1).wlast)
  io.slave.wvalid := writeActive && !writeDataDone &&
    selectWrite(io.masters(0).wvalid, io.masters(1).wvalid)

  private val writeResponsePhase = writeAddressDone && writeDataDone
  io.slave.bready := writeActive && writeResponsePhase &&
    selectWrite(io.masters(0).bready, io.masters(1).bready)

  for (index <- 0 until 2) {
    when(writeActive && writeOwner === index.B) {
      io.masters(index).awready := io.slave.awready && !writeAddressDone
      io.masters(index).wready := io.slave.wready && !writeDataDone
      io.masters(index).bid := io.slave.bid
      io.masters(index).bresp := io.slave.bresp
      io.masters(index).bvalid := io.slave.bvalid && writeResponsePhase
    }
  }

  private val writeAddressFire = io.slave.awvalid && io.slave.awready
  private val writeDataFire = io.slave.wvalid && io.slave.wready
  private val writeResponseFire = io.slave.bvalid && io.slave.bready

  when(!writeActive && (writeRequest0 || writeRequest1)) {
    writeActive := true.B
    writeOwner := nextWriteOwner
    writeAddressDone := false.B
    writeDataDone := false.B
  }.elsewhen(writeActive) {
    when(writeAddressFire) { writeAddressDone := true.B }
    when(writeDataFire) { writeDataDone := true.B }
    when(writeResponseFire) {
      writeActive := false.B
      writeAddressDone := false.B
      writeDataDone := false.B
      lastWriteOwner := writeOwner
    }
  }

  private val readActive = RegInit(false.B)
  private val readOwner = RegInit(false.B)
  private val readAddressDone = RegInit(false.B)
  private val lastReadOwner = RegInit(false.B)
  private val readRequest0 = io.masters(0).arvalid
  private val readRequest1 = io.masters(1).arvalid
  private val nextReadOwner = Mux(
    readRequest0 && readRequest1,
    !lastReadOwner,
    readRequest1
  )

  private def selectRead(a: UInt, b: UInt): UInt = Mux(readOwner, b, a)
  private def selectRead(a: Bool, b: Bool): Bool = Mux(readOwner, b, a)

  io.slave.arid := selectRead(io.masters(0).arid, io.masters(1).arid)
  io.slave.araddr := selectRead(io.masters(0).araddr, io.masters(1).araddr)
  io.slave.arlen := selectRead(io.masters(0).arlen, io.masters(1).arlen)
  io.slave.arsize := selectRead(io.masters(0).arsize, io.masters(1).arsize)
  io.slave.arburst := selectRead(io.masters(0).arburst, io.masters(1).arburst)
  io.slave.arlock := selectRead(io.masters(0).arlock, io.masters(1).arlock)
  io.slave.arcache := selectRead(io.masters(0).arcache, io.masters(1).arcache)
  io.slave.arprot := selectRead(io.masters(0).arprot, io.masters(1).arprot)
  io.slave.arqos := selectRead(io.masters(0).arqos, io.masters(1).arqos)
  io.slave.arregion := selectRead(io.masters(0).arregion, io.masters(1).arregion)
  io.slave.arvalid := readActive && !readAddressDone &&
    selectRead(io.masters(0).arvalid, io.masters(1).arvalid)
  io.slave.rready := readActive && readAddressDone &&
    selectRead(io.masters(0).rready, io.masters(1).rready)

  for (index <- 0 until 2) {
    when(readActive && readOwner === index.B) {
      io.masters(index).arready := io.slave.arready && !readAddressDone
      io.masters(index).rid := io.slave.rid
      io.masters(index).rdata := io.slave.rdata
      io.masters(index).rresp := io.slave.rresp
      io.masters(index).rlast := io.slave.rlast
      io.masters(index).rvalid := io.slave.rvalid && readAddressDone
    }
  }

  private val readAddressFire = io.slave.arvalid && io.slave.arready
  private val readResponseFire = io.slave.rvalid && io.slave.rready

  when(!readActive && (readRequest0 || readRequest1)) {
    readActive := true.B
    readOwner := nextReadOwner
    readAddressDone := false.B
  }.elsewhen(readActive) {
    when(readAddressFire) { readAddressDone := true.B }
    when(readResponseFire && io.slave.rlast) {
      readActive := false.B
      readAddressDone := false.B
      lastReadOwner := readOwner
    }
  }
}
