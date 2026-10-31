#!/usr/bin/env bash
set -euo pipefail
script_dir="$(cd "$(dirname "$0")" && pwd)"
cd "$script_dir/.."
mode="${1:-window}"
case "$mode" in
    window|strict) ;;
    *) echo "Usage: $0 [window|strict]" >&2; exit 2 ;;
esac
"${MAVEN_CMD:-mvn}" -nsu -ntp -Dexec.mainClass=example.StreamingPolicyDemo "-Dexec.args=$mode" compile exec:java
