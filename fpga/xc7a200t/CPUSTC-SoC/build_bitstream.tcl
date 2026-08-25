set script_dir [file normalize [file dirname [info script]]]
set project_name "CPUSTC-SoC"
set project_xpr [file join $script_dir "${project_name}.xpr"]
set report_dir [file join $script_dir reports]

if {![file isfile $project_xpr]} {
    error "Project does not exist: ${project_xpr}"
}
file mkdir $report_dir

proc require_completed_run {run_name} {
    set run [get_runs $run_name]
    set progress [get_property PROGRESS $run]
    set status [get_property STATUS $run]
    if {$progress ne "100%" || ![string match "*Complete*" $status]} {
        error "Run ${run_name} failed: progress=${progress}, status=${status}"
    }
}

proc add_missing_files {paths} {
    foreach path $paths {
        set normalized [file normalize $path]
        if {[llength [get_files -quiet $normalized]] == 0} {
            add_files -norecurse $normalized
        }
    }
}

open_project $project_xpr
add_missing_files [glob -nocomplain [file join $script_dir .. .. .. IP myCPU *.sv]]
set axi_bd_dir [file join $script_dir CPUSTC-SoC.gen sources_1 bd axi_interconnect_0]
add_missing_files [glob -nocomplain [file join $axi_bd_dir hdl axi_interconnect_0_wrapper.v]]
report_ip_status -file [file join $report_dir ip_status.rpt]

set locked_ips [get_ips -quiet -filter {IS_LOCKED == 1}]
if {[llength $locked_ips] > 0} {
    error "Locked IP detected: ${locked_ips}"
}

reset_run synth_1
set_property incremental_checkpoint "" [get_runs synth_1]
launch_runs synth_1 -jobs 8
wait_on_run synth_1
require_completed_run synth_1

launch_runs impl_1 -to_step write_bitstream -jobs 8
wait_on_run impl_1
require_completed_run impl_1

open_run impl_1
report_drc -file [file join $report_dir drc_routed.rpt]
report_methodology -file [file join $report_dir methodology_routed.rpt]
report_timing_summary -delay_type min_max -max_paths 20 -report_unconstrained \
    -check_timing_verbose -file [file join $report_dir timing_summary_routed.rpt]
report_clock_interaction -delay_type min_max \
    -file [file join $report_dir clock_interaction_routed.rpt]
report_cdc -details -file [file join $report_dir cdc_routed.rpt]
report_utilization -file [file join $report_dir utilization_routed.rpt]

set drc_errors [get_drc_violations -quiet -filter {SEVERITY == Error}]
set drc_critical [get_drc_violations -quiet -filter {SEVERITY == {Critical Warning}}]
if {[llength $drc_errors] > 0 || [llength $drc_critical] > 0} {
    error "Routed DRC gate failed: errors=[llength $drc_errors], critical_warnings=[llength $drc_critical]"
}

set setup_path [get_timing_paths -quiet -delay_type max -max_paths 1]
set hold_path [get_timing_paths -quiet -delay_type min -max_paths 1]
if {[llength $setup_path] == 0 || [llength $hold_path] == 0} {
    error "Timing gate could not obtain setup and hold paths"
}

set setup_slack [get_property SLACK $setup_path]
set hold_slack [get_property SLACK $hold_path]
if {$setup_slack < 0.0 || $hold_slack < 0.0} {
    error "Timing gate failed: setup_slack=${setup_slack}, hold_slack=${hold_slack}"
}

set bitstream [file join $script_dir "${project_name}.runs" impl_1 soc_top.bit]
if {![file isfile $bitstream]} {
    error "Bitstream was not generated: ${bitstream}"
}

puts "BITSTREAM=${bitstream}"
puts "SETUP_SLACK=${setup_slack}"
puts "HOLD_SLACK=${hold_slack}"
puts "DRC_ERRORS=[llength $drc_errors]"
puts "DRC_CRITICAL_WARNINGS=[llength $drc_critical]"
close_project
