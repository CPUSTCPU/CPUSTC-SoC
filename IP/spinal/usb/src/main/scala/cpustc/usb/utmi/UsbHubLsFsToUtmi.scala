package cpustc.usb.utmi

import spinal.core._
import spinal.lib._
import spinal.lib.com.usb.ohci.UsbPid
import spinal.lib.com.usb.phy.UsbHubLsFs
import scala.language.postfixOps

/** USB3500 的 8-bit UTMI+ Link 侧接口。
  *
  * DATA 总线在模块边界拆成输入、输出和输出使能，物理 IOBUF 留给板级顶层处理。
  */
case class Usb3500UtmiIo() extends Bundle with IMasterSlave {
  val dataI: Bits = Bits(8 bits)
  val dataO: Bits = Bits(8 bits)
  val dataOe: Bool = Bool()
  val dataT: Bits = Bits(8 bits)

  val txValid: Bool = Bool()
  val txReady: Bool = Bool()
  val rxValid: Bool = Bool()
  val rxActive: Bool = Bool()
  val rxError: Bool = Bool()
  val lineState: Bits = Bits(2 bits)

  val xcvrSel: Bits = Bits(2 bits)
  val termSel: Bool = Bool()
  val opMode: Bits = Bits(2 bits)
  val suspendN: Bool = Bool()
  val dpPd: Bool = Bool()
  val dmPd: Bool = Bool()

  val vbusValid: Bool = Bool()
  val hostDisconnect: Bool = Bool()

  override def asMaster(): Unit = {
    out(dataO, dataOe, dataT, txValid, xcvrSel, termSel, opMode, suspendN, dpPd, dmPd)
    in(dataI, txReady, rxValid, rxActive, rxError, lineState, vbusValid, hostDisconnect)
  }
}

/** USB3500 UTMI 端口状态机使用的周期计数。
  *
  * 默认值对应 60 MHz UTMI 时钟；测试可缩短计数，板级生成保持默认值。
  */
case class UsbHubLsFsToUtmiTiming(
    attachDebounceCycles: Int = 500 * 60,
    disconnectCycles: Int = 3 * 60,
    resetCycles: Int = 50 * 1000 * 60,
    chirpFilterCycles: Int = 165,
    resumeCycles: Int = 20 * 1000 * 60,
    txEopTimeoutCycles: Int = 4096,
    fullSpeedInterPacketCycles: Int = 10,
    lowSpeedInterPacketCycles: Int = 80,
    resumeEopSe0Cycles: Int = 80,
    resumeEopJCycles: Int = 40
) {
  require(attachDebounceCycles > 1)
  require(disconnectCycles > 1)
  require(resetCycles > 1)
  require(chirpFilterCycles > 1)
  require(resumeCycles > 1)
  require(txEopTimeoutCycles > 1)
  require(fullSpeedInterPacketCycles > 1)
  require(lowSpeedInterPacketCycles > 1)
  require(resumeEopSe0Cycles > 1)
  require(resumeEopJCycles > 1)
}

/** UTMI 发送完成状态机导出的原生 60 MHz 调试信号。 */
case class UsbHubLsFsToUtmiDebug(
    waitCounterWidth: Int,
    ipdCounterWidth: Int,
    chirpFilterCounterWidth: Int
) extends Bundle {
  val txEopState: Bits = Bits(3 bits)
  val txLastAccepted: Bool = Bool()
  val ctrlTxEop: Bool = Bool()
  val txLaunchAllowed: Bool = Bool()
  val txFault: Bool = Bool()
  val txFaultReason: Bits = Bits(2 bits)
  val txWaitCounter: Bits = Bits(waitCounterWidth bits)
  val txIpdCounter: Bits = Bits(ipdCounterWidth bits)
  val portLowSpeed: Bool = Bool()
  val portResetActive: Bool = Bool()
  val debugSampleTick: Bool = Bool()
  val chirpCandidate: Bits = Bits(2 bits)
  val chirpFilterCounter: Bits = Bits(chirpFilterCounterWidth bits)
  val chirpStateQualified: Bool = Bool()
  val chirpQualifiedState: Bits = Bits(2 bits)
  val txBufferState0: Bits = Bits(2 bits)
  val txBufferState1: Bits = Bits(2 bits)
  val txOutputData: Bits = Bits(8 bits)
  val txOutputValid: Bool = Bool()
  val phyXcvrSel: Bits = Bits(2 bits)
  val phyTermSel: Bool = Bool()
  val phyOpMode: Bits = Bits(2 bits)
}

/** 将 SpinalHDL OHCI 的内部低速/全速 Hub 接口适配为 USB3500 UTMI+接口。
  *
  * 该模块只支持单端口固定 Host 的 LS/FS 模式，不实现 EHCI/HS、OTG、VBUS 开关或过流检测。
  * 输入时钟域必须是 USB3500 CLKOUT 提供的 60 MHz UTMI 时钟域。
  */
case class UsbHubLsFsToUtmi(
    timing: UsbHubLsFsToUtmiTiming = UsbHubLsFsToUtmiTiming(),
    txBufferDepth: Int = 1026,
    resetChirpDiagnostic: Boolean = false
) extends Component {
  require(txBufferDepth >= 1026)

  private val txWaitCounterWidth = log2Up(timing.txEopTimeoutCycles)
  private val txIpdCounterWidth =
    log2Up(timing.fullSpeedInterPacketCycles max timing.lowSpeedInterPacketCycles)
  private val chirpFilterCounterWidth = log2Up(timing.chirpFilterCycles + 1)
  private val stateCounterWidth = log2Up(
    timing.resetCycles max
      timing.resumeCycles max
      timing.resumeEopSe0Cycles max
      timing.resumeEopJCycles
  )

  val io = new Bundle {
    val ctrl: UsbHubLsFs.Ctrl = slave(UsbHubLsFs.Ctrl(1))
    val utmi: Usb3500UtmiIo = master(Usb3500UtmiIo())
    val debug: UsbHubLsFsToUtmiDebug =
      out(
        UsbHubLsFsToUtmiDebug(
          txWaitCounterWidth,
          txIpdCounterWidth,
          chirpFilterCounterWidth
        )
      )
  }

  object PortState extends SpinalEnum {
    val PowerOff, Disconnected, Disabled, Resetting, Enabled, Suspended, Resuming,
        ResumeEopSe0, ResumeEopJ = newElement()
  }

  object TxBufferState extends SpinalEnum {
    val Free, Filling, Ready, Sending = newElement()
  }

  object TxEopState extends SpinalEnum {
    val Idle, WaitSe0, WaitJ, InterPacket, FaultLock = newElement()
  }

  object TxPairState extends SpinalEnum {
    val Idle, AwaitData, PairReady, TokenPhysical, DataPending, DataPhysical = newElement()
  }

  object TxFaultReason extends SpinalEnum {
    val NoFault, WaitSe0Timeout, WaitJTimeout, PairProtocol = newElement()
  }

  val state = RegInit(PortState.PowerOff)
  val stateNext = PortState()
  stateNext := state
  val port = io.ctrl.ports(0)
  val portLowSpeed = RegInit(False)
  val portLowSpeedNext = Bool()
  portLowSpeedNext := portLowSpeed

  val lineSe0 = io.utmi.lineState === B"00"
  val lineFsJ = io.utmi.lineState === B"01"
  val lineLsJ = io.utmi.lineState === B"10"
  val lineJ = portLowSpeed ? lineLsJ | lineFsJ
  val lineAttached = lineFsJ || lineLsJ
  val lineK = portLowSpeed ? lineFsJ | lineLsJ

  val chirpDiagnosticActive =
    if (resetChirpDiagnostic) state === PortState.Resetting else False
  val chirpDiagnosticActivePrev = RegNext(chirpDiagnosticActive) init False
  val chirpCandidate = Reg(Bits(2 bits)) init 0
  val chirpFilterCounter = Reg(UInt(chirpFilterCounterWidth bits)) init 0
  val chirpStateQualified = RegInit(False)
  val chirpQualifiedState = Reg(Bits(2 bits)) init 0
  val chirpLineIsKOrJ = io.utmi.lineState === B"01" || io.utmi.lineState === B"10"

  when(!chirpDiagnosticActive || port.reset.ready) {
    chirpCandidate := io.utmi.lineState
    chirpFilterCounter := 0
    chirpStateQualified := False
    chirpQualifiedState := 0
  } elsewhen(!chirpDiagnosticActivePrev || io.utmi.lineState =/= chirpCandidate) {
    chirpCandidate := io.utmi.lineState
    chirpFilterCounter := 0
    chirpStateQualified := False
    chirpQualifiedState := 0
  } elsewhen(!chirpLineIsKOrJ) {
    chirpFilterCounter := 0
    chirpStateQualified := False
    chirpQualifiedState := 0
  } elsewhen(!chirpStateQualified) {
    when(chirpFilterCounter === timing.chirpFilterCycles - 1) {
      chirpFilterCounter := timing.chirpFilterCycles
      chirpStateQualified := True
      chirpQualifiedState := chirpCandidate
    } otherwise {
      chirpFilterCounter := chirpFilterCounter + 1
    }
  }

  val tickDivider = CounterFreeRun(5)
  val debugSampleDivider = CounterFreeRun(256)
  io.ctrl.tick := tickDivider.willOverflow
  io.ctrl.overcurrent := False

  io.utmi.dpPd := True
  io.utmi.dmPd := True
  // 第一阶段保持 PHY 时钟运行，避免 SUSPENDN 拉低后 CLKOUT 停止而无法在本时钟域唤醒。
  io.utmi.suspendN := True

  val phyXcvrSel = Reg(Bits(2 bits)) init B"01"
  val phyTermSel = RegInit(True)
  val phyOpMode = Reg(Bits(2 bits)) init B"00"
  val phyXcvrSelShadow = Reg(Bits(2 bits)) init B"01"
  val phyTermSelShadow = RegInit(True)
  val phyOpModeShadow = Reg(Bits(2 bits)) init B"00"
  phyXcvrSel.addAttribute("IOB", "TRUE")
  phyTermSel.addAttribute("IOB", "TRUE")
  phyOpMode.addAttribute("IOB", "TRUE")
  phyXcvrSel.addAttribute("DONT_TOUCH", "TRUE")
  phyTermSel.addAttribute("DONT_TOUCH", "TRUE")
  phyOpMode.addAttribute("DONT_TOUCH", "TRUE")
  phyXcvrSelShadow.addAttribute("DONT_TOUCH", "TRUE")
  phyTermSelShadow.addAttribute("DONT_TOUCH", "TRUE")
  phyOpModeShadow.addAttribute("DONT_TOUCH", "TRUE")
  io.utmi.xcvrSel := phyXcvrSel
  io.utmi.termSel := phyTermSel
  io.utmi.opMode := phyOpMode

  val txPhysicalEnabled = state === PortState.Enabled
  // 两个 bank 各缓存一个完整包，允许当前包发送时并行接收下一包。
  val txBuffer0 = StreamFifo(Fragment(Bits(8 bits)), txBufferDepth)
  val txBuffer1 = StreamFifo(Fragment(Bits(8 bits)), txBufferDepth)
  val txBufferState0 = Reg(TxBufferState()) init TxBufferState.Free
  val txBufferState1 = Reg(TxBufferState()) init TxBufferState.Free
  val txBufferPid0 = Reg(Bits(8 bits)) init 0
  val txBufferPid1 = Reg(Bits(8 bits)) init 0
  val txBufferLowSpeed0 = RegInit(False)
  val txBufferLowSpeed1 = RegInit(False)
  // 两个 bank 同时 Ready 时，记录先完成整包入队的 bank；False 对应 bank0，True 对应 bank1。
  val txFirstReadyBank1 = RegInit(False)
  val txPairState = Reg(TxPairState()) init TxPairState.Idle
  val txPairTokenBank1 = RegInit(False)
  val txPairLowSpeed = RegInit(False)
  val txEopState = Reg(TxEopState()) init TxEopState.Idle
  val txFaultReason = Reg(TxFaultReason()) init TxFaultReason.NoFault
  val txWaitCounter = Reg(UInt(txWaitCounterWidth bits)) init 0
  val txIpdCounter = Reg(UInt(txIpdCounterWidth bits)) init 0
  val txEopLowSpeed = RegInit(False)
  val txEopPulse = RegInit(False)
  val txIpdLast = txEopLowSpeed ?
    U(timing.lowSpeedInterPacketCycles - 1, txIpdCounterWidth bits) |
    U(timing.fullSpeedInterPacketCycles - 1, txIpdCounterWidth bits)
  val txIpdComplete =
    txEopState === TxEopState.InterPacket && lineJ && txIpdCounter === txIpdLast
  val txLaunchAllowed = txEopState === TxEopState.Idle || txIpdComplete
  val txFault = txEopState === TxEopState.FaultLock

  txBuffer0.io.flush := !txPhysicalEnabled
  txBuffer1.io.flush := !txPhysicalEnabled
  txBuffer0.io.push.fragment := io.ctrl.tx.fragment
  txBuffer0.io.push.last := io.ctrl.tx.last
  txBuffer1.io.push.fragment := io.ctrl.tx.fragment
  txBuffer1.io.push.last := io.ctrl.tx.last

  // 当前包收到 last 前，后续字节始终写入同一个 Filling bank。
  // 没有 Filling bank 时，新包优先选择空闲的 bank0，其次选择 bank1。
  val txFillAvailable = Bool()
  val txFillBank1 = Bool()
  txFillAvailable := True
  txFillBank1 := False
  when(txBufferState0 === TxBufferState.Filling) {
    txFillBank1 := False
  } elsewhen(txBufferState1 === TxBufferState.Filling) {
    txFillBank1 := True
  } elsewhen(txBufferState0 === TxBufferState.Free) {
    txFillBank1 := False
  } elsewhen(txBufferState1 === TxBufferState.Free) {
    txFillBank1 := True
  } otherwise {
    txFillAvailable := False
  }

  val txFillReady = txFillBank1 ? txBuffer1.io.push.ready | txBuffer0.io.push.ready
  // 端口不可物理发送时仍消费 OHCI 的共享 TX 流，并在 last 后完成逻辑 EOP。
  val txDropMode = !txPhysicalEnabled
  val txPairAcceptsInput =
    txPairState === TxPairState.Idle || txPairState === TxPairState.AwaitData
  io.ctrl.tx.ready :=
    !txFault && (txDropMode || (txPairAcceptsInput && txFillAvailable && txFillReady))
  txBuffer0.io.push.valid :=
    io.ctrl.tx.valid && txPhysicalEnabled && !txFault && txPairAcceptsInput && txFillAvailable && !txFillBank1
  txBuffer1.io.push.valid :=
    io.ctrl.tx.valid && txPhysicalEnabled && !txFault && txPairAcceptsInput && txFillAvailable && txFillBank1
  val txPushFire = io.ctrl.tx.valid && io.ctrl.tx.ready
  val txDropLast = txDropMode && txPushFire && io.ctrl.tx.last
  val txPushStartsPacket = txFillBank1 ?
    (txBufferState1 === TxBufferState.Free) |
    (txBufferState0 === TxBufferState.Free)
  val txCompletedPid = txPushStartsPacket ?
    io.ctrl.tx.fragment |
    (txFillBank1 ? txBufferPid1 | txBufferPid0)
  val txCompletedLowSpeed = txPushStartsPacket ?
    io.ctrl.lowSpeed |
    (txFillBank1 ? txBufferLowSpeed1 | txBufferLowSpeed0)
  val txCompletedIsPairToken =
    txCompletedPid === B(UsbPid.token(UsbPid.SETUP), 8 bits) ||
      txCompletedPid === B(UsbPid.token(UsbPid.OUT), 8 bits)
  val txCompletedIsPairData =
    txCompletedPid === B(UsbPid.token(UsbPid.DATA0), 8 bits) ||
      txCompletedPid === B(UsbPid.token(UsbPid.DATA1), 8 bits)

  // UTMI 只从已收齐整包的 Sending bank 取数，避免源端空拍中断 TXVALID。
  val txSending0 = txBufferState0 === TxBufferState.Sending
  val txSending1 = txBufferState1 === TxBufferState.Sending
  val txSendActive = txSending0 || txSending1
  val txSendingLowSpeed = txSending1 ? txBufferLowSpeed1 | txBufferLowSpeed0
  val txPopValid = (txSending0 && txBuffer0.io.pop.valid) || (txSending1 && txBuffer1.io.pop.valid)

  val txLaunchCandidateValid = Bool()
  val txLaunchCandidateBank1 = Bool()
  txLaunchCandidateValid := False
  txLaunchCandidateBank1 := False
  when(txPairState === TxPairState.PairReady) {
    txLaunchCandidateValid := txPairTokenBank1 ?
      (txBufferState1 === TxBufferState.Ready) |
      (txBufferState0 === TxBufferState.Ready)
    txLaunchCandidateBank1 := txPairTokenBank1
  } elsewhen(txPairState === TxPairState.DataPending) {
    txLaunchCandidateValid := txPairTokenBank1 ?
      (txBufferState0 === TxBufferState.Ready) |
      (txBufferState1 === TxBufferState.Ready)
    txLaunchCandidateBank1 := !txPairTokenBank1
  } elsewhen(txPairState === TxPairState.Idle) {
    when(txBufferState0 === TxBufferState.Ready && txBufferState1 === TxBufferState.Ready) {
      txLaunchCandidateValid := True
      txLaunchCandidateBank1 := txFirstReadyBank1
    } elsewhen(txBufferState0 === TxBufferState.Ready) {
      txLaunchCandidateValid := True
      txLaunchCandidateBank1 := False
    } elsewhen(txBufferState1 === TxBufferState.Ready) {
      txLaunchCandidateValid := True
      txLaunchCandidateBank1 := True
    }
  }
  val txLaunchCandidateLowSpeed =
    txLaunchCandidateBank1 ? txBufferLowSpeed1 | txBufferLowSpeed0
  val txLaunchCandidateXcvrSel = portLowSpeed ?
    B"10" |
    (txLaunchCandidateLowSpeed ? B"11" | B"01")

  // 物理输出级保存当前 UTMI beat；stall 时保持，握手时同时接受旧 beat 并装入下一 beat。
  val txOutputData = Reg(Bits(8 bits)) init 0
  val txOutputValid = RegInit(False)
  val txOutputDataT = Reg(Bits(8 bits)) init B"11111111"
  val txOutputLast = RegInit(False)
  val txOutputIsPacket = RegInit(False)
  val txOutputDataShadow = Reg(Bits(8 bits)) init 0
  val txOutputValidShadow = RegInit(False)
  txOutputData.addAttribute("IOB", "TRUE")
  txOutputValid.addAttribute("IOB", "TRUE")
  txOutputDataT.addAttribute("IOB", "TRUE")
  txOutputData.addAttribute("DONT_TOUCH", "TRUE")
  txOutputValid.addAttribute("DONT_TOUCH", "TRUE")
  txOutputDataT.addAttribute("DONT_TOUCH", "TRUE")
  txOutputDataShadow.addAttribute("DONT_TOUCH", "TRUE")
  txOutputValidShadow.addAttribute("DONT_TOUCH", "TRUE")

  io.utmi.dataO := txOutputData
  io.utmi.txValid := txOutputValid
  // 每个 DATA 位使用独立 T 极性寄存器，避免共享输出使能留在 fabric。
  io.utmi.dataT := txOutputDataT
  io.utmi.dataOe := txOutputValidShadow

  val txOutputFire = txOutputValidShadow && txOutputIsPacket && io.utmi.txReady
  val txOutputCanLoad = !txOutputValidShadow || txOutputFire
  val txPacketOutputEnabled = stateNext === PortState.Enabled
  txBuffer0.io.pop.ready :=
    txPacketOutputEnabled && txSending0 && txOutputCanLoad
  txBuffer1.io.pop.ready :=
    txPacketOutputEnabled && txSending1 && txOutputCanLoad

  val txLastAccepted = txOutputFire && txOutputLast

  val txOutputNextData = txSending1 ? txBuffer1.io.pop.fragment | txBuffer0.io.pop.fragment
  val txOutputDataLoad =
    stateNext === PortState.Resuming ||
      (txPacketOutputEnabled && txOutputCanLoad && txPopValid)
  when(txOutputDataLoad) {
    val txOutputLoadedData =
      (stateNext === PortState.Resuming) ? B(0, 8 bits) | txOutputNextData
    txOutputData := txOutputLoadedData
    txOutputDataShadow := txOutputLoadedData
  }

  // 单一写使能推导为 OUTFF/TFF 的 CE，避免物理寄存器 Q 回送 fabric 实现保持。
  val txOutputControlLoad = !txPacketOutputEnabled || txOutputCanLoad
  when(txOutputControlLoad) {
    val txOutputNextValid =
      stateNext === PortState.Resuming || (txPacketOutputEnabled && txPopValid)
    txOutputValid := txOutputNextValid
    txOutputValidShadow := txOutputNextValid
    txOutputDataT := txOutputNextValid ? B(0, 8 bits) | B(0xff, 8 bits)
    txOutputLast :=
      txPacketOutputEnabled && (txSending1 ? txBuffer1.io.pop.last | txBuffer0.io.pop.last)
    txOutputIsPacket := txPacketOutputEnabled && txPopValid
  }
  val txBufferedPacketComplete =
    txBufferState0 === TxBufferState.Ready ||
      txBufferState0 === TxBufferState.Sending ||
      txBufferState1 === TxBufferState.Ready ||
      txBufferState1 === TxBufferState.Sending
  // 端口状态在物理包中途变化时终止 UTMI 驱动，并释放正在等待 txEop 的 OHCI 事务。
  val txPhysicalAbort =
    !txPhysicalEnabled && txBufferedPacketComplete && txPairState =/= TxPairState.AwaitData
  val txEopWaitAbort = !txPhysicalEnabled &&
    (txEopState === TxEopState.WaitSe0 || txEopState === TxEopState.WaitJ)
  val txPairPhysicalEopPending =
    txPairState === TxPairState.TokenPhysical || txPairState === TxPairState.DataPhysical

  io.ctrl.txEop := txEopPulse
  txEopPulse := False

  when(!txPhysicalEnabled && (txEopState =/= TxEopState.FaultLock)) {
    when(txPhysicalAbort || txEopWaitAbort) {
      txEopPulse := True
    }
    txEopState := TxEopState.Idle
    txFaultReason := TxFaultReason.NoFault
    txWaitCounter := 0
    txIpdCounter := 0
  } otherwise {
    switch(txEopState) {
      is(TxEopState.Idle) {
        txWaitCounter := 0
        txIpdCounter := 0
        when(txLastAccepted) {
          txEopState := TxEopState.WaitSe0
        }
      }

      is(TxEopState.WaitSe0) {
        when(lineSe0) {
          txEopState := TxEopState.WaitJ
          txWaitCounter := 0
        } elsewhen(txWaitCounter === timing.txEopTimeoutCycles - 1) {
          txEopState := TxEopState.FaultLock
          txFaultReason := TxFaultReason.WaitSe0Timeout
          when(txPairPhysicalEopPending) {
            txEopPulse := True
            txPairState := TxPairState.Idle
          }
        } otherwise {
          txWaitCounter := txWaitCounter + 1
        }
      }

      is(TxEopState.WaitJ) {
        when(lineJ) {
          txEopState := TxEopState.InterPacket
          when(txPairState === TxPairState.TokenPhysical) {
            txPairState := TxPairState.DataPending
          } otherwise {
            txEopPulse := True
            when(txPairState === TxPairState.DataPhysical) {
              txPairState := TxPairState.Idle
            }
          }
          txWaitCounter := 0
          txIpdCounter := 0
        } elsewhen(txWaitCounter === timing.txEopTimeoutCycles - 1) {
          txEopState := TxEopState.FaultLock
          txFaultReason := TxFaultReason.WaitJTimeout
          when(txPairPhysicalEopPending) {
            txEopPulse := True
            txPairState := TxPairState.Idle
          }
        } otherwise {
          txWaitCounter := txWaitCounter + 1
        }
      }

      is(TxEopState.InterPacket) {
        when(lineJ) {
          when(txIpdCounter === txIpdLast) {
            txEopState := TxEopState.Idle
            txIpdCounter := 0
          } otherwise {
            txIpdCounter := txIpdCounter + 1
          }
        } otherwise {
          txIpdCounter := 0
        }
      }

      is(TxEopState.FaultLock) {
        // 粘滞故障只由当前 UTMI 时钟域复位清除。
      }
    }
  }

  when(txDropLast) {
    txEopPulse := True
  }

  // last 入队后封存整包；SETUP/OUT 先等待合法 DATA 配对，其他包保持原物理 EOP 语义。
  when(!txPhysicalEnabled) {
    txBufferState0 := TxBufferState.Free
    txBufferState1 := TxBufferState.Free
    txBufferLowSpeed0 := False
    txBufferLowSpeed1 := False
    txFirstReadyBank1 := False
    txPairState := TxPairState.Idle
    txPairTokenBank1 := False
    txPairLowSpeed := False
    txEopLowSpeed := False
  } otherwise {
    when(txPushFire) {
      when(txFillBank1) {
        when(txBufferState1 === TxBufferState.Free) {
          txBufferPid1 := io.ctrl.tx.fragment
          txBufferLowSpeed1 := io.ctrl.lowSpeed
        }
        txBufferState1 := io.ctrl.tx.last ? TxBufferState.Ready | TxBufferState.Filling
        when(io.ctrl.tx.last) {
          txFirstReadyBank1 := txBufferState0 =/= TxBufferState.Ready
        }
      } otherwise {
        when(txBufferState0 === TxBufferState.Free) {
          txBufferPid0 := io.ctrl.tx.fragment
          txBufferLowSpeed0 := io.ctrl.lowSpeed
        }
        txBufferState0 := io.ctrl.tx.last ? TxBufferState.Ready | TxBufferState.Filling
        when(io.ctrl.tx.last) {
          txFirstReadyBank1 := txBufferState1 === TxBufferState.Ready
        }
      }

      when(io.ctrl.tx.last) {
        when(txPairState === TxPairState.Idle && txCompletedIsPairToken) {
          txPairState := TxPairState.AwaitData
          txPairTokenBank1 := txFillBank1
          txPairLowSpeed := txCompletedLowSpeed
          txEopPulse := True
        } elsewhen(txPairState === TxPairState.AwaitData) {
          when(
            txCompletedIsPairData &&
              txFillBank1 =/= txPairTokenBank1 &&
              txCompletedLowSpeed === txPairLowSpeed
          ) {
            txPairState := TxPairState.PairReady
          } otherwise {
            txPairState := TxPairState.Idle
            txEopState := TxEopState.FaultLock
            txFaultReason := TxFaultReason.PairProtocol
            txEopPulse := True
          }
        }
      }
    }

    when(
      !txSendActive &&
        txLaunchAllowed &&
        txLaunchCandidateValid &&
        phyXcvrSel === txLaunchCandidateXcvrSel
    ) {
      when(txLaunchCandidateBank1) {
        txBufferState1 := TxBufferState.Sending
      } otherwise {
        txBufferState0 := TxBufferState.Sending
      }
      when(txPairState === TxPairState.PairReady) {
        txPairState := TxPairState.TokenPhysical
      } elsewhen(txPairState === TxPairState.DataPending) {
        txPairState := TxPairState.DataPhysical
      }
    }

    when(txLastAccepted) {
      txEopLowSpeed := portLowSpeed || txSendingLowSpeed
      when(txSending1) {
        txBufferState1 := TxBufferState.Free
      } otherwise {
        txBufferState0 := TxBufferState.Free
      }
    }
  }

  io.debug.txEopState := txEopState.asBits
  io.debug.txLastAccepted := txLastAccepted
  io.debug.ctrlTxEop := io.ctrl.txEop
  io.debug.txLaunchAllowed := txLaunchAllowed
  io.debug.txFault := txFault
  io.debug.txFaultReason := txFaultReason.asBits
  io.debug.txWaitCounter := txWaitCounter.asBits
  io.debug.txIpdCounter := txIpdCounter.asBits
  io.debug.portLowSpeed := portLowSpeed
  io.debug.portResetActive := state === PortState.Resetting
  io.debug.debugSampleTick := debugSampleDivider.willOverflow
  io.debug.chirpCandidate := chirpCandidate
  io.debug.chirpFilterCounter := chirpFilterCounter.asBits
  io.debug.chirpStateQualified := chirpStateQualified
  io.debug.chirpQualifiedState := chirpQualifiedState
  io.debug.txBufferState0 := txBufferState0.asBits
  io.debug.txBufferState1 := txBufferState1.asBits
  io.debug.txOutputData := txOutputDataShadow
  io.debug.txOutputValid := txOutputValidShadow
  io.debug.phyXcvrSel := phyXcvrSelShadow
  io.debug.phyTermSel := phyTermSelShadow
  io.debug.phyOpMode := phyOpModeShadow

  io.ctrl.rx.active := io.utmi.rxActive && state === PortState.Enabled
  // OHCI 只在 flow.valid 周期采样 stuffingError；RXERROR 独立于 RXVALID 时补一个错误 beat。
  io.ctrl.rx.flow.valid :=
    io.utmi.rxActive && (io.utmi.rxValid || io.utmi.rxError) && state === PortState.Enabled
  io.ctrl.rx.flow.data := io.utmi.dataI
  io.ctrl.rx.flow.stuffingError := io.utmi.rxError

  port.lowSpeed := portLowSpeed
  port.overcurrent := False
  port.connect := False
  port.disconnect := False
  port.remoteResume := False
  port.disable.ready := True
  port.reset.ready := False
  port.suspend.ready := False
  port.resume.ready := False

  val attachCounter = Reg(UInt(log2Up(timing.attachDebounceCycles) bits)) init 0
  val disconnectCounter = Reg(UInt(log2Up(timing.disconnectCycles) bits)) init 0
  val stateCounter = Reg(UInt(stateCounterWidth bits)) init 0
  // 参考 UsbLsFsPhy：断连过滤在 Disabled、Enabled 和 Suspended 三个状态持续工作。
  val disconnectMonitored =
    state === PortState.Disabled || state === PortState.Enabled || state === PortState.Suspended
  val disconnectEvent = Bool()
  disconnectEvent := False

  when(disconnectMonitored && (lineSe0 || !io.utmi.vbusValid)) {
    when(disconnectCounter === timing.disconnectCycles - 1) {
      disconnectCounter := 0
      disconnectEvent := True
    } otherwise {
      disconnectCounter := disconnectCounter + 1
    }
  } otherwise {
    disconnectCounter := 0
  }

  when(io.ctrl.usbReset || !port.power) {
    stateNext := PortState.PowerOff
    attachCounter := 0
    disconnectCounter := 0
    stateCounter := 0
  } otherwise {
    when(disconnectEvent) {
      port.disconnect := True
      stateCounter := 0
      stateNext := PortState.Disconnected
    } otherwise {
      switch(state) {
        is(PortState.PowerOff) {
          stateNext := PortState.Disconnected
        }

        is(PortState.Disconnected) {
          when(io.utmi.vbusValid && lineAttached) {
            when(attachCounter === timing.attachDebounceCycles - 1) {
              portLowSpeedNext := lineLsJ
              port.connect := True
              attachCounter := 0
              stateNext := PortState.Disabled
            } otherwise {
              attachCounter := attachCounter + 1
            }
          } otherwise {
            attachCounter := 0
          }
        }

        is(PortState.Disabled) {
          when(port.reset.valid) {
            stateCounter := 0
            stateNext := PortState.Resetting
          }
        }

        is(PortState.Resetting) {
          when(stateCounter === timing.resetCycles - 1) {
            stateCounter := 0
            port.reset.ready := True
            stateNext := PortState.Enabled
          } otherwise {
            stateCounter := stateCounter + 1
          }
        }

        is(PortState.Enabled) {
          when(port.disable.valid) {
            stateNext := PortState.Disabled
          } elsewhen(port.suspend.valid) {
            port.suspend.ready := True
            stateNext := PortState.Suspended
          } elsewhen(port.reset.valid) {
            stateCounter := 0
            stateNext := PortState.Resetting
          } elsewhen(io.ctrl.usbResume || port.resume.valid) {
            stateCounter := 0
            stateNext := PortState.Resuming
          }
        }

        is(PortState.Suspended) {
          when(port.disable.valid) {
            stateNext := PortState.Disabled
          } elsewhen(port.reset.valid) {
            stateCounter := 0
            stateNext := PortState.Resetting
          } elsewhen(port.resume.valid || io.ctrl.usbResume || lineK) {
            port.remoteResume := lineK
            stateCounter := 0
            stateNext := PortState.Resuming
          }
        }

        is(PortState.Resuming) {
          when(stateCounter === timing.resumeCycles - 1) {
            stateCounter := 0
            port.resume.ready := True
            stateNext := PortState.ResumeEopSe0
          } otherwise {
            stateCounter := stateCounter + 1
          }
        }

        is(PortState.ResumeEopSe0) {
          when(stateCounter === timing.resumeEopSe0Cycles - 1) {
            stateCounter := 0
            stateNext := PortState.ResumeEopJ
          } otherwise {
            stateCounter := stateCounter + 1
          }
        }

        is(PortState.ResumeEopJ) {
          // 保持一个低速 bit time 的 J 窗口，然后恢复包发送。
          when(stateCounter === timing.resumeEopJCycles - 1) {
            stateCounter := 0
            stateNext := PortState.Enabled
          } otherwise {
            stateCounter := stateCounter + 1
          }
        }
      }
    }
  }

  val phyXcvrSelNext = Bits(2 bits)
  val phyTermSelNext = Bool()
  val phyOpModeNext = Bits(2 bits)
  val phyEnabledXcvrSel = Bits(2 bits)
  phyEnabledXcvrSel := portLowSpeedNext ?
    B"10" |
    (io.ctrl.lowSpeed ? B"11" | B"01")
  when(txLaunchCandidateValid) {
    phyEnabledXcvrSel := portLowSpeedNext ?
      B"10" |
      (txLaunchCandidateLowSpeed ? B"11" | B"01")
  }
  when(txSendActive) {
    phyEnabledXcvrSel := portLowSpeedNext ?
      B"10" |
      (txSendingLowSpeed ? B"11" | B"01")
  }
  val phyEnabledModeChangeAllowed =
    !txOutputValidShadow &&
      !io.utmi.rxActive &&
      (txEopState === TxEopState.Idle || txIpdComplete)

  phyXcvrSelNext := portLowSpeedNext ? B"10" | B"01"
  phyTermSelNext := True
  phyOpModeNext := B"00"

  switch(stateNext) {
    is(PortState.Enabled) {
      when(phyEnabledModeChangeAllowed) {
        phyXcvrSelNext := phyEnabledXcvrSel
      } otherwise {
        phyXcvrSelNext := phyXcvrSel
      }
    }
    is(PortState.Resetting) {
      // 选择 HS 端接会在下行端口产生 SE0；本设计不进入 HS 握手。
      phyXcvrSelNext := B"00"
      phyTermSelNext := False
      if (resetChirpDiagnostic) {
        // TXVALID 保持低，只切换 LINESTATE 到 Chirp K/J 解释模式。
        phyOpModeNext := B"10"
      }
    }
    is(PortState.Resuming) {
      phyOpModeNext := B"10"
    }
    is(PortState.ResumeEopSe0) {
      // USB 2.0 7.1.7.7：resume K 后驱动两个低速 bit time 的 SE0。
      phyXcvrSelNext := B"00"
      phyTermSelNext := False
    }
  }

  state := stateNext
  portLowSpeed := portLowSpeedNext
  phyXcvrSel := phyXcvrSelNext
  phyTermSel := phyTermSelNext
  phyOpMode := phyOpModeNext
  phyXcvrSelShadow := phyXcvrSelNext
  phyTermSelShadow := phyTermSelNext
  phyOpModeShadow := phyOpModeNext
}
