#!/usr/bin/env bash
set -euo pipefail
script_dir="$(cd "$(dirname "$0")" && pwd)"
cd "$script_dir/.."
"${MAVEN_CMD:-mvn}" -nsu -ntp -Dexec.mainClass=example.PlainStreamingDemo compile exec:java
