package cpustc.lcd

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._

import scala.collection.mutable.ArrayBuffer

class LcdVideoDmaSpec extends AnyFunSuite {
  private final case class FrameBeat(data: BigInt, last: Boolean)
  private final case class ReadCommand(address: BigInt, beats: Int)

  private val parameter = LcdVideoDmaParameter()

  private lazy val compiled = SimConfig.withVerilator
    .workspacePath("/tmp/cpustc-lcd-video-dma-sim")
    .workspaceName("LcdVideoDmaSpec")
    .compile {
      val resetConfig = ClockDomainConfig(resetKind = SYNC, resetActiveLevel = HIGH)
      val axiClockDomain = ClockDomain.external(
        "axi",
        frequency = FixedFrequency(100 MHz),
        config = resetConfig
      )
      val frameClockDomain = ClockDomain.external(
        "frame",
        frequency = FixedFrequency(33 MHz),
        config = resetConfig
      )
      LcdVideoDma(parameter, axiClockDomain, frameClockDomain)
    }

  private def initialize(dut: LcdVideoDma): Unit = {
    dut.io.start #= false
    dut.io.baseAddress #= 0
    dut.io.width #= 0
    dut.io.height #= 0
    dut.io.sourceStride #= 0
    dut.io.axi.readCmd.ready #= false
    dut.io.axi.readRsp.valid #= false
    dut.io.axi.readRsp.data #= 0
    dut.io.axi.readRsp.resp #= 0
    dut.io.axi.readRsp.last #= false
    dut.io.frame.ready #= false

    dut.axiClockDomain.forkStimulus(10000, resetCycles = 1)
    dut.frameClockDomain.forkStimulus(30303, resetCycles = 1)
    dut.axiClockDomain.waitSampling(6)
    dut.frameClockDomain.waitSampling(3)

    dut.axiClockDomain.assertReset()
    dut.frameClockDomain.assertReset()
    dut.axiClockDomain.waitRisingEdge(4)
    dut.frameClockDomain.waitRisingEdge(4)
    dut.axiClockDomain.deassertReset()
    dut.frameClockDomain.deassertReset()
    dut.axiClockDomain.waitSampling(2)
    dut.frameClockDomain.waitSampling(2)
  }

  private def pulseStart(
      dut: LcdVideoDma,
      baseAddress: BigInt,
      width: Int,
      height: Int,
      sourceStride: BigInt
  ): Unit = {
    dut.io.baseAddress #= baseAddress
    dut.io.width #= width
    dut.io.height #= height
    dut.io.sourceStride #= sourceStride
    dut.io.start #= true
    dut.axiClockDomain.waitSampling()
    dut.io.start #= false
    waitUntil(dut.axiClockDomain, 16, "DMA busy assertion") {
      dut.io.busy.toBoolean
    }
  }

  private def acceptReadCommand(dut: LcdVideoDma, holdCycles: Int = 2): ReadCommand = {
    waitUntil(dut.axiClockDomain, 256, "AXI ARVALID") {
      dut.io.axi.readCmd.valid.toBoolean
    }

    val address = dut.io.axi.readCmd.addr.toBigInt
    val beats = dut.io.axi.readCmd.len.toInt + 1
    assert(beats == parameter.beatPerAccess)
    assert(dut.io.axi.readCmd.size.toInt == 2)
    assert(dut.io.axi.readCmd.prot.toInt == 2)
    assert(dut.io.axi.readCmd.cache.toInt == 15)

    for (_ <- 0 until holdCycles) {
      dut.axiClockDomain.waitSampling()
      assert(dut.io.axi.readCmd.valid.toBoolean)
      assert(dut.io.axi.readCmd.addr.toBigInt == address, "ARADDR changed under backpressure")
    }

    dut.io.axi.readCmd.ready #= true
    dut.axiClockDomain.waitSampling()
    dut.io.axi.readCmd.ready #= false
    ReadCommand(address, beats)
  }

  private def driveReadBurst(
      dut: LcdVideoDma,
      words: Seq[BigInt],
      responses: Seq[Int] = Seq.fill(4)(0)
  ): Unit = {
    assert(words.size == parameter.beatPerAccess)
    assert(responses.size == words.size)

    words.zip(responses).zipWithIndex.foreach { case ((word, response), index) =>
      dut.io.axi.readRsp.data #= word
      dut.io.axi.readRsp.resp #= response
      dut.io.axi.readRsp.last #= index == words.size - 1
      dut.io.axi.readRsp.valid #= true
      dut.axiClockDomain.waitSampling()
      assert(dut.io.axi.readRsp.ready.toBoolean, "LCD DMA deasserted AXI RREADY")
      dut.io.axi.readRsp.valid #= false
      dut.io.axi.readRsp.last #= false
      dut.axiClockDomain.waitSampling()
    }
  }

  private def collectFrame(dut: LcdVideoDma, beatCount: Int, timeoutCycles: Int = 4096): Vector[FrameBeat] = {
    val beats = ArrayBuffer.empty[FrameBeat]
    var cycles = 0
    while (beats.size < beatCount && cycles < timeoutCycles) {
      dut.frameClockDomain.waitSampling()
      if (dut.io.frame.valid.toBoolean && dut.io.frame.ready.toBoolean) {
        beats += FrameBeat(dut.io.frame.fragment.toBigInt, dut.io.frame.last.toBoolean)
      }
      cycles += 1
    }
    assert(beats.size == beatCount, s"received ${beats.size} of $beatCount frame halfwords")
    beats.toVector
  }

  private def waitUntil(
      clockDomain: ClockDomain,
      timeoutCycles: Int,
      description: String
  )(condition: => Boolean): Unit = {
    var cycles = 0
    while (!condition && cycles < timeoutCycles) {
      clockDomain.waitSampling()
      cycles += 1
    }
    assert(condition, s"timeout waiting for $description")
  }

  private def halfWordAt(byteAddress: BigInt): BigInt =
    ((byteAddress >> 1) ^ BigInt("5a5a", 16)) & 0xffff

  private def burstWords(address: BigInt): Vector[BigInt] =
    (0 until parameter.beatPerAccess).map { wordIndex =>
      val wordAddress = address + wordIndex * 4
      halfWordAt(wordAddress) | (halfWordAt(wordAddress + 2) << 16)
    }.toVector

  private def expectedReadAddresses(
      baseAddress: BigInt,
      width: Int,
      height: Int,
      sourceStride: BigInt
  ): Vector[BigInt] =
    (0 until height).flatMap { row =>
      val rowBase = baseAddress + sourceStride * row
      val alignedBase = rowBase & ~BigInt(0xf)
      val leadingHalfWords = ((rowBase - alignedBase) / 2).toInt
      val burstCount = (leadingHalfWords + width + parameter.halfWordsPerBurst - 1) /
        parameter.halfWordsPerBurst
      (0 until burstCount).map(burst => alignedBase + burst * parameter.bytesPerBurst)
    }.toVector

  private def expectedPixels(
      baseAddress: BigInt,
      width: Int,
      height: Int,
      sourceStride: BigInt
  ): Vector[BigInt] =
    (0 until height).flatMap { row =>
      val rowBase = baseAddress + sourceStride * row
      (0 until width).map(column => halfWordAt(rowBase + column * 2))
    }.toVector

  private def assertFinalLast(frame: Seq[FrameBeat]): Unit = {
    assert(frame.nonEmpty)
    assert(frame.dropRight(1).forall(!_.last))
    assert(frame.last.last)
    assert(frame.count(_.last) == 1)
  }

  private def assertRejectedRequest(
      dut: LcdVideoDma,
      baseAddress: BigInt,
      width: Int,
      height: Int,
      sourceStride: BigInt
  ): Unit = {
    dut.io.baseAddress #= baseAddress
    dut.io.width #= width
    dut.io.height #= height
    dut.io.sourceStride #= sourceStride
    dut.io.start #= true
    dut.axiClockDomain.waitSampling()
    dut.io.start #= false

    var errorSeen = false
    for (_ <- 0 until 16) {
      assert(!dut.io.busy.toBoolean, "invalid raw request asserted busy")
      assert(!dut.io.axi.readCmd.valid.toBoolean, "invalid raw request issued an AXI read")
      errorSeen = errorSeen || dut.io.error.toBoolean
      dut.axiClockDomain.waitSampling()
    }
    assert(errorSeen && dut.io.error.toBoolean, "invalid raw request did not latch error")
  }

  private def stage(name: String): Unit =
    println(s"[LcdVideoDmaSpec] t=${simTime()} $name")

  test("invalid raw requests raise error without issuing AXI reads or busy") {
    val invalidRequests = Seq(
      ("zero-width", BigInt("001000", 16), 0, 1, BigInt(0)),
      ("zero-height", BigInt("001000", 16), 3, 0, BigInt(6)),
      ("odd-base", BigInt("001001", 16), 1, 1, BigInt(0)),
      ("short-multi-line-stride", BigInt("001000", 16), 8, 2, BigInt(14))
    )

    invalidRequests.foreach { case (name, baseAddress, width, height, sourceStride) =>
      compiled.doSim(s"reject-$name") { dut =>
        SimTimeout(200000000)
        initialize(dut)
        assert(!dut.io.busy.toBoolean)
        assert(!dut.io.error.toBoolean)
        assertRejectedRequest(dut, baseAddress, width, height, sourceStride)
      }
    }
  }

  test("legacy height-one requests preserve contiguous burst addresses and halfword order") {
    compiled.doSim("legacy-one-dimensional") { dut =>
      SimTimeout(200000000)
      initialize(dut)
      dut.io.frame.ready #= true

      val transfers = Seq(
        (BigInt("012340", 16), 8),
        (BigInt("023450", 16), 24)
      )

      transfers.foreach { case (baseAddress, width) =>
        val expectedAddresses = expectedReadAddresses(baseAddress, width, height = 1, sourceStride = 0)
        val commands = ArrayBuffer.empty[ReadCommand]
        val responder = fork {
          expectedAddresses.foreach { address =>
            commands += acceptReadCommand(dut)
            driveReadBurst(dut, burstWords(address))
          }
        }

        pulseStart(dut, baseAddress, width, height = 1, sourceStride = 0)
        val frame = collectFrame(dut, width)
        responder.join()
        waitUntil(dut.axiClockDomain, 64, "legacy DMA completion") {
          !dut.io.busy.toBoolean
        }

        assert(commands.map(_.address).toVector == expectedAddresses)
        assert(commands.forall(_.beats == parameter.beatPerAccess))
        assert(frame.map(_.data) == expectedPixels(baseAddress, width, height = 1, sourceStride = 0))
        assertFinalLast(frame)
        assert(!dut.io.error.toBoolean)
      }
    }
  }

  test("multi-line stride realigns every row for all 2-byte offsets and discards head and tail padding") {
    compiled.doSim("two-dimensional-offsets") { dut =>
      SimTimeout(200000000)
      initialize(dut)
      dut.io.frame.ready #= true

      val baseAddress = BigInt("001000", 16)
      val width = 3
      val height = 8
      val sourceStride = BigInt(18)
      val rowOffsets = (0 until height).map(row => (baseAddress + sourceStride * row) & 0xf)
      val expectedAddresses = expectedReadAddresses(baseAddress, width, height, sourceStride)
      val commands = ArrayBuffer.empty[ReadCommand]

      assert(rowOffsets == Seq(0, 2, 4, 6, 8, 10, 12, 14).map(BigInt(_)))
      assert(expectedAddresses.size == 10, "offset 12 and 14 rows must each cross a burst boundary")

      val responder = fork {
        expectedAddresses.foreach { address =>
          commands += acceptReadCommand(dut, holdCycles = 1)
          driveReadBurst(dut, burstWords(address))
        }
      }

      pulseStart(dut, baseAddress, width, height, sourceStride)
      val frame = collectFrame(dut, width * height)
      responder.join()
      waitUntil(dut.axiClockDomain, 128, "two-dimensional DMA completion") {
        !dut.io.busy.toBoolean
      }

      assert(commands.map(_.address).toVector == expectedAddresses)
      assert(commands.forall(command => (command.address & 0xf) == 0))
      assert(frame.map(_.data) == expectedPixels(baseAddress, width, height, sourceStride))
      assertFinalLast(frame)
      assert(!dut.io.error.toBoolean)
      stage("two-dimensional offset coverage complete")
    }
  }

  test("frame backpressure holds payload stable and resumes a strided frame without loss") {
    compiled.doSim("frame-backpressure") { dut =>
      SimTimeout(200000000)
      initialize(dut)

      val baseAddress = BigInt("034566", 16)
      val width = 11
      val height = 3
      val sourceStride = BigInt(30)
      val expectedAddresses = expectedReadAddresses(baseAddress, width, height, sourceStride)
      val commands = ArrayBuffer.empty[ReadCommand]

      val responder = fork {
        expectedAddresses.foreach { address =>
          commands += acceptReadCommand(dut, holdCycles = 0)
          driveReadBurst(dut, burstWords(address))
        }
      }

      pulseStart(dut, baseAddress, width, height, sourceStride)
      waitUntil(dut.frameClockDomain, 256, "stalled frame valid") {
        dut.io.frame.valid.toBoolean
      }

      val heldBeat = FrameBeat(dut.io.frame.fragment.toBigInt, dut.io.frame.last.toBoolean)
      for (_ <- 0 until 6) {
        assert(dut.io.frame.valid.toBoolean)
        assert(FrameBeat(dut.io.frame.fragment.toBigInt, dut.io.frame.last.toBoolean) == heldBeat)
        dut.frameClockDomain.waitSampling()
      }

      val frame = ArrayBuffer.empty[FrameBeat]
      var stalledBeat = Option.empty[FrameBeat]
      var stalledValidCycles = 0
      var readyTransitions = 0
      var previousReady = false
      var frameCycles = 0
      val logicalPixelCount = width * height
      while (frame.size < logicalPixelCount - 1 && frameCycles < 4096) {
        val ready = frameCycles % 4 != 0 && frameCycles % 7 != 0
        dut.io.frame.ready #= ready
        sleep(1)

        if (ready != previousReady) readyTransitions += 1
        val valid = dut.io.frame.valid.toBoolean
        val observed = FrameBeat(dut.io.frame.fragment.toBigInt, dut.io.frame.last.toBoolean)
        if (valid && !ready) {
          stalledBeat.foreach(previous => assert(observed == previous, "frame payload changed under backpressure"))
          stalledBeat = Some(observed)
          stalledValidCycles += 1
        } else if (valid && ready) {
          stalledBeat.foreach(previous => assert(observed == previous, "stalled frame beat was not resumed"))
          stalledBeat = None
        } else {
          stalledBeat = None
        }

        val fire = valid && ready
        dut.frameClockDomain.waitSampling()
        if (fire) frame += observed

        previousReady = ready
        frameCycles += 1
      }

      assert(frame.size == logicalPixelCount - 1, s"received ${frame.size} non-final halfwords under ready jitter")
      assert(stalledValidCycles > 0)
      assert(readyTransitions > 4)

      dut.io.frame.ready #= false
      waitUntil(dut.frameClockDomain, 256, "backpressured final frame beat") {
        dut.io.frame.valid.toBoolean && dut.io.frame.last.toBoolean
      }
      val finalBeat = FrameBeat(dut.io.frame.fragment.toBigInt, dut.io.frame.last.toBoolean)
      for (_ <- 0 until 6) {
        assert(dut.io.frame.valid.toBoolean)
        assert(FrameBeat(dut.io.frame.fragment.toBigInt, dut.io.frame.last.toBoolean) == finalBeat)
        assert(dut.io.busy.toBoolean, "DMA busy cleared before the final frame handshake")
        dut.frameClockDomain.waitSampling()
      }
      dut.io.frame.ready #= true
      sleep(1)
      assert(dut.io.frame.valid.toBoolean && dut.io.frame.last.toBoolean)
      dut.frameClockDomain.waitSampling()
      frame += finalBeat

      responder.join()
      waitUntil(dut.axiClockDomain, 128, "backpressured DMA completion") {
        !dut.io.busy.toBoolean
      }

      assert(commands.map(_.address).toVector == expectedAddresses)
      assert(frame.map(_.data).toVector == expectedPixels(baseAddress, width, height, sourceStride))
      assertFinalLast(frame.toSeq)
      assert(!dut.io.error.toBoolean)
    }
  }

  test("AXI error latches across a multi-line frame and clears on the next start") {
    compiled.doSim("error-latch") { dut =>
      SimTimeout(200000000)
      initialize(dut)
      dut.io.frame.ready #= true

      val failedBase = BigInt("045672", 16)
      val failedWidth = 5
      val failedHeight = 2
      val failedStride = BigInt(18)
      val failedAddresses = expectedReadAddresses(failedBase, failedWidth, failedHeight, failedStride)
      val failedResponder = fork {
        failedAddresses.zipWithIndex.foreach { case (address, commandIndex) =>
          acceptReadCommand(dut, holdCycles = 0)
          val responses = if (commandIndex == 0) Seq(0, 0, 0, 2) else Seq.fill(4)(0)
          driveReadBurst(dut, burstWords(address), responses)
        }
      }

      pulseStart(dut, failedBase, failedWidth, failedHeight, failedStride)
      val failedFrame = collectFrame(dut, failedWidth * failedHeight)
      failedResponder.join()
      waitUntil(dut.axiClockDomain, 32, "AXI error latch") {
        dut.io.error.toBoolean
      }
      waitUntil(dut.axiClockDomain, 128, "errored DMA completion") {
        !dut.io.busy.toBoolean
      }
      assert(failedFrame.map(_.data) == expectedPixels(failedBase, failedWidth, failedHeight, failedStride))
      assertFinalLast(failedFrame)
      dut.axiClockDomain.waitSampling(8)
      assert(dut.io.error.toBoolean, "AXI error was not sticky while idle")

      val cleanBase = BigInt("05678e", 16)
      val cleanWidth = 1
      val cleanAddresses = expectedReadAddresses(cleanBase, cleanWidth, height = 1, sourceStride = 0)
      val cleanResponder = fork {
        cleanAddresses.foreach { address =>
          acceptReadCommand(dut, holdCycles = 0)
          driveReadBurst(dut, burstWords(address))
        }
      }

      pulseStart(dut, cleanBase, cleanWidth, height = 1, sourceStride = 0)
      waitUntil(dut.axiClockDomain, 4, "AXI error clear on restart") {
        !dut.io.error.toBoolean
      }
      val cleanFrame = collectFrame(dut, cleanWidth)
      cleanResponder.join()
      waitUntil(dut.axiClockDomain, 64, "clean DMA completion") {
        !dut.io.busy.toBoolean
      }

      assert(cleanFrame.map(_.data) == expectedPixels(cleanBase, cleanWidth, height = 1, sourceStride = 0))
      assertFinalLast(cleanFrame)
      assert(!dut.io.error.toBoolean)
    }
  }
}
