package chisel.axiInterconnect.sdio

import chisel3._
import chisel3.experimental.{Analog, ExtModule, attach}
import chisel.common.bus.{AXI3IO, AXI3PortAdapter, AXI4IO}
import tensorcore.{AxiDmaReadExt, AxiDmaWriteExt}

/** Board-level four-bit SD interface with active-low card detection. */
class SdioPort extends Bundle {
  val sdClock: Bool = Output(Bool())
  val command: Analog = Analog(1.W)
  val data: Analog = Analog(4.W)
  val cardDetectN: Bool = Input(Bool())
}

/** Fixed-version LiteSDCard standalone Verilog core. */
class RawLiteSDCardCore extends ExtModule {
  override def desiredName: String = "litesdcard_core"

  val clk: Clock = IO(Input(Clock()))
  val irq: Bool = IO(Output(Bool()))
  val rst: Bool = IO(Input(Bool()))
  val sdcard_cd: Bool = IO(Input(Bool()))
  val sdcard_clk: Bool = IO(Output(Bool()))
  val sdcard_cmd: Analog = IO(Analog(1.W))
  val sdcard_cmd_dir: Bool = IO(Output(Bool()))
  val sdcard_dat0_dir: Bool = IO(Output(Bool()))
  val sdcard_dat13_dir: Bool = IO(Output(Bool()))
  val sdcard_data: Analog = IO(Analog(4.W))

  val wb_ctrl_ack: Bool = IO(Output(Bool()))
  val wb_ctrl_adr: UInt = IO(Input(UInt(30.W)))
  val wb_ctrl_bte: UInt = IO(Input(UInt(2.W)))
  val wb_ctrl_cti: UInt = IO(Input(UInt(3.W)))
  val wb_ctrl_cyc: Bool = IO(Input(Bool()))
  val wb_ctrl_dat_r: UInt = IO(Output(UInt(32.W)))
  val wb_ctrl_dat_w: UInt = IO(Input(UInt(32.W)))
  val wb_ctrl_err: Bool = IO(Output(Bool()))
  val wb_ctrl_sel: UInt = IO(Input(UInt(4.W)))
  val wb_ctrl_stb: Bool = IO(Input(Bool()))
  val wb_ctrl_we: Bool = IO(Input(Bool()))

  val dma_wr_desc_addr: UInt = IO(Output(UInt(32.W)))
  val dma_wr_desc_len: UInt = IO(Output(UInt(21.W)))
  val dma_wr_desc_valid: Bool = IO(Output(Bool()))
  val dma_wr_desc_ready: Bool = IO(Input(Bool()))
  val dma_wr_status_len: UInt = IO(Input(UInt(21.W)))
  val dma_wr_status_error: UInt = IO(Input(UInt(4.W)))
  val dma_wr_status_valid: Bool = IO(Input(Bool()))
  val dma_wr_data_tdata: UInt = IO(Output(UInt(32.W)))
  val dma_wr_data_tkeep: UInt = IO(Output(UInt(4.W)))
  val dma_wr_data_tvalid: Bool = IO(Output(Bool()))
  val dma_wr_data_tready: Bool = IO(Input(Bool()))
  val dma_wr_data_tlast: Bool = IO(Output(Bool()))

  val dma_rd_desc_addr: UInt = IO(Output(UInt(32.W)))
  val dma_rd_desc_len: UInt = IO(Output(UInt(21.W)))
  val dma_rd_desc_valid: Bool = IO(Output(Bool()))
  val dma_rd_desc_ready: Bool = IO(Input(Bool()))
  val dma_rd_status_error: UInt = IO(Input(UInt(4.W)))
  val dma_rd_status_valid: Bool = IO(Input(Bool()))
  val dma_rd_data_tdata: UInt = IO(Input(UInt(32.W)))
  val dma_rd_data_tkeep: UInt = IO(Input(UInt(4.W)))
  val dma_rd_data_tvalid: Bool = IO(Input(Bool()))
  val dma_rd_data_tready: Bool = IO(Output(Bool()))
  val dma_rd_data_tlast: Bool = IO(Input(Bool()))

  val dma_block2mem_enabled: Bool = IO(Output(Bool()))
  val dma_mem2block_enabled: Bool = IO(Output(Bool()))
  val mem2block_payload_requested: Bool = IO(Output(Bool()))

  val debug_crc_error_next: Bool = IO(Output(Bool()))
  val debug_crc_error_latched: Bool = IO(Output(Bool()))
  val debug_crc_sample_enable: Bool = IO(Output(Bool()))
  val debug_sd_dat: UInt = IO(Output(UInt(4.W)))
  val debug_crc_count: UInt = IO(Output(UInt(5.W)))
  val debug_datar_state: UInt = IO(Output(UInt(3.W)))
  val debug_datar_count: UInt = IO(Output(UInt(10.W)))
  val debug_block_index: UInt = IO(Output(UInt(8.W)))
  val debug_local_crc_dat0: UInt = IO(Output(UInt(16.W)))
  val debug_local_crc_dat1: UInt = IO(Output(UInt(16.W)))
  val debug_local_crc_dat2: UInt = IO(Output(UInt(16.W)))
  val debug_local_crc_dat3: UInt = IO(Output(UInt(16.W)))
  val debug_frontend_offset_bytes: UInt = IO(Output(UInt(32.W)))
  val debug_frontend_state: UInt = IO(Output(UInt(3.W)))
  val debug_crc_expected: UInt = IO(Output(UInt(4.W)))
  val debug_crc_mismatch: UInt = IO(Output(UInt(4.W)))
  val debug_crc_correct: Bool = IO(Output(Bool()))
  val debug_data_done: Bool = IO(Output(Bool()))
  val debug_datar_valid: Bool = IO(Output(Bool()))
  val debug_sample_ce: Bool = IO(Output(Bool()))
  val debug_clocker_ce: Bool = IO(Output(Bool()))
  val debug_clocker_clk: Bool = IO(Output(Bool()))
  val debug_clocker_clk_en: Bool = IO(Output(Bool()))
  val debug_clocker_stop: Bool = IO(Output(Bool()))
  val debug_datar_reset: Bool = IO(Output(Bool()))
  val debug_sd_data_oe: Bool = IO(Output(Bool()))
  val debug_crc16_enable: Bool = IO(Output(Bool()))
  val debug_crc16_reset: Bool = IO(Output(Bool()))
  val debug_clock_divider: UInt = IO(Output(UInt(9.W)))
  val debug_data_count: UInt = IO(Output(UInt(16.W)))
  val debug_datar_source_valid: Bool = IO(Output(Bool()))
  val debug_datar_source_ready: Bool = IO(Output(Bool()))
  val debug_datar_source_data: UInt = IO(Output(UInt(8.W)))
  val debug_core_state: UInt = IO(Output(UInt(3.W)))
  val debug_dataw_state: UInt = IO(Output(UInt(4.W)))
  val debug_cmdw_state: UInt = IO(Output(UInt(2.W)))
  val debug_cmdr_state: UInt = IO(Output(UInt(3.W)))
  val debug_mem2block_fifo_level: UInt = IO(Output(UInt(10.W)))
  val debug_cmd_index: UInt = IO(Output(UInt(6.W)))
  val debug_data_type: UInt = IO(Output(UInt(2.W)))
  val debug_cmd_send: Bool = IO(Output(Bool()))
  val debug_cmd_event: UInt = IO(Output(UInt(4.W)))
  val debug_data_event: UInt = IO(Output(UInt(4.W)))
  val debug_sd_cmd_i: Bool = IO(Output(Bool()))
  val debug_sd_cmd_o: Bool = IO(Output(Bool()))
  val debug_sd_cmd_oe: Bool = IO(Output(Bool()))
  val debug_dataw_sink_valid: Bool = IO(Output(Bool()))
  val debug_dataw_sink_ready: Bool = IO(Output(Bool()))
}

class LiteSdioControllerIO extends Bundle {
  val clock: Clock = Input(Clock())
  val resetn: Bool = Input(Bool())
  val control: AXI3IO = Flipped(new AXI3IO)
  val dma: AXI4IO = new AXI4IO(
    idWidth = 4,
    addrWidth = 32,
    lenWidth = 8,
    lockWidth = 1,
    dataWidth = 32,
    strbWidth = 4
  )
  val sdio: SdioPort = new SdioPort
  val interrupt: Bool = Output(Bool())
}

/** LiteSDCard with CPU AXI3 control and burst-capable AXI4 DDR DMA. */
class LiteSdioController extends RawModule {
  val io: LiteSdioControllerIO = IO(new LiteSdioControllerIO)

  private val raw = Module(new RawLiteSDCardCore)
  private val controlBridge = withClockAndReset(io.clock, (!io.resetn).asAsyncReset) {
    Module(new Axi3ToWishboneControlBridge)
  }
  private val wishboneRouter = withClockAndReset(io.clock, (!io.resetn).asAsyncReset) {
    Module(new LiteSdioWishboneRouter)
  }
  private val sgCsr = withClockAndReset(io.clock, (!io.resetn).asAsyncReset) {
    Module(new LiteSdioSgCsr)
  }
  private val sgPrefetch = withClockAndReset(io.clock, (!io.resetn).asAsyncReset) {
    Module(new LiteSdioSgPrefetch)
  }
  private val sgTable = withClockAndReset(io.clock, (!io.resetn).asAsyncReset) {
    Module(new LiteSdioSgTableMemory)
  }
  private val sgDatapath = withClockAndReset(io.clock, (!io.resetn).asAsyncReset) {
    Module(new LiteSdioSgDatapath)
  }
  private val readDma = Module(new AxiDmaReadExt(axiIdWidth = 4, lenWidth = 21))
  private val tableDma = Module(new AxiDmaReadExt(axiIdWidth = 4, lenWidth = 21))
  private val writeDma = Module(new AxiDmaWriteExt(axiIdWidth = 4, lenWidth = 21))

  AXI3PortAdapter.connectMasterToSlave(io.control, controlBridge.io.axi)

  raw.clk := io.clock
  raw.rst := !io.resetn
  raw.sdcard_cd := io.sdio.cardDetectN
  io.sdio.sdClock := raw.sdcard_clk
  attach(io.sdio.command, raw.sdcard_cmd)
  attach(io.sdio.data, raw.sdcard_data)
  io.interrupt := raw.irq

  wishboneRouter.io.upstream <> controlBridge.io.wishbone
  sgCsr.io.wishbone <> wishboneRouter.io.sg
  raw.wb_ctrl_adr := wishboneRouter.io.raw.adr
  raw.wb_ctrl_bte := wishboneRouter.io.raw.bte
  raw.wb_ctrl_cti := wishboneRouter.io.raw.cti
  raw.wb_ctrl_cyc := wishboneRouter.io.raw.cyc
  raw.wb_ctrl_dat_w := wishboneRouter.io.raw.datW
  raw.wb_ctrl_sel := wishboneRouter.io.raw.sel
  raw.wb_ctrl_stb := wishboneRouter.io.raw.stb
  raw.wb_ctrl_we := wishboneRouter.io.raw.we
  wishboneRouter.io.raw.ack := raw.wb_ctrl_ack
  wishboneRouter.io.raw.datR := raw.wb_ctrl_dat_r
  wishboneRouter.io.raw.err := raw.wb_ctrl_err

  private val sgEnabled = sgCsr.io.enable
  private val sgRouteActive = sgEnabled || sgPrefetch.io.busy || sgDatapath.io.active

  sgCsr.io.locked := sgPrefetch.io.busy || sgPrefetch.io.ready || sgDatapath.io.active
  sgCsr.io.fetchBusy := sgPrefetch.io.busy
  sgCsr.io.tableReady := sgPrefetch.io.ready
  sgCsr.io.tableOwner := sgPrefetch.io.busy
  sgCsr.io.prefetchError := sgPrefetch.io.error
  sgCsr.io.prefetchErrorCode := sgPrefetch.io.errorCode
  sgCsr.io.prefetchErrorIndex := sgPrefetch.io.errorIndex
  sgCsr.io.prefetchErrorDetail := sgPrefetch.io.errorDetail
  sgCsr.io.fetchCycles := sgPrefetch.io.fetchCycles
  sgCsr.io.operationActive := sgDatapath.io.active
  sgCsr.io.operationDone := sgDatapath.io.completed
  sgCsr.io.operationAborted := sgDatapath.io.aborted
  sgCsr.io.operationError := sgDatapath.io.operationError
  sgCsr.io.operationErrorCode := sgDatapath.io.operationErrorCode
  sgCsr.io.operationErrorIndex := sgDatapath.io.operationErrorIndex
  sgCsr.io.operationErrorDetail := sgDatapath.io.operationErrorDetail
  sgCsr.io.currentIndex := sgDatapath.io.currentIndex
  sgCsr.io.maxGapCycles := sgDatapath.io.maxGapCycles

  sgPrefetch.io.enable := sgEnabled
  sgPrefetch.io.arm := sgCsr.io.arm
  sgPrefetch.io.abort := sgCsr.io.abort
  sgPrefetch.io.clear := sgCsr.io.clear
  sgPrefetch.io.consume := sgDatapath.io.consumeTable
  sgPrefetch.io.tableAddress := sgCsr.io.tableAddress
  sgPrefetch.io.entryCount := sgCsr.io.entryCount
  sgPrefetch.io.totalBytes := sgCsr.io.totalBytes

  sgTable.io.writeEnable := sgPrefetch.io.tableWriteEnable
  sgTable.io.writeIndex := sgPrefetch.io.tableWriteIndex
  sgTable.io.writeData := sgPrefetch.io.tableWriteData
  sgTable.io.readEnable := sgDatapath.io.tableReadEnable
  sgTable.io.readIndex := sgDatapath.io.tableReadIndex
  sgDatapath.io.tableReadData := sgTable.io.readData
  sgDatapath.io.tableReadValid := sgTable.io.readValid

  tableDma.clk := io.clock
  tableDma.rst := !io.resetn
  tableDma.enable := true.B
  tableDma.s_axis_read_desc_addr := sgPrefetch.io.dmaDescAddress
  tableDma.s_axis_read_desc_len := sgPrefetch.io.dmaDescLength
  tableDma.s_axis_read_desc_tag := 0.U
  tableDma.s_axis_read_desc_id := 0.U
  tableDma.s_axis_read_desc_dest := 0.U
  tableDma.s_axis_read_desc_user := 0.U
  tableDma.s_axis_read_desc_valid := sgPrefetch.io.dmaDescValid
  sgPrefetch.io.dmaDescReady := tableDma.s_axis_read_desc_ready
  sgPrefetch.io.dmaStatusError := tableDma.m_axis_read_desc_status_error
  sgPrefetch.io.dmaStatusValid := tableDma.m_axis_read_desc_status_valid
  sgPrefetch.io.dmaData := tableDma.m_axis_read_data_tdata
  sgPrefetch.io.dmaKeep := tableDma.m_axis_read_data_tkeep
  sgPrefetch.io.dmaValid := tableDma.m_axis_read_data_tvalid
  tableDma.m_axis_read_data_tready := sgPrefetch.io.dmaReady
  sgPrefetch.io.dmaLast := tableDma.m_axis_read_data_tlast

  sgDatapath.io.enable := sgEnabled
  sgDatapath.io.tableReady := sgPrefetch.io.ready
  sgDatapath.io.entryCount := sgCsr.io.entryCount
  sgDatapath.io.totalBytes := sgCsr.io.totalBytes
  sgDatapath.io.abort := sgCsr.io.abort
  sgDatapath.io.clearStats := sgCsr.io.arm || sgCsr.io.clear
  sgDatapath.io.block2memEnabled := raw.dma_block2mem_enabled
  sgDatapath.io.mem2blockEnabled := raw.dma_mem2block_enabled
  sgDatapath.io.mem2blockWriteActive := raw.mem2block_payload_requested

  readDma.clk := io.clock
  readDma.rst := !io.resetn
  readDma.enable := Mux(sgRouteActive, sgDatapath.io.dmaReadEnable, true.B)
  readDma.s_axis_read_desc_addr := Mux(sgRouteActive,
    sgDatapath.io.dmaReadDescAddress, raw.dma_rd_desc_addr)
  readDma.s_axis_read_desc_len := Mux(sgRouteActive,
    sgDatapath.io.dmaReadDescLength, raw.dma_rd_desc_len)
  readDma.s_axis_read_desc_tag := 0.U
  readDma.s_axis_read_desc_id := 0.U
  readDma.s_axis_read_desc_dest := 0.U
  readDma.s_axis_read_desc_user := 0.U
  readDma.s_axis_read_desc_valid := Mux(sgRouteActive,
    sgDatapath.io.dmaReadDescValid, raw.dma_rd_desc_valid)

  sgDatapath.io.rawReadDescAddress := raw.dma_rd_desc_addr
  sgDatapath.io.rawReadDescLength := raw.dma_rd_desc_len
  sgDatapath.io.rawReadDescValid := raw.dma_rd_desc_valid
  raw.dma_rd_desc_ready := Mux(sgRouteActive,
    sgDatapath.io.rawReadDescReady, readDma.s_axis_read_desc_ready)
  raw.dma_rd_status_error := Mux(sgRouteActive,
    sgDatapath.io.rawReadStatusError, readDma.m_axis_read_desc_status_error)
  raw.dma_rd_status_valid := Mux(sgRouteActive,
    sgDatapath.io.rawReadStatusValid, readDma.m_axis_read_desc_status_valid)
  raw.dma_rd_data_tdata := Mux(sgRouteActive,
    sgDatapath.io.rawReadData, readDma.m_axis_read_data_tdata)
  raw.dma_rd_data_tkeep := Mux(sgRouteActive,
    sgDatapath.io.rawReadKeep, readDma.m_axis_read_data_tkeep)
  raw.dma_rd_data_tvalid := Mux(sgRouteActive,
    sgDatapath.io.rawReadValid, readDma.m_axis_read_data_tvalid)
  raw.dma_rd_data_tlast := Mux(sgRouteActive,
    sgDatapath.io.rawReadLast, readDma.m_axis_read_data_tlast)

  sgDatapath.io.rawReadReady := raw.dma_rd_data_tready
  sgDatapath.io.dmaReadDescReady := readDma.s_axis_read_desc_ready
  sgDatapath.io.dmaReadStatusError := readDma.m_axis_read_desc_status_error
  sgDatapath.io.dmaReadStatusValid := readDma.m_axis_read_desc_status_valid
  sgDatapath.io.dmaReadData := readDma.m_axis_read_data_tdata
  sgDatapath.io.dmaReadKeep := readDma.m_axis_read_data_tkeep
  sgDatapath.io.dmaReadValid := readDma.m_axis_read_data_tvalid
  sgDatapath.io.dmaReadLast := readDma.m_axis_read_data_tlast
  readDma.m_axis_read_data_tready := Mux(sgRouteActive,
    sgDatapath.io.dmaReadReady, raw.dma_rd_data_tready)

  writeDma.clk := io.clock
  writeDma.rst := !io.resetn
  writeDma.enable := true.B
  writeDma.abort := false.B
  writeDma.s_axis_write_desc_addr := Mux(sgRouteActive,
    sgDatapath.io.dmaWriteDescAddress, raw.dma_wr_desc_addr)
  writeDma.s_axis_write_desc_len := Mux(sgRouteActive,
    sgDatapath.io.dmaWriteDescLength, raw.dma_wr_desc_len)
  writeDma.s_axis_write_desc_tag := 0.U
  writeDma.s_axis_write_desc_valid := Mux(sgRouteActive,
    sgDatapath.io.dmaWriteDescValid, raw.dma_wr_desc_valid)
  writeDma.s_axis_write_data_tdata := Mux(sgRouteActive,
    sgDatapath.io.dmaWriteData, raw.dma_wr_data_tdata)
  writeDma.s_axis_write_data_tkeep := Mux(sgRouteActive,
    sgDatapath.io.dmaWriteKeep, raw.dma_wr_data_tkeep)
  writeDma.s_axis_write_data_tvalid := Mux(sgRouteActive,
    sgDatapath.io.dmaWriteValid, raw.dma_wr_data_tvalid)
  writeDma.s_axis_write_data_tlast := Mux(sgRouteActive,
    sgDatapath.io.dmaWriteLast, raw.dma_wr_data_tlast)
  writeDma.s_axis_write_data_tid := 0.U
  writeDma.s_axis_write_data_tdest := 0.U
  writeDma.s_axis_write_data_tuser := 0.U

  sgDatapath.io.rawWriteDescAddress := raw.dma_wr_desc_addr
  sgDatapath.io.rawWriteDescLength := raw.dma_wr_desc_len
  sgDatapath.io.rawWriteDescValid := raw.dma_wr_desc_valid
  raw.dma_wr_desc_ready := Mux(sgRouteActive,
    sgDatapath.io.rawWriteDescReady, writeDma.s_axis_write_desc_ready)
  raw.dma_wr_status_len := Mux(sgRouteActive,
    sgDatapath.io.rawWriteStatusLength, writeDma.m_axis_write_desc_status_len)
  raw.dma_wr_status_error := Mux(sgRouteActive,
    sgDatapath.io.rawWriteStatusError, writeDma.m_axis_write_desc_status_error)
  raw.dma_wr_status_valid := Mux(sgRouteActive,
    sgDatapath.io.rawWriteStatusValid, writeDma.m_axis_write_desc_status_valid)
  sgDatapath.io.rawWriteData := raw.dma_wr_data_tdata
  sgDatapath.io.rawWriteKeep := raw.dma_wr_data_tkeep
  sgDatapath.io.rawWriteValid := raw.dma_wr_data_tvalid
  sgDatapath.io.rawWriteLast := raw.dma_wr_data_tlast
  raw.dma_wr_data_tready := Mux(sgRouteActive,
    sgDatapath.io.rawWriteReady, writeDma.s_axis_write_data_tready)

  sgDatapath.io.dmaWriteDescReady := writeDma.s_axis_write_desc_ready
  sgDatapath.io.dmaWriteStatusLength := writeDma.m_axis_write_desc_status_len
  sgDatapath.io.dmaWriteStatusError := writeDma.m_axis_write_desc_status_error
  sgDatapath.io.dmaWriteStatusValid := writeDma.m_axis_write_desc_status_valid
  sgDatapath.io.dmaWriteReady := writeDma.s_axis_write_data_tready

  io.dma.awid := writeDma.m_axi_awid
  io.dma.awaddr := writeDma.m_axi_awaddr
  io.dma.awlen := writeDma.m_axi_awlen
  io.dma.awsize := writeDma.m_axi_awsize
  io.dma.awburst := writeDma.m_axi_awburst
  io.dma.awlock := writeDma.m_axi_awlock
  io.dma.awcache := writeDma.m_axi_awcache
  io.dma.awprot := writeDma.m_axi_awprot
  io.dma.awqos := 0.U
  io.dma.awregion := 0.U
  io.dma.awvalid := writeDma.m_axi_awvalid
  writeDma.m_axi_awready := io.dma.awready

  io.dma.wdata := writeDma.m_axi_wdata
  io.dma.wstrb := writeDma.m_axi_wstrb
  io.dma.wlast := writeDma.m_axi_wlast
  io.dma.wvalid := writeDma.m_axi_wvalid
  writeDma.m_axi_wready := io.dma.wready

  writeDma.m_axi_bid := io.dma.bid
  writeDma.m_axi_bresp := io.dma.bresp
  writeDma.m_axi_bvalid := io.dma.bvalid
  io.dma.bready := writeDma.m_axi_bready

  private val tableOwnsRead = sgPrefetch.io.busy
  private val payloadOutstanding = withClockAndReset(
    io.clock, (!io.resetn).asAsyncReset) { RegInit(0.U(9.W)) }
  private val payloadArFire = !tableOwnsRead && io.dma.arvalid && io.dma.arready
  private val payloadRlastFire = !tableOwnsRead && io.dma.rvalid &&
    io.dma.rready && io.dma.rlast

  withClockAndReset(io.clock, (!io.resetn).asAsyncReset) {
    when(payloadArFire && !payloadRlastFire) {
      payloadOutstanding := payloadOutstanding + 1.U
    }.elsewhen(!payloadArFire && payloadRlastFire && payloadOutstanding =/= 0.U) {
      payloadOutstanding := payloadOutstanding - 1.U
    }
  }
  sgPrefetch.io.canStart := payloadOutstanding === 0.U &&
    !readDma.m_axi_arvalid && !sgDatapath.io.active

  io.dma.arid := Mux(tableOwnsRead, tableDma.m_axi_arid, readDma.m_axi_arid)
  io.dma.araddr := Mux(tableOwnsRead, tableDma.m_axi_araddr, readDma.m_axi_araddr)
  io.dma.arlen := Mux(tableOwnsRead, tableDma.m_axi_arlen, readDma.m_axi_arlen)
  io.dma.arsize := Mux(tableOwnsRead, tableDma.m_axi_arsize, readDma.m_axi_arsize)
  io.dma.arburst := Mux(tableOwnsRead, tableDma.m_axi_arburst, readDma.m_axi_arburst)
  io.dma.arlock := Mux(tableOwnsRead, tableDma.m_axi_arlock, readDma.m_axi_arlock)
  io.dma.arcache := Mux(tableOwnsRead, tableDma.m_axi_arcache, readDma.m_axi_arcache)
  io.dma.arprot := Mux(tableOwnsRead, tableDma.m_axi_arprot, readDma.m_axi_arprot)
  io.dma.arqos := 0.U
  io.dma.arregion := 0.U
  io.dma.arvalid := Mux(tableOwnsRead,
    tableDma.m_axi_arvalid, readDma.m_axi_arvalid)
  tableDma.m_axi_arready := io.dma.arready && tableOwnsRead
  readDma.m_axi_arready := io.dma.arready && !tableOwnsRead

  readDma.m_axi_rid := io.dma.rid
  readDma.m_axi_rdata := io.dma.rdata
  readDma.m_axi_rresp := io.dma.rresp
  readDma.m_axi_rlast := io.dma.rlast
  readDma.m_axi_rvalid := io.dma.rvalid && !tableOwnsRead
  tableDma.m_axi_rid := io.dma.rid
  tableDma.m_axi_rdata := io.dma.rdata
  tableDma.m_axi_rresp := io.dma.rresp
  tableDma.m_axi_rlast := io.dma.rlast
  tableDma.m_axi_rvalid := io.dma.rvalid && tableOwnsRead
  io.dma.rready := Mux(tableOwnsRead,
    tableDma.m_axi_rready, readDma.m_axi_rready)
}
