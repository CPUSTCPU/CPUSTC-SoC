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
import spinal.lib.com.usb.phy.{UsbHubLsFs, UsbLsFsPhyAbstractIo}

import scala.collection.mutable.ArrayBuffer

private case class UsbHubLsFsToUtmiPortFsmSimTop(timing: UsbHubLsFsToUtmiTiming) extends Component {
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

    // 纯仿真虚拟总线；全部叶子作为 testbench 驱动端口。
    val usb = UsbLsFsPhyAbstractIo()
    in(usb.tx.enable, usb.tx.data, usb.tx.se0, usb.rx.dp, usb.rx.dm)
  }

  val adapter = UsbHubLsFsToUtmi(timing)
  io.ctrl <> adapter.io.ctrl
  io.utmi <> adapter.io.utmi
  io.debug := adapter.io.debug
}

private final case class PortFsmUtmiSample(
    cycle: Long,
    txValid: Boolean,
    dataOe: Boolean,
    opMode: Int,
    xcvrSel: Int,
    termSel: Boolean,
    txLastAccepted: Boolean,
    ctrlTxEop: Boolean
) {
  def rawDrive: Boolean = txValid && dataOe && opMode == 2
  def lowSpeedRawK: Boolean = rawDrive && xcvrSel == 2 && termSel
  def rawSe0: Boolean = !txValid && !dataOe && opMode == 0 && xcvrSel == 0 && !termSel
  def lowSpeedIdle: Boolean =
    !txValid && !dataOe && opMode == 0 && xcvrSel == 2 && termSel
  def controlTuple: (Boolean, Boolean, Int, Int, Boolean) =
    (txValid, dataOe, opMode, xcvrSel, termSel)
}

private final class PortFsmMonitor(dut: UsbHubLsFsToUtmiPortFsmSimTop) {
  var cycle = 0L
  val connectCycles = ArrayBuffer.empty[Long]
  val disconnectCycles = ArrayBuffer.empty[Long]
  val remoteResumeCycles = ArrayBuffer.empty[Long]
  val controls = ArrayBuffer.empty[PortFsmUtmiSample]

  dut.clockDomain.onSamplings {
    cycle += 1
    val port = dut.io.ctrl.ports(0)
    assert(dut.io.debug.phyXcvrSel.toInt == dut.io.utmi.xcvrSel.toInt)
    assert(dut.io.debug.phyTermSel.toBoolean == dut.io.utmi.termSel.toBoolean)
    assert(dut.io.debug.phyOpMode.toInt == dut.io.utmi.opMode.toInt)
    assert(dut.io.utmi.dataOe.toBoolean == dut.io.utmi.txValid.toBoolean)
    assert(dut.io.utmi.dataT.toInt == (if (dut.io.utmi.txValid.toBoolean) 0x00 else 0xff))
    if (port.connect.toBoolean) connectCycles += cycle
    if (port.disconnect.toBoolean) disconnectCycles += cycle
    if (port.remoteResume.toBoolean) remoteResumeCycles += cycle
    controls += PortFsmUtmiSample(
      cycle = cycle,
      txValid = dut.io.utmi.txValid.toBoolean,
      dataOe = dut.io.utmi.dataOe.toBoolean,
      opMode = dut.io.utmi.opMode.toInt,
      xcvrSel = dut.io.utmi.xcvrSel.toInt,
      termSel = dut.io.utmi.termSel.toBoolean,
      txLastAccepted = dut.io.debug.txLastAccepted.toBoolean,
      ctrlTxEop = dut.io.debug.ctrlTxEop.toBoolean
    )
  }
}

private final case class ResetObservation(
    requestCycle: Long,
    readyCycle: Option[Long],
    rawSe0Cycles: Int
) {
  def latencyCycles: Option[Long] = readyCycle.map(_ - requestCycle)
}

class UsbHubLsFsToUtmiPortFsmSpec extends AnyFunSuite {
  private val timing = UsbHubLsFsToUtmiTiming(
    attachDebounceCycles = 8,
    disconnectCycles = 128,
    resetCycles = 32,
    resumeCycles = 32,
    txEopTimeoutCycles = 1024
  )

  private val lowSpeedJ = 2
  private val lowSpeedK = 1
  private val se0 = 0
  private val lowSpeedBitCycles = 40 // 60 MHz / 1.5 Mb/s

  test("suspended port detects disconnect while SE0 remains asserted") {
    compileDut().doSim { dut =>
      initialize(dut)
      val monitor = new PortFsmMonitor(dut)

      attachLowSpeed(dut)
      val firstReset = holdResetUntilReady(dut, monitor, timing.resetCycles + 20)
      assert(firstReset.readyCycle.nonEmpty, resetFailure("enabled-control setup", firstReset, monitor))
      dut.clockDomain.waitSampling(4)

      val enabledSe0Start = monitor.cycle
      dut.io.utmi.lineState #= se0
      dut.clockDomain.waitSampling(timing.disconnectCycles * 2)
      val enabledSe0End = monitor.cycle
      val enabledDisconnects = cyclesInWindow(
        monitor.disconnectCycles,
        enabledSe0Start,
        enabledSe0End
      )

      dut.io.utmi.lineState #= lowSpeedJ
      val reattachStart = monitor.cycle
      dut.clockDomain.waitSampling(timing.attachDebounceCycles + 12)
      val reattachConnects = cyclesInWindow(monitor.connectCycles, reattachStart, monitor.cycle)
      val secondReset = holdResetUntilReady(dut, monitor, timing.resetCycles + 20)
      assert(secondReset.readyCycle.nonEmpty, resetFailure("suspended-experiment setup", secondReset, monitor))
      dut.clockDomain.waitSampling(4)

      val suspendAccepted = pulseSuspend(dut)
      dut.clockDomain.waitSampling(2)
      val suspendedSe0Start = monitor.cycle
      dut.io.utmi.lineState #= se0
      dut.clockDomain.waitSampling(timing.disconnectCycles * 2)
      val resumeRequestCycle = monitor.cycle
      val suspendedDisconnects = cyclesInWindow(
        monitor.disconnectCycles,
        suspendedSe0Start,
        resumeRequestCycle
      )

      val resumeAccepted = holdResumeUntilReady(dut, timeoutCycles = 96)
      dut.clockDomain.waitSampling(timing.disconnectCycles * 2 + timing.resumeCycles + 16)
      val postResumeDisconnects = monitor.disconnectCycles.filter(_ > resumeRequestCycle).toVector
      val postResumeLatency = postResumeDisconnects.headOption.map(_ - resumeRequestCycle)

      val diagnostic =
        s"suspendAccepted=$suspendAccepted resumeAccepted=$resumeAccepted " +
          s"enabledSE0=($enabledSe0Start,$enabledSe0End] " +
          s"enabledDisconnects=${enabledDisconnects.mkString("[", ",", "]")} " +
          s"reattachConnects=${reattachConnects.mkString("[", ",", "]")} " +
          s"suspendedSE0=($suspendedSe0Start,$resumeRequestCycle] " +
          s"suspendedDisconnects=${suspendedDisconnects.mkString("[", ",", "]")} " +
          s"resumeRequest=$resumeRequestCycle postResumeDisconnects=${postResumeDisconnects.mkString("[", ",", "]")} " +
          s"firstPostResumeLatency=$postResumeLatency controls=${formatControls(monitor.controls.filter(_.cycle > suspendedSe0Start))}"

      assert(suspendAccepted, diagnostic)
      assert(enabledDisconnects.size == 1, diagnostic)
      assert(reattachConnects.size == 1, diagnostic)
      assert(suspendedDisconnects.size == 1, diagnostic)
    }
  }

  test("software resume from suspended low-speed port emits a trailing low-speed EOP") {
    compileDut().doSim { dut =>
      initialize(dut)
      val monitor = new PortFsmMonitor(dut)
      enableLowSpeedPort(dut, monitor)

      val suspendAccepted = pulseSuspend(dut)
      dut.clockDomain.waitSampling(2)
      dut.io.utmi.txReady #= true
      val resumeRequestCycle = monitor.cycle
      val resumeAccepted = holdResumeUntilReady(dut, timeoutCycles = 96)
      dut.clockDomain.waitSampling(timing.resumeCycles + lowSpeedBitCycles * 3 + 24)

      val trace = monitor.controls.filter(_.cycle > resumeRequestCycle).toVector
      val rawK = trace.filter(_.lowSpeedRawK)
      val sawRawK = rawK.nonEmpty
      val rawKEnd = rawK.lastOption.map(_.cycle)
      val afterRawK = rawKEnd match {
        case Some(end) => trace.filter(_.cycle > end)
        case None      => Vector.empty
      }
      val trailingSe0Drive = afterRawK.filter(_.rawSe0)
      val sawTrailingSe0Drive = trailingSe0Drive.nonEmpty
      val legalControls = trace.forall(sample =>
        sample.lowSpeedIdle || sample.lowSpeedRawK || sample.rawSe0
      )
      val dataOeMatchesTxValid = trace.forall(sample => sample.dataOe == sample.txValid)
      val rawKIsContiguous = rawK.isEmpty || rawK.last.cycle - rawK.head.cycle + 1 == rawK.size
      val trailingSe0IsContiguous =
        trailingSe0Drive.isEmpty ||
          trailingSe0Drive.last.cycle - trailingSe0Drive.head.cycle + 1 == trailingSe0Drive.size
      val rawKHasNoPacketCompletion = rawK.forall(sample => !sample.txLastAccepted && !sample.ctrlTxEop)

      val diagnostic =
        s"suspendAccepted=$suspendAccepted resumeAccepted=$resumeAccepted resumeRequest=$resumeRequestCycle " +
          s"sawRawK=$sawRawK rawKCycles=${rawK.map(_.cycle).mkString("[", ",", "]")} rawKEnd=$rawKEnd " +
          s"sawTrailingSe0Drive=$sawTrailingSe0Drive " +
          s"trailingSe0DriveCycles=${trailingSe0Drive.map(_.cycle).mkString("[", ",", "]")} " +
          s"resumeWindowEnd=${monitor.cycle} controls=${formatControls(trace)}"

      assert(suspendAccepted && resumeAccepted, diagnostic)
      assert(sawRawK, diagnostic)
      assert(sawTrailingSe0Drive, diagnostic)
      assert(legalControls, diagnostic)
      assert(dataOeMatchesTxValid, diagnostic)
      assert(rawKIsContiguous && rawK.size == timing.resumeCycles, diagnostic)
      assert(
        trailingSe0IsContiguous && trailingSe0Drive.size == timing.resumeEopSe0Cycles,
        diagnostic
      )
      assert(rawKHasNoPacketCompletion, diagnostic)
    }
  }

  test("reset command completes when issued from suspended state") {
    compileDut().doSim { dut =>
      initialize(dut)
      val monitor = new PortFsmMonitor(dut)
      attachLowSpeed(dut)

      val disabledControl = holdResetUntilReady(dut, monitor, timing.resetCycles + 20)
      dut.clockDomain.waitSampling(4)
      val suspendAccepted = pulseSuspend(dut)
      dut.clockDomain.waitSampling(2)
      val suspendedReset = holdResetUntilReady(dut, monitor, 96)

      dut.io.ctrl.usbReset #= true
      dut.clockDomain.waitSampling()
      val cleanupCycle = monitor.cycle
      dut.io.ctrl.usbReset #= false
      dut.clockDomain.waitSampling(2)

      val controlLatencyExpected = timing.resetCycles.toLong to (timing.resetCycles + 4).toLong
      val diagnostic =
        s"disabledControl=$disabledControl disabledLatency=${disabledControl.latencyCycles} " +
          s"expectedDisabledLatency=$controlLatencyExpected suspendAccepted=$suspendAccepted " +
          s"suspendedReset=$suspendedReset suspendedLatency=${suspendedReset.latencyCycles} " +
          s"suspendedDeadline=96 cleanupUsbResetCycle=$cleanupCycle " +
          s"connectCycles=${monitor.connectCycles.mkString("[", ",", "]")} " +
          s"controls=${formatControls(monitor.controls)}"

      assert(disabledControl.readyCycle.nonEmpty, diagnostic)
      assert(disabledControl.latencyCycles.exists(controlLatencyExpected.contains), diagnostic)
      assert(suspendAccepted, diagnostic)
      assert(suspendedReset.readyCycle.nonEmpty, diagnostic)
      assert(suspendedReset.latencyCycles.exists(_ <= 96), diagnostic)
    }
  }

  test("disable command from suspended state blocks remote resume on a following low-speed K") {
    compileDut().doSim { dut =>
      initialize(dut)
      val monitor = new PortFsmMonitor(dut)
      enableLowSpeedPort(dut, monitor)

      val suspendAccepted = pulseSuspend(dut)
      dut.clockDomain.waitSampling(2)

      val disable = dut.io.ctrl.ports(0).disable
      disable.valid #= true
      dut.clockDomain.waitSampling()
      val disableCycle = monitor.cycle
      val disableReady = disable.ready.toBoolean
      disable.valid #= false

      dut.io.utmi.lineState #= lowSpeedK
      val lowSpeedKStart = monitor.cycle
      dut.clockDomain.waitSampling(timing.resumeCycles + 96)

      val remoteResumeAfterDisable = monitor.remoteResumeCycles.filter(_ > disableCycle).toVector
      val controlAfterK = monitor.controls.filter(_.cycle > lowSpeedKStart).toVector
      val rawResume = controlAfterK.filter(_.lowSpeedRawK)
      val diagnostic =
        s"suspendAccepted=$suspendAccepted disableReady=$disableReady disableCycle=$disableCycle " +
          s"lowSpeedKStart=$lowSpeedKStart remoteResumeCycles=${remoteResumeAfterDisable.mkString("[", ",", "]")} " +
          s"rawResumeCycles=${rawResume.map(_.cycle).mkString("[", ",", "]")} " +
          s"controls=${formatControls(controlAfterK)}"

      assert(suspendAccepted, diagnostic)
      assert(disableReady, diagnostic)
      assert(remoteResumeAfterDisable.isEmpty, diagnostic)
      assert(rawResume.isEmpty, diagnostic)
    }
  }

  test("four-cycle SE0 glitch in disabled state neither disconnects nor reconnects the port") {
    compileDut().doSim { dut =>
      initialize(dut)
      val monitor = new PortFsmMonitor(dut)

      attachLowSpeed(dut)
      dut.clockDomain.waitSampling(8)
      val initialConnects = monitor.connectCycles.toVector

      val glitchStart = monitor.cycle
      dut.io.utmi.lineState #= se0
      dut.clockDomain.waitSampling(4)
      val returnToJCycle = monitor.cycle
      dut.io.utmi.lineState #= lowSpeedJ
      dut.clockDomain.waitSampling(timing.disconnectCycles * 2 + timing.attachDebounceCycles + 16)

      val disconnectsAfterGlitch = monitor.disconnectCycles.filter(_ > glitchStart).toVector
      val reconnectsAfterGlitch = monitor.connectCycles.filter(_ > glitchStart).toVector
      val diagnostic =
        s"initialConnectCycles=${initialConnects.mkString("[", ",", "]")} " +
          s"glitchSE0=($glitchStart,$returnToJCycle] glitchCycles=${returnToJCycle - glitchStart} " +
          s"disconnectCycles=${disconnectsAfterGlitch.mkString("[", ",", "]")} " +
          s"reconnectCycles=${reconnectsAfterGlitch.mkString("[", ",", "]")} " +
          s"controls=${formatControls(monitor.controls.filter(_.cycle > glitchStart))}"

      assert(initialConnects.size == 1, diagnostic)
      assert(returnToJCycle - glitchStart == 4, diagnostic)
      assert(disconnectsAfterGlitch.isEmpty, diagnostic)
      assert(reconnectsAfterGlitch.isEmpty, diagnostic)
    }
  }

  private def compileDut() =
    SimConfig.withVerilator.compile(UsbHubLsFsToUtmiPortFsmSimTop(timing))

  private def initialize(dut: UsbHubLsFsToUtmiPortFsmSimTop): Unit = {
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
    dut.io.utmi.lineState #= se0
    dut.io.utmi.vbusValid #= true
    dut.io.utmi.hostDisconnect #= false

    val port = dut.io.ctrl.ports(0)
    port.removable #= true
    port.power #= true
    port.disable.valid #= false
    port.reset.valid #= false
    port.suspend.valid #= false
    port.resume.valid #= false

    dut.io.usb.tx.enable #= false
    dut.io.usb.tx.data #= false
    dut.io.usb.tx.se0 #= false
    dut.io.usb.rx.dp #= false
    dut.io.usb.rx.dm #= false
    dut.clockDomain.waitSampling(5)
  }

  private def attachLowSpeed(dut: UsbHubLsFsToUtmiPortFsmSimTop): Unit = {
    dut.io.utmi.lineState #= lowSpeedJ
    dut.clockDomain.waitSampling(timing.attachDebounceCycles + 4)
  }

  private def enableLowSpeedPort(
      dut: UsbHubLsFsToUtmiPortFsmSimTop,
      monitor: PortFsmMonitor
  ): Unit = {
    attachLowSpeed(dut)
    val reset = holdResetUntilReady(dut, monitor, timing.resetCycles + 20)
    assert(reset.readyCycle.nonEmpty, resetFailure("enableLowSpeedPort", reset, monitor))
    dut.clockDomain.waitSampling(4)
  }

  private def holdResetUntilReady(
      dut: UsbHubLsFsToUtmiPortFsmSimTop,
      monitor: PortFsmMonitor,
      timeoutCycles: Int
  ): ResetObservation = {
    val reset = dut.io.ctrl.ports(0).reset
    val requestCycle = monitor.cycle
    var readyCycle = Option.empty[Long]
    var rawSe0Cycles = 0

    reset.valid #= true
    var remaining = timeoutCycles
    while (readyCycle.isEmpty && remaining > 0) {
      dut.clockDomain.waitSampling()
      val sample = monitor.controls.last
      if (sample.rawSe0) rawSe0Cycles += 1
      if (reset.ready.toBoolean) readyCycle = Some(monitor.cycle)
      remaining -= 1
    }
    reset.valid #= false
    ResetObservation(requestCycle, readyCycle, rawSe0Cycles)
  }

  private def pulseSuspend(dut: UsbHubLsFsToUtmiPortFsmSimTop): Boolean = {
    val suspend = dut.io.ctrl.ports(0).suspend
    suspend.valid #= true
    dut.clockDomain.waitSampling()
    val ready = suspend.ready.toBoolean
    suspend.valid #= false
    ready
  }

  private def holdResumeUntilReady(
      dut: UsbHubLsFsToUtmiPortFsmSimTop,
      timeoutCycles: Int
  ): Boolean = {
    require(timeoutCycles > 0)
    val resume = dut.io.ctrl.ports(0).resume
    resume.valid #= true
    var ready = false
    var remaining = timeoutCycles
    while (!ready && remaining > 0) {
      dut.clockDomain.waitSampling()
      ready = resume.ready.toBoolean
      remaining -= 1
    }
    resume.valid #= false
    ready
  }

  private def cyclesInWindow(
      cycles: scala.collection.Seq[Long],
      startExclusive: Long,
      endInclusive: Long
  ): Vector[Long] =
    cycles.filter(cycle => cycle > startExclusive && cycle <= endInclusive).toVector

  private def resetFailure(
      label: String,
      reset: ResetObservation,
      monitor: PortFsmMonitor
  ): String =
    s"$label reset did not become ready: observation=$reset connectCycles=${monitor.connectCycles.mkString("[", ",", "]")} " +
      s"disconnectCycles=${monitor.disconnectCycles.mkString("[", ",", "]")} controls=${formatControls(monitor.controls)}"

  private def formatControls(samples: scala.collection.Seq[PortFsmUtmiSample]): String = {
    if (samples.isEmpty) return "[]"

    val runs = ArrayBuffer.empty[(Long, Long, (Boolean, Boolean, Int, Int, Boolean))]
    var runStart = samples.head.cycle
    var runEnd = samples.head.cycle
    var tuple = samples.head.controlTuple
    for (sample <- samples.tail) {
      if (sample.controlTuple == tuple && sample.cycle == runEnd + 1) {
        runEnd = sample.cycle
      } else {
        runs += ((runStart, runEnd, tuple))
        runStart = sample.cycle
        runEnd = sample.cycle
        tuple = sample.controlTuple
      }
    }
    runs += ((runStart, runEnd, tuple))

    runs
      .map { case (start, end, (txValid, dataOe, opMode, xcvrSel, termSel)) =>
        s"$start-$end:(txValid=$txValid,dataOe=$dataOe,opMode=$opMode,xcvrSel=$xcvrSel,termSel=$termSel)"
      }
      .mkString("[", ", ", "]")
  }
}
