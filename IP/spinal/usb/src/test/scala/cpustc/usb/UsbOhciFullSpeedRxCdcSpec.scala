package cpustc.usb

import cpustc.usb.sim.Usb3500UtmiAgent
import cpustc.usb.utmi.{UsbHubLsFsToUtmiTiming, UsbOhciAxi4Apb3Utmi}
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._
import spinal.lib.bus.amba3.apb.sim.Apb3Driver
import spinal.lib.com.usb.ohci.{OhciPortParameter, UsbOhciParameter}

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

class UsbOhciFullSpeedRxCdcSpec extends AnyFunSuite {
  private val hcControl = 0x04
  private val hcCommandStatus = 0x08
  private val hcHcca = 0x18
  private val hcControlHeadEd = 0x20
  private val hcFmInterval = 0x34
  private val hcPeriodicStart = 0x40
  private val hcRhStatus = 0x50
  private val hcRhPortStatus = 0x54

  private val currentConnectStatus = BigInt(1) << 0
  private val portEnableStatus = BigInt(1) << 1
  private val setPortReset = BigInt(1) << 4
  private val controlListEnable = BigInt(1) << 4
  private val usbOperational = BigInt(2) << 6
  private val controlListFilled = BigInt(1) << 1
  private val hostControllerReset = BigInt(1)
  private val operationalScheduleControl = BigInt(0x0f)

  private val hccaBase = 0x1000L
  private val edBase = 0x2000L
  private val tdBase = 0x2010L
  private val tailTdBase = 0x2020L
  private val bufferBase = 0x3000L
  private val frameInterval = 0x27782edfL
  private val periodicStart = 0x2a2f

  private val ctrlPeriodPs = 30303
  private val utmiPeriodPs = 16666
  private val responsePhases = 0 until 20
  private val ccNoError = 0
  private val ccNotAccessed = 0xf
  private val inPid = 0x69
  private val ackPid = 0xd2

  private val descriptor = Vector(
    0x12,
    0x01,
    0x00,
    0x02,
    0x00,
    0x00,
    0x00,
    0x08,
    0xd1,
    0x12,
    0xd1,
    0x10,
    0x11,
    0x01,
    0x01,
    0x02,
    0x00,
    0x01
  )
  private val dataPids = Vector(0x4b, 0xc3, 0x4b)
  private val payloads = descriptor.grouped(8).map(_.toVector).toVector
  private val expectedCrcBytes = Vector(Vector(0x57, 0xe7), Vector(0x46, 0x4a), Vector(0x3f, 0x8f))
  private val responsePackets = payloads.zip(dataPids).map { case (payload, pid) =>
    val crc = usbCrc16(payload)
    Vector(pid) ++ payload ++ Vector(crc & 0xff, (crc >> 8) & 0xff)
  }

  private final case class RxPacketTiming(validOffsets: Vector[Int], activeFallOffset: Int) {
    require(validOffsets.nonEmpty)
    require(validOffsets == validOffsets.sorted.distinct)
    require(validOffsets.head >= 0)
    require(activeFallOffset > validOffsets.last)
  }

  private val tailOnlyTwoCycleTiming = regularRxTiming(responsePackets.last.size, tailHoldCycles = 2)
  private val trial4MeasuredTiming = RxPacketTiming(
    validOffsets = Vector(45, 85, 125, 205, 209),
    activeFallOffset = 211
  )

  private val parameter = UsbOhciParameter(
    noPowerSwitching = false,
    powerSwitchingMode = false,
    noOverCurrentProtection = true,
    powerOnToPowerGoodTime = 10,
    dataWidth = 32,
    portsConfig = Seq(OhciPortParameter()),
    dmaLengthWidth = 6,
    fifoBytes = 2048,
    storageBursts = 4
  )

  private val timing = UsbHubLsFsToUtmiTiming(
    attachDebounceCycles = 8,
    disconnectCycles = 128,
    resetCycles = 16,
    resumeCycles = 16
  )

  private final case class PhaseResult(
      phase: Int,
      retired: Boolean,
      completionCode: Int,
      completedBytes: Int,
      currentBufferPointer: BigInt,
      responses: Int,
      inTokens: Int,
      finalTdControl: BigInt,
      finalEdHead: BigInt,
      dmaPayload: Vector[Int],
      rxEventOverflow: Boolean,
      rxEventCollision: Boolean,
      injectedRxEvents: Vector[String],
      frontRxEvents: Vector[String]
  ) {
    def passed: Boolean =
      retired &&
        completionCode == ccNoError &&
        completedBytes == descriptor.size &&
        currentBufferPointer == 0 &&
        responses == responsePackets.size &&
        (finalEdHead & 1) == 0 &&
        dmaPayload == descriptor &&
        !rxEventOverflow &&
        !rxEventCollision

    def summary: String =
      f"phase=$phase%02d retired=$retired cc=$completionCode completedBytes=$completedBytes " +
        f"cbp=0x$currentBufferPointer%08x " +
        s"responses=$responses inTokens=$inTokens tdControl=0x${finalTdControl.toString(16)} " +
        s"edHead=0x${finalEdHead.toString(16)} rxEventOverflow=$rxEventOverflow " +
        s"rxEventCollision=$rxEventCollision"
  }

  test("full-speed EP0 IN preserves the 18-byte packet tail across CtrlCc RX CDC phases") {
    assert(payloads.map(_.size) == Vector(8, 8, 2))
    assert(responsePackets.map(_.takeRight(2)) == expectedCrcBytes)
    println(
      s"clock-config ctrlPeriodPs=$ctrlPeriodPs dmaPeriodPs=$ctrlPeriodPs " +
        s"dmaSharesControlClockDomain=true utmiPeriodPs=$utmiPeriodPs"
    )
    println(s"descriptor=${hex(descriptor)}")
    responsePackets.zipWithIndex.foreach { case (packet, index) =>
      println(s"response-packet index=$index bytes=${hex(packet)}")
    }

    val compiled = compileDut()
    val results = ArrayBuffer.empty[PhaseResult]
    responsePhases.foreach { phase =>
      compiled.doSim { dut =>
        val result = runPhase(dut, phase)
        results += result
        println(s"phase-result ${result.summary}")
      }
    }

    val failures = results.filterNot(_.passed)
    failures.foreach { result =>
      println(
        s"hardware-signature ${result.summary} expectedDma=${hex(descriptor)} " +
          s"actualDma=${hex(result.dmaPayload)}"
      )
    }
    assert(results.size == responsePhases.size, s"only ${results.size} phases reached a TD terminal state")
    assert(
      failures.isEmpty,
      failures.map(_.summary).mkString("legal full-speed input failed: ", "; ", "")
    )
  }

  test("full-speed EP0 IN preserves a PID valid on the RXACTIVE start cycle across CtrlCc RX CDC phases") {
    println(
      s"start-data-clock-config ctrlPeriodPs=$ctrlPeriodPs dmaPeriodPs=$ctrlPeriodPs " +
        s"dmaSharesControlClockDomain=true utmiPeriodPs=$utmiPeriodPs"
    )
    println(s"start-data-descriptor=${hex(descriptor)}")
    responsePackets.zipWithIndex.foreach { case (packet, index) =>
      val packetTiming = regularRxTiming(packet.size, tailHoldCycles = 5, firstValidOffset = 0)
      println(
        s"start-data-response-packet index=$index bytes=${hex(packet)} " +
          s"validOffsets=${packetTiming.validOffsets.mkString(",")}"
      )
    }

    val compiled = compileDut()
    val results = ArrayBuffer.empty[PhaseResult]
    responsePhases.foreach { phase =>
      compiled.doSim { dut =>
        val result = runPhase(
          dut,
          phase,
          firstValidOffset = 0,
          deferRetirementFailure = true
        )
        results += result
        println(s"start-data-phase-result ${result.summary} dma=${hex(result.dmaPayload)}")
      }
    }

    assert(results.size == responsePhases.size, s"only ${results.size} start-data phases completed")
    val ccCounts = results.groupMapReduce(_.completionCode)(_ => 1)(_ + _).toVector.sortBy(_._1)
    val byteCounts = results.groupMapReduce(_.completedBytes)(_ => 1)(_ + _).toVector.sortBy(_._1)
    val responseCounts = results.groupMapReduce(_.responses)(_ => 1)(_ + _).toVector.sortBy(_._1)
    println(
      s"start-data-summary ccCounts=${ccCounts.mkString("[", ",", "]")} " +
        s"completedBytesCounts=${byteCounts.mkString("[", ",", "]")} " +
        s"responseCounts=${responseCounts.mkString("[", ",", "]")} " +
        s"overflowPhases=${results.count(_.rxEventOverflow)} " +
        s"collisionPhases=${results.count(_.rxEventCollision)}"
    )

    val failures = results.filterNot(_.passed)
    failures.foreach { result =>
      println(
        s"start-data-hardware-signature ${result.summary} expectedDma=${hex(descriptor)} " +
          s"actualDma=${hex(result.dmaPayload)}"
      )
    }
    assert(
      failures.isEmpty,
      failures.map(_.summary).mkString("RXACTIVE/start-data CDC gate failed: ", "; ", "")
    )
  }

  test("full-speed EP0 IN preserves two-cycle packet tails across CtrlCc RX CDC phases") {
    val cases = Vector(
      "tail-only-2cycle" -> tailOnlyTwoCycleTiming,
      "trial4-45-40-40-80-4-2" -> trial4MeasuredTiming
    )
    assert(tailOnlyTwoCycleTiming.validOffsets == Vector(45, 85, 125, 165, 205))
    assert(tailOnlyTwoCycleTiming.activeFallOffset == 207)
    assert(trial4MeasuredTiming.validOffsets == Vector(45, 85, 125, 205, 209))
    assert(trial4MeasuredTiming.activeFallOffset == 211)

    println(
      s"short-tail-clock-config ctrlPeriodPs=$ctrlPeriodPs dmaPeriodPs=$ctrlPeriodPs " +
        s"dmaSharesControlClockDomain=true utmiPeriodPs=$utmiPeriodPs"
    )
    println(s"short-tail-packet bytes=${hex(responsePackets.last)}")
    val compiled = compileDut()
    val gateFailures = ArrayBuffer.empty[String]

    cases.foreach { case (caseName, shortPacketTiming) =>
      println(
        s"short-tail-case-start case=$caseName validOffsets=${shortPacketTiming.validOffsets.mkString(",")} " +
          s"activeFallOffset=${shortPacketTiming.activeFallOffset}"
      )
      val results = ArrayBuffer.empty[PhaseResult]
      responsePhases.foreach { phase =>
        compiled.doSim { dut =>
          val result = runPhase(
            dut,
            phase,
            finalPacketTiming = Some(shortPacketTiming),
            captureRxEvents = true
          )
          results += result
          println(
            s"short-tail-phase-result case=$caseName ${result.summary} dma=${hex(result.dmaPayload)}"
          )
          println(
            s"short-tail-injected-events case=$caseName phase=${f"$phase%02d"} " +
              result.injectedRxEvents.mkString("[", "; ", "]")
          )
          println(
            s"short-tail-front-events case=$caseName phase=${f"$phase%02d"} " +
              result.frontRxEvents.mkString("[", "; ", "]")
          )
        }
      }

      assert(results.size == responsePhases.size, s"case=$caseName only ${results.size} phases retired")
      val ccCounts = results.groupMapReduce(_.completionCode)(_ => 1)(_ + _).toVector.sortBy(_._1)
      val byteCounts = results.groupMapReduce(_.completedBytes)(_ => 1)(_ + _).toVector.sortBy(_._1)
      println(
        s"short-tail-case-summary case=$caseName ccCounts=${ccCounts.mkString("[", ",", "]")} " +
          s"completedBytesCounts=${byteCounts.mkString("[", ",", "]")}"
      )
      results.filterNot(_.passed).foreach { result =>
        gateFailures += s"case=$caseName ${result.summary}"
      }
    }

    assert(
      gateFailures.isEmpty,
      gateFailures.mkString("full-speed RX CDC repair gate failed: ", "; ", "")
    )
  }

  private def compileDut() = SimConfig.withVerilator.compile {
    val resetConfig = ClockDomainConfig(resetKind = SYNC, resetActiveLevel = HIGH)
    val ctrlCd = ClockDomain.external("ctrl", config = resetConfig)
    val dmaCd = ctrlCd
    val utmiCd = ClockDomain.external(
      "utmi",
      frequency = FixedFrequency(60 MHz),
      config = resetConfig
    )
    val dut = UsbOhciAxi4Apb3Utmi(parameter, ctrlCd, utmiCd, dmaCd, timing)
    dut.cc.input.rx.active.simPublic()
    dut.cc.input.rx.flow.valid.simPublic()
    dut.cc.input.rx.flow.data.simPublic()
    dut.cc.rxEventOverflow.simPublic()
    dut.cc.rxEventCollision.simPublic()
    dut
  }

  private def runPhase(
      dut: UsbOhciAxi4Apb3Utmi,
      phase: Int,
      finalPacketTiming: Option[RxPacketTiming] = None,
      captureRxEvents: Boolean = false,
      firstValidOffset: Int = 45,
      deferRetirementFailure: Boolean = false
  ): PhaseResult = {
    dut.frontCd.forkStimulus(ctrlPeriodPs)
    dut.backCd.forkStimulus(utmiPeriodPs)

    final case class WriteBurst(var address: Long, bytesPerBeat: Int, var beatsLeft: Int)
    final case class WriteBeat(data: BigInt, strobe: BigInt, last: Boolean)
    final case class ReadBurst(var address: Long, bytesPerBeat: Int, var beatsLeft: Int)

    val bytes = mutable.Map.empty[Long, Int]
    val writeBursts = mutable.Queue.empty[WriteBurst]
    val writeBeats = mutable.Queue.empty[WriteBeat]
    val readBursts = mutable.Queue.empty[ReadBurst]

    var pendingWriteResponses = 0
    var readBeatValid = false
    var awHandshakes = 0
    var wHandshakes = 0
    var bHandshakes = 0
    var arHandshakes = 0
    var rHandshakes = 0
    val injectedRxEvents = ArrayBuffer.empty[String]
    val frontRxEvents = ArrayBuffer.empty[String]
    var frontCycle = 0L
    var frontRxActiveLast = false

    if (captureRxEvents) {
      dut.frontCd.onSamplings {
        frontCycle += 1
        val active = dut.cc.input.rx.active.toBoolean
        val flow = dut.cc.input.rx.flow
        if (active != frontRxActiveLast) {
          frontRxEvents += s"c=$frontCycle:${if (active) "active+" else "active-"}"
        }
        if (flow.valid.toBoolean) {
          frontRxEvents += f"c=$frontCycle:valid=${flow.data.toInt & 0xff}%02x:active=${if (active) 1 else 0}"
        }
        frontRxActiveLast = active
      }
    }

    def write32(address: Long, value: BigInt): Unit = {
      for (lane <- 0 until 4) {
        bytes(address + lane) = ((value >> (lane * 8)) & 0xff).toInt
      }
    }

    def read32(address: Long): BigInt = {
      var value = BigInt(0)
      for (lane <- 0 until 4) {
        value |= BigInt(bytes.getOrElse(address + lane, 0)) << (lane * 8)
      }
      value
    }

    def readBeat(address: Long): BigInt = {
      var value = BigInt(0)
      for (lane <- 0 until parameter.dataWidth / 8) {
        value |= BigInt(bytes.getOrElse(address + lane, 0)) << (lane * 8)
      }
      value
    }

    val edControl = BigInt(8) << 16
    val tdControl =
      (BigInt(ccNotAccessed) << 28) |
        (BigInt(3) << 24) |
        (BigInt(2) << 19)

    write32(edBase, edControl)
    write32(edBase + 4, tailTdBase)
    write32(edBase + 8, tdBase)
    write32(edBase + 12, 0)

    write32(tdBase, tdControl)
    write32(tdBase + 4, bufferBase)
    write32(tdBase + 8, tailTdBase)
    write32(tdBase + 12, bufferBase + descriptor.size - 1)

    write32(tailTdBase, 0)
    write32(tailTdBase + 4, 0)
    write32(tailTdBase + 8, 0)
    write32(tailTdBase + 12, 0)

    dut.io.dma.aw.ready #= true
    dut.io.dma.w.ready #= true
    dut.io.dma.b.valid #= false
    dut.io.dma.b.resp #= 0
    dut.io.dma.ar.ready #= true
    dut.io.dma.r.valid #= false
    dut.io.dma.r.data #= 0
    dut.io.dma.r.resp #= 0
    dut.io.dma.r.last #= false

    fork {
      while (true) {
        dut.frontCd.waitSampling()

        if (pendingWriteResponses != 0 && dut.io.dma.b.ready.toBoolean) {
          pendingWriteResponses -= 1
          bHandshakes += 1
        }

        if (readBeatValid && dut.io.dma.r.ready.toBoolean) {
          val burst = readBursts.front
          burst.beatsLeft -= 1
          rHandshakes += 1
          readBeatValid = false
          if (burst.beatsLeft == 0) {
            readBursts.dequeue()
          } else {
            burst.address += burst.bytesPerBeat
          }
        }

        if (dut.io.dma.aw.valid.toBoolean && dut.io.dma.aw.ready.toBoolean) {
          awHandshakes += 1
          writeBursts.enqueue(
            WriteBurst(
              address = dut.io.dma.aw.addr.toBigInt.longValue,
              bytesPerBeat = 1 << dut.io.dma.aw.size.toInt,
              beatsLeft = dut.io.dma.aw.len.toInt + 1
            )
          )
        }

        if (dut.io.dma.w.valid.toBoolean && dut.io.dma.w.ready.toBoolean) {
          wHandshakes += 1
          writeBeats.enqueue(
            WriteBeat(
              data = dut.io.dma.w.data.toBigInt,
              strobe = dut.io.dma.w.strb.toBigInt,
              last = dut.io.dma.w.last.toBoolean
            )
          )
        }

        if (dut.io.dma.ar.valid.toBoolean && dut.io.dma.ar.ready.toBoolean) {
          arHandshakes += 1
          readBursts.enqueue(
            ReadBurst(
              address = dut.io.dma.ar.addr.toBigInt.longValue,
              bytesPerBeat = 1 << dut.io.dma.ar.size.toInt,
              beatsLeft = dut.io.dma.ar.len.toInt + 1
            )
          )
        }

        if (writeBursts.nonEmpty && writeBeats.nonEmpty) {
          val burst = writeBursts.front
          val beat = writeBeats.dequeue()
          val expectedLast = burst.beatsLeft == 1
          assert(beat.last == expectedLast, "AXI WLAST did not match AWLEN")

          for (lane <- 0 until parameter.dataWidth / 8) {
            if (((beat.strobe >> lane) & 1) != 0) {
              bytes(burst.address + lane) = ((beat.data >> (lane * 8)) & 0xff).toInt
            }
          }

          burst.beatsLeft -= 1
          if (burst.beatsLeft == 0) {
            writeBursts.dequeue()
            pendingWriteResponses += 1
          } else {
            burst.address += burst.bytesPerBeat
          }
        }

        if (!readBeatValid && readBursts.nonEmpty) {
          val burst = readBursts.front
          dut.io.dma.r.data #= readBeat(burst.address)
          dut.io.dma.r.last #= burst.beatsLeft == 1
          readBeatValid = true
        }

        dut.io.dma.b.valid #= pendingWriteResponses != 0
        dut.io.dma.ar.ready #= readBursts.isEmpty && !readBeatValid
        dut.io.dma.r.valid #= readBeatValid
      }
    }

    val utmi = new Usb3500UtmiAgent(dut.io.utmi, dut.backCd)
    utmi.initialize()
    val apb = Apb3Driver(dut.io.ctrl, dut.frontCd)
    val txPackets = ArrayBuffer.empty[Vector[Int]]
    var inTokens = 0
    var responsesSent = 0
    var acknowledgedPackets = 0

    dut.frontCd.waitSampling(10)
    apb.write(hcCommandStatus, hostControllerReset)
    var resetTimeout = 100
    while ((apb.read(hcCommandStatus) & hostControllerReset) != 0 && resetTimeout > 0) {
      dut.frontCd.waitSampling()
      resetTimeout -= 1
    }
    assert(resetTimeout != 0, s"phase=$phase HCR did not clear")

    apb.write(hcHcca, hccaBase)
    apb.write(hcControlHeadEd, edBase)
    apb.write(hcFmInterval, frameInterval)
    apb.write(hcPeriodicStart, periodicStart)
    apb.write(hcRhStatus, 1 << 16)

    dut.io.utmi.lineState #= 1
    utmi.waitCycles(timing.attachDebounceCycles + 4)
    apb.write(hcControl, usbOperational)
    dut.frontCd.waitSampling(12)

    apb.write(hcRhPortStatus, setPortReset)
    utmi.waitCycles(timing.resetCycles + 8)
    dut.frontCd.waitSampling(12)

    val portStatus = apb.read(hcRhPortStatus)
    assert(
      (portStatus & (currentConnectStatus | portEnableStatus)) ==
        (currentConnectStatus | portEnableStatus),
      f"phase=$phase port was not connected and enabled before the IN TD: 0x$portStatus%x"
    )
    assert(dut.io.utmi.xcvrSel.toInt == 1, s"phase=$phase port did not select full-speed UTMI mode")

    fork {
      while (true) {
        val packet = acceptFullSpeedTxPacket(dut)
        txPackets += packet
        packet.headOption.foreach { pid =>
          if (pid == ackPid && acknowledgedPackets < payloads.size) {
            acknowledgedPackets += 1
          }
          if (pid == inPid) {
            inTokens += 1
            val responseIndex = acknowledgedPackets min (responsePackets.size - 1)
            responsesSent += 1
            val packet = responsePackets(responseIndex)
            val packetTiming =
              if (responseIndex == responsePackets.size - 1) {
                finalPacketTiming.getOrElse(
                  regularRxTiming(
                    packet.size,
                    tailHoldCycles = 5,
                    firstValidOffset = firstValidOffset
                  )
                )
              } else {
                regularRxTiming(
                  packet.size,
                  tailHoldCycles = 5,
                  firstValidOffset = firstValidOffset
                )
              }
            injectedRxEvents +=
              s"response=$responsesSent packet=$responseIndex bytes=${hex(packet)} " +
                s"validOffsets=${packetTiming.validOffsets.mkString(",")} " +
                s"activeFallOffset=${packetTiming.activeFallOffset}"
            emitFullSpeedRxPacket(dut, packet, phase, packetTiming)
          }
        }
      }
    }

    apb.write(
      hcControl,
      usbOperational | operationalScheduleControl | controlListEnable
    )
    apb.write(hcCommandStatus, controlListFilled)

    val pointerMask = BigInt("fffffff0", 16)
    var remaining = 600000
    while ((read32(edBase + 8) & pointerMask) != tailTdBase && remaining > 0) {
      dut.backCd.waitSampling()
      remaining -= 1
    }

    val retired = (read32(edBase + 8) & pointerMask) == tailTdBase
    if (!retired) {
      val currentTdControl = read32(tdBase)
      val currentCbp = read32(tdBase + 4)
      println(
        f"phase-result phase=$phase%02d terminal=timeout cc=${(currentTdControl >> 28) & 0xf} " +
          f"cbp=0x$currentCbp%08x responses=$responsesSent inTokens=$inTokens " +
          s"txPackets=${txPackets.size} AXI_AR_R_AW_W_B=" +
          s"$arHandshakes/$rHandshakes/$awHandshakes/$wHandshakes/$bHandshakes"
      )
    }
    if (!deferRetirementFailure) {
      assert(
        retired,
        s"phase=$phase General TD was not retired within the timeout " +
          s"(IN tokens=$inTokens, responses=$responsesSent, TX packets=${txPackets.size}, " +
          s"AXI AR/R/AW/W/B=$arHandshakes/$rHandshakes/$awHandshakes/$wHandshakes/$bHandshakes)"
      )
    }

    dut.frontCd.waitSampling(16)
    val finalTdControl = read32(tdBase)
    val finalCbp = read32(tdBase + 4)
    val finalEdHead = read32(edBase + 8)
    val completionCode = ((finalTdControl >> 28) & 0xf).toInt
    val completedBytes =
      if (finalCbp == 0) descriptor.size
      else if (finalCbp >= bufferBase && finalCbp <= bufferBase + descriptor.size) {
        (finalCbp - bufferBase).toInt
      } else -1
    val dmaPayload = descriptor.indices.map(index => bytes.getOrElse(bufferBase + index, -1)).toVector

    PhaseResult(
      phase = phase,
      retired = retired,
      completionCode = completionCode,
      completedBytes = completedBytes,
      currentBufferPointer = finalCbp,
      responses = responsesSent,
      inTokens = inTokens,
      finalTdControl = finalTdControl,
      finalEdHead = finalEdHead,
      dmaPayload = dmaPayload,
      rxEventOverflow = dut.cc.rxEventOverflow.toBoolean,
      rxEventCollision = dut.cc.rxEventCollision.toBoolean,
      injectedRxEvents = injectedRxEvents.toVector,
      frontRxEvents = frontRxEvents.toVector
    )
  }

  private def acceptFullSpeedTxPacket(dut: UsbOhciAxi4Apb3Utmi): Vector[Int] = {
    val packet = ArrayBuffer.empty[Int]
    while (!dut.io.utmi.txValid.toBoolean) {
      dut.backCd.waitSampling()
    }

    while (dut.io.utmi.txValid.toBoolean) {
      dut.backCd.waitSampling(2)
      assert(dut.io.utmi.txValid.toBoolean, "TXVALID ended before USB3500 requested a byte")
      val byte = dut.io.utmi.dataO.toInt & 0xff
      dut.io.utmi.txReady #= true
      dut.backCd.waitSampling()
      packet += byte
      dut.io.utmi.txReady #= false
      dut.backCd.waitSampling(39)
    }

    dut.io.utmi.lineState #= 0
    dut.backCd.waitSampling(10)
    dut.io.utmi.lineState #= 1
    dut.backCd.waitSampling(5)
    packet.toVector
  }

  private def emitFullSpeedRxPacket(
      dut: UsbOhciAxi4Apb3Utmi,
      packet: Vector[Int],
      phase: Int,
      packetTiming: RxPacketTiming
  ): Unit = {
    require(packetTiming.validOffsets.size == packet.size)
    dut.backCd.waitSampling(phase)
    dut.io.utmi.rxActive #= true
    dut.io.utmi.rxValid #= false
    dut.io.utmi.rxError #= false

    packet.zip(packetTiming.validOffsets).zipWithIndex.foreach { case ((byte, offset), index) =>
      val waitCycles =
        if (index == 0) offset else offset - packetTiming.validOffsets(index - 1) - 1
      dut.backCd.waitSampling(waitCycles)
      dut.io.utmi.dataI #= byte
      dut.io.utmi.rxValid #= true
      dut.backCd.waitSampling()
      dut.io.utmi.rxValid #= false
    }

    dut.backCd.waitSampling(packetTiming.activeFallOffset - packetTiming.validOffsets.last)
    dut.io.utmi.rxActive #= false
    dut.io.utmi.dataI #= 0
  }

  private def regularRxTiming(
      byteCount: Int,
      tailHoldCycles: Int,
      firstValidOffset: Int = 45
  ): RxPacketTiming = {
    require(byteCount > 0)
    require(tailHoldCycles > 0)
    require(firstValidOffset >= 0)
    val validOffsets = Vector.tabulate(byteCount)(index => firstValidOffset + index * 40)
    RxPacketTiming(validOffsets, validOffsets.last + tailHoldCycles)
  }

  private def usbCrc16(payload: Seq[Int]): Int = {
    var crc = 0xffff
    payload.foreach { value =>
      var data = value & 0xff
      for (_ <- 0 until 8) {
        val mix = (crc ^ data) & 1
        crc >>>= 1
        if (mix != 0) crc ^= 0xa001
        data >>>= 1
      }
    }
    crc ^ 0xffff
  }

  private def hex(bytes: Seq[Int]): String =
    bytes.map(value => f"${value & 0xff}%02x").mkString(" ")
}
