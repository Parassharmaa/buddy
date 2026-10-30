#!/usr/bin/env bash
set -euo pipefail
capture_dir="$(cd "$(dirname "$0")/.." && pwd)/captures"
mkdir -p "$capture_dir"
adb exec-out screencap -p > "$capture_dir/$(date -u +%Y%m%dT%H%M%SZ).png"
