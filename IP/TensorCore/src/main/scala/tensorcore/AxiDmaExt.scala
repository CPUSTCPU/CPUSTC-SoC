package tensorcore

import chisel3._
import chisel3.experimental.{ExtModule, IntParam}
import chisel3.util.HasExtModuleResource

/** Unmodified verilog-axi AXI-to-stream read DMA. */
class AxiDmaReadExt(
  axiIdWidth: Int = 3,
  lenWidth: Int = 20
) extends ExtModule(Map(
  "AXI_DATA_WIDTH" -> IntParam(32),
  "AXI_ADDR_WIDTH" -> IntParam(32),
  "AXI_STRB_WIDTH" -> IntParam(4),
  "AXI_ID_WIDTH" -> IntParam(axiIdWidth),
  "AXI_MAX_BURST_LEN" -> IntParam(16),
  "AXIS_DATA_WIDTH" -> IntParam(32),
  "AXIS_KEEP_ENABLE" -> IntParam(1),
  "AXIS_KEEP_WIDTH" -> IntParam(4),
  "AXIS_LAST_ENABLE" -> IntParam(1),
  "AXIS_ID_ENABLE" -> IntParam(0),
  "AXIS_ID_WIDTH" -> IntParam(1),
  "AXIS_DEST_ENABLE" -> IntParam(0),
  "AXIS_DEST_WIDTH" -> IntParam(1),
  "AXIS_USER_ENABLE" -> IntParam(0),
  "AXIS_USER_WIDTH" -> IntParam(1),
  "LEN_WIDTH" -> IntParam(lenWidth),
  "TAG_WIDTH" -> IntParam(8),
  "ENABLE_SG" -> IntParam(0),
  "ENABLE_UNALIGNED" -> IntParam(0)
)) with HasExtModuleResource {
  override def desiredName: String = "axi_dma_rd"
  addResource("/verilog-axi/axi_dma_rd.v")

  val clk: Clock = IO(Input(Clock()))
  val rst: Bool = IO(Input(Bool()))

  val s_axis_read_desc_addr: UInt = IO(Input(UInt(32.W)))
  val s_axis_read_desc_len: UInt = IO(Input(UInt(lenWidth.W)))
  val s_axis_read_desc_tag: UInt = IO(Input(UInt(8.W)))
  val s_axis_read_desc_id: UInt = IO(Input(UInt(1.W)))
  val s_axis_read_desc_dest: UInt = IO(Input(UInt(1.W)))
  val s_axis_read_desc_user: UInt = IO(Input(UInt(1.W)))
  val s_axis_read_desc_valid: Bool = IO(Input(Bool()))
  val s_axis_read_desc_ready: Bool = IO(Output(Bool()))

  val m_axis_read_desc_status_tag: UInt = IO(Output(UInt(8.W)))
  val m_axis_read_desc_status_error: UInt = IO(Output(UInt(4.W)))
  val m_axis_read_desc_status_valid: Bool = IO(Output(Bool()))

  val m_axis_read_data_tdata: UInt = IO(Output(UInt(32.W)))
  val m_axis_read_data_tkeep: UInt = IO(Output(UInt(4.W)))
  val m_axis_read_data_tvalid: Bool = IO(Output(Bool()))
  val m_axis_read_data_tready: Bool = IO(Input(Bool()))
  val m_axis_read_data_tlast: Bool = IO(Output(Bool()))
  val m_axis_read_data_tid: UInt = IO(Output(UInt(1.W)))
  val m_axis_read_data_tdest: UInt = IO(Output(UInt(1.W)))
  val m_axis_read_data_tuser: UInt = IO(Output(UInt(1.W)))

  val m_axi_arid: UInt = IO(Output(UInt(axiIdWidth.W)))
  val m_axi_araddr: UInt = IO(Output(UInt(32.W)))
  val m_axi_arlen: UInt = IO(Output(UInt(8.W)))
  val m_axi_arsize: UInt = IO(Output(UInt(3.W)))
  val m_axi_arburst: UInt = IO(Output(UInt(2.W)))
  val m_axi_arlock: Bool = IO(Output(Bool()))
  val m_axi_arcache: UInt = IO(Output(UInt(4.W)))
  val m_axi_arprot: UInt = IO(Output(UInt(3.W)))
  val m_axi_arvalid: Bool = IO(Output(Bool()))
  val m_axi_arready: Bool = IO(Input(Bool()))
  val m_axi_rid: UInt = IO(Input(UInt(axiIdWidth.W)))
  val m_axi_rdata: UInt = IO(Input(UInt(32.W)))
  val m_axi_rresp: UInt = IO(Input(UInt(2.W)))
  val m_axi_rlast: Bool = IO(Input(Bool()))
  val m_axi_rvalid: Bool = IO(Input(Bool()))
  val m_axi_rready: Bool = IO(Output(Bool()))

  val enable: Bool = IO(Input(Bool()))
}

/** Unmodified verilog-axi stream-to-AXI write DMA. */
class AxiDmaWriteExt(
  axiIdWidth: Int = 3,
  lenWidth: Int = 20
) extends ExtModule(Map(
  "AXI_DATA_WIDTH" -> IntParam(32),
  "AXI_ADDR_WIDTH" -> IntParam(32),
  "AXI_STRB_WIDTH" -> IntParam(4),
  "AXI_ID_WIDTH" -> IntParam(axiIdWidth),
  "AXI_MAX_BURST_LEN" -> IntParam(16),
  "AXIS_DATA_WIDTH" -> IntParam(32),
  "AXIS_KEEP_ENABLE" -> IntParam(1),
  "AXIS_KEEP_WIDTH" -> IntParam(4),
  "AXIS_LAST_ENABLE" -> IntParam(1),
  "AXIS_ID_ENABLE" -> IntParam(0),
  "AXIS_ID_WIDTH" -> IntParam(1),
  "AXIS_DEST_ENABLE" -> IntParam(0),
  "AXIS_DEST_WIDTH" -> IntParam(1),
  "AXIS_USER_ENABLE" -> IntParam(0),
  "AXIS_USER_WIDTH" -> IntParam(1),
  "LEN_WIDTH" -> IntParam(lenWidth),
  "TAG_WIDTH" -> IntParam(8),
  "ENABLE_SG" -> IntParam(0),
  "ENABLE_UNALIGNED" -> IntParam(0)
)) with HasExtModuleResource {
  override def desiredName: String = "axi_dma_wr"
  addResource("/verilog-axi/axi_dma_wr.v")

  val clk: Clock = IO(Input(Clock()))
  val rst: Bool = IO(Input(Bool()))

  val s_axis_write_desc_addr: UInt = IO(Input(UInt(32.W)))
  val s_axis_write_desc_len: UInt = IO(Input(UInt(lenWidth.W)))
  val s_axis_write_desc_tag: UInt = IO(Input(UInt(8.W)))
  val s_axis_write_desc_valid: Bool = IO(Input(Bool()))
  val s_axis_write_desc_ready: Bool = IO(Output(Bool()))

  val m_axis_write_desc_status_len: UInt = IO(Output(UInt(lenWidth.W)))
  val m_axis_write_desc_status_tag: UInt = IO(Output(UInt(8.W)))
  val m_axis_write_desc_status_id: UInt = IO(Output(UInt(1.W)))
  val m_axis_write_desc_status_dest: UInt = IO(Output(UInt(1.W)))
  val m_axis_write_desc_status_user: UInt = IO(Output(UInt(1.W)))
  val m_axis_write_desc_status_error: UInt = IO(Output(UInt(4.W)))
  val m_axis_write_desc_status_valid: Bool = IO(Output(Bool()))

  val s_axis_write_data_tdata: UInt = IO(Input(UInt(32.W)))
  val s_axis_write_data_tkeep: UInt = IO(Input(UInt(4.W)))
  val s_axis_write_data_tvalid: Bool = IO(Input(Bool()))
  val s_axis_write_data_tready: Bool = IO(Output(Bool()))
  val s_axis_write_data_tlast: Bool = IO(Input(Bool()))
  val s_axis_write_data_tid: UInt = IO(Input(UInt(1.W)))
  val s_axis_write_data_tdest: UInt = IO(Input(UInt(1.W)))
  val s_axis_write_data_tuser: UInt = IO(Input(UInt(1.W)))

  val m_axi_awid: UInt = IO(Output(UInt(axiIdWidth.W)))
  val m_axi_awaddr: UInt = IO(Output(UInt(32.W)))
  val m_axi_awlen: UInt = IO(Output(UInt(8.W)))
  val m_axi_awsize: UInt = IO(Output(UInt(3.W)))
  val m_axi_awburst: UInt = IO(Output(UInt(2.W)))
  val m_axi_awlock: Bool = IO(Output(Bool()))
  val m_axi_awcache: UInt = IO(Output(UInt(4.W)))
  val m_axi_awprot: UInt = IO(Output(UInt(3.W)))
  val m_axi_awvalid: Bool = IO(Output(Bool()))
  val m_axi_awready: Bool = IO(Input(Bool()))
  val m_axi_wdata: UInt = IO(Output(UInt(32.W)))
  val m_axi_wstrb: UInt = IO(Output(UInt(4.W)))
  val m_axi_wlast: Bool = IO(Output(Bool()))
  val m_axi_wvalid: Bool = IO(Output(Bool()))
  val m_axi_wready: Bool = IO(Input(Bool()))
  val m_axi_bid: UInt = IO(Input(UInt(axiIdWidth.W)))
  val m_axi_bresp: UInt = IO(Input(UInt(2.W)))
  val m_axi_bvalid: Bool = IO(Input(Bool()))
  val m_axi_bready: Bool = IO(Output(Bool()))

  val enable: Bool = IO(Input(Bool()))
  val abort: Bool = IO(Input(Bool()))
}
