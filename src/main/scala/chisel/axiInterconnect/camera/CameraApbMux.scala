package chisel.axiInterconnect.camera

import chisel3._
import chisel3.util.Cat
import chisel.common.bus.APB3IO

class CameraApbMuxIO extends Bundle {
  val upstream: APB3IO = Flipped(new APB3IO(addrWidth = 13))
  val capture: APB3IO = new APB3IO(addrWidth = 13)
  val sccb: APB3IO = new APB3IO(addrWidth = 20)
}

/** 将 camera 的 8 KiB APB 页拆为 capture 与 SCCB 两个 4 KiB 子窗口。
  *
  * 下游地址统一清除 bit 12，使既有 APB-to-Wishbone 桥看到从零开始的局部地址。
  */
class CameraApbMux extends RawModule {
  val io: CameraApbMuxIO = IO(new CameraApbMuxIO)

  private val sccbSelected = io.upstream.paddr(12)
  private val localAddress = Cat(0.U(8.W), io.upstream.paddr(11, 0))

  private def connectOutput(port: APB3IO, selected: Bool): Unit = {
    port.psel := io.upstream.psel && selected
    port.penable := io.upstream.penable && selected
    port.pwrite := io.upstream.pwrite
    port.paddr := localAddress
    port.pwdata := io.upstream.pwdata
  }

  connectOutput(io.capture, !sccbSelected)
  connectOutput(io.sccb, sccbSelected)

  io.upstream.prdata := Mux(sccbSelected, io.sccb.prdata, io.capture.prdata)
  io.upstream.pready := Mux(sccbSelected, io.sccb.pready, io.capture.pready)
  io.upstream.pslverr := Mux(sccbSelected, io.sccb.pslverr, io.capture.pslverr)
}
