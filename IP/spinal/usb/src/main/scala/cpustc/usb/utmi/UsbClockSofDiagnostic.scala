package cpustc.usb.utmi

import spinal.core._
import spinal.lib._

/** 33 MHz 域内保存的 USB 时钟、Reset、SOF 和 SETUP 诊断状态。 */
case class UsbClockSofDiagnosticStatus() extends Bundle {
  val timestamp = UInt(32 bits)
  val captureQualifier = Bool()
  val heartbeatEvent = Bool()
  val heartbeatInterval = UInt(16 bits)
  val heartbeatIntervalMin = UInt(16 bits)
  val heartbeatIntervalMax = UInt(16 bits)
  val heartbeatAge = UInt(16 bits)
  val heartbeatTimeout = Bool()
  val heartbeatRangeFault = Bool()
  val heartbeatCount = UInt(32 bits)
  val heartbeatSeen = Bool()
  val portResetActive = Bool()
  val resetDurationCurrent = UInt(24 bits)
  val resetDurationLast = UInt(24 bits)
  val resetCount = UInt(16 bits)
  val sofEvent = Bool()
  val sofInterval = UInt(32 bits)
  val sofCount = UInt(16 bits)
  val sofSeen = Bool()
  val resetToFirstSof = UInt(32 bits)
  val firstSofAfterReset = Bool()
  val awaitingFirstSof = Bool()
  val setupEvent = Bool()
  val setupTimestamp = UInt(32 bits)
  val setupCount = UInt(16 bits)
  val sourceTickCount = UInt(32 bits)
  val destinationTickCount = UInt(32 bits)
  val tickCountDelta = SInt(8 bits)
  val rxEventOverflow = Bool()
  val rxEventCollision = Bool()
  val fault = Bool()
}

/** Vivado 中的 33 MHz USB 时钟与 SOF 诊断 ILA。 */
case class UsbClockSofIla() extends BlackBox {
  setDefinitionName("ila_usb_clock_sof_diag")
  noIoPrefix()

  val io = new Bundle {
    val clk = in Bool()
    val probe0 = in Bits (32 bits)
    val probe1 = in Bool()
    val probe2 = in Bool()
    val probe3 = in Bits (16 bits)
    val probe4 = in Bits (16 bits)
    val probe5 = in Bits (16 bits)
    val probe6 = in Bits (16 bits)
    val probe7 = in Bits (2 bits)
    val probe8 = in Bits (32 bits)
    val probe9 = in Bool()
    val probe10 = in Bits (24 bits)
    val probe11 = in Bits (24 bits)
    val probe12 = in Bits (16 bits)
    val probe13 = in Bool()
    val probe14 = in Bits (32 bits)
    val probe15 = in Bits (16 bits)
    val probe16 = in Bits (32 bits)
    val probe17 = in Bool()
    val probe18 = in Bool()
    val probe19 = in Bits (32 bits)
    val probe20 = in Bits (16 bits)
    val probe21 = in Bits (32 bits)
    val probe22 = in Bits (32 bits)
    val probe23 = in Bits (2 bits)
    val probe24 = in Bits (3 bits)
    val probe25 = in Bool()
    val probe26 = in Bits (8 bits)
  }
}

/**
  * 只观测 60 MHz UTMI 事件，并在 33 MHz 控制域测量周期与累计计数。
  * 不向 USB 控制或数据通路产生反馈。
  */
case class UsbClockSofDiagnostic(
    sourceCd: ClockDomain,
    frontCd: ClockDomain,
    withIla: Boolean
) extends Component {
  val io = new Bundle {
    val heartbeat = in Bool()
    val sourceTick = in Bool()
    val destinationTick = in Bool()
    val portResetActive = in Bool()
    val txData = in Bits (8 bits)
    val txValid = in Bool()
    val txReady = in Bool()
    val rxEventOverflow = in Bool()
    val rxEventCollision = in Bool()
    val status = out(UsbClockSofDiagnosticStatus())
  }

  private val source = sourceCd on new Area {
    val txPacketActive = RegInit(False)
    val sourceTickCount = Reg(UInt(32 bits)) init 0
    val sourceTickCountNext = sourceTickCount + 1
    val sourceTickShifted = (B(0, 1 bits) ## sourceTickCountNext(31 downto 1).asBits).asUInt
    val sourceTickGray = Reg(Bits(32 bits)) init 0
    val portResetActive = RegNext(io.portResetActive) init False
    val txFirstAccepted = io.txValid && io.txReady && !txPacketActive
    val sof = txFirstAccepted && io.txData === B(0xa5, 8 bits)
    val setup = txFirstAccepted && io.txData === B(0x2d, 8 bits)

    when(!io.txValid) {
      txPacketActive := False
    }
    when(io.txValid && io.txReady) {
      txPacketActive := True
    }
    when(io.sourceTick) {
      sourceTickCount := sourceTickCountNext
      sourceTickGray := (sourceTickCountNext ^ sourceTickShifted).asBits
    }
  }

  private val heartbeat = PulseCCByToggle(io.heartbeat, sourceCd, frontCd)
  private val sof = PulseCCByToggle(source.sof, sourceCd, frontCd)
  private val setup = PulseCCByToggle(source.setup, sourceCd, frontCd)
  private val sourceTickGray = frontCd(BufferCC.withTag(source.sourceTickGray, B(0, 32 bits)))
  private val portResetActive = frontCd(BufferCC.withTag(source.portResetActive, False))
  private val rxEventOverflow = frontCd(BufferCC.withTag(io.rxEventOverflow, False))
  private val rxEventCollision = frontCd(BufferCC.withTag(io.rxEventCollision, False))

  private val front = frontCd on new Area {
    private val intervalMaximum = U(0xffff, 16 bits)
    private val resetDurationMaximum = U(0xffffff, 24 bits)

    val timestamp = Reg(UInt(32 bits)) init 0
    timestamp := timestamp + 1

    val heartbeatSeen = RegInit(False)
    val heartbeatInterval = Reg(UInt(16 bits)) init 0
    val heartbeatIntervalMin = Reg(UInt(16 bits)) init intervalMaximum
    val heartbeatIntervalMax = Reg(UInt(16 bits)) init 0
    val heartbeatAge = Reg(UInt(16 bits)) init 0
    val heartbeatTimeout = RegInit(False)
    val heartbeatRangeFault = RegInit(False)
    val heartbeatCount = Reg(UInt(32 bits)) init 0
    val nextHeartbeatInterval = UInt(16 bits)
    nextHeartbeatInterval := heartbeatAge
    when(heartbeatAge =/= intervalMaximum) {
      nextHeartbeatInterval := heartbeatAge + 1
    }

    when(heartbeat) {
      heartbeatCount := heartbeatCount + 1
      when(heartbeatSeen) {
        heartbeatInterval := nextHeartbeatInterval
        when(nextHeartbeatInterval < heartbeatIntervalMin) {
          heartbeatIntervalMin := nextHeartbeatInterval
        }
        when(nextHeartbeatInterval > heartbeatIntervalMax) {
          heartbeatIntervalMax := nextHeartbeatInterval
        }
        when(nextHeartbeatInterval < 128 || nextHeartbeatInterval > 154) {
          heartbeatRangeFault := True
        }
      }
      heartbeatSeen := True
      heartbeatAge := 0
    } elsewhen (heartbeatAge =/= intervalMaximum) {
      heartbeatAge := heartbeatAge + 1
    }
    when(!heartbeat && heartbeatAge >= 511) {
      heartbeatTimeout := True
    }

    val portResetActiveLast = RegNext(portResetActive) init False
    val resetRise = portResetActive && !portResetActiveLast
    val resetFall = !portResetActive && portResetActiveLast
    val resetDurationCurrent = Reg(UInt(24 bits)) init 0
    val resetDurationLast = Reg(UInt(24 bits)) init 0
    val resetCount = Reg(UInt(16 bits)) init 0
    val resetEndTimestamp = Reg(UInt(32 bits)) init 0
    val awaitingFirstSof = RegInit(False)

    when(resetRise) {
      resetDurationCurrent := 1
      awaitingFirstSof := False
    } elsewhen (portResetActive && resetDurationCurrent =/= resetDurationMaximum) {
      resetDurationCurrent := resetDurationCurrent + 1
    }
    when(resetFall) {
      resetDurationLast := resetDurationCurrent
      resetDurationCurrent := 0
      resetCount := resetCount + 1
      resetEndTimestamp := timestamp
      awaitingFirstSof := True
    }

    val sofSeen = RegInit(False)
    val sofTimestamp = Reg(UInt(32 bits)) init 0
    val sofInterval = Reg(UInt(32 bits)) init 0
    val sofCount = Reg(UInt(16 bits)) init 0
    val resetToFirstSof = Reg(UInt(32 bits)) init 0
    val firstSofAfterReset = sof && awaitingFirstSof
    when(sof) {
      when(sofSeen) {
        sofInterval := timestamp - sofTimestamp
      }
      when(awaitingFirstSof) {
        resetToFirstSof := timestamp - resetEndTimestamp
        awaitingFirstSof := False
      }
      sofTimestamp := timestamp
      sofSeen := True
      sofCount := sofCount + 1
    }

    val setupTimestamp = Reg(UInt(32 bits)) init 0
    val setupCount = Reg(UInt(16 bits)) init 0
    when(setup) {
      setupTimestamp := timestamp
      setupCount := setupCount + 1
    }

    val sourceTickBinary = Vec(Bool(), 32)
    sourceTickBinary(31) := sourceTickGray(31)
    for (bit <- 30 downto 0) {
      sourceTickBinary(bit) := sourceTickBinary(bit + 1) ^ sourceTickGray(bit)
    }
    val sourceTickCount = sourceTickBinary.asBits.asUInt
    val destinationTickCount = Reg(UInt(32 bits)) init 0
    when(io.destinationTick) {
      destinationTickCount := destinationTickCount + 1
    }
    val sourceTickBaseline = Reg(UInt(32 bits)) init 0
    val destinationTickBaseline = Reg(UInt(32 bits)) init 0
    val tickBaselineValid = RegInit(False)
    when(heartbeat && !tickBaselineValid) {
      sourceTickBaseline := sourceTickCount
      destinationTickBaseline := destinationTickCount
      tickBaselineValid := True
    }
    val tickCountDelta = SInt(8 bits)
    tickCountDelta := 0
    when(tickBaselineValid) {
      tickCountDelta := ((sourceTickCount - sourceTickBaseline) -
        (destinationTickCount - destinationTickBaseline)).resize(8).asSInt
    }

    val fault = heartbeatTimeout || heartbeatRangeFault || rxEventOverflow || rxEventCollision
    val faultLast = RegNext(fault) init False
    val faultRise = fault && !faultLast
    val captureDivider = Reg(UInt(5 bits)) init 0
    val captureHeartbeat = heartbeat && captureDivider === 31
    when(heartbeat) {
      when(captureDivider === 31) {
        captureDivider := 0
      } otherwise {
        captureDivider := captureDivider + 1
      }
    }
    val captureEvent = captureHeartbeat || resetRise || resetFall || sof || setup || faultRise
    val captureEventDelayed = RegNext(captureEvent) init False

    val status = UsbClockSofDiagnosticStatus()
    status.timestamp := timestamp
    status.captureQualifier := captureEvent || captureEventDelayed
    status.heartbeatEvent := heartbeat
    status.heartbeatInterval := heartbeatInterval
    status.heartbeatIntervalMin := heartbeatIntervalMin
    status.heartbeatIntervalMax := heartbeatIntervalMax
    status.heartbeatAge := heartbeatAge
    status.heartbeatTimeout := heartbeatTimeout
    status.heartbeatRangeFault := heartbeatRangeFault
    status.heartbeatCount := heartbeatCount
    status.heartbeatSeen := heartbeatSeen
    status.portResetActive := portResetActive
    status.resetDurationCurrent := resetDurationCurrent
    status.resetDurationLast := resetDurationLast
    status.resetCount := resetCount
    status.sofEvent := sof
    status.sofInterval := sofInterval
    status.sofCount := sofCount
    status.sofSeen := sofSeen
    status.resetToFirstSof := resetToFirstSof
    status.firstSofAfterReset := firstSofAfterReset
    status.awaitingFirstSof := awaitingFirstSof
    status.setupEvent := setup
    status.setupTimestamp := setupTimestamp
    status.setupCount := setupCount
    status.sourceTickCount := sourceTickCount
    status.destinationTickCount := destinationTickCount
    status.tickCountDelta := tickCountDelta
    status.rxEventOverflow := rxEventOverflow
    status.rxEventCollision := rxEventCollision
    status.fault := fault

    val ila = withIla generate new Area {
      val core = UsbClockSofIla()
      core.io.clk := ClockDomain.current.readClockWire
      core.io.probe0 := status.timestamp.asBits
      core.io.probe1 := status.captureQualifier
      core.io.probe2 := status.heartbeatEvent
      core.io.probe3 := status.heartbeatInterval.asBits
      core.io.probe4 := status.heartbeatIntervalMin.asBits
      core.io.probe5 := status.heartbeatIntervalMax.asBits
      core.io.probe6 := status.heartbeatAge.asBits
      core.io.probe7 := status.heartbeatTimeout ## status.heartbeatRangeFault
      core.io.probe8 := status.heartbeatCount.asBits
      core.io.probe9 := status.portResetActive
      core.io.probe10 := status.resetDurationCurrent.asBits
      core.io.probe11 := status.resetDurationLast.asBits
      core.io.probe12 := status.resetCount.asBits
      core.io.probe13 := status.sofEvent
      core.io.probe14 := status.sofInterval.asBits
      core.io.probe15 := status.sofCount.asBits
      core.io.probe16 := status.resetToFirstSof.asBits
      core.io.probe17 := status.firstSofAfterReset
      core.io.probe18 := status.setupEvent
      core.io.probe19 := status.setupTimestamp.asBits
      core.io.probe20 := status.setupCount.asBits
      core.io.probe21 := status.sourceTickCount.asBits
      core.io.probe22 := status.destinationTickCount.asBits
      core.io.probe23 := status.rxEventOverflow ## status.rxEventCollision
      core.io.probe24 := status.heartbeatSeen ## status.sofSeen ## status.awaitingFirstSof
      core.io.probe25 := status.fault
      core.io.probe26 := status.tickCountDelta.asBits
    }
  }

  io.status := front.status
}
