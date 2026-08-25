package cpustc.usb

import cpustc.usb.sim.Usb3500UtmiAgent
import cpustc.usb.utmi.{UsbHubLsFsToUtmiTiming, UsbOhciAxi4Apb3Utmi}
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._
import spinal.lib.bus.amba3.apb.sim.Apb3Driver
import spinal.lib.com.usb.ohci.{OhciPortParameter, UsbOhciParameter}

import scala.collection.mutable

class UsbOhciHccaFrameSpec extends AnyFunSuite {
  private val hcControl = 0x04
  private val hcCommandStatus = 0x08
  private val hcInterruptStatus = 0x0c
  private val hcInterruptEnable = 0x10
  private val hcInterruptDisable = 0x14
  private val hcHcca = 0x18
  private val hcFmInterval = 0x34
  private val hcFmRemaining = 0x38
  private val hcFmNumber = 0x3c
  private val hcPeriodicStart = 0x40
  private val hcRhStatus = 0x50
  private val hcRhPortStatus = 0x54

  private val currentConnectStatus = BigInt(1) << 0
  private val portEnableStatus = BigInt(1) << 1
  private val setPortReset = BigInt(1) << 4
  private val operationalScheduleControl = BigInt(0x0f)
  private val startOfFrameInterrupt = BigInt(1) << 2
  private val masterInterruptEnable = BigInt(1) << 31
  private val hostControllerReset = BigInt(1)

  private val hccaBase = 0x1000L
  private val hccaFrameNumber = hccaBase + 0x80
  private val sofPid = 0xa5
  private val frameInterval = 0x27782edfL
  private val periodicStart = 0x2a2f

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

  test("HCCA frame number keeps advancing when disconnect overlaps an Operational SOF transfer") {
    SimConfig.withVerilator.compile {
      val resetConfig = ClockDomainConfig(resetKind = SYNC, resetActiveLevel = HIGH)
      val ctrlCd = ClockDomain.external("ctrl", config = resetConfig)
      val dmaCd = ClockDomain.external("dma", config = resetConfig)
      val utmiCd = ClockDomain.external(
        "utmi",
        frequency = FixedFrequency(60 MHz),
        config = resetConfig
      )
      UsbOhciAxi4Apb3Utmi(parameter, ctrlCd, utmiCd, dmaCd, timing)
    }.doSim { dut =>
      dut.frontCd.forkStimulus(30000)
      dut.dmaCd.forkStimulus(30300)
      dut.backCd.forkStimulus(16667)

      final case class WriteBurst(var address: Long, bytesPerBeat: Int, var beatsLeft: Int)
      final case class WriteBeat(data: BigInt, strobe: BigInt, last: Boolean)
      final case class ReadBurst(var address: Long, bytesPerBeat: Int, var beatsLeft: Int)

      val bytes = mutable.Map.empty[Long, Int]
      val bursts = mutable.Queue.empty[WriteBurst]
      val beats = mutable.Queue.empty[WriteBeat]
      val reads = mutable.Queue.empty[ReadBurst]
      var pendingWriteResponses = 0
      var readResponseActive = false
      var awHandshakes = 0
      var wHandshakes = 0
      var bHandshakes = 0
      var arHandshakes = 0
      var rHandshakes = 0
      var txHandshakes = 0
      var completedEops = 0
      val transmittedRuns = mutable.ArrayBuffer.empty[Vector[Int]]
      val transmittedRunSpeeds = mutable.ArrayBuffer.empty[String]

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

          if (dut.io.dma.b.valid.toBoolean && dut.io.dma.b.ready.toBoolean) {
            pendingWriteResponses -= 1
            bHandshakes += 1
          }

          if (dut.io.dma.aw.valid.toBoolean && dut.io.dma.aw.ready.toBoolean) {
            awHandshakes += 1
            bursts.enqueue(
              WriteBurst(
                address = dut.io.dma.aw.addr.toBigInt.longValue,
                bytesPerBeat = 1 << dut.io.dma.aw.size.toInt,
                beatsLeft = dut.io.dma.aw.len.toInt + 1
              )
            )
          }

          if (dut.io.dma.w.valid.toBoolean && dut.io.dma.w.ready.toBoolean) {
            wHandshakes += 1
            beats.enqueue(
              WriteBeat(
                data = dut.io.dma.w.data.toBigInt,
                strobe = dut.io.dma.w.strb.toBigInt,
                last = dut.io.dma.w.last.toBoolean
              )
            )
          }

          if (dut.io.dma.r.valid.toBoolean && dut.io.dma.r.ready.toBoolean) {
            val read = reads.front
            rHandshakes += 1
            read.beatsLeft -= 1
            if (read.beatsLeft == 0) {
              reads.dequeue()
            } else {
              read.address += read.bytesPerBeat
            }
            readResponseActive = false
          }

          if (dut.io.dma.ar.valid.toBoolean && dut.io.dma.ar.ready.toBoolean) {
            arHandshakes += 1
            reads.enqueue(
              ReadBurst(
                address = dut.io.dma.ar.addr.toBigInt.longValue,
                bytesPerBeat = 1 << dut.io.dma.ar.size.toInt,
                beatsLeft = dut.io.dma.ar.len.toInt + 1
              )
            )
          }

          if (bursts.nonEmpty && beats.nonEmpty) {
            val burst = bursts.front
            val beat = beats.dequeue()
            val expectedLast = burst.beatsLeft == 1
            assert(beat.last == expectedLast, "AXI WLAST did not match AWLEN")

            for (lane <- 0 until parameter.dataWidth / 8) {
              if (((beat.strobe >> lane) & 1) != 0) {
                bytes(burst.address + lane) = ((beat.data >> (lane * 8)) & 0xff).toInt
              }
            }

            burst.beatsLeft -= 1
            if (burst.beatsLeft == 0) {
              bursts.dequeue()
              pendingWriteResponses += 1
            } else {
              burst.address += burst.bytesPerBeat
            }
          }

          dut.io.dma.b.valid #= pendingWriteResponses != 0

          if (!readResponseActive && reads.nonEmpty) {
            val read = reads.front
            var data = BigInt(0)
            for (lane <- 0 until parameter.dataWidth / 8) {
              data |= BigInt(bytes.getOrElse(read.address + lane, 0)) << (lane * 8)
            }
            dut.io.dma.r.data #= data
            dut.io.dma.r.last #= read.beatsLeft == 1
            dut.io.dma.r.valid #= true
            readResponseActive = true
          } else if (!readResponseActive) {
            dut.io.dma.r.valid #= false
          }
        }
      }

      val utmi = new Usb3500UtmiAgent(dut.io.utmi, dut.backCd)
      utmi.initialize()
      val apb = Apb3Driver(dut.io.ctrl, dut.frontCd)

      def readFrameNumber(): Int = {
        val low = bytes.getOrElse(hccaFrameNumber, 0)
        val high = bytes.getOrElse(hccaFrameNumber + 1, 0)
        low | (high << 8)
      }

      def waitForFrameChange(previous: Int, timeoutCycles: Int): Int = {
        var remaining = timeoutCycles
        var current = readFrameNumber()
        while (current == previous && remaining > 0) {
          dut.backCd.waitSampling()
          current = readFrameNumber()
          remaining -= 1
        }
        val registerDetails = if (current == previous) {
          val control = apb.read(hcControl)
          val hcca = apb.read(hcHcca)
          val interval = apb.read(hcFmInterval)
          val fmRemaining = apb.read(hcFmRemaining)
          val fmNumber = apb.read(hcFmNumber)
          f", HcControl=0x$control%x, HcHCCA=0x$hcca%x, " +
            f"HcFmInterval=0x$interval%x, HcFmRemaining=0x$fmRemaining%x, " +
            f"HcFmNumber=0x$fmNumber%x"
        } else ""
        assert(
          current != previous,
          f"HCCA frame number stopped at 0x$previous%04x " +
            s"(UTMI TX handshakes=$txHandshakes, EOPs=$completedEops, " +
            s"AXI AW/W/B=$awHandshakes/$wHandshakes/$bHandshakes, " +
            s"AR/R=$arHandshakes/$rHandshakes, TX runs=${transmittedRuns.mkString("[", ", ", "]")}" +
            s", TX speeds=${transmittedRunSpeeds.mkString("[", ", ", "]")}" +
            s"$registerDetails)"
        )
        current
      }

      var disconnectOnNextSof = false
      var disconnectAtSofSeen = false

      fork {
        while (!disconnectAtSofSeen) {
          while (!dut.io.utmi.txValid.toBoolean) {
            dut.backCd.waitSampling()
          }

          if (disconnectOnNextSof) {
            val lowSpeed = dut.io.utmi.xcvrSel.toInt == 2
            dut.backCd.waitSampling(2)
            assert(
              (dut.io.utmi.dataO.toInt & 0xff) == sofPid,
              "the armed transmit activity was not an SOF PID"
            )
            val byte = dut.io.utmi.dataO.toInt & 0xff
            dut.io.utmi.txReady #= true
            dut.backCd.waitSampling()
            txHandshakes += 1
            dut.io.utmi.txReady #= false
            transmittedRuns += Vector(byte)
            transmittedRunSpeeds += (if (lowSpeed) "LS" else "FS")
            utmi.disconnect()
            disconnectAtSofSeen = true
          } else {
            val run = mutable.ArrayBuffer.empty[Int]
            val lowSpeed = dut.io.utmi.xcvrSel.toInt == 2
            val cyclesPerByte = if (lowSpeed) 320 else 40
            while (dut.io.utmi.txValid.toBoolean) {
              dut.backCd.waitSampling(2)
              assert(dut.io.utmi.txValid.toBoolean, "TXVALID ended before USB3500 requested a byte")
              val byte = dut.io.utmi.dataO.toInt & 0xff
              dut.io.utmi.txReady #= true
              dut.backCd.waitSampling()
              txHandshakes += 1
              run += byte
              dut.io.utmi.txReady #= false
              dut.backCd.waitSampling(cyclesPerByte - 1)
            }
            transmittedRuns += run.toVector
            transmittedRunSpeeds += (if (lowSpeed) "LS" else "FS")

            // USB3500 完成发送时输出两位 SE0、一位发送速率的 J，再释放到设备空闲线态。
            val bitCycles = if (lowSpeed) 40 else 5
            dut.io.utmi.lineState #= 0
            dut.backCd.waitSampling(bitCycles * 2)
            dut.io.utmi.lineState #= (if (lowSpeed) 2 else 1)
            dut.backCd.waitSampling(bitCycles)
            dut.io.utmi.lineState #= 2
            completedEops += 1
          }
        }
      }

      dut.frontCd.waitSampling(10)
      apb.write(hcCommandStatus, hostControllerReset)
      var resetTimeout = 100
      while ((apb.read(hcCommandStatus) & hostControllerReset) != 0 && resetTimeout > 0) {
        dut.frontCd.waitSampling()
        resetTimeout -= 1
      }
      assert(resetTimeout != 0, "HCR did not clear")
      apb.write(hcInterruptDisable, BigInt("ffffffff", 16))
      apb.write(hcInterruptStatus, BigInt("ffffffff", 16))
      apb.write(hcHcca, hccaBase)
      apb.write(hcFmInterval, frameInterval)
      apb.write(hcPeriodicStart, periodicStart)
      apb.write(hcInterruptEnable, masterInterruptEnable | startOfFrameInterrupt)
      apb.write(hcRhStatus, 1 << 16)
      utmi.connectLowSpeed()
      utmi.waitCycles(timing.attachDebounceCycles + 4)
      apb.write(hcControl, (BigInt(2) << 6) | operationalScheduleControl)
      dut.frontCd.waitSampling(12)
      apb.write(hcRhPortStatus, setPortReset)
      utmi.waitCycles(timing.resetCycles + 8)
      dut.frontCd.waitSampling(12)
      val connectedPortStatus = apb.read(hcRhPortStatus)
      assert(
        (connectedPortStatus & (currentConnectStatus | portEnableStatus)) ==
          (currentConnectStatus | portEnableStatus),
        f"port was not connected and enabled before disconnect: 0x$connectedPortStatus%x"
      )

      // OpenHCI 1.0a sections 4.4.1 and 6.3.3 require this write each Operational frame.
      val firstFrame = waitForFrameChange(previous = 0, timeoutCycles = 180000)
      val frameBeforeDisconnect = waitForFrameChange(firstFrame, timeoutCycles = 120000)

      while (dut.io.utmi.txValid.toBoolean) {
        dut.backCd.waitSampling()
      }
      disconnectOnNextSof = true

      var disconnectTimeout = 120000
      while (!disconnectAtSofSeen && disconnectTimeout > 0) {
        dut.backCd.waitSampling()
        disconnectTimeout -= 1
      }
      assert(disconnectAtSofSeen, "no SOF transmit activity was observed for disconnect injection")

      utmi.waitCycles(timing.disconnectCycles + 4)
      dut.frontCd.waitSampling(12)
      val portStatus = apb.read(hcRhPortStatus)
      assert(
        (portStatus & currentConnectStatus) == 0,
        f"CCS remained set after disconnect: 0x$portStatus%x"
      )

      val firstFrameAfterDisconnect = waitForFrameChange(frameBeforeDisconnect, timeoutCycles = 180000)
      waitForFrameChange(firstFrameAfterDisconnect, timeoutCycles = 120000)
    }
  }
}
