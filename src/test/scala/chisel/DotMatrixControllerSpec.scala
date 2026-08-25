package chisel

import chisel3._
import chiseltest._
import chiseltest.simulator.VerilatorBackendAnnotation
import org.scalatest.freespec.AnyFreeSpec

private object DotMatrixControllerSpec {
  final case class ApbResponse(
    data: BigInt,
    error: Boolean,
    setupError: Boolean
  )

  final class Driver(dut: DotMatrixController) {
    def initialize(): Unit = driveIdle()

    def reset(): Unit = {
      initialize()
      dut.reset.poke(true.B)
      dut.clock.step(3)
      dut.reset.poke(false.B)
    }

    def apbWrite(address: Int, data: BigInt): ApbResponse =
      apbAccess(address, write = true, data)

    def apbRead(address: Int): ApbResponse =
      apbAccess(address, write = false, 0)

    def expectOutputs(rows: Int, columns: Int): Unit = {
      dut.io.rows.expect(rows.U)
      dut.io.columns.expect(columns.U)
    }

    private def apbAccess(address: Int, write: Boolean, data: BigInt): ApbResponse = {
      dut.io.apb.psel.poke(true.B)
      dut.io.apb.penable.poke(false.B)
      dut.io.apb.pwrite.poke(write.B)
      dut.io.apb.paddr.poke(address.U)
      dut.io.apb.pwdata.poke(data.U)
      dut.io.apb.pready.expect(true.B)
      val setupError = dut.io.apb.pslverr.peek().litToBoolean
      dut.clock.step()

      dut.io.apb.penable.poke(true.B)
      dut.io.apb.pready.expect(true.B)
      val response = ApbResponse(
        data = dut.io.apb.prdata.peek().litValue,
        error = dut.io.apb.pslverr.peek().litToBoolean,
        setupError = setupError
      )
      dut.clock.step()
      driveIdle()
      response
    }

    private def driveIdle(): Unit = {
      dut.io.apb.psel.poke(false.B)
      dut.io.apb.penable.poke(false.B)
      dut.io.apb.pwrite.poke(false.B)
      dut.io.apb.paddr.poke(0.U)
      dut.io.apb.pwdata.poke(0.U)
    }
  }
}

class DotMatrixControllerSpec extends AnyFreeSpec with ChiselScalatestTester {
  import DotMatrixControllerSpec._

  private val annotations = Seq(VerilatorBackendAnnotation)

  "DotMatrixController resets disabled and holds polarity-correct inactive outputs" in {
    test(new DotMatrixController(defaultScanDivider = 7)).withAnnotations(annotations) { dut =>
      val driver = new Driver(dut)
      driver.reset()

      driver.expectOutputs(rows = 0x00, columns = 0xff)
      assert(driver.apbRead(DotMatrixRegisters.patternLow).data == 0)
      assert(driver.apbRead(DotMatrixRegisters.patternHigh).data == 0)
      assert(driver.apbRead(DotMatrixRegisters.control).data == 0x0000ff04)
      assert(driver.apbRead(DotMatrixRegisters.scanDivider).data == 7)

      assert(!driver.apbWrite(DotMatrixRegisters.patternLow, 0xff).error)
      driver.expectOutputs(rows = 0x00, columns = 0xff)
      dut.clock.step(10)
      driver.expectOutputs(rows = 0x00, columns = 0xff)
    }
  }

  "DotMatrixController preserves both halves of the 64-bit pattern register" in {
    test(new DotMatrixController(defaultScanDivider = 7)).withAnnotations(annotations) { dut =>
      val driver = new Driver(dut)
      driver.reset()

      val low = BigInt("89abcdef", 16)
      val high = BigInt("76543210", 16)
      assert(!driver.apbWrite(DotMatrixRegisters.patternLow, low).error)
      assert(!driver.apbWrite(DotMatrixRegisters.patternHigh, high).error)
      assert(driver.apbRead(DotMatrixRegisters.patternLow).data == low)
      assert(driver.apbRead(DotMatrixRegisters.patternHigh).data == high)

      val replacementLow = BigInt("01234567", 16)
      assert(!driver.apbWrite(DotMatrixRegisters.patternLow, replacementLow).error)
      assert(driver.apbRead(DotMatrixRegisters.patternLow).data == replacementLow)
      assert(driver.apbRead(DotMatrixRegisters.patternHigh).data == high)
    }
  }

  "DotMatrixController advances rows through exactly one blanking cycle" in {
    test(new DotMatrixController(defaultScanDivider = 3)).withAnnotations(annotations) { dut =>
      val driver = new Driver(dut)
      driver.reset()

      assert(!driver.apbWrite(DotMatrixRegisters.patternLow, 0x00000201).error)
      assert(!driver.apbWrite(DotMatrixRegisters.control, 0x0000ff05).error)

      driver.expectOutputs(rows = 0x01, columns = 0xfe)
      dut.clock.step()
      driver.expectOutputs(rows = 0x01, columns = 0xfe)
      dut.clock.step()
      driver.expectOutputs(rows = 0x01, columns = 0xfe)

      dut.clock.step()
      driver.expectOutputs(rows = 0x00, columns = 0xff)

      dut.clock.step()
      driver.expectOutputs(rows = 0x02, columns = 0xfd)
    }
  }

  "DotMatrixController treats zero brightness as always blank" in {
    test(new DotMatrixController(defaultScanDivider = 1024)).withAnnotations(annotations) { dut =>
      val driver = new Driver(dut)
      driver.reset()

      assert(!driver.apbWrite(DotMatrixRegisters.patternLow, 0xa5).error)
      assert(!driver.apbWrite(DotMatrixRegisters.control, 0x00000005).error)
      for (_ <- 0 until 260) {
        driver.expectOutputs(rows = 0x00, columns = 0xff)
        dut.clock.step()
      }
    }
  }

  "DotMatrixController treats brightness 255 as continuously enabled" in {
    test(new DotMatrixController(defaultScanDivider = 1024)).withAnnotations(annotations) { dut =>
      val driver = new Driver(dut)
      driver.reset()

      assert(!driver.apbWrite(DotMatrixRegisters.patternLow, 0xa5).error)
      assert(!driver.apbWrite(DotMatrixRegisters.control, 0x0000ff05).error)
      for (_ <- 0 until 260) {
        driver.expectOutputs(rows = 0x01, columns = 0x5a)
        dut.clock.step()
      }
    }
  }

  "DotMatrixController applies intermediate PWM brightness across counter wrap" in {
    test(new DotMatrixController(defaultScanDivider = 1024)).withAnnotations(annotations) { dut =>
      val driver = new Driver(dut)
      driver.reset()

      assert(!driver.apbWrite(DotMatrixRegisters.patternLow, 0x81).error)
      assert(!driver.apbWrite(DotMatrixRegisters.control, 0x00000205).error)
      for (phase <- 0 until 260) {
        if ((phase & 0xff) < 2) {
          driver.expectOutputs(rows = 0x01, columns = 0x7e)
        } else {
          driver.expectOutputs(rows = 0x00, columns = 0xff)
        }
        dut.clock.step()
      }
    }
  }

  "DotMatrixController independently configures row and column active polarity" in {
    test(new DotMatrixController(defaultScanDivider = 32)).withAnnotations(annotations) { dut =>
      val driver = new Driver(dut)
      driver.reset()

      assert(!driver.apbWrite(DotMatrixRegisters.patternLow, 0x01).error)
      assert(!driver.apbWrite(DotMatrixRegisters.control, 0x0000ff03).error)
      driver.expectOutputs(rows = 0xfe, columns = 0x01)

      assert(!driver.apbWrite(DotMatrixRegisters.control, 0x0000ff02).error)
      driver.expectOutputs(rows = 0xff, columns = 0x00)
      dut.clock.step(4)
      driver.expectOutputs(rows = 0xff, columns = 0x00)
    }
  }

  "DotMatrixController normalizes a zero scan divider to one cycle" in {
    test(new DotMatrixController(defaultScanDivider = 9)).withAnnotations(annotations) { dut =>
      val driver = new Driver(dut)
      driver.reset()

      val write = driver.apbWrite(DotMatrixRegisters.scanDivider, 0)
      assert(!write.setupError && !write.error)
      assert(driver.apbRead(DotMatrixRegisters.scanDivider).data == 1)

      assert(!driver.apbWrite(DotMatrixRegisters.patternLow, 0x00000201).error)
      assert(!driver.apbWrite(DotMatrixRegisters.control, 0x0000ff05).error)
      driver.expectOutputs(rows = 0x01, columns = 0xfe)
      dut.clock.step()
      driver.expectOutputs(rows = 0x00, columns = 0xff)
      dut.clock.step()
      driver.expectOutputs(rows = 0x02, columns = 0xfd)
    }
  }

  "DotMatrixController rejects status writes and unknown or misaligned registers" in {
    test(new DotMatrixController(defaultScanDivider = 7)).withAnnotations(annotations) { dut =>
      val driver = new Driver(dut)
      driver.reset()

      val statusRead = driver.apbRead(DotMatrixRegisters.status)
      assert(!statusRead.setupError && !statusRead.error)

      val statusWrite = driver.apbWrite(DotMatrixRegisters.status, 0xffffffffL)
      assert(!statusWrite.setupError && statusWrite.error)

      val unknownRead = driver.apbRead(0x214)
      val misalignedWrite = driver.apbWrite(DotMatrixRegisters.control + 2, 0xffffffffL)
      assert(!unknownRead.setupError && unknownRead.error && unknownRead.data == 0)
      assert(!misalignedWrite.setupError && misalignedWrite.error)

      assert(driver.apbRead(DotMatrixRegisters.control).data == 0x0000ff04)
      driver.expectOutputs(rows = 0x00, columns = 0xff)
    }
  }
}
