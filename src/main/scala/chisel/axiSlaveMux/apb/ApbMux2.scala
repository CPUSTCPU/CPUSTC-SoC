package chisel.axiSlaveMux.apb

import chisel3._
import chisel3.util.{Cat, MuxCase}
import chisel.axiInterconnect.nand.ApbMuxDmaPort
import chisel.common.bus.APB3IO

class LegacyApb8Port extends Bundle {
  val request: Bool = Output(Bool())
  val acknowledge: Bool = Input(Bool())
  val write: Bool = Output(Bool())
  val psel: Bool = Output(Bool())
  val penable: Bool = Output(Bool())
  val addr: UInt = Output(UInt(20.W))
  val writeData: UInt = Output(UInt(8.W))
  val readData: UInt = Input(UInt(8.W))
}

class LegacyApb32Port extends Bundle {
  val request: Bool = Output(Bool())
  val acknowledge: Bool = Input(Bool())
  val write: Bool = Output(Bool())
  val psel: Bool = Output(Bool())
  val penable: Bool = Output(Bool())
  val addr: UInt = Output(UInt(20.W))
  val writeData: UInt = Output(UInt(32.W))
  val readData: UInt = Input(UInt(32.W))
}

/** `ApbMux2` 使用的连续 8 KiB 外设窗口页号。 */
object ApbMux2AddressMap {
  val windowOffsetWidth: Int = 13

  val uart: Int = 0x00
  val usb: Int = 0x01
  val display: Int = 0x02
  val lcdTouch: Int = 0x03
  val lcd: Int = 0x04
  val cascadedInterrupt: Int = 0x05
  val nand: Int = 0x06
  val camera: Int = 0x07
}

/** APB 页的编译期挂载配置；UART 和级联中断控制器固定保留。 */
final case class ApbMux2Config(
  usb: Boolean = true,
  display: Boolean = true,
  lcdTouch: Boolean = true,
  lcd: Boolean = true,
  nand: Boolean = true,
  camera: Boolean = true
)

/** 下游端口按 8 KiB 地址页递增编号，`apb0` 对应页 0，`apb6` 对应页 6。 */
class ApbMux2IO extends Bundle {
  val clk: Clock = Input(Clock())
  val resetn: Bool = Input(Bool())
  val cpu: Axi2ApbCpuPort = Flipped(new Axi2ApbCpuPort)
  val dma: ApbMuxDmaPort = new ApbMuxDmaPort
  val apb0: LegacyApb8Port = new LegacyApb8Port
  val apb1: APB3IO = new APB3IO(addrWidth = 20)
  val apb2: APB3IO = new APB3IO(addrWidth = 20)
  val apb3: APB3IO = new APB3IO(addrWidth = 20)
  val apb4: APB3IO = new APB3IO(addrWidth = 20)
  val apb5: APB3IO = new APB3IO(addrWidth = 20)
  val apb6: LegacyApb32Port = new LegacyApb32Port
  val apb7: APB3IO = new APB3IO(addrWidth = 13)
}

/** CPU 与 NAND DMA 共享的 APB 地址译码和响应选择器。
  *
  * 本模块保持 legacy `apb_mux2` 的仲裁和 8/32 位数据拼接。
  * 未挂载或未知页立即完成并报告错误；下游 APB3 `PSLVERR` 向 CPU bridge 传播。
  */
class ApbMux2(config: ApbMux2Config = ApbMux2Config()) extends RawModule {
  val io: ApbMux2IO = IO(new ApbMux2IO)

  private val dmaGrant = withClock(io.clk) {
    val grant = Reg(Bool())
    when(!io.resetn) {
      grant := false.B
    }.elsewhen(io.cpu.valid && !io.dma.valid) {
      grant := false.B
    }.elsewhen(io.dma.valid && !io.cpu.valid) {
      grant := true.B
    }.elsewhen(!io.cpu.valid && !io.dma.valid) {
      grant := false.B
    }
    grant
  }

  private val selectedAddress = Mux(dmaGrant, io.dma.addr, io.cpu.addr)
  private val selectedWrite = Mux(dmaGrant, io.dma.write, io.cpu.write)
  private val selectedPsel = Mux(dmaGrant, io.dma.psel, io.cpu.psel)
  private val selectedPenable = Mux(dmaGrant, io.dma.penable, io.cpu.penable)
  private val selectedWriteData = Mux(
    dmaGrant,
    io.dma.writeData,
    Cat(io.cpu.high24Write, io.cpu.writeData)
  )
  private val selectedPage = selectedAddress(19, ApbMux2AddressMap.windowOffsetWidth)
  private val selectedLocalAddress = Cat(
    0.U((20 - ApbMux2AddressMap.windowOffsetWidth).W),
    selectedAddress(ApbMux2AddressMap.windowOffsetWidth - 1, 0)
  )

  private val uartSelected = selectedPage === ApbMux2AddressMap.uart.U
  private val nandSelected = selectedPage === ApbMux2AddressMap.nand.U
  private val cameraSelected = selectedPage === ApbMux2AddressMap.camera.U
  private val usbSelected = selectedPage === ApbMux2AddressMap.usb.U
  private val displaySelected = selectedPage === ApbMux2AddressMap.display.U
  private val lcdTouchSelected = selectedPage === ApbMux2AddressMap.lcdTouch.U
  private val lcdSelected = selectedPage === ApbMux2AddressMap.lcd.U
  private val cascadedInterruptSelected =
    selectedPage === ApbMux2AddressMap.cascadedInterrupt.U
  private val usbEnabled = config.usb.B
  private val displayEnabled = config.display.B
  private val lcdTouchEnabled = config.lcdTouch.B
  private val lcdEnabled = config.lcd.B
  private val nandEnabled = config.nand.B
  private val cameraEnabled = config.camera.B

  io.apb0.request := uartSelected
  io.apb0.write := selectedWrite
  io.apb0.psel := selectedPsel && uartSelected
  io.apb0.penable := selectedPenable && uartSelected
  io.apb0.addr := selectedLocalAddress
  io.apb0.writeData := selectedWriteData(7, 0)

  io.apb6.request := nandSelected && nandEnabled
  io.apb6.write := selectedWrite
  io.apb6.psel := selectedPsel && nandSelected && nandEnabled
  io.apb6.penable := selectedPenable && nandSelected && nandEnabled
  io.apb6.addr := selectedLocalAddress
  io.apb6.writeData := selectedWriteData

  private def connectApb3Output(port: APB3IO, selected: Bool): Unit = {
    port.psel := selectedPsel && selected
    port.penable := selectedPenable && selected
    port.pwrite := selectedWrite
    port.paddr := selectedLocalAddress
    port.pwdata := selectedWriteData
  }

  connectApb3Output(io.apb1, usbSelected && usbEnabled)
  connectApb3Output(io.apb2, displaySelected && displayEnabled)
  connectApb3Output(io.apb3, lcdTouchSelected && lcdTouchEnabled)
  connectApb3Output(io.apb4, lcdSelected && lcdEnabled)
  connectApb3Output(io.apb5, cascadedInterruptSelected)
  connectApb3Output(io.apb7, cameraSelected && cameraEnabled)

  private val knownPageSelected = uartSelected || usbSelected || displaySelected ||
    lcdTouchSelected || lcdSelected || cascadedInterruptSelected || nandSelected ||
    cameraSelected
  private val unknownPageSelected = !knownPageSelected

  private val selectedReady = MuxCase(unknownPageSelected, Seq(
    uartSelected -> io.apb0.acknowledge,
    usbSelected -> Mux(usbEnabled, io.apb1.pready, true.B),
    displaySelected -> Mux(displayEnabled, io.apb2.pready, true.B),
    lcdTouchSelected -> Mux(lcdTouchEnabled, io.apb3.pready, true.B),
    lcdSelected -> Mux(lcdEnabled, io.apb4.pready, true.B),
    cascadedInterruptSelected -> io.apb5.pready,
    nandSelected -> Mux(nandEnabled, io.apb6.acknowledge, true.B),
    cameraSelected -> Mux(cameraEnabled, io.apb7.pready, true.B)
  ))
  private val selectedReadData = MuxCase(0.U(32.W), Seq(
    uartSelected -> Cat(0.U(24.W), io.apb0.readData),
    usbSelected -> io.apb1.prdata,
    displaySelected -> io.apb2.prdata,
    lcdTouchSelected -> io.apb3.prdata,
    lcdSelected -> io.apb4.prdata,
    cascadedInterruptSelected -> io.apb5.prdata,
    nandSelected -> io.apb6.readData,
    cameraSelected -> io.apb7.prdata
  ))
  private val cpuWordTransfer = nandSelected || usbSelected || displaySelected ||
    lcdTouchSelected || lcdSelected || cascadedInterruptSelected || cameraSelected
  private val selectedError = MuxCase(unknownPageSelected && selectedPenable, Seq(
    usbSelected -> Mux(usbEnabled, io.apb1.pslverr, selectedPenable),
    displaySelected -> Mux(displayEnabled, io.apb2.pslverr, selectedPenable),
    lcdTouchSelected -> Mux(lcdTouchEnabled, io.apb3.pslverr, selectedPenable),
    lcdSelected -> Mux(lcdEnabled, io.apb4.pslverr, selectedPenable),
    cascadedInterruptSelected -> io.apb5.pslverr,
    nandSelected -> (!nandEnabled && selectedPenable),
    cameraSelected -> Mux(cameraEnabled, io.apb7.pslverr, selectedPenable)
  ))

  io.cpu.ready := !dmaGrant && selectedReady
  io.cpu.readData := Mux(dmaGrant, 0.U, selectedReadData(7, 0))
  io.cpu.high24Read := Mux(dmaGrant, 0.U, selectedReadData(31, 8))
  io.cpu.wordTrans := !dmaGrant && cpuWordTransfer
  io.cpu.grant := !dmaGrant
  io.cpu.error := !dmaGrant && selectedError

  io.dma.ready := dmaGrant && selectedReady
  io.dma.readData := Mux(dmaGrant, selectedReadData, 0.U)
  io.dma.grant := dmaGrant
}
