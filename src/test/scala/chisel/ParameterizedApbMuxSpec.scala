package chisel

import chisel3._
import chiseltest._
import chiseltest.simulator.VerilatorBackendAnnotation
import chisel.axiInterconnect.nand.ApbMuxDmaPort
import chisel.axiSlaveMux.apb._
import chisel.axiSlaveMux.apb.display._
import chisel.common.bus.APB3IO
import org.scalatest.freespec.AnyFreeSpec

private class ParameterizedApbMuxSimIO extends Bundle {
  val cpu = Flipped(new Axi2ApbCpuPort)
  val dma = new ApbMuxDmaPort
  val apb0 = new LegacyApb8Port
  val apb1 = new APB3IO(addrWidth = 20)
  val apb2 = new APB3IO(addrWidth = 20)
  val apb3 = new APB3IO(addrWidth = 20)
  val apb4 = new APB3IO(addrWidth = 20)
  val apb5 = new APB3IO(addrWidth = 20)
  val apb6 = new LegacyApb32Port
  val apb7 = new APB3IO(addrWidth = 13)
}

private class ParameterizedApbMux_sim(config: ApbMux2Config) extends Module {
  val io = IO(new ParameterizedApbMuxSimIO)

  private val mux = Module(new ApbMux2(config))
  mux.io.clk := clock
  mux.io.resetn := !reset.asBool
  io.cpu <> mux.io.cpu
  io.dma <> mux.io.dma
  io.apb0 <> mux.io.apb0
  io.apb1 <> mux.io.apb1
  io.apb2 <> mux.io.apb2
  io.apb3 <> mux.io.apb3
  io.apb4 <> mux.io.apb4
  io.apb5 <> mux.io.apb5
  io.apb6 <> mux.io.apb6
  io.apb7 <> mux.io.apb7
}

private class ParameterizedDisplayApbMuxSimIO extends Bundle {
  val upstream = Flipped(new APB3IO(addrWidth = 20))
  val vga = new APB3IO(addrWidth = 20)
  val gpu = new APB3IO(addrWidth = 20)
  val tensorCore = new APB3IO(addrWidth = 20)
  val dotMatrix = new APB3IO(addrWidth = 20)
}

private class ParameterizedDisplayApbMux_sim(config: DisplayApbMuxConfig) extends Module {
  val io = IO(new ParameterizedDisplayApbMuxSimIO)

  private val mux = Module(new DisplayApbMux(config))
  io.upstream <> mux.io.upstream
  io.vga <> mux.io.vga
  io.gpu <> mux.io.gpu
  io.tensorCore <> mux.io.tensorCore
  io.dotMatrix <> mux.io.dotMatrix
}

private object ParameterizedApbMuxSpec {
  final case class DisabledPage(name: String, page: Int, config: ApbMux2Config)
  final case class EnabledApb3Page(name: String, page: Int, output: Int)
  final case class DisabledDisplayWindow(
    name: String,
    address: Int,
    config: DisplayApbMuxConfig
  )

  val disabledPages: Seq[DisabledPage] = Seq(
    DisabledPage("USB", ApbMux2AddressMap.usb, ApbMux2Config(usb = false)),
    DisabledPage("display", ApbMux2AddressMap.display, ApbMux2Config(display = false)),
    DisabledPage("LCD touch", ApbMux2AddressMap.lcdTouch, ApbMux2Config(lcdTouch = false)),
    DisabledPage("LCD", ApbMux2AddressMap.lcd, ApbMux2Config(lcd = false)),
    DisabledPage("NAND", ApbMux2AddressMap.nand, ApbMux2Config(nand = false)),
    DisabledPage("camera", ApbMux2AddressMap.camera, ApbMux2Config(camera = false))
  )

  val enabledApb3Pages: Seq[EnabledApb3Page] = Seq(
    EnabledApb3Page("USB", ApbMux2AddressMap.usb, output = 1),
    EnabledApb3Page("display", ApbMux2AddressMap.display, output = 2),
    EnabledApb3Page("LCD touch", ApbMux2AddressMap.lcdTouch, output = 3),
    EnabledApb3Page("LCD", ApbMux2AddressMap.lcd, output = 4),
    EnabledApb3Page("cascaded interrupt", ApbMux2AddressMap.cascadedInterrupt, output = 5),
    EnabledApb3Page("camera", ApbMux2AddressMap.camera, output = 6)
  )

  val disabledDisplayWindows: Seq[DisabledDisplayWindow] = Seq(
    DisabledDisplayWindow("VGA", 0x000, DisplayApbMuxConfig(vga = false)),
    DisabledDisplayWindow("GPU", 0x100, DisplayApbMuxConfig(gpu = false)),
    DisabledDisplayWindow(
      "TensorCore",
      0x200,
      DisplayApbMuxConfig(tensorCore = false)
    ),
    DisabledDisplayWindow(
      "dot matrix",
      0x300,
      DisplayApbMuxConfig(dotMatrix = false)
    )
  )

  def apb3Ports(dut: ParameterizedApbMux_sim): Seq[APB3IO] =
    Seq(dut.io.apb1, dut.io.apb2, dut.io.apb3, dut.io.apb4, dut.io.apb5,
      dut.io.apb7)

  def displayPorts(dut: ParameterizedDisplayApbMux_sim): Seq[APB3IO] =
    Seq(dut.io.vga, dut.io.gpu, dut.io.tensorCore, dut.io.dotMatrix)

  def initializeApbMux(dut: ParameterizedApbMux_sim): Unit = {
    dut.io.cpu.valid.poke(false.B)
    dut.io.cpu.high24Write.poke(0.U)
    dut.io.cpu.psel.poke(false.B)
    dut.io.cpu.penable.poke(false.B)
    dut.io.cpu.write.poke(false.B)
    dut.io.cpu.addr.poke(0.U)
    dut.io.cpu.writeData.poke(0.U)

    dut.io.dma.write.poke(false.B)
    dut.io.dma.psel.poke(false.B)
    dut.io.dma.penable.poke(false.B)
    dut.io.dma.addr.poke(0.U)
    dut.io.dma.writeData.poke(0.U)
    dut.io.dma.valid.poke(false.B)

    dut.io.apb0.acknowledge.poke(false.B)
    dut.io.apb0.readData.poke(0.U)
    dut.io.apb6.acknowledge.poke(false.B)
    dut.io.apb6.readData.poke(0.U)
    for (port <- apb3Ports(dut)) {
      port.prdata.poke(0.U)
      port.pready.poke(false.B)
      port.pslverr.poke(false.B)
    }
  }

  def resetApbMux(dut: ParameterizedApbMux_sim): Unit = {
    initializeApbMux(dut)
    dut.reset.poke(true.B)
    dut.clock.step(2)
    dut.reset.poke(false.B)
  }

  def driveCpuSetup(dut: ParameterizedApbMux_sim, page: Int): Unit = {
    val address = (page << ApbMux2AddressMap.windowOffsetWidth) | 0x15c
    dut.io.cpu.valid.poke(true.B)
    dut.io.cpu.psel.poke(true.B)
    dut.io.cpu.penable.poke(false.B)
    dut.io.cpu.write.poke(false.B)
    dut.io.cpu.addr.poke(address.U)
    dut.io.cpu.writeData.poke(0.U)
    dut.io.cpu.high24Write.poke(0.U)
  }

  def driveCpuAccess(dut: ParameterizedApbMux_sim): Unit =
    dut.io.cpu.penable.poke(true.B)

  def driveCpuIdle(dut: ParameterizedApbMux_sim): Unit = {
    dut.io.cpu.valid.poke(false.B)
    dut.io.cpu.psel.poke(false.B)
    dut.io.cpu.penable.poke(false.B)
  }

  def expectNoApbMuxSelection(dut: ParameterizedApbMux_sim): Unit = {
    dut.io.apb0.request.expect(false.B)
    dut.io.apb0.psel.expect(false.B)
    dut.io.apb0.penable.expect(false.B)
    dut.io.apb6.request.expect(false.B)
    dut.io.apb6.psel.expect(false.B)
    dut.io.apb6.penable.expect(false.B)
    for (port <- apb3Ports(dut)) {
      port.psel.expect(false.B)
      port.penable.expect(false.B)
    }
  }

  def initializeDisplayMux(dut: ParameterizedDisplayApbMux_sim): Unit = {
    dut.io.upstream.psel.poke(false.B)
    dut.io.upstream.penable.poke(false.B)
    dut.io.upstream.pwrite.poke(false.B)
    dut.io.upstream.paddr.poke(0.U)
    dut.io.upstream.pwdata.poke(0.U)
    for (port <- displayPorts(dut)) {
      port.prdata.poke("hffffffff".U)
      port.pready.poke(false.B)
      port.pslverr.poke(false.B)
    }
  }

  def driveDisplaySetup(
    dut: ParameterizedDisplayApbMux_sim,
    address: Int
  ): Unit = {
    dut.io.upstream.psel.poke(true.B)
    dut.io.upstream.penable.poke(false.B)
    dut.io.upstream.pwrite.poke(false.B)
    dut.io.upstream.paddr.poke(address.U)
    dut.io.upstream.pwdata.poke(0.U)
  }

  def expectNoDisplaySelection(dut: ParameterizedDisplayApbMux_sim): Unit =
    displayPorts(dut).foreach(_.psel.expect(false.B))
}

class ParameterizedApbMuxSpec extends AnyFreeSpec with ChiselScalatestTester {
  import ParameterizedApbMuxSpec._

  private val annotations = Seq(VerilatorBackendAnnotation)

  for (route <- disabledPages) {
    s"ApbMux2 should reject the disabled ${route.name} page during the CPU access phase" in {
      test(new ParameterizedApbMux_sim(route.config))
        .withAnnotations(annotations) { dut =>
          resetApbMux(dut)

          driveCpuSetup(dut, route.page)
          expectNoApbMuxSelection(dut)
          dut.io.cpu.grant.expect(true.B)
          dut.io.cpu.ready.expect(true.B)
          dut.io.cpu.error.expect(false.B)
          dut.clock.step()

          driveCpuAccess(dut)
          expectNoApbMuxSelection(dut)
          dut.io.cpu.grant.expect(true.B)
          dut.io.cpu.ready.expect(true.B)
          dut.io.cpu.error.expect(true.B)
          dut.clock.step()

          driveCpuIdle(dut)
          dut.io.cpu.error.expect(false.B)
        }
    }
  }

  "ApbMux2 should propagate the selected enabled APB3 slave error to the CPU" in {
    test(new ParameterizedApbMux_sim(ApbMux2Config()))
      .withAnnotations(annotations) { dut =>
        resetApbMux(dut)

        for (route <- enabledApb3Pages) {
          val selectedPort = apb3Ports(dut)(route.output - 1)
          driveCpuSetup(dut, route.page)
          selectedPort.psel.expect(true.B)
          selectedPort.penable.expect(false.B)
          dut.io.cpu.ready.expect(false.B)
          dut.io.cpu.error.expect(false.B)
          dut.clock.step()

          driveCpuAccess(dut)
          selectedPort.psel.expect(true.B)
          selectedPort.penable.expect(true.B)
          dut.io.cpu.ready.expect(false.B)
          dut.io.cpu.error.expect(false.B)

          selectedPort.pready.poke(true.B)
          selectedPort.pslverr.poke(true.B)
          dut.io.cpu.ready.expect(true.B)
          dut.io.cpu.error.expect(true.B)
          dut.clock.step()

          driveCpuIdle(dut)
          selectedPort.pready.poke(false.B)
          selectedPort.pslverr.poke(false.B)
          dut.io.cpu.error.expect(false.B)
          dut.clock.step()
        }
      }
  }

  "ApbMux2 should immediately reject unknown pages during the CPU access phase" in {
    test(new ParameterizedApbMux_sim(ApbMux2Config()))
      .withAnnotations(annotations) { dut =>
        resetApbMux(dut)
        dut.io.apb0.acknowledge.poke(true.B)
        dut.io.apb6.acknowledge.poke(true.B)
        for (port <- apb3Ports(dut)) {
          port.pready.poke(true.B)
          port.pslverr.poke(true.B)
        }

        for (page <- Seq(0x08, 0x40, 0x7f)) {
          driveCpuSetup(dut, page)
          expectNoApbMuxSelection(dut)
          dut.io.cpu.ready.expect(true.B)
          dut.io.cpu.error.expect(false.B)
          dut.clock.step()

          driveCpuAccess(dut)
          expectNoApbMuxSelection(dut)
          dut.io.cpu.ready.expect(true.B)
          dut.io.cpu.error.expect(true.B)
          dut.clock.step()

          driveCpuIdle(dut)
          dut.clock.step()
        }
      }
  }

  for (route <- disabledDisplayWindows) {
    s"DisplayApbMux should reject the disabled ${route.name} subwindow only in the APB access phase" in {
      test(new ParameterizedDisplayApbMux_sim(route.config))
        .withAnnotations(annotations) { dut =>
          initializeDisplayMux(dut)

          driveDisplaySetup(dut, route.address)
          expectNoDisplaySelection(dut)
          dut.io.upstream.prdata.expect(0.U)
          dut.io.upstream.pready.expect(true.B)
          dut.io.upstream.pslverr.expect(false.B)
          dut.clock.step()

          dut.io.upstream.penable.poke(true.B)
          expectNoDisplaySelection(dut)
          dut.io.upstream.prdata.expect(0.U)
          dut.io.upstream.pready.expect(true.B)
          dut.io.upstream.pslverr.expect(true.B)
          dut.clock.step()

          dut.io.upstream.psel.poke(false.B)
          dut.io.upstream.penable.poke(false.B)
          expectNoDisplaySelection(dut)
          dut.io.upstream.pslverr.expect(false.B)
        }
    }
  }
}
