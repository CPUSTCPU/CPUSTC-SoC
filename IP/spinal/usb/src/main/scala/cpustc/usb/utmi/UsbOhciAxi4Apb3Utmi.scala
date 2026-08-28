package cpustc.usb.utmi

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba3.apb.{Apb3, Apb3Config, Apb3ToBmb}
import spinal.lib.bus.amba4.axi.Axi4
import spinal.lib.bus.bmb.{BmbCcFifo, BmbToAxi4SharedBridge}
import spinal.lib.com.usb.ohci.{UsbOhci, UsbOhciParameter}

/** 单端口 OHCI Host 的 APB3 控制、AXI4 DMA 和 USB3500 UTMI+集成顶层。 */
case class UsbOhciAxi4Apb3Utmi(
    p: UsbOhciParameter,
    frontCd: ClockDomain,
    backCd: ClockDomain,
    dmaCd: ClockDomain,
    utmiTiming: UsbHubLsFsToUtmiTiming = UsbHubLsFsToUtmiTiming(),
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
    val debugRxEventOverflow = out Bool()
    val debugRxEventCollision = out Bool()
    val debug = out(
      UsbHubLsFsToUtmiDebug(
        waitCounterWidth = log2Up(utmiTiming.txEopTimeoutCycles),
        ipdCounterWidth = log2Up(
          utmiTiming.fullSpeedInterPacketCycles max utmiTiming.lowSpeedInterPacketCycles
        ),
        chirpFilterCounterWidth = log2Up(utmiTiming.chirpFilterCycles + 1)
      )
    )
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

    // OpenHCI: disconnecting an enabled port clears PES and raises PESC/RHSC.
    ohci.rework {
      for ((status, port) <- (ohci.reg.hcRhPortStatus, ohci.io.phy.ports).zipped) {
        status.PESC.set setWhen (port.disconnect && status.PES)
      }
    }
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
    io.debug := adapter.io.debug
  }

  val cc = UsbHubLsFsCtrlCc(p.portCount, frontCd, backCdPatched)
  cc.input <> front.ohci.io.phy
  cc.output <> back.adapter.io.ctrl
  io.debugRxEventOverflow := cc.rxEventOverflow
  io.debugRxEventCollision := cc.rxEventCollision

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
