package chisel

import chisel3._
import chiseltest._
import chiseltest.simulator.VerilatorBackendAnnotation
import chisel.axiInterconnect.gpu._
import org.scalatest.freespec.AnyFreeSpec

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

private object TwoDGpuSpec {
  final case class ApbResponse(data: BigInt, error: Boolean)
  final case class AxiWrite(address: BigInt, data: BigInt, strobe: Int)
  final case class AxiBurst(address: BigInt, beats: Int)
  final case class AxiEvent(channel: String, address: BigInt, beat: Int = 0)
  private final case class ActiveRead(burst: AxiBurst, beat: Int)
  private final case class ActiveWrite(burst: AxiBurst, beat: Int)
  private final case class AddressPayload(id: BigInt, address: BigInt, length: BigInt,
    size: BigInt, burst: BigInt)
  private final case class WritePayload(data: BigInt, strobe: BigInt, last: Boolean)

  final class AxiMemoryModel(dut: TwoDGpu) {
    private val bytes = mutable.Map.empty[BigInt, Int]
    private val readResponses = mutable.Queue.empty[Int]
    private val writeResponses = mutable.Queue.empty[Int]

    private var activeRead = Option.empty[ActiveRead]
    private var activeWrite = Option.empty[ActiveWrite]
    private var pendingWriteResponse = Option.empty[Int]
    private var heldWriteAddress = Option.empty[AddressPayload]
    private var heldWriteData = Option.empty[WritePayload]
    private var heldReadAddress = Option.empty[AddressPayload]
    private var cycle = 0L
    private var backpressureEnabled = false

    val readAddresses: ArrayBuffer[BigInt] = ArrayBuffer.empty
    val readBursts: ArrayBuffer[AxiBurst] = ArrayBuffer.empty
    val writes: ArrayBuffer[AxiWrite] = ArrayBuffer.empty
    val writeBursts: ArrayBuffer[AxiBurst] = ArrayBuffer.empty
    val events: ArrayBuffer[AxiEvent] = ArrayBuffer.empty

    def initialize(): Unit = {
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
      val allowAw = !backpressureEnabled || cycle % 5 != 0
      val allowW = !backpressureEnabled || cycle % 4 != 1
      val allowAr = !backpressureEnabled || cycle % 3 != 0
      val allowR = !backpressureEnabled || cycle % 5 != 2
      val allowB = !backpressureEnabled || cycle % 4 != 3
      val awReady = activeWrite.isEmpty && pendingWriteResponse.isEmpty && allowAw
      val wReady = activeWrite.nonEmpty && pendingWriteResponse.isEmpty && allowW
      val arReady = activeRead.isEmpty && allowAr

      dut.io.axi.awready.poke(awReady.B)
      dut.io.axi.wready.poke(wReady.B)
      dut.io.axi.arready.poke(arReady.B)

      pendingWriteResponse match {
        case Some(response) if allowB =>
          dut.io.axi.bid.poke(0.U)
          dut.io.axi.bresp.poke(response.U)
          dut.io.axi.bvalid.poke(true.B)
        case _ =>
          dut.io.axi.bid.poke(0.U)
          dut.io.axi.bresp.poke(0.U)
          dut.io.axi.bvalid.poke(false.B)
      }

      activeRead match {
        case Some(read) if allowR =>
          val address = read.burst.address + read.beat * 4
          val response = readResponses.headOption.getOrElse(0)
          dut.io.axi.rid.poke(0.U)
          dut.io.axi.rdata.poke(word(address).U)
          dut.io.axi.rresp.poke(response.U)
          dut.io.axi.rlast.poke((read.beat == read.burst.beats - 1).B)
          dut.io.axi.rvalid.poke(true.B)
        case _ =>
          dut.io.axi.rid.poke(0.U)
          dut.io.axi.rdata.poke(0.U)
          dut.io.axi.rresp.poke(0.U)
          dut.io.axi.rlast.poke(false.B)
          dut.io.axi.rvalid.poke(false.B)
      }

      val awValid = dut.io.axi.awvalid.peek().litToBoolean
      val awPayload = AddressPayload(
        dut.io.axi.awid.peek().litValue,
        dut.io.axi.awaddr.peek().litValue,
        dut.io.axi.awlen.peek().litValue,
        dut.io.axi.awsize.peek().litValue,
        dut.io.axi.awburst.peek().litValue
      )
      checkHeldPayload("AW", heldWriteAddress, awValid, awPayload)
      if (awReady && awValid) {
        val burst = validateAddress("write", awPayload)
        activeWrite = Some(ActiveWrite(burst, beat = 0))
        writeBursts += burst
        events += AxiEvent("AW", burst.address)
      }
      heldWriteAddress = if (awValid && !awReady) Some(awPayload) else None

      val wValid = dut.io.axi.wvalid.peek().litToBoolean
      val wPayload = WritePayload(
        dut.io.axi.wdata.peek().litValue,
        dut.io.axi.wstrb.peek().litValue,
        dut.io.axi.wlast.peek().litToBoolean
      )
      checkHeldPayload("W", heldWriteData, wValid, wPayload)
      if (wReady && wValid) {
        val write = activeWrite.getOrElse(
          throw new AssertionError("accepted AXI W beat without an active AW"))
        val expectedLast = write.beat == write.burst.beats - 1
        assert(wPayload.last == expectedLast,
          s"AXI WLAST=${wPayload.last} at beat ${write.beat}, expected $expectedLast")
        val address = write.burst.address + write.beat * 4
        val strobe = wPayload.strobe.toInt
        writes += AxiWrite(address, wPayload.data, strobe)
        events += AxiEvent("W", address, write.beat)
        for (lane <- 0 until 4 if ((strobe >> lane) & 1) != 0) {
          setByte(address + lane, ((wPayload.data >> (lane * 8)) & 0xff).toInt)
        }
        if (expectedLast) {
          activeWrite = None
          pendingWriteResponse = Some(if (writeResponses.nonEmpty) writeResponses.dequeue() else 0)
        } else {
          activeWrite = Some(write.copy(beat = write.beat + 1))
        }
      }
      heldWriteData = if (wValid && !wReady) Some(wPayload) else None

      if (pendingWriteResponse.nonEmpty && allowB &&
        dut.io.axi.bready.peek().litToBoolean) {
        events += AxiEvent("B", 0)
        pendingWriteResponse = None
      }

      val arValid = dut.io.axi.arvalid.peek().litToBoolean
      val arPayload = AddressPayload(
        dut.io.axi.arid.peek().litValue,
        dut.io.axi.araddr.peek().litValue,
        dut.io.axi.arlen.peek().litValue,
        dut.io.axi.arsize.peek().litValue,
        dut.io.axi.arburst.peek().litValue
      )
      checkHeldPayload("AR", heldReadAddress, arValid, arPayload)
      if (arReady && arValid) {
        val burst = validateAddress("read", arPayload)
        activeRead = Some(ActiveRead(burst, beat = 0))
        readBursts += burst
        events += AxiEvent("AR", burst.address)
      }
      heldReadAddress = if (arValid && !arReady) Some(arPayload) else None

      activeRead match {
        case Some(read) if allowR && dut.io.axi.rready.peek().litToBoolean =>
          val address = read.burst.address + read.beat * 4
          readAddresses += address
          events += AxiEvent("R", address, read.beat)
          if (readResponses.nonEmpty) {
            readResponses.dequeue()
          }
          if (read.beat == read.burst.beats - 1) {
            activeRead = None
          } else {
            activeRead = Some(read.copy(beat = read.beat + 1))
          }
        case _ =>
      }
      cycle += 1
    }

    def enqueueReadResponse(response: Int): Unit = readResponses.enqueue(response)
    def enqueueReadResponses(responses: Seq[Int]): Unit = readResponses.enqueueAll(responses)
    def enqueueWriteResponse(response: Int): Unit = writeResponses.enqueue(response)
    def setBackpressure(enabled: Boolean): Unit = backpressureEnabled = enabled

    def clearTransactions(): Unit = {
      readAddresses.clear()
      readBursts.clear()
      writes.clear()
      writeBursts.clear()
      events.clear()
    }

    def setByte(address: BigInt, value: Int): Unit = bytes(address) = value & 0xff
    def byte(address: BigInt): Int = bytes.getOrElse(address, 0)

    def setPixel(base: BigInt, stride: Int, x: Int, y: Int, value: Int): Unit = {
      val address = base + y * stride + x * 2
      setByte(address, value)
      setByte(address + 1, value >> 8)
    }

    def pixel(base: BigInt, stride: Int, x: Int, y: Int): Int = {
      val address = base + y * stride + x * 2
      byte(address) | (byte(address + 1) << 8)
    }

    private def word(address: BigInt): BigInt =
      (0 until 4).foldLeft(BigInt(0)) { (value, lane) =>
        value | (BigInt(byte(address + lane)) << (lane * 8))
      }

    private def validateAddress(channel: String, payload: AddressPayload): AxiBurst = {
      assert(payload.id == 0, s"AXI $channel ID ${payload.id} is not zero")
      assert(payload.size == 2, s"AXI $channel SIZE ${payload.size} is not 32-bit")
      assert(payload.burst == 1, s"AXI $channel BURST ${payload.burst} is not INCR")
      assert((payload.address & 3) == 0,
        f"unaligned AXI $channel address 0x${payload.address}%x")
      val beats = payload.length.toInt + 1
      assert(beats >= 1 && beats <= 16,
        s"AXI $channel burst has $beats beats, expected 1..16")
      assert((payload.address & 0xfff) + beats * 4 <= 4096,
        f"AXI $channel burst crosses 4 KiB: address=0x${payload.address}%x beats=$beats")
      AxiBurst(payload.address, beats)
    }

    private def checkHeldPayload[T](channel: String, held: Option[T],
      valid: Boolean, payload: T): Unit = {
      held.foreach { expected =>
        assert(valid, s"AXI $channel VALID dropped while stalled")
        assert(payload == expected,
          s"AXI $channel payload changed while stalled: expected=$expected actual=$payload")
      }
    }
  }

  final class Driver(dut: TwoDGpu) {
    val memory = new AxiMemoryModel(dut)

    def reset(): Unit = {
      driveApbIdle()
      memory.initialize()
      dut.reset.poke(true.B)
      step(3)
      dut.reset.poke(false.B)
      step()
      memory.clearTransactions()
    }

    def step(cycles: Int = 1): Unit = {
      for (_ <- 0 until cycles) {
        memory.tick()
        dut.clock.step()
      }
    }

    def write(address: Int, data: BigInt): ApbResponse =
      access(address, write = true, data)

    def read(address: Int): ApbResponse =
      access(address, write = false, 0)

    def status: BigInt = {
      driveApbIdle()
      dut.io.apb.paddr.poke(TwoDGpuRegisters.status.U)
      dut.io.apb.prdata.peek().litValue
    }

    def waitForCompletion(maxCycles: Int = 4096): BigInt = {
      var cycles = 0
      while ((status & 1) != 0 && cycles < maxCycles) {
        step()
        cycles += 1
      }
      assert(cycles < maxCycles, "timeout waiting for 2D_GPU completion")
      status
    }

    def configure(
      srcAddress: BigInt = 0,
      dstAddress: BigInt,
      srcStride: Int = 0,
      dstStride: Int,
      srcX: Int = 0,
      srcY: Int = 0,
      dstX: Int,
      dstY: Int,
      width: Int,
      height: Int,
      foreground: Int = 0,
      background: Int = 0
    ): Unit = {
      val writes = Seq(
        TwoDGpuRegisters.srcAddress -> srcAddress,
        TwoDGpuRegisters.dstAddress -> dstAddress,
        TwoDGpuRegisters.srcStride -> BigInt(srcStride),
        TwoDGpuRegisters.dstStride -> BigInt(dstStride),
        TwoDGpuRegisters.srcXy -> xy(srcX, srcY),
        TwoDGpuRegisters.dstXy -> xy(dstX, dstY),
        TwoDGpuRegisters.size -> xy(width, height),
        TwoDGpuRegisters.foreground -> BigInt(foreground),
        TwoDGpuRegisters.background -> BigInt(background)
      )
      writes.foreach { case (address, value) =>
        assert(!write(address, value).error, f"configuration write 0x$address%x failed")
      }
    }

    def start(command: Int): Unit =
      assert(!write(TwoDGpuRegisters.command, command).error, s"command $command returned APB error")

    private def access(address: Int, write: Boolean, data: BigInt): ApbResponse = {
      dut.io.apb.psel.poke(true.B)
      dut.io.apb.penable.poke(false.B)
      dut.io.apb.pwrite.poke(write.B)
      dut.io.apb.paddr.poke(address.U)
      dut.io.apb.pwdata.poke(data.U)
      step()

      dut.io.apb.penable.poke(true.B)
      dut.io.apb.pready.expect(true.B)
      val response = ApbResponse(
        dut.io.apb.prdata.peek().litValue,
        dut.io.apb.pslverr.peek().litToBoolean
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
  }

  def xy(x: Int, y: Int): BigInt = (BigInt(y) << 16) | (x & 0xffff)

  def expectSuccess(driver: Driver): Unit = {
    val status = driver.waitForCompletion()
    assert(status == 2, f"expected DONE, got status 0x$status%x")
  }

  def expectError(driver: Driver): Unit = {
    val status = driver.waitForCompletion()
    assert(status == 6, f"expected DONE|ERROR, got status 0x$status%x")
  }
}

class TwoDGpuSpec extends AnyFreeSpec with ChiselScalatestTester {
  import TwoDGpuSpec._

  private val annotations = Seq(VerilatorBackendAnnotation)

  private def initializeSurface(memory: AxiMemoryModel, base: BigInt, stride: Int,
    columns: Int, rows: Int, seed: Int): Vector[Vector[Int]] = {
    val values = Vector.tabulate(rows, columns) { (y, x) =>
      (seed + y * 0x101 + x) & 0xffff
    }
    for (y <- values.indices; x <- values(y).indices) {
      memory.setPixel(base, stride, x, y, values(y)(x))
    }
    values
  }

  private def copyExpected(initial: Vector[Vector[Int]], srcX: Int, srcY: Int,
    dstX: Int, dstY: Int, width: Int, height: Int): Vector[Vector[Int]] =
    Vector.tabulate(initial.length, initial.head.length) { (y, x) =>
      if (y >= dstY && y < dstY + height && x >= dstX && x < dstX + width) {
        initial(srcY + y - dstY)(srcX + x - dstX)
      } else {
        initial(y)(x)
      }
    }

  private def verifySurface(memory: AxiMemoryModel, base: BigInt, stride: Int,
    expected: Seq[Seq[Int]], description: String): Unit = {
    for (y <- expected.indices; x <- expected(y).indices) {
      assert(memory.pixel(base, stride, x, y) == expected(y)(x),
        s"$description differs at ($x,$y)")
    }
  }

  "FILL_RECT handles every start and width parity across rows without touching adjacent pixels" in {
    test(new TwoDGpu).withAnnotations(annotations) { dut =>
      dut.clock.setTimeout(0)
      val driver = new Driver(dut)
      driver.reset()

      val stride = 18
      val color = 0x5a3c
      val cases = Seq(
        ("even start, even width", 2, 4),
        ("even start, odd width", 2, 3),
        ("odd start, even width", 1, 4),
        ("odd start, odd width", 1, 3)
      )

      cases.zipWithIndex.foreach { case ((description, dstX, width), index) =>
        val base = BigInt(0x1000 + index * 0x100)
        for (y <- 0 until 4; x <- 0 until 9) {
          driver.memory.setPixel(base, stride, x, y, 0x1000 + y * 0x100 + x)
        }
        driver.memory.clearTransactions()
        driver.configure(
          dstAddress = base,
          dstStride = stride,
          dstX = dstX,
          dstY = 1,
          width = width,
          height = 2,
          foreground = color
        )
        driver.start(TwoDGpuCommands.fillRect)
        assert((driver.status & 1) == 1, s"$description did not enter BUSY")
        expectSuccess(driver)

        for (y <- 0 until 4; x <- 0 until 9) {
          val inside = y >= 1 && y < 3 && x >= dstX && x < dstX + width
          val expected = if (inside) color else 0x1000 + y * 0x100 + x
          assert(
            driver.memory.pixel(base, stride, x, y) == expected,
            s"$description changed pixel ($x,$y) incorrectly"
          )
        }
        val expectedWriteCount = (1 until 3).map { y =>
          val startsInUpperHalfword = ((base + y * stride + dstX * 2) >> 1 & 1).toInt
          (width + startsInUpperHalfword + 1) / 2
        }.sum
        assert(driver.memory.writes.size == expectedWriteCount, s"$description used unexpected write count")
      }
    }
  }

  "FILL_RECT emits bounded bursts at 1, 31, 32, and 33 pixel boundaries" in {
    test(new TwoDGpu).withAnnotations(annotations) { dut =>
      dut.clock.setTimeout(0)
      val driver = new Driver(dut)
      driver.reset()

      final case class FillCase(description: String, dstX: Int, width: Int,
        expectedBeats: Seq[Int], firstStrobe: Int, lastStrobe: Int)
      val cases = Seq(
        FillCase("lower halfword width 1", 0, 1, Seq(1), 0x3, 0x3),
        FillCase("upper halfword width 1", 1, 1, Seq(1), 0xc, 0xc),
        FillCase("upper halfword width 31", 1, 31, Seq(16), 0xc, 0xf),
        FillCase("lower halfword width 32", 0, 32, Seq(16), 0xf, 0xf),
        FillCase("upper halfword width 32", 1, 32, Seq(16, 1), 0xc, 0x3),
        FillCase("lower halfword width 33", 0, 33, Seq(16, 1), 0xf, 0x3)
      )
      val stride = 80
      val color = 0xa55a

      cases.zipWithIndex.foreach { case (fillCase, index) =>
        val base = BigInt(0x10000 + index * 0x1000)
        val initial = initializeSurface(driver.memory, base, stride, 40, 1, 0x1000 + index * 0x100)
        driver.memory.clearTransactions()
        driver.configure(
          dstAddress = base, dstStride = stride,
          dstX = fillCase.dstX, dstY = 0,
          width = fillCase.width, height = 1,
          foreground = color
        )
        driver.start(TwoDGpuCommands.fillRect)
        expectSuccess(driver)

        for (x <- initial.head.indices) {
          val inside = x >= fillCase.dstX && x < fillCase.dstX + fillCase.width
          val expected = if (inside) color else initial.head(x)
          assert(driver.memory.pixel(base, stride, x, 0) == expected,
            s"${fillCase.description} changed pixel $x incorrectly")
        }
        assert(driver.memory.writeBursts.map(_.beats).toSeq == fillCase.expectedBeats,
          s"${fillCase.description} used unexpected bursts: ${driver.memory.writeBursts}")
        assert(driver.memory.writes.head.strobe == fillCase.firstStrobe,
          s"${fillCase.description} used wrong first WSTRB")
        assert(driver.memory.writes.last.strobe == fillCase.lastStrobe,
          s"${fillCase.description} used wrong last WSTRB")
        assert(driver.memory.readBursts.isEmpty)
      }
    }
  }

  "FILL_RECT splits a burst at the 4 KiB boundary" in {
    test(new TwoDGpu).withAnnotations(annotations) { dut =>
      dut.clock.setTimeout(0)
      val driver = new Driver(dut)
      driver.reset()

      val base = BigInt(0x0ffa)
      val stride = 80
      val width = 34
      val color = 0x6b4d
      val initial = initializeSurface(driver.memory, base, stride, 40, 1, 0x2400)
      driver.memory.setByte(0x0ff8, 0x5a)
      driver.memory.setByte(0x0ff9, 0xa5)
      driver.memory.clearTransactions()
      driver.configure(
        dstAddress = base, dstStride = stride,
        dstX = 0, dstY = 0, width = width, height = 1,
        foreground = color
      )
      driver.start(TwoDGpuCommands.fillRect)
      expectSuccess(driver)

      for (x <- initial.head.indices) {
        val expected = if (x < width) color else initial.head(x)
        assert(driver.memory.pixel(base, stride, x, 0) == expected,
          s"4 KiB fill changed pixel $x incorrectly")
      }
      assert(driver.memory.byte(0x0ff8) == 0x5a && driver.memory.byte(0x0ff9) == 0xa5,
        "4 KiB fill changed the lower halfword before the rectangle")
      assert(driver.memory.writeBursts.toSeq == Seq(AxiBurst(0x0ff8, 2), AxiBurst(0x1000, 16)),
        s"unexpected 4 KiB split: ${driver.memory.writeBursts}")
    }
  }

  "COPY_AREA preserves non-overlap and memmove ordering for horizontal and vertical overlap" in {
    test(new TwoDGpu).withAnnotations(annotations) { dut =>
      dut.clock.setTimeout(0)
      val driver = new Driver(dut)
      driver.reset()

      val stride = 16
      val srcBase = BigInt(0x2000)
      val dstBase = BigInt(0x3000)
      for (y <- 0 until 4; x <- 0 until 8) {
        driver.memory.setPixel(srcBase, stride, x, y, 0x100 + y * 16 + x)
        driver.memory.setPixel(dstBase, stride, x, y, 0x700 + y * 16 + x)
      }
      val sourceSnapshot = Seq.tabulate(2, 3) { (y, x) =>
        driver.memory.pixel(srcBase, stride, x + 1, y + 1)
      }
      driver.memory.clearTransactions()
      driver.configure(
        srcAddress = srcBase,
        dstAddress = dstBase,
        srcStride = stride,
        dstStride = stride,
        srcX = 1,
        srcY = 1,
        dstX = 2,
        dstY = 0,
        width = 3,
        height = 2
      )
      driver.start(TwoDGpuCommands.copyArea)
      expectSuccess(driver)
      for (y <- 0 until 2; x <- 0 until 3) {
        assert(driver.memory.pixel(dstBase, stride, x + 2, y) == sourceSnapshot(y)(x))
      }
      assert(driver.memory.readAddresses.size == 4 && driver.memory.writes.size == 4)
      assert(driver.memory.readBursts.size == 2 && driver.memory.writeBursts.size == 2)

      def initializeSurface(base: BigInt, rows: Int): Vector[Vector[Int]] = {
        val values = Vector.tabulate(rows, 8)((y, x) => y * 0x100 + x)
        for (y <- values.indices; x <- values(y).indices) {
          driver.memory.setPixel(base, stride, x, y, values(y)(x))
        }
        values
      }

      def verifySurface(base: BigInt, expected: Seq[Seq[Int]]): Unit = {
        for (y <- expected.indices; x <- expected(y).indices) {
          assert(
            driver.memory.pixel(base, stride, x, y) == expected(y)(x),
            s"surface 0x${base.toString(16)} differs at ($x,$y)"
          )
        }
      }

      val rightBase = BigInt(0x4000)
      val rightInitial = initializeSurface(rightBase, 1)
      val rightExpected = rightInitial.updated(0, rightInitial(0).patch(3, rightInitial(0).slice(1, 5), 4))
      driver.memory.clearTransactions()
      driver.configure(
        srcAddress = rightBase, dstAddress = rightBase,
        srcStride = stride, dstStride = stride,
        srcX = 1, srcY = 0, dstX = 3, dstY = 0, width = 4, height = 1
      )
      driver.start(TwoDGpuCommands.copyArea)
      expectSuccess(driver)
      verifySurface(rightBase, rightExpected)
      assert(driver.memory.readBursts.size == 1 && driver.memory.writeBursts.size == 1)

      val leftBase = BigInt(0x4100)
      val leftInitial = initializeSurface(leftBase, 1)
      val leftExpected = leftInitial.updated(0, leftInitial(0).patch(1, leftInitial(0).slice(3, 7), 4))
      driver.memory.clearTransactions()
      driver.configure(
        srcAddress = leftBase, dstAddress = leftBase,
        srcStride = stride, dstStride = stride,
        srcX = 3, srcY = 0, dstX = 1, dstY = 0, width = 4, height = 1
      )
      driver.start(TwoDGpuCommands.copyArea)
      expectSuccess(driver)
      verifySurface(leftBase, leftExpected)
      assert(driver.memory.readBursts.size == 1 && driver.memory.writeBursts.size == 1)

      val downBase = BigInt(0x4200)
      val downInitial = initializeSurface(downBase, 5)
      val downExpected = downInitial.zipWithIndex.map { case (row, y) =>
        if (y >= 1 && y < 4) row.patch(2, downInitial(y - 1).slice(2, 5), 3) else row
      }
      driver.memory.clearTransactions()
      driver.configure(
        srcAddress = downBase, dstAddress = downBase,
        srcStride = stride, dstStride = stride,
        srcX = 2, srcY = 0, dstX = 2, dstY = 1, width = 3, height = 3
      )
      driver.start(TwoDGpuCommands.copyArea)
      expectSuccess(driver)
      verifySurface(downBase, downExpected)
      assert(driver.memory.readAddresses.head > driver.memory.readAddresses.last,
        "downward-overlap copy did not process bottom-up")
    }
  }

  "COPY_AREA realigns all source and destination halfword phases across 30-pixel chunks" in {
    test(new TwoDGpu).withAnnotations(annotations) { dut =>
      dut.clock.setTimeout(0)
      val driver = new Driver(dut)
      driver.reset()
      driver.memory.setBackpressure(true)

      val stride = 160
      val columns = 80
      val rows = 4
      val width = 61
      val height = 2
      val phaseCases = Seq(
        ("lower to lower", 0, 0),
        ("lower to upper", 0, 1),
        ("upper to lower", 1, 0),
        ("upper to upper", 1, 1)
      )

      phaseCases.zipWithIndex.foreach { case ((description, srcX, dstX), index) =>
        val srcBase = BigInt(0x20000 + index * 0x10000)
        val dstBase = srcBase + 0x8000
        val srcInitial = initializeSurface(driver.memory, srcBase, stride, columns, rows,
          0x1000 + index * 0x1000)
        val dstInitial = initializeSurface(driver.memory, dstBase, stride, columns, rows,
          0x8000 + index * 0x1000)
        val expected = Vector.tabulate(rows, columns) { (y, x) =>
          if (y >= 1 && y < 1 + height && x >= dstX && x < dstX + width) {
            srcInitial(1 + y - 1)(srcX + x - dstX)
          } else {
            dstInitial(y)(x)
          }
        }

        driver.memory.clearTransactions()
        driver.configure(
          srcAddress = srcBase, dstAddress = dstBase,
          srcStride = stride, dstStride = stride,
          srcX = srcX, srcY = 1, dstX = dstX, dstY = 1,
          width = width, height = height
        )
        driver.start(TwoDGpuCommands.copyArea)
        expectSuccess(driver)

        verifySurface(driver.memory, srcBase, stride, srcInitial,
          s"$description source")
        verifySurface(driver.memory, dstBase, stride, expected, description)
        val sourceChunkBeats = Seq.fill(2)(if (srcX == 0) 15 else 16) :+ 1
        val destinationChunkBeats = Seq.fill(2)(if (dstX == 0) 15 else 16) :+ 1
        assert(driver.memory.readBursts.map(_.beats).toSeq ==
          Seq.fill(height)(sourceChunkBeats).flatten,
          s"$description used unexpected read bursts: ${driver.memory.readBursts}")
        assert(driver.memory.writeBursts.map(_.beats).toSeq ==
          Seq.fill(height)(destinationChunkBeats).flatten,
          s"$description used unexpected write bursts: ${driver.memory.writeBursts}")
        val addressChannels = driver.memory.events.iterator
          .map(_.channel).filter(channel => channel == "AR" || channel == "AW").toSeq
        assert(addressChannels == Seq.fill(height * 3)(Seq("AR", "AW")).flatten,
          s"$description issued a write before fully buffering its source chunk")
      }
    }
  }

  "COPY_AREA preserves horizontal and vertical overlap across buffered chunks" in {
    test(new TwoDGpu).withAnnotations(annotations) { dut =>
      dut.clock.setTimeout(0)
      val driver = new Driver(dut)
      driver.reset()

      final case class OverlapCase(description: String, srcX: Int, srcY: Int,
        dstX: Int, dstY: Int, width: Int, height: Int, backward: Boolean)
      val cases = Seq(
        OverlapCase("right", 0, 0, 3, 0, 61, 1, backward = true),
        OverlapCase("left", 3, 0, 0, 0, 61, 1, backward = false),
        OverlapCase("down", 1, 0, 1, 1, 61, 3, backward = true),
        OverlapCase("up", 1, 1, 1, 0, 61, 3, backward = false)
      )
      val stride = 160
      val columns = 80
      val rows = 6

      cases.zipWithIndex.foreach { case (overlap, index) =>
        val base = BigInt(0x80000 + index * 0x10000)
        val initial = initializeSurface(driver.memory, base, stride, columns, rows,
          0x2000 + index * 0x1000)
        val expected = copyExpected(initial,
          overlap.srcX, overlap.srcY, overlap.dstX, overlap.dstY,
          overlap.width, overlap.height)
        driver.memory.clearTransactions()
        driver.configure(
          srcAddress = base, dstAddress = base,
          srcStride = stride, dstStride = stride,
          srcX = overlap.srcX, srcY = overlap.srcY,
          dstX = overlap.dstX, dstY = overlap.dstY,
          width = overlap.width, height = overlap.height
        )
        driver.start(TwoDGpuCommands.copyArea)
        expectSuccess(driver)

        verifySurface(driver.memory, base, stride, expected, overlap.description)
        val expectedChunks = overlap.height * 3
        assert(driver.memory.readBursts.size == expectedChunks &&
          driver.memory.writeBursts.size == expectedChunks,
          s"${overlap.description} used unexpected chunk count")
        val addressChannels = driver.memory.events.iterator
          .map(_.channel).filter(channel => channel == "AR" || channel == "AW").toSeq
        assert(addressChannels == Seq.fill(expectedChunks)(Seq("AR", "AW")).flatten,
          s"${overlap.description} did not read each chunk before writing it")
        if (overlap.backward) {
          assert(driver.memory.readBursts.head.address > driver.memory.readBursts.last.address,
            s"${overlap.description} did not process chunks backward")
        } else {
          assert(driver.memory.readBursts.head.address < driver.memory.readBursts.last.address,
            s"${overlap.description} did not process chunks forward")
        }
      }
    }
  }

  "COPY_AREA splits source and destination bursts independently at 4 KiB boundaries" in {
    test(new TwoDGpu).withAnnotations(annotations) { dut =>
      dut.clock.setTimeout(0)
      val driver = new Driver(dut)
      driver.reset()

      val srcBase = BigInt(0x20ff2)
      val dstBase = BigInt(0x31ff8)
      val stride = 160
      val columns = 70
      val width = 61
      val srcInitial = initializeSurface(driver.memory, srcBase, stride, columns, 1, 0x3100)
      val dstInitial = initializeSurface(driver.memory, dstBase, stride, columns, 1, 0x9100)
      val expected = Vector(dstInitial.head.patch(0, srcInitial.head.take(width), width))
      driver.memory.clearTransactions()
      driver.configure(
        srcAddress = srcBase, dstAddress = dstBase,
        srcStride = stride, dstStride = stride,
        srcX = 0, srcY = 0, dstX = 0, dstY = 0,
        width = width, height = 1
      )
      driver.start(TwoDGpuCommands.copyArea)
      expectSuccess(driver)

      verifySurface(driver.memory, srcBase, stride, srcInitial, "4 KiB source")
      verifySurface(driver.memory, dstBase, stride, expected, "4 KiB destination")
      assert(driver.memory.readBursts.size > 3 && driver.memory.writeBursts.size > 3,
        "4 KiB limits did not split the nominal 30-pixel chunks")
      assert(driver.memory.readBursts.exists { burst =>
        (burst.address & 0xfff) + burst.beats * 4 == 4096
      }, "no source burst ended at the 4 KiB boundary")
      assert(driver.memory.writeBursts.exists { burst =>
        (burst.address & 0xfff) + burst.beats * 4 == 4096
      }, "no destination burst ended at the 4 KiB boundary")
    }
  }

  "COPY_AREA preserves a backward copy whose destination ends at 2^32" in {
    test(new TwoDGpu).withAnnotations(annotations) { dut =>
      dut.clock.setTimeout(0)
      val driver = new Driver(dut)
      driver.reset()

      val base = BigInt("ffffff80", 16)
      val stride = 128
      val initial = initializeSurface(driver.memory, base, stride, 64, 1, 0x6300)
      val expected = copyExpected(initial,
        srcX = 0, srcY = 0, dstX = 1, dstY = 0, width = 63, height = 1)
      driver.memory.clearTransactions()
      driver.configure(
        srcAddress = base, dstAddress = base,
        srcStride = stride, dstStride = stride,
        srcX = 0, srcY = 0, dstX = 1, dstY = 0,
        width = 63, height = 1
      )
      driver.start(TwoDGpuCommands.copyArea)
      expectSuccess(driver)

      verifySurface(driver.memory, base, stride, expected, "2^32 backward boundary")
      assert(driver.memory.writeBursts.last.address == base,
        s"backward copy did not finish at the first source word: ${driver.memory.writeBursts}")
    }
  }

  "IMAGE_BLIT1 uses Linux MSB-first bit order across byte and source-stride boundaries" in {
    test(new TwoDGpu).withAnnotations(annotations) { dut =>
      dut.clock.setTimeout(0)
      val driver = new Driver(dut)
      driver.reset()

      val srcBase = BigInt(0x5001)
      val srcStride = 5
      val dstBase = BigInt(0x6000)
      val dstStride = 20
      val foreground = 0xf81f
      val background = 0x07e0
      val sourceRows = Seq(Seq(0x05, 0xa0), Seq(0x02, 0x50))
      val expectedBits = Seq(
        Seq(1, 0, 1, 1, 0, 1, 0),
        Seq(0, 1, 0, 0, 1, 0, 1)
      )
      for (row <- sourceRows.indices; byteIndex <- sourceRows(row).indices) {
        driver.memory.setByte(srcBase + (row + 1) * srcStride + byteIndex, sourceRows(row)(byteIndex))
      }
      for (y <- 0 until 4; x <- 0 until 10) {
        driver.memory.setPixel(dstBase, dstStride, x, y, 0x2222)
      }

      driver.memory.clearTransactions()
      driver.configure(
        srcAddress = srcBase,
        dstAddress = dstBase,
        srcStride = srcStride,
        dstStride = dstStride,
        srcX = 5,
        srcY = 1,
        dstX = 1,
        dstY = 1,
        width = 7,
        height = 2,
        foreground = foreground,
        background = background
      )
      driver.start(TwoDGpuCommands.imageBlit1)
      expectSuccess(driver)

      for (y <- 0 until 4; x <- 0 until 10) {
        val expected =
          if (y >= 1 && y < 3 && x >= 1 && x < 8) {
            if (expectedBits(y - 1)(x - 1) == 1) foreground else background
          } else 0x2222
        assert(driver.memory.pixel(dstBase, dstStride, x, y) == expected,
          s"IMAGE_BLIT1 pixel mismatch at ($x,$y)")
      }
      assert(driver.memory.readAddresses.toSeq == Seq(BigInt(0x5004), BigInt(0x5008), BigInt(0x500c)),
        s"unexpected glyph reads: ${driver.memory.readAddresses.map(_.toString(16)).mkString(",")}")
      assert(driver.memory.writes.size == 14)
    }
  }

  "STATUS exposes BUSY and implements DONE/ERROR W1C for completion and invalid commands" in {
    test(new TwoDGpu).withAnnotations(annotations) { dut =>
      dut.clock.setTimeout(0)
      val driver = new Driver(dut)
      driver.reset()

      assert(driver.status == 0)
      assert(driver.read(TwoDGpuRegisters.identification).data == BigInt("32444750", 16))
      assert(driver.read(TwoDGpuRegisters.capabilities).data == TwoDGpuCapabilities.value)
      assert(driver.read(TwoDGpuRegisters.version).data == TwoDGpuCapabilities.interfaceVersion)
      assert(driver.write(TwoDGpuRegisters.capabilities, 0).error)
      assert(driver.write(TwoDGpuRegisters.version, 0).error)
      driver.configure(
        dstAddress = BigInt(0x7000), dstStride = 32,
        dstX = 1, dstY = 1, width = 7, height = 2, foreground = 0x1234
      )
      driver.start(TwoDGpuCommands.fillRect)
      assert(driver.status == 1, f"expected BUSY, got 0x${driver.status}%x")
      val blocked = driver.write(TwoDGpuRegisters.dstAddress, BigInt(0x7800))
      assert(blocked.error, "configuration write during BUSY was not rejected")
      assert(!driver.write(TwoDGpuRegisters.status, 7).error)
      assert((driver.status & 1) == 1, "STATUS W1C cleared BUSY")
      expectSuccess(driver)
      val successfulWriteCount = driver.memory.writes.size
      assert(driver.read(TwoDGpuRegisters.dstAddress).data == BigInt(0x7000))
      assert(!driver.write(TwoDGpuRegisters.status, 2).error)
      assert(driver.status == 0, "DONE W1C failed")

      driver.configure(
        dstAddress = BigInt(0x7000), dstStride = 32,
        dstX = 0, dstY = 0, width = 1, height = 1
      )
      driver.start(0x99)
      expectError(driver)
      assert(!driver.write(TwoDGpuRegisters.status, 4).error)
      assert(driver.status == 2, "ERROR W1C modified DONE")
      assert(!driver.write(TwoDGpuRegisters.status, 2).error)

      driver.configure(
        dstAddress = BigInt(0x7000), dstStride = 32,
        dstX = 0, dstY = 0, width = 0, height = 1
      )
      driver.start(TwoDGpuCommands.fillRect)
      expectError(driver)
      assert(!driver.write(TwoDGpuRegisters.status, 6).error)

      driver.configure(
        dstAddress = BigInt(0x7000), dstStride = 32,
        dstX = 0, dstY = 0, width = 1, height = 0
      )
      driver.start(TwoDGpuCommands.fillRect)
      expectError(driver)
      assert(driver.memory.writes.size == successfulWriteCount, "invalid commands issued additional AXI writes")
    }
  }

  "AXI RRESP and BRESP errors stop the command and set DONE|ERROR" in {
    test(new TwoDGpu).withAnnotations(annotations) { dut =>
      dut.clock.setTimeout(0)
      val driver = new Driver(dut)
      driver.reset()

      val srcBase = BigInt(0x8000)
      val dstBase = BigInt(0x8100)
      val stride = 16
      driver.memory.setPixel(srcBase, stride, 0, 0, 0xabcd)
      driver.memory.setPixel(dstBase, stride, 0, 0, 0x1357)
      driver.memory.clearTransactions()
      driver.memory.enqueueReadResponse(2)
      driver.configure(
        srcAddress = srcBase, dstAddress = dstBase,
        srcStride = stride, dstStride = stride,
        srcX = 0, srcY = 0, dstX = 0, dstY = 0, width = 2, height = 1
      )
      driver.start(TwoDGpuCommands.copyArea)
      expectError(driver)
      assert(driver.memory.readAddresses.size == 1)
      assert(driver.memory.writes.isEmpty)
      assert(driver.memory.pixel(dstBase, stride, 0, 0) == 0x1357)
      assert(!driver.write(TwoDGpuRegisters.status, 6).error)

      driver.memory.clearTransactions()
      driver.memory.enqueueWriteResponse(2)
      val errorBase = BigInt(0x9000)
      val errorStride = 160
      val errorInitial = initializeSurface(driver.memory, errorBase, errorStride, 70, 1, 0x4100)
      driver.configure(
        dstAddress = errorBase, dstStride = errorStride,
        dstX = 0, dstY = 0, width = 65, height = 1, foreground = 0xbeef
      )
      driver.start(TwoDGpuCommands.fillRect)
      expectError(driver)
      assert(driver.memory.writeBursts.size == 1,
        "BRESP error did not stop after the failing burst")
      assert(driver.memory.writes.size == 16,
        "the failing 16-beat burst did not complete before BRESP")
      for (x <- errorInitial.head.indices) {
        val expected = if (x < 32) 0xbeef else errorInitial.head(x)
        assert(driver.memory.pixel(errorBase, errorStride, x, 0) == expected,
          s"BRESP stop changed pixel $x incorrectly")
      }
      assert(driver.memory.readAddresses.isEmpty)
    }
  }

  "COPY_AREA drains a middle RRESP error before accepting the next read command" in {
    test(new TwoDGpu).withAnnotations(annotations) { dut =>
      dut.clock.setTimeout(0)
      val driver = new Driver(dut)
      driver.reset()

      val srcBase = BigInt(0xd0000)
      val dstBase = BigInt(0xd1000)
      val stride = 80
      val srcInitial = initializeSurface(driver.memory, srcBase, stride, 40, 1, 0x5200)
      val dstInitial = initializeSurface(driver.memory, dstBase, stride, 40, 1, 0xa200)
      driver.memory.enqueueReadResponses(Seq.fill(7)(0) :+ 2)
      driver.memory.clearTransactions()
      driver.configure(
        srcAddress = srcBase, dstAddress = dstBase,
        srcStride = stride, dstStride = stride,
        srcX = 1, srcY = 0, dstX = 0, dstY = 0,
        width = 30, height = 1
      )
      driver.start(TwoDGpuCommands.copyArea)
      expectError(driver)

      assert(driver.memory.readBursts.toSeq == Seq(AxiBurst(srcBase, 16)),
        s"unexpected errored read burst: ${driver.memory.readBursts}")
      assert(driver.memory.readAddresses.size == 16,
        "RRESP error did not drain the remaining read beats")
      assert(driver.memory.writeBursts.isEmpty,
        "COPY_AREA wrote a partially read chunk after RRESP error")
      verifySurface(driver.memory, srcBase, stride, srcInitial, "errored copy source")
      verifySurface(driver.memory, dstBase, stride, dstInitial, "errored copy destination")
      assert(!driver.write(TwoDGpuRegisters.status, 6).error)

      val recoverySrc = BigInt(0xe0000)
      val recoveryDst = BigInt(0xe1000)
      val recoveryStride = 16
      driver.memory.setPixel(recoverySrc, recoveryStride, 0, 0, 0x1357)
      driver.memory.setPixel(recoverySrc, recoveryStride, 1, 0, 0x2468)
      driver.memory.setPixel(recoveryDst, recoveryStride, 0, 0, 0xaaaa)
      driver.memory.setPixel(recoveryDst, recoveryStride, 1, 0, 0xbbbb)
      driver.memory.clearTransactions()
      driver.configure(
        srcAddress = recoverySrc, dstAddress = recoveryDst,
        srcStride = recoveryStride, dstStride = recoveryStride,
        srcX = 0, srcY = 0, dstX = 0, dstY = 0,
        width = 2, height = 1
      )
      driver.start(TwoDGpuCommands.copyArea)
      expectSuccess(driver)
      assert(driver.memory.pixel(recoveryDst, recoveryStride, 0, 0) == 0x1357)
      assert(driver.memory.pixel(recoveryDst, recoveryStride, 1, 0) == 0x2468)
      assert(driver.memory.readBursts.size == 1 && driver.memory.writeBursts.size == 1,
        "the command after a drained RRESP error did not use a clean AXI transaction")
    }
  }

  "commands reject stride, coordinate, address, and aliased-surface overflow before AXI" in {
    test(new TwoDGpu).withAnnotations(annotations) { dut =>
      dut.clock.setTimeout(0)
      val driver = new Driver(dut)
      driver.reset()

      val invalidFills = Seq(
        (BigInt(0x1000), 16, 6, 0, 3, 1),
        (BigInt(0x1000), 0x20000, 0xffff, 0, 2, 1),
        (BigInt("fffff000", 16), 0x1000, 0, 1, 1, 1)
      )
      invalidFills.foreach { case (base, stride, x, y, width, height) =>
        driver.configure(
          dstAddress = base, dstStride = stride,
          dstX = x, dstY = y, width = width, height = height
        )
        driver.memory.clearTransactions()
        driver.start(TwoDGpuCommands.fillRect)
        expectError(driver)
        assert(driver.memory.readAddresses.isEmpty && driver.memory.writes.isEmpty)
        assert(!driver.write(TwoDGpuRegisters.status, 6).error)
      }

      driver.configure(
        srcAddress = 0x2000, dstAddress = 0x3000,
        srcStride = 8, dstStride = 16,
        srcX = 3, srcY = 0, dstX = 0, dstY = 0,
        width = 2, height = 1
      )
      driver.memory.clearTransactions()
      driver.start(TwoDGpuCommands.copyArea)
      expectError(driver)
      assert(driver.memory.readAddresses.isEmpty && driver.memory.writes.isEmpty)
      assert(!driver.write(TwoDGpuRegisters.status, 6).error)

      driver.configure(
        srcAddress = 0x4000, dstAddress = 0x5000,
        srcStride = 1, dstStride = 16,
        srcX = 7, srcY = 0, dstX = 0, dstY = 0,
        width = 2, height = 1
      )
      driver.memory.clearTransactions()
      driver.start(TwoDGpuCommands.imageBlit1)
      expectError(driver)
      assert(driver.memory.readAddresses.isEmpty && driver.memory.writes.isEmpty)
      assert(!driver.write(TwoDGpuRegisters.status, 6).error)

      driver.configure(
        srcAddress = 0x6000, dstAddress = 0x6002,
        srcStride = 16, dstStride = 16,
        srcX = 0, srcY = 0, dstX = 0, dstY = 0,
        width = 4, height = 1
      )
      driver.memory.clearTransactions()
      driver.start(TwoDGpuCommands.copyArea)
      expectError(driver)
      assert(driver.memory.readAddresses.isEmpty && driver.memory.writes.isEmpty,
        "aliased surfaces with different bases must be rejected")
    }
  }
}
