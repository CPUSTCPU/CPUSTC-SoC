set script_dir [file normalize [file dirname [info script]]]
set run_dir [file join $script_dir CPUSTCPUSoc.runs impl_1]
set checkpoint [file join $run_dir soc_top_routed.dcp]
set bitstream [file join $run_dir soc_top.bit]
set report_dir [file join $script_dir reports]

if {![file isfile $checkpoint]} {
    error "Routed checkpoint does not exist: ${checkpoint}"
}
file mkdir $report_dir

open_checkpoint $checkpoint
report_drc -file [file join $report_dir drc_routed.rpt]
report_timing_summary -delay_type min_max -max_paths 20 -report_unconstrained \
    -check_timing_verbose -file [file join $report_dir timing_summary_routed.rpt]

set drc_errors [get_drc_violations -quiet -filter {SEVERITY == Error}]
set drc_critical [get_drc_violations -quiet -filter {SEVERITY == {Critical Warning}}]
set setup_path [get_timing_paths -quiet -delay_type max -max_paths 1]
set hold_path [get_timing_paths -quiet -delay_type min -max_paths 1]
if {[llength $drc_errors] > 0 || [llength $drc_critical] > 0} {
    error "Routed DRC gate failed: errors=[llength $drc_errors], critical_warnings=[llength $drc_critical]"
}
if {[llength $setup_path] == 0 || [llength $hold_path] == 0} {
    error "Timing gate could not obtain setup and hold paths"
}

set setup_slack [get_property SLACK $setup_path]
set hold_slack [get_property SLACK $hold_path]
if {$setup_slack < 0.0 || $hold_slack < 0.0} {
    error "Timing gate failed: setup_slack=${setup_slack}, hold_slack=${hold_slack}"
}

write_bitstream -force $bitstream
if {![file isfile $bitstream]} {
    error "Bitstream was not generated: ${bitstream}"
}

puts "BITSTREAM=${bitstream}"
puts "SETUP_SLACK=${setup_slack}"
puts "HOLD_SLACK=${hold_slack}"
puts "DRC_ERRORS=[llength $drc_errors]"
puts "DRC_CRITICAL_WARNINGS=[llength $drc_critical]"
close_project
