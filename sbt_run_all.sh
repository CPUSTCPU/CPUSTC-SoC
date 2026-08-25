#!/usr/bin/env bash

set -euo pipefail

repo_root=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
projects=(
  "."
  "IP/spinal/interrupt"
  "IP/spinal/lcd"
  "IP/spinal/usb"
)

command -v sbt >/dev/null 2>&1 || {
  printf '未找到 sbt，请先将其加入 PATH。\n' >&2
  exit 1
}

for project in "${projects[@]}"; do
  project_dir="${repo_root}/${project}"
  if [[ ! -f "${project_dir}/build.sbt" ]]; then
    printf '未找到 SBT 工程：%s\n' "${project_dir}" >&2
    exit 1
  fi

  printf '执行 sbt run：%s\n' "${project_dir}"
  (
    cd -- "${project_dir}"
    sbt run
  )
done

printf '全部 SBT 工程执行完成。\n'
