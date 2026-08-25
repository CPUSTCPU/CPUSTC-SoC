package cpustc.usb.utmi

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba3.apb.{Apb3, Apb3Config, Apb3ToBmb}
import spinal.lib.bus.amba4.axi.Axi4
import spinal.lib.bus.bmb.{BmbCcFifo, BmbToAxi4SharedBridge}
import spinal.lib.com.usb.ohci.{UsbOhci, UsbOhciParameter}

/** Vivado 中需创建的显式 UTMI EOP 调试 ILA，所有探针均属于 60 MHz UTMI 时钟域。 */
case class UsbUtmiTxEopIla(
    waitCounterWidth: Int,
    ipdCounterWidth: Int,
    chirpFilterCounterWidth: Int
) extends BlackBox {
  setDefinitionName("ila_usb_utmi_eop")
  noIoPrefix()

  val io = new Bundle {
    val clk = in Bool()
    val probe0 = in Bits (8 bits)
    val probe1 = in Bool()
    val probe2 = in Bool()
    val probe3 = in Bits (2 bits)
    val probe4 = in Bits (3 bits)
    val probe5 = in Bool()
    val probe6 = in Bool()
    val probe7 = in Bool()
    val probe8 = in Bool()
    val probe9 = in Bits (2 bits)
    val probe10 = in Bits (waitCounterWidth bits)
    val probe11 = in Bits (ipdCounterWidth bits)
    val probe12 = in Bool()
    val probe13 = in Bits (2 bits)
    val probe14 = in Bits (2 bits)
    val probe15 = in Bool()
    val probe16 = in Bool()
    val probe17 = in Bits (8 bits)
    val probe18 = in Bits (2 bits)
    val probe19 = in Bool()
    val probe20 = in Bits (2 bits)
    val probe21 = in Bool()
    val probe22 = in Bool()
    val probe23 = in Bits (2 bits)
    val probe24 = in Bits (chirpFilterCounterWidth bits)
    val probe25 = in Bool()
    val probe26 = in Bits (2 bits)
  }
}

/** 单端口 OHCI Host 的 APB3 控制、AXI4 DMA 和 USB3500 UTMI+集成顶层。 */
case class UsbOhciAxi4Apb3Utmi(
    p: UsbOhciParameter,
    frontCd: ClockDomain,
    backCd: ClockDomain,
    dmaCd: ClockDomain,
    utmiTiming: UsbHubLsFsToUtmiTiming = UsbHubLsFsToUtmiTiming(),
    withTxEopIla: Boolean = false,
    withClockSofDiagnostic: Boolean = false,
    resetChirpDiagnostic: Boolean = false
) extends Component {
  require(p.portCount == 1, "UsbOhciAxi4Apb3Utmi only supports one USB3500 port")

  val ctrlParameter = Apb3Config(
    addressWidth = 12,
    dataWidth = 32
  )

  val dmaParameter = BmbToAxi4SharedBridge.getAxi4Config(UsbOhci.dmaParameter(p))

  val io = new Bundle {
    val dma = master(Axi4(dmaParameter))
    val ctrl = slave(Apb3(ctrlParameter))
    val interrupt = out Bool()
    val utmi = master(Usb3500UtmiIo())
  }

  val front = frontCd on new Area {
    val ctrlBridge = new Apb3ToBmb(ctrlParameter)
    val ctrlWriteData = Bits(ctrlParameter.dataWidth bits)
    ctrlWriteData := io.ctrl.PWDATA

    // 板级能力固定，阻止软件写回不存在的 VBUS 开关和过流检测能力。
    if (p.noPowerSwitching) {
      when(io.ctrl.PADDR === U(UsbOhci.HcRhDescriptorA, ctrlParameter.addressWidth bits)) {
        ctrlWriteData(8) := False
        ctrlWriteData(9) := True
      }
      when(io.ctrl.PADDR === U(UsbOhci.HcRhDescriptorB, ctrlParameter.addressWidth bits)) {
        ctrlWriteData(17 + p.portCount - 1 downto 17) := 0
      }
    }
    if (p.noOverCurrentProtection) {
      when(io.ctrl.PADDR === U(UsbOhci.HcRhDescriptorA, ctrlParameter.addressWidth bits)) {
        ctrlWriteData(11) := False
        ctrlWriteData(12) := True
      }
    }

    ctrlBridge.io.apb.PADDR := io.ctrl.PADDR
    ctrlBridge.io.apb.PSEL := io.ctrl.PSEL
    ctrlBridge.io.apb.PENABLE := io.ctrl.PENABLE
    ctrlBridge.io.apb.PWRITE := io.ctrl.PWRITE
    ctrlBridge.io.apb.PWDATA := ctrlWriteData
    io.ctrl.PREADY := ctrlBridge.io.apb.PREADY
    io.ctrl.PRDATA := ctrlBridge.io.apb.PRDATA
    io.ctrl.PSLVERROR := ctrlBridge.io.apb.PSLVERROR

    val ohci = UsbOhci(p, ctrlBridge.io.bmb.p)
    ohci.io.ctrl <> ctrlBridge.io.bmb
    ohci.io.interrupt <> io.interrupt
  }

  val dma = dmaCd on new Area {
    val bmb = cloneOf(front.ohci.io.dma)
    val noCC = dmaCd == frontCd generate new Area {
      bmb << front.ohci.io.dma
    }
    val cc = dmaCd != frontCd generate new Area {
      val bridge = new BmbCcFifo(bmb.p, 2, 16, frontCd, dmaCd)
      bridge.io.input << front.ohci.io.dma
      bmb << bridge.io.output
    }

    val dmaBridge = BmbToAxi4SharedBridge(UsbOhci.dmaParameter(p))
    dmaBridge.io.output.toAxi4() >> io.dma
    dmaBridge.io.input << bmb
  }

  val backCdPatched = backCd.hasResetSignal.mux(
    backCd,
    ResetCtrl.asyncAssertSyncDeassertCreateCd(
      resetCd = frontCd,
      clockCd = backCd
    )
  )
  val back = backCdPatched on new Area {
    val adapter = UsbHubLsFsToUtmi(
      timing = utmiTiming,
      resetChirpDiagnostic = resetChirpDiagnostic
    )
    io.utmi <> adapter.io.utmi

    val txEopIla = withTxEopIla generate new Area {
      val core = UsbUtmiTxEopIla(
        waitCounterWidth = log2Up(utmiTiming.txEopTimeoutCycles),
        ipdCounterWidth = log2Up(
          utmiTiming.fullSpeedInterPacketCycles max utmiTiming.lowSpeedInterPacketCycles
        ),
        chirpFilterCounterWidth = log2Up(utmiTiming.chirpFilterCycles + 1)
      )
      core.io.clk := ClockDomain.current.readClockWire
      // IOB寄存器的Q端只驱动引脚；ILA观察同拍shadow，避免破坏OLOGIC放置。
      core.io.probe0 := adapter.io.debug.txOutputData
      core.io.probe1 := adapter.io.debug.txOutputValid
      core.io.probe2 := adapter.io.utmi.txReady
      core.io.probe3 := adapter.io.utmi.lineState
      core.io.probe4 := adapter.io.debug.txEopState
      core.io.probe5 := adapter.io.debug.txLastAccepted
      core.io.probe6 := adapter.io.debug.ctrlTxEop
      core.io.probe7 := adapter.io.debug.txLaunchAllowed
      core.io.probe8 := adapter.io.debug.txFault
      core.io.probe9 := adapter.io.debug.txFaultReason
      core.io.probe10 := adapter.io.debug.txWaitCounter
      core.io.probe11 := adapter.io.debug.txIpdCounter
      core.io.probe12 := adapter.io.debug.portLowSpeed
      core.io.probe13 := adapter.io.debug.txBufferState0
      core.io.probe14 := adapter.io.debug.txBufferState1
      core.io.probe15 := adapter.io.utmi.rxActive
      core.io.probe16 := adapter.io.utmi.rxValid
      core.io.probe17 := adapter.io.utmi.dataI
      core.io.probe18 := adapter.io.debug.phyXcvrSel
      core.io.probe19 := adapter.io.debug.phyTermSel
      core.io.probe20 := adapter.io.debug.phyOpMode
      core.io.probe21 := adapter.io.debug.portResetActive
      core.io.probe22 := adapter.io.debug.debugSampleTick
      core.io.probe23 := adapter.io.debug.chirpCandidate
      core.io.probe24 := adapter.io.debug.chirpFilterCounter
      core.io.probe25 := adapter.io.debug.chirpStateQualified
      core.io.probe26 := adapter.io.debug.chirpQualifiedState
    }
  }

  val cc = UsbHubLsFsCtrlCc(p.portCount, frontCd, backCdPatched)
  cc.input <> front.ohci.io.phy
  cc.output <> back.adapter.io.ctrl

  val clockSofDiagnostic = withClockSofDiagnostic generate new Area {
    val core = UsbClockSofDiagnostic(
      sourceCd = backCdPatched,
      frontCd = frontCd,
      withIla = true
    )
    core.io.heartbeat := back.adapter.io.debug.debugSampleTick
    core.io.sourceTick := cc.output.tick
    core.io.destinationTick := cc.input.tick
    core.io.portResetActive := back.adapter.io.debug.portResetActive
    core.io.txData := back.adapter.io.debug.txOutputData
    core.io.txValid := back.adapter.io.debug.txOutputValid
    core.io.txReady := back.adapter.io.utmi.txReady
    core.io.rxEventOverflow := cc.rxEventOverflow
    core.io.rxEventCollision := cc.rxEventCollision
  }
}
