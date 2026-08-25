set mode [lindex $argv 0]
set project_file [file normalize [lindex $argv 1]]
set compile_order_report [file normalize [lindex $argv 2]]
set constraint_order_report [file normalize [lindex $argv 3]]
set mappings [lrange $argv 4 end]

if {$mode ni {sync check}} {
    error "Unsupported mode: ${mode}"
}
if {![file isfile $project_file]} {
    error "Vivado project does not exist: ${project_file}"
}
if {[llength $mappings] == 0 || [llength $mappings] % 2 != 0} {
    error "Expected one or more source/destination path pairs"
}

proc find_exact_file {path} {
    set normalized_path [file normalize $path]
    set matches {}
    foreach project_file [get_files -quiet -all] {
        if {[file normalize [get_property NAME $project_file]] eq $normalized_path} {
            lappend matches $project_file
        }
    }
    return $matches
}

proc fileset_contains {fileset path} {
    set normalized_path [file normalize $path]
    foreach project_file [get_files -quiet -all -of_objects $fileset] {
        if {[file normalize [get_property NAME $project_file]] eq $normalized_path} {
            return 1
        }
    }
    return 0
}

proc relative_path {from_directory target_path} {
    set from_parts [file split [file normalize $from_directory]]
    set target_parts [file split [file normalize $target_path]]
    set shared_count 0
    set limit [expr {min([llength $from_parts], [llength $target_parts])}]

    while {$shared_count < $limit &&
           [lindex $from_parts $shared_count] eq [lindex $target_parts $shared_count]} {
        incr shared_count
    }

    set result {}
    for {set index $shared_count} {$index < [llength $from_parts]} {incr index} {
        lappend result ..
    }
    foreach part [lrange $target_parts $shared_count end] {
        lappend result $part
    }
    return [file join {*}$result]
}

proc expected_bd_gen_directory {bd_path project_file} {
    set project_name [file rootname [file tail $project_file]]
    set bd_name [file rootname [file tail $bd_path]]
    set target [file join [file dirname $project_file] "${project_name}.gen" \
        sources_1 bd $bd_name]
    return [relative_path [file dirname $bd_path] $target]
}

proc expected_standalone_ip_gen_directory {ip_path project_file} {
    set project_name [file rootname [file tail $project_file]]
    set ip_name [file rootname [file tail $ip_path]]
    set target [file join [file dirname $project_file] "${project_name}.gen" \
        sources_1 ip $ip_name]
    return [relative_path [file dirname $ip_path] $target]
}

proc replace_unique_text {content old_text new_text description} {
    set first [string first $old_text $content]
    if {$first < 0} {
        error "Could not find ${description} in Vivado IP configuration"
    }
    set second [string first $old_text $content [expr {$first + [string length $old_text]}]]
    if {$second >= 0} {
        error "Found duplicate ${description} in Vivado IP configuration"
    }
    return [string replace $content $first \
        [expr {$first + [string length $old_text] - 1}] $new_text]
}

proc normalize_standalone_ip_directories {ip_path project_file} {
    set expected [expected_standalone_ip_gen_directory $ip_path $project_file]
    set actual_generated [exec jq -er {.ip_inst.gen_directory} $ip_path]
    set actual_output [exec jq -er {.ip_inst.parameters.runtime_parameters.OUTPUTDIR[0].value} $ip_path]
    set actual_shared [exec jq -er {.ip_inst.parameters.runtime_parameters.SHAREDDIR[0].value} $ip_path]
    if {$actual_generated eq $expected && $actual_output eq $expected && $actual_shared eq "."} {
        return
    }

    set input [open $ip_path r]
    set content [read $input]
    close $input

    if {$actual_generated ne $expected} {
        set content [replace_unique_text $content \
            [format {"gen_directory": "%s"} $actual_generated] \
            [format {"gen_directory": "%s"} $expected] \
            "gen_directory field"]
    }
    if {$actual_output ne $expected} {
        set content [replace_unique_text $content \
            [format {"OUTPUTDIR": [ { "value": "%s" } ]} $actual_output] \
            [format {"OUTPUTDIR": [ { "value": "%s" } ]} $expected] \
            "OUTPUTDIR field"]
    }
    if {$actual_shared ne "."} {
        set content [replace_unique_text $content \
            [format {"SHAREDDIR": [ { "value": "%s" } ]} $actual_shared] \
            {"SHAREDDIR": [ { "value": "." } ]} \
            "SHAREDDIR field"]
    }

    set temporary_path "${ip_path}.sync.[pid]"
    set output [open $temporary_path w]
    puts -nonewline $output $content
    close $output
    if {[exec jq -er {.ip_inst.gen_directory} $temporary_path] ne $expected ||
        [exec jq -er {.ip_inst.parameters.runtime_parameters.OUTPUTDIR[0].value} $temporary_path] ne $expected ||
        [exec jq -er {.ip_inst.parameters.runtime_parameters.SHAREDDIR[0].value} $temporary_path] ne "."} {
        file delete -force $temporary_path
        error "Failed to normalize standalone IP generation metadata: ${ip_path}"
    }
    file rename -force $temporary_path $ip_path
    file attributes $ip_path -permissions 00644
}

proc require_standalone_ip_directories {ip_path project_file} {
    set expected [expected_standalone_ip_gen_directory $ip_path $project_file]
    set actual_generated [exec jq -er {.ip_inst.gen_directory} $ip_path]
    set actual_output [exec jq -er {.ip_inst.parameters.runtime_parameters.OUTPUTDIR[0].value} $ip_path]
    set actual_shared [exec jq -er {.ip_inst.parameters.runtime_parameters.SHAREDDIR[0].value} $ip_path]
    if {$actual_generated ne $expected || $actual_output ne $expected} {
        error "Standalone IP generation directory is stale: ${ip_path}"
    }
    if {$actual_shared ne "."} {
        error "Standalone IP shared directory is stale: ${ip_path}"
    }
    puts "STANDALONE_IP_OUTPUT=[file normalize [file join [file dirname $ip_path] $expected]]"
}

proc path_is_within {path directory} {
    set normalized_path [file normalize $path]
    set normalized_directory [file normalize $directory]
    return [expr {$normalized_path eq $normalized_directory ||
        [string first "${normalized_directory}/" $normalized_path] == 0}]
}

proc require_core_container_reference {container_path} {
    set ip_name [file rootname [file tail $container_path]]
    set ip [get_ips -quiet $ip_name]
    if {[llength $ip] != 1} {
        error "Expected one core-container IP named ${ip_name}, found [llength $ip]"
    }
    set registered_container [get_property IP_CORE_CONTAINER $ip]
    if {$registered_container eq "" ||
        [file normalize $registered_container] ne [file normalize $container_path]} {
        error "Core-container path is stale for ${ip_name}: ${registered_container}"
    }
    set extracted_directory [file join [file dirname $container_path] $ip_name]
    set ip_file [get_property IP_FILE $ip]
    if {$ip_file eq "" || ![path_is_within $ip_file $extracted_directory]} {
        error "Core-container IP file is outside its tracked directory: ${ip_file}"
    }
    puts "CORE_CONTAINER=[file normalize $registered_container]"
    puts "CORE_CONTAINER_IP_FILE=[file normalize $ip_file]"
}

proc normalize_bd_gen_directory {bd_path project_file} {
    set expected [expected_bd_gen_directory $bd_path $project_file]
    set actual [exec jq -er {.design.design_info.gen_directory} $bd_path]
    if {$actual eq $expected} {
        return
    }

    set temporary_path "${bd_path}.sync.[pid]"
    if {[catch {
        exec jq -e --arg gen_directory $expected \
            {.design.design_info.gen_directory = $gen_directory} \
            $bd_path > $temporary_path
    } result options]} {
        file delete -force $temporary_path
        return -options $options $result
    }
    file rename -force $temporary_path $bd_path
    file attributes $bd_path -permissions 00644
}

proc copy_tracked_source {source_path destination_path project_file} {
    if {![file isfile $source_path]} {
        error "Local Vivado source does not exist: ${source_path}"
    }
    file mkdir [file dirname $destination_path]
    file copy -force $source_path $destination_path
    file attributes $destination_path -permissions 00644
    if {[file extension $destination_path] eq ".bd"} {
        normalize_bd_gen_directory $destination_path $project_file
    }
}

proc copy_missing_bd_children {source_path destination_path} {
    set child_paths [split [exec jq -r {.. | .xci_path? // empty} $destination_path] "\n"]
    foreach child_path $child_paths {
        if {$child_path eq ""} {
            continue
        }
        set source_child [file join [file dirname $source_path] $child_path]
        set destination_child [file join [file dirname $destination_path] $child_path]
        if {[file isfile $destination_child]} {
            continue
        }
        if {![file isfile $source_child]} {
            error "Generated BD child configuration is missing from both source and destination: ${child_path}"
        }
        file mkdir [file dirname $destination_child]
        file copy -force $source_child $destination_child
        file attributes $destination_child -permissions 00644
        puts "BD_CHILD_SOURCE=[file normalize $destination_child]"
    }
}

proc require_bd_children {bd_path} {
    set child_paths [split [exec jq -r {.. | .xci_path? // empty} $bd_path] "\n"]
    foreach child_path $child_paths {
        if {$child_path ne "" && ![file isfile [file join [file dirname $bd_path] $child_path]]} {
            error "Generated BD child configuration is missing: ${child_path}"
        }
    }
}

proc bd_child_directories {bd_path project_file child_path} {
    set child_config [file normalize [file join [file dirname $bd_path] $child_path]]
    set project_name [file rootname [file tail $project_file]]
    set bd_name [file rootname [file tail $bd_path]]
    set child_name [file rootname [file tail $child_path]]
    set generated_target [file join [file dirname $project_file] "${project_name}.gen" \
        sources_1 bd $bd_name ip $child_name]
    set shared_target [file join [file dirname $bd_path] ipshared]
    return [list $child_config \
        [relative_path [file dirname $child_config] $generated_target] \
        [relative_path [file dirname $child_config] $shared_target]]
}

proc normalize_bd_child_directories {bd_path project_file} {
    set child_paths [split [exec jq -r {.. | .xci_path? // empty} $bd_path] "\n"]
    foreach child_path $child_paths {
        if {$child_path eq ""} {
            continue
        }
        lassign [bd_child_directories $bd_path $project_file $child_path] \
            child_config generated_directory shared_directory
        set actual_generated [exec jq -er {.ip_inst.gen_directory} $child_config]
        set actual_output [exec jq -er {.ip_inst.parameters.runtime_parameters.OUTPUTDIR[0].value} $child_config]
        set actual_shared [exec jq -er {.ip_inst.parameters.runtime_parameters.SHAREDDIR[0].value} $child_config]
        if {$actual_generated eq $generated_directory &&
            $actual_output eq $generated_directory &&
            $actual_shared eq $shared_directory} {
            continue
        }

        set temporary_path "${child_config}.sync.[pid]"
        if {[catch {
            exec jq -e --arg generated_directory $generated_directory \
                --arg shared_directory $shared_directory \
                {.ip_inst.gen_directory = $generated_directory |
                 .ip_inst.parameters.runtime_parameters.OUTPUTDIR[0].value = $generated_directory |
                 .ip_inst.parameters.runtime_parameters.SHAREDDIR[0].value = $shared_directory} \
                $child_config > $temporary_path
        } result options]} {
            file delete -force $temporary_path
            return -options $options $result
        }
        file rename -force $temporary_path $child_config
        file attributes $child_config -permissions 00644
    }
}

proc require_bd_child_directories {bd_path project_file} {
    set child_paths [split [exec jq -r {.. | .xci_path? // empty} $bd_path] "\n"]
    foreach child_path $child_paths {
        if {$child_path eq ""} {
            continue
        }
        lassign [bd_child_directories $bd_path $project_file $child_path] \
            child_config generated_directory shared_directory
        set actual_generated [exec jq -er {.ip_inst.gen_directory} $child_config]
        set actual_output [exec jq -er {.ip_inst.parameters.runtime_parameters.OUTPUTDIR[0].value} $child_config]
        set actual_shared [exec jq -er {.ip_inst.parameters.runtime_parameters.SHAREDDIR[0].value} $child_config]
        if {$actual_generated ne $generated_directory || $actual_output ne $generated_directory} {
            error "BD child generation directory is stale: ${child_config}"
        }
        if {$actual_shared ne $shared_directory} {
            error "BD child shared directory is stale: ${child_config}"
        }
    }
}

proc bd_child_ip_names {bd_path pattern} {
    set names {}
    set child_paths [split [exec jq -r {.. | .xci_path? // empty} $bd_path] "\n"]
    foreach child_path $child_paths {
        if {$child_path eq ""} {
            continue
        }
        set child_name [file rootname [file tail $child_path]]
        if {[string match $pattern $child_name]} {
            lappend names $child_name
        }
    }
    return [lsort -unique $names]
}

proc require_bd_implementation_constraints {bd_path project_file} {
    set auto_cc_names [bd_child_ip_names $bd_path "*_auto_cc_*"]
    if {[llength $auto_cc_names] == 0} {
        error "BD does not contain AXI clock-converter child IP: ${bd_path}"
    }

    set generated_relative [expected_bd_gen_directory $bd_path $project_file]
    set generated_directory [file normalize [file join [file dirname $bd_path] $generated_relative]]
    foreach ip_name $auto_cc_names {
        if {[llength [get_filesets -quiet $ip_name]] != 1} {
            error "Missing child-IP fileset: ${ip_name}"
        }
        if {[llength [get_runs -quiet "${ip_name}_synth_1"]] != 1} {
            error "Missing child-IP OOC synthesis run: ${ip_name}_synth_1"
        }

        set clocks_xdc [file join $generated_directory ip $ip_name "${ip_name}_clocks.xdc"]
        if {![file isfile $clocks_xdc]} {
            error "Generated child-IP clock constraint is missing: ${clocks_xdc}"
        }
        set constraint_files [get_files -quiet -all -filter \
            "NAME =~ \"*/${ip_name}_clocks.xdc\""]
        if {[llength $constraint_files] != 1} {
            error "Expected one registered implementation constraint for ${ip_name}, found [llength $constraint_files]"
        }
        set registered_path [file normalize [get_property NAME $constraint_files]]
        if {$registered_path ne [file normalize $clocks_xdc]} {
            error "Child-IP clock constraint is outside the project generation directory: ${registered_path}"
        }
        set used_in [get_property USED_IN $constraint_files]
        if {[lsearch -exact $used_in implementation] < 0} {
            error "Child-IP clock constraint is not used in implementation: ${clocks_xdc}"
        }
        puts "IMPLEMENTATION_XDC=${clocks_xdc}"
    }
}

proc require_tracked_reference {source_path destination_path} {
    set source_files [find_exact_file $source_path]
    set destination_files [find_exact_file $destination_path]

    if {[llength $source_files] != 0} {
        error "Project still references local Vivado source: ${source_path}"
    }
    if {[llength $destination_files] != 1} {
        error "Expected one tracked Vivado source reference for ${destination_path}, found [llength $destination_files]"
    }
}

open_project $project_file

if {$mode eq "sync"} {
    foreach {source_path destination_path} $mappings {
        set source_files [find_exact_file $source_path]
        set destination_files [find_exact_file $destination_path]

        if {[llength $source_files] > 1 || [llength $destination_files] > 1} {
            error "Duplicate project references for ${source_path} or ${destination_path}"
        }
        if {[llength $source_files] == 0 && [llength $destination_files] == 0} {
            error "Project references neither ${source_path} nor ${destination_path}"
        }

        if {[llength $source_files] == 1} {
            copy_tracked_source $source_path $destination_path $project_file
            remove_files $source_files
        }
        if {[file extension $destination_path] eq ".xci"} {
            normalize_standalone_ip_directories $destination_path $project_file
        }
        if {[llength $destination_files] == 0} {
            add_files -norecurse -fileset sources_1 $destination_path
        }
        if {[file extension $destination_path] in {.bd .xci}} {
            if {[file extension $destination_path] eq ".bd"} {
                copy_missing_bd_children $source_path $destination_path
                normalize_bd_gen_directory $destination_path $project_file
                normalize_bd_child_directories $destination_path $project_file
            }
            # Re-register moved sources so Vivado drops generation paths cached
            # from their former project-local locations.
            set destination_files [find_exact_file $destination_path]
            if {[llength $destination_files] == 1} {
                remove_files $destination_files
                add_files -norecurse -fileset sources_1 $destination_path
            }
        }
        puts "PROJECT_SOURCE=[file normalize $destination_path]"
    }

    update_compile_order -fileset sources_1
    close_project
    open_project $project_file

    foreach {source_path destination_path} $mappings {
        set extension [file extension $destination_path]
        if {$extension eq ".bd"} {
            open_bd_design $destination_path
            validate_bd_design
            set bd_file [get_files -quiet -all $destination_path]
            reset_target all $bd_file
            generate_target all $bd_file
            create_ip_run $bd_file
            close_bd_design [current_bd_design]
        } elseif {$extension eq ".xci"} {
            set ip_name [file rootname [file tail $destination_path]]
            set ip [get_ips -quiet $ip_name]
            if {[llength $ip] != 1} {
                error "Expected one standalone IP named ${ip_name}, found [llength $ip]"
            }
            reset_target all $ip
            generate_target all $ip
            if {[llength [get_runs -quiet "${ip_name}_synth_1"]] == 0} {
                create_ip_run $ip
            }
        }
    }
    foreach ip_name {clk_wiz_1} {
        set ip [get_ips -quiet $ip_name]
        if {[llength $ip] == 1 && [llength [get_runs -quiet "${ip_name}_synth_1"]] == 0} {
            create_ip_run $ip
        }
    }
    foreach {source_path destination_path} $mappings {
        set ip_name [file rootname [file tail $destination_path]]
        if {$ip_name eq "clk_wiz_1"} {
            set fileset [get_filesets $ip_name]
            if {![fileset_contains $fileset $destination_path]} {
                move_files -fileset $fileset [find_exact_file $destination_path]
            }
        }
    }
    foreach fileset [concat \
        [get_filesets -quiet blk_mem_gen_0] \
        [get_filesets -quiet clk_wiz_1] \
        [get_filesets -quiet {axi_interconnect_0_*}]] {
        set_property verilog_define {} $fileset
    }
    update_compile_order -fileset sources_1
    close_project
    open_project $project_file
}

foreach {source_path destination_path} $mappings {
    require_tracked_reference $source_path $destination_path
    if {[file extension $destination_path] eq ".bd"} {
        set expected [expected_bd_gen_directory $destination_path $project_file]
        set actual [exec jq -er {.design.design_info.gen_directory} $destination_path]
        if {$actual ne $expected} {
            error "BD generation directory is ${actual}, expected ${expected}"
        }
        require_bd_children $destination_path
        require_bd_child_directories $destination_path $project_file
        require_bd_implementation_constraints $destination_path $project_file
    } elseif {[file extension $destination_path] eq ".xci"} {
        require_standalone_ip_directories $destination_path $project_file
    } elseif {[file extension $destination_path] eq ".xcix"} {
        require_core_container_reference $destination_path
    }
}

report_compile_order -of_objects [get_filesets sources_1] -used_in synthesis \
    -sources -file $compile_order_report
if {![file isfile $compile_order_report] || [file size $compile_order_report] == 0} {
    error "Vivado did not produce a synthesis compile-order report"
}
report_compile_order -constraints -used_in implementation -file $constraint_order_report
if {![file isfile $constraint_order_report] || [file size $constraint_order_report] == 0} {
    error "Vivado did not produce an implementation constraint-order report"
}

set locked_ips [get_ips -quiet -filter {IS_LOCKED == 1}]
if {[llength $locked_ips] != 0} {
    error "Locked Vivado IP: ${locked_ips}"
}
foreach ip_name {clk_wiz_1 blk_mem_gen_0} {
    if {[llength [get_runs -quiet "${ip_name}_synth_1"]] != 1} {
        error "Missing OOC synthesis run: ${ip_name}_synth_1"
    }
}
foreach fileset [concat \
    [get_filesets -quiet blk_mem_gen_0] \
    [get_filesets -quiet clk_wiz_1] \
    [get_filesets -quiet {axi_interconnect_0_*}]] {
    if {[get_property verilog_define $fileset] ne ""} {
        error "OOC fileset has unexpected Verilog defines: ${fileset}"
    }
}
puts "VIVADO_SOURCE_CHECK=PASS"
close_project
