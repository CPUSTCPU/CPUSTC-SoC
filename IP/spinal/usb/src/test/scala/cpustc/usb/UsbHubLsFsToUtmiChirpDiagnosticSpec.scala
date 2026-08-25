package cpustc.usb

import cpustc.usb.utmi.{
  Usb3500UtmiIo,
  UsbHubLsFsToUtmi,
  UsbHubLsFsToUtmiDebug,
  UsbHubLsFsToUtmiTiming
}
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.com.usb.phy.UsbHubLsFs

private case class UsbHubLsFsToUtmiChirpDiagnosticSimTop(
    timing: UsbHubLsFsToUtmiTiming,
    resetChirpDiagnostic: Boolean
) extends Component {
  val io = new Bundle {
    val ctrl = slave(UsbHubLsFs.Ctrl(1))
    val utmi = master(Usb3500UtmiIo())
    val debug = out(
      UsbHubLsFsToUtmiDebug(
        waitCounterWidth = log2Up(timing.txEopTimeoutCycles),
        ipdCounterWidth = log2Up(
          timing.fullSpeedInterPacketCycles max timing.lowSpeedInterPacketCycles
        ),
        chirpFilterCounterWidth = log2Up(timing.chirpFilterCycles + 1)
      )
    )
  }

  val adapter = UsbHubLsFsToUtmi(
    timing = timing,
    resetChirpDiagnostic = resetChirpDiagnostic
  )
  io.ctrl <> adapter.io.ctrl
  io.utmi <> adapter.io.utmi
  io.debug := adapter.io.debug
}

class UsbHubLsFsToUtmiChirpDiagnosticSpec extends AnyFunSuite {
  private val chirpFilterCycles = 165
  private val se0 = 0
  private val lineState01 = 1
  private val lineState10 = 2

  private val timing = UsbHubLsFsToUtmiTiming(
    attachDebounceCycles = 4,
    disconnectCycles = 32,
    resetCycles = 700,
    chirpFilterCycles = chirpFilterCycles,
    resumeCycles = 4,
    txEopTimeoutCycles = 32,
    fullSpeedInterPacketCycles = 10,
    lowSpeedInterPacketCycles = 80,
    resumeEopSe0Cycles = 80,
    resumeEopJCycles = 40
  )

  test("reset keeps normal OPMODE when chirp diagnostic is disabled") {
    compileDut(resetChirpDiagnostic = false).doSim { dut =>
      initialize(dut)
      enterReset(dut)

      for (_ <- 0 until 8) {
        assertResetControls(dut, expectedOpMode = 0)
        assertDiagnosticClear(dut)
        waitSamplingStable(dut)
      }
    }
  }

  test("reset chirp diagnostic qualifies only after 165 complete CLKOUT cycles") {
    compileDut(resetChirpDiagnostic = true).doSim { dut =>
      initialize(dut)
      enterReset(dut)

      qualifyAfterCompleteCycles(dut, lineState01)

      // Switching K/J must discard the qualified state and start a fresh 165-cycle window.
      beginCandidate(dut, lineState10)
      countToQualification(dut, lineState10)

      dut.io.utmi.lineState #= se0
      waitResetSampling(dut)
      assert(dut.io.debug.chirpCandidate.toInt == se0)
      assertDiagnosticClear(dut)

      // Re-qualify so leaving Resetting has non-zero diagnostic state to clear.
      qualifyAfterCompleteCycles(dut, lineState01)
      waitForResetExit(dut)
      assertDiagnosticClear(dut)
    }
  }

  private def compileDut(resetChirpDiagnostic: Boolean) =
    SimConfig.withVerilator.compile(
      UsbHubLsFsToUtmiChirpDiagnosticSimTop(timing, resetChirpDiagnostic)
    )

  private def initialize(dut: UsbHubLsFsToUtmiChirpDiagnosticSimTop): Unit = {
    dut.clockDomain.forkStimulus(16667)

    dut.io.ctrl.lowSpeed #= false
    dut.io.ctrl.usbReset #= false
    dut.io.ctrl.usbResume #= false
    dut.io.ctrl.tx.valid #= false
    dut.io.ctrl.tx.fragment #= 0
    dut.io.ctrl.tx.last #= false

    dut.io.utmi.dataI #= 0
    dut.io.utmi.txReady #= false
    dut.io.utmi.rxValid #= false
    dut.io.utmi.rxActive #= false
    dut.io.utmi.rxError #= false
    dut.io.utmi.lineState #= lineState01
    dut.io.utmi.vbusValid #= true
    dut.io.utmi.hostDisconnect #= false

    val port = dut.io.ctrl.ports(0)
    port.removable #= true
    port.power #= true
    port.disable.valid #= false
    port.reset.valid #= false
    port.suspend.valid #= false
    port.resume.valid #= false

    waitSamplingStable(dut, timing.attachDebounceCycles + 5)
  }

  private def enterReset(dut: UsbHubLsFsToUtmiChirpDiagnosticSimTop): Unit = {
    val reset = dut.io.ctrl.ports(0).reset
    dut.io.utmi.lineState #= se0
    waitSamplingStable(dut)
    reset.valid #= true

    var remaining = 8
    while (!dut.io.debug.portResetActive.toBoolean && remaining > 0) {
      waitSamplingStable(dut)
      remaining -= 1
    }
    assert(dut.io.debug.portResetActive.toBoolean, "port did not enter Resetting")
    reset.valid #= false

    // Allow chirpDiagnosticActivePrev to observe the active reset state with SE0 selected.
    waitSamplingStable(dut)
    assertDiagnosticClear(dut)
  }

  private def qualifyAfterCompleteCycles(
      dut: UsbHubLsFsToUtmiChirpDiagnosticSimTop,
      lineState: Int
  ): Unit = {
    beginCandidate(dut, lineState)
    countToQualification(dut, lineState)
  }

  private def beginCandidate(
      dut: UsbHubLsFsToUtmiChirpDiagnosticSimTop,
      lineState: Int
  ): Unit = {
    dut.io.utmi.lineState #= lineState
    waitResetSampling(dut)
    assert(dut.io.debug.chirpCandidate.toInt == lineState)
    assertDiagnosticClear(dut)
  }

  private def countToQualification(
      dut: UsbHubLsFsToUtmiChirpDiagnosticSimTop,
      lineState: Int
  ): Unit = {
    for (completeCycles <- 1 until chirpFilterCycles) {
      waitResetSampling(dut)
      assert(
        dut.io.debug.chirpFilterCounter.toInt == completeCycles,
        s"counter=${dut.io.debug.chirpFilterCounter.toInt} after $completeCycles complete cycles"
      )
      assert(
        !dut.io.debug.chirpStateQualified.toBoolean,
        s"LINESTATE=$lineState qualified after only $completeCycles complete cycles"
      )
      assert(dut.io.debug.chirpQualifiedState.toInt == 0)
    }

    waitResetSampling(dut)
    assert(dut.io.debug.chirpFilterCounter.toInt == chirpFilterCycles)
    assert(dut.io.debug.chirpStateQualified.toBoolean)
    assert(dut.io.debug.chirpQualifiedState.toInt == lineState)
  }

  private def waitResetSampling(dut: UsbHubLsFsToUtmiChirpDiagnosticSimTop): Unit = {
    waitSamplingStable(dut)
    assert(dut.io.debug.portResetActive.toBoolean, "port left Resetting before the check completed")
    assertResetControls(dut, expectedOpMode = 2)
  }

  private def waitForResetExit(dut: UsbHubLsFsToUtmiChirpDiagnosticSimTop): Unit = {
    var remaining = timing.resetCycles
    while (dut.io.debug.portResetActive.toBoolean && remaining > 0) {
      assertResetControls(dut, expectedOpMode = 2)
      waitSamplingStable(dut)
      remaining -= 1
    }
    assert(!dut.io.debug.portResetActive.toBoolean, "port did not leave Resetting")
  }

  private def waitSamplingStable(
      dut: UsbHubLsFsToUtmiChirpDiagnosticSimTop,
      cycles: Int = 1
  ): Unit = {
    dut.clockDomain.waitSampling(cycles)
    sleep(1)
  }

  private def assertResetControls(
      dut: UsbHubLsFsToUtmiChirpDiagnosticSimTop,
      expectedOpMode: Int
  ): Unit = {
    assert(dut.io.debug.portResetActive.toBoolean)
    assert(dut.io.utmi.xcvrSel.toInt == 0)
    assert(!dut.io.utmi.termSel.toBoolean)
    assert(dut.io.utmi.opMode.toInt == expectedOpMode)
    assert(dut.io.debug.phyXcvrSel.toInt == dut.io.utmi.xcvrSel.toInt)
    assert(dut.io.debug.phyTermSel.toBoolean == dut.io.utmi.termSel.toBoolean)
    assert(dut.io.debug.phyOpMode.toInt == dut.io.utmi.opMode.toInt)
    assert(!dut.io.utmi.txValid.toBoolean)
  }

  private def assertDiagnosticClear(dut: UsbHubLsFsToUtmiChirpDiagnosticSimTop): Unit = {
    assert(dut.io.debug.chirpFilterCounter.toInt == 0)
    assert(!dut.io.debug.chirpStateQualified.toBoolean)
    assert(dut.io.debug.chirpQualifiedState.toInt == 0)
  }
}
