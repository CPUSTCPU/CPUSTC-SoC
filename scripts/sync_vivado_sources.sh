#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
repo_root="$(git -C "$script_dir" rev-parse --show-toplevel)"
project_rel="fpga/xc7a200t/CPUSTCPUSoc/CPUSTCPUSoc.xpr"
project="$repo_root/$project_rel"
tcl_script="$script_dir/sync_vivado_sources.tcl"
vivado_bin="${VIVADO:-vivado}"

sources=(
  "fpga/xc7a200t/CPUSTCPUSoc/CPUSTCPUSoc.srcs/sources_1/ip/blk_mem_gen_0_1/blk_mem_gen_0.xci"
  "fpga/xc7a200t/CPUSTCPUSoc/CPUSTCPUSoc.srcs/sources_1/bd/axi_interconnect_0/axi_interconnect_0.bd"
  "fpga/xc7a200t/CPUSTCPUSoc/CPUSTCPUSoc.srcs/sources_1/ip/clk_wiz_1/clk_wiz_1.xci"
)

destinations=(
  "IP/xilinx_ip/blk_mem_gen_0/blk_mem_gen_0.xci"
  "IP/xilinx_ip/axi_interconnect_0/axi_interconnect_0.bd"
  "IP/xilinx_ip/clk_wiz_1/clk_wiz_1.xci"
)

usage() {
  cat <<'EOF'
Usage: scripts/sync_vivado_sources.sh [--check] [--stage]

Synchronize the active project-local Vivado source configurations into
IP/xilinx_ip and switch the project file set to the tracked copies.

  --check  Verify file contents, Git tracking, Vivado references, project-local
           IP generation paths, compile order, and implementation constraints
           without copying or staging files.
  --stage  Synchronize and git add only the copied configurations and XPR.

The script never creates a Git commit.
EOF
}

mode="sync"
stage=0
while (($# > 0)); do
  case "$1" in
    --check)
      mode="check"
      ;;
    --stage)
      stage=1
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      printf 'Unknown argument: %s\n' "$1" >&2
      usage >&2
      exit 2
      ;;
  esac
  shift
done

if [[ "$mode" == "check" && "$stage" -eq 1 ]]; then
  printf '%s\n' '--check and --stage cannot be used together.' >&2
  exit 2
fi

if [[ ! -f "$project" ]]; then
  printf 'Vivado project does not exist: %s\n' "$project" >&2
  exit 1
fi
if [[ ! -f "$tcl_script" ]]; then
  printf 'Vivado helper does not exist: %s\n' "$tcl_script" >&2
  exit 1
fi
if ! command -v "$vivado_bin" >/dev/null 2>&1; then
  printf 'Vivado executable was not found: %s\n' "$vivado_bin" >&2
  exit 1
fi
if ! command -v jq >/dev/null 2>&1; then
  printf '%s\n' 'jq is required to normalize Vivado block-design metadata.' >&2
  exit 1
fi

for index in "${!sources[@]}"; do
  source_path="$repo_root/${sources[$index]}"
  destination_path="$repo_root/${destinations[$index]}"

  if [[ "$mode" == "sync" && ! -f "$source_path" && ! -f "$destination_path" ]]; then
    printf 'Neither local nor tracked Vivado source exists: %s\n' "${destinations[$index]}" >&2
    exit 1
  fi
  if [[ "$mode" == "sync" ]]; then
    continue
  fi
  if [[ ! -f "$destination_path" ]]; then
    printf 'Tracked Vivado source does not exist: %s\n' "${destinations[$index]}" >&2
    exit 1
  fi
  if git -C "$repo_root" check-ignore -q -- "${destinations[$index]}"; then
    printf 'Vivado source is ignored by Git: %s\n' "${destinations[$index]}" >&2
    exit 1
  fi
  if ! git -C "$repo_root" ls-files --error-unmatch -- "${destinations[$index]}" >/dev/null 2>&1; then
    printf 'Vivado source is not in the Git index: %s\n' "${destinations[$index]}" >&2
    exit 1
  fi
done

temp_dir="$(mktemp -d "${TMPDIR:-/tmp}/cpustc-vivado-source-sync.XXXXXX")"
trap 'rm -rf -- "$temp_dir"' EXIT
vivado_log="$temp_dir/vivado.log"
compile_order_report="$temp_dir/compile_order.rpt"
constraint_order_report="$temp_dir/implementation_constraints.rpt"
tcl_args=("$mode" "$project" "$compile_order_report" "$constraint_order_report")
for index in "${!sources[@]}"; do
  tcl_args+=("$repo_root/${sources[$index]}" "$repo_root/${destinations[$index]}")
done

if ! "$vivado_bin" -mode batch -nojournal -log "$vivado_log" \
  -source "$tcl_script" -tclargs "${tcl_args[@]}"; then
  grep -En '^(ERROR|CRITICAL WARNING):' "$vivado_log" >&2 || true
  printf 'Vivado source synchronization failed. Full log: %s\n' "$vivado_log" >&2
  trap - EXIT
  exit 1
fi

if grep -Eq '^(ERROR|CRITICAL WARNING):' "$vivado_log"; then
  grep -En '^(ERROR|CRITICAL WARNING):' "$vivado_log" >&2
  printf 'Vivado reported an error or critical warning. Full log: %s\n' "$vivado_log" >&2
  trap - EXIT
  exit 1
fi

if [[ "$stage" -eq 1 ]]; then
  git -C "$repo_root" add -- "$project_rel" "${destinations[@]}"
  printf 'STAGED %s and %d Vivado source configurations\n' \
    "$project_rel" "${#destinations[@]}"
fi

if [[ "$mode" == "check" ]]; then
  printf 'CHECK PASS: tracked Vivado sources and project references are consistent.\n'
else
  printf 'SYNC PASS: Vivado sources and project references were updated.\n'
fi
