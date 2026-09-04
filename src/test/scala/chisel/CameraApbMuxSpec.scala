package chisel

import chisel3._
import chiseltest._
import chiseltest.simulator.VerilatorBackendAnnotation
import chisel.axiInterconnect.camera.CameraApbMux
import chisel.common.bus.APB3IO
import org.scalatest.freespec.AnyFreeSpec

private class CameraApbMuxHarness extends Module {
  val io = IO(new Bundle {
    val upstream = Flipped(new APB3IO(addrWidth = 13))
    val capture = new APB3IO(addrWidth = 13)
    val sccb = new APB3IO(addrWidth = 20)
  })

  private val mux = Module(new CameraApbMux)
  io.upstream <> mux.io.upstream
  io.capture <> mux.io.capture
  io.sccb <> mux.io.sccb
}

class CameraApbMuxSpec extends AnyFreeSpec with ChiselScalatestTester {
  private val annotations = Seq(VerilatorBackendAnnotation)

  private def initialize(dut: CameraApbMuxHarness): Unit = {
    dut.io.upstream.psel.poke(false.B)
    dut.io.upstream.penable.poke(false.B)
    dut.io.upstream.pwrite.poke(false.B)
    dut.io.upstream.paddr.poke(0.U)
    dut.io.upstream.pwdata.poke(0.U)
    for (port <- Seq(dut.io.capture, dut.io.sccb)) {
      port.prdata.poke(0.U)
      port.pready.poke(false.B)
      port.pslverr.poke(false.B)
    }
  }

  "CameraApbMux should route both 4 KiB subwindows with local addresses" in {
    test(new CameraApbMuxHarness).withAnnotations(annotations) { dut =>
      initialize(dut)

      for ((address, captureSelected) <- Seq(
        0x0000 -> true,
        0x0fff -> true,
        0x1000 -> false,
        0x1fff -> false
      )) {
        val localAddress = address & 0x0fff
        dut.io.upstream.psel.poke(true.B)
        dut.io.upstream.penable.poke(false.B)
        dut.io.upstream.pwrite.poke(true.B)
        dut.io.upstream.paddr.poke(address.U)
        dut.io.upstream.pwdata.poke("h89abcdef".U)

        dut.io.capture.psel.expect(captureSelected.B)
        dut.io.sccb.psel.expect((!captureSelected).B)
        dut.io.capture.penable.expect(false.B)
        dut.io.sccb.penable.expect(false.B)
        dut.io.capture.paddr.expect(localAddress.U)
        dut.io.sccb.paddr.expect(localAddress.U)

        dut.io.upstream.penable.poke(true.B)
        dut.io.capture.penable.expect(captureSelected.B)
        dut.io.sccb.penable.expect((!captureSelected).B)
      }
    }
  }

  "CameraApbMux should return only the selected downstream response" in {
    test(new CameraApbMuxHarness).withAnnotations(annotations) { dut =>
      initialize(dut)
      dut.io.capture.prdata.poke("h11223344".U)
      dut.io.capture.pready.poke(true.B)
      dut.io.capture.pslverr.poke(false.B)
      dut.io.sccb.prdata.poke("haabbccdd".U)
      dut.io.sccb.pready.poke(false.B)
      dut.io.sccb.pslverr.poke(true.B)

      dut.io.upstream.psel.poke(true.B)
      dut.io.upstream.penable.poke(true.B)
      dut.io.upstream.paddr.poke(0x024.U)
      dut.io.upstream.prdata.expect("h11223344".U)
      dut.io.upstream.pready.expect(true.B)
      dut.io.upstream.pslverr.expect(false.B)

      dut.io.upstream.paddr.poke(0x1018.U)
      dut.io.upstream.prdata.expect("haabbccdd".U)
      dut.io.upstream.pready.expect(false.B)
      dut.io.upstream.pslverr.expect(true.B)
    }
  }
}
