#!/usr/bin/env bash
set -euo pipefail
script_dir="$(cd "$(dirname "$0")" && pwd)"
cd "$script_dir/.."
# -nsu 防止相同 SNAPSHOT 坐标被远程版本覆盖；先执行 install-local.sh。
"${MAVEN_CMD:-mvn}" -nsu -ntp compile exec:java
