`timescale 1ns / 1ps
`default_nettype none

module camera_tb;

localparam integer WIDTH = 640;
localparam integer HEIGHT = 480;
localparam integer FRAME_BYTES = WIDTH * HEIGHT * 2;

localparam [31:0] STATUS_FIFO_OVERFLOW = 32'h0000_0001;
localparam [31:0] STATUS_FRAME_SIZE     = 32'h0000_0002;
localparam [31:0] STATUS_AXI_BRESP      = 32'h0000_0008;
localparam [31:0] STATUS_ABORTED        = 32'h0000_0010;

reg         aclk = 1'b0;
reg         aresetn = 1'b0;
reg         pclk = 1'b0;
reg         pclk_resetn = 1'b0;
reg         vsync = 1'b1;
reg         href = 1'b0;
reg  [7:0]  data = 8'b0;

reg  [12:0] paddr = 13'b0;
reg         psel = 1'b0;
reg         penable = 1'b0;
reg         pwrite = 1'b0;
reg  [31:0] pwdata = 32'b0;
wire [31:0] prdata;
wire        pready;
wire        pslverr;
wire        interrupt;

wire [3:0]  m_axi_awid;
wire [31:0] m_axi_awaddr;
wire [7:0]  m_axi_awlen;
wire [2:0]  m_axi_awsize;
wire [1:0]  m_axi_awburst;
wire        m_axi_awlock;
wire [3:0]  m_axi_awcache;
wire [2:0]  m_axi_awprot;
wire [3:0]  m_axi_awqos;
wire [3:0]  m_axi_awregion;
wire        m_axi_awvalid;
reg         m_axi_awready = 1'b0;
wire [31:0] m_axi_wdata;
wire [3:0]  m_axi_wstrb;
wire        m_axi_wlast;
wire        m_axi_wvalid;
reg         m_axi_wready = 1'b0;
reg  [3:0]  m_axi_bid = 4'b0;
reg  [1:0]  m_axi_bresp = 2'b0;
reg         m_axi_bvalid = 1'b0;
wire        m_axi_bready;
wire [3:0]  m_axi_arid;
wire [31:0] m_axi_araddr;
wire [7:0]  m_axi_arlen;
wire [2:0]  m_axi_arsize;
wire [1:0]  m_axi_arburst;
wire        m_axi_arlock;
wire [3:0]  m_axi_arcache;
wire [2:0]  m_axi_arprot;
wire [3:0]  m_axi_arqos;
wire [3:0]  m_axi_arregion;
wire        m_axi_arvalid;
reg         m_axi_arready = 1'b0;
reg  [3:0]  m_axi_rid = 4'b0;
reg  [31:0] m_axi_rdata = 32'b0;
reg  [1:0]  m_axi_rresp = 2'b0;
reg         m_axi_rlast = 1'b0;
reg         m_axi_rvalid = 1'b0;
wire        m_axi_rready;

cpustc_camera_capture dut (
    .aclk(aclk),
    .aresetn(aresetn),
    .pclk(pclk),
    .pclk_resetn(pclk_resetn),
    .vsync(vsync),
    .href(href),
    .data(data),
    .paddr(paddr),
    .psel(psel),
    .penable(penable),
    .pwrite(pwrite),
    .pwdata(pwdata),
    .prdata(prdata),
    .pready(pready),
    .pslverr(pslverr),
    .interrupt(interrupt),
    .m_axi_awid(m_axi_awid),
    .m_axi_awaddr(m_axi_awaddr),
    .m_axi_awlen(m_axi_awlen),
    .m_axi_awsize(m_axi_awsize),
    .m_axi_awburst(m_axi_awburst),
    .m_axi_awlock(m_axi_awlock),
    .m_axi_awcache(m_axi_awcache),
    .m_axi_awprot(m_axi_awprot),
    .m_axi_awqos(m_axi_awqos),
    .m_axi_awregion(m_axi_awregion),
    .m_axi_awvalid(m_axi_awvalid),
    .m_axi_awready(m_axi_awready),
    .m_axi_wdata(m_axi_wdata),
    .m_axi_wstrb(m_axi_wstrb),
    .m_axi_wlast(m_axi_wlast),
    .m_axi_wvalid(m_axi_wvalid),
    .m_axi_wready(m_axi_wready),
    .m_axi_bid(m_axi_bid),
    .m_axi_bresp(m_axi_bresp),
    .m_axi_bvalid(m_axi_bvalid),
    .m_axi_bready(m_axi_bready),
    .m_axi_arid(m_axi_arid),
    .m_axi_araddr(m_axi_araddr),
    .m_axi_arlen(m_axi_arlen),
    .m_axi_arsize(m_axi_arsize),
    .m_axi_arburst(m_axi_arburst),
    .m_axi_arlock(m_axi_arlock),
    .m_axi_arcache(m_axi_arcache),
    .m_axi_arprot(m_axi_arprot),
    .m_axi_arqos(m_axi_arqos),
    .m_axi_arregion(m_axi_arregion),
    .m_axi_arvalid(m_axi_arvalid),
    .m_axi_arready(m_axi_arready),
    .m_axi_rid(m_axi_rid),
    .m_axi_rdata(m_axi_rdata),
    .m_axi_rresp(m_axi_rresp),
    .m_axi_rlast(m_axi_rlast),
    .m_axi_rvalid(m_axi_rvalid),
    .m_axi_rready(m_axi_rready)
);

always #5 aclk = ~aclk;
initial begin
    #1.7;
    forever #6.5 pclk = ~pclk;
end

integer failures = 0;
integer axi_cycle = 0;
integer aw_count = 0;
integer w_count = 0;
integer b_count = 0;
integer aw_stall_cycles = 0;
integer w_stall_cycles = 0;
integer b_wait_cycles = 0;
integer protocol_errors = 0;
integer data_errors = 0;
integer expected_pixel_index = 0;
integer burst_beat_index = 0;
integer b_delay_count = 0;
integer b_response_sequence = 0;
integer inject_bresp_index = -1;
integer frame_sequence = 0;
reg [31:0] expected_awaddr = 32'b0;
reg        check_axi_addr = 1'b0;
reg        check_axi_data = 1'b0;
reg        block_aw = 1'b0;
reg        block_w = 1'b0;
reg        block_b = 1'b0;
reg        b_pending = 1'b0;
reg [1:0]  pending_bresp = 2'b0;
reg        burst_active = 1'b0;
reg        aw_hold = 1'b0;
reg [31:0] aw_hold_addr = 32'b0;
reg [7:0]  aw_hold_len = 8'b0;
reg [2:0]  aw_hold_size = 3'b0;
reg [1:0]  aw_hold_burst = 2'b0;
reg        w_hold = 1'b0;
reg [31:0] w_hold_data = 32'b0;
reg [3:0]  w_hold_strb = 4'b0;
reg        w_hold_last = 1'b0;

function automatic [15:0] pixel_pattern(input integer index);
    pixel_pattern = index[15:0] ^ 16'h5a3c;
endfunction

task automatic record_protocol_error(input string message);
begin
    protocol_errors = protocol_errors + 1;
    $display("AXI_PROTOCOL_FAIL time=%0t %s", $time, message);
end
endtask

task automatic check(input bit condition, input string message);
begin
    if (condition) begin
        $display("CHECK_PASS %s", message);
    end else begin
        failures = failures + 1;
        $display("CHECK_FAIL %s", message);
    end
end
endtask

always @(posedge aclk or negedge aresetn) begin : axi_memory_model
    reg [31:0] expected_word;
    if (!aresetn) begin
        m_axi_awready <= 1'b0;
        m_axi_wready <= 1'b0;
        m_axi_bvalid <= 1'b0;
        m_axi_bresp <= 2'b0;
        b_pending <= 1'b0;
        b_delay_count <= 0;
        burst_active <= 1'b0;
        aw_hold <= 1'b0;
        w_hold <= 1'b0;
    end else begin
        axi_cycle <= axi_cycle + 1;
        m_axi_awready <= !block_aw && ((axi_cycle % 5) != 0);
        m_axi_wready <= !block_w && ((axi_cycle % 4) != 1);

        if (aw_hold) begin
            if (!m_axi_awvalid || m_axi_awaddr != aw_hold_addr ||
                    m_axi_awlen != aw_hold_len || m_axi_awsize != aw_hold_size ||
                    m_axi_awburst != aw_hold_burst)
                record_protocol_error("AW VALID or payload changed while stalled");
        end
        if (w_hold) begin
            if (!m_axi_wvalid || m_axi_wdata != w_hold_data ||
                    m_axi_wstrb != w_hold_strb || m_axi_wlast != w_hold_last)
                record_protocol_error("W VALID or payload changed while stalled");
        end
        aw_hold <= m_axi_awvalid && !m_axi_awready;
        if (m_axi_awvalid && !m_axi_awready) begin
            aw_hold_addr <= m_axi_awaddr;
            aw_hold_len <= m_axi_awlen;
            aw_hold_size <= m_axi_awsize;
            aw_hold_burst <= m_axi_awburst;
            aw_stall_cycles <= aw_stall_cycles + 1;
        end
        w_hold <= m_axi_wvalid && !m_axi_wready;
        if (m_axi_wvalid && !m_axi_wready) begin
            w_hold_data <= m_axi_wdata;
            w_hold_strb <= m_axi_wstrb;
            w_hold_last <= m_axi_wlast;
            w_stall_cycles <= w_stall_cycles + 1;
        end

        if (m_axi_awvalid && m_axi_awready) begin
            aw_count <= aw_count + 1;
            if (burst_active)
                record_protocol_error("AW accepted while prior W burst is active");
            if (m_axi_awid != 0 || m_axi_awlen != 8'd15 ||
                    m_axi_awsize != 3'd2 || m_axi_awburst != 2'b01)
                record_protocol_error("AW attributes are not one 16-beat INCR burst");
            if (m_axi_awaddr[5:0] != 0)
                record_protocol_error("AW address is not 64-byte aligned");
            if (check_axi_addr && m_axi_awaddr != expected_awaddr) begin
                data_errors <= data_errors + 1;
                $display("AXI_ADDR_FAIL got=%08x expected=%08x", m_axi_awaddr,
                    expected_awaddr);
            end
            expected_awaddr <= expected_awaddr + 32'd64;
            burst_active <= 1'b1;
            burst_beat_index <= 0;
        end

        if (m_axi_wvalid && m_axi_wready) begin
            w_count <= w_count + 1;
            if (!burst_active)
                record_protocol_error("W accepted without an active AW burst");
            if (m_axi_wstrb != 4'hf)
                record_protocol_error("W strobe is not 4'hf");
            if (m_axi_wlast != (burst_beat_index == 15))
                record_protocol_error("WLAST does not match beat 15");
            if (check_axi_data) begin
                expected_word = {pixel_pattern(expected_pixel_index + 1),
                    pixel_pattern(expected_pixel_index)};
                if (m_axi_wdata != expected_word) begin
                    data_errors <= data_errors + 1;
                    $display("AXI_DATA_FAIL pixel=%0d got=%08x expected=%08x",
                        expected_pixel_index, m_axi_wdata, expected_word);
                end
                expected_pixel_index <= expected_pixel_index + 2;
            end
            if (m_axi_wlast) begin
                burst_active <= 1'b0;
                b_pending <= 1'b1;
                b_delay_count <= 4;
                pending_bresp <= b_response_sequence == inject_bresp_index ?
                    2'b10 : 2'b00;
                b_response_sequence <= b_response_sequence + 1;
            end else begin
                burst_beat_index <= burst_beat_index + 1;
            end
        end

        if (b_pending && !m_axi_bvalid && !block_b) begin
            if (b_delay_count == 0) begin
                m_axi_bvalid <= 1'b1;
                m_axi_bresp <= pending_bresp;
            end else begin
                b_delay_count <= b_delay_count - 1;
                b_wait_cycles <= b_wait_cycles + 1;
            end
        end
        if (m_axi_bvalid && m_axi_bready) begin
            b_count <= b_count + 1;
            m_axi_bvalid <= 1'b0;
            b_pending <= 1'b0;
        end
    end
end

task automatic clear_scoreboard(input [31:0] base_addr);
begin
    @(negedge aclk);
    axi_cycle = 0;
    aw_count = 0;
    w_count = 0;
    b_count = 0;
    aw_stall_cycles = 0;
    w_stall_cycles = 0;
    b_wait_cycles = 0;
    protocol_errors = 0;
    data_errors = 0;
    expected_pixel_index = 0;
    burst_beat_index = 0;
    b_response_sequence = 0;
    inject_bresp_index = -1;
    expected_awaddr = base_addr;
    block_aw = 1'b0;
    block_w = 1'b0;
    block_b = 1'b0;
    check_axi_addr = 1'b0;
    check_axi_data = 1'b0;
end
endtask

task automatic reset_dut;
begin
    aresetn = 1'b0;
    pclk_resetn = 1'b0;
    vsync = 1'b1;
    href = 1'b0;
    data = 8'b0;
    psel = 1'b0;
    penable = 1'b0;
    pwrite = 1'b0;
    repeat (8) @(posedge aclk);
    repeat (4) @(posedge pclk);
    @(negedge aclk);
    aresetn = 1'b1;
    @(negedge pclk);
    pclk_resetn = 1'b1;
    repeat (8) @(posedge aclk);
end
endtask

task automatic apb_write(
    input [11:0] address,
    input [31:0] value,
    output bit error
);
begin
    @(negedge aclk);
    paddr = {1'b0, address};
    psel = 1'b1;
    penable = 1'b0;
    pwrite = 1'b1;
    pwdata = value;
    @(negedge aclk);
    penable = 1'b1;
    @(posedge aclk);
    #1ps;
    error = !pready || pslverr;
    @(negedge aclk);
    psel = 1'b0;
    penable = 1'b0;
    pwrite = 1'b0;
    pwdata = 32'b0;
end
endtask

task automatic apb_read(
    input [11:0] address,
    output [31:0] value,
    output bit error
);
begin
    @(negedge aclk);
    paddr = {1'b0, address};
    psel = 1'b1;
    penable = 1'b0;
    pwrite = 1'b0;
    @(negedge aclk);
    penable = 1'b1;
    @(posedge aclk);
    #1ps;
    value = prdata;
    error = !pready || pslverr;
    @(negedge aclk);
    psel = 1'b0;
    penable = 1'b0;
end
endtask

task automatic write_reg(input [11:0] address, input [31:0] value);
    bit error;
begin
    apb_write(address, value, error);
    check(!error, $sformatf("APB write %03x accepted", address));
end
endtask

task automatic read_reg(input [11:0] address, output [31:0] value);
    bit error;
begin
    apb_read(address, value, error);
    check(!error, $sformatf("APB read %03x accepted", address));
end
endtask

task automatic queue_descriptor(input [31:0] address, input [31:0] tag);
begin
    write_reg(12'h040, address);
    write_reg(12'h044, tag);
    write_reg(12'h048, 32'd1);
end
endtask

task automatic begin_frame;
begin
    @(negedge pclk);
    href = 1'b0;
    vsync = 1'b1;
    repeat (2) @(negedge pclk);
    vsync = 1'b0;
    @(negedge pclk);
end
endtask

task automatic send_line(
    input integer pixel_count,
    input integer pattern_offset,
    input integer blank_cycles
);
    integer pixel;
    reg [15:0] pixel_value;
begin
    for (pixel = 0; pixel < pixel_count; pixel = pixel + 1) begin
        pixel_value = pixel_pattern(pattern_offset + pixel);
        @(negedge pclk);
        href = 1'b1;
        data = pixel_value[15:8];
        @(negedge pclk);
        data = pixel_value[7:0];
    end
    @(negedge pclk);
    href = 1'b0;
    data = 8'b0;
    repeat (blank_cycles) @(negedge pclk);
end
endtask

task automatic finish_frame;
begin
    @(negedge pclk);
    href = 1'b0;
    vsync = 1'b1;
    repeat (3) @(negedge pclk);
end
endtask

task automatic send_frame_shape(
    input integer line_total,
    input integer exceptional_line,
    input integer exceptional_pixels
);
    integer line;
    integer pixels;
begin
    frame_sequence = frame_sequence + 1;
    $display("DVP_FRAME_BEGIN sequence=%0d lines=%0d exceptional_line=%0d exceptional_pixels=%0d time=%0t",
        frame_sequence, line_total, exceptional_line, exceptional_pixels, $time);
    begin_frame();
    for (line = 0; line < line_total; line = line + 1) begin
        pixels = line == exceptional_line ? exceptional_pixels : WIDTH;
        send_line(pixels, line * WIDTH, 1);
    end
    finish_frame();
    $display("DVP_FRAME_END sequence=%0d time=%0t", frame_sequence, $time);
end
endtask

task automatic wait_done_count(input integer target, input integer timeout_cycles);
    integer cycles;
begin
    cycles = 0;
    while (dut.done_count < target && cycles < timeout_cycles) begin
        @(posedge aclk);
        cycles = cycles + 1;
    end
    check(dut.done_count >= target,
        $sformatf("completion count reached %0d within %0d aclk cycles", target,
            timeout_cycles));
end
endtask

task automatic read_completion(
    output [31:0] tag,
    output [31:0] status,
    output [31:0] bytes
);
begin
    read_reg(12'h060, tag);
    read_reg(12'h064, status);
    read_reg(12'h068, bytes);
end
endtask

task automatic verify_common_axi;
begin
    check(protocol_errors == 0, "AXI protocol monitor reported no violations");
    check(data_errors == 0, "AXI address/data scoreboard reported no mismatch");
    check(aw_stall_cycles > 0, "AW channel experienced backpressure");
    check(w_stall_cycles > 0, "W channel experienced backpressure");
    check(b_wait_cycles > 0, "B response was delayed");
end
endtask

task automatic run_full_frame;
    reg [31:0] tag;
    reg [31:0] status;
    reg [31:0] bytes;
    reg [31:0] counter;
begin
    clear_scoreboard(32'h1000_0000);
    check_axi_addr = 1'b1;
    check_axi_data = 1'b1;
    queue_descriptor(32'h1000_0000, 32'hf00d_0001);
    write_reg(12'h00c, 32'h0000_0001);
    send_frame_shape(HEIGHT, -1, WIDTH);
    wait_done_count(1, 2_000_000);
    read_completion(tag, status, bytes);
    check(tag == 32'hf00d_0001, "full-frame completion tag matches descriptor");
    check(status == 0, "full-frame completion status is success");
    check(bytes == FRAME_BYTES, "full-frame completion reports 614400 bytes");
    check(aw_count == FRAME_BYTES / 64, "full frame issued exactly 9600 AW bursts");
    check(w_count == FRAME_BYTES / 4, "full frame issued exactly 153600 W beats");
    check(b_count == FRAME_BYTES / 64, "full frame received exactly 9600 BRESPs");
    check(expected_pixel_index == WIDTH * HEIGHT,
        "full-frame scoreboard consumed exactly 307200 RGB565 pixels");
    read_reg(12'h080, counter);
    check(counter == 1, "frames_started incremented once");
    read_reg(12'h084, counter);
    check(counter == 1, "frames_completed incremented once");
    verify_common_axi();
end
endtask

task automatic run_bresp_error;
    reg [31:0] tag;
    reg [31:0] status;
    reg [31:0] bytes;
    reg [31:0] counter;
begin
    clear_scoreboard(32'h1100_0000);
    inject_bresp_index = 0;
    block_aw = 1'b1;
    queue_descriptor(32'h1100_0000, 32'hf00d_0002);
    write_reg(12'h00c, 32'h0000_0001);
    fork
        send_frame_shape(1, -1, WIDTH);
        begin
            wait (m_axi_awvalid);
            repeat (8) @(posedge aclk);
            block_aw = 1'b0;
        end
    join
    wait_done_count(1, 100_000);
    read_completion(tag, status, bytes);
    check(tag == 32'hf00d_0002, "BRESP-error completion tag matches descriptor");
    check(status == STATUS_AXI_BRESP, "BRESP SLVERR maps to AXI error status");
    check(bytes == 0, "failed first burst reports zero acknowledged bytes");
    read_reg(12'h090, counter);
    check(counter == 1, "AXI error counter incremented once");
    check(protocol_errors == 0, "BRESP-error run preserved AXI handshakes");
    check(aw_stall_cycles >= 8 && w_stall_cycles > 0 && b_wait_cycles > 0,
        "BRESP-error run exercised AW/W/B delays");
end
endtask

task automatic run_fifo_overflow;
    reg [31:0] tag;
    reg [31:0] status;
    reg [31:0] bytes;
    reg [31:0] counter;
begin
    clear_scoreboard(32'h1200_0000);
    queue_descriptor(32'h1200_0000, 32'hf00d_0003);
    write_reg(12'h00c, 32'h0000_0001);
    block_aw = 1'b1;
    send_frame_shape(HEIGHT, -1, WIDTH);
    check(aw_stall_cycles > 8192, "AW was blocked long enough to fill the real CDC FIFO");
    block_aw = 1'b0;
    wait_done_count(1, 500_000);
    read_completion(tag, status, bytes);
    check(tag == 32'hf00d_0003, "overflow completion tag matches descriptor");
    check((status & STATUS_FIFO_OVERFLOW) != 0,
        "FIFO overflow is returned in completion status");
    read_reg(12'h08c, counter);
    check(counter > 0, "FIFO overflow counter incremented");
    check(protocol_errors == 0, "AW payload stayed stable through long backpressure");
end
endtask

task automatic run_irq;
    reg [31:0] value;
begin
    clear_scoreboard(32'h1180_0000);
    inject_bresp_index = 0;
    write_reg(12'h018, 32'h0000_0008);
    queue_descriptor(32'h1180_0000, 32'hf00d_0008);
    write_reg(12'h00c, 32'h0000_0001);
    send_frame_shape(1, -1, WIDTH);
    wait_done_count(1, 100_000);
    check(interrupt, "AXI error raises enabled interrupt");
    read_reg(12'h014, value);
    check((value & 32'h0000_0008) != 0, "AXI error sticky bit is set");
    write_reg(12'h014, 32'h0000_0008);
    read_reg(12'h014, value);
    check((value & 32'h0000_0008) == 0, "W1C clears AXI error sticky bit");

    write_reg(12'h06c, 32'd1);
    inject_bresp_index = 1;
    block_b = 1'b1;
    queue_descriptor(32'h1181_0000, 32'hf00d_0009);
    send_frame_shape(1, -1, WIDTH);
    wait (b_pending);
    repeat (8) @(posedge aclk);
    block_b = 1'b0;
    wait (m_axi_bvalid);
    @(negedge aclk);
    paddr = 13'h014;
    psel = 1'b1;
    penable = 1'b0;
    pwrite = 1'b1;
    pwdata = 32'h0000_0008;
    @(negedge aclk);
    penable = 1'b1;
    check(dut.completion_valid && dut.completion_ready,
        "second AXI-error completion is aligned with IRQ W1C access");
    @(posedge aclk);
    #1ps;
    check(!pslverr, "IRQ W1C remains a legal APB access during concurrent event");
    @(negedge aclk);
    psel = 1'b0;
    penable = 1'b0;
    pwrite = 1'b0;
    read_reg(12'h014, value);
    check((value & 32'h0000_0008) != 0,
        "new AXI error wins over same-cycle W1C clear");
    check(interrupt, "concurrent AXI error keeps enabled interrupt asserted");
end
endtask

task automatic run_no_descriptor;
    reg [31:0] counter;
begin
    clear_scoreboard(32'h1300_0000);
    write_reg(12'h00c, 32'h0000_0001);
    send_frame_shape(HEIGHT, -1, WIDTH);
    repeat (1000) @(posedge aclk);
    read_reg(12'h094, counter);
    check(counter == 1, "frame without descriptor increments no_buffer_drops");
    read_reg(12'h088, counter);
    check(counter == 1, "frame without descriptor increments frames_dropped");
    read_reg(12'h070, counter);
    check(counter == 0, "frame without descriptor creates no completion");
    check(aw_count == 0 && w_count == 0, "frame without descriptor performs no AXI writes");
end
endtask

task automatic run_completion_full;
    integer index;
    reg [31:0] counter;
begin
    clear_scoreboard(32'h1400_0000);
    write_reg(12'h00c, 32'h0000_0001);
    for (index = 0; index < 4; index = index + 1) begin
        queue_descriptor(32'h1400_0000 + index * 32'h0010_0000,
            32'hc000_0000 + index);
        send_frame_shape(HEIGHT, -1, WIDTH);
        wait_done_count(index + 1, 2_000_000);
    end
    read_reg(12'h070, counter);
    check(counter == 4, "completion FIFO reached its documented depth of four");
    queue_descriptor(32'h1480_0000, 32'hc000_0004);
    send_frame_shape(HEIGHT, -1, WIDTH);
    repeat (100) @(posedge aclk);
    read_reg(12'h04c, counter);
    check(counter == 1, "fifth descriptor remains queued while completion FIFO is full");
    read_reg(12'h094, counter);
    check(counter == 1, "fifth frame is counted as a no-buffer drop");
    write_reg(12'h06c, 32'd1);
    repeat (100) @(posedge aclk);
    read_reg(12'h070, counter);
    check(counter == 3, "completion pop frees one queue entry");
    read_reg(12'h04c, counter);
    check(counter == 1, "dropped fifth frame did not consume its descriptor");
    send_frame_shape(HEIGHT, -1, WIDTH);
    wait_done_count(4, 2_000_000);
    read_reg(12'h070, counter);
    check(counter == 4, "sixth frame fills the released completion entry");
    read_reg(12'h04c, counter);
    check(counter == 0, "sixth frame consumes the retained descriptor");
    check(aw_count == 5 * (FRAME_BYTES / 64),
        "five accepted full frames issued exactly 48000 AW bursts");
    check(w_count == 5 * (FRAME_BYTES / 4),
        "five accepted full frames issued exactly 768000 W beats");
    check(b_count == 5 * (FRAME_BYTES / 64),
        "five accepted full frames received exactly 48000 BRESPs");
    verify_common_axi();
end
endtask

task automatic run_stop;
    reg [31:0] tag;
    reg [31:0] status;
    reg [31:0] bytes;
begin
    clear_scoreboard(32'h1500_0000);
    queue_descriptor(32'h1500_0000, 32'hf00d_0005);
    write_reg(12'h00c, 32'h0000_0001);
    fork
        send_frame_shape(20, -1, WIDTH);
        begin
            wait (b_count >= 2);
            write_reg(12'h00c, 32'h0000_0000);
        end
    join
    wait_done_count(1, 100_000);
    read_completion(tag, status, bytes);
    check(tag == 32'hf00d_0005, "stop completion tag matches descriptor");
    check((status & STATUS_ABORTED) != 0, "capture-enable clear aborts active frame");
    check(bytes >= 128 && bytes < FRAME_BYTES,
        "stop completion reports only finished bursts");
    check(protocol_errors == 0, "stop preserves AXI protocol");
end
endtask

task automatic run_abort;
    reg [31:0] tag;
    reg [31:0] status;
    reg [31:0] bytes;
begin
    clear_scoreboard(32'h1600_0000);
    queue_descriptor(32'h1600_0000, 32'hf00d_0006);
    write_reg(12'h00c, 32'h0000_0001);
    fork
        send_frame_shape(20, -1, WIDTH);
        begin
            wait (aw_count >= 1);
            block_w = 1'b1;
            wait (m_axi_wvalid);
            repeat (8) @(posedge aclk);
            write_reg(12'h01c, 32'd1);
            block_w = 1'b0;
        end
    join
    wait_done_count(1, 100_000);
    read_completion(tag, status, bytes);
    check(tag == 32'hf00d_0006, "abort completion tag matches descriptor");
    check((status & STATUS_ABORTED) != 0, "abort command marks completion aborted");
    check(bytes == 64, "abort during first W burst completes exactly that burst");
    check(w_stall_cycles >= 8, "abort was asserted during W-channel backpressure");
    check(protocol_errors == 0, "abort preserves AW/W/B protocol");
end
endtask

task automatic run_reset;
    reg [31:0] value;
    reg [31:0] tag;
    reg [31:0] status;
    reg [31:0] bytes;
begin
    clear_scoreboard(32'h1700_0000);
    queue_descriptor(32'h1700_0000, 32'hdead_0001);
    write_reg(12'h00c, 32'h0000_0001);
    block_aw = 1'b1;
    begin_frame();
    send_line(WIDTH, 0, 1);
    wait (m_axi_awvalid);
    aresetn = 1'b0;
    pclk_resetn = 1'b0;
    vsync = 1'b1;
    href = 1'b0;
    repeat (8) @(posedge aclk);
    repeat (4) @(posedge pclk);
    @(negedge aclk);
    aresetn = 1'b1;
    @(negedge pclk);
    pclk_resetn = 1'b1;
    repeat (8) @(posedge aclk);
    clear_scoreboard(32'h1780_0000);
    read_reg(12'h00c, value);
    check(value == 0, "reset clears capture control");
    read_reg(12'h04c, value);
    check(value == 0, "reset clears descriptor FIFO");
    read_reg(12'h070, value);
    check(value == 0, "reset clears completion FIFO");
    queue_descriptor(32'h1780_0000, 32'hf00d_0007);
    write_reg(12'h00c, 32'h0000_0001);
    check_axi_addr = 1'b1;
    check_axi_data = 1'b1;
    send_frame_shape(HEIGHT, -1, WIDTH);
    wait_done_count(1, 2_000_000);
    read_completion(tag, status, bytes);
    check(tag == 32'hf00d_0007 && status == 0 && bytes == FRAME_BYTES,
        "clean full frame succeeds after mid-frame dual-domain reset");
    check(expected_pixel_index == WIDTH * HEIGHT,
        "post-reset frame retained all RGB565 pixels");
    verify_common_axi();
end
endtask

task automatic run_bad_shape(
    input string shape_name,
    input integer line_total,
    input integer exceptional_line,
    input integer exceptional_pixels,
    input [31:0] base_addr,
    input [31:0] tag_value
);
    reg [31:0] tag;
    reg [31:0] status;
    reg [31:0] bytes;
begin
    clear_scoreboard(base_addr);
    queue_descriptor(base_addr, tag_value);
    write_reg(12'h00c, 32'h0000_0001);
    send_frame_shape(line_total, exceptional_line, exceptional_pixels);
    wait_done_count(1, 2_000_000);
    read_completion(tag, status, bytes);
    $display("BAD_SHAPE_RESULT name=%s tag=%08x status=%08x bytes=%0d",
        shape_name, tag, status, bytes);
    check(tag == tag_value, $sformatf("%s completion tag matches", shape_name));
    check(status == STATUS_FRAME_SIZE,
        $sformatf("%s is classified as frame-size error", shape_name));
    check(bytes < 32'h0100_0000,
        $sformatf("%s completion byte count is bounded", shape_name));
    check(protocol_errors == 0,
        $sformatf("%s preserves AXI protocol", shape_name));
end
endtask

task automatic finish_selected_test(input string test_name);
begin
    $display("TEST_SUMMARY name=%s failures=%0d protocol_errors=%0d data_errors=%0d aw=%0d w=%0d b=%0d aw_stall=%0d w_stall=%0d b_wait=%0d",
        test_name, failures, protocol_errors, data_errors, aw_count, w_count,
        b_count, aw_stall_cycles, w_stall_cycles, b_wait_cycles);
    if (failures == 0) begin
        $display("TEST_PASS name=%s", test_name);
        $finish;
    end else begin
        $display("TEST_FAIL name=%s failures=%0d", test_name, failures);
        $fatal(1, "selected camera test failed");
    end
end
endtask

string selected_test;
initial begin : test_dispatch
    if (!$value$plusargs("TEST=%s", selected_test))
        selected_test = "full_frame";
    $display("TEST_BEGIN name=%s aclk_period_ns=10 pclk_period_ns=13 pclk_phase_ns=1.7",
        selected_test);
    reset_dut();

    if (selected_test == "full_frame")
        run_full_frame();
    else if (selected_test == "bresp_error")
        run_bresp_error();
    else if (selected_test == "fifo_overflow")
        run_fifo_overflow();
    else if (selected_test == "irq")
        run_irq();
    else if (selected_test == "no_descriptor")
        run_no_descriptor();
    else if (selected_test == "completion_full")
        run_completion_full();
    else if (selected_test == "stop")
        run_stop();
    else if (selected_test == "abort")
        run_abort();
    else if (selected_test == "reset")
        run_reset();
    else if (selected_test == "short_line")
        run_bad_shape("short_line", HEIGHT, 17, WIDTH - 1,
            32'h1800_0000, 32'hbad0_0001);
    else if (selected_test == "long_line")
        run_bad_shape("long_line", HEIGHT, 17, WIDTH + 1,
            32'h1900_0000, 32'hbad0_0002);
    else if (selected_test == "short_frame")
        run_bad_shape("short_frame", HEIGHT - 1, -1, WIDTH,
            32'h1a00_0000, 32'hbad0_0003);
    else if (selected_test == "long_frame")
        run_bad_shape("long_frame", HEIGHT + 1, -1, WIDTH,
            32'h1b00_0000, 32'hbad0_0004);
    else begin
        failures = failures + 1;
        $display("CHECK_FAIL unknown TEST=%s", selected_test);
    end

    finish_selected_test(selected_test);
end

initial begin : watchdog
    #200_000_000ns;
    $fatal(1, "camera testbench watchdog expired");
end

endmodule

`default_nettype wire
