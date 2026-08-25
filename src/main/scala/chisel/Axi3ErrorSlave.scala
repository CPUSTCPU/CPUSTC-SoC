package chisel

import chisel3._

/** AXI3 DECERR 从设备接口。 */
class Axi3ErrorSlaveIO(
  idWidth: Int = SocConfig.axiIdWidth,
  addrWidth: Int = SocConfig.axiAddrWidth,
  lenWidth: Int = SocConfig.axiLenWidth,
  dataWidth: Int = SocConfig.axiDataWidth
) extends Bundle {
  val axi: AXI3IO = Flipped(new AXI3IO(
    idWidth = idWidth,
    addrWidth = addrWidth,
    lenWidth = lenWidth,
    dataWidth = dataWidth,
    strbWidth = dataWidth / 8
  ))
}

/** 为未挂载的 CPU AXI3 地址窗口返回 DECERR。
  *
  * 本模块支持一个写事务和一个读事务；写通道接收至 WLAST 后应答，读通道按
  * ARLEN 返回相同拍数的零数据。它只用于固定地址译码中的缺席外设窗口。
  */
class Axi3ErrorSlave(
  idWidth: Int = SocConfig.axiIdWidth,
  addrWidth: Int = SocConfig.axiAddrWidth,
  lenWidth: Int = SocConfig.axiLenWidth,
  dataWidth: Int = SocConfig.axiDataWidth
) extends Module {
  val io: Axi3ErrorSlaveIO = IO(new Axi3ErrorSlaveIO(
    idWidth = idWidth,
    addrWidth = addrWidth,
    lenWidth = lenWidth,
    dataWidth = dataWidth
  ))

  private val decerr = 3.U(SocConfig.axiRespWidth.W)

  private val writeIdReg = RegInit(0.U(idWidth.W))
  private val writeAddressAcceptedReg = RegInit(false.B)
  private val writeLastAcceptedReg = RegInit(false.B)
  private val writeResponseValidReg = RegInit(false.B)

  private val writeAddressFire = io.axi.awvalid && io.axi.awready
  private val writeDataFire = io.axi.wvalid && io.axi.wready
  private val writeLastFire = writeDataFire && io.axi.wlast
  private val writeResponseFire = writeResponseValidReg && io.axi.bready

  io.axi.awready := !writeAddressAcceptedReg && !writeResponseValidReg
  io.axi.wready := !writeLastAcceptedReg && !writeResponseValidReg
  io.axi.bid := writeIdReg
  io.axi.bresp := decerr
  io.axi.bvalid := writeResponseValidReg

  when(writeAddressFire) {
    writeIdReg := io.axi.awid
    writeAddressAcceptedReg := true.B
  }
  when(writeLastFire) {
    writeLastAcceptedReg := true.B
  }
  when(!writeResponseValidReg &&
    (writeAddressAcceptedReg || writeAddressFire) &&
    (writeLastAcceptedReg || writeLastFire)) {
    writeResponseValidReg := true.B
  }
  when(writeResponseFire) {
    writeAddressAcceptedReg := false.B
    writeLastAcceptedReg := false.B
    writeResponseValidReg := false.B
  }

  private val readIdReg = RegInit(0.U(idWidth.W))
  private val readBeatsRemainingReg = RegInit(0.U(lenWidth.W))
  private val readValidReg = RegInit(false.B)
  private val readAddressFire = io.axi.arvalid && io.axi.arready
  private val readDataFire = readValidReg && io.axi.rready

  io.axi.arready := !readValidReg
  io.axi.rid := readIdReg
  io.axi.rdata := 0.U
  io.axi.rresp := decerr
  io.axi.rlast := readBeatsRemainingReg === 0.U
  io.axi.rvalid := readValidReg

  when(readAddressFire) {
    readIdReg := io.axi.arid
    readBeatsRemainingReg := io.axi.arlen
    readValidReg := true.B
  }
  when(readDataFire) {
    when(readBeatsRemainingReg === 0.U) {
      readValidReg := false.B
    }.otherwise {
      readBeatsRemainingReg := readBeatsRemainingReg - 1.U
    }
  }
}
