`timescale 1ns / 1ps
`default_nettype none

// Complete OV7670 capture block: DVP receiver, asynchronous FIFO, descriptor
// and completion queues, APB3 control/status registers, and AXI4 write master.
module cpustc_camera_capture (
    input  wire        aclk,
    input  wire        aresetn,
    input  wire        pclk,
    input  wire        pclk_resetn,
    input  wire        vsync,
    input  wire        href,
    input  wire [7:0]  data,

    input  wire [12:0] paddr,
    input  wire        psel,
    input  wire        penable,
    input  wire        pwrite,
    input  wire [31:0] pwdata,
    output reg  [31:0] prdata,
    output wire        pready,
    output wire        pslverr,
    output wire        interrupt,

    output wire [3:0]  m_axi_awid,
    output wire [31:0] m_axi_awaddr,
    output wire [7:0]  m_axi_awlen,
    output wire [2:0]  m_axi_awsize,
    output wire [1:0]  m_axi_awburst,
    output wire        m_axi_awlock,
    output wire [3:0]  m_axi_awcache,
    output wire [2:0]  m_axi_awprot,
    output wire [3:0]  m_axi_awqos,
    output wire [3:0]  m_axi_awregion,
    output wire        m_axi_awvalid,
    input  wire        m_axi_awready,
    output wire [31:0] m_axi_wdata,
    output wire [3:0]  m_axi_wstrb,
    output wire        m_axi_wlast,
    output wire        m_axi_wvalid,
    input  wire        m_axi_wready,
    input  wire [3:0]  m_axi_bid,
    input  wire [1:0]  m_axi_bresp,
    input  wire        m_axi_bvalid,
    output wire        m_axi_bready,
    output wire [3:0]  m_axi_arid,
    output wire [31:0] m_axi_araddr,
    output wire [7:0]  m_axi_arlen,
    output wire [2:0]  m_axi_arsize,
    output wire [1:0]  m_axi_arburst,
    output wire        m_axi_arlock,
    output wire [3:0]  m_axi_arcache,
    output wire [2:0]  m_axi_arprot,
    output wire [3:0]  m_axi_arqos,
    output wire [3:0]  m_axi_arregion,
    output wire        m_axi_arvalid,
    input  wire        m_axi_arready,
    input  wire [3:0]  m_axi_rid,
    input  wire [31:0] m_axi_rdata,
    input  wire [1:0]  m_axi_rresp,
    input  wire        m_axi_rlast,
    input  wire        m_axi_rvalid,
    output wire        m_axi_rready
);

localparam [31:0] ID_VALUE      = 32'h4341_4d31;
localparam [31:0] VERSION_VALUE = 32'h0001_0000;
localparam [31:0] FORMAT_VALUE  = 32'h5042_4752; // V4L2_PIX_FMT_RGB565 ('RGBP')
localparam [31:0] WIDTH_VALUE   = 32'd640;
localparam [31:0] HEIGHT_VALUE  = 32'd480;
localparam [31:0] BPL_VALUE     = 32'd1280;
localparam [31:0] FRAME_VALUE   = 32'd614400;

localparam [31:0] IRQ_DONE          = 32'h0000_0001;
localparam [31:0] IRQ_FIFO_OVERFLOW = 32'h0000_0002;
localparam [31:0] IRQ_FRAME_ERROR   = 32'h0000_0004;
localparam [31:0] IRQ_AXI_ERROR     = 32'h0000_0008;
localparam [31:0] IRQ_QUEUE_ERROR   = 32'h0000_0010;
localparam [31:0] IRQ_ABORTED       = 32'h0000_0020;

reg        capture_enable;
reg        byte_swap;
reg [31:0] irq_sticky;
reg [31:0] irq_enable;
reg [31:0] queue_addr_latch;
reg [31:0] queue_tag_latch;

reg [31:0] desc_addr [0:3];
reg [31:0] desc_tag [0:3];
reg [1:0]  desc_write_ptr;
reg [1:0]  desc_read_ptr;
reg [2:0]  desc_count;

reg [31:0] done_tag [0:3];
reg [31:0] done_status [0:3];
reg [31:0] done_bytes [0:3];
reg [1:0]  done_write_ptr;
reg [1:0]  done_read_ptr;
reg [2:0]  done_count;

reg [31:0] frames_started;
reg [31:0] frames_completed;
reg [31:0] frames_dropped;
reg [31:0] fifo_overflows;
reg [31:0] axi_errors;
reg [31:0] no_buffer_drops;
reg [31:0] queue_errors;
reg        queue_push_error_latched;
reg        done_pop_error_latched;

wire [63:0] dvp_tdata;
wire [7:0]  dvp_tkeep;
wire        dvp_tvalid;
wire        dvp_tlast;
wire        dvp_tuser;

wire [63:0] fifo_tdata;
wire [7:0]  fifo_tkeep;
wire        fifo_tvalid;
wire        fifo_tready;
wire        fifo_tlast;
wire        fifo_tuser;
wire [13:0] fifo_s_depth;
wire [13:0] fifo_m_depth;
wire        fifo_m_overflow;

wire        descriptor_ready;
wire        completion_valid;
wire [31:0] completion_tag_wire;
wire [31:0] completion_status_wire;
wire [31:0] completion_bytes_wire;
wire        completion_ready = done_count != 3'd4;
wire        writer_busy;
wire        no_buffer_sof = fifo_tvalid && fifo_tready &&
    fifo_tdata[63:62] == 2'b01 && capture_enable &&
    (desc_count == 0 || !completion_ready);
wire [31:0] last_frame_cycles;
wire [31:0] last_fifo_wait_cycles;
wire [31:0] last_axi_active_cycles;
wire [31:0] last_axi_stall_cycles;
wire [31:0] max_fifo_depth;

wire apb_setup = psel && !penable;
wire apb_access = psel && penable;
wire aligned = paddr[1:0] == 2'b00;
wire queue_push_access = apb_access && pwrite && paddr[11:0] == 12'h048;
wire done_pop_access = apb_access && pwrite && paddr[11:0] == 12'h06c;
wire illegal_queue_push = queue_push_access && queue_push_error_latched;
wire illegal_done_pop = done_pop_access && done_pop_error_latched;
wire descriptor_push = queue_push_access && !illegal_queue_push;
wire completion_pop = done_pop_access && !illegal_done_pop;
wire [31:0] irq_clear_mask = (apb_access && pwrite && !access_error &&
    paddr[11:0] == 12'h014) ? {pwdata[31:1], 1'b0} : 32'b0;
wire [31:0] irq_set_mask = {
    26'b0,
    completion_valid && completion_ready && completion_status_wire[4],
    illegal_queue_push || illegal_done_pop,
    completion_valid && completion_ready && completion_status_wire[3],
    completion_valid && completion_ready && |completion_status_wire[2:1],
    fifo_m_overflow ||
        (completion_valid && completion_ready && completion_status_wire[0]),
    1'b0
};
wire address_known = paddr[11:0] == 12'h000 || paddr[11:0] == 12'h004 ||
    paddr[11:0] == 12'h008 || paddr[11:0] == 12'h00c ||
    paddr[11:0] == 12'h010 || paddr[11:0] == 12'h014 ||
    paddr[11:0] == 12'h018 || paddr[11:0] == 12'h01c ||
    paddr[11:0] == 12'h020 || paddr[11:0] == 12'h024 ||
    paddr[11:0] == 12'h028 || paddr[11:0] == 12'h02c ||
    paddr[11:0] == 12'h030 || paddr[11:0] == 12'h040 ||
    paddr[11:0] == 12'h044 || paddr[11:0] == 12'h048 ||
    paddr[11:0] == 12'h04c || paddr[11:0] == 12'h060 ||
    paddr[11:0] == 12'h064 || paddr[11:0] == 12'h068 ||
    paddr[11:0] == 12'h06c || paddr[11:0] == 12'h070 ||
    (paddr[11:0] >= 12'h080 && paddr[11:0] <= 12'h0ac && aligned);
wire write_allowed = paddr[11:0] == 12'h00c || paddr[11:0] == 12'h014 ||
    paddr[11:0] == 12'h018 || paddr[11:0] == 12'h01c ||
    paddr[11:0] == 12'h040 || paddr[11:0] == 12'h044 ||
    paddr[11:0] == 12'h048 || paddr[11:0] == 12'h06c;
wire read_allowed = paddr[11:0] != 12'h048 && paddr[11:0] != 12'h06c &&
    paddr[11:0] != 12'h01c;
wire access_error = apb_access && (!aligned || !address_known ||
    (pwrite && !write_allowed) || (!pwrite && !read_allowed) ||
    illegal_queue_push || illegal_done_pop);

assign pready = apb_access;
assign pslverr = access_error;
assign interrupt = |(irq_enable & ({irq_sticky[31:1], done_count != 0}));

cpustc_dvp_rx dvp_rx_inst (
    .pclk(pclk),
    .resetn(pclk_resetn),
    .vsync(vsync),
    .href(href),
    .data(data),
    .m_axis_tdata(dvp_tdata),
    .m_axis_tkeep(dvp_tkeep),
    .m_axis_tvalid(dvp_tvalid),
    .m_axis_tlast(dvp_tlast),
    .m_axis_tuser(dvp_tuser)
);

axis_async_fifo #(
    .DEPTH(8192),
    .DATA_WIDTH(64),
    .KEEP_ENABLE(1),
    .KEEP_WIDTH(8),
    .LAST_ENABLE(1),
    .ID_ENABLE(0),
    .ID_WIDTH(1),
    .DEST_ENABLE(0),
    .DEST_WIDTH(1),
    .USER_ENABLE(1),
    .USER_WIDTH(1),
    .FRAME_FIFO(0),
    .MARK_WHEN_FULL(1)
) camera_async_fifo_inst (
    .s_clk(pclk),
    .s_rst(!pclk_resetn),
    .s_axis_tdata(dvp_tdata),
    .s_axis_tkeep(dvp_tkeep),
    .s_axis_tvalid(dvp_tvalid),
    .s_axis_tready(),
    .s_axis_tlast(dvp_tlast),
    .s_axis_tid(1'b0),
    .s_axis_tdest(1'b0),
    .s_axis_tuser(dvp_tuser),
    .m_clk(aclk),
    .m_rst(!aresetn),
    .m_axis_tdata(fifo_tdata),
    .m_axis_tkeep(fifo_tkeep),
    .m_axis_tvalid(fifo_tvalid),
    .m_axis_tready(fifo_tready),
    .m_axis_tlast(fifo_tlast),
    .m_axis_tid(),
    .m_axis_tdest(),
    .m_axis_tuser(fifo_tuser),
    .s_pause_req(1'b0),
    .s_pause_ack(),
    .m_pause_req(1'b0),
    .m_pause_ack(),
    .s_status_depth(fifo_s_depth),
    .s_status_depth_commit(),
    .s_status_overflow(),
    .s_status_bad_frame(),
    .s_status_good_frame(),
    .m_status_depth(fifo_m_depth),
    .m_status_depth_commit(),
    .m_status_overflow(fifo_m_overflow),
    .m_status_bad_frame(),
    .m_status_good_frame()
);

cpustc_camera_axi_writer writer_inst (
    .clk(aclk),
    .resetn(aresetn),
    .capture_enable(capture_enable),
    .abort(apb_access && pwrite && paddr[11:0] == 12'h01c && pwdata[0]),
    .byte_swap(byte_swap),
    .s_axis_tdata(fifo_tdata),
    .s_axis_tkeep(fifo_tkeep),
    .s_axis_tvalid(fifo_tvalid),
    .s_axis_tready(fifo_tready),
    .s_axis_tlast(fifo_tlast),
    .s_axis_tuser(fifo_tuser),
    .fifo_depth(fifo_m_depth),
    .descriptor_valid(desc_count != 0),
    .descriptor_ready(descriptor_ready),
    .descriptor_addr(desc_addr[desc_read_ptr]),
    .descriptor_tag(desc_tag[desc_read_ptr]),
    .completion_valid(completion_valid),
    .completion_ready(completion_ready),
    .completion_tag(completion_tag_wire),
    .completion_status(completion_status_wire),
    .completion_bytes(completion_bytes_wire),
    .m_axi_awid(m_axi_awid), .m_axi_awaddr(m_axi_awaddr),
    .m_axi_awlen(m_axi_awlen), .m_axi_awsize(m_axi_awsize),
    .m_axi_awburst(m_axi_awburst), .m_axi_awlock(m_axi_awlock),
    .m_axi_awcache(m_axi_awcache), .m_axi_awprot(m_axi_awprot),
    .m_axi_awqos(m_axi_awqos), .m_axi_awregion(m_axi_awregion),
    .m_axi_awvalid(m_axi_awvalid), .m_axi_awready(m_axi_awready),
    .m_axi_wdata(m_axi_wdata), .m_axi_wstrb(m_axi_wstrb),
    .m_axi_wlast(m_axi_wlast), .m_axi_wvalid(m_axi_wvalid),
    .m_axi_wready(m_axi_wready), .m_axi_bid(m_axi_bid),
    .m_axi_bresp(m_axi_bresp), .m_axi_bvalid(m_axi_bvalid),
    .m_axi_bready(m_axi_bready), .m_axi_arid(m_axi_arid),
    .m_axi_araddr(m_axi_araddr), .m_axi_arlen(m_axi_arlen),
    .m_axi_arsize(m_axi_arsize), .m_axi_arburst(m_axi_arburst),
    .m_axi_arlock(m_axi_arlock), .m_axi_arcache(m_axi_arcache),
    .m_axi_arprot(m_axi_arprot), .m_axi_arqos(m_axi_arqos),
    .m_axi_arregion(m_axi_arregion), .m_axi_arvalid(m_axi_arvalid),
    .m_axi_arready(m_axi_arready), .m_axi_rid(m_axi_rid),
    .m_axi_rdata(m_axi_rdata), .m_axi_rresp(m_axi_rresp),
    .m_axi_rlast(m_axi_rlast), .m_axi_rvalid(m_axi_rvalid),
    .m_axi_rready(m_axi_rready),
    .last_frame_cycles(last_frame_cycles),
    .last_fifo_wait_cycles(last_fifo_wait_cycles),
    .last_axi_active_cycles(last_axi_active_cycles),
    .last_axi_stall_cycles(last_axi_stall_cycles),
    .max_fifo_depth(max_fifo_depth),
    .busy(writer_busy)
);

always @(*) begin
    case (paddr[11:0])
        12'h000: prdata = ID_VALUE;
        12'h004: prdata = VERSION_VALUE;
        12'h008: prdata = {30'b0,
`ifdef CPUSTC_CAMERA_PROFILE
            1'b1,
`else
            1'b0,
`endif
            1'b1};
        12'h00c: prdata = {30'b0, byte_swap, capture_enable};
        12'h010: prdata = {13'b0, fifo_m_depth, 2'b0, writer_busy,
            done_count != 0, desc_count != 0};
        12'h014: prdata = {irq_sticky[31:1], done_count != 0};
        12'h018: prdata = irq_enable;
        12'h020: prdata = FORMAT_VALUE;
        12'h024: prdata = WIDTH_VALUE;
        12'h028: prdata = HEIGHT_VALUE;
        12'h02c: prdata = BPL_VALUE;
        12'h030: prdata = FRAME_VALUE;
        12'h040: prdata = queue_addr_latch;
        12'h044: prdata = queue_tag_latch;
        12'h04c: prdata = {29'b0, desc_count};
        12'h060: prdata = done_count != 0 ? done_tag[done_read_ptr] : 32'b0;
        12'h064: prdata = done_count != 0 ? done_status[done_read_ptr] : 32'b0;
        12'h068: prdata = done_count != 0 ? done_bytes[done_read_ptr] : 32'b0;
        12'h070: prdata = {29'b0, done_count};
        12'h080: prdata = frames_started;
        12'h084: prdata = frames_completed;
        12'h088: prdata = frames_dropped;
        12'h08c: prdata = fifo_overflows;
        12'h090: prdata = axi_errors;
        12'h094: prdata = no_buffer_drops;
        12'h098: prdata = queue_errors;
        12'h09c: prdata = last_frame_cycles;
        12'h0a0: prdata = last_fifo_wait_cycles;
        12'h0a4: prdata = last_axi_active_cycles;
        12'h0a8: prdata = last_axi_stall_cycles;
        12'h0ac: prdata = max_fifo_depth;
        default: prdata = 32'b0;
    endcase
end

always @(posedge aclk or negedge aresetn) begin
    if (!aresetn) begin
        capture_enable  <= 1'b0;
        byte_swap       <= 1'b0;
        irq_sticky      <= 32'b0;
        irq_enable      <= 32'b0;
        queue_addr_latch <= 32'b0;
        queue_tag_latch <= 32'b0;
        desc_write_ptr  <= 2'b0;
        desc_read_ptr   <= 2'b0;
        desc_count      <= 3'b0;
        done_write_ptr  <= 2'b0;
        done_read_ptr   <= 2'b0;
        done_count      <= 3'b0;
        frames_started  <= 32'b0;
        frames_completed <= 32'b0;
        frames_dropped  <= 32'b0;
        fifo_overflows  <= 32'b0;
        axi_errors      <= 32'b0;
        no_buffer_drops <= 32'b0;
        queue_errors    <= 32'b0;
        queue_push_error_latched <= 1'b0;
        done_pop_error_latched <= 1'b0;
    end else begin
        if (apb_setup) begin
            queue_push_error_latched <= pwrite && paddr[11:0] == 12'h048 &&
                (pwdata != 32'd1 || desc_count == 3'd4 ||
                 queue_addr_latch[5:0] != 6'b0);
            done_pop_error_latched <= pwrite && paddr[11:0] == 12'h06c &&
                (pwdata != 32'd1 || done_count == 0);
        end

        if (descriptor_ready) begin
            desc_read_ptr  <= desc_read_ptr + 1'b1;
            frames_started <= frames_started + 1'b1;
        end

        if (no_buffer_sof) begin
            no_buffer_drops <= no_buffer_drops + 1'b1;
            frames_dropped  <= frames_dropped + 1'b1;
        end

        if (completion_valid && completion_ready) begin
            done_tag[done_write_ptr]    <= completion_tag_wire;
            done_status[done_write_ptr] <= completion_status_wire;
            done_bytes[done_write_ptr]  <= completion_bytes_wire;
            done_write_ptr              <= done_write_ptr + 1'b1;
            frames_completed            <= frames_completed + 1'b1;
            if (completion_status_wire != 0)
                frames_dropped <= frames_dropped + 1'b1;
            if (completion_status_wire[3]) begin
                axi_errors <= axi_errors + 1'b1;
            end
        end

        case ({descriptor_push, descriptor_ready})
            2'b10: desc_count <= desc_count + 1'b1;
            2'b01: desc_count <= desc_count - 1'b1;
            default: ;
        endcase
        case ({completion_valid && completion_ready, completion_pop})
            2'b10: done_count <= done_count + 1'b1;
            2'b01: done_count <= done_count - 1'b1;
            default: ;
        endcase

        if (fifo_m_overflow) begin
            fifo_overflows <= fifo_overflows + 1'b1;
        end

        if (illegal_queue_push || illegal_done_pop) begin
            queue_errors <= queue_errors + 1'b1;
        end

        irq_sticky <= (irq_sticky & ~irq_clear_mask) | irq_set_mask;

        if (apb_access && pwrite && !access_error) begin
            case (paddr[11:0])
                12'h00c: begin
                    capture_enable <= pwdata[0];
                    byte_swap      <= pwdata[1];
                end
                12'h018: irq_enable <= pwdata;
                12'h040: queue_addr_latch <= pwdata;
                12'h044: queue_tag_latch <= pwdata;
                12'h048: begin
                    desc_addr[desc_write_ptr] <= queue_addr_latch;
                    desc_tag[desc_write_ptr]  <= queue_tag_latch;
                    desc_write_ptr            <= desc_write_ptr + 1'b1;
                end
                12'h06c: done_read_ptr <= done_read_ptr + 1'b1;
                default: ;
            endcase
        end
    end
end

wire unused_fifo_depth = &{1'b0, fifo_s_depth};
wire unused_apb_setup = apb_setup;

endmodule

`default_nettype wire
