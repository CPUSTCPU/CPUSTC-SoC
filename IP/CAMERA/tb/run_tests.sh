#!/usr/bin/env bash
set -uo pipefail

tb_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
repo_dir=$(cd -- "$tb_dir/../../.." && pwd)
workspace_dir=$(cd -- "$repo_dir/.." && pwd)
timestamp=$(date +%Y%m%dT%H%M%S%z)
log_dir=${1:-"$workspace_dir/logs/camera_rtl_sim_$timestamp"}
build_dir="$log_dir/obj_dir"
summary="$log_dir/summary.tsv"

mkdir -p -- "$log_dir"
mkdir -p -- "$log_dir/source_snapshot/rtl" "$log_dir/source_snapshot/tb"
cp -a -- "$repo_dir/IP/CAMERA/rtl/"*.v "$log_dir/source_snapshot/rtl/"
cp -a -- "$tb_dir/camera_tb.sv" "$tb_dir/Makefile" "$tb_dir/run_tests.sh" \
    "$log_dir/source_snapshot/tb/"

{
    echo "timestamp=$timestamp"
    echo "repo=$repo_dir"
    echo "commit=$(git -C "$repo_dir" rev-parse HEAD)"
    echo "verilator=$(verilator --version)"
    echo "build_command=make -C $tb_dir BUILD_DIR=$build_dir all"
    echo "run_command=$build_dir/Vcamera_tb +TEST=<case>"
    echo "git_status_begin"
    git -C "$repo_dir" status --short --branch
    echo "git_status_end"
    echo "source_sha256_begin"
    sha256sum "$repo_dir/IP/CAMERA/rtl/"*.v "$tb_dir/camera_tb.sv" \
        "$tb_dir/Makefile" "$tb_dir/run_tests.sh"
    echo "source_sha256_end"
} >"$log_dir/manifest.txt"

set +e
make -C "$tb_dir" BUILD_DIR="$build_dir" all >"$log_dir/build.log" 2>&1
build_rc=$?
set -e
echo "build_exit_code=$build_rc" >>"$log_dir/manifest.txt"
if (( build_rc != 0 )); then
    printf 'build\tFAIL\t%d\n' "$build_rc" >"$summary"
    printf 'Camera RTL build failed; raw log: %s\n' "$log_dir/build.log" >&2
    exit "$build_rc"
fi

printf 'case\texpectation\tresult\texit_code\n' >"$summary"
pass_cases=(full_frame bresp_error fifo_overflow irq no_descriptor completion_full \
    stop abort reset short_line long_line short_frame long_frame)
xfail_cases=()
unexpected=0

run_case() {
    local case_name=$1
    local expectation=$2
    local case_log="$log_dir/$case_name.log"
    local case_rc
    local result

    set +e
    "$build_dir/Vcamera_tb" "+TEST=$case_name" >"$case_log" 2>&1
    case_rc=$?
    set -e

    if [[ $expectation == PASS ]]; then
        if (( case_rc == 0 )); then
            result=PASS
        else
            result=FAIL
            unexpected=$((unexpected + 1))
        fi
    else
        if (( case_rc == 0 )); then
            result=XPASS
            unexpected=$((unexpected + 1))
        else
            result=XFAIL
        fi
    fi
    printf '%s\t%s\t%s\t%d\n' "$case_name" "$expectation" "$result" "$case_rc" \
        | tee -a "$summary"
}

for case_name in "${pass_cases[@]}"; do
    run_case "$case_name" PASS
done
for case_name in "${xfail_cases[@]}"; do
    run_case "$case_name" XFAIL
done

echo "unexpected_results=$unexpected" >>"$log_dir/manifest.txt"
printf 'Raw simulation logs: %s\n' "$log_dir"
exit "$unexpected"
