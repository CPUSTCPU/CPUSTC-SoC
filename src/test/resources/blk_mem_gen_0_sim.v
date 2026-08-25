module blk_mem_gen_0(
    input         clka,
    input         ena,
    input         wea,
    input  [9:0]  addra,
    input  [31:0] dina,
    input         clkb,
    input         enb,
    input  [9:0]  addrb,
    output [31:0] doutb
);
    reg [31:0] mem [0:1023];
    reg [31:0] doutb_pipe;
    reg [31:0] doutb_r;

    assign doutb = doutb_r;

    always @(posedge clka) begin
        if (ena && wea) begin
            mem[addra] <= dina;
        end
    end

    always @(posedge clkb) begin
        if (enb) begin
            doutb_pipe <= mem[addrb];
            doutb_r <= doutb_pipe;
        end
    end
endmodule
