package chisel

import chisel3._
import chiseltest._
import chiseltest.simulator.VerilatorBackendAnnotation
import org.scalatest.freespec.AnyFreeSpec

import scala.collection.mutable.ArrayBuffer

private object LcdCtrlSpec {
  final case class LcdWrite(data: BigInt, registerSelect: Boolean)
  final case class ApbResponse(
    data: BigInt,
    error: Boolean,
    waitCycles: Int,
    setupReady: Boolean,
    setupError: Boolean
  )

  final class Driver(dut: LcdApbDomain) {
    val lcdWrites: ArrayBuffer[LcdWrite] = ArrayBuffer.empty
    private var previousWriten = true

    def initialize(): Unit = {
      dut.io.apb.psel.poke(false.B)
      dut.io.apb.penable.poke(false.B)
      dut.io.apb.pwrite.poke(false.B)
      dut.io.apb.paddr.poke(0.U)
      dut.io.apb.pwdata.poke(0.U)
      dut.io.dmaFrame.valid.poke(false.B)
      dut.io.dmaFrame.data.poke(0.U)
      dut.io.dmaFrame.last.poke(false.B)
      dut.io.dmaStatus.errorToggle.poke(false.B)
      dut.io.buttonPress.poke(0.U)
    }

    def reset(): Unit = {
      initialize()
      dut.reset.poke(true.B)
      step(3)
      dut.reset.poke(false.B)
      step()
      previousWriten = dut.io.lcd.writen.peek().litToBoolean
      lcdWrites.clear()
    }

    def step(cycles: Int = 1): Unit = {
      for (_ <- 0 until cycles) {
        dut.clock.step()
        observeLcdWrite()
      }
    }

    def apbWrite(address: Int, data: BigInt): ApbResponse =
      apbAccess(address, write = true, data)

    def apbRead(address: Int): ApbResponse =
      apbAccess(address, write = false, 0)

    def pulseButton(index: Int): Unit = {
      require(index >= 0 && index < 4)
      dut.io.buttonPress.poke((1 << index).U)
      step()
      dut.io.buttonPress.poke(0.U)
    }

    def startLegacyDma(base: BigInt, halfWordCount: BigInt, panelResetn: Boolean = false): Unit = {
      assert(!apbWrite(LcdCtrlRegisters.dmaBase, base).error)
      assert(!apbWrite(LcdCtrlRegisters.dmaLength, halfWordCount).error)
      assert(!apbWrite(LcdCtrlRegisters.control, if (panelResetn) 3 else 2).error)
    }

    def programTwoDimensionalDma(base: BigInt, width: BigInt, height: BigInt, sourceStride: BigInt): Unit = {
      assert(!apbWrite(LcdCtrlRegisters.dmaBase, base).error)
      assert(!apbWrite(LcdCtrlRegisters.dmaWidth, width).error)
      assert(!apbWrite(LcdCtrlRegisters.dmaHeight, height).error)
      assert(!apbWrite(LcdCtrlRegisters.dmaSourceStride, sourceStride).error)
    }

    def offerFrame(data: BigInt, last: Boolean): Int = {
      dut.io.dmaFrame.valid.poke(true.B)
      dut.io.dmaFrame.data.poke(data.U)
      dut.io.dmaFrame.last.poke(last.B)

      var waitCycles = 0
      while (!dut.io.dmaFrame.ready.peek().litToBoolean && waitCycles < 512) {
        step()
        waitCycles += 1
      }
      assert(dut.io.dmaFrame.ready.peek().litToBoolean, "DMA frame input remained backpressured")
      step()

      dut.io.dmaFrame.valid.poke(false.B)
      dut.io.dmaFrame.data.poke(0.U)
      dut.io.dmaFrame.last.poke(false.B)
      waitCycles
    }

    def status: BigInt = {
      dut.io.apb.psel.poke(false.B)
      dut.io.apb.penable.poke(false.B)
      dut.io.apb.pwrite.poke(false.B)
      dut.io.apb.paddr.poke(LcdCtrlRegisters.status.U)
      dut.io.apb.prdata.peek().litValue
    }

    def powerControl: BigInt = {
      dut.io.apb.psel.poke(false.B)
      dut.io.apb.penable.poke(false.B)
      dut.io.apb.pwrite.poke(false.B)
      dut.io.apb.paddr.poke(LcdCtrlRegisters.powerControl.U)
      dut.io.apb.prdata.peek().litValue
    }

    def waitUntil(condition: => Boolean, maxCycles: Int, description: String): Unit = {
      var cycles = 0
      while (!condition && cycles < maxCycles) {
        step()
        cycles += 1
      }
      assert(condition, s"timeout waiting for $description")
    }

    private def apbAccess(address: Int, write: Boolean, data: BigInt): ApbResponse = {
      dut.io.apb.psel.poke(true.B)
      dut.io.apb.penable.poke(false.B)
      dut.io.apb.pwrite.poke(write.B)
      dut.io.apb.paddr.poke(address.U)
      dut.io.apb.pwdata.poke(data.U)
      val setupReady = dut.io.apb.pready.peek().litToBoolean
      val setupError = dut.io.apb.pslverr.peek().litToBoolean
      step()

      dut.io.apb.penable.poke(true.B)
      var waitCycles = 0
      while (!dut.io.apb.pready.peek().litToBoolean && waitCycles < 512) {
        dut.io.apb.pslverr.expect(false.B)
        step()
        waitCycles += 1
      }
      assert(dut.io.apb.pready.peek().litToBoolean, f"APB access 0x$address%x timed out")

      val response = ApbResponse(
        data = dut.io.apb.prdata.peek().litValue,
        error = dut.io.apb.pslverr.peek().litToBoolean,
        waitCycles = waitCycles,
        setupReady = setupReady,
        setupError = setupError
      )
      step()
      driveApbIdle()
      response
    }

    private def driveApbIdle(): Unit = {
      dut.io.apb.psel.poke(false.B)
      dut.io.apb.penable.poke(false.B)
      dut.io.apb.pwrite.poke(false.B)
      dut.io.apb.paddr.poke(0.U)
      dut.io.apb.pwdata.poke(0.U)
    }

    private def observeLcdWrite(): Unit = {
      val writen = dut.io.lcd.writen.peek().litToBoolean
      dut.io.lcd.readn.expect(true.B)
      if (!writen) {
        dut.io.lcd.chipSelectn.expect(false.B)
      }
      if (previousWriten && !writen) {
        lcdWrites += LcdWrite(
          data = dut.io.lcd.data.peek().litValue,
          registerSelect = dut.io.lcd.registerSelect.peek().litToBoolean
        )
      }
      previousWriten = writen
    }
  }
}

class LcdCtrlSpec extends AnyFreeSpec with ChiselScalatestTester {
  import LcdCtrlSpec._

  private val annotations = Seq(VerilatorBackendAnnotation)

  "LcdApbDomain CMD and DATA writes hold PREADY low through the complete 8080 cycle" in {
    test(new LcdApbDomain).withAnnotations(annotations) { dut =>
      val driver = new Driver(dut)
      driver.reset()

      val timing = BigInt("00020302", 16) // setup=2, low=3, hold=2
      assert(!driver.apbWrite(LcdCtrlRegisters.writeTiming, timing).error)

      val command = driver.apbWrite(LcdCtrlRegisters.command, BigInt("cafe1234", 16))
      val data = driver.apbWrite(LcdCtrlRegisters.data, BigInt("beef5678", 16))

      assert(!command.setupReady && !data.setupReady)
      assert(command.waitCycles == 7, s"CMD PREADY wait was ${command.waitCycles} cycles")
      assert(data.waitCycles == 7, s"DATA PREADY wait was ${data.waitCycles} cycles")
      assert(!command.error && !data.error)
      assert(
        driver.lcdWrites.toSeq == Seq(
          LcdWrite(BigInt("1234", 16), registerSelect = false),
          LcdWrite(BigInt("5678", 16), registerSelect = true)
        )
      )
      dut.io.lcd.chipSelectn.expect(true.B)
      dut.io.lcd.writen.expect(true.B)
    }
  }

  "LcdApbDomain rejects invalid register aliases, invalid legacy ranges, and CMD or DATA during DMA" in {
    test(new LcdApbDomain).withAnnotations(annotations) { dut =>
      val driver = new Driver(dut)
      driver.reset()

      val invalidAlias = driver.apbWrite(0x100, 0x1234)
      assert(!invalidAlias.setupError && invalidAlias.error, "0x100 must not alias COMMAND")
      val misalignedPowerRead = driver.apbRead(LcdCtrlRegisters.powerControl + 2)
      assert(!misalignedPowerRead.setupError && misalignedPowerRead.error)

      assert(!driver.apbWrite(LcdCtrlRegisters.dmaBase, BigInt("1001", 16)).error)
      assert(!driver.apbWrite(LcdCtrlRegisters.dmaLength, 3).error)
      val invalidStart = driver.apbWrite(LcdCtrlRegisters.control, 2)
      assert(invalidStart.error)
      dut.io.dmaRequest.startToggle.expect(false.B)
      assert((driver.apbRead(LcdCtrlRegisters.status).data & 0xe) == 0)

      assert(!driver.apbWrite(LcdCtrlRegisters.dmaBase, BigInt("1000", 16)).error)
      assert(!driver.apbWrite(LcdCtrlRegisters.dmaLength, 0).error)
      assert(driver.apbWrite(LcdCtrlRegisters.control, 2).error)
      dut.io.dmaRequest.startToggle.expect(false.B)

      assert(!driver.apbWrite(LcdCtrlRegisters.dmaBase, BigInt("fffffffe", 16)).error)
      assert(!driver.apbWrite(LcdCtrlRegisters.dmaLength, 2).error)
      assert(driver.apbWrite(LcdCtrlRegisters.control, 2).error)
      dut.io.dmaRequest.startToggle.expect(false.B)

      driver.startLegacyDma(BigInt("1002", 16), 3)
      dut.io.dmaRequest.startToggle.expect(true.B)
      dut.io.dmaRequest.baseAddress.expect(BigInt("1002", 16).U)
      dut.io.dmaRequest.lineWidth.expect(3.U)
      dut.io.dmaRequest.height.expect(1.U)
      dut.io.dmaRequest.sourceStride.expect(0.U)
      assert((driver.apbRead(LcdCtrlRegisters.status).data & 0x2) == 0x2)

      val command = driver.apbWrite(LcdCtrlRegisters.command, 0xabcd)
      val data = driver.apbWrite(LcdCtrlRegisters.data, 0x1234)
      val dataRead = driver.apbRead(LcdCtrlRegisters.data)
      assert(command.error && data.error && dataRead.error)
      assert(command.waitCycles == 0 && data.waitCycles == 0 && dataRead.waitCycles == 0)
      assert(driver.lcdWrites.isEmpty)
      assert((driver.apbRead(LcdCtrlRegisters.status).data & 0x2) == 0x2)
    }
  }

  "LcdApbDomain validates and latches two-dimensional DMA registers" in {
    test(new LcdApbDomain).withAnnotations(annotations) { dut =>
      val driver = new Driver(dut)
      driver.reset()
      assert(!driver.apbWrite(LcdCtrlRegisters.writeTiming, BigInt("00010101", 16)).error)

      def expectRejected(base: BigInt, width: BigInt, height: BigInt, sourceStride: BigInt): Unit = {
        driver.programTwoDimensionalDma(base, width, height, sourceStride)
        val toggleBefore = dut.io.dmaRequest.startToggle.peek().litToBoolean
        assert(driver.apbWrite(LcdCtrlRegisters.control, 6).error)
        dut.io.dmaRequest.startToggle.expect(toggleBefore.B)
        assert((driver.apbRead(LcdCtrlRegisters.status).data & 0xe) == 0)
      }

      expectRejected(base = BigInt("2001", 16), width = 3, height = 2, sourceStride = 6)
      expectRejected(base = BigInt("200e", 16), width = 0, height = 2, sourceStride = 6)
      expectRejected(base = BigInt("200e", 16), width = 3, height = 0, sourceStride = 6)
      expectRejected(base = BigInt("200e", 16), width = 3, height = 2, sourceStride = 7)
      expectRejected(base = BigInt("200e", 16), width = 4, height = 2, sourceStride = 6)
      expectRejected(base = BigInt("fffffff0", 16), width = 16, height = 1, sourceStride = 32)
      expectRejected(base = 0, width = 1, height = 3, sourceStride = BigInt("80000000", 16))

      val base = BigInt("200e", 16)
      val width = BigInt(3)
      val height = BigInt(8)
      val sourceStride = BigInt(18)
      driver.programTwoDimensionalDma(base, width, height, sourceStride)

      Seq(
        LcdCtrlRegisters.dmaBase -> base,
        LcdCtrlRegisters.dmaWidth -> width,
        LcdCtrlRegisters.dmaHeight -> height,
        LcdCtrlRegisters.dmaSourceStride -> sourceStride
      ).foreach { case (address, expected) =>
        val response = driver.apbRead(address)
        assert(!response.error && response.data == expected)
      }

      assert(!driver.apbWrite(LcdCtrlRegisters.control, 6).error)
      dut.io.dmaRequest.startToggle.expect(true.B)
      dut.io.dmaRequest.baseAddress.expect(base.U)
      dut.io.dmaRequest.lineWidth.expect(width.U)
      dut.io.dmaRequest.height.expect(height.U)
      dut.io.dmaRequest.sourceStride.expect(sourceStride.U)
      assert((driver.status & 0x2) == 0x2)

      val pixels = (0 until (width * height).toInt).map(index => BigInt(0x3000 + index))
      pixels.zipWithIndex.foreach { case (pixel, index) =>
        driver.offerFrame(pixel, last = index == pixels.size - 1)
      }
      driver.waitUntil(
        (driver.status & 0x6) == 0x4,
        maxCycles = 16,
        description = "two-dimensional DMA completion"
      )
      assert(driver.lcdWrites.toSeq == pixels.map(LcdWrite(_, registerSelect = true)))
    }
  }

  "LcdApbDomain maps DMA_LENGTH to a legacy row and backpressures its frame stream" in {
    test(new LcdApbDomain).withAnnotations(annotations) { dut =>
      val driver = new Driver(dut)
      driver.reset()
      assert(!driver.apbWrite(LcdCtrlRegisters.writeTiming, BigInt("00010101", 16)).error)

      val base = BigInt("1002", 16)
      val pixels = Seq(BigInt(0x1111), BigInt(0x2222), BigInt(0x3333))

      driver.startLegacyDma(base, pixels.size)
      dut.io.dmaRequest.baseAddress.expect(base.U)
      dut.io.dmaRequest.lineWidth.expect(pixels.size.U)
      dut.io.dmaRequest.height.expect(1.U)
      dut.io.dmaRequest.sourceStride.expect(0.U)
      val frameWaits = pixels.zipWithIndex.map { case (value, index) =>
        driver.offerFrame(value, last = index == pixels.size - 1)
      }
      driver.waitUntil(
        (driver.status & 0x6) == 0x4,
        maxCycles = 16,
        description = "legacy DMA completion"
      )

      assert(frameWaits.exists(_ > 0), "8080 writer never backpressured the frame stream")
      assert(driver.lcdWrites.toSeq == pixels.map(LcdWrite(_, registerSelect = true)))
      assert(driver.apbRead(LcdCtrlRegisters.status).data == 0x4)
    }
  }

  "LcdApbDomain sets DONE only after the final 8080 hold and clears DONE and ERROR with STATUS W1C" in {
    test(new LcdApbDomain).withAnnotations(annotations) { dut =>
      val driver = new Driver(dut)
      driver.reset()
      val timing = BigInt("00030202", 16) // setup=2, low=2, hold=3
      assert(!driver.apbWrite(LcdCtrlRegisters.writeTiming, timing).error)

      val pixels = (0 until 8).map(index => BigInt(0x4000 + index))
      driver.startLegacyDma(BigInt("2000", 16), pixels.size)

      dut.io.dmaStatus.errorToggle.poke(true.B)
      driver.step(3)
      assert((driver.status & 0xa) == 0xa)

      pixels.dropRight(1).foreach(pixel => driver.offerFrame(pixel, last = false))
      driver.offerFrame(pixels.last, last = true)
      assert(driver.lcdWrites.size == 7)
      assert((driver.status & 0x6) == 0x2)

      for (_ <- 0 until 7) {
        driver.step()
        assert((driver.status & 0x6) == 0x2, "DONE rose before the final 8080 write completed")
      }
      dut.io.lcd.chipSelectn.expect(true.B)
      dut.io.lcd.writen.expect(true.B)

      driver.step()
      assert((driver.status & 0xe) == 0xc)
      assert(driver.lcdWrites.toSeq == pixels.map(LcdWrite(_, registerSelect = true)))

      assert(!driver.apbWrite(LcdCtrlRegisters.status, 0x4).error)
      assert(driver.apbRead(LcdCtrlRegisters.status).data == 0x8)
      assert(!driver.apbWrite(LcdCtrlRegisters.status, 0x8).error)
      assert(driver.apbRead(LcdCtrlRegisters.status).data == 0)
    }
  }

  "LcdApbDomain defaults all three controls on and maps button, status, IRQ, and W1C semantics" in {
    test(new LcdApbDomain(displayOnDelayCycles = 4)).withAnnotations(annotations) { dut =>
      val driver = new Driver(dut)
      driver.reset()

      dut.io.touchEnabled.expect(true.B)
      dut.io.lcd.resetn.expect(false.B)
      dut.io.lcd.backlightEnable.expect(false.B)
      assert(driver.apbRead(LcdCtrlRegisters.powerControl).data == 0x207)

      assert(!driver.apbWrite(LcdCtrlRegisters.control, 1).error)
      dut.io.lcd.resetn.expect(true.B)
      dut.io.lcd.backlightEnable.expect(true.B)
      assert(driver.apbRead(LcdCtrlRegisters.powerControl).data == 0x307)

      assert(!driver.apbWrite(LcdCtrlRegisters.irqEnable, 2).error)
      driver.pulseButton(0)
      assert(driver.powerControl == 0x206)
      assert((driver.status & 0x10) != 0)
      dut.io.lcd.backlightEnable.expect(false.B)
      dut.io.touchEnabled.expect(true.B)
      dut.io.interrupt.expect(true.B)

      assert(!driver.apbWrite(LcdCtrlRegisters.status, 0).error)
      assert((driver.status & 0x10) != 0, "STATUS W1C zero write cleared CONTROL_CHANGED")
      assert(!driver.apbWrite(LcdCtrlRegisters.status, 0x20).error)
      assert((driver.status & 0x10) != 0, "unrelated W1C bit cleared CONTROL_CHANGED")
      assert(!driver.apbWrite(LcdCtrlRegisters.status, 0x10).error)
      assert((driver.status & 0x10) == 0)
      dut.io.interrupt.expect(false.B)

      driver.pulseButton(2)
      dut.io.touchEnabled.expect(false.B)
      assert((driver.powerControl & 0x7) == 0x2)
      driver.pulseButton(0)
      dut.io.lcd.backlightEnable.expect(true.B)
      assert((driver.powerControl & 0x107) == 0x103)
      driver.pulseButton(2)
      dut.io.touchEnabled.expect(true.B)
      assert((driver.powerControl & 0x107) == 0x107)
    }
  }

  "LcdApbDomain waits for the DMA safe point before DISPLAY_OFF and delays backlight after DISPLAY_ON" in {
    val displayOnDelayCycles = 4
    test(new LcdApbDomain(displayOnDelayCycles)).withAnnotations(annotations) { dut =>
      val driver = new Driver(dut)
      driver.reset()
      assert(!driver.apbWrite(LcdCtrlRegisters.writeTiming, BigInt("00010101", 16)).error)
      assert(!driver.apbWrite(LcdCtrlRegisters.control, 1).error)
      driver.lcdWrites.clear()

      val pixels = Seq(BigInt(0x1111), BigInt(0x2222))
      driver.startLegacyDma(BigInt("5000", 16), pixels.size, panelResetn = true)
      driver.offerFrame(pixels.head, last = false)
      driver.pulseButton(1)

      assert((driver.status & 0x82) == 0x82)
      assert((driver.powerControl & 0xf07) == 0xe05)
      dut.io.lcd.backlightEnable.expect(false.B)
      assert(driver.apbWrite(LcdCtrlRegisters.control, 2).error)
      assert(driver.apbWrite(LcdCtrlRegisters.command, 0x1234).error)

      driver.offerFrame(pixels.last, last = true)
      driver.waitUntil(
        (driver.status & 0x6) == 0x4,
        maxCycles = 16,
        description = "DMA completion before DISPLAY_OFF"
      )
      assert(driver.lcdWrites.toSeq == pixels.map(LcdWrite(_, registerSelect = true)))

      driver.waitUntil(
        driver.lcdWrites.size == pixels.size + 1,
        maxCycles = 16,
        description = "DISPLAY_OFF command"
      )
      assert(driver.lcdWrites.last == LcdWrite(BigInt("2800", 16), registerSelect = false))
      driver.waitUntil(
        (driver.status & 0x80) == 0,
        maxCycles = 8,
        description = "DISPLAY_OFF completion"
      )
      assert((driver.powerControl & 0xf07) == 0x805)
      assert(driver.apbWrite(LcdCtrlRegisters.control, 2).error)

      assert(!driver.apbWrite(LcdCtrlRegisters.status, 0x10).error)
      driver.pulseButton(1)
      assert((driver.status & 0x80) != 0)
      assert(driver.apbWrite(LcdCtrlRegisters.data, 0xabcd).error)
      driver.waitUntil(
        driver.lcdWrites.size == pixels.size + 2,
        maxCycles = 16,
        description = "DISPLAY_ON command"
      )
      assert(driver.lcdWrites.last == LcdWrite(BigInt("2900", 16), registerSelect = false))
      driver.waitUntil(
        (driver.powerControl & 0x200) != 0,
        maxCycles = 8,
        description = "DISPLAY_ON command completion"
      )
      assert((driver.powerControl & 0xc00) == 0xc00)
      dut.io.lcd.backlightEnable.expect(false.B)

      for (_ <- 0 until displayOnDelayCycles - 1) {
        driver.step()
        dut.io.lcd.backlightEnable.expect(false.B)
        assert((driver.status & 0x80) != 0)
      }
      driver.step()
      dut.io.lcd.backlightEnable.expect(true.B)
      assert((driver.status & 0x90) == 0x10)
      assert(driver.lcdWrites.toSeq == Seq(
        LcdWrite(pixels.head, registerSelect = true),
        LcdWrite(pixels.last, registerSelect = true),
        LcdWrite(BigInt("2800", 16), registerSelect = false),
        LcdWrite(BigInt("2900", 16), registerSelect = false)
      ))
    }
  }

  "LcdApbDomain recovery ignores repeats until W1C without extending soft reset" in {
    test(new LcdApbDomain(displayOnDelayCycles = 4)).withAnnotations(annotations) { dut =>
      val driver = new Driver(dut)
      driver.reset()
      assert(!driver.apbWrite(LcdCtrlRegisters.writeTiming, BigInt("00010101", 16)).error)
      assert(!driver.apbWrite(LcdCtrlRegisters.control, 1).error)
      assert(!driver.apbWrite(LcdCtrlRegisters.irqEnable, 4).error)
      driver.lcdWrites.clear()

      driver.startLegacyDma(BigInt("6000", 16), 2, panelResetn = true)
      driver.offerFrame(0x5555, last = false)
      driver.pulseButton(3)

      dut.io.dmaSoftReset.expect(true.B)
      dut.io.lcd.resetn.expect(false.B)
      dut.io.lcd.chipSelectn.expect(true.B)
      dut.io.lcd.writen.expect(true.B)
      dut.io.lcd.backlightEnable.expect(false.B)
      dut.io.dmaRequest.startToggle.expect(false.B)
      assert((driver.status & 0xc3) == 0xc0)
      dut.io.interrupt.expect(false.B)
      assert(driver.lcdWrites.isEmpty)

      dut.io.dmaFrame.valid.poke(true.B)
      dut.io.dmaFrame.data.poke(0x6666.U)
      dut.io.dmaFrame.last.poke(true.B)

      var recoveryCycles = 0
      def stepRecovery(): Unit = {
        dut.io.dmaSoftReset.expect(true.B)
        dut.io.dmaFrame.ready.expect(false.B)
        driver.step()
        recoveryCycles += 1
      }

      for (_ <- 0 until 5) {
        stepRecovery()
      }
      dut.io.buttonPress.poke(8.U)
      stepRecovery()
      dut.io.buttonPress.poke(0.U)
      for (_ <- 0 until 10) {
        stepRecovery()
      }

      assert(recoveryCycles == 16)
      dut.io.dmaSoftReset.expect(false.B)
      dut.io.dmaFrame.ready.expect(false.B)
      dut.io.dmaFrame.valid.poke(false.B)

      assert(driver.status == 0x20)
      dut.io.interrupt.expect(true.B)
      driver.pulseButton(3)
      dut.io.dmaSoftReset.expect(false.B)
      assert(driver.status == 0x20)
      val ignoredSoftwareRecovery = driver.apbWrite(LcdCtrlRegisters.powerControl, 0xf)
      assert(!ignoredSoftwareRecovery.setupError && !ignoredSoftwareRecovery.error)
      dut.io.dmaSoftReset.expect(false.B)
      assert(driver.status == 0x20)

      val recoveryCommand = driver.apbWrite(LcdCtrlRegisters.command, 0x2a00)
      val recoveryData = driver.apbWrite(LcdCtrlRegisters.data, 0x1234)
      assert(!recoveryCommand.error && !recoveryData.error)
      assert(driver.lcdWrites.toSeq == Seq(
        LcdWrite(BigInt("2a00", 16), registerSelect = false),
        LcdWrite(BigInt("1234", 16), registerSelect = true)
      ))
      assert(driver.status == 0x20)

      assert(!driver.apbWrite(LcdCtrlRegisters.status, 0).error)
      assert(driver.status == 0x20)
      dut.io.interrupt.expect(true.B)
      assert(!driver.apbWrite(LcdCtrlRegisters.status, 0x20).error)
      assert(driver.status == 0)
      dut.io.interrupt.expect(false.B)
      dut.io.lcd.resetn.expect(false.B)
      dut.io.lcd.backlightEnable.expect(false.B)

      assert(!driver.apbWrite(LcdCtrlRegisters.control, 1).error)
      val softwareRecovery = driver.apbWrite(LcdCtrlRegisters.powerControl, 0xf)
      assert(!softwareRecovery.setupError && !softwareRecovery.error)
      dut.io.dmaSoftReset.expect(true.B)
      dut.io.lcd.resetn.expect(false.B)
      assert((driver.powerControl & 0x8) == 0, "software recovery request bit must read as zero")
    }
  }

  "LcdApbDomain recovery W1C preserves a disabled display request" in {
    test(new LcdApbDomain(displayOnDelayCycles = 4)).withAnnotations(annotations) { dut =>
      val driver = new Driver(dut)
      driver.reset()
      assert(!driver.apbWrite(LcdCtrlRegisters.writeTiming, BigInt("00010101", 16)).error)
      assert(!driver.apbWrite(LcdCtrlRegisters.control, 1).error)

      assert(!driver.apbWrite(LcdCtrlRegisters.powerControl, 0x5).error)
      driver.waitUntil(
        (driver.status & 0x80) == 0,
        maxCycles = 16,
        description = "DISPLAY_OFF completion before recovery"
      )
      assert(driver.lcdWrites.last == LcdWrite(BigInt("2800", 16), registerSelect = false))
      assert(!driver.apbWrite(LcdCtrlRegisters.status, 0x10).error)
      assert(driver.status == 0)
      assert((driver.powerControl & 0x202) == 0)
      driver.lcdWrites.clear()

      driver.pulseButton(3)
      driver.waitUntil(
        driver.status == 0x20,
        maxCycles = 20,
        description = "recovery pending with display disabled"
      )
      assert(!driver.apbWrite(LcdCtrlRegisters.status, 0x20).error)
      assert(driver.status == 0)
      assert((driver.powerControl & 0x202) == 0)
      dut.io.lcd.backlightEnable.expect(false.B)

      driver.step(8)
      assert(driver.status == 0)
      assert(driver.lcdWrites.isEmpty, "recovery W1C incorrectly scheduled DISPLAY_OFF")
    }
  }

  "LcdApbDomain IRQ_ENABLE gates pending DONE and excludes DMA_ERROR" in {
    test(new LcdApbDomain).withAnnotations(annotations) { dut =>
      val driver = new Driver(dut)
      driver.reset()

      val irqEnableAddress = 0x1c
      val highBitsOnly = BigInt("fffffff8", 16)
      val allBitsSet = BigInt("ffffffff", 16)

      val resetIrqEnable = driver.apbRead(irqEnableAddress)
      assert(!resetIrqEnable.error && resetIrqEnable.data == 0)
      assert(!driver.apbWrite(irqEnableAddress, highBitsOnly).error)
      assert(driver.apbRead(irqEnableAddress).data == 0)
      assert(!driver.apbWrite(irqEnableAddress, allBitsSet).error)
      assert(driver.apbRead(irqEnableAddress).data == 7)
      assert(!driver.apbWrite(irqEnableAddress, highBitsOnly).error)
      assert(driver.apbRead(irqEnableAddress).data == 0)

      assert(!driver.apbWrite(LcdCtrlRegisters.writeTiming, BigInt("00010101", 16)).error)
      driver.startLegacyDma(BigInt("3000", 16), 1)
      driver.offerFrame(BigInt("1234", 16), last = true)
      driver.waitUntil(
        (driver.status & 0x6) == 0x4,
        maxCycles = 16,
        description = "pending DMA_DONE with IRQ disabled"
      )
      dut.io.interrupt.expect(false.B)

      assert(!driver.apbWrite(irqEnableAddress, 1).error)
      dut.io.interrupt.expect(true.B)
      assert(driver.apbRead(irqEnableAddress).data == 1)

      assert(!driver.apbWrite(LcdCtrlRegisters.status, 0x4).error)
      dut.io.interrupt.expect(false.B)
      assert((driver.apbRead(LcdCtrlRegisters.status).data & 0x4) == 0)
      assert(driver.apbRead(irqEnableAddress).data == 1)

      driver.startLegacyDma(BigInt("4000", 16), 2)
      dut.io.dmaStatus.errorToggle.poke(true.B)
      dut.io.interrupt.expect(false.B)
      for (_ <- 0 until 3) {
        driver.step()
        dut.io.interrupt.expect(false.B)
      }
      assert((driver.status & 0xe) == 0xa)
      assert(driver.apbRead(irqEnableAddress).data == 1)
      dut.io.interrupt.expect(false.B)
    }
  }
}
