package chisel

import chisel3._
import chiseltest._
import chiseltest.simulator.VerilatorBackendAnnotation
import chisel.axiInterconnect.nand.ApbMuxDmaPort
import chisel.axiSlaveMux.apb._
import chisel.common.bus.APB3IO
import org.scalatest.freespec.AnyFreeSpec

private class ApbMux2SimIO extends Bundle {
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

private class ApbMux2_sim extends Module {
  val io = IO(new ApbMux2SimIO)

  private val mux = Module(new ApbMux2)
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

private object ApbMux2Spec {
  final case class PageRoute(page: Int, output: Int, wordTransfer: Boolean)

  val pageRoutes: Seq[PageRoute] = Seq(
    PageRoute(0x00, 0, wordTransfer = false),
    PageRoute(0x01, 1, wordTransfer = true),
    PageRoute(0x02, 2, wordTransfer = true),
    PageRoute(0x03, 3, wordTransfer = true),
    PageRoute(0x04, 4, wordTransfer = true),
    PageRoute(0x05, 5, wordTransfer = true),
    PageRoute(0x06, 6, wordTransfer = true),
    PageRoute(0x07, 7, wordTransfer = true)
  )

  def initializeInputs(dut: ApbMux2_sim): Unit = {
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

    for (port <- Seq(dut.io.apb1, dut.io.apb2, dut.io.apb3, dut.io.apb4,
      dut.io.apb5, dut.io.apb7)) {
      port.prdata.poke(0.U)
      port.pready.poke(false.B)
      port.pslverr.poke(false.B)
    }
  }

  def reset(dut: ApbMux2_sim): Unit = {
    initializeInputs(dut)
    dut.reset.poke(true.B)
    dut.clock.step()
    dut.reset.poke(false.B)
  }

  def driveCpu(
    dut: ApbMux2_sim,
    address: BigInt,
    write: Boolean = true,
    lowByte: Int = 0x5a,
    high24: BigInt = BigInt("a1b2c3", 16)
  ): Unit = {
    dut.io.cpu.valid.poke(true.B)
    dut.io.cpu.psel.poke(true.B)
    dut.io.cpu.penable.poke(true.B)
    dut.io.cpu.write.poke(write.B)
    dut.io.cpu.addr.poke(address.U)
    dut.io.cpu.writeData.poke(lowByte.U)
    dut.io.cpu.high24Write.poke(high24.U)
  }

  def driveDma(
    dut: ApbMux2_sim,
    address: BigInt,
    write: Boolean = true,
    writeData: BigInt = BigInt("76543210", 16)
  ): Unit = {
    dut.io.dma.valid.poke(true.B)
    dut.io.dma.psel.poke(true.B)
    dut.io.dma.penable.poke(true.B)
    dut.io.dma.write.poke(write.B)
    dut.io.dma.addr.poke(address.U)
    dut.io.dma.writeData.poke(writeData.U)
  }

  def expectNoSelection(dut: ApbMux2_sim): Unit = {
    dut.io.apb0.request.expect(false.B)
    dut.io.apb0.psel.expect(false.B)
    dut.io.apb0.penable.expect(false.B)
    dut.io.apb6.request.expect(false.B)
    dut.io.apb6.psel.expect(false.B)
    dut.io.apb6.penable.expect(false.B)
    for (port <- Seq(dut.io.apb1, dut.io.apb2, dut.io.apb3, dut.io.apb4,
      dut.io.apb5, dut.io.apb7)) {
      port.psel.expect(false.B)
      port.penable.expect(false.B)
    }
  }

  def expectSelected(dut: ApbMux2_sim, output: Int, localAddress: Int): Unit = {
    dut.io.apb0.request.expect((output == 0).B)
    dut.io.apb0.psel.expect((output == 0).B)
    dut.io.apb0.penable.expect((output == 0).B)
    dut.io.apb6.request.expect((output == 6).B)
    dut.io.apb6.psel.expect((output == 6).B)
    dut.io.apb6.penable.expect((output == 6).B)

    val apb3Ports = Seq(
      1 -> dut.io.apb1,
      2 -> dut.io.apb2,
      3 -> dut.io.apb3,
      4 -> dut.io.apb4,
      5 -> dut.io.apb5,
      7 -> dut.io.apb7
    )
    for ((portNumber, port) <- apb3Ports) {
      val selected = output == portNumber
      port.psel.expect(selected.B)
      port.penable.expect(selected.B)
    }

    output match {
      case 0 => dut.io.apb0.addr.expect(localAddress.U)
      case 1 => dut.io.apb1.paddr.expect(localAddress.U)
      case 2 => dut.io.apb2.paddr.expect(localAddress.U)
      case 3 => dut.io.apb3.paddr.expect(localAddress.U)
      case 4 => dut.io.apb4.paddr.expect(localAddress.U)
      case 5 => dut.io.apb5.paddr.expect(localAddress.U)
      case 6 => dut.io.apb6.addr.expect(localAddress.U)
      case 7 => dut.io.apb7.paddr.expect(localAddress.U)
    }
  }
}

class ApbMux2Spec extends AnyFreeSpec with ChiselScalatestTester {
  import ApbMux2Spec._

  private val annotations = Seq(VerilatorBackendAnnotation)

  "ApbMux2 should decode all eight contiguous 8 KiB pages and strip the page bits" in {
    test(new ApbMux2_sim).withAnnotations(annotations) { dut =>
      reset(dut)
      assert(ApbMux2AddressMap.windowOffsetWidth == 13)
      assert(Seq(
        ApbMux2AddressMap.uart,
        ApbMux2AddressMap.usb,
        ApbMux2AddressMap.display,
        ApbMux2AddressMap.lcdTouch,
        ApbMux2AddressMap.lcd,
        ApbMux2AddressMap.cascadedInterrupt,
        ApbMux2AddressMap.nand,
        ApbMux2AddressMap.camera
      ) == (0x00 to 0x07))

      for (route <- pageRoutes; offset <- Seq(0x0000, 0x0123, 0x1fff)) {
        val address = (route.page << 13) | offset
        driveCpu(dut, address)

        dut.io.cpu.grant.expect(true.B)
        dut.io.dma.grant.expect(false.B)
        dut.io.cpu.wordTrans.expect(route.wordTransfer.B)
        expectSelected(dut, route.output, offset)
      }
    }
  }

  "ApbMux2 should return ready and read data from the selected page only" in {
    test(new ApbMux2_sim).withAnnotations(annotations) { dut =>
      reset(dut)

      val responseData = Seq(
        BigInt("000000a0", 16),
        BigInt("111111a1", 16),
        BigInt("222222a2", 16),
        BigInt("333333a3", 16),
        BigInt("444444a4", 16),
        BigInt("555555a5", 16),
        BigInt("666666a6", 16),
        BigInt("777777a7", 16)
      )

      for ((route, data) <- pageRoutes.zip(responseData)) {
        dut.io.apb0.acknowledge.poke(false.B)
        dut.io.apb6.acknowledge.poke(false.B)
        for (port <- Seq(dut.io.apb1, dut.io.apb2, dut.io.apb3, dut.io.apb4,
          dut.io.apb5, dut.io.apb7)) {
          port.pready.poke(false.B)
        }

        route.output match {
          case 0 =>
            dut.io.apb0.acknowledge.poke(true.B)
            dut.io.apb0.readData.poke(data.U)
          case 1 =>
            dut.io.apb1.pready.poke(true.B)
            dut.io.apb1.prdata.poke(data.U)
          case 2 =>
            dut.io.apb2.pready.poke(true.B)
            dut.io.apb2.prdata.poke(data.U)
          case 3 =>
            dut.io.apb3.pready.poke(true.B)
            dut.io.apb3.prdata.poke(data.U)
          case 4 =>
            dut.io.apb4.pready.poke(true.B)
            dut.io.apb4.prdata.poke(data.U)
          case 5 =>
            dut.io.apb5.pready.poke(true.B)
            dut.io.apb5.prdata.poke(data.U)
          case 6 =>
            dut.io.apb6.acknowledge.poke(true.B)
            dut.io.apb6.readData.poke(data.U)
          case 7 =>
            dut.io.apb7.pready.poke(true.B)
            dut.io.apb7.prdata.poke(data.U)
        }

        driveCpu(dut, address = (route.page << 13) | 0x168, write = false)
        dut.io.cpu.ready.expect(true.B)
        dut.io.cpu.readData.expect((data & 0xff).U)
        val expectedHigh24 = if (route.wordTransfer) data >> 8 else BigInt(0)
        dut.io.cpu.high24Read.expect(expectedHigh24.U)
      }
    }
  }

  "ApbMux2 should retain the current owner while both CPU and DMA remain valid" in {
    test(new ApbMux2_sim).withAnnotations(annotations) { dut =>
      reset(dut)

      val cpuAddress = (ApbMux2AddressMap.display << 13) | 0x144
      val dmaAddress = (ApbMux2AddressMap.nand << 13) | 0x0c0
      driveCpu(dut, cpuAddress, write = false)
      driveDma(dut, dmaAddress, write = false)
      dut.io.apb2.pready.poke(true.B)
      dut.io.apb2.prdata.poke("h89abcdef".U)
      dut.io.apb6.acknowledge.poke(true.B)
      dut.io.apb6.readData.poke("h76543210".U)

      for (_ <- 0 until 3) {
        dut.clock.step()
        dut.io.cpu.grant.expect(true.B)
        dut.io.dma.grant.expect(false.B)
        dut.io.cpu.ready.expect(true.B)
        dut.io.dma.ready.expect(false.B)
        expectSelected(dut, output = 2, localAddress = 0x144)
      }

      dut.io.cpu.valid.poke(false.B)
      dut.clock.step()
      dut.io.cpu.grant.expect(false.B)
      dut.io.dma.grant.expect(true.B)
      dut.io.cpu.ready.expect(false.B)
      dut.io.dma.ready.expect(true.B)
      dut.io.dma.readData.expect("h76543210".U)
      expectSelected(dut, output = 6, localAddress = 0x0c0)

      dut.io.cpu.valid.poke(true.B)
      for (_ <- 0 until 3) {
        dut.clock.step()
        dut.io.cpu.grant.expect(false.B)
        dut.io.dma.grant.expect(true.B)
        dut.io.cpu.ready.expect(false.B)
        dut.io.dma.ready.expect(true.B)
        expectSelected(dut, output = 6, localAddress = 0x0c0)
      }

      dut.io.dma.valid.poke(false.B)
      dut.clock.step()
      dut.io.cpu.grant.expect(true.B)
      dut.io.dma.grant.expect(false.B)
      dut.io.cpu.ready.expect(true.B)
      dut.io.dma.ready.expect(false.B)
      expectSelected(dut, output = 2, localAddress = 0x144)
    }
  }

  "ApbMux2 should preserve the CPU byte split for UART and assemble 32-bit transfers" in {
    test(new ApbMux2_sim).withAnnotations(annotations) { dut =>
      reset(dut)

      driveCpu(dut, address = 0x1e0, write = true, lowByte = 0xd4,
        high24 = BigInt("a1b2c3", 16))
      dut.io.apb0.write.expect(true.B)
      dut.io.apb0.writeData.expect(0xd4.U)

      driveCpu(dut, address = 0x1e0, write = false)
      dut.io.apb0.acknowledge.poke(true.B)
      dut.io.apb0.readData.poke(0xe7.U)

      dut.io.apb0.write.expect(false.B)
      dut.io.cpu.ready.expect(true.B)
      dut.io.cpu.readData.expect(0xe7.U)
      dut.io.cpu.high24Read.expect(0.U)
      dut.io.cpu.wordTrans.expect(false.B)

      val usbAddress = (ApbMux2AddressMap.usb << 13) | 0x1a4
      driveCpu(dut, address = usbAddress, write = true, lowByte = 0xd4,
        high24 = BigInt("a1b2c3", 16))
      dut.io.apb1.pwrite.expect(true.B)
      dut.io.apb1.pwdata.expect("ha1b2c3d4".U)
      dut.io.apb1.paddr.expect(0x1a4.U)

      driveCpu(dut, address = usbAddress, write = false)
      dut.io.apb1.pready.poke(true.B)
      dut.io.apb1.prdata.poke("h89abcdef".U)

      dut.io.apb1.pwrite.expect(false.B)
      dut.io.cpu.ready.expect(true.B)
      dut.io.cpu.readData.expect(0xef.U)
      dut.io.cpu.high24Read.expect("h89abcd".U)
      dut.io.cpu.wordTrans.expect(true.B)
    }
  }

  "ApbMux2 should immediately reject unknown 7-bit pages without selecting a slave" in {
    test(new ApbMux2_sim).withAnnotations(annotations) { dut =>
      reset(dut)

      dut.io.apb0.acknowledge.poke(true.B)
      dut.io.apb6.acknowledge.poke(true.B)
      for (port <- Seq(dut.io.apb1, dut.io.apb2, dut.io.apb3, dut.io.apb4,
        dut.io.apb5, dut.io.apb7)) {
        port.pready.poke(true.B)
      }

      for (page <- Seq(0x08, 0x40, 0x7f)) {
        driveCpu(dut, address = (page << 13) | 0x321, write = false)
        expectNoSelection(dut)
        dut.io.cpu.grant.expect(true.B)
        dut.io.cpu.ready.expect(true.B)
        dut.io.cpu.error.expect(true.B)
        dut.io.cpu.readData.expect(0.U)
        dut.io.cpu.high24Read.expect(0.U)
        dut.io.cpu.wordTrans.expect(false.B)
        dut.io.dma.ready.expect(false.B)
        dut.io.dma.readData.expect(0.U)
      }
    }
  }
}
