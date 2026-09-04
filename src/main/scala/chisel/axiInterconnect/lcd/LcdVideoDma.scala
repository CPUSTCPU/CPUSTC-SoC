package chisel.axiInterconnect.lcd

import chisel3._
import chisel3.experimental.ExtModule
import chisel.common.bus.AXI4IO
import chisel.common.cdc.ResetnSync

/** APB 域锁存后发送到 AXI 域的 DMA 请求。 */
class LcdDmaRequest extends Bundle {
  val startToggle: Bool = Bool()
  val baseAddress: UInt = UInt(32.W)
  val lineWidth: UInt = UInt(32.W)
  val height: UInt = UInt(32.W)
  val sourceStride: UInt = UInt(32.W)
}

/** AXI 域返回 APB 域的 DMA 状态。 */
class LcdDmaStatus extends Bundle {
  val errorToggle: Bool = Bool()
}

/** VideoDma 在 APB/8080 时钟域输出的 16 位数据流。 */
class LcdDmaFrameIO extends Bundle {
  val valid: Bool = Output(Bool())
  val ready: Bool = Input(Bool())
  val data: UInt = Output(UInt(16.W))
  val last: Bool = Output(Bool())
}

/** SpinalHDL 生成的 LcdVideoDma 黑盒。 */
class RawLcdVideoDma extends ExtModule {
  override def desiredName: String = "LcdVideoDma"

  val io_start: Bool = IO(Input(Bool()))
  val io_baseAddress: UInt = IO(Input(UInt(32.W)))
  val io_width: UInt = IO(Input(UInt(32.W)))
  val io_height: UInt = IO(Input(UInt(32.W)))
  val io_sourceStride: UInt = IO(Input(UInt(32.W)))
  val io_busy: Bool = IO(Output(Bool()))
  val io_error: Bool = IO(Output(Bool()))

  val io_axi_ar_valid: Bool = IO(Output(Bool()))
  val io_axi_ar_ready: Bool = IO(Input(Bool()))
  val io_axi_ar_payload_addr: UInt = IO(Output(UInt(32.W)))
  val io_axi_ar_payload_len: UInt = IO(Output(UInt(8.W)))
  val io_axi_ar_payload_size: UInt = IO(Output(UInt(3.W)))
  val io_axi_ar_payload_cache: UInt = IO(Output(UInt(4.W)))
  val io_axi_ar_payload_prot: UInt = IO(Output(UInt(3.W)))
  val io_axi_r_valid: Bool = IO(Input(Bool()))
  val io_axi_r_ready: Bool = IO(Output(Bool()))
  val io_axi_r_payload_data: UInt = IO(Input(UInt(32.W)))
  val io_axi_r_payload_resp: UInt = IO(Input(UInt(2.W)))
  val io_axi_r_payload_last: Bool = IO(Input(Bool()))

  val io_frame_valid: Bool = IO(Output(Bool()))
  val io_frame_ready: Bool = IO(Input(Bool()))
  val io_frame_payload_last: Bool = IO(Output(Bool()))
  val io_frame_payload_fragment: UInt = IO(Output(UInt(16.W)))

  val axi_clk: Clock = IO(Input(Clock()))
  val axi_reset: Bool = IO(Input(Bool()))
  val frame_clk: Clock = IO(Input(Clock()))
  val frame_reset: Bool = IO(Input(Bool()))
}

class LcdVideoDmaIO extends Bundle {
  val axiClk: Clock = Input(Clock())
  val axiResetn: Bool = Input(Bool())
  val frameClk: Clock = Input(Clock())
  val frameResetn: Bool = Input(Bool())
  val softReset: Bool = Input(Bool())
  val request: LcdDmaRequest = Input(new LcdDmaRequest)
  val status: LcdDmaStatus = Output(new LcdDmaStatus)
  val frame: LcdDmaFrameIO = new LcdDmaFrameIO
  val axi: AXI4IO = new AXI4IO(
    idWidth = 3,
    addrWidth = 32,
    lenWidth = 8,
    lockWidth = 1,
    dataWidth = 32,
    strbWidth = 4
  )
}

/** LcdVideoDma 的 Chisel 连接层。
  *
  * 该层同步 APB 请求，并将 Spinal AXI 读口适配到 SoC AXI4 Bundle。
  */
class LcdVideoDma extends RawModule {
  override def desiredName: String = "LcdVideoDmaChisel"

  val io: LcdVideoDmaIO = IO(new LcdVideoDmaIO)
  val raw: RawLcdVideoDma = Module(new RawLcdVideoDma)

  val dmaAxiResetn: Bool = ResetnSync(io.axiClk, io.axiResetn && !io.softReset, 2)
  val dmaFrameResetn: Bool = ResetnSync(io.frameClk, io.frameResetn && !io.softReset, 2)

  raw.axi_clk := io.axiClk
  raw.axi_reset := !dmaAxiResetn
  raw.frame_clk := io.frameClk
  raw.frame_reset := !dmaFrameResetn

  val startPulse: Bool = WireDefault(false.B)
  val activeBaseAddress: UInt = Wire(UInt(32.W))
  val activeWidth: UInt = Wire(UInt(32.W))
  val activeHeight: UInt = Wire(UInt(32.W))
  val activeSourceStride: UInt = Wire(UInt(32.W))

  withClockAndReset(io.axiClk, (!dmaAxiResetn).asAsyncReset) {
    val startToggleMetaReg = RegNext(io.request.startToggle, false.B)
    val startToggleSyncReg = RegNext(startToggleMetaReg, false.B)
    val startToggleSeenReg = RegInit(false.B)

    val baseMetaReg = RegNext(io.request.baseAddress, 0.U)
    val baseSyncReg = RegNext(baseMetaReg, 0.U)
    val widthMetaReg = RegNext(io.request.lineWidth, 0.U)
    val widthSyncReg = RegNext(widthMetaReg, 0.U)
    val heightMetaReg = RegNext(io.request.height, 0.U)
    val heightSyncReg = RegNext(heightMetaReg, 0.U)
    val strideMetaReg = RegNext(io.request.sourceStride, 0.U)
    val strideSyncReg = RegNext(strideMetaReg, 0.U)

    val activeBaseReg = RegInit(0.U(32.W))
    val activeWidthReg = RegInit(0.U(32.W))
    val activeHeightReg = RegInit(0.U(32.W))
    val activeStrideReg = RegInit(0.U(32.W))
    val requestCapturePendingReg = RegInit(false.B)
    val startPendingReg = RegInit(false.B)
    val errorSeenReg = RegInit(false.B)
    val errorToggleReg = RegInit(false.B)

    when(startToggleSyncReg =/= startToggleSeenReg) {
      startToggleSeenReg := startToggleSyncReg
      requestCapturePendingReg := true.B
    }

    // 配置总线由 APB 域保持稳定；toggle 到达后再等待一拍再锁存，避免多位 CDC 数据与事件发生偏斜。
    when(requestCapturePendingReg) {
      requestCapturePendingReg := false.B
      activeBaseReg := baseSyncReg
      activeWidthReg := widthSyncReg
      activeHeightReg := heightSyncReg
      activeStrideReg := strideSyncReg
      startPendingReg := true.B
    }

    when(startPendingReg && !raw.io_busy) {
      startPendingReg := false.B
      startPulse := true.B
      errorSeenReg := false.B
    }

    when(raw.io_error && !errorSeenReg) {
      errorSeenReg := true.B
      errorToggleReg := !errorToggleReg
    }

    activeBaseAddress := activeBaseReg
    activeWidth := activeWidthReg
    activeHeight := activeHeightReg
    activeSourceStride := activeStrideReg
    io.status.errorToggle := errorToggleReg
  }

  raw.io_start := startPulse
  raw.io_baseAddress := activeBaseAddress
  raw.io_width := activeWidth
  raw.io_height := activeHeight
  raw.io_sourceStride := activeSourceStride

  AXI4IO.tieOffOutputs(io.axi)
  io.axi.arid := 0.U
  io.axi.araddr := raw.io_axi_ar_payload_addr
  io.axi.arlen := raw.io_axi_ar_payload_len
  io.axi.arsize := raw.io_axi_ar_payload_size
  io.axi.arburst := 1.U
  io.axi.arlock := 0.U
  io.axi.arcache := raw.io_axi_ar_payload_cache
  io.axi.arprot := raw.io_axi_ar_payload_prot
  io.axi.arqos := 0.U
  io.axi.arregion := 0.U
  io.axi.arvalid := raw.io_axi_ar_valid
  raw.io_axi_ar_ready := io.axi.arready

  raw.io_axi_r_valid := io.axi.rvalid
  raw.io_axi_r_payload_data := io.axi.rdata
  raw.io_axi_r_payload_resp := io.axi.rresp
  raw.io_axi_r_payload_last := io.axi.rlast
  io.axi.rready := raw.io_axi_r_ready

  io.frame.valid := raw.io_frame_valid
  io.frame.data := raw.io_frame_payload_fragment
  io.frame.last := raw.io_frame_payload_last
  raw.io_frame_ready := io.frame.ready
}
