package chisel

import chisel3._
import chiseltest._
import chiseltest.simulator.VerilatorBackendAnnotation
import org.scalatest.freespec.AnyFreeSpec

private class ApbToWishboneBridgeTestHarness extends Module {
  val io = IO(new Bundle {
    val apb = Flipped(new APB3IO(addrWidth = 20))
    val wishbone = new WishboneMasterPort
  })

  val bridge = Module(new ApbToWishboneBridge)
  bridge.io.clk := clock
  bridge.io.resetn := !reset.asBool

  io.apb <> bridge.io.apb
  io.wishbone <> bridge.io.wishbone
}

private object ApbToWishboneBridgeSpec {
  def initializeInputs(dut: ApbToWishboneBridgeTestHarness): Unit = {
    dut.io.apb.psel.poke(false.B)
    dut.io.apb.penable.poke(false.B)
    dut.io.apb.pwrite.poke(false.B)
    dut.io.apb.paddr.poke(0.U)
    dut.io.apb.pwdata.poke(0.U)
    dut.io.wishbone.readData.poke(0.U)
    dut.io.wishbone.acknowledge.poke(false.B)
  }

  def reset(dut: ApbToWishboneBridgeTestHarness): Unit = {
    initializeInputs(dut)
    dut.reset.poke(true.B)
    dut.clock.step()
    dut.reset.poke(false.B)
  }

  def expectDefaultOutputs(dut: ApbToWishboneBridgeTestHarness): Unit = {
    dut.io.apb.prdata.expect(0.U)
    dut.io.apb.pready.expect(false.B)
    dut.io.apb.pslverr.expect(false.B)
    dut.io.wishbone.addr.expect(0.U)
    dut.io.wishbone.writeData.expect(0.U)
    dut.io.wishbone.write.expect(false.B)
    dut.io.wishbone.strobe.expect(false.B)
    dut.io.wishbone.cycle.expect(false.B)
  }

  def driveSetup(
    dut: ApbToWishboneBridgeTestHarness,
    address: BigInt,
    write: Boolean,
    writeData: BigInt
  ): Unit = {
    dut.io.apb.psel.poke(true.B)
    dut.io.apb.penable.poke(false.B)
    dut.io.apb.paddr.poke(address.U)
    dut.io.apb.pwrite.poke(write.B)
    dut.io.apb.pwdata.poke(writeData.U)
  }

  def driveAccess(dut: ApbToWishboneBridgeTestHarness): Unit = {
    dut.io.apb.psel.poke(true.B)
    dut.io.apb.penable.poke(true.B)
  }

  def expectWishboneRequest(
    dut: ApbToWishboneBridgeTestHarness,
    address: Int,
    write: Boolean,
    writeData: Int
  ): Unit = {
    dut.io.wishbone.addr.expect(address.U)
    dut.io.wishbone.writeData.expect(writeData.U)
    dut.io.wishbone.write.expect(write.B)
    dut.io.wishbone.strobe.expect(true.B)
    dut.io.wishbone.cycle.expect(true.B)
  }

  def expectWaiting(dut: ApbToWishboneBridgeTestHarness): Unit = {
    dut.io.apb.pready.expect(false.B)
    dut.io.apb.pslverr.expect(false.B)
  }
}

class ApbToWishboneBridgeSpec extends AnyFreeSpec with ChiselScalatestTester {
  import ApbToWishboneBridgeSpec._

  private val annotations = Seq(VerilatorBackendAnnotation)

  "ApbToWishboneBridge should asynchronously reset every registered output" in {
    test(new ApbToWishboneBridgeTestHarness).withAnnotations(annotations) { dut =>
      reset(dut)
      expectDefaultOutputs(dut)

      driveSetup(dut, address = 0x1c, write = true, writeData = 0x123456abL)
      dut.clock.step()
      expectWishboneRequest(dut, address = 7, write = true, writeData = 0xab)

      driveAccess(dut)
      expectWaiting(dut)

      // No clock edge occurs after reset assertion, so these checks exercise the asynchronous path.
      dut.reset.poke(true.B)
      expectDefaultOutputs(dut)
    }
  }

  "ApbToWishboneBridge should map eight four-byte-spaced writes and keep only pwdata(7,0)" in {
    test(new ApbToWishboneBridgeTestHarness).withAnnotations(annotations) { dut =>
      reset(dut)

      for (register <- 0 until 8) {
        val address = register * 4
        val lowByte = (0x31 + register * 0x17) & 0xff
        val writeData = BigInt("a5c30000", 16) | BigInt(lowByte)

        driveSetup(dut, address = address, write = true, writeData = writeData)
        expectWaiting(dut)
        dut.clock.step()

        expectWishboneRequest(dut, address = register, write = true, writeData = lowByte)
        dut.io.apb.pready.expect(false.B)
        dut.io.apb.pslverr.expect(false.B)

        driveAccess(dut)
        dut.io.wishbone.acknowledge.poke(true.B)
        dut.io.apb.pready.expect(true.B)
        dut.io.apb.pslverr.expect(false.B)
        expectWishboneRequest(dut, address = register, write = true, writeData = lowByte)
        dut.clock.step()

        dut.io.wishbone.acknowledge.poke(false.B)
        dut.io.wishbone.strobe.expect(false.B)
        dut.io.wishbone.cycle.expect(false.B)
      }

      dut.io.apb.psel.poke(false.B)
      dut.io.apb.penable.poke(false.B)
    }
  }

  "ApbToWishboneBridge should hold a latched request throughout delayed Wishbone acknowledge" in {
    test(new ApbToWishboneBridgeTestHarness).withAnnotations(annotations) { dut =>
      reset(dut)

      driveSetup(dut, address = 0x14, write = true, writeData = 0xdeadbe5aL)
      dut.clock.step()
      expectWishboneRequest(dut, address = 5, write = true, writeData = 0x5a)

      driveAccess(dut)
      dut.io.apb.paddr.poke(0x04.U)
      dut.io.apb.pwrite.poke(false.B)
      dut.io.apb.pwdata.poke(0x123456c3L.U)

      for (_ <- 0 until 4) {
        expectWaiting(dut)
        expectWishboneRequest(dut, address = 5, write = true, writeData = 0x5a)
        dut.clock.step()
      }

      dut.io.wishbone.acknowledge.poke(true.B)
      dut.io.apb.pready.expect(true.B)
      dut.io.apb.pslverr.expect(false.B)
      expectWishboneRequest(dut, address = 5, write = true, writeData = 0x5a)
      dut.clock.step()

      dut.io.wishbone.strobe.expect(false.B)
      dut.io.wishbone.cycle.expect(false.B)
      dut.io.apb.pready.expect(false.B)
    }
  }

  "ApbToWishboneBridge should zero-extend eight-bit Wishbone read data" in {
    test(new ApbToWishboneBridgeTestHarness).withAnnotations(annotations) { dut =>
      reset(dut)

      driveSetup(dut, address = 0x08, write = false, writeData = 0xffffffffL)
      dut.clock.step()
      expectWishboneRequest(dut, address = 2, write = false, writeData = 0xff)

      driveAccess(dut)
      dut.io.wishbone.readData.poke(0xd7.U)
      dut.io.wishbone.acknowledge.poke(true.B)

      dut.io.apb.pready.expect(true.B)
      dut.io.apb.pslverr.expect(false.B)
      dut.io.apb.prdata.expect(0x000000d7L.U)
      expectWishboneRequest(dut, address = 2, write = false, writeData = 0xff)
      dut.clock.step()
    }
  }

  "ApbToWishboneBridge should immediately reject misaligned and out-of-window offsets" in {
    test(new ApbToWishboneBridgeTestHarness).withAnnotations(annotations) { dut =>
      reset(dut)

      val invalidAddresses = Seq(0x01, 0x02, 0x03, 0x20, 0x24, 0x3c)
      for (address <- invalidAddresses) {
        driveSetup(dut, address = address, write = true, writeData = 0x87654321L)
        dut.clock.step()

        dut.io.wishbone.strobe.expect(false.B)
        dut.io.wishbone.cycle.expect(false.B)
        driveAccess(dut)

        dut.io.apb.pready.expect(true.B)
        dut.io.apb.pslverr.expect(true.B)
        dut.io.apb.prdata.expect(0.U)
        dut.io.wishbone.strobe.expect(false.B)
        dut.io.wishbone.cycle.expect(false.B)
        dut.clock.step()

        dut.io.apb.pready.expect(false.B)
        dut.io.apb.pslverr.expect(false.B)
      }
    }
  }

  "ApbToWishboneBridge should accept two consecutive APB transfers without an idle cycle" in {
    test(new ApbToWishboneBridgeTestHarness).withAnnotations(annotations) { dut =>
      reset(dut)

      driveSetup(dut, address = 0x0c, write = true, writeData = 0x1357247eL)
      dut.clock.step()
      expectWishboneRequest(dut, address = 3, write = true, writeData = 0x7e)

      driveAccess(dut)
      dut.io.wishbone.acknowledge.poke(true.B)
      dut.io.apb.pready.expect(true.B)
      dut.clock.step()

      // PSEL remains asserted while PENABLE drops for the next setup phase.
      dut.io.wishbone.acknowledge.poke(false.B)
      driveSetup(dut, address = 0x04, write = false, writeData = 0)
      dut.io.wishbone.readData.poke(0x9c.U)
      dut.io.wishbone.strobe.expect(false.B)
      dut.io.wishbone.cycle.expect(false.B)
      dut.io.apb.pready.expect(false.B)
      dut.clock.step()

      expectWishboneRequest(dut, address = 1, write = false, writeData = 0x00)
      driveAccess(dut)
      dut.io.wishbone.acknowledge.poke(true.B)
      dut.io.apb.pready.expect(true.B)
      dut.io.apb.pslverr.expect(false.B)
      dut.io.apb.prdata.expect(0x0000009cL.U)
      dut.clock.step()

      dut.io.wishbone.strobe.expect(false.B)
      dut.io.wishbone.cycle.expect(false.B)
    }
  }
}
