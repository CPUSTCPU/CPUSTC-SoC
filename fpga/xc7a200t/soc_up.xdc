#set_property SEVERITY {Warning} [get_drc_checks RTSTAT-2]

#create_clock -period 10.000 [get_ports clk]
set_property PACKAGE_PIN AC19 [get_ports clk]
set_property CLOCK_DEDICATED_ROUTE BACKBONE [get_nets clk]
create_clock -period 10.000 -name clk -waveform {0.000 5.000} [get_ports clk]

#reset
set_property PACKAGE_PIN Y3 [get_ports resetn]

#LED
set_property PACKAGE_PIN K23 [get_ports {led[0]}]
set_property PACKAGE_PIN J21 [get_ports {led[1]}]
set_property PACKAGE_PIN H23 [get_ports {led[2]}]
set_property PACKAGE_PIN J19 [get_ports {led[3]}]
set_property PACKAGE_PIN G9 [get_ports {led[4]}]
set_property PACKAGE_PIN J26 [get_ports {led[5]}]
set_property PACKAGE_PIN J23 [get_ports {led[6]}]
set_property PACKAGE_PIN J8 [get_ports {led[7]}]
set_property PACKAGE_PIN H8 [get_ports {led[8]}]
set_property PACKAGE_PIN G8 [get_ports {led[9]}]
set_property PACKAGE_PIN F7 [get_ports {led[10]}]
set_property PACKAGE_PIN A4 [get_ports {led[11]}]
set_property PACKAGE_PIN A5 [get_ports {led[12]}]
set_property PACKAGE_PIN A3 [get_ports {led[13]}]
set_property PACKAGE_PIN D5 [get_ports {led[14]}]
set_property PACKAGE_PIN H7 [get_ports {led[15]}]

#led_rg 0/1
set_property PACKAGE_PIN G7 [get_ports {led_rg0[0]}]
set_property PACKAGE_PIN F8 [get_ports {led_rg0[1]}]
set_property PACKAGE_PIN B5 [get_ports {led_rg1[0]}]
set_property PACKAGE_PIN D6 [get_ports {led_rg1[1]}]

# 8x8 LED dot matrix column select
set_property PACKAGE_PIN G6 [get_ports FPGA_DOT_C1]
set_property PACKAGE_PIN G5 [get_ports FPGA_DOT_C2]
set_property PACKAGE_PIN H6 [get_ports FPGA_DOT_C3]
set_property PACKAGE_PIN J4 [get_ports FPGA_DOT_C4]
set_property PACKAGE_PIN J6 [get_ports FPGA_DOT_C5]
set_property PACKAGE_PIN E3 [get_ports FPGA_DOT_C6]
set_property PACKAGE_PIN C1 [get_ports FPGA_DOT_C7]
set_property PACKAGE_PIN H4 [get_ports FPGA_DOT_C8]

# 8x8 LED dot matrix row select
set_property PACKAGE_PIN F3 [get_ports FPGA_DOT_R1]
set_property PACKAGE_PIN F4 [get_ports FPGA_DOT_R2]
set_property PACKAGE_PIN C2 [get_ports FPGA_DOT_R3]
set_property PACKAGE_PIN F5 [get_ports FPGA_DOT_R4]
set_property PACKAGE_PIN H3 [get_ports FPGA_DOT_R5]
set_property PACKAGE_PIN B1 [get_ports FPGA_DOT_R6]
set_property PACKAGE_PIN G4 [get_ports FPGA_DOT_R7]
set_property PACKAGE_PIN J5 [get_ports FPGA_DOT_R8]

set_property IOSTANDARD LVCMOS33 [get_ports {FPGA_DOT_C* FPGA_DOT_R*}]

#NUM
set_property PACKAGE_PIN D3  [get_ports {num_csn[7]}]
set_property PACKAGE_PIN D25 [get_ports {num_csn[6]}]
set_property PACKAGE_PIN D26 [get_ports {num_csn[5]}]
set_property PACKAGE_PIN E25 [get_ports {num_csn[4]}]
set_property PACKAGE_PIN E26 [get_ports {num_csn[3]}]
set_property PACKAGE_PIN G25 [get_ports {num_csn[2]}]
set_property PACKAGE_PIN G26 [get_ports {num_csn[1]}]
set_property PACKAGE_PIN H26 [get_ports {num_csn[0]}]

set_property PACKAGE_PIN C3 [get_ports {num_a_g[0]}]
set_property PACKAGE_PIN E6 [get_ports {num_a_g[1]}]
set_property PACKAGE_PIN B2 [get_ports {num_a_g[2]}]
set_property PACKAGE_PIN B4 [get_ports {num_a_g[3]}]
set_property PACKAGE_PIN E5 [get_ports {num_a_g[4]}]
set_property PACKAGE_PIN D4 [get_ports {num_a_g[5]}]
set_property PACKAGE_PIN A2 [get_ports {num_a_g[6]}]
#set_property PACKAGE_PIN C4 :DP

#switch
set_property PACKAGE_PIN AC21 [get_ports {switch[7]}]
set_property PACKAGE_PIN AD24 [get_ports {switch[6]}]
set_property PACKAGE_PIN AC22 [get_ports {switch[5]}]
set_property PACKAGE_PIN AC23 [get_ports {switch[4]}]
set_property PACKAGE_PIN AB6  [get_ports {switch[3]}]
set_property PACKAGE_PIN W6   [get_ports {switch[2]}]
set_property PACKAGE_PIN AA7  [get_ports {switch[1]}]
set_property PACKAGE_PIN Y6   [get_ports {switch[0]}]

#btn_key
set_property PACKAGE_PIN V8  [get_ports {btn_key_col[0]}]
set_property PACKAGE_PIN V9  [get_ports {btn_key_col[1]}]
set_property PACKAGE_PIN Y8  [get_ports {btn_key_col[2]}]
set_property PACKAGE_PIN V7  [get_ports {btn_key_col[3]}]
set_property PACKAGE_PIN U7  [get_ports {btn_key_row[0]}]
set_property PACKAGE_PIN W8  [get_ports {btn_key_row[1]}]
set_property PACKAGE_PIN Y7  [get_ports {btn_key_row[2]}]
set_property PACKAGE_PIN AA8 [get_ports {btn_key_row[3]}]

#btn_step
set_property PACKAGE_PIN Y5 [get_ports btn_step[0]]
set_property PACKAGE_PIN V6 [get_ports btn_step[1]]

#SPI flash
set_property PACKAGE_PIN P20 [get_ports SPI_CLK]
set_property PACKAGE_PIN R20 [get_ports SPI_CS]
set_property PACKAGE_PIN P19 [get_ports SPI_MISO]
set_property PACKAGE_PIN N18 [get_ports SPI_MOSI]

#mac phy connect
set_property PACKAGE_PIN AB21 [get_ports mtxclk_0]
set_property PACKAGE_PIN AA19 [get_ports mrxclk_0]
set_property PACKAGE_PIN AA15 [get_ports mtxen_0]
set_property PACKAGE_PIN AF18 [get_ports {mtxd_0[0]}]
set_property PACKAGE_PIN AE18 [get_ports {mtxd_0[1]}]
set_property PACKAGE_PIN W15 [get_ports {mtxd_0[2]}]
set_property PACKAGE_PIN W14 [get_ports {mtxd_0[3]}]
set_property PACKAGE_PIN AB20 [get_ports mtxerr_0]
set_property PACKAGE_PIN AE22 [get_ports mrxdv_0]
set_property PACKAGE_PIN V1 [get_ports {mrxd_0[0]}]
set_property PACKAGE_PIN V4 [get_ports {mrxd_0[1]}]
set_property PACKAGE_PIN V2 [get_ports {mrxd_0[2]}]
set_property PACKAGE_PIN V3 [get_ports {mrxd_0[3]}]
set_property PACKAGE_PIN W16 [get_ports mrxerr_0]
set_property PACKAGE_PIN Y15 [get_ports mcoll_0]
set_property PACKAGE_PIN AF20 [get_ports mcrs_0]
set_property PACKAGE_PIN W3 [get_ports mdc_0]
set_property PACKAGE_PIN W1 [get_ports mdio_0]
set_property PACKAGE_PIN AE26 [get_ports phy_rstn]

#uart
set_property PACKAGE_PIN F23 [get_ports UART_RX]
set_property IOSTANDARD LVCMOS33 [get_ports UART_RX]
set_property PACKAGE_PIN H19 [get_ports UART_TX]
set_property IOSTANDARD LVCMOS33 [get_ports UART_TX]

# Micro-SD storage board, 4-bit SDIO at 3.3 V. The module provides external
# 10 kOhm pull-ups, including card detect. Place P1 down J15's right/even row:
# P1.1..P1.8 -> J15.6,8,10,12,14,16,18,20(GND); P1.9 -> J16.2(3.3V).
# Signal pins use J15 EXT0_IO3/5/7/9/11/13/15, respectively.
set_property PACKAGE_PIN AD25 [get_ports SD_CD_N]
set_property PACKAGE_PIN W23  [get_ports SD_CLK]
set_property PACKAGE_PIN V22  [get_ports SD_CMD]
set_property PACKAGE_PIN U26  [get_ports {SD_DAT[0]}]
set_property PACKAGE_PIN AF25 [get_ports {SD_DAT[1]}]
set_property PACKAGE_PIN AF24 [get_ports {SD_DAT[2]}]
set_property PACKAGE_PIN V24  [get_ports {SD_DAT[3]}]
set_property IOSTANDARD LVCMOS33 [get_ports {SD_CD_N SD_CLK SD_CMD SD_DAT[*]}]

#nand flash
set_property PACKAGE_PIN V19 [get_ports NAND_CLE]
set_property PACKAGE_PIN W20 [get_ports NAND_ALE]
set_property PACKAGE_PIN AA25 [get_ports NAND_RDY]
set_property PACKAGE_PIN AA24 [get_ports NAND_RD]
set_property PACKAGE_PIN AB24 [get_ports NAND_CE]
set_property PACKAGE_PIN AA22 [get_ports NAND_WR]
set_property PACKAGE_PIN W19 [get_ports {NAND_DATA[7]}]
set_property PACKAGE_PIN Y20 [get_ports {NAND_DATA[6]}]
set_property PACKAGE_PIN Y21 [get_ports {NAND_DATA[5]}]
set_property PACKAGE_PIN V18 [get_ports {NAND_DATA[4]}]
set_property PACKAGE_PIN U19 [get_ports {NAND_DATA[3]}]
set_property PACKAGE_PIN U20 [get_ports {NAND_DATA[2]}]
set_property PACKAGE_PIN W21 [get_ports {NAND_DATA[1]}]
set_property PACKAGE_PIN AC24 [get_ports {NAND_DATA[0]}]

#ejtag
set_property PACKAGE_PIN J18 [get_ports EJTAG_TRST]
set_property PACKAGE_PIN K18 [get_ports EJTAG_TCK]
set_property PACKAGE_PIN K20 [get_ports EJTAG_TDI]
set_property PACKAGE_PIN K22 [get_ports EJTAG_TMS]
set_property PACKAGE_PIN K21 [get_ports EJTAG_TDO]


set_property IOSTANDARD LVCMOS33 [get_ports clk]
set_property IOSTANDARD LVCMOS33 [get_ports resetn]
set_property IOSTANDARD LVCMOS33 [get_ports {led[*]}]
set_property IOSTANDARD LVCMOS33 [get_ports {led_rg0[*]}]
set_property IOSTANDARD LVCMOS33 [get_ports {led_rg1[*]}]
set_property IOSTANDARD LVCMOS33 [get_ports {num_a_g[*]}]
set_property IOSTANDARD LVCMOS33 [get_ports {num_csn[*]}]
set_property IOSTANDARD LVCMOS33 [get_ports {switch[*]}]
set_property IOSTANDARD LVCMOS33 [get_ports {btn_key_col[*]}]
set_property IOSTANDARD LVCMOS33 [get_ports {btn_key_row[*]}]
set_property IOSTANDARD LVCMOS33 [get_ports {btn_step[*]}]

# OV7670 signal header follows the module silkscreen order on J13 pins 23..38.
# These are EXT_IO16..31, each with a board-level 4.7 kOhm pull-up to 3.3 V:
#   23/24 SIOC/SIOD, 25/26 VSYNC/HREF, 27/28 PCLK/XCLK,
#   29/30 D7/D6, 31/32 D5/D4, 33/34 D3/D2, 35/36 D1/D0,
#   37/38 RESET#/PWDN.
# Power is wired separately: module 3.3V -> J13.1 or 2 and GND -> J13.39 or 40.
# RESET# is active low at the sensor; the SoC drives it high after the 5 ms
# power-up hold.
set_property PACKAGE_PIN M22  [get_ports CAMERA_SIOC]
set_property PACKAGE_PIN N24  [get_ports CAMERA_SIOD]
set_property PACKAGE_PIN N23  [get_ports CAMERA_VSYNC]
set_property PACKAGE_PIN N22  [get_ports CAMERA_HREF]
set_property PACKAGE_PIN N21  [get_ports CAMERA_XCLK]
set_property PACKAGE_PIN T23  [get_ports CAMERA_RESETN]
set_property PACKAGE_PIN M21  [get_ports CAMERA_PCLK]
set_property PACKAGE_PIN T22  [get_ports CAMERA_PWDN]
set_property PACKAGE_PIN M20  [get_ports {CAMERA_D[7]}]
set_property PACKAGE_PIN N19  [get_ports {CAMERA_D[6]}]
set_property PACKAGE_PIN P24  [get_ports {CAMERA_D[5]}]
set_property PACKAGE_PIN P23  [get_ports {CAMERA_D[4]}]
set_property PACKAGE_PIN P21  [get_ports {CAMERA_D[3]}]
set_property PACKAGE_PIN R23  [get_ports {CAMERA_D[2]}]
set_property PACKAGE_PIN R22  [get_ports {CAMERA_D[1]}]
set_property PACKAGE_PIN T24  [get_ports {CAMERA_D[0]}]
set_property IOSTANDARD LVCMOS33 [get_ports {CAMERA_* CAMERA_D[*]}]

# OV7670 VGA RGB565 PCLK can vary with sensor register settings.  The initial
# 5 fps mode is constrained at the conservative 25 MHz maximum clock rate.
create_clock -period 40.000 -name camera_pclk [get_ports CAMERA_PCLK]
set camera_dvp_inputs [get_ports {CAMERA_D[*] CAMERA_HREF CAMERA_VSYNC}]
set_input_delay -clock [get_clocks camera_pclk] -clock_fall -min 0.000 $camera_dvp_inputs
set_input_delay -clock [get_clocks camera_pclk] -clock_fall -max 5.000 $camera_dvp_inputs
# PCLK-to-aClk CDC is constrained by IP/CAMERA/syn/axis_async_fifo.tcl.
# Do not declare the complete domains asynchronous; that would override the
# FIFO Gray-pointer max-delay and bus-skew constraints.

set_property IOSTANDARD LVCMOS33 [get_ports SPI_MOSI]
set_property IOSTANDARD LVCMOS33 [get_ports SPI_MISO]
set_property IOSTANDARD LVCMOS33 [get_ports SPI_CS]
set_property IOSTANDARD LVCMOS33 [get_ports SPI_CLK]

set_property IOSTANDARD LVCMOS33 [get_ports {mrxd_0[*]}]
set_property IOSTANDARD LVCMOS33 [get_ports {mtxd_0[*]}]
set_property IOSTANDARD LVCMOS33 [get_ports phy_rstn]
set_property IOSTANDARD LVCMOS33 [get_ports mtxerr_0]
set_property IOSTANDARD LVCMOS33 [get_ports mtxen_0]
set_property IOSTANDARD LVCMOS33 [get_ports mtxclk_0]
set_property IOSTANDARD LVCMOS33 [get_ports mrxerr_0]
set_property IOSTANDARD LVCMOS33 [get_ports mcoll_0]
set_property IOSTANDARD LVCMOS33 [get_ports mcrs_0]
set_property IOSTANDARD LVCMOS33 [get_ports mdc_0]
set_property IOSTANDARD LVCMOS33 [get_ports mdio_0]
set_property IOSTANDARD LVCMOS33 [get_ports mrxclk_0]
set_property IOSTANDARD LVCMOS33 [get_ports mrxdv_0]

set_property IOSTANDARD LVCMOS33 [get_ports NAND_CLE]
set_property IOSTANDARD LVCMOS33 [get_ports NAND_ALE]
set_property IOSTANDARD LVCMOS33 [get_ports NAND_RDY]
set_property IOSTANDARD LVCMOS33 [get_ports NAND_RD]
set_property IOSTANDARD LVCMOS33 [get_ports NAND_CE]
set_property IOSTANDARD LVCMOS33 [get_ports NAND_WR]
set_property IOSTANDARD LVCMOS33 [get_ports {NAND_DATA[7]}]
set_property IOSTANDARD LVCMOS33 [get_ports {NAND_DATA[6]}]
set_property IOSTANDARD LVCMOS33 [get_ports {NAND_DATA[5]}]
set_property IOSTANDARD LVCMOS33 [get_ports {NAND_DATA[4]}]
set_property IOSTANDARD LVCMOS33 [get_ports {NAND_DATA[3]}]
set_property IOSTANDARD LVCMOS33 [get_ports {NAND_DATA[2]}]
set_property IOSTANDARD LVCMOS33 [get_ports {NAND_DATA[1]}]
set_property IOSTANDARD LVCMOS33 [get_ports {NAND_DATA[0]}]

set_property IOSTANDARD LVCMOS33 [get_ports EJTAG_TRST]
set_property IOSTANDARD LVCMOS33 [get_ports EJTAG_TCK]
set_property IOSTANDARD LVCMOS33 [get_ports EJTAG_TDI]
set_property IOSTANDARD LVCMOS33 [get_ports EJTAG_TMS]
set_property IOSTANDARD LVCMOS33 [get_ports EJTAG_TDO]
# set_property CLOCK_DEDICATED_ROUTE FALSE [get_nets EJTAG_TCK_IBUF]

create_clock -period 40.000 -name mrxclk_0 -waveform {0.000 20.000} [get_ports mrxclk_0]
create_clock -period 40.000 -name mtxclk_0 -waveform {0.000 20.000} [get_ports mtxclk_0]

# DM9161 MII TX timing.  The 40 ns clock and 12 ns output delay cover
# 100 Mbps setup timing; the 6 ns minimum delay covers the 10 Mbps 5 ns
# hold requirement plus 1 ns of implementation/board margin.
set mii_tx_ports [get_ports {mtxd_0[*] mtxen_0 mtxerr_0}]
set_output_delay -clock [get_clocks mtxclk_0] -max 12.000 $mii_tx_ports
set_output_delay -clock [get_clocks mtxclk_0] -min -5.000 $mii_tx_ports

set_false_path -from [get_clocks clk_pll_i] -to [get_clocks clk_out2_clk_pll_33]
set_false_path -from [get_clocks clk_pll_i] -to [get_clocks clk_out1_clk_pll_33]
set_false_path -from [get_clocks mrxclk_0] -to [get_clocks clk_out2_clk_pll_33]
set_false_path -from [get_clocks mtxclk_0] -to [get_clocks clk_out2_clk_pll_33]
# The CPU, AXI, APB, and VGA domains exchange data only through audited CDC protocols.
set_clock_groups -quiet -asynchronous \
    -group [get_clocks -quiet clk] \
    -group [get_clocks -quiet clk_out1_clk_pll_33] \
    -group [get_clocks -quiet clk_out2_clk_pll_33] \
    -group [get_clocks -quiet clk_out2_clk_wiz_1]

# ResetnSync asserts through CLR asynchronously and releases through its constant-one
# register chain. Exclude only the marked synchronizer CLR pins from recovery/removal
# timing; functional CDC paths and the synchronizer register D paths remain timed.
set cpustc_reset_sync_cells [get_cells -hierarchical -quiet \
    -filter {CPUSTC_RESET_SYNC == TRUE}]
set cpustc_reset_sync_clear_pins [get_pins -quiet -of_objects $cpustc_reset_sync_cells \
    -filter {REF_PIN_NAME == CLR}]
set_false_path -to $cpustc_reset_sync_clear_pins

set_false_path -from [get_clocks clk_out2_clk_pll_33] -to [get_clocks mrxclk_0]
set_false_path -from [get_clocks clk_out2_clk_pll_33] -to [get_clocks mrxclk_0]
set_false_path -from [get_clocks clk_out2_clk_pll_33] -to [get_clocks mtxclk_0]
set_false_path -from [get_clocks clk_out2_clk_pll_33] -to [get_clocks mtxclk_0]


## LCD
set_property IOSTANDARD LVCMOS33 [get_ports LCD_*]

set_property -dict {PACKAGE_PIN J24 IOSTANDARD LVCMOS33} [get_ports LCD_TOUCH_SDA]
set_property -dict {PACKAGE_PIN H21 IOSTANDARD LVCMOS33} [get_ports LCD_TOUCH_SCL]
set_property -dict {PACKAGE_PIN L19 IOSTANDARD LVCMOS33} [get_ports LCD_TOUCH_INT]
set_property -dict {PACKAGE_PIN G24 IOSTANDARD LVCMOS33} [get_ports LCD_TOUCH_RESET]

set_property PACKAGE_PIN H9 [get_ports {LCD_data_tri_io[0]}]
set_property PACKAGE_PIN K17 [get_ports {LCD_data_tri_io[1]}]
set_property PACKAGE_PIN J20 [get_ports {LCD_data_tri_io[2]}]
set_property PACKAGE_PIN M17 [get_ports {LCD_data_tri_io[3]}]
set_property PACKAGE_PIN L17 [get_ports {LCD_data_tri_io[4]}]
set_property PACKAGE_PIN L18 [get_ports {LCD_data_tri_io[5]}]
set_property PACKAGE_PIN L15 [get_ports {LCD_data_tri_io[6]}]
set_property PACKAGE_PIN M15 [get_ports {LCD_data_tri_io[7]}]
set_property PACKAGE_PIN M16 [get_ports {LCD_data_tri_io[8]}]
set_property PACKAGE_PIN L14 [get_ports {LCD_data_tri_io[9]}]
set_property PACKAGE_PIN M14 [get_ports {LCD_data_tri_io[10]}]
set_property PACKAGE_PIN F22 [get_ports {LCD_data_tri_io[11]}]
set_property PACKAGE_PIN G22 [get_ports {LCD_data_tri_io[12]}]
set_property PACKAGE_PIN G21 [get_ports {LCD_data_tri_io[13]}]
set_property PACKAGE_PIN H24 [get_ports {LCD_data_tri_io[14]}]
set_property PACKAGE_PIN J16 [get_ports {LCD_data_tri_io[15]}]

set_property PACKAGE_PIN J25 [get_ports LCD_nrst]
set_property PACKAGE_PIN H18 [get_ports LCD_csel]
set_property PACKAGE_PIN K8 [get_ports LCD_rd]
set_property PACKAGE_PIN K16 [get_ports LCD_rs]
set_property PACKAGE_PIN L8 [get_ports LCD_wr]

set_property PACKAGE_PIN J15 [get_ports LCD_lighton]

## PS/2 ports
#set_property -dict {PACKAGE_PIN AD1 IOSTANDARD LVCMOS33} [get_ports PS2_dat_tri_io]
#set_property -dict {PACKAGE_PIN Y2 IOSTANDARD LVCMOS33} [get_ports PS2_clk_tri_io]

# VGA interface (DAC with resistors)
set_property IOSTANDARD LVCMOS33 [get_ports VGA_*]

set_property PACKAGE_PIN U4 [get_ports {VGA_R[3]}]
set_property PACKAGE_PIN U2 [get_ports {VGA_R[2]}]
set_property PACKAGE_PIN T2 [get_ports {VGA_R[1]}]
set_property PACKAGE_PIN T3 [get_ports {VGA_R[0]}]

set_property PACKAGE_PIN R5 [get_ports {VGA_G[3]}]
set_property PACKAGE_PIN U1 [get_ports {VGA_G[2]}]
set_property PACKAGE_PIN R1 [get_ports {VGA_G[1]}]
set_property PACKAGE_PIN R2 [get_ports {VGA_G[0]}]

set_property PACKAGE_PIN P3 [get_ports {VGA_B[3]}]
set_property PACKAGE_PIN P1 [get_ports {VGA_B[2]}]
set_property PACKAGE_PIN N1 [get_ports {VGA_B[1]}]
set_property PACKAGE_PIN P5 [get_ports {VGA_B[0]}]

set_property PACKAGE_PIN U5 [get_ports VGA_HSYNC]
set_property PACKAGE_PIN U6 [get_ports VGA_VSYNC]


###############################################################################
# USB3500 UTMI+ PHY constraints
# Device: XC7A200T
# IO standard: 3.3V LVCMOS
###############################################################################

###############################################################################
# USB PHY clock from USB3500 CLKOUT
###############################################################################

set_property PACKAGE_PIN AA20 [get_ports FPGA_USB_PHY0_CLK]
set_property IOSTANDARD LVCMOS33 [get_ports FPGA_USB_PHY0_CLK]

# USB3500 UTMI CLKOUT is typically 60 MHz
# period = 16.666 ns
create_clock -period 16.666 -name usb_phy_clk [get_ports FPGA_USB_PHY0_CLK]

# USB3500 CLKOUT 与 FPGA PLL 生成的外设时钟没有固定相位关系；跨域逻辑由 OHCI 内部 CDC 处理。
set_clock_groups -quiet -asynchronous \
    -group [get_clocks -quiet usb_phy_clk] \
    -group [get_clocks -quiet clk_out2_clk_pll_33]

# USB3500 Table 4-6 timing relative to CLKOUT. DATA[7:0] is constrained in
# both directions because the board-level bus is bidirectional.
set usb_phy_input_ports [get_ports {
    FPGA_USB_PHY0_DATA[*]
    USB_TXREADY
    USB_RXVALID
    USB_RXACTIVE
    USB_RXERROR
    USB_ID_LINESTATE0
    USB_ID_LINESTATE1
}]
set_input_delay -clock [get_clocks usb_phy_clk] -min 2.000 $usb_phy_input_ports
set_input_delay -clock [get_clocks usb_phy_clk] -max 5.000 $usb_phy_input_ports

set usb_phy_output_ports [get_ports {
    FPGA_USB_PHY0_DATA[*]
    USB_TXVALID
    USB_XCVRSEL0
    USB_XCVRSEL1
    USB_TERMSEL
    USB_OPMODE0
    USB_OPMODE1
}]
set_output_delay -clock [get_clocks usb_phy_clk] -min 0.000 $usb_phy_output_ports
set_output_delay -clock [get_clocks usb_phy_clk] -max 5.000 $usb_phy_output_ports


###############################################################################
# USB3500 reset / suspend
###############################################################################

set_property PACKAGE_PIN AD23 [get_ports FPGA_USB_PHY0_RST]
set_property IOSTANDARD LVCMOS33 [get_ports FPGA_USB_PHY0_RST]

set_property PACKAGE_PIN AE20 [get_ports USB_SUSPENDN]
set_property IOSTANDARD LVCMOS33 [get_ports USB_SUSPENDN]


###############################################################################
# UTMI+ bidirectional DATA[7:0]
#
# 注意：
# 这组信号在 RTL 顶层应声明为 inout / Analog，
# 内部用 IOBUF 拆成 data_i / data_o / data_oe。
###############################################################################

set_property PACKAGE_PIN AA3 [get_ports {FPGA_USB_PHY0_DATA[0]}]
set_property PACKAGE_PIN AC3 [get_ports {FPGA_USB_PHY0_DATA[1]}]
set_property PACKAGE_PIN AE1 [get_ports {FPGA_USB_PHY0_DATA[2]}]
set_property PACKAGE_PIN AB4 [get_ports {FPGA_USB_PHY0_DATA[3]}]
set_property PACKAGE_PIN AD3 [get_ports {FPGA_USB_PHY0_DATA[4]}]
set_property PACKAGE_PIN AA4 [get_ports {FPGA_USB_PHY0_DATA[5]}]
set_property PACKAGE_PIN AC4 [get_ports {FPGA_USB_PHY0_DATA[6]}]
set_property PACKAGE_PIN AE2 [get_ports {FPGA_USB_PHY0_DATA[7]}]

set_property IOSTANDARD LVCMOS33 [get_ports {FPGA_USB_PHY0_DATA[*]}]


###############################################################################
# UTMI+ TX signals
###############################################################################

set_property PACKAGE_PIN AF23 [get_ports USB_TXVALID]
set_property IOSTANDARD LVCMOS33 [get_ports USB_TXVALID]

set_property PACKAGE_PIN AD21 [get_ports USB_TXREADY]
set_property IOSTANDARD LVCMOS33 [get_ports USB_TXREADY]


###############################################################################
# UTMI+ RX signals
###############################################################################

set_property PACKAGE_PIN AF22 [get_ports USB_RXVALID]
set_property IOSTANDARD LVCMOS33 [get_ports USB_RXVALID]

set_property PACKAGE_PIN AB5 [get_ports USB_RXACTIVE]
set_property IOSTANDARD LVCMOS33 [get_ports USB_RXACTIVE]

set_property PACKAGE_PIN AB2 [get_ports USB_RXERROR]
set_property IOSTANDARD LVCMOS33 [get_ports USB_RXERROR]


###############################################################################
# UTMI+ line state
###############################################################################

set_property PACKAGE_PIN AA5 [get_ports USB_ID_LINESTATE0]
set_property IOSTANDARD LVCMOS33 [get_ports USB_ID_LINESTATE0]

set_property PACKAGE_PIN AE5 [get_ports USB_ID_LINESTATE1]
set_property IOSTANDARD LVCMOS33 [get_ports USB_ID_LINESTATE1]


###############################################################################
# UTMI+ PHY mode control
###############################################################################

set_property PACKAGE_PIN AD20 [get_ports USB_XCVRSEL0]
set_property IOSTANDARD LVCMOS33 [get_ports USB_XCVRSEL0]

set_property PACKAGE_PIN AF4 [get_ports USB_XCVRSEL1]
set_property IOSTANDARD LVCMOS33 [get_ports USB_XCVRSEL1]

set_property PACKAGE_PIN AE21 [get_ports USB_TERMSEL]
set_property IOSTANDARD LVCMOS33 [get_ports USB_TERMSEL]

set_property PACKAGE_PIN AC6 [get_ports USB_OPMODE0]
set_property IOSTANDARD LVCMOS33 [get_ports USB_OPMODE0]

set_property PACKAGE_PIN AF5 [get_ports USB_OPMODE1]
set_property IOSTANDARD LVCMOS33 [get_ports USB_OPMODE1]


###############################################################################
# USB host / OTG / VBUS related signals
#
# 固定 Host 第一版可以不参与 OHCI 核心逻辑，
# 但建议保留为顶层端口或接入状态寄存器，避免悬空。
###############################################################################

set_property PACKAGE_PIN AC2 [get_ports USB_DPPD]
set_property IOSTANDARD LVCMOS33 [get_ports USB_DPPD]

set_property PACKAGE_PIN AC1 [get_ports USB_DMPD]
set_property IOSTANDARD LVCMOS33 [get_ports USB_DMPD]

set_property PACKAGE_PIN AB1 [get_ports USB_VBUSVLD]
set_property IOSTANDARD LVCMOS33 [get_ports USB_VBUSVLD]

set_property PACKAGE_PIN AA2 [get_ports USB_SESSVLD]
set_property IOSTANDARD LVCMOS33 [get_ports USB_SESSVLD]

set_property PACKAGE_PIN AF2 [get_ports USB_SESSEND]
set_property IOSTANDARD LVCMOS33 [get_ports USB_SESSEND]

set_property PACKAGE_PIN AD4 [get_ports USB_HOSTDISC]
set_property IOSTANDARD LVCMOS33 [get_ports USB_HOSTDISC]

set_property PACKAGE_PIN W4 [get_ports USB_ID_DIG]
set_property IOSTANDARD LVCMOS33 [get_ports USB_ID_DIG]

set_property PACKAGE_PIN AD5 [get_ports USB_ID_PULLUP]
set_property IOSTANDARD LVCMOS33 [get_ports USB_ID_PULLUP]

set_property PACKAGE_PIN AF3 [get_ports USB_CHRGVBUS]
set_property IOSTANDARD LVCMOS33 [get_ports USB_CHRGVBUS]

set_property PACKAGE_PIN AE3 [get_ports USB_DISCHRGVBUS]
set_property IOSTANDARD LVCMOS33 [get_ports USB_DISCHRGVBUS]
