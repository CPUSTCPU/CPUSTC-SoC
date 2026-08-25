`timescale 1ns / 1ps
`default_nettype none

// OV7670 DVP receiver.  Data is sampled on the rising PCLK edge and encoded
// as a token stream for the asynchronous FIFO.  The stream cannot exert
// backpressure on the camera; axis_async_fifo MARK_WHEN_FULL handles overflow.
module cpustc_dvp_rx #(
    parameter WIDTH = 640,
    parameter HEIGHT = 480
) (
    input  wire        pclk,
    input  wire        resetn,
    input  wire        vsync,
    input  wire        href,
    input  wire [7:0]  data,

    output reg  [63:0] m_axis_tdata,
    output reg  [7:0]  m_axis_tkeep,
    output reg         m_axis_tvalid,
    output reg         m_axis_tlast,
    output reg         m_axis_tuser
);

localparam [1:0] TOKEN_PIXEL = 2'b00;
localparam [1:0] TOKEN_SOF   = 2'b01;
localparam [1:0] TOKEN_EOF   = 2'b10;
localparam [11:0] LINE_BYTES = WIDTH * 2;

reg        vsync_d;
reg        href_d;
reg        frame_active;
reg        frame_error;
reg        byte_phase;
reg [7:0]  first_byte;
reg [11:0] line_byte_count;
reg [9:0]  line_count;

wire vsync_rise = vsync && !vsync_d;
wire vsync_fall = !vsync && vsync_d;
wire href_rise  = href && !href_d;
wire href_fall  = !href && href_d;
wire line_error_now = href_fall && frame_active &&
    ((line_byte_count != LINE_BYTES) || byte_phase);
wire [10:0] final_line_count = {1'b0, line_count} +
    ((href_fall && frame_active) ? 11'd1 : 11'd0);

always @(posedge pclk or negedge resetn) begin
    if (!resetn) begin
        vsync_d        <= 1'b1;
        href_d         <= 1'b0;
        frame_active   <= 1'b0;
        frame_error    <= 1'b0;
        byte_phase     <= 1'b0;
        first_byte     <= 8'b0;
        line_byte_count <= 12'b0;
        line_count     <= 10'b0;
        m_axis_tdata   <= 64'b0;
        m_axis_tkeep   <= 8'b0;
        m_axis_tvalid  <= 1'b0;
        m_axis_tlast   <= 1'b0;
        m_axis_tuser   <= 1'b0;
    end else begin
        vsync_d       <= vsync;
        href_d        <= href;
        m_axis_tvalid <= 1'b0;
        m_axis_tlast  <= 1'b0;
        m_axis_tuser  <= 1'b0;
        m_axis_tkeep  <= 8'b0;

        if (vsync_rise) begin
            if (frame_active) begin
                m_axis_tdata  <= {TOKEN_EOF, 61'b0,
                    frame_error || line_error_now ||
                    (final_line_count != HEIGHT)};
                m_axis_tvalid <= 1'b1;
                m_axis_tlast  <= 1'b1;
                // tuser is reserved for axis_async_fifo MARK_WHEN_FULL.
                m_axis_tuser  <= 1'b0;
            end
            frame_active <= 1'b0;
            byte_phase   <= 1'b0;
        end else if (vsync_fall) begin
            m_axis_tdata   <= {TOKEN_SOF, 62'b0};
            m_axis_tvalid  <= 1'b1;
            frame_active   <= 1'b1;
            frame_error    <= 1'b0;
            byte_phase     <= 1'b0;
            line_byte_count <= 12'b0;
            line_count     <= 10'b0;
        end else if (frame_active) begin
            if (href_rise) begin
                first_byte      <= data;
                byte_phase      <= 1'b1;
                line_byte_count <= 12'd1;
            end else if (href) begin
                line_byte_count <= line_byte_count + 1'b1;
                if (!byte_phase) begin
                    first_byte <= data;
                    byte_phase <= 1'b1;
                end else begin
                    // The first camera byte contains R[4:0],G[5:3].  Keeping
                    // it in bits 15:8 makes the second byte land at the lower
                    // memory address on the little-endian AXI bus.
                    m_axis_tdata  <= {TOKEN_PIXEL, 46'b0, first_byte, data};
                    m_axis_tkeep  <= 8'h03;
                    m_axis_tvalid <= 1'b1;
                    byte_phase    <= 1'b0;
                end
            end else if (href_fall) begin
                line_count <= line_count + 1'b1;
                byte_phase <= 1'b0;
                if ((line_byte_count != LINE_BYTES) || byte_phase)
                    frame_error <= 1'b1;
            end
        end
    end
end

endmodule

`default_nettype wire
