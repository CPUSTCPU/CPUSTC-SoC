`timescale 1ns/1ps

module axi2apb_error_sim;
    localparam [1:0] AXI_OKAY   = 2'b00;
    localparam [1:0] AXI_DECERR = 2'b11;

    reg         clk;
    reg         rst_n;
    reg  [3:0]  axi_s_awid;
    reg  [31:0] axi_s_awaddr;
    reg  [3:0]  axi_s_awlen;
    reg  [2:0]  axi_s_awsize;
    reg  [1:0]  axi_s_awburst;
    reg  [1:0]  axi_s_awlock;
    reg  [3:0]  axi_s_awcache;
    reg  [2:0]  axi_s_awprot;
    reg         axi_s_awvalid;
    wire        axi_s_awready;
    reg  [3:0]  axi_s_wid;
    reg  [31:0] axi_s_wdata;
    reg  [3:0]  axi_s_wstrb;
    reg         axi_s_wlast;
    reg         axi_s_wvalid;
    wire        axi_s_wready;
    wire [3:0]  axi_s_bid;
    wire [1:0]  axi_s_bresp;
    wire        axi_s_bvalid;
    reg         axi_s_bready;
    reg  [3:0]  axi_s_arid;
    reg  [31:0] axi_s_araddr;
    reg  [3:0]  axi_s_arlen;
    reg  [2:0]  axi_s_arsize;
    reg  [1:0]  axi_s_arburst;
    reg  [1:0]  axi_s_arlock;
    reg  [3:0]  axi_s_arcache;
    reg  [2:0]  axi_s_arprot;
    reg         axi_s_arvalid;
    wire        axi_s_arready;
    wire [3:0]  axi_s_rid;
    wire [31:0] axi_s_rdata;
    wire [1:0]  axi_s_rresp;
    wire        axi_s_rlast;
    wire        axi_s_rvalid;
    reg         axi_s_rready;

    wire        apb_valid_cpu;
    reg         cpu_grant;
    reg         apb_word_trans;
    reg  [23:0] apb_high_24b_rd;
    wire [23:0] apb_high_24b_wr;
    wire        apb_clk;
    wire        apb_reset_n;
    wire        reg_psel;
    wire        reg_enable;
    wire        reg_rw;
    wire [19:0] reg_addr;
    wire [7:0]  reg_datai;
    wire [7:0]  reg_datao;
    wire        reg_ready_1;
    wire        reg_error_1;

    integer apb_beat;
    integer error_beat;
    integer pass_count;
    integer timeout_count;
    reg     expected_write;

    assign reg_ready_1 = 1'b1;
    assign reg_error_1 = reg_enable && (apb_beat == error_beat);
    assign reg_datao   = reg_addr[7:0] ^ 8'ha5;

    always #5 clk = ~clk;

    function automatic [19:0] expected_apb_addr(input integer beat);
        case (beat)
            0: expected_apb_addr = 20'h00100;
            1: expected_apb_addr = 20'h00101;
            2: expected_apb_addr = 20'h00102;
            3: expected_apb_addr = 20'h00103;
            default: expected_apb_addr = 20'hfffff;
        endcase
    endfunction

    function automatic [7:0] expected_apb_write_data(input integer beat);
        case (beat)
            0: expected_apb_write_data = 8'h11;
            1: expected_apb_write_data = 8'h22;
            2: expected_apb_write_data = 8'h33;
            3: expected_apb_write_data = 8'h44;
            default: expected_apb_write_data = 8'hxx;
        endcase
    endfunction

    always @(posedge clk) begin
        if (!rst_n)
            apb_beat <= 0;
        else if (reg_enable && reg_ready_1) begin
            if (!reg_psel)
                $fatal(1, "APB PENABLE asserted without PSEL");
            if (reg_addr !== expected_apb_addr(apb_beat))
                $fatal(1, "APB byte address mismatch at beat %0d", apb_beat);
            if (reg_rw !== expected_write)
                $fatal(1, "APB direction mismatch at beat %0d", apb_beat);
            if (expected_write && reg_datai !== expected_apb_write_data(apb_beat))
                $fatal(1, "APB write data mismatch at beat %0d", apb_beat);
            apb_beat <= apb_beat + 1;
        end
    end

    axi2apb_bridge dut (
        .clk(clk),
        .rst_n(rst_n),
        .axi_s_awid(axi_s_awid),
        .axi_s_awaddr(axi_s_awaddr),
        .axi_s_awlen(axi_s_awlen),
        .axi_s_awsize(axi_s_awsize),
        .axi_s_awburst(axi_s_awburst),
        .axi_s_awlock(axi_s_awlock),
        .axi_s_awcache(axi_s_awcache),
        .axi_s_awprot(axi_s_awprot),
        .axi_s_awvalid(axi_s_awvalid),
        .axi_s_awready(axi_s_awready),
        .axi_s_wid(axi_s_wid),
        .axi_s_wdata(axi_s_wdata),
        .axi_s_wstrb(axi_s_wstrb),
        .axi_s_wlast(axi_s_wlast),
        .axi_s_wvalid(axi_s_wvalid),
        .axi_s_wready(axi_s_wready),
        .axi_s_bid(axi_s_bid),
        .axi_s_bresp(axi_s_bresp),
        .axi_s_bvalid(axi_s_bvalid),
        .axi_s_bready(axi_s_bready),
        .axi_s_arid(axi_s_arid),
        .axi_s_araddr(axi_s_araddr),
        .axi_s_arlen(axi_s_arlen),
        .axi_s_arsize(axi_s_arsize),
        .axi_s_arburst(axi_s_arburst),
        .axi_s_arlock(axi_s_arlock),
        .axi_s_arcache(axi_s_arcache),
        .axi_s_arprot(axi_s_arprot),
        .axi_s_arvalid(axi_s_arvalid),
        .axi_s_arready(axi_s_arready),
        .axi_s_rid(axi_s_rid),
        .axi_s_rdata(axi_s_rdata),
        .axi_s_rresp(axi_s_rresp),
        .axi_s_rlast(axi_s_rlast),
        .axi_s_rvalid(axi_s_rvalid),
        .axi_s_rready(axi_s_rready),
        .apb_valid_cpu(apb_valid_cpu),
        .cpu_grant(cpu_grant),
        .apb_word_trans(apb_word_trans),
        .apb_high_24b_rd(apb_high_24b_rd),
        .apb_high_24b_wr(apb_high_24b_wr),
        .apb_clk(apb_clk),
        .apb_reset_n(apb_reset_n),
        .reg_psel(reg_psel),
        .reg_enable(reg_enable),
        .reg_rw(reg_rw),
        .reg_addr(reg_addr),
        .reg_datai(reg_datai),
        .reg_ready_1(reg_ready_1),
        .reg_error_1(reg_error_1),
        .reg_datao(reg_datao)
    );

    task automatic fail(input string message);
        begin
            $display("FAIL cycle=%0t beat=%0d: %s", $time, apb_beat, message);
            $fatal(1);
        end
    endtask

    task automatic wait_awready;
        begin
            timeout_count = 0;
            while (!axi_s_awready) begin
                @(negedge clk);
                timeout_count = timeout_count + 1;
                if (timeout_count > 20)
                    fail("AWREADY timeout");
            end
        end
    endtask

    task automatic wait_wready;
        begin
            timeout_count = 0;
            while (!axi_s_wready) begin
                @(negedge clk);
                timeout_count = timeout_count + 1;
                if (timeout_count > 20)
                    fail("WREADY timeout");
            end
        end
    endtask

    task automatic wait_bvalid;
        begin
            timeout_count = 0;
            while (!axi_s_bvalid) begin
                @(negedge clk);
                timeout_count = timeout_count + 1;
                if (timeout_count > 40)
                    fail("BVALID timeout");
            end
        end
    endtask

    task automatic wait_arready;
        begin
            timeout_count = 0;
            while (!axi_s_arready) begin
                @(negedge clk);
                timeout_count = timeout_count + 1;
                if (timeout_count > 20)
                    fail("ARREADY timeout");
            end
        end
    endtask

    task automatic wait_rvalid;
        begin
            timeout_count = 0;
            while (!axi_s_rvalid) begin
                @(negedge clk);
                timeout_count = timeout_count + 1;
                if (timeout_count > 40)
                    fail("RVALID timeout");
            end
        end
    endtask

    task automatic start_write(input [3:0] id, input integer inject_beat);
        begin
            error_beat = inject_beat;
            apb_beat = 0;
            expected_write = 1'b1;
            axi_s_bready = 1'b0;
            axi_s_awid = id;
            axi_s_awaddr = 32'h1fe0_0100;
            axi_s_awsize = 3'b010;
            axi_s_awvalid = 1'b1;
            wait_awready();
            axi_s_awvalid = 1'b0;

            axi_s_wid = id;
            axi_s_wdata = 32'h4433_2211;
            axi_s_wstrb = 4'b1111;
            axi_s_wlast = 1'b1;
            axi_s_wvalid = 1'b1;
            wait_wready();
            axi_s_wvalid = 1'b0;
            wait_bvalid();
            if (apb_beat != 4)
                fail("32-bit write did not complete four byte APB transfers");
        end
    endtask

    task automatic finish_write;
        begin
            axi_s_bready = 1'b1;
            @(negedge clk);
            axi_s_bready = 1'b0;
            @(negedge clk);
            if (axi_s_bvalid)
                fail("BVALID remained asserted after handshake");
        end
    endtask

    task automatic start_read(input [3:0] id, input integer inject_beat);
        begin
            error_beat = inject_beat;
            apb_beat = 0;
            expected_write = 1'b0;
            axi_s_rready = 1'b0;
            axi_s_arid = id;
            axi_s_araddr = 32'h1fe0_0100;
            axi_s_arsize = 3'b010;
            axi_s_arvalid = 1'b1;
            wait_arready();
            axi_s_arvalid = 1'b0;
            wait_rvalid();
            if (apb_beat != 4)
                fail("32-bit read did not complete four byte APB transfers");
        end
    endtask

    task automatic finish_read;
        begin
            axi_s_rready = 1'b1;
            @(negedge clk);
            axi_s_rready = 1'b0;
            @(negedge clk);
            if (axi_s_rvalid)
                fail("RVALID remained asserted after handshake");
        end
    endtask

    task automatic check_write_response(
        input [3:0] id,
        input integer inject_beat,
        input [1:0] expected_resp,
        input string test_name
    );
        begin
            start_write(id, inject_beat);
            if (axi_s_bid !== id)
                fail({test_name, ": BID mismatch"});
            if (axi_s_bresp !== expected_resp)
                fail({test_name, ": BRESP mismatch"});
            finish_write();
            pass_count = pass_count + 1;
            $display("PASS %s", test_name);
        end
    endtask

    task automatic check_read_response(
        input [3:0] id,
        input integer inject_beat,
        input [1:0] expected_resp,
        input string test_name
    );
        begin
            start_read(id, inject_beat);
            if (axi_s_rid !== id)
                fail({test_name, ": RID mismatch"});
            if (!axi_s_rlast)
                fail({test_name, ": RLAST not asserted"});
            if (axi_s_rresp !== expected_resp)
                fail({test_name, ": RRESP mismatch"});
            if (axi_s_rdata !== 32'ha6a7_a4a5)
                fail({test_name, ": RDATA byte assembly mismatch"});
            finish_read();
            pass_count = pass_count + 1;
            $display("PASS %s", test_name);
        end
    endtask

    task automatic check_write_backpressure;
        reg [3:0] held_bid;
        reg [1:0] held_bresp;
        integer cycle;
        begin
            start_write(4'ha, 1);
            held_bid = axi_s_bid;
            held_bresp = axi_s_bresp;
            for (cycle = 0; cycle < 5; cycle = cycle + 1) begin
                @(negedge clk);
                if (!axi_s_bvalid || axi_s_bid !== held_bid || axi_s_bresp !== held_bresp)
                    fail("B response changed under backpressure");
            end
            if (held_bresp !== AXI_DECERR)
                fail("backpressured write did not return DECERR");
            finish_write();
            pass_count = pass_count + 1;
            $display("PASS write response stable under backpressure");
        end
    endtask

    task automatic check_read_backpressure;
        reg [3:0] held_rid;
        reg [31:0] held_rdata;
        reg [1:0] held_rresp;
        reg held_rlast;
        integer cycle;
        begin
            start_read(4'hb, 2);
            held_rid = axi_s_rid;
            held_rdata = axi_s_rdata;
            held_rresp = axi_s_rresp;
            held_rlast = axi_s_rlast;
            for (cycle = 0; cycle < 5; cycle = cycle + 1) begin
                @(negedge clk);
                if (!axi_s_rvalid || axi_s_rid !== held_rid ||
                    axi_s_rdata !== held_rdata || axi_s_rresp !== held_rresp ||
                    axi_s_rlast !== held_rlast)
                    fail("R response changed under backpressure");
            end
            if (held_rresp !== AXI_DECERR || !held_rlast)
                fail("backpressured read did not return a final DECERR beat");
            finish_read();
            pass_count = pass_count + 1;
            $display("PASS read response stable under backpressure");
        end
    endtask

    initial begin
        $dumpfile("axi2apb_error_sim.vcd");
        $dumpvars(0, axi2apb_error_sim);

        clk = 1'b0;
        rst_n = 1'b0;
        axi_s_awid = 4'b0;
        axi_s_awaddr = 32'b0;
        axi_s_awlen = 4'b0;
        axi_s_awsize = 3'b0;
        axi_s_awburst = 2'b01;
        axi_s_awlock = 2'b0;
        axi_s_awcache = 4'b0;
        axi_s_awprot = 3'b0;
        axi_s_awvalid = 1'b0;
        axi_s_wid = 4'b0;
        axi_s_wdata = 32'b0;
        axi_s_wstrb = 4'b0;
        axi_s_wlast = 1'b0;
        axi_s_wvalid = 1'b0;
        axi_s_bready = 1'b0;
        axi_s_arid = 4'b0;
        axi_s_araddr = 32'b0;
        axi_s_arlen = 4'b0;
        axi_s_arsize = 3'b0;
        axi_s_arburst = 2'b01;
        axi_s_arlock = 2'b0;
        axi_s_arcache = 4'b0;
        axi_s_arprot = 3'b0;
        axi_s_arvalid = 1'b0;
        axi_s_rready = 1'b0;
        cpu_grant = 1'b1;
        apb_word_trans = 1'b0;
        apb_high_24b_rd = 24'hc3_b2_a1;
        apb_beat = 0;
        error_beat = -1;
        pass_count = 0;
        expected_write = 1'b0;

        repeat (4) @(negedge clk);
        rst_n = 1'b1;
        repeat (2) @(negedge clk);

        check_write_response(4'h1, -1, AXI_OKAY, "write OKAY across four APB bytes");
        check_read_response(4'h2, -1, AXI_OKAY, "read OKAY across four APB bytes");

        check_write_response(4'h3, 0, AXI_DECERR, "write DECERR from APB byte 0");
        check_write_response(4'h4, 1, AXI_DECERR, "write DECERR from APB byte 1");
        check_write_response(4'h5, 2, AXI_DECERR, "write DECERR from APB byte 2");
        check_write_response(4'h6, 3, AXI_DECERR, "write DECERR from APB byte 3");

        check_read_response(4'h7, 0, AXI_DECERR, "read DECERR from APB byte 0");
        check_read_response(4'h8, 1, AXI_DECERR, "read DECERR from APB byte 1");
        check_read_response(4'h9, 2, AXI_DECERR, "read DECERR from APB byte 2");
        check_read_response(4'ha, 3, AXI_DECERR, "read DECERR from APB byte 3");

        check_write_backpressure();
        check_read_backpressure();

        check_write_response(4'hc, -1, AXI_OKAY, "write OKAY after prior DECERR");
        check_read_response(4'hd, -1, AXI_OKAY, "read OKAY after prior DECERR");

        $display("ALL PASS: %0d checks", pass_count);
        $finish;
    end
endmodule
