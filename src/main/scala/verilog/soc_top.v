`include "config.h"

module soc_top(
    input         resetn,
    input         clk,

    //------gpio----------------
    output [15:0] led,
    output [1 :0] led_rg0,
    output [1 :0] led_rg1,
    output [7 :0] num_csn,
    output [6 :0] num_a_g,
    input  [7 :0] switch,
    output [3 :0] btn_key_col,
    input  [3 :0] btn_key_row,
    input  [1 :0] btn_step,

    //------OV7670 camera on J13------
    input         CAMERA_PCLK,
    input         CAMERA_VSYNC,
    input         CAMERA_HREF,
    input  [7:0]  CAMERA_D,
    output        CAMERA_XCLK,
    output        CAMERA_RESETN,
    output        CAMERA_PWDN,
    inout         CAMERA_SIOC,
    inout         CAMERA_SIOD,

    //------8x8 LED dot matrix------
    output        FPGA_DOT_C1,
    output        FPGA_DOT_C2,
    output        FPGA_DOT_C3,
    output        FPGA_DOT_C4,
    output        FPGA_DOT_C5,
    output        FPGA_DOT_C6,
    output        FPGA_DOT_C7,
    output        FPGA_DOT_C8,
    output        FPGA_DOT_R1,
    output        FPGA_DOT_R2,
    output        FPGA_DOT_R3,
    output        FPGA_DOT_R4,
    output        FPGA_DOT_R5,
    output        FPGA_DOT_R6,
    output        FPGA_DOT_R7,
    output        FPGA_DOT_R8,

    //------DDR3 interface------
    inout  [15:0] ddr3_dq,
    output [12:0] ddr3_addr,
    output [2 :0] ddr3_ba,
    output        ddr3_ras_n,
    output        ddr3_cas_n,
    output        ddr3_we_n,
    output        ddr3_odt,
    output        ddr3_reset_n,
    output        ddr3_cke,
    output [1 :0] ddr3_dm,
    inout  [1 :0] ddr3_dqs_p,
    inout  [1 :0] ddr3_dqs_n,
    output        ddr3_ck_p,
    output        ddr3_ck_n,

    //------mac controller-------
    input         mtxclk_0,
    output        mtxen_0,
    output [3:0]  mtxd_0,
    output        mtxerr_0,
    input         mrxclk_0,
    input         mrxdv_0,
    input  [3:0]  mrxd_0,
    input         mrxerr_0,
    input         mcoll_0,
    input         mcrs_0,
    output        mdc_0,
    inout         mdio_0,
    output        phy_rstn,

    //------EJTAG-------
    input         EJTAG_TRST,
    input         EJTAG_TCK,
    input         EJTAG_TDI,
    input         EJTAG_TMS,
    output        EJTAG_TDO,

    //------uart-------
    inout         UART_RX,
    inout         UART_TX,

    //------micro SDIO------
    input         SD_CD_N,
    output        SD_CLK,
    inout         SD_CMD,
    inout  [3:0]  SD_DAT,

    //------nand-------
    output        NAND_CLE,
    output        NAND_ALE,
    input         NAND_RDY,
    inout  [7:0]  NAND_DATA,
    output        NAND_RD,
    output        NAND_CE,
    output        NAND_WR,

    //------spi flash-------
    output        SPI_CLK,
    output        SPI_CS,
    inout         SPI_MISO,
    inout         SPI_MOSI,

    //------vga------
    output [3:0] VGA_R,
    output [3:0] VGA_G,
    output [3:0] VGA_B,
    output VGA_VSYNC,
    output VGA_HSYNC,

    //------LCD display------
    inout  [15:0] LCD_data_tri_io,
    output        LCD_nrst,
    output        LCD_csel,
    output        LCD_rs,
    output        LCD_wr,
    output        LCD_rd,
    output        LCD_lighton,

    //------LCD touch------
    inout         LCD_TOUCH_SCL,
    inout         LCD_TOUCH_SDA,
    inout         LCD_TOUCH_INT,
    output        LCD_TOUCH_RESET,

    //------USB3500 UTMI+------
    input         FPGA_USB_PHY0_CLK,
    output        FPGA_USB_PHY0_RST,
    output        USB_SUSPENDN,
    inout  [7:0]  FPGA_USB_PHY0_DATA,
    output        USB_TXVALID,
    input         USB_TXREADY,
    input         USB_RXVALID,
    input         USB_RXACTIVE,
    input         USB_RXERROR,
    input         USB_ID_LINESTATE0,
    input         USB_ID_LINESTATE1,
    output        USB_XCVRSEL0,
    output        USB_XCVRSEL1,
    output        USB_TERMSEL,
    output        USB_OPMODE0,
    output        USB_OPMODE1,
    output        USB_DPPD,
    output        USB_DMPD,
    input         USB_VBUSVLD,
    input         USB_SESSVLD,
    input         USB_SESSEND,
    input         USB_HOSTDISC,
    input         USB_ID_DIG,
    output        USB_ID_PULLUP,
    output        USB_CHRGVBUS,
    output        USB_DISCHRGVBUS
);

wire        mac_md_i_0;
wire        mac_md_o_0;
wire        mac_md_oe_0;

wire        uart_txd_o;
wire        uart_txd_oe;
wire        uart_rxd_o;
wire        uart_rxd_oe;

wire [3:0]  nand_ce;
wire [7:0]  nand_dat_i;
wire [7:0]  nand_dat_o;
wire        nand_dat_oe;

wire [3:0]  spi_csn_o;
wire [3:0]  spi_csn_en;
wire        spi_sck_o;
wire        spi_sdo_i;
wire        spi_sdo_o;
wire        spi_sdo_en;
wire        spi_sdi_i;
wire        spi_sdi_o;
wire        spi_sdi_en;
wire        spi_inta_o;

wire        scl_pad_i;
wire        scl_pad_o;
wire        scl_padoen_o;
wire        sda_pad_i;
wire        sda_pad_o;
wire        sda_padoen_o;

wire [15:0] lcd_data_o;

wire [7:0]  usb_data_i;
wire [7:0]  usb_data_o;
wire [7:0]  usb_data_t;
wire        usb_tx_valid;
wire [7:0]  fpga_dot_columns;
wire [7:0]  fpga_dot_rows;
wire        camera_xclk;
wire        camera_resetn;
wire        camera_pwdn;
wire        camera_sioc_i;
wire        camera_sioc_o;
wire        camera_sioc_oen;
wire        camera_siod_i;
wire        camera_siod_o;
wire        camera_siod_oen;

assign {FPGA_DOT_C8, FPGA_DOT_C7, FPGA_DOT_C6, FPGA_DOT_C5,
        FPGA_DOT_C4, FPGA_DOT_C3, FPGA_DOT_C2, FPGA_DOT_C1} = fpga_dot_columns;
assign {FPGA_DOT_R8, FPGA_DOT_R7, FPGA_DOT_R6, FPGA_DOT_R5,
        FPGA_DOT_R4, FPGA_DOT_R3, FPGA_DOT_R2, FPGA_DOT_R1} = fpga_dot_rows;

(* ASYNC_REG = "TRUE" *) reg [1:0] usb_phy_reset_sync_ff;

(* ASYNC_REG = "TRUE" *) reg [1:0] mtxclk_sync_ff;
(* ASYNC_REG = "TRUE" *) reg [1:0] mrxclk_sync_ff;
(* ASYNC_REG = "TRUE" *) reg [1:0] mtxen_sync_ff;
(* ASYNC_REG = "TRUE" *) reg [1:0] mrxdv_sync_ff;
reg [31:0] eth_ref_counter;

always @(posedge FPGA_USB_PHY0_CLK or negedge resetn)
begin
    if (!resetn)
    begin
        usb_phy_reset_sync_ff <= 2'b11;
    end
    else
    begin
        usb_phy_reset_sync_ff <= {usb_phy_reset_sync_ff[0], 1'b0};
    end
end

always @(posedge clk)
begin
    if (!resetn)
    begin
        mtxclk_sync_ff <= 2'b00;
        mrxclk_sync_ff <= 2'b00;
        mtxen_sync_ff  <= 2'b00;
        mrxdv_sync_ff  <= 2'b00;
        eth_ref_counter <= 32'b0;
    end
    else
    begin
        mtxclk_sync_ff <= {mtxclk_sync_ff[0], mtxclk_0};
        mrxclk_sync_ff <= {mrxclk_sync_ff[0], mrxclk_0};
        mtxen_sync_ff  <= {mtxen_sync_ff[0], mtxen_0};
        mrxdv_sync_ff  <= {mrxdv_sync_ff[0], mrxdv_0};
        eth_ref_counter <= eth_ref_counter + 1'b1;
    end
end

// USB IRQ debug temporarily disables the fixed-clock Ethernet speed ILA instance.
// ila_1 u_ila_1 (
//     .clk   (clk),
//     .probe0(mtxclk_sync_ff[1]),
//     .probe1(mrxclk_sync_ff[1]),
//     .probe2(mtxen_sync_ff[1]),
//     .probe3(mrxdv_sync_ff[1]),
//     .probe4(phy_rstn),
//     .probe5(eth_ref_counter)
// );

IOBUF mac_mdio_iobuf (
    .IO(mdio_0),
    .I (mac_md_o_0),
    .T (~mac_md_oe_0),
    .O (mac_md_i_0)
);

assign UART_TX = uart_txd_oe ? 1'bz : uart_txd_o;
assign UART_RX = uart_rxd_oe ? 1'bz : uart_rxd_o;

assign NAND_CE = nand_ce[0];
genvar nand_data_i;
generate
    for (nand_data_i = 0; nand_data_i < 8; nand_data_i = nand_data_i + 1) begin: nand_data_loop
        IOBUF nand_data_iobuf (
            .IO(NAND_DATA[nand_data_i]),
            .I (nand_dat_o[nand_data_i]),
            .T (nand_dat_oe),
            .O (nand_dat_i[nand_data_i])
        );
    end
endgenerate

assign SPI_CLK  = spi_sck_o;
assign SPI_CS   = ~spi_csn_en[0] & spi_csn_o[0];
assign SPI_MOSI = spi_sdo_en ? 1'bz : spi_sdo_o;
assign SPI_MISO = spi_sdi_en ? 1'bz : spi_sdi_o;
assign spi_sdo_i = SPI_MOSI;
assign spi_sdi_i = SPI_MISO;

IOBUF scl_iobuf (
    .I (scl_pad_o),
    .O (scl_pad_i),
    .T (scl_padoen_o),
    .IO(LCD_TOUCH_SCL)
);

IOBUF sda_iobuf (
    .I (sda_pad_o),
    .O (sda_pad_i),
    .T (sda_padoen_o),
    .IO(LCD_TOUCH_SDA)
);

IOBUF camera_sioc_iobuf (
    .I (camera_sioc_o),
    .O (camera_sioc_i),
    .T (camera_sioc_oen),
    .IO(CAMERA_SIOC)
);

IOBUF camera_siod_iobuf (
    .I (camera_siod_o),
    .O (camera_siod_i),
    .T (camera_siod_oen),
    .IO(CAMERA_SIOD)
);

ODDR #(
    .DDR_CLK_EDGE("SAME_EDGE")
) camera_xclk_oddr (
    .Q (CAMERA_XCLK),
    .C (camera_xclk),
    .CE(1'b1),
    .D1(1'b1),
    .D2(1'b0),
    .R (1'b0),
    .S (1'b0)
);

assign CAMERA_RESETN = camera_resetn;
assign CAMERA_PWDN   = camera_pwdn;

wire lcd_touch_interrupt_i;
wire lcd_touch_interrupt_o;
wire lcd_touch_interrupt_oe;

IOBUF lcd_touch_interrupt_iobuf (
    .I (lcd_touch_interrupt_o),
    .O (lcd_touch_interrupt_i),
    .T (~lcd_touch_interrupt_oe),
    .IO(LCD_TOUCH_INT)
);

genvar lcd_data_iobuf_i;
generate
    for (lcd_data_iobuf_i = 0; lcd_data_iobuf_i < 16; lcd_data_iobuf_i = lcd_data_iobuf_i + 1) begin: lcd_data_iobuf_loop
        IOBUF lcd_data_iobuf (
            .IO(LCD_data_tri_io[lcd_data_iobuf_i]),
            .I (lcd_data_o[lcd_data_iobuf_i]),
            .T (1'b0),
            .O ()
        );
    end
endgenerate

assign FPGA_USB_PHY0_RST = usb_phy_reset_sync_ff[1];
assign USB_ID_PULLUP     = 1'b0;
assign USB_CHRGVBUS      = 1'b0;
assign USB_DISCHRGVBUS   = 1'b0;
assign USB_TXVALID       = usb_tx_valid;

genvar usb_data_iobuf_i;
generate
    for (usb_data_iobuf_i = 0; usb_data_iobuf_i < 8; usb_data_iobuf_i = usb_data_iobuf_i + 1) begin: usb_data_iobuf_loop
        IOBUF usb_data_iobuf (
            .IO(FPGA_USB_PHY0_DATA[usb_data_iobuf_i]),
            .I (usb_data_o[usb_data_iobuf_i]),
            .T (usb_data_t[usb_data_iobuf_i]),
            .O (usb_data_i[usb_data_iobuf_i])
        );
    end
endgenerate

CPUSTCSoc u_cpustc_soc (
    .clock                   (clk),
    .reset                   (~resetn),
    .io_resetn               (resetn),
    .io_clk                  (clk),

    .io_gpio_led             (led),
    .io_gpio_led_rg0         (led_rg0),
    .io_gpio_led_rg1         (led_rg1),
    .io_gpio_num_csn         (num_csn),
    .io_gpio_num_a_g         (num_a_g),
    .io_gpio_switch          (switch),
    .io_gpio_btn_key_col     (btn_key_col),
    .io_gpio_btn_key_row     (btn_key_row),
    .io_gpio_btn_step        (btn_step),

    .io_dotMatrix_columns    (fpga_dot_columns),
    .io_dotMatrix_rows       (fpga_dot_rows),

    .io_camera_pclk            (CAMERA_PCLK),
    .io_camera_vsync           (CAMERA_VSYNC),
    .io_camera_href            (CAMERA_HREF),
    .io_camera_data            (CAMERA_D),
    .io_camera_xclk            (camera_xclk),
    .io_camera_resetn          (camera_resetn),
    .io_camera_pwdn            (camera_pwdn),
    .io_camera_sccb_sclPadI    (camera_sioc_i),
    .io_camera_sccb_sclPadO    (camera_sioc_o),
    .io_camera_sccb_sclPadOenO (camera_sioc_oen),
    .io_camera_sccb_sdaPadI    (camera_siod_i),
    .io_camera_sccb_sdaPadO    (camera_siod_o),
    .io_camera_sccb_sdaPadOenO (camera_siod_oen),

    .io_ddr3_ddr3_addr       (ddr3_addr),
    .io_ddr3_ddr3_ba         (ddr3_ba),
    .io_ddr3_ddr3_ras_n      (ddr3_ras_n),
    .io_ddr3_ddr3_cas_n      (ddr3_cas_n),
    .io_ddr3_ddr3_we_n       (ddr3_we_n),
    .io_ddr3_ddr3_odt        (ddr3_odt),
    .io_ddr3_ddr3_reset_n    (ddr3_reset_n),
    .io_ddr3_ddr3_cke        (ddr3_cke),
    .io_ddr3_ddr3_dm         (ddr3_dm),
    .io_ddr3_ddr3_ck_p       (ddr3_ck_p),
    .io_ddr3_ddr3_ck_n       (ddr3_ck_n),
    .io_ddr3_dq              (ddr3_dq),
    .io_ddr3_dqs_p           (ddr3_dqs_p),
    .io_ddr3_dqs_n           (ddr3_dqs_n),

    .io_mac_mtxclk_0         (mtxclk_0),
    .io_mac_mtxen_0          (mtxen_0),
    .io_mac_mtxd_0           (mtxd_0),
    .io_mac_mtxerr_0         (mtxerr_0),
    .io_mac_mrxclk_0         (mrxclk_0),
    .io_mac_mrxdv_0          (mrxdv_0),
    .io_mac_mrxd_0           (mrxd_0),
    .io_mac_mrxerr_0         (mrxerr_0),
    .io_mac_mcoll_0          (mcoll_0),
    .io_mac_mcrs_0           (mcrs_0),
    .io_mac_mdc_0            (mdc_0),
    .io_mac_md_i_0           (mac_md_i_0),
    .io_mac_md_o_0           (mac_md_o_0),
    .io_mac_md_oe_0          (mac_md_oe_0),
    .io_mac_phy_rstn         (phy_rstn),

    .io_ejtag_EJTAG_TRST     (EJTAG_TRST),
    .io_ejtag_EJTAG_TCK      (EJTAG_TCK),
    .io_ejtag_EJTAG_TDI      (EJTAG_TDI),
    .io_ejtag_EJTAG_TMS      (EJTAG_TMS),
    .io_ejtag_EJTAG_TDO      (EJTAG_TDO),

    .io_uart_txd_i           (UART_TX),
    .io_uart_txd_o           (uart_txd_o),
    .io_uart_txd_oe          (uart_txd_oe),
    .io_uart_rxd_i           (UART_RX),
    .io_uart_rxd_o           (uart_rxd_o),
    .io_uart_rxd_oe          (uart_rxd_oe),
    .io_uart_rts_o           (),
    .io_uart_dtr_o           (),
    .io_uart_cts_i           (1'b0),
    .io_uart_dsr_i           (1'b0),
    .io_uart_dcd_i           (1'b0),
    .io_uart_ri_i            (1'b0),
    .io_debugUart_UART_RX2   (1'b1),
    .io_debugUart_UART_TX2   (),

    .io_nand_nandType        (2'h2),
    .io_nand_cle             (NAND_CLE),
    .io_nand_ale             (NAND_ALE),
    .io_nand_rdy             ({3'b0, NAND_RDY}),
    .io_nand_rd              (NAND_RD),
    .io_nand_ce              (nand_ce),
    .io_nand_wr              (NAND_WR),
    .io_nand_dat_i           (nand_dat_i),
    .io_nand_dat_o           (nand_dat_o),
    .io_nand_dat_oe          (nand_dat_oe),

    .io_spiFlash_csn_o       (spi_csn_o),
    .io_spiFlash_csn_en      (spi_csn_en),
    .io_spiFlash_sck_o       (spi_sck_o),
    .io_spiFlash_sdo_i       (spi_sdo_i),
    .io_spiFlash_sdo_o       (spi_sdo_o),
    .io_spiFlash_sdo_en      (spi_sdo_en),
    .io_spiFlash_sdi_i       (spi_sdi_i),
    .io_spiFlash_sdi_o       (spi_sdi_o),
    .io_spiFlash_sdi_en      (spi_sdi_en),
    .io_spiFlash_inta_o      (spi_inta_o),

    .io_vga_vga_r            (VGA_R),
    .io_vga_vga_g            (VGA_G),
    .io_vga_vga_b            (VGA_B),
    .io_vga_vga_vsync        (VGA_VSYNC),
    .io_vga_vga_hsync        (VGA_HSYNC),

    .io_lcd_data              (lcd_data_o),
    .io_lcd_resetn            (LCD_nrst),
    .io_lcd_chipSelectn       (LCD_csel),
    .io_lcd_registerSelect    (LCD_rs),
    .io_lcd_writen            (LCD_wr),
    .io_lcd_readn             (LCD_rd),
    .io_lcd_backlightEnable   (LCD_lighton),

    .io_lcdTouch_i2c_sclPadI    (scl_pad_i),
    .io_lcdTouch_i2c_sclPadO    (scl_pad_o),
    .io_lcdTouch_i2c_sclPadOenO (scl_padoen_o),
    .io_lcdTouch_i2c_sdaPadI    (sda_pad_i),
    .io_lcdTouch_i2c_sdaPadO    (sda_pad_o),
    .io_lcdTouch_i2c_sdaPadOenO (sda_padoen_o),
    .io_lcdTouch_interrupt      (lcd_touch_interrupt_i),
    .io_lcdTouch_interruptOut   (lcd_touch_interrupt_o),
    .io_lcdTouch_interruptOutputEnable(lcd_touch_interrupt_oe),
    .io_lcdTouch_reset          (LCD_TOUCH_RESET),

    .io_usb_dataI            (usb_data_i),
    .io_usb_dataO            (usb_data_o),
    .io_usb_dataOe           (),
    .io_usb_dataT            (usb_data_t),
    .io_usb_txValid          (usb_tx_valid),
    .io_usb_txReady          (USB_TXREADY),
    .io_usb_rxValid          (USB_RXVALID),
    .io_usb_rxActive         (USB_RXACTIVE),
    .io_usb_rxError          (USB_RXERROR),
    .io_usb_lineState        ({USB_ID_LINESTATE1, USB_ID_LINESTATE0}),
    .io_usb_xcvrSel          ({USB_XCVRSEL1, USB_XCVRSEL0}),
    .io_usb_termSel          (USB_TERMSEL),
    .io_usb_opMode           ({USB_OPMODE1, USB_OPMODE0}),
    .io_usb_suspendN         (USB_SUSPENDN),
    .io_usb_dpPd             (USB_DPPD),
    .io_usb_dmPd             (USB_DMPD),
    .io_usb_vbusValid        (USB_VBUSVLD),
    .io_usb_hostDisconnect   (USB_HOSTDISC),

    .io_sdio_sdClock         (SD_CLK),
    .io_sdio_command         (SD_CMD),
    .io_sdio_data            (SD_DAT),
    .io_sdio_cardDetectN     (SD_CD_N),
    .io_usbPhyClk            (FPGA_USB_PHY0_CLK)
);

endmodule
