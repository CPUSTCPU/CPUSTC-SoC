package chisel

import chisel3._
import chisel3.experimental.ExtModule

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

  val ctrl_clk:   Clock = IO(Input(Clock()))
  val ctrl_reset: Bool  = IO(Input(Bool()))
  val utmi_clk:   Clock = IO(Input(Clock()))
  val utmi_reset: Bool  = IO(Input(Bool()))
  val dma_clk:    Clock = IO(Input(Clock()))
  val dma_reset:  Bool  = IO(Input(Bool()))
}

/** 当前 USB UTMI 调试候选使用的 27 路、60 MHz ILA。 */
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
  }
}
