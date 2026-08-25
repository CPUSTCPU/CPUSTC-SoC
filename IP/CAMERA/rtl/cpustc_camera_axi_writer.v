`timescale 1ns / 1ps
`default_nettype none

// Converts camera tokens to fixed 16-beat AXI4 write bursts.  Only one burst
// is outstanding, so a completion is emitted after the final BRESP is known.
module cpustc_camera_axi_writer #(
    parameter WIDTH = 640,
    parameter HEIGHT = 480
) (
    input  wire        clk,
    input  wire        resetn,
    input  wire        capture_enable,
    input  wire        abort,
    input  wire        byte_swap,

    input  wire [63:0] s_axis_tdata,
    input  wire [7:0]  s_axis_tkeep,
    input  wire        s_axis_tvalid,
    output wire        s_axis_tready,
    input  wire        s_axis_tlast,
    input  wire        s_axis_tuser,
    input  wire [13:0] fifo_depth,

    input  wire        descriptor_valid,
    output wire        descriptor_ready,
    input  wire [31:0] descriptor_addr,
    input  wire [31:0] descriptor_tag,

    output wire        completion_valid,
    input  wire        completion_ready,
    output reg  [31:0] completion_tag,
    output reg  [31:0] completion_status,
    output reg  [31:0] completion_bytes,

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
    output wire        m_axi_rready,

    output wire [31:0] last_frame_cycles,
    output wire [31:0] last_fifo_wait_cycles,
    output wire [31:0] last_axi_active_cycles,
    output wire [31:0] last_axi_stall_cycles,
    output wire [31:0] max_fifo_depth,
    output wire        busy
);

localparam [1:0] TOKEN_PIXEL = 2'b00;
localparam [1:0] TOKEN_SOF   = 2'b01;
localparam [1:0] TOKEN_EOF   = 2'b10;

localparam [31:0] STATUS_FIFO_OVERFLOW = 32'h0000_0001;
localparam [31:0] STATUS_FRAME_SIZE     = 32'h0000_0002;
localparam [31:0] STATUS_PROTOCOL       = 32'h0000_0004;
localparam [31:0] STATUS_AXI_BRESP      = 32'h0000_0008;
localparam [31:0] STATUS_ABORTED        = 32'h0000_0010;
localparam integer FRAME_PIXELS = WIDTH * HEIGHT;

localparam [2:0] STATE_RESYNC   = 3'd0;
localparam [2:0] STATE_CAPTURE  = 3'd1;
localparam [2:0] STATE_AW       = 3'd2;
localparam [2:0] STATE_W        = 3'd3;
localparam [2:0] STATE_B        = 3'd4;
localparam [2:0] STATE_COMPLETE = 3'd5;

reg [2:0]  state;
reg [31:0] frame_addr;
reg [31:0] active_tag;
reg [31:0] status_accum;
reg [31:0] bytes_written;
reg [31:0] pixel_count;
reg [15:0] first_pixel;
reg        half_word;
reg [4:0]  burst_word_count;
reg [4:0]  write_index;
reg        abort_pending;
reg [31:0] burst_mem [0:15];

wire [1:0] token_type = s_axis_tdata[63:62];
wire [15:0] raw_pixel = s_axis_tdata[15:0];
wire [15:0] camera_pixel = byte_swap ? {raw_pixel[7:0], raw_pixel[15:8]} : raw_pixel;
wire stream_bad_end = s_axis_tvalid && s_axis_tlast && s_axis_tuser;
wire sof_available = s_axis_tvalid && token_type == TOKEN_SOF &&
    !s_axis_tlast && !s_axis_tuser;

assign s_axis_tready = (state == STATE_RESYNC) || (state == STATE_CAPTURE);
assign descriptor_ready = state == STATE_RESYNC && capture_enable &&
    descriptor_valid && completion_ready && sof_available;
assign completion_valid = state == STATE_COMPLETE;
assign busy = state != STATE_RESYNC;

assign m_axi_awid     = 4'b0;
assign m_axi_awaddr   = frame_addr + bytes_written;
assign m_axi_awlen    = 8'd15;
assign m_axi_awsize   = 3'd2;
assign m_axi_awburst  = 2'b01;
assign m_axi_awlock   = 1'b0;
assign m_axi_awcache  = 4'b0011;
assign m_axi_awprot   = 3'b000;
assign m_axi_awqos    = 4'b0;
assign m_axi_awregion = 4'b0;
assign m_axi_awvalid  = state == STATE_AW;

assign m_axi_wdata  = burst_mem[write_index[3:0]];
assign m_axi_wstrb  = 4'hf;
assign m_axi_wlast  = write_index == 5'd15;
assign m_axi_wvalid = state == STATE_W;
assign m_axi_bready = state == STATE_B;

assign m_axi_arid     = 4'b0;
assign m_axi_araddr   = 32'b0;
assign m_axi_arlen    = 8'b0;
assign m_axi_arsize   = 3'b0;
assign m_axi_arburst  = 2'b0;
assign m_axi_arlock   = 1'b0;
assign m_axi_arcache  = 4'b0;
assign m_axi_arprot   = 3'b0;
assign m_axi_arqos    = 4'b0;
assign m_axi_arregion = 4'b0;
assign m_axi_arvalid  = 1'b0;
assign m_axi_rready   = 1'b0;

always @(posedge clk or negedge resetn) begin
    if (!resetn) begin
        state             <= STATE_RESYNC;
        frame_addr        <= 32'b0;
        active_tag        <= 32'b0;
        status_accum      <= 32'b0;
        bytes_written     <= 32'b0;
        pixel_count       <= 32'b0;
        first_pixel       <= 16'b0;
        half_word         <= 1'b0;
        burst_word_count  <= 5'b0;
        write_index       <= 5'b0;
        abort_pending     <= 1'b0;
        completion_tag    <= 32'b0;
        completion_status <= 32'b0;
        completion_bytes  <= 32'b0;
    end else begin
        case (state)
            STATE_RESYNC: begin
                half_word        <= 1'b0;
                burst_word_count <= 5'b0;
                abort_pending    <= 1'b0;
                if (descriptor_ready) begin
                    frame_addr    <= descriptor_addr;
                    active_tag    <= descriptor_tag;
                    status_accum  <= 32'b0;
                    bytes_written <= 32'b0;
                    pixel_count   <= 32'b0;
                    state         <= STATE_CAPTURE;
                end
            end

            STATE_CAPTURE: begin
                if (abort || !capture_enable) begin
                    completion_tag    <= active_tag;
                    completion_status <= status_accum | STATUS_ABORTED;
                    completion_bytes  <= bytes_written;
                    state             <= STATE_COMPLETE;
                end else if (s_axis_tvalid) begin
                    if (stream_bad_end) begin
                        completion_tag    <= active_tag;
                        completion_status <= status_accum | STATUS_FIFO_OVERFLOW;
                        completion_bytes  <= bytes_written;
                        state             <= STATE_COMPLETE;
                    end else if (token_type == TOKEN_PIXEL && !s_axis_tlast &&
                                 s_axis_tkeep == 8'h03) begin
                        if (pixel_count >= FRAME_PIXELS) begin
                            completion_tag    <= active_tag;
                            completion_status <= status_accum | STATUS_FRAME_SIZE;
                            completion_bytes  <= bytes_written;
                            state             <= STATE_COMPLETE;
                        end else begin
                            pixel_count <= pixel_count + 1'b1;
                            if (!half_word) begin
                                first_pixel <= camera_pixel;
                                half_word   <= 1'b1;
                            end else begin
                                burst_mem[burst_word_count[3:0]] <=
                                    {camera_pixel, first_pixel};
                                half_word <= 1'b0;
                                if (burst_word_count == 5'd15) begin
                                    burst_word_count <= 5'b0;
                                    state            <= STATE_AW;
                                end else begin
                                    burst_word_count <= burst_word_count + 1'b1;
                                end
                            end
                        end
                    end else if (token_type == TOKEN_EOF && s_axis_tlast &&
                                 s_axis_tkeep == 8'h00) begin
                        completion_tag <= active_tag;
                        completion_status <= status_accum |
                            ((pixel_count == FRAME_PIXELS && !half_word &&
                              burst_word_count == 0 && !s_axis_tdata[0]) ?
                              32'b0 : STATUS_FRAME_SIZE);
                        completion_bytes <= bytes_written;
                        state <= STATE_COMPLETE;
                    end else begin
                        completion_tag    <= active_tag;
                        completion_status <= status_accum | STATUS_PROTOCOL;
                        completion_bytes  <= bytes_written;
                        state             <= STATE_COMPLETE;
                    end
                end
            end

            STATE_AW: begin
                if (abort || !capture_enable)
                    abort_pending <= 1'b1;
                if (m_axi_awready) begin
                    write_index <= 5'b0;
                    state       <= STATE_W;
                end
            end

            STATE_W: begin
                if (abort || !capture_enable)
                    abort_pending <= 1'b1;
                if (m_axi_wready) begin
                    if (write_index == 5'd15) begin
                        state <= STATE_B;
                    end else begin
                        write_index <= write_index + 1'b1;
                    end
                end
            end

            STATE_B: begin
                if (abort || !capture_enable)
                    abort_pending <= 1'b1;
                if (m_axi_bvalid) begin
                    if (m_axi_bresp != 2'b00) begin
                        completion_tag    <= active_tag;
                        completion_status <= status_accum | STATUS_AXI_BRESP |
                            (abort_pending ? STATUS_ABORTED : 32'b0);
                        completion_bytes  <= bytes_written;
                        state             <= STATE_COMPLETE;
                    end else if (abort_pending || abort || !capture_enable) begin
                        completion_tag    <= active_tag;
                        completion_status <= status_accum | STATUS_ABORTED;
                        completion_bytes  <= bytes_written + 32'd64;
                        bytes_written     <= bytes_written + 32'd64;
                        state             <= STATE_COMPLETE;
                    end else begin
                        bytes_written <= bytes_written + 32'd64;
                        state         <= STATE_CAPTURE;
                    end
                end
            end

            STATE_COMPLETE: begin
                if (completion_ready)
                    state <= STATE_RESYNC;
            end

            default: state <= STATE_RESYNC;
        endcase
    end
end

`ifdef CPUSTC_CAMERA_PROFILE
reg [31:0] frame_cycles_reg;
reg [31:0] fifo_wait_cycles_reg;
reg [31:0] axi_active_cycles_reg;
reg [31:0] axi_stall_cycles_reg;
reg [31:0] max_fifo_depth_reg;
reg [31:0] last_frame_cycles_reg;
reg [31:0] last_fifo_wait_cycles_reg;
reg [31:0] last_axi_active_cycles_reg;
reg [31:0] last_axi_stall_cycles_reg;

assign last_frame_cycles      = last_frame_cycles_reg;
assign last_fifo_wait_cycles  = last_fifo_wait_cycles_reg;
assign last_axi_active_cycles = last_axi_active_cycles_reg;
assign last_axi_stall_cycles  = last_axi_stall_cycles_reg;
assign max_fifo_depth         = max_fifo_depth_reg;

always @(posedge clk or negedge resetn) begin
    if (!resetn) begin
        frame_cycles_reg           <= 32'b0;
        fifo_wait_cycles_reg       <= 32'b0;
        axi_active_cycles_reg      <= 32'b0;
        axi_stall_cycles_reg       <= 32'b0;
        max_fifo_depth_reg         <= 32'b0;
        last_frame_cycles_reg      <= 32'b0;
        last_fifo_wait_cycles_reg  <= 32'b0;
        last_axi_active_cycles_reg <= 32'b0;
        last_axi_stall_cycles_reg  <= 32'b0;
    end else begin
        if (descriptor_ready) begin
            frame_cycles_reg      <= 32'b0;
            fifo_wait_cycles_reg  <= 32'b0;
            axi_active_cycles_reg <= 32'b0;
            axi_stall_cycles_reg  <= 32'b0;
        end else if (state != STATE_RESYNC && state != STATE_COMPLETE) begin
            frame_cycles_reg <= frame_cycles_reg + 1'b1;
            if (state == STATE_CAPTURE && !s_axis_tvalid)
                fifo_wait_cycles_reg <= fifo_wait_cycles_reg + 1'b1;
            if (state == STATE_AW || state == STATE_W || state == STATE_B)
                axi_active_cycles_reg <= axi_active_cycles_reg + 1'b1;
            if ((state == STATE_AW && !m_axi_awready) ||
                (state == STATE_W && !m_axi_wready) ||
                (state == STATE_B && !m_axi_bvalid))
                axi_stall_cycles_reg <= axi_stall_cycles_reg + 1'b1;
        end
        if (fifo_depth > max_fifo_depth_reg)
            max_fifo_depth_reg <= fifo_depth;
        if (state == STATE_COMPLETE && completion_ready) begin
            last_frame_cycles_reg      <= frame_cycles_reg;
            last_fifo_wait_cycles_reg  <= fifo_wait_cycles_reg;
            last_axi_active_cycles_reg <= axi_active_cycles_reg;
            last_axi_stall_cycles_reg  <= axi_stall_cycles_reg;
        end
    end
end
`else
assign last_frame_cycles      = 32'b0;
assign last_fifo_wait_cycles  = 32'b0;
assign last_axi_active_cycles = 32'b0;
assign last_axi_stall_cycles  = 32'b0;
assign max_fifo_depth         = 32'b0;
`endif

wire unused_read_inputs = &{1'b0, m_axi_bid, m_axi_arready, m_axi_rid,
    m_axi_rdata, m_axi_rresp, m_axi_rlast, m_axi_rvalid};

endmodule

`default_nettype wire
