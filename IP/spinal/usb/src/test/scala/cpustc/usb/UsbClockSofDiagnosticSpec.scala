package cpustc.usb

import cpustc.usb.utmi.{UsbClockSofDiagnostic, UsbClockSofDiagnosticStatus}
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._
import spinal.lib.PulseCCByToggle

private case class UsbClockSofDiagnosticSimTop() extends Component {
  private val resetConfig = ClockDomainConfig(resetKind = SYNC, resetActiveLevel = HIGH)
  val frontCd: ClockDomain = ClockDomain.external(
    "front",
    frequency = FixedFrequency(33 MHz),
    config = resetConfig
  )
  val sourceCd: ClockDomain = ClockDomain.external(
    "source",
    frequency = FixedFrequency(60 MHz),
    config = resetConfig
  )

  val io = new Bundle {
    val heartbeat = in Bool()
    val sourceTick = in Bool()
    val portResetActive = in Bool()
    val txData = in Bits (8 bits)
    val txValid = in Bool()
    val txReady = in Bool()
    val rxEventOverflow = in Bool()
    val rxEventCollision = in Bool()
    val status = out(UsbClockSofDiagnosticStatus())
  }

  private val destinationTick = PulseCCByToggle(io.sourceTick, sourceCd, frontCd)
  private val diagnostic = UsbClockSofDiagnostic(
    sourceCd = sourceCd,
    frontCd = frontCd,
    withIla = false
  )
  diagnostic.io.heartbeat := io.heartbeat
  diagnostic.io.sourceTick := io.sourceTick
  diagnostic.io.destinationTick := destinationTick
  diagnostic.io.portResetActive := io.portResetActive
  diagnostic.io.txData := io.txData
  diagnostic.io.txValid := io.txValid
  diagnostic.io.txReady := io.txReady
  diagnostic.io.rxEventOverflow := io.rxEventOverflow
  diagnostic.io.rxEventCollision := io.rxEventCollision
  io.status := diagnostic.io.status
}

class UsbClockSofDiagnosticSpec extends AnyFunSuite {
  private val frontPeriodPs = 30303
  private val sourcePeriodPs = 16667

  test("33 MHz monitor measures the 60 MHz heartbeat, reset, SOF, SETUP and tick CDC") {
    SimConfig.withVerilator.compile(UsbClockSofDiagnosticSimTop()).doSim { dut =>
      dut.frontCd.forkStimulus(frontPeriodPs)
      dut.sourceCd.forkStimulus(sourcePeriodPs)

      dut.io.heartbeat #= false
      dut.io.sourceTick #= false
      dut.io.portResetActive #= false
      dut.io.txData #= 0
      dut.io.txValid #= false
      dut.io.txReady #= true
      dut.io.rxEventOverflow #= false
      dut.io.rxEventCollision #= false
      dut.frontCd.assertReset()
      dut.sourceCd.assertReset()
      dut.frontCd.waitSampling(8)
      dut.sourceCd.waitSampling(8)
      dut.frontCd.deassertReset()
      dut.sourceCd.deassertReset()

      @volatile var heartbeatEnabled = true
      fork {
        while (true) {
          dut.sourceCd.waitSampling(255)
          if (heartbeatEnabled) {
            dut.io.heartbeat #= true
          }
          dut.sourceCd.waitSampling()
          dut.io.heartbeat #= false
        }
      }
      fork {
        while (true) {
          dut.sourceCd.waitSampling(4)
          dut.io.sourceTick #= true
          dut.sourceCd.waitSampling()
          dut.io.sourceTick #= false
        }
      }

      var firstSofAfterResetPulses = 0
      var captureQualifierCycles = 0
      dut.frontCd.onSamplings {
        if (dut.io.status.firstSofAfterReset.toBoolean) {
          firstSofAfterResetPulses += 1
        }
        if (dut.io.status.captureQualifier.toBoolean) {
          captureQualifierCycles += 1
        }
      }

      waitUntil(dut.frontCd, 4000)(dut.io.status.heartbeatCount.toBigInt >= 8)
      val heartbeatMin = dut.io.status.heartbeatIntervalMin.toInt
      val heartbeatMax = dut.io.status.heartbeatIntervalMax.toInt
      assert(heartbeatMin >= 139 && heartbeatMin <= 142, s"heartbeat min=$heartbeatMin")
      assert(heartbeatMax >= 139 && heartbeatMax <= 142, s"heartbeat max=$heartbeatMax")
      assert(!dut.io.status.heartbeatTimeout.toBoolean)
      assert(!dut.io.status.heartbeatRangeFault.toBoolean)

      dut.io.portResetActive #= true
      dut.sourceCd.waitSampling(300)
      dut.io.portResetActive #= false
      waitUntil(dut.frontCd, 64)(dut.io.status.resetCount.toInt == 1)
      val resetDuration = dut.io.status.resetDurationLast.toInt
      assert(resetDuration >= 163 && resetDuration <= 167, s"reset duration=$resetDuration")

      dut.sourceCd.waitSampling(120)
      sendPacket(dut, Vector(0xa5, 0x00, 0x00))
      waitUntil(dut.frontCd, 64)(dut.io.status.sofCount.toInt == 1)
      waitUntil(dut.frontCd, 64)(firstSofAfterResetPulses == 1)
      assert(dut.io.status.resetToFirstSof.toBigInt > 0)

      dut.sourceCd.waitSampling(60000)
      sendPacket(dut, Vector(0xa5, 0x01, 0x00))
      waitUntil(dut.frontCd, 64)(dut.io.status.sofCount.toInt == 2)
      val sofInterval = dut.io.status.sofInterval.toBigInt.toInt
      assert(sofInterval >= 32995 && sofInterval <= 33010, s"SOF interval=$sofInterval")

      sendPacket(dut, Vector(0x69, 0xa5, 0x00))
      dut.frontCd.waitSampling(8)
      assert(dut.io.status.sofCount.toInt == 2, "mid-packet SOF byte was classified as a PID")
      sendPacket(dut, Vector(0x69, 0x2d, 0x00))
      dut.frontCd.waitSampling(8)
      assert(dut.io.status.setupCount.toInt == 0, "mid-packet SETUP byte was classified as a PID")

      dut.io.txData #= 0x2d
      dut.io.txValid #= true
      dut.io.txReady #= false
      dut.sourceCd.waitSampling(8)
      dut.frontCd.waitSampling(4)
      assert(dut.io.status.setupCount.toInt == 0, "stalled SETUP PID was counted before TXREADY")
      dut.io.txReady #= true
      dut.sourceCd.waitSampling()
      dut.io.txData #= 0x00
      dut.sourceCd.waitSampling()
      dut.io.txData #= 0x10
      dut.sourceCd.waitSampling()
      dut.io.txValid #= false
      dut.io.txData #= 0
      dut.sourceCd.waitSampling(2)
      waitUntil(dut.frontCd, 64)(dut.io.status.setupCount.toInt == 1)
      assert(dut.io.status.setupTimestamp.toBigInt > 0)

      val heartbeatCountBeforePause = dut.io.status.heartbeatCount.toBigInt
      heartbeatEnabled = false
      dut.frontCd.waitSampling(700)
      assert(dut.io.status.heartbeatTimeout.toBoolean)
      heartbeatEnabled = true
      waitUntil(dut.frontCd, 1000) {
        dut.io.status.heartbeatCount.toBigInt > heartbeatCountBeforePause
      }
      assert(dut.io.status.heartbeatRangeFault.toBoolean)

      dut.io.rxEventOverflow #= true
      dut.io.rxEventCollision #= true
      waitUntil(dut.frontCd, 16) {
        dut.io.status.rxEventOverflow.toBoolean && dut.io.status.rxEventCollision.toBoolean
      }
      assert(dut.io.status.fault.toBoolean)

      dut.frontCd.waitSampling(16)
      val sourceTicks = dut.io.status.sourceTickCount.toBigInt
      val destinationTicks = dut.io.status.destinationTickCount.toBigInt
      assert((sourceTicks - destinationTicks).abs <= 1, s"tick counts source=$sourceTicks destination=$destinationTicks")
      assert(captureQualifierCycles >= 2, s"capture qualifier cycles=$captureQualifierCycles")
    }
  }

  test("startup without any 60 MHz heartbeat raises a front-domain fault") {
    SimConfig.withVerilator.compile(UsbClockSofDiagnosticSimTop()).doSim { dut =>
      dut.frontCd.forkStimulus(frontPeriodPs)
      dut.sourceCd.forkStimulus(sourcePeriodPs)
      dut.io.heartbeat #= false
      dut.io.sourceTick #= false
      dut.io.portResetActive #= false
      dut.io.txData #= 0
      dut.io.txValid #= false
      dut.io.txReady #= true
      dut.io.rxEventOverflow #= false
      dut.io.rxEventCollision #= false
      dut.frontCd.assertReset()
      dut.sourceCd.assertReset()
      dut.frontCd.waitSampling(8)
      dut.sourceCd.waitSampling(8)
      dut.frontCd.deassertReset()
      dut.sourceCd.deassertReset()

      var captureQualifierCycles = 0
      dut.frontCd.onSamplings {
        if (dut.io.status.captureQualifier.toBoolean) {
          captureQualifierCycles += 1
        }
      }
      dut.frontCd.waitSampling(700)
      assert(!dut.io.status.heartbeatSeen.toBoolean)
      assert(dut.io.status.heartbeatTimeout.toBoolean)
      assert(dut.io.status.fault.toBoolean)
      assert(captureQualifierCycles == 2, s"capture qualifier cycles=$captureQualifierCycles")
    }
  }

  test("tick delta removes source-domain activity before the front reset is released") {
    SimConfig.withVerilator.compile(UsbClockSofDiagnosticSimTop()).doSim { dut =>
      dut.frontCd.forkStimulus(frontPeriodPs)
      dut.sourceCd.forkStimulus(sourcePeriodPs)
      dut.io.heartbeat #= false
      dut.io.sourceTick #= false
      dut.io.portResetActive #= false
      dut.io.txData #= 0
      dut.io.txValid #= false
      dut.io.txReady #= true
      dut.io.rxEventOverflow #= false
      dut.io.rxEventCollision #= false
      dut.frontCd.waitSampling(20)
      dut.sourceCd.waitSampling(20)

      fork {
        while (true) {
          dut.sourceCd.waitSampling(255)
          dut.io.heartbeat #= true
          dut.sourceCd.waitSampling()
          dut.io.heartbeat #= false
        }
      }
      fork {
        while (true) {
          dut.sourceCd.waitSampling(4)
          dut.io.sourceTick #= true
          dut.sourceCd.waitSampling()
          dut.io.sourceTick #= false
        }
      }

      waitUntil(dut.frontCd, 3000)(dut.io.status.heartbeatCount.toBigInt >= 2)
      dut.frontCd.assertReset()
      dut.frontCd.waitRisingEdge(8)
      dut.sourceCd.waitSampling(1600)
      dut.frontCd.deassertReset()
      waitUntil(dut.frontCd, 3000)(dut.io.status.heartbeatCount.toBigInt >= 4)
      dut.frontCd.waitSampling(16)

      val sourceTicks = dut.io.status.sourceTickCount.toBigInt
      val destinationTicks = dut.io.status.destinationTickCount.toBigInt
      val tickDelta = dut.io.status.tickCountDelta.toInt
      assert(sourceTicks > destinationTicks + 200,
        s"raw counters did not preserve the reset offset: source=$sourceTicks destination=$destinationTicks")
      assert(tickDelta.abs <= 1,
        s"baseline-adjusted tick delta=$tickDelta source=$sourceTicks destination=$destinationTicks")
    }
  }

  private def sendPacket(dut: UsbClockSofDiagnosticSimTop, bytes: Vector[Int]): Unit = {
    dut.io.txValid #= true
    bytes.foreach { byte =>
      dut.io.txData #= byte
      dut.sourceCd.waitSampling()
    }
    dut.io.txValid #= false
    dut.io.txData #= 0
    dut.sourceCd.waitSampling(2)
  }

  private def waitUntil(clockDomain: ClockDomain, timeoutCycles: Int)(condition: => Boolean): Unit = {
    var cycles = 0
    while (!condition && cycles < timeoutCycles) {
      clockDomain.waitSampling()
      cycles += 1
    }
    assert(condition, s"condition not met after $timeoutCycles cycles")
  }
}
