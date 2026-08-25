package chisel

import chisel3._
import chisel3.util.{Mux1H, MuxCase, Queue, RRArbiter, log2Ceil}

/** AXI 地址窗口。`mask` 中为 1 的地址位参与和 `base` 的比较。 */
final case class AxiAddressWindow(base: BigInt, mask: BigInt, output: Int)

/** CPU 本地 AXI 地址空间到从口编号的集中定义。 */
object AxiSlaveMuxAddressMap {
  val ddr: Int = 0
  val spiFlash: Int = 1
  val apb: Int = 2
  val confreg: Int = 3
  val ethernet: Int = 4
  val sdio: Int = 5
  val outputCount: Int = 6

  private val windows: Seq[AxiAddressWindow] = Seq(
    AxiAddressWindow(base = BigInt("1c000000", 16), mask = BigInt("fff00000", 16), output = spiFlash),
    AxiAddressWindow(base = BigInt("1fe80000", 16), mask = BigInt("ffff0000", 16), output = spiFlash),
    AxiAddressWindow(base = BigInt("1fe00000", 16), mask = BigInt("ffff0000", 16), output = apb),
    AxiAddressWindow(base = BigInt("1fd00000", 16), mask = BigInt("ffff0000", 16), output = confreg),
    AxiAddressWindow(base = BigInt("1ff00000", 16), mask = BigInt("ffff0000", 16), output = ethernet),
    AxiAddressWindow(base = BigInt("1fe10000", 16), mask = BigInt("ffff0000", 16), output = sdio)
  )

  require(windows.forall(window => window.output > ddr && window.output < outputCount))

  /** 将 CPU 字节地址译码为 AXI 从口，未命中外设窗口的地址进入 DDR。 */
  def decode(address: UInt): UInt = {
    val routeWidth = log2Ceil(outputCount)
    MuxCase(
      ddr.U(routeWidth.W),
      windows.map { window =>
        val mask = window.mask.U(address.getWidth.W)
        ((address & mask) === window.base.U(address.getWidth.W)) -> window.output.U(routeWidth.W)
      }
    )
  }
}

class AxiSlaveMuxIO(
  idWidth: Int = SocConfig.axiIdWidth,
  addrWidth: Int = SocConfig.axiAddrWidth,
  lenWidth: Int = SocConfig.axiLenWidth,
  sizeWidth: Int = SocConfig.axiSizeWidth,
  burstWidth: Int = SocConfig.axiBurstWidth,
  lockWidth: Int = SocConfig.axiLockWidth,
  cacheWidth: Int = SocConfig.axiCacheWidth,
  protWidth: Int = SocConfig.axiProtWidth,
  dataWidth: Int = SocConfig.axiDataWidth,
  strbWidth: Int = SocConfig.axiStrbWidth,
  respWidth: Int = SocConfig.axiRespWidth,
  outputCount: Int = AxiSlaveMuxAddressMap.outputCount
) extends Bundle {
  val clk: Clock = Input(Clock())
  val resetn: Bool = Input(Bool())
  val spiBoot: Bool = Input(Bool())
  val axiSlave: AXI3IO = Flipped(new AXI3IO(
    idWidth,
    addrWidth,
    lenWidth,
    sizeWidth,
    burstWidth,
    lockWidth,
    cacheWidth,
    protWidth,
    dataWidth,
    strbWidth,
    respWidth
  ))
  val axiMasters: Vec[AXI3IO] = Vec(outputCount, new AXI3IO(
    idWidth,
    addrWidth,
    lenWidth,
    sizeWidth,
    burstWidth,
    lockWidth,
    cacheWidth,
    protWidth,
    dataWidth,
    strbWidth,
    respWidth
  ))
}

private class AxiWriteResponse(idWidth: Int, respWidth: Int) extends Bundle {
  val id: UInt = UInt(idWidth.W)
  val resp: UInt = UInt(respWidth.W)
}

/** 单 AXI3 master 到多个 AXI3 slave 的地址路由器。
  *
  * 写地址和读地址分别以二项方向队列记录目标从口。写数据严格按照 AW
  * 接收顺序转发，读数据严格按照 AR 接收顺序返回；不同从口的 B 响应
  * 允许通过 ID 独立仲裁。本模块不做协议、时钟或数据位宽转换。
  */
class AxiSlaveMux(
  idWidth: Int = SocConfig.axiIdWidth,
  addrWidth: Int = SocConfig.axiAddrWidth,
  lenWidth: Int = SocConfig.axiLenWidth,
  sizeWidth: Int = SocConfig.axiSizeWidth,
  burstWidth: Int = SocConfig.axiBurstWidth,
  lockWidth: Int = SocConfig.axiLockWidth,
  cacheWidth: Int = SocConfig.axiCacheWidth,
  protWidth: Int = SocConfig.axiProtWidth,
  dataWidth: Int = SocConfig.axiDataWidth,
  strbWidth: Int = SocConfig.axiStrbWidth,
  respWidth: Int = SocConfig.axiRespWidth,
  outstandingDepth: Int = 2
) extends RawModule {
  override def desiredName: String = "AxiSlaveMux"

  private val outputCount = AxiSlaveMuxAddressMap.outputCount
  private val routeWidth = log2Ceil(outputCount)

  require(outstandingDepth > 0)

  val io: AxiSlaveMuxIO = IO(new AxiSlaveMuxIO(
    idWidth,
    addrWidth,
    lenWidth,
    sizeWidth,
    burstWidth,
    lockWidth,
    cacheWidth,
    protWidth,
    dataWidth,
    strbWidth,
    respWidth,
    outputCount
  ))

  private val moduleReset = (!io.resetn).asAsyncReset
  private val writeRoutes = withClockAndReset(io.clk, moduleReset) {
    Module(new Queue(UInt(routeWidth.W), outstandingDepth))
  }
  private val readRoutes = withClockAndReset(io.clk, moduleReset) {
    Module(new Queue(UInt(routeWidth.W), outstandingDepth))
  }
  private val writeResponseArbiter = withClockAndReset(io.clk, moduleReset) {
    Module(new RRArbiter(new AxiWriteResponse(idWidth, respWidth), outputCount))
  }
  private val writeResponseQueue = withClockAndReset(io.clk, moduleReset) {
    Module(new Queue(new AxiWriteResponse(idWidth, respWidth), 1))
  }

  private val writeAddressRoute = AxiSlaveMuxAddressMap.decode(io.axiSlave.awaddr)
  private val readAddressRoute = AxiSlaveMuxAddressMap.decode(io.axiSlave.araddr)
  private val writeAddressSelect = VecInit.tabulate(outputCount)(writeAddressRoute === _.U)
  private val writeDataSelect = VecInit.tabulate(outputCount)(writeRoutes.io.deq.bits === _.U)
  private val readAddressSelect = VecInit.tabulate(outputCount)(readAddressRoute === _.U)
  private val readDataSelect = VecInit.tabulate(outputCount)(readRoutes.io.deq.bits === _.U)

  for (index <- 0 until outputCount) {
    val master = io.axiMasters(index)

    master.awid := io.axiSlave.awid
    master.awaddr := io.axiSlave.awaddr
    master.awlen := io.axiSlave.awlen
    master.awsize := io.axiSlave.awsize
    master.awburst := io.axiSlave.awburst
    master.awlock := io.axiSlave.awlock
    master.awcache := io.axiSlave.awcache
    master.awprot := io.axiSlave.awprot
    master.awvalid := io.axiSlave.awvalid && writeRoutes.io.enq.ready && writeAddressSelect(index)

    master.wid := io.axiSlave.wid
    master.wdata := io.axiSlave.wdata
    master.wstrb := io.axiSlave.wstrb
    master.wlast := io.axiSlave.wlast
    master.wvalid := io.axiSlave.wvalid && writeRoutes.io.deq.valid && writeDataSelect(index)

    writeResponseArbiter.io.in(index).valid := master.bvalid
    writeResponseArbiter.io.in(index).bits.id := master.bid
    writeResponseArbiter.io.in(index).bits.resp := master.bresp
    master.bready := writeResponseArbiter.io.in(index).ready

    master.arid := io.axiSlave.arid
    master.araddr := io.axiSlave.araddr
    master.arlen := io.axiSlave.arlen
    master.arsize := io.axiSlave.arsize
    master.arburst := io.axiSlave.arburst
    master.arlock := io.axiSlave.arlock
    master.arcache := io.axiSlave.arcache
    master.arprot := io.axiSlave.arprot
    master.arvalid := io.axiSlave.arvalid && readRoutes.io.enq.ready && readAddressSelect(index)

    master.rready := io.axiSlave.rready && readRoutes.io.deq.valid && readDataSelect(index)
  }

  private val selectedAwReady = Mux1H(writeAddressSelect, io.axiMasters.map(_.awready))
  io.axiSlave.awready := writeRoutes.io.enq.ready && selectedAwReady
  writeRoutes.io.enq.valid := io.axiSlave.awvalid && selectedAwReady
  writeRoutes.io.enq.bits := writeAddressRoute

  private val selectedWReady = Mux1H(writeDataSelect, io.axiMasters.map(_.wready))
  io.axiSlave.wready := writeRoutes.io.deq.valid && selectedWReady
  writeRoutes.io.deq.ready := io.axiSlave.wvalid && io.axiSlave.wready && io.axiSlave.wlast

  writeResponseQueue.io.enq <> writeResponseArbiter.io.out
  io.axiSlave.bid := writeResponseQueue.io.deq.bits.id
  io.axiSlave.bresp := writeResponseQueue.io.deq.bits.resp
  io.axiSlave.bvalid := writeResponseQueue.io.deq.valid
  writeResponseQueue.io.deq.ready := io.axiSlave.bready

  private val selectedArReady = Mux1H(readAddressSelect, io.axiMasters.map(_.arready))
  io.axiSlave.arready := readRoutes.io.enq.ready && selectedArReady
  readRoutes.io.enq.valid := io.axiSlave.arvalid && selectedArReady
  readRoutes.io.enq.bits := readAddressRoute

  private val selectedRId = Mux1H(readDataSelect, io.axiMasters.map(_.rid))
  private val selectedRData = Mux1H(readDataSelect, io.axiMasters.map(_.rdata))
  private val selectedRResp = Mux1H(readDataSelect, io.axiMasters.map(_.rresp))
  private val selectedRLast = Mux1H(readDataSelect, io.axiMasters.map(_.rlast))
  private val selectedRValid = Mux1H(readDataSelect, io.axiMasters.map(_.rvalid))

  io.axiSlave.rid := selectedRId
  io.axiSlave.rdata := selectedRData
  io.axiSlave.rresp := selectedRResp
  io.axiSlave.rlast := readRoutes.io.deq.valid && selectedRLast
  io.axiSlave.rvalid := readRoutes.io.deq.valid && selectedRValid
  readRoutes.io.deq.ready := io.axiSlave.rvalid && io.axiSlave.rready && io.axiSlave.rlast

  // `spiBoot` 是旧顶层兼容端口，legacy RTL 中也未参与地址译码。
  dontTouch(io.spiBoot)
}
