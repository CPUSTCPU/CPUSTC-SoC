package chisel

import chisel3._
import chiseltest._
import chiseltest.simulator.{VerilatorBackendAnnotation, VerilatorFlags}
import chisel.axiInterconnect.vga.{VGACtrl, VGACtrlRegisters}
import chisel.common.bus.{APB3IO, AXI4IO}
import org.scalatest.freespec.AnyFreeSpec
import java.nio.file.Paths
import scala.collection.mutable.ArrayBuffer

private class VGACtrlBoardLikeTestHarness(useBoardVgaClockDivider: Boolean = true) extends Module {
  val io = IO(new Bundle {
    val axi = new AXI4IO
    val apb = Flipped(new APB3IO)
    val vga = new VGAPort
    val interrupt = Output(Bool())
  })

  val vgaClockDiv = RegInit(0.U(2.W))
  vgaClockDiv := vgaClockDiv + 1.U
  val apbClockDiv = RegInit(false.B)
  apbClockDiv := !apbClockDiv

  val dut = Module(new VGACtrl)
  dut.io.vgaClk := (if (useBoardVgaClockDivider) vgaClockDiv(1).asClock else clock)
  dut.io.axiClk := clock
  dut.io.apbClk := apbClockDiv.asClock
  dut.io.resetn := !reset.asBool

  io.axi <> dut.io.axi
  APB3IO.connectMasterToSlave(io.apb, dut.io.apb)
  io.vga <> dut.io.vga
  io.interrupt := dut.io.interrupt
}

private object VGACtrlSpec {
  val HVisible = 640
  val HFront = 16
  val HSync = 96
  val HBack = 48
  val HTotal = HVisible + HFront + HSync + HBack

  val VVisible = 480
  val VFront = 10
  val VSync = 2
  val VBack = 33
  val VTotal = VVisible + VFront + VSync + VBack

  val AxiCyclesPerVgaCycle = 4
  val FrameBase = BigInt("87e00000", 16)
  val BytesPerLine = HVisible * 2
  val BeatsPerBurst = 16

  val SmallHVisible = 64
  val SmallHFront = 2
  val SmallHSync = 2
  val SmallHBack = 2
  val SmallHTotal = SmallHVisible + SmallHFront + SmallHSync + SmallHBack
  val SmallVVisible = 4
  val SmallVFront = 1
  val SmallVSync = 1
  val SmallVBack = 1
  val SmallVTotal = SmallVVisible + SmallVFront + SmallVSync + SmallVBack
  val SmallBurstCount = 2
  val NewFrameBase = BigInt("88000000", 16)

  final case class ApbResponse(waitCycles: Int, error: Boolean)
  final case class ApbReadResponse(data: BigInt, waitCycles: Int, error: Boolean)

  def pixelForLine(line: Int): Int = {
    val encoded = line + 1
    val r = (encoded >> 8) & 0xf
    val g = (encoded >> 4) & 0xf
    val b = encoded & 0xf
    (r << 12) | (g << 7) | (b << 1)
  }

  def colorForLine(line: Int): Int = {
    val pixel = pixelForLine(line)
    val r = (pixel >> 12) & 0xf
    val g = (pixel >> 7) & 0xf
    val b = (pixel >> 1) & 0xf
    (r << 8) | (g << 4) | b
  }

  def lineForAddress(addr: BigInt): Int = {
    val offset = (addr - FrameBase).toInt
    require(offset >= 0, f"unexpected VGA read address 0x$addr%x")
    val line = offset / BytesPerLine
    require(line >= 0 && line < VVisible, s"unexpected VGA framebuffer line $line")
    line
  }

  def wordForAddress(addr: BigInt): BigInt = {
    val pixel = pixelForLine(lineForAddress(addr))
    BigInt((pixel << 16) | pixel)
  }

  def vgaColor(dut: VGACtrlBoardLikeTestHarness): Int = {
    val r = dut.io.vga.vga_r.peek().litValue.toInt
    val g = dut.io.vga.vga_g.peek().litValue.toInt
    val b = dut.io.vga.vga_b.peek().litValue.toInt
    (r << 8) | (g << 4) | b
  }

  def formatColors(colors: Seq[Int]): String =
    colors.map(c => f"0x$c%03x").mkString("[", ", ", "]")

  final class AxiReadModel(
    dut: VGACtrlBoardLikeTestHarness,
    readLatencyCycles: Int,
    beatGapCycles: Int,
    arCooldownCycles: Int,
    dataForAddress: BigInt => BigInt = wordForAddress
  ) {
    private var active = false
    private var addr = BigInt(0)
    private var beatsLeft = 0
    private var readDelay = 0
    private var beatGap = 0
    private var arCooldown = 0
    var cycleCount = 0L
    val acceptedAddresses: ArrayBuffer[BigInt] = ArrayBuffer.empty
    val acceptedAddressCycles: ArrayBuffer[Long] = ArrayBuffer.empty

    def init(): Unit = {
      dut.io.axi.awready.poke(false.B)
      dut.io.axi.wready.poke(false.B)
      dut.io.axi.bid.poke(0.U)
      dut.io.axi.bresp.poke(0.U)
      dut.io.axi.bvalid.poke(false.B)
      dut.io.axi.arready.poke(false.B)
      dut.io.axi.rid.poke(0.U)
      dut.io.axi.rdata.poke(0.U)
      dut.io.axi.rresp.poke(0.U)
      dut.io.axi.rlast.poke(false.B)
      dut.io.axi.rvalid.poke(false.B)
    }

    def tick(): Unit = {
      cycleCount += 1
      val canAcceptAr = !active && arCooldown == 0
      dut.io.axi.arready.poke(canAcceptAr.B)

      if (canAcceptAr && dut.io.axi.arvalid.peek().litToBoolean) {
        active = true
        addr = dut.io.axi.araddr.peek().litValue
        acceptedAddresses += addr
        acceptedAddressCycles += cycleCount
        beatsLeft = dut.io.axi.arlen.peek().litValue.toInt + 1
        readDelay = readLatencyCycles
        beatGap = 0
        arCooldown = arCooldownCycles
        require(beatsLeft == BeatsPerBurst, s"unexpected VGA AXI burst length $beatsLeft")
      }

      if (arCooldown > 0 && !canAcceptAr) {
        arCooldown -= 1
      }

      if (active && readDelay > 0) {
        readDelay -= 1
        dut.io.axi.rvalid.poke(false.B)
        dut.io.axi.rdata.poke(0.U)
        dut.io.axi.rlast.poke(false.B)
      } else if (active && beatGap > 0) {
        beatGap -= 1
        dut.io.axi.rvalid.poke(false.B)
        dut.io.axi.rdata.poke(0.U)
        dut.io.axi.rlast.poke(false.B)
      } else if (active) {
        dut.io.axi.rvalid.poke(true.B)
        dut.io.axi.rdata.poke(dataForAddress(addr).U)
        dut.io.axi.rlast.poke((beatsLeft == 1).B)

        if (dut.io.axi.rready.peek().litToBoolean) {
          addr += 4
          beatsLeft -= 1
          if (beatsLeft == 0) {
            active = false
          } else {
            beatGap = beatGapCycles
          }
        }
      } else {
        dut.io.axi.rvalid.poke(false.B)
        dut.io.axi.rdata.poke(0.U)
        dut.io.axi.rlast.poke(false.B)
      }
    }
  }

  final class ApbDriver(dut: VGACtrlBoardLikeTestHarness, axi: AxiReadModel) {
    def init(): Unit = driveIdle()

    def write(address: Int, data: BigInt, maxWaitCycles: Int = 1024): ApbResponse = {
      dut.io.apb.psel.poke(true.B)
      dut.io.apb.penable.poke(false.B)
      dut.io.apb.pwrite.poke(true.B)
      dut.io.apb.paddr.poke(address.U)
      dut.io.apb.pwdata.poke(data.U)
      stepWithAxi(dut, axi, 2)

      dut.io.apb.penable.poke(true.B)
      var waitCycles = 0
      while (!dut.io.apb.pready.peek().litToBoolean && waitCycles < maxWaitCycles) {
        dut.io.apb.pslverr.expect(false.B)
        stepWithAxi(dut, axi, 1)
        waitCycles += 1
      }
      assert(dut.io.apb.pready.peek().litToBoolean, f"APB write 0x$address%x timed out")
      val response = ApbResponse(waitCycles, dut.io.apb.pslverr.peek().litToBoolean)
      stepWithAxi(dut, axi, 2)
      driveIdle()
      response
    }

    def read(address: Int, maxWaitCycles: Int = 1024): ApbReadResponse = {
      dut.io.apb.psel.poke(true.B)
      dut.io.apb.penable.poke(false.B)
      dut.io.apb.pwrite.poke(false.B)
      dut.io.apb.paddr.poke(address.U)
      dut.io.apb.pwdata.poke(0.U)
      stepWithAxi(dut, axi, 2)

      dut.io.apb.penable.poke(true.B)
      var waitCycles = 0
      while (!dut.io.apb.pready.peek().litToBoolean && waitCycles < maxWaitCycles) {
        dut.io.apb.pslverr.expect(false.B)
        stepWithAxi(dut, axi, 1)
        waitCycles += 1
      }
      assert(dut.io.apb.pready.peek().litToBoolean, f"APB read 0x$address%x timed out")
      val response = ApbReadResponse(
        dut.io.apb.prdata.peek().litValue,
        waitCycles,
        dut.io.apb.pslverr.peek().litToBoolean
      )
      stepWithAxi(dut, axi, 2)
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

  def stepWithAxi(dut: VGACtrlBoardLikeTestHarness, axi: AxiReadModel, cycles: Int): Unit = {
    for (_ <- 0 until cycles) {
      axi.tick()
      dut.clock.step()
    }
  }

  def waitForVsyncRising(dut: VGACtrlBoardLikeTestHarness, axi: AxiReadModel): Unit = {
    var last = dut.io.vga.vga_vsync.peek().litToBoolean
    var seen = false
    var guard = 0
    while (!seen && guard < HTotal * VTotal * AxiCyclesPerVgaCycle * 2) {
      axi.tick()
      dut.clock.step()
      val now = dut.io.vga.vga_vsync.peek().litToBoolean
      seen = !last && now
      last = now
      guard += 1
    }
    require(seen, "timeout while waiting for VGA vsync rising edge")
  }

  def firstVisibleColorAfterVsync(dut: VGACtrlBoardLikeTestHarness, axi: AxiReadModel): Int = {
    waitForVsyncRising(dut, axi)
    var color = 0
    var guard = 0
    val maxScan = (VBack + 2) * HTotal * AxiCyclesPerVgaCycle
    while (color == 0 && guard < maxScan) {
      stepWithAxi(dut, axi, 1)
      color = vgaColor(dut)
      guard += 1
    }
    require(color != 0, "timeout while waiting for first non-black visible VGA pixel after vsync")
    color
  }

  def sampleLineColorsFromCurrentPixel(
    dut: VGACtrlBoardLikeTestHarness,
    axi: AxiReadModel,
    firstColor: Int,
    rows: Int
  ): Seq[Int] = {
    val colors = collection.mutable.ArrayBuffer(firstColor)
    for (_ <- 1 until rows) {
      stepWithAxi(dut, axi, HTotal * AxiCyclesPerVgaCycle)
      colors += vgaColor(dut)
    }
    colors.toSeq
  }

  def packedXY(x: Int, y: Int): BigInt =
    (BigInt(y & 0xffff) << 16) | BigInt(x & 0xffff)

  def packedSize(width: Int, height: Int): BigInt =
    BigInt((height << 8) | width)

  def packedCursorSource(x: Int, y: Int): BigInt =
    BigInt((y << 8) | x)

  def cursorPixelAddress(bank: Int, x: Int, y: Int): Int = {
    require(bank == 0 || bank == 1)
    require(x >= 0 && x < VGACtrlRegisters.cursorWidth)
    require(y >= 0 && y < VGACtrlRegisters.cursorHeight)
    (bank << 12) | (y << 6) | x
  }

  def configureSmallMode(apb: ApbDriver): Unit = {
    val responses = Seq(
      VGACtrlRegisters.hVisible -> SmallHVisible,
      VGACtrlRegisters.hFront -> SmallHFront,
      VGACtrlRegisters.hSync -> SmallHSync,
      VGACtrlRegisters.hBack -> SmallHBack,
      VGACtrlRegisters.vVisible -> SmallVVisible,
      VGACtrlRegisters.vFront -> SmallVFront,
      VGACtrlRegisters.vSync -> SmallVSync,
      VGACtrlRegisters.vBack -> SmallVBack,
      VGACtrlRegisters.burstCountMax -> SmallBurstCount
    ).map { case (address, value) => apb.write(address, value) }
    assert(responses.forall(!_.error), "small timing configuration returned APB error")
  }

  def waitForSmallTiming(dut: VGACtrlBoardLikeTestHarness, axi: AxiReadModel): Unit = {
    var previousHsync = dut.io.vga.vga_hsync.peek().litToBoolean
    var lastHsyncFalling = Option.empty[Long]
    var sawSmallPeriod = false
    val deadline = axi.cycleCount + HTotal.toLong * VTotal * 2
    while (!sawSmallPeriod && axi.cycleCount < deadline) {
      stepWithAxi(dut, axi, 1)
      val hsync = dut.io.vga.vga_hsync.peek().litToBoolean
      if (previousHsync && !hsync) {
        lastHsyncFalling.foreach { previousFalling =>
          sawSmallPeriod ||= axi.cycleCount - previousFalling == SmallHTotal
        }
        lastHsyncFalling = Some(axi.cycleCount)
      }
      previousHsync = hsync
    }
    assert(sawSmallPeriod, "64-pixel cursor test timing was not applied")
  }

  def waitForSignalRising(
    dut: VGACtrlBoardLikeTestHarness,
    axi: AxiReadModel,
    signal: () => Boolean,
    maxCycles: Int,
    description: String
  ): Unit = {
    var previous = signal()
    var seen = false
    var cycles = 0
    while (!seen && cycles < maxCycles) {
      stepWithAxi(dut, axi, 1)
      val current = signal()
      seen = !previous && current
      previous = current
      cycles += 1
    }
    assert(seen, s"timeout while waiting for $description rising edge")
  }

  def waitForHsyncFallingSmall(dut: VGACtrlBoardLikeTestHarness, axi: AxiReadModel): Unit = {
    var previous = dut.io.vga.vga_hsync.peek().litToBoolean
    var seen = false
    var cycles = 0
    while (!seen && cycles < SmallHTotal * 2) {
      stepWithAxi(dut, axi, 1)
      val current = dut.io.vga.vga_hsync.peek().litToBoolean
      seen = previous && !current
      previous = current
      cycles += 1
    }
    assert(seen, "timeout while waiting for small-mode HSYNC falling edge")
  }

  /** Returns with the VGA output aligned to visible pixel (0, 0). */
  def waitForSmallVisibleFrameStart(dut: VGACtrlBoardLikeTestHarness, axi: AxiReadModel): Unit = {
    waitForSignalRising(
      dut,
      axi,
      () => dut.io.vga.vga_vsync.peek().litToBoolean,
      SmallHTotal * SmallVTotal * 2,
      "small-mode VSYNC"
    )
    waitForHsyncFallingSmall(dut, axi)
    stepWithAxi(dut, axi, SmallHSync + SmallHBack)
  }

  def writeCursorPixel(
    apb: ApbDriver,
    bank: Int,
    x: Int,
    y: Int,
    argb8888: BigInt
  ): Unit = {
    assert(!apb.write(VGACtrlRegisters.cursorRamAddress, cursorPixelAddress(bank, x, y)).error)
    assert(!apb.write(VGACtrlRegisters.cursorRamData, argb8888).error)
  }

  def waitForCursorIdle(apb: ApbDriver, maxReads: Int = 256): BigInt = {
    var status = apb.read(VGACtrlRegisters.cursorStatus)
    var reads = 1
    while (!status.error && (status.data & 0x4) != 0 && reads < maxReads) {
      status = apb.read(VGACtrlRegisters.cursorStatus)
      reads += 1
    }
    assert(!status.error, "CURSOR_STATUS read returned APB error")
    assert((status.data & 0x4) == 0,
      f"cursor commit remained busy after $reads reads: status=0x${status.data}%x")
    status.data
  }
}

class VGACtrlSpec extends AnyFreeSpec with ChiselScalatestTester {
  import VGACtrlSpec._

  "VGACtrl cursor APB ABI should enforce reset, access, and upload rules" in {
    val blkMemGen0Sim = Paths.get("src/test/resources/blk_mem_gen_0_sim.v").toAbsolutePath.toString

    test(new VGACtrlBoardLikeTestHarness(useBoardVgaClockDivider = false))
      .withAnnotations(Seq(
        VerilatorBackendAnnotation,
        VerilatorFlags(Seq("-Wno-WIDTH", blkMemGen0Sim))
      )) { dut =>
        dut.clock.setTimeout(0)

        val axi = new AxiReadModel(
          dut = dut,
          readLatencyCycles = 0,
          beatGapCycles = 0,
          arCooldownCycles = 0,
          dataForAddress = _ => BigInt(0)
        )
        val apb = new ApbDriver(dut, axi)
        axi.init()
        apb.init()

        dut.reset.poke(true.B)
        stepWithAxi(dut, axi, 16)
        dut.reset.poke(false.B)
        stepWithAxi(dut, axi, 8)

        val resetValues = Seq(
          VGACtrlRegisters.cursorIdentification -> VGACtrlRegisters.cursorIdentificationValue,
          VGACtrlRegisters.cursorCapabilities -> VGACtrlRegisters.cursorCapabilitiesValue,
          VGACtrlRegisters.cursorPosition -> BigInt(0),
          VGACtrlRegisters.cursorSource -> BigInt(0),
          VGACtrlRegisters.cursorSize -> BigInt(0),
          VGACtrlRegisters.cursorControl -> BigInt(0),
          VGACtrlRegisters.cursorRamAddress -> BigInt(0x1000),
          VGACtrlRegisters.cursorStatus -> BigInt(0)
        )
        resetValues.foreach { case (address, expected) =>
          val response = apb.read(address)
          assert(!response.error && response.data == expected,
            f"cursor reset read 0x$address%x was data=0x${response.data}%x error=${response.error}")
        }

        assert(apb.read(VGACtrlRegisters.cursorRamData).error,
          "CURSOR_RAM_DATA unexpectedly allowed reads")
        assert(apb.read(0x58).error, "unmapped cursor-adjacent APB read did not fail")
        assert(apb.write(0x58, 0).error, "unmapped cursor-adjacent APB write did not fail")
        Seq(
          VGACtrlRegisters.cursorIdentification,
          VGACtrlRegisters.cursorCapabilities,
          VGACtrlRegisters.cursorStatus
        ).foreach { address =>
          assert(apb.write(address, 0).error, f"read-only cursor register 0x$address%x accepted a write")
        }

        assert(apb.write(VGACtrlRegisters.cursorSource, packedCursorSource(64, 0)).error)
        assert(apb.write(VGACtrlRegisters.cursorSource, packedCursorSource(0, 64)).error)
        assert(apb.write(VGACtrlRegisters.cursorSource, BigInt(1) << 16).error)
        assert(apb.write(VGACtrlRegisters.cursorSize, packedSize(65, 1)).error)
        assert(apb.write(VGACtrlRegisters.cursorSize, packedSize(1, 65)).error)
        assert(apb.write(VGACtrlRegisters.cursorSize, BigInt(1) << 16).error)
        assert(apb.write(VGACtrlRegisters.cursorControl, 1).error,
          "zero-sized cursor was enabled")
        assert(apb.write(VGACtrlRegisters.cursorControl, 4).error,
          "reserved CURSOR_CONTROL bits were accepted")
        assert(apb.write(VGACtrlRegisters.cursorRamAddress, 0x2000).error,
          "out-of-range cursor RAM address was accepted")
        assert(apb.read(VGACtrlRegisters.cursorSource).data == 0)
        assert(apb.read(VGACtrlRegisters.cursorSize).data == 0)
        assert(apb.read(VGACtrlRegisters.cursorControl).data == 0)
        assert(apb.read(VGACtrlRegisters.cursorRamAddress).data == 0x1000)

        assert(!apb.write(VGACtrlRegisters.cursorRamAddress, 0x1ffe).error)
        assert(!apb.write(VGACtrlRegisters.cursorRamData, BigInt("11223344", 16)).error)
        assert(apb.read(VGACtrlRegisters.cursorRamAddress).data == 0x1fff,
          "inactive cursor RAM write did not auto-increment")
        assert(!apb.write(VGACtrlRegisters.cursorRamData, BigInt("55667788", 16)).error)
        assert(apb.read(VGACtrlRegisters.cursorRamAddress).data == 0x1000,
          "cursor RAM auto-increment crossed out of its selected bank")

        assert(!apb.write(VGACtrlRegisters.cursorRamAddress, 0).error)
        assert(apb.write(VGACtrlRegisters.cursorRamData, BigInt("deadbeef", 16)).error,
          "active cursor bank accepted an upload")
        assert(apb.read(VGACtrlRegisters.cursorRamAddress).data == 0,
          "rejected active-bank write advanced CURSOR_RAM_ADDRESS")
        val uploadErrorStatus = apb.read(VGACtrlRegisters.cursorStatus)
        assert(!uploadErrorStatus.error && uploadErrorStatus.data == 0x8,
          f"active-bank rejection did not latch upload error: 0x${uploadErrorStatus.data}%x")
      }
  }

  "VGACtrl cursor should blend, clip, and commit at line or vblank boundaries" in {
    val blkMemGen0Sim = Paths.get("src/test/resources/blk_mem_gen_0_sim.v").toAbsolutePath.toString

    test(new VGACtrlBoardLikeTestHarness(useBoardVgaClockDivider = false))
      .withAnnotations(Seq(
        VerilatorBackendAnnotation,
        VerilatorFlags(Seq("-Wno-WIDTH", blkMemGen0Sim))
      )) { dut =>
        dut.clock.setTimeout(0)

        val background565 = BigInt("2104", 16)
        val backgroundColor = 0x222
        val bank1Red = BigInt("ffff0000", 16)
        val bank0Green = BigInt("ff00ff00", 16)
        val axi = new AxiReadModel(
          dut = dut,
          readLatencyCycles = 0,
          beatGapCycles = 0,
          arCooldownCycles = 0,
          dataForAddress = _ => (background565 << 16) | background565
        )
        val apb = new ApbDriver(dut, axi)
        val datapathFailures = ArrayBuffer.empty[String]
        def checkDatapath(condition: Boolean, message: => String): Unit = {
          if (!condition) datapathFailures += message
        }
        axi.init()
        apb.init()

        dut.reset.poke(true.B)
        stepWithAxi(dut, axi, 16)
        dut.reset.poke(false.B)
        stepWithAxi(dut, axi, 8)

        configureSmallMode(apb)
        waitForSmallTiming(dut, axi)

        assert(!apb.write(VGACtrlRegisters.cursorRamAddress, cursorPixelAddress(1, 10, 10)).error)
        Seq(
          BigInt("00000000", 16),
          BigInt("ffff0000", 16),
          BigInt("80402010", 16)
        ).foreach { pixel =>
          assert(!apb.write(VGACtrlRegisters.cursorRamData, pixel).error)
        }
        assert(apb.read(VGACtrlRegisters.cursorRamAddress).data == cursorPixelAddress(1, 13, 10),
          "ARGB upload sequence did not auto-increment across adjacent pixels")
        writeCursorPixel(apb, bank = 1, x = 3, y = 2, argb8888 = BigInt("ff00ff00", 16))
        writeCursorPixel(apb, bank = 1, x = 4, y = 2, argb8888 = BigInt("ff0000ff", 16))
        for (y <- 30 until 34) {
          writeCursorPixel(apb, bank = 1, x = 30, y = y, argb8888 = bank1Red)
        }

        assert(!apb.write(VGACtrlRegisters.cursorPosition, packedXY(0, 0)).error)
        assert(!apb.write(VGACtrlRegisters.cursorSource, packedCursorSource(10, 10)).error)
        assert(!apb.write(VGACtrlRegisters.cursorSize, packedSize(3, 1)).error)
        assert(!apb.write(VGACtrlRegisters.cursorControl, 3).error)
        val enablingStatus = apb.read(VGACtrlRegisters.cursorStatus)
        assert(!enablingStatus.error && (enablingStatus.data & 0x4) != 0,
          f"cursor enable/bank commit did not report busy: 0x${enablingStatus.data}%x")
        assert((enablingStatus.data & 0x3) == 0,
          f"cursor enable/bank became active before commit: 0x${enablingStatus.data}%x")
        val enabledStatus = waitForCursorIdle(apb)
        assert((enabledStatus & 0x3) == 0x3,
          f"cursor enable/bank commit did not become active: 0x$enabledStatus%x")
        assert(apb.read(VGACtrlRegisters.cursorPosition).data == packedXY(0, 0))
        assert(apb.read(VGACtrlRegisters.cursorSource).data == packedCursorSource(10, 10))
        assert(apb.read(VGACtrlRegisters.cursorSize).data == packedSize(3, 1))
        assert(apb.read(VGACtrlRegisters.cursorControl).data == 3)

        waitForSmallVisibleFrameStart(dut, axi)
        val alphaColors = (0 until 3).map { x =>
          if (x > 0) stepWithAxi(dut, axi, 1)
          vgaColor(dut)
        }
        checkDatapath(
          alphaColors == Seq(backgroundColor, 0xf00, 0x532),
          s"ARGB8888 alpha 0/255/128 colors were ${formatColors(alphaColors)}"
        )

        assert(!apb.write(VGACtrlRegisters.cursorPosition, packedXY(-2, -1)).error)
        assert(!apb.write(VGACtrlRegisters.cursorSource, packedCursorSource(1, 1)).error)
        assert(!apb.write(VGACtrlRegisters.cursorSize, packedSize(4, 2)).error)
        assert(!apb.write(VGACtrlRegisters.cursorControl, 3).error)
        waitForCursorIdle(apb)
        waitForSmallVisibleFrameStart(dut, axi)
        val clippedColors = Seq(
          vgaColor(dut),
          { stepWithAxi(dut, axi, 1); vgaColor(dut) },
          { stepWithAxi(dut, axi, 1); vgaColor(dut) }
        )
        checkDatapath(
          clippedColors == Seq(0x0f0, 0x00f, backgroundColor),
          s"negative-edge clipping/source offset colors were ${formatColors(clippedColors)}"
        )

        assert(!apb.write(VGACtrlRegisters.cursorPosition, packedXY(20, 0)).error)
        assert(!apb.write(VGACtrlRegisters.cursorSource, packedCursorSource(30, 30)).error)
        assert(!apb.write(VGACtrlRegisters.cursorSize, packedSize(1, 4)).error)
        assert(!apb.write(VGACtrlRegisters.cursorControl, 3).error)
        waitForCursorIdle(apb)
        waitForSmallVisibleFrameStart(dut, axi)
        val positionRowStart = axi.cycleCount
        stepWithAxi(dut, axi, 5)
        assert(!apb.write(VGACtrlRegisters.cursorPosition, packedXY(40, 0)).error)
        assert(!apb.write(VGACtrlRegisters.cursorControl, 3).error)
        val positionCommitElapsed = (axi.cycleCount - positionRowStart).toInt
        assert(positionCommitElapsed < 20,
          s"position request missed its intended row: elapsed=$positionCommitElapsed")
        stepWithAxi(dut, axi, 20 - positionCommitElapsed)
        checkDatapath(
          vgaColor(dut) == 0xf00,
          f"old cursor position changed before line boundary: 0x${vgaColor(dut)}%03x"
        )
        stepWithAxi(dut, axi, 20)
        checkDatapath(
          vgaColor(dut) == backgroundColor,
          f"new cursor position appeared within the old row: 0x${vgaColor(dut)}%03x"
        )
        stepWithAxi(dut, axi, SmallHTotal - 40 + 20)
        checkDatapath(
          vgaColor(dut) == backgroundColor,
          f"old cursor position remained after line boundary: 0x${vgaColor(dut)}%03x"
        )
        stepWithAxi(dut, axi, 20)
        checkDatapath(
          vgaColor(dut) == 0xf00,
          f"new cursor position was absent after line boundary: 0x${vgaColor(dut)}%03x"
        )
        val positionStatus = apb.read(VGACtrlRegisters.cursorStatus)
        assert(!positionStatus.error && (positionStatus.data & 0x7) == 0x3,
          f"position-only commit did not acknowledge with bank 1 active: 0x${positionStatus.data}%x")

        for (y <- 30 until 34) {
          writeCursorPixel(apb, bank = 0, x = 30, y = y, argb8888 = bank0Green)
        }
        waitForSmallVisibleFrameStart(dut, axi)
        val bankRowStart = axi.cycleCount
        assert(!apb.write(VGACtrlRegisters.cursorControl, 1).error)
        val bankPendingStatus = apb.read(VGACtrlRegisters.cursorStatus)
        assert(!bankPendingStatus.error && (bankPendingStatus.data & 0x7) == 0x7,
          f"bank switch was not pending with bank 1 active: 0x${bankPendingStatus.data}%x")
        val bankRequestElapsed = (axi.cycleCount - bankRowStart).toInt
        assert(bankRequestElapsed < 40,
          s"bank request missed its intended visible row: elapsed=$bankRequestElapsed")
        stepWithAxi(dut, axi, 40 - bankRequestElapsed)
        checkDatapath(vgaColor(dut) == 0xf00, "bank changed during visible row 0")
        for (row <- 1 until SmallVVisible) {
          stepWithAxi(dut, axi, SmallHTotal)
          checkDatapath(vgaColor(dut) == 0xf00, s"bank changed during visible row $row")
        }
        stepWithAxi(dut, axi, (SmallVTotal - SmallVVisible + 1) * SmallHTotal)
        checkDatapath(
          vgaColor(dut) == 0x0f0,
          f"new bank was absent from the next visible frame: 0x${vgaColor(dut)}%03x"
        )
        val bankActiveStatus = apb.read(VGACtrlRegisters.cursorStatus)
        assert(!bankActiveStatus.error && (bankActiveStatus.data & 0x7) == 0x1,
          f"bank switch did not acknowledge bank 0 at vblank: 0x${bankActiveStatus.data}%x")
        assert(datapathFailures.isEmpty, datapathFailures.mkString("; "))
      }
  }

  "VGACtrl IRQ and display control should work across board-like clocks and AXI traffic" in {
    val blkMemGen0Sim = Paths.get("src/test/resources/blk_mem_gen_0_sim.v").toAbsolutePath.toString

    test(new VGACtrlBoardLikeTestHarness)
      .withAnnotations(Seq(
        VerilatorBackendAnnotation,
        VerilatorFlags(Seq("-Wno-WIDTH", blkMemGen0Sim))
      )) { dut =>
        dut.clock.setTimeout(0)

        val pixel = BigInt("f81f", 16)
        val axi = new AxiReadModel(
          dut = dut,
          readLatencyCycles = 5,
          beatGapCycles = 1,
          arCooldownCycles = 2,
          dataForAddress = _ => (pixel << 16) | pixel
        )
        val apb = new ApbDriver(dut, axi)
        axi.init()
        apb.init()

        dut.reset.poke(true.B)
        stepWithAxi(dut, axi, 32)
        dut.reset.poke(false.B)
        stepWithAxi(dut, axi, 16)

        val resetIrqStatus = apb.read(VGACtrlRegisters.irqStatus)
        val resetIrqEnable = apb.read(VGACtrlRegisters.irqEnable)
        val resetControl = apb.read(VGACtrlRegisters.control)
        assert(!resetIrqStatus.error && resetIrqStatus.data == 0,
          f"IRQ_STATUS reset value was 0x${resetIrqStatus.data}%x")
        assert(!resetIrqEnable.error && resetIrqEnable.data == 0,
          f"IRQ_ENABLE reset value was 0x${resetIrqEnable.data}%x")
        assert(!resetControl.error && resetControl.data == 1,
          f"CONTROL reset value was 0x${resetControl.data}%x")
        dut.io.interrupt.expect(false.B)

        val smallModeWrites = Seq(
          VGACtrlRegisters.hVisible -> SmallHVisible,
          VGACtrlRegisters.hFront -> SmallHFront,
          VGACtrlRegisters.hSync -> SmallHSync,
          VGACtrlRegisters.hBack -> SmallHBack,
          VGACtrlRegisters.vVisible -> SmallVVisible,
          VGACtrlRegisters.vFront -> SmallVFront,
          VGACtrlRegisters.vSync -> SmallVSync,
          VGACtrlRegisters.vBack -> SmallVBack,
          VGACtrlRegisters.burstCountMax -> SmallBurstCount
        ).map { case (address, value) => apb.write(address, value) }
        assert(smallModeWrites.forall(!_.error), "small timing configuration returned APB error")

        val smallFrameCycles = SmallHTotal * SmallVTotal * AxiCyclesPerVgaCycle
        val smallHsyncPeriod = SmallHTotal * AxiCyclesPerVgaCycle
        val smallModeDeadline = axi.cycleCount + HTotal.toLong * VTotal * AxiCyclesPerVgaCycle * 2
        var previousVsync = dut.io.vga.vga_vsync.peek().litToBoolean
        var previousHsync = dut.io.vga.vga_hsync.peek().litToBoolean
        var lastVsyncFalling = Option.empty[Long]
        var lastHsyncFalling = Option.empty[Long]
        var sawSmallVsyncPeriod = false
        var sawSmallHsyncPeriod = false
        while ((!sawSmallVsyncPeriod || !sawSmallHsyncPeriod) && axi.cycleCount < smallModeDeadline) {
          stepWithAxi(dut, axi, 1)
          val vsync = dut.io.vga.vga_vsync.peek().litToBoolean
          val hsync = dut.io.vga.vga_hsync.peek().litToBoolean
          if (previousVsync && !vsync) {
            lastVsyncFalling.foreach { previousFalling =>
              sawSmallVsyncPeriod ||= axi.cycleCount - previousFalling == smallFrameCycles
            }
            lastVsyncFalling = Some(axi.cycleCount)
          }
          if (previousHsync && !hsync) {
            lastHsyncFalling.foreach { previousFalling =>
              sawSmallHsyncPeriod ||= axi.cycleCount - previousFalling == smallHsyncPeriod
            }
            lastHsyncFalling = Some(axi.cycleCount)
          }
          previousVsync = vsync
          previousHsync = hsync
        }
        assert(sawSmallHsyncPeriod, "small horizontal timing was not observed with the divided VGA clock")
        assert(sawSmallVsyncPeriod, "small vertical timing was not observed with the divided VGA clock")

        val maskedStatus = apb.read(VGACtrlRegisters.irqStatus)
        assert(!maskedStatus.error && maskedStatus.data == 1,
          f"masked vblank did not set IRQ_STATUS: 0x${maskedStatus.data}%x")
        dut.io.interrupt.expect(false.B)

        assert(!apb.write(VGACtrlRegisters.irqStatus, 0).error)
        val statusAfterZero = apb.read(VGACtrlRegisters.irqStatus)
        assert(!statusAfterZero.error && statusAfterZero.data == 1,
          f"IRQ_STATUS changed after W1C zero write: 0x${statusAfterZero.data}%x")

        assert(!apb.write(VGACtrlRegisters.irqEnable, 1).error)
        assert(apb.read(VGACtrlRegisters.irqEnable).data == 1, "IRQ_ENABLE did not read back as enabled")
        dut.io.interrupt.expect(true.B)

        assert(!apb.write(VGACtrlRegisters.irqEnable, 0).error)
        dut.io.interrupt.expect(false.B)
        assert(apb.read(VGACtrlRegisters.irqStatus).data == 1,
          "masking the interrupt cleared the pending vblank status")
        assert(!apb.write(VGACtrlRegisters.irqEnable, 1).error)
        dut.io.interrupt.expect(true.B)

        assert(!apb.write(VGACtrlRegisters.irqStatus, 1).error)
        val clearedStatus = apb.read(VGACtrlRegisters.irqStatus)
        assert(!clearedStatus.error && clearedStatus.data == 0,
          f"IRQ_STATUS W1C failed: 0x${clearedStatus.data}%x")
        dut.io.interrupt.expect(false.B)

        val nextVblankDeadline = axi.cycleCount + smallFrameCycles * 2L
        var nextVblankStatus = BigInt(0)
        while (nextVblankStatus == 0 && axi.cycleCount < nextVblankDeadline) {
          stepWithAxi(dut, axi, 4)
          nextVblankStatus = apb.read(VGACtrlRegisters.irqStatus).data
        }
        assert(nextVblankStatus == 1, "the next vblank did not set IRQ_STATUS")
        dut.io.interrupt.expect(true.B)

        var sawActivePixel = false
        val activePixelDeadline = axi.cycleCount + smallFrameCycles * 2L
        while (!sawActivePixel && axi.cycleCount < activePixelDeadline) {
          stepWithAxi(dut, axi, 1)
          sawActivePixel = vgaColor(dut) != 0
        }
        assert(sawActivePixel, "enabled display did not produce a non-black pixel")
        assert(axi.acceptedAddresses.nonEmpty, "enabled display did not issue AXI reads")

        assert(!apb.write(VGACtrlRegisters.irqStatus, 1).error)
        assert(!apb.write(VGACtrlRegisters.control, 0).error)
        val disabledControl = apb.read(VGACtrlRegisters.control)
        assert(!disabledControl.error && disabledControl.data == 0,
          f"CONTROL did not read back as disabled: 0x${disabledControl.data}%x")

        stepWithAxi(dut, axi, smallFrameCycles * 2)
        val disabledStatus = apb.read(VGACtrlRegisters.irqStatus)
        assert(!disabledStatus.error && disabledStatus.data == 1,
          "vblank status stopped while display output was disabled")
        dut.io.interrupt.expect(true.B)

        val readsAtDisabledStart = axi.acceptedAddresses.size
        previousHsync = dut.io.vga.vga_hsync.peek().litToBoolean
        previousVsync = dut.io.vga.vga_vsync.peek().litToBoolean
        var hsyncFallingEdges = 0
        var vsyncFallingEdges = 0
        for (_ <- 0 until smallFrameCycles + smallHsyncPeriod) {
          stepWithAxi(dut, axi, 1)
          assert(vgaColor(dut) == 0, "RGB output was non-black while CONTROL.ENABLE was clear")
          dut.io.axi.arvalid.expect(false.B)
          val hsync = dut.io.vga.vga_hsync.peek().litToBoolean
          val vsync = dut.io.vga.vga_vsync.peek().litToBoolean
          if (previousHsync && !hsync) hsyncFallingEdges += 1
          if (previousVsync && !vsync) vsyncFallingEdges += 1
          previousHsync = hsync
          previousVsync = vsync
        }
        assert(hsyncFallingEdges >= SmallVTotal,
          s"HSYNC stopped while display was disabled: falling edges=$hsyncFallingEdges")
        assert(vsyncFallingEdges >= 1, "VSYNC stopped while display was disabled")
        assert(axi.acceptedAddresses.size == readsAtDisabledStart,
          "new AXI reads were accepted while display was disabled")

        val readsBeforeEnable = axi.acceptedAddresses.size
        assert(!apb.write(VGACtrlRegisters.control, 1).error)
        val enabledControl = apb.read(VGACtrlRegisters.control)
        assert(!enabledControl.error && enabledControl.data == 1,
          f"CONTROL did not read back as enabled: 0x${enabledControl.data}%x")

        var sawRestoredPixel = false
        var sawRestoredRead = false
        val restoreDeadline = axi.cycleCount + smallFrameCycles * 3L
        while ((!sawRestoredPixel || !sawRestoredRead) && axi.cycleCount < restoreDeadline) {
          stepWithAxi(dut, axi, 1)
          sawRestoredPixel ||= vgaColor(dut) != 0
          sawRestoredRead ||= axi.acceptedAddresses.size > readsBeforeEnable
        }
        assert(sawRestoredRead, "AXI framebuffer reads did not resume after display re-enable")
        assert(sawRestoredPixel, "RGB output did not resume after display re-enable")
      }
  }

  "VGACtrl should transfer APB configuration and apply it only at safe frame boundaries" in {
    val blkMemGen0Sim = Paths.get("src/test/resources/blk_mem_gen_0_sim.v").toAbsolutePath.toString

    test(new VGACtrlBoardLikeTestHarness(useBoardVgaClockDivider = false))
      .withAnnotations(Seq(
        VerilatorBackendAnnotation,
        VerilatorFlags(Seq("-Wno-WIDTH", blkMemGen0Sim))
      )) { dut =>
        dut.clock.setTimeout(0)

        val axi = new AxiReadModel(
          dut = dut,
          readLatencyCycles = 0,
          beatGapCycles = 0,
          arCooldownCycles = 0,
          dataForAddress = _ => BigInt(0)
        )
        val apb = new ApbDriver(dut, axi)
        axi.init()
        apb.init()

        dut.reset.poke(true.B)
        stepWithAxi(dut, axi, 16)
        dut.reset.poke(false.B)
        stepWithAxi(dut, axi, 8)

        val firstTimingWrite = apb.write(VGACtrlRegisters.hVisible, SmallHVisible)
        val secondTimingWrite = apb.write(VGACtrlRegisters.hFront, SmallHFront)
        assert(!firstTimingWrite.error && !secondTimingWrite.error)
        assert(
          secondTimingWrite.waitCycles > firstTimingWrite.waitCycles,
          s"APB write did not wait for the previous CDC request: first=${firstTimingWrite.waitCycles}, second=${secondTimingWrite.waitCycles}"
        )

        val remainingTimingWrites = Seq(
          VGACtrlRegisters.hSync -> SmallHSync,
          VGACtrlRegisters.hBack -> SmallHBack,
          VGACtrlRegisters.vVisible -> SmallVVisible,
          VGACtrlRegisters.vFront -> SmallVFront,
          VGACtrlRegisters.vSync -> SmallVSync,
          VGACtrlRegisters.vBack -> SmallVBack
        ).map { case (address, value) => apb.write(address, value) }
        assert(remainingTimingWrites.forall(!_.error), "small timing configuration returned APB error")
        stepWithAxi(dut, axi, 32)

        var elapsed = 0
        var previousHsync = dut.io.vga.vga_hsync.peek().litToBoolean
        var previousVsync = dut.io.vga.vga_vsync.peek().litToBoolean
        var lastHsyncFalling = Option.empty[Int]
        var lastVsyncFalling = Option.empty[Int]
        var defaultHorizontalPeriods = 0
        var smallHorizontalPeriods = 0
        var sawDefaultVsyncFalling = false
        var sawDefaultVsyncRising = false
        var sawSmallVerticalPeriod = false
        val timingGuard = HTotal * VTotal + SmallHTotal * SmallVTotal * 6

        while ((smallHorizontalPeriods < 2 || !sawSmallVerticalPeriod) && elapsed < timingGuard) {
          stepWithAxi(dut, axi, 1)
          elapsed += 1
          val hsync = dut.io.vga.vga_hsync.peek().litToBoolean
          val vsync = dut.io.vga.vga_vsync.peek().litToBoolean

          if (previousHsync && !hsync) {
            lastHsyncFalling.foreach { previousFalling =>
              val period = elapsed - previousFalling
              if (period == HTotal) {
                defaultHorizontalPeriods += 1
              } else if (period == SmallHTotal) {
                assert(sawDefaultVsyncRising, "small horizontal timing became active before the old frame completed")
                smallHorizontalPeriods += 1
              } else {
                assert(sawDefaultVsyncRising,
                  s"horizontal timing changed before the old frame boundary: period=$period")
              }
            }
            lastHsyncFalling = Some(elapsed)
          }

          if (previousVsync && !vsync) {
            lastVsyncFalling.foreach { previousFalling =>
              if (elapsed - previousFalling == SmallHTotal * SmallVTotal) {
                sawSmallVerticalPeriod = true
              }
            }
            lastVsyncFalling = Some(elapsed)
            sawDefaultVsyncFalling = true
          }
          if (!previousVsync && vsync && sawDefaultVsyncFalling) {
            sawDefaultVsyncRising = true
          }
          previousHsync = hsync
          previousVsync = vsync
        }

        assert(defaultHorizontalPeriods >= 500,
          s"old 800-cycle line timing ended too early after $defaultHorizontalPeriods complete periods")
        assert(smallHorizontalPeriods >= 2, "64x4 horizontal timing was not applied")
        assert(sawSmallVerticalPeriod, "64x4 vertical timing was not applied")

        var previousSmallVsync = dut.io.vga.vga_vsync.peek().litToBoolean
        var sawSmallVsyncRising = false
        var frameGuard = 0
        while (!sawSmallVsyncRising && frameGuard < SmallHTotal * SmallVTotal * 2) {
          stepWithAxi(dut, axi, 1)
          val smallVsync = dut.io.vga.vga_vsync.peek().litToBoolean
          sawSmallVsyncRising = !previousSmallVsync && smallVsync
          previousSmallVsync = smallVsync
          frameGuard += 1
        }
        assert(sawSmallVsyncRising, "small-mode VSYNC rising edge was not observed")
        stepWithAxi(dut, axi, SmallHTotal)
        val smallFrameStartCycle = axi.cycleCount
        val nextVisibleFrameBoundaryCycle = smallFrameStartCycle + SmallVVisible * SmallHTotal

        axi.acceptedAddresses.clear()
        axi.acceptedAddressCycles.clear()
        val oldLineStride = BigInt(20 * 64)
        var oldLineStartIndex = -1
        var oldLineStartAddress = BigInt(0)
        var addressGuard = 0
        while (oldLineStartIndex < 0 && addressGuard < SmallHTotal * SmallVTotal * 3) {
          val previousAddressCount = axi.acceptedAddresses.size
          stepWithAxi(dut, axi, 1)
          axi.acceptedAddresses.drop(previousAddressCount).zipWithIndex.foreach { case (address, offset) =>
            if (oldLineStartIndex < 0 && address >= FrameBase && (address - FrameBase) % oldLineStride == 0) {
              oldLineStartIndex = previousAddressCount + offset
              oldLineStartAddress = address
            }
          }
          addressGuard += 1
        }
        assert(oldLineStartIndex >= 0, "old configuration did not start another framebuffer line")

        val baseWrite = apb.write(VGACtrlRegisters.frameBaseAddr, NewFrameBase)
        val burstWrite = apb.write(VGACtrlRegisters.burstCountMax, SmallBurstCount)
        assert(!baseWrite.error && !burstWrite.error)
        assert(
          burstWrite.waitCycles > baseWrite.waitCycles,
          s"second AXI configuration write did not wait for CDC acknowledgement: first=${baseWrite.waitCycles}, second=${burstWrite.waitCycles}"
        )
        assert(axi.cycleCount < nextVisibleFrameBoundaryCycle,
          "test configuration writes crossed the next visible frame boundary")

        var firstNewAddressIndex = axi.acceptedAddresses.indexWhere(_ == NewFrameBase)
        addressGuard = 0
        while (firstNewAddressIndex < 0 && addressGuard < SmallHTotal * SmallVTotal * 6) {
          stepWithAxi(dut, axi, 1)
          firstNewAddressIndex = axi.acceptedAddresses.indexWhere(_ == NewFrameBase)
          addressGuard += 1
        }
        assert(firstNewAddressIndex >= 0, "new AXI frame base was not applied after the safe boundary")
        assert(axi.acceptedAddressCycles(firstNewAddressIndex) >= nextVisibleFrameBoundaryCycle,
          "new AXI frame base became active before the visible frame boundary")

        var burstCompletionGuard = 0
        while (axi.acceptedAddresses.size < oldLineStartIndex + 20 &&
            burstCompletionGuard < SmallHTotal * SmallVTotal * 2) {
          stepWithAxi(dut, axi, 1)
          burstCompletionGuard += 1
        }
        assert(axi.acceptedAddresses.size >= oldLineStartIndex + 20,
          "old-config AXI line did not complete within two small frames")
        val oldLineAddresses = axi.acceptedAddresses.slice(oldLineStartIndex, oldLineStartIndex + 20)
        val expectedOldLineAddresses = (0 until 20).map(index => oldLineStartAddress + index * 64)
        assert(oldLineAddresses == expectedOldLineAddresses,
          s"in-flight old line changed burst count or +64 progression: $oldLineAddresses")

        burstCompletionGuard = 0
        while (axi.acceptedAddresses.size < firstNewAddressIndex + 4 &&
            burstCompletionGuard < SmallHTotal * SmallVTotal * 2) {
          stepWithAxi(dut, axi, 1)
          burstCompletionGuard += 1
        }
        assert(axi.acceptedAddresses.size >= firstNewAddressIndex + 4,
          "new-config AXI line pair did not complete within two small frames")
        val newAddresses = axi.acceptedAddresses.slice(firstNewAddressIndex, firstNewAddressIndex + 4)
        val expectedNewAddresses = Seq(
          NewFrameBase,
          NewFrameBase + 64,
          NewFrameBase + 128,
          NewFrameBase + 192
        )
        assert(newAddresses == expectedNewAddresses,
          s"new AXI bursts did not advance by +64 and the configured 128-byte line stride: $newAddresses")
      }
  }

  "VGACtrl should start a new frame from framebuffer line 0, not line 479" in {
    val blkMemGen0Sim = Paths.get("src/test/resources/blk_mem_gen_0_sim.v").toAbsolutePath.toString

    test(new VGACtrlBoardLikeTestHarness)
      .withAnnotations(Seq(
        VerilatorBackendAnnotation,
        VerilatorFlags(Seq("-Wno-WIDTH", blkMemGen0Sim))
      )) { dut =>
        dut.clock.setTimeout(0)

        val axi = new AxiReadModel(
          dut = dut,
          readLatencyCycles = 40,
          beatGapCycles = 1,
          arCooldownCycles = 3
        )
        axi.init()
        new ApbDriver(dut, axi).init()

        dut.reset.poke(true.B)
        stepWithAxi(dut, axi, 32)
        dut.reset.poke(false.B)

        waitForVsyncRising(dut, axi)

        for (frame <- 0 until 4) {
          val firstColor = firstVisibleColorAfterVsync(dut, axi)
          assert(
            firstColor == colorForLine(0),
            f"frame $frame first visible color was 0x$firstColor%03x, expected line0 color 0x${colorForLine(0)}%03x"
          )

          val observed = sampleLineColorsFromCurrentPixel(dut, axi, firstColor, rows = 4)
          val expected = (0 until 4).map(colorForLine)
          assert(
            observed == expected,
            s"frame $frame visible line starts ${formatColors(observed)} but expected ${formatColors(expected)}"
          )
        }
      }
  }
}
