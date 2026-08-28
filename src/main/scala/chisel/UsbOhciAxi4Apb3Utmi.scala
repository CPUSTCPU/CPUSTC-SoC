package chisel

import chisel3._
import chisel3.experimental.ExtModule
import chisel3.util.Cat

class RawUsbOhciAxi4Apb3Utmi extends ExtModule {
  override def desiredName: String = "UsbOhciAxi4Apb3Utmi"

  val io_dma_aw_valid:         Bool = IO(Output(Bool()))
  val io_dma_aw_ready:         Bool = IO(Input(Bool()))
  val io_dma_aw_payload_addr:  UInt = IO(Output(UInt(32.W)))
  val io_dma_aw_payload_len:   UInt = IO(Output(UInt(8.W)))
  val io_dma_aw_payload_size:  UInt = IO(Output(UInt(3.W)))
  val io_dma_aw_payload_burst: UInt = IO(Output(UInt(2.W)))
  val io_dma_aw_payload_cache: UInt = IO(Output(UInt(4.W)))
  val io_dma_aw_payload_prot:  UInt = IO(Output(UInt(3.W)))
  val io_dma_w_valid:          Bool = IO(Output(Bool()))
  val io_dma_w_ready:          Bool = IO(Input(Bool()))
  val io_dma_w_payload_data:   UInt = IO(Output(UInt(32.W)))
  val io_dma_w_payload_strb:   UInt = IO(Output(UInt(4.W)))
  val io_dma_w_payload_last:   Bool = IO(Output(Bool()))
  val io_dma_b_valid:          Bool = IO(Input(Bool()))
  val io_dma_b_ready:          Bool = IO(Output(Bool()))
  val io_dma_b_payload_resp:   UInt = IO(Input(UInt(2.W)))
  val io_dma_ar_valid:         Bool = IO(Output(Bool()))
  val io_dma_ar_ready:         Bool = IO(Input(Bool()))
  val io_dma_ar_payload_addr:  UInt = IO(Output(UInt(32.W)))
  val io_dma_ar_payload_len:   UInt = IO(Output(UInt(8.W)))
  val io_dma_ar_payload_size:  UInt = IO(Output(UInt(3.W)))
  val io_dma_ar_payload_burst: UInt = IO(Output(UInt(2.W)))
  val io_dma_ar_payload_cache: UInt = IO(Output(UInt(4.W)))
  val io_dma_ar_payload_prot:  UInt = IO(Output(UInt(3.W)))
  val io_dma_r_valid:          Bool = IO(Input(Bool()))
  val io_dma_r_ready:          Bool = IO(Output(Bool()))
  val io_dma_r_payload_data:   UInt = IO(Input(UInt(32.W)))
  val io_dma_r_payload_resp:   UInt = IO(Input(UInt(2.W)))
  val io_dma_r_payload_last:   Bool = IO(Input(Bool()))

  val io_ctrl_PADDR:     UInt = IO(Input(UInt(12.W)))
  val io_ctrl_PSEL:      UInt = IO(Input(UInt(1.W)))
  val io_ctrl_PENABLE:   Bool = IO(Input(Bool()))
  val io_ctrl_PREADY:    Bool = IO(Output(Bool()))
  val io_ctrl_PWRITE:    Bool = IO(Input(Bool()))
  val io_ctrl_PWDATA:    UInt = IO(Input(UInt(32.W)))
  val io_ctrl_PRDATA:    UInt = IO(Output(UInt(32.W)))
  val io_ctrl_PSLVERROR: Bool = IO(Output(Bool()))
  val io_interrupt:      Bool = IO(Output(Bool()))

  val io_utmi_dataI:          UInt = IO(Input(UInt(8.W)))
  val io_utmi_dataO:          UInt = IO(Output(UInt(8.W)))
  val io_utmi_dataOe:         Bool = IO(Output(Bool()))
  val io_utmi_dataT:          UInt = IO(Output(UInt(8.W)))
  val io_utmi_txValid:        Bool = IO(Output(Bool()))
  val io_utmi_txReady:        Bool = IO(Input(Bool()))
  val io_utmi_rxValid:        Bool = IO(Input(Bool()))
  val io_utmi_rxActive:       Bool = IO(Input(Bool()))
  val io_utmi_rxError:        Bool = IO(Input(Bool()))
  val io_utmi_lineState:      UInt = IO(Input(UInt(2.W)))
  val io_utmi_xcvrSel:        UInt = IO(Output(UInt(2.W)))
  val io_utmi_termSel:        Bool = IO(Output(Bool()))
  val io_utmi_opMode:         UInt = IO(Output(UInt(2.W)))
  val io_utmi_suspendN:       Bool = IO(Output(Bool()))
  val io_utmi_dpPd:           Bool = IO(Output(Bool()))
  val io_utmi_dmPd:           Bool = IO(Output(Bool()))
  val io_utmi_vbusValid:      Bool = IO(Input(Bool()))
  val io_utmi_hostDisconnect: Bool = IO(Input(Bool()))

  val io_debug_txEopState:          UInt = IO(Output(UInt(3.W)))
  val io_debug_txLastAccepted:      Bool = IO(Output(Bool()))
  val io_debug_ctrlTxEop:            Bool = IO(Output(Bool()))
  val io_debug_txLaunchAllowed:      Bool = IO(Output(Bool()))
  val io_debug_txFault:              Bool = IO(Output(Bool()))
  val io_debug_txFaultReason:        UInt = IO(Output(UInt(2.W)))
  val io_debug_txWaitCounter:        UInt = IO(Output(UInt(12.W)))
  val io_debug_txIpdCounter:         UInt = IO(Output(UInt(7.W)))
  val io_debug_portLowSpeed:         Bool = IO(Output(Bool()))
  val io_debug_portState:            UInt = IO(Output(UInt(4.W)))
  val io_debug_portConnectPulse:     Bool = IO(Output(Bool()))
  val io_debug_portDisconnectPulse:  Bool = IO(Output(Bool()))
  val io_debug_portResetActive:      Bool = IO(Output(Bool()))
  val io_debug_debugSampleTick:      Bool = IO(Output(Bool()))
  val io_debug_chirpCandidate:       UInt = IO(Output(UInt(2.W)))
  val io_debug_chirpFilterCounter:   UInt = IO(Output(UInt(8.W)))
  val io_debug_chirpStateQualified:  Bool = IO(Output(Bool()))
  val io_debug_chirpQualifiedState:  UInt = IO(Output(UInt(2.W)))
  val io_debug_txBufferState0:       UInt = IO(Output(UInt(2.W)))
  val io_debug_txBufferState1:       UInt = IO(Output(UInt(2.W)))
  val io_debug_txOutputData:         UInt = IO(Output(UInt(8.W)))
  val io_debug_txOutputValid:        Bool = IO(Output(Bool()))
  val io_debug_phyXcvrSel:            UInt = IO(Output(UInt(2.W)))
  val io_debug_phyTermSel:            Bool = IO(Output(Bool()))
  val io_debug_phyOpMode:             UInt = IO(Output(UInt(2.W)))
  val io_debugRxEventOverflow:        Bool = IO(Output(Bool()))
  val io_debugRxEventCollision:       Bool = IO(Output(Bool()))

  val ctrl_clk:   Clock = IO(Input(Clock()))
  val ctrl_reset: Bool  = IO(Input(Bool()))
  val utmi_clk:   Clock = IO(Input(Clock()))
  val utmi_reset: Bool  = IO(Input(Bool()))
  val dma_clk:    Clock = IO(Input(Clock()))
  val dma_reset:  Bool  = IO(Input(Bool()))
}

/** USB UTMI 调试候选使用的 44 路、60 MHz ILA。 */
class UsbUtmiIla extends ExtModule {
  override def desiredName: String = "ila_usb_utmi_eop"

  val clk: Clock = IO(Input(Clock()))
  val probe0: UInt = IO(Input(UInt(8.W)))
  val probe1: Bool = IO(Input(Bool()))
  val probe2: Bool = IO(Input(Bool()))
  val probe3: UInt = IO(Input(UInt(2.W)))
  val probe4: UInt = IO(Input(UInt(3.W)))
  val probe5: Bool = IO(Input(Bool()))
  val probe6: Bool = IO(Input(Bool()))
  val probe7: Bool = IO(Input(Bool()))
  val probe8: Bool = IO(Input(Bool()))
  val probe9: UInt = IO(Input(UInt(2.W)))
  val probe10: UInt = IO(Input(UInt(12.W)))
  val probe11: UInt = IO(Input(UInt(7.W)))
  val probe12: Bool = IO(Input(Bool()))
  val probe13: UInt = IO(Input(UInt(2.W)))
  val probe14: UInt = IO(Input(UInt(2.W)))
  val probe15: Bool = IO(Input(Bool()))
  val probe16: Bool = IO(Input(Bool()))
  val probe17: UInt = IO(Input(UInt(8.W)))
  val probe18: UInt = IO(Input(UInt(2.W)))
  val probe19: Bool = IO(Input(Bool()))
  val probe20: UInt = IO(Input(UInt(2.W)))
  val probe21: Bool = IO(Input(Bool()))
  val probe22: Bool = IO(Input(Bool()))
  val probe23: UInt = IO(Input(UInt(2.W)))
  val probe24: UInt = IO(Input(UInt(8.W)))
  val probe25: Bool = IO(Input(Bool()))
  val probe26: UInt = IO(Input(UInt(2.W)))
  val probe27: UInt = IO(Input(UInt(28.W)))
  val probe28: UInt = IO(Input(UInt(16.W)))
  val probe29: UInt = IO(Input(UInt(4.W)))
  val probe30: UInt = IO(Input(UInt(28.W)))
  val probe31: UInt = IO(Input(UInt(28.W)))
  val probe32: UInt = IO(Input(UInt(28.W)))
  val probe33: UInt = IO(Input(UInt(28.W)))
  val probe34: UInt = IO(Input(UInt(28.W)))
  val probe35: UInt = IO(Input(UInt(28.W)))
  val probe36: UInt = IO(Input(UInt(28.W)))
  val probe37: UInt = IO(Input(UInt(28.W)))
  val probe38: UInt = IO(Input(UInt(16.W)))
  val probe39: UInt = IO(Input(UInt(16.W)))
  val probe40: UInt = IO(Input(UInt(16.W)))
  val probe41: UInt = IO(Input(UInt(16.W)))
  val probe42: UInt = IO(Input(UInt(16.W)))
  val probe43: UInt = IO(Input(UInt(16.W)))
}

class UsbPreSetupDebugMonitor extends Module {
  private val TimestampWidth = 28
  private val CounterWidth = 16

  val io = IO(new Bundle {
    val txData = Input(UInt(8.W))
    val txValid = Input(Bool())
    val txReady = Input(Bool())
    val txLastAccepted = Input(Bool())
    val lineState = Input(UInt(2.W))
    val vbusValid = Input(Bool())
    val hostDisconnect = Input(Bool())
    val rxActive = Input(Bool())
    val phyXcvrSel = Input(UInt(2.W))
    val phyTermSel = Input(Bool())
    val phyOpMode = Input(UInt(2.W))
    val portState = Input(UInt(4.W))
    val portConnectPulse = Input(Bool())
    val portDisconnectPulse = Input(Bool())
    val portResetActive = Input(Bool())
    val rxEventOverflow = Input(Bool())
    val rxEventCollision = Input(Bool())

    val timestamp = Output(UInt(TimestampWidth.W))
    val eventFlags = Output(UInt(16.W))
    val attachCandidateTimestamp = Output(UInt(TimestampWidth.W))
    val attachStableTimestamp = Output(UInt(TimestampWidth.W))
    val resetStartTimestamp = Output(UInt(TimestampWidth.W))
    val resetEndTimestamp = Output(UInt(TimestampWidth.W))
    val resetDurationCycles = Output(UInt(TimestampWidth.W))
    val firstSofTimestamp = Output(UInt(TimestampWidth.W))
    val firstSetupTimestamp = Output(UInt(TimestampWidth.W))
    val maxSofGapCycles = Output(UInt(TimestampWidth.W))
    val resetCount = Output(UInt(CounterWidth.W))
    val setupCountAfterReset = Output(UInt(CounterWidth.W))
    val sofCountBeforeSetup = Output(UInt(CounterWidth.W))
    val unexpectedPacketCountBeforeSetup = Output(UInt(CounterWidth.W))
    val vbusFallCount = Output(UInt(CounterWidth.W))
    val disconnectCount = Output(UInt(CounterWidth.W))
  })

  private def incrementSaturating(counter: UInt): Unit = {
    when(!counter.andR) {
      counter := counter + 1.U
    }
  }

  val timestamp = RegInit(0.U(TimestampWidth.W))
  timestamp := timestamp + 1.U

  val attachCandidateTimestamp = RegInit(0.U(TimestampWidth.W))
  val attachStableTimestamp = RegInit(0.U(TimestampWidth.W))
  val resetStartTimestamp = RegInit(0.U(TimestampWidth.W))
  val resetEndTimestamp = RegInit(0.U(TimestampWidth.W))
  val resetDurationCycles = RegInit(0.U(TimestampWidth.W))
  val firstSofTimestamp = RegInit(0.U(TimestampWidth.W))
  val firstSetupTimestamp = RegInit(0.U(TimestampWidth.W))
  val maxSofGapCycles = RegInit(0.U(TimestampWidth.W))
  val lastSofTimestamp = RegInit(0.U(TimestampWidth.W))

  val resetCount = RegInit(0.U(CounterWidth.W))
  val setupCountAfterReset = RegInit(0.U(CounterWidth.W))
  val sofCountBeforeSetup = RegInit(0.U(CounterWidth.W))
  val unexpectedPacketCountBeforeSetup = RegInit(0.U(CounterWidth.W))
  val vbusFallCount = RegInit(0.U(CounterWidth.W))
  val disconnectCount = RegInit(0.U(CounterWidth.W))

  val attachCandidateValid = RegInit(false.B)
  val attachStableValid = RegInit(false.B)
  val resetStartValid = RegInit(false.B)
  val resetEndValid = RegInit(false.B)
  val firstSofValid = RegInit(false.B)
  val firstSetupValid = RegInit(false.B)
  val afterReset = RegInit(false.B)
  val waitingForFirstSetup = RegInit(false.B)
  val sofBaselineValid = RegInit(false.B)
  val txPacketInProgress = RegInit(false.B)
  val phyModeChangedWhileTxRx = RegInit(false.B)

  // Spinal PortState.Disconnected is encoding 1; gating avoids treating reset recovery J as a new attach.
  val attachCandidate =
    io.portState === 1.U && io.vbusValid && (io.lineState === "b01".U || io.lineState === "b10".U)
  val attachCandidatePrev = RegNext(attachCandidate, false.B)
  val vbusValidPrev = RegNext(io.vbusValid, false.B)
  val resetActivePrev = RegNext(io.portResetActive, false.B)
  val phyXcvrSelPrev = RegNext(io.phyXcvrSel, 0.U)
  val phyTermSelPrev = RegNext(io.phyTermSel, false.B)
  val phyOpModePrev = RegNext(io.phyOpMode, 0.U)

  when(attachCandidate && !attachCandidatePrev) {
    attachCandidateTimestamp := timestamp
    attachCandidateValid := true.B
  }
  when(io.portConnectPulse) {
    attachStableTimestamp := timestamp
    attachStableValid := true.B
  }
  when(vbusValidPrev && !io.vbusValid) {
    incrementSaturating(vbusFallCount)
  }
  when(io.portDisconnectPulse) {
    incrementSaturating(disconnectCount)
  }

  val resetStarted = io.portResetActive && !resetActivePrev
  val resetEnded = !io.portResetActive && resetActivePrev
  when(resetStarted) {
    resetStartTimestamp := timestamp
    resetStartValid := true.B
    resetEndValid := false.B
    firstSofValid := false.B
    firstSetupValid := false.B
    afterReset := false.B
    waitingForFirstSetup := false.B
    sofBaselineValid := false.B
    setupCountAfterReset := 0.U
    sofCountBeforeSetup := 0.U
    unexpectedPacketCountBeforeSetup := 0.U
    maxSofGapCycles := 0.U
    incrementSaturating(resetCount)
  }
  when(resetEnded) {
    resetEndTimestamp := timestamp
    resetDurationCycles := timestamp - resetStartTimestamp
    resetEndValid := true.B
    afterReset := true.B
    waitingForFirstSetup := true.B
    lastSofTimestamp := timestamp
    sofBaselineValid := true.B
  }

  val txFire = io.txValid && io.txReady
  val txPacketStart = txFire && !txPacketInProgress
  when(txFire && io.txLastAccepted) {
    txPacketInProgress := false.B
  }.elsewhen(txPacketStart) {
    txPacketInProgress := true.B
  }

  val isSetup = io.txData === "h2d".U
  val isSof = io.txData === "ha5".U
  when(txPacketStart && afterReset) {
    when(isSetup) {
      incrementSaturating(setupCountAfterReset)
      when(!firstSetupValid) {
        firstSetupTimestamp := timestamp
        firstSetupValid := true.B
        waitingForFirstSetup := false.B
      }
    }.elsewhen(isSof && waitingForFirstSetup) {
      incrementSaturating(sofCountBeforeSetup)
      when(!firstSofValid) {
        firstSofTimestamp := timestamp
        firstSofValid := true.B
      }
      when(sofBaselineValid) {
        val sofGap = timestamp - lastSofTimestamp
        when(sofGap > maxSofGapCycles) {
          maxSofGapCycles := sofGap
        }
      }
      lastSofTimestamp := timestamp
      sofBaselineValid := true.B
    }.elsewhen(waitingForFirstSetup) {
      incrementSaturating(unexpectedPacketCountBeforeSetup)
    }
  }

  val phyModeChanged =
    io.phyXcvrSel =/= phyXcvrSelPrev || io.phyTermSel =/= phyTermSelPrev || io.phyOpMode =/= phyOpModePrev
  when(phyModeChanged && (io.txValid || io.rxActive)) {
    phyModeChangedWhileTxRx := true.B
  }

  io.timestamp := timestamp
  // bit 15..0: disconnect/connect/in-packet/reset/mode fault/RX collision/RX overflow/
  // host-disconnect/VBUS/waiting/first SETUP/first SOF/reset end/reset start/stable attach/attach candidate.
  io.eventFlags := Cat(
    io.portDisconnectPulse,
    io.portConnectPulse,
    txPacketInProgress,
    io.portResetActive,
    phyModeChangedWhileTxRx,
    io.rxEventCollision,
    io.rxEventOverflow,
    io.hostDisconnect,
    io.vbusValid,
    waitingForFirstSetup,
    firstSetupValid,
    firstSofValid,
    resetEndValid,
    resetStartValid,
    attachStableValid,
    attachCandidateValid
  )
  io.attachCandidateTimestamp := attachCandidateTimestamp
  io.attachStableTimestamp := attachStableTimestamp
  io.resetStartTimestamp := resetStartTimestamp
  io.resetEndTimestamp := resetEndTimestamp
  io.resetDurationCycles := resetDurationCycles
  io.firstSofTimestamp := firstSofTimestamp
  io.firstSetupTimestamp := firstSetupTimestamp
  io.maxSofGapCycles := maxSofGapCycles
  io.resetCount := resetCount
  io.setupCountAfterReset := setupCountAfterReset
  io.sofCountBeforeSetup := sofCountBeforeSetup
  io.unexpectedPacketCountBeforeSetup := unexpectedPacketCountBeforeSetup
  io.vbusFallCount := vbusFallCount
  io.disconnectCount := disconnectCount
}

class UsbOhciAxi4Apb3UtmiWrapperIO extends Bundle {
  val ctrlClock: Clock = Input(Clock())
  val ctrlReset: Bool  = Input(Bool())
  val dmaClock:  Clock = Input(Clock())
  val dmaReset:  Bool  = Input(Bool())
  val utmiClock: Clock = Input(Clock())
  val utmiReset: Bool  = Input(Bool())
  val dma:       AXI4IO = new AXI4IO(lenWidth = 8)
  val ctrl:      APB3IO = Flipped(new APB3IO(addrWidth = 12))
  val utmi:      Usb3500UtmiPort = new Usb3500UtmiPort
  val interrupt: Bool = Output(Bool())
}

class UsbOhciAxi4Apb3Utmi(withUsbIla: Boolean = false) extends RawModule {
  override def desiredName: String = "UsbOhciAxi4Apb3UtmiChisel"

  val io: UsbOhciAxi4Apb3UtmiWrapperIO = IO(new UsbOhciAxi4Apb3UtmiWrapperIO)

  val raw: RawUsbOhciAxi4Apb3Utmi = Module(new RawUsbOhciAxi4Apb3Utmi)

  raw.ctrl_clk   := io.ctrlClock
  raw.ctrl_reset := io.ctrlReset
  raw.dma_clk    := io.dmaClock
  raw.dma_reset  := io.dmaReset
  raw.utmi_clk   := io.utmiClock
  raw.utmi_reset := io.utmiReset

  io.dma.awid     := 0.U
  io.dma.awaddr   := raw.io_dma_aw_payload_addr
  io.dma.awlen    := raw.io_dma_aw_payload_len
  io.dma.awsize   := raw.io_dma_aw_payload_size
  io.dma.awburst  := raw.io_dma_aw_payload_burst
  io.dma.awlock   := 0.U
  io.dma.awcache  := raw.io_dma_aw_payload_cache
  io.dma.awprot   := raw.io_dma_aw_payload_prot
  io.dma.awqos    := 0.U
  io.dma.awregion := 0.U
  io.dma.awvalid  := raw.io_dma_aw_valid
  raw.io_dma_aw_ready := io.dma.awready

  io.dma.wdata  := raw.io_dma_w_payload_data
  io.dma.wstrb  := raw.io_dma_w_payload_strb
  io.dma.wlast  := raw.io_dma_w_payload_last
  io.dma.wvalid := raw.io_dma_w_valid
  raw.io_dma_w_ready := io.dma.wready

  raw.io_dma_b_valid        := io.dma.bvalid
  raw.io_dma_b_payload_resp := io.dma.bresp
  io.dma.bready             := raw.io_dma_b_ready

  io.dma.arid     := 0.U
  io.dma.araddr   := raw.io_dma_ar_payload_addr
  io.dma.arlen    := raw.io_dma_ar_payload_len
  io.dma.arsize   := raw.io_dma_ar_payload_size
  io.dma.arburst  := raw.io_dma_ar_payload_burst
  io.dma.arlock   := 0.U
  io.dma.arcache  := raw.io_dma_ar_payload_cache
  io.dma.arprot   := raw.io_dma_ar_payload_prot
  io.dma.arqos    := 0.U
  io.dma.arregion := 0.U
  io.dma.arvalid  := raw.io_dma_ar_valid
  raw.io_dma_ar_ready := io.dma.arready

  raw.io_dma_r_valid        := io.dma.rvalid
  raw.io_dma_r_payload_data := io.dma.rdata
  raw.io_dma_r_payload_resp := io.dma.rresp
  raw.io_dma_r_payload_last := io.dma.rlast
  io.dma.rready             := raw.io_dma_r_ready

  raw.io_ctrl_PADDR   := io.ctrl.paddr
  raw.io_ctrl_PSEL    := io.ctrl.psel
  raw.io_ctrl_PENABLE := io.ctrl.penable
  io.ctrl.pready      := raw.io_ctrl_PREADY
  raw.io_ctrl_PWRITE  := io.ctrl.pwrite
  raw.io_ctrl_PWDATA  := io.ctrl.pwdata
  io.ctrl.prdata      := raw.io_ctrl_PRDATA
  io.ctrl.pslverr     := raw.io_ctrl_PSLVERROR
  io.interrupt        := raw.io_interrupt

  raw.io_utmi_dataI          := io.utmi.dataI
  io.utmi.dataO              := raw.io_utmi_dataO
  io.utmi.dataOe             := raw.io_utmi_dataOe
  io.utmi.dataT              := raw.io_utmi_dataT
  io.utmi.txValid            := raw.io_utmi_txValid
  raw.io_utmi_txReady        := io.utmi.txReady
  raw.io_utmi_rxValid        := io.utmi.rxValid
  raw.io_utmi_rxActive       := io.utmi.rxActive
  raw.io_utmi_rxError        := io.utmi.rxError
  raw.io_utmi_lineState      := io.utmi.lineState
  io.utmi.xcvrSel            := raw.io_utmi_xcvrSel
  io.utmi.termSel            := raw.io_utmi_termSel
  io.utmi.opMode             := raw.io_utmi_opMode
  io.utmi.suspendN           := raw.io_utmi_suspendN
  io.utmi.dpPd               := raw.io_utmi_dpPd
  io.utmi.dmPd               := raw.io_utmi_dmPd
  raw.io_utmi_vbusValid      := io.utmi.vbusValid
  raw.io_utmi_hostDisconnect := io.utmi.hostDisconnect

  if (withUsbIla) {
    val monitor = withClockAndReset(io.utmiClock, io.utmiReset) {
      Module(new UsbPreSetupDebugMonitor)
    }
    monitor.io.txData := raw.io_debug_txOutputData
    monitor.io.txValid := raw.io_debug_txOutputValid
    monitor.io.txReady := io.utmi.txReady
    monitor.io.txLastAccepted := raw.io_debug_txLastAccepted
    monitor.io.lineState := io.utmi.lineState
    monitor.io.vbusValid := io.utmi.vbusValid
    monitor.io.hostDisconnect := io.utmi.hostDisconnect
    monitor.io.rxActive := io.utmi.rxActive
    monitor.io.phyXcvrSel := raw.io_debug_phyXcvrSel
    monitor.io.phyTermSel := raw.io_debug_phyTermSel
    monitor.io.phyOpMode := raw.io_debug_phyOpMode
    monitor.io.portState := raw.io_debug_portState
    monitor.io.portConnectPulse := raw.io_debug_portConnectPulse
    monitor.io.portDisconnectPulse := raw.io_debug_portDisconnectPulse
    monitor.io.portResetActive := raw.io_debug_portResetActive
    monitor.io.rxEventOverflow := raw.io_debugRxEventOverflow
    monitor.io.rxEventCollision := raw.io_debugRxEventCollision

    val ila = Module(new UsbUtmiIla)
    ila.clk := io.utmiClock
    ila.probe0 := raw.io_debug_txOutputData
    ila.probe1 := raw.io_debug_txOutputValid
    ila.probe2 := io.utmi.txReady
    ila.probe3 := io.utmi.lineState
    ila.probe4 := raw.io_debug_txEopState
    ila.probe5 := raw.io_debug_txLastAccepted
    ila.probe6 := raw.io_debug_ctrlTxEop
    ila.probe7 := raw.io_debug_txLaunchAllowed
    ila.probe8 := raw.io_debug_txFault
    ila.probe9 := raw.io_debug_txFaultReason
    ila.probe10 := raw.io_debug_txWaitCounter
    ila.probe11 := raw.io_debug_txIpdCounter
    ila.probe12 := raw.io_debug_portLowSpeed
    ila.probe13 := raw.io_debug_txBufferState0
    ila.probe14 := raw.io_debug_txBufferState1
    ila.probe15 := io.utmi.rxActive
    ila.probe16 := io.utmi.rxValid
    ila.probe17 := io.utmi.dataI
    ila.probe18 := raw.io_debug_phyXcvrSel
    ila.probe19 := raw.io_debug_phyTermSel
    ila.probe20 := raw.io_debug_phyOpMode
    ila.probe21 := raw.io_debug_portResetActive
    ila.probe22 := raw.io_debug_debugSampleTick
    ila.probe23 := raw.io_debug_chirpCandidate
    ila.probe24 := raw.io_debug_chirpFilterCounter
    ila.probe25 := raw.io_debug_chirpStateQualified
    ila.probe26 := raw.io_debug_chirpQualifiedState
    // Snapshot probes: timebase/flags/state, event timestamps, then saturating event counters.
    ila.probe27 := monitor.io.timestamp
    ila.probe28 := monitor.io.eventFlags
    ila.probe29 := raw.io_debug_portState
    ila.probe30 := monitor.io.attachCandidateTimestamp
    ila.probe31 := monitor.io.attachStableTimestamp
    ila.probe32 := monitor.io.resetStartTimestamp
    ila.probe33 := monitor.io.resetEndTimestamp
    ila.probe34 := monitor.io.resetDurationCycles
    ila.probe35 := monitor.io.firstSofTimestamp
    ila.probe36 := monitor.io.firstSetupTimestamp
    ila.probe37 := monitor.io.maxSofGapCycles
    ila.probe38 := monitor.io.resetCount
    ila.probe39 := monitor.io.setupCountAfterReset
    ila.probe40 := monitor.io.sofCountBeforeSetup
    ila.probe41 := monitor.io.unexpectedPacketCountBeforeSetup
    ila.probe42 := monitor.io.vbusFallCount
    ila.probe43 := monitor.io.disconnectCount
  }
}
