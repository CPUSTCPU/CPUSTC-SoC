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

class UsbOhciRxCompletionSpec extends AnyFunSuite {
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

  private val ccNoError = 0
  private val ccCrc = 1
  private val ccBitStuffing = 2
  private val ccNotAccessed = 0xf

  private val inPid = 0x69
  private val validData1Packet = Vector(0x4b, 0x12, 0x01, 0x10, 0x01, 0x00, 0x00, 0x00, 0x08, 0x11, 0x77)
  private val validPayload = validData1Packet.slice(1, 9)

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

  private final case class RxScenario(
      name: String,
      errorWithValid: Boolean,
      errorWithoutValid: Boolean,
      responsesRequired: Int,
      expectedCc: Int
  ) {
    require(!(errorWithValid && errorWithoutValid))
  }

  private val scenarios = Seq(
    RxScenario(
      name = "valid captured DATA1 completes a General TD without a CRC condition code",
      errorWithValid = false,
      errorWithoutValid = false,
      responsesRequired = 1,
      expectedCc = ccNoError
    ),
    RxScenario(
      name = "RXERROR with RXVALID completes a General TD with bit-stuffing CC",
      errorWithValid = true,
      errorWithoutValid = false,
      responsesRequired = 3,
      expectedCc = ccBitStuffing
    ),
    RxScenario(
      name = "RXERROR without RXVALID while RXACTIVE completes a General TD with bit-stuffing CC",
      errorWithValid = false,
      errorWithoutValid = true,
      responsesRequired = 3,
      expectedCc = ccBitStuffing
    )
  )

  for (scenario <- scenarios) {
    test(scenario.name) {
      compileDut().doSim { dut =>
        runScenario(dut, scenario)
      }
    }
  }

  private def compileDut() = SimConfig.withVerilator.compile {
    val resetConfig = ClockDomainConfig(resetKind = SYNC, resetActiveLevel = HIGH)
    val ctrlCd = ClockDomain.external("ctrl", config = resetConfig)
    val dmaCd = ClockDomain.external("dma", config = resetConfig)
    val utmiCd = ClockDomain.external(
      "utmi",
      frequency = FixedFrequency(60 MHz),
      config = resetConfig
    )
    UsbOhciAxi4Apb3Utmi(parameter, ctrlCd, utmiCd, dmaCd, timing)
  }

  private def runScenario(dut: UsbOhciAxi4Apb3Utmi, scenario: RxScenario): Unit = {
    dut.frontCd.forkStimulus(30000)
    dut.dmaCd.forkStimulus(30300)
    dut.backCd.forkStimulus(16667)

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

    val edControl =
      (BigInt(8) << 16) | // MaximumPacketSize = 8 bytes
        (BigInt(1) << 13) // low-speed endpoint
    val tdControl =
      (BigInt(ccNotAccessed) << 28) |
        (BigInt(3) << 24) | // use DATA1 from the TD
        (BigInt(2) << 19) // IN token

    write32(edBase, edControl)
    write32(edBase + 4, tailTdBase)
    write32(edBase + 8, tdBase)
    write32(edBase + 12, 0)

    write32(tdBase, tdControl)
    write32(tdBase + 4, bufferBase)
    write32(tdBase + 8, tailTdBase)
    write32(tdBase + 12, bufferBase + validPayload.size - 1)

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
        dut.dmaCd.waitSampling()

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

    dut.frontCd.waitSampling(10)
    apb.write(hcCommandStatus, hostControllerReset)
    var resetTimeout = 100
    while ((apb.read(hcCommandStatus) & hostControllerReset) != 0 && resetTimeout > 0) {
      dut.frontCd.waitSampling()
      resetTimeout -= 1
    }
    assert(resetTimeout != 0, "HCR did not clear")

    apb.write(hcHcca, hccaBase)
    apb.write(hcControlHeadEd, edBase)
    apb.write(hcFmInterval, frameInterval)
    apb.write(hcPeriodicStart, periodicStart)
    apb.write(hcRhStatus, 1 << 16)

    utmi.connectLowSpeed()
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
      f"port was not connected and enabled before the IN TD: 0x$portStatus%x"
    )

    fork {
      while (true) {
        val packet = acceptLowSpeedTxPacket(dut)
        txPackets += packet
        if (packet.headOption.contains(inPid)) {
          inTokens += 1
          if (responsesSent < scenario.responsesRequired) {
            responsesSent += 1
            emitLowSpeedRxPacket(dut, scenario)
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

    assert(
      (read32(edBase + 8) & pointerMask) == tailTdBase,
      s"General TD was not retired within the timeout " +
        s"(IN tokens=$inTokens, responses=$responsesSent, TX packets=${txPackets.size}, " +
        s"AXI AR/R/AW/W/B=$arHandshakes/$rHandshakes/$awHandshakes/$wHandshakes/$bHandshakes)"
    )

    dut.dmaCd.waitSampling(16)
    val finalTdControl = read32(tdBase)
    val completionCode = ((finalTdControl >> 28) & 0xf).toInt

    assert(
      completionCode != ccCrc,
      f"the externally visible General TD reported CRC CC=1: 0x$finalTdControl%08x"
    )
    assert(
      completionCode == scenario.expectedCc,
      f"unexpected General TD completion code $completionCode: 0x$finalTdControl%08x"
    )
    assert(
      responsesSent == scenario.responsesRequired,
      s"expected ${scenario.responsesRequired} UTMI responses, observed $responsesSent"
    )

    val finalEdHead = read32(edBase + 8)
    if (scenario.expectedCc == ccNoError) {
      assert((finalEdHead & 1) == 0, f"ED halted after a valid DATA1 packet: 0x$finalEdHead%08x")
      val received = validPayload.indices.map(index => bytes.getOrElse(bufferBase + index, -1))
      assert(received == validPayload, s"DMA payload mismatch: $received")
    } else {
      assert((finalEdHead & 1) != 0, f"ED did not halt after three receive errors: 0x$finalEdHead%08x")
    }
  }

  private def acceptLowSpeedTxPacket(dut: UsbOhciAxi4Apb3Utmi): Vector[Int] = {
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
      dut.backCd.waitSampling(319)
    }

    // Two low-speed bit times of SE0 terminate the packet; J then completes EOP.
    dut.io.utmi.lineState #= 0
    dut.backCd.waitSampling(80)
    dut.io.utmi.lineState #= 2
    dut.backCd.waitSampling(40)
    dut.io.utmi.lineState #= 2
    packet.toVector
  }

  private def emitLowSpeedRxPacket(dut: UsbOhciAxi4Apb3Utmi, scenario: RxScenario): Unit = {
    val errorByteIndex = 4
    dut.backCd.waitSampling(80)
    dut.io.utmi.rxActive #= true
    dut.io.utmi.rxValid #= false
    dut.io.utmi.rxError #= false

    for ((byte, index) <- validData1Packet.zipWithIndex) {
      if (scenario.errorWithoutValid && index == errorByteIndex) {
        dut.backCd.waitSampling(159)
        assert(dut.io.utmi.rxActive.toBoolean)
        assert(!dut.io.utmi.rxValid.toBoolean)
        dut.io.utmi.rxError #= true
        dut.backCd.waitSampling()
        dut.io.utmi.rxError #= false
        dut.backCd.waitSampling(159)
      } else {
        dut.backCd.waitSampling(319)
      }

      dut.io.utmi.dataI #= byte
      dut.io.utmi.rxValid #= true
      dut.io.utmi.rxError #= scenario.errorWithValid && index == errorByteIndex
      dut.backCd.waitSampling()
      dut.io.utmi.rxValid #= false
      dut.io.utmi.rxError #= false
    }

    dut.backCd.waitSampling(80)
    dut.io.utmi.rxActive #= false
    dut.io.utmi.dataI #= 0
  }
}
