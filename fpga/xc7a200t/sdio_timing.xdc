# LiteSD 3.3 V High Speed timing model at the maximum configured 50 MHz.
# Slower initialization/runtime divider settings have additional setup margin.
#
# SD Physical Layer High Speed card limits used here:
#   card output: tODLY <= 7.5 ns, tOH >= 1.5 ns
#   card input:  tISU  >= 6 ns,   tIH >= 2 ns
# Add 1 ns in either direction for the storage module/header interconnect skew.

set sd_clk_port [get_ports -quiet SD_CLK]
set sd_clk_source_pin [get_pins -quiet u_cpustc_soc/sdioController/raw/FDCE/C]
set sd_cmd_data_ports [get_ports -quiet {SD_CMD SD_DAT[*]}]
set sd_iddr_cells [get_cells -quiet -hierarchical -filter {
    REF_NAME == IDDR && NAME =~ */sdioController/raw/IDDR*
}]
set sd_iddr_d_pins [get_pins -quiet -of_objects $sd_iddr_cells -filter {REF_PIN_NAME == D}]
set sd_sample_clock [get_clocks -quiet clk]
set sd_output_registers [get_cells -quiet -hierarchical -filter {
    REF_NAME == FDCE && NAME =~ */sdioController/raw/FDCE*
}]
set sd_output_clock_pins [get_pins -quiet -of_objects $sd_output_registers -filter {REF_PIN_NAME == C}]

# The programmable LiteSD divider is constrained at its fastest supported mode.
# SD_CLK is a forwarded clock emitted by an FDCE in the 100 MHz SDIO domain.
create_generated_clock -name sd_clk_50m \
    -source $sd_clk_source_pin -divide_by 2 $sd_clk_port

# Card-to-host: High Speed tODLY/tOH are measured from the SD_CLK rising edge.
# IDDR Q1 captures the launched value on the intervening 100 MHz system edge.
set_input_delay -clock sd_clk_50m -max 8.500 $sd_cmd_data_ports
set_input_delay -clock sd_clk_50m -min 0.500 $sd_cmd_data_ports

# IDDR captures on every 100 MHz edge, but LiteSD consumes Q1 only after the
# selected divider phase. Check the next system-clock capture and preserve the
# original hold relationship.
set_multicycle_path 2 -setup -end -from $sd_cmd_data_ports \
    -through $sd_iddr_d_pins -to $sd_sample_clock
set_multicycle_path 1 -hold -end -from $sd_cmd_data_ports \
    -through $sd_iddr_d_pins -to $sd_sample_clock

# LiteX lowers SDRInput to an IDDR. Q1 feeds the design, while Q2 is an
# unconnected placeholder.
set_false_path -from $sd_cmd_data_ports \
    -through $sd_iddr_d_pins -fall_to $sd_sample_clock

# Host-to-card: card setup/hold requirements plus interconnect skew.
set_output_delay -clock sd_clk_50m -max  7.000 $sd_cmd_data_ports
set_output_delay -clock sd_clk_50m -min -3.000 $sd_cmd_data_ports

# The FDCE outputs update only on the SD_CLK falling phase. Static timing does
# not infer that functional clock-enable relationship, so move only the output
# hold launch edge by one 100 MHz system-clock cycle. Setup remains single-cycle.
set_multicycle_path 1 -hold -start \
    -from $sd_output_clock_pins -to $sd_cmd_data_ports
