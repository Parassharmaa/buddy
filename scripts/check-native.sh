#!/usr/bin/env bash
set -euo pipefail
project_dir="$(cd "$(dirname "$0")/.." && pwd)"
cmake_bin="${CMAKE:-cmake}"
if ! command -v "$cmake_bin" >/dev/null; then cmake_bin="${ANDROID_HOME:?}/cmake/4.1.2/bin/cmake"; fi
"$cmake_bin" -S "$project_dir/pairing-native/src/main/cpp" -B "$project_dir/pairing-native/build/host" -DCMAKE_BUILD_TYPE=Debug
"$cmake_bin" --build "$project_dir/pairing-native/build/host" --target pairing_tests buddy_pairing -j 4
"$project_dir/pairing-native/build/host/pairing_tests"
