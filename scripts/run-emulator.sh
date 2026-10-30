#!/usr/bin/env bash
set -euo pipefail
if [[ "${GITHUB_ACTIONS:-}" != true ]]; then
    echo 'This launcher requires a disposable GitHub Actions worker.' >&2
    exit 1
fi
buddy_sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:?}}"
export ANDROID_AVD_HOME="${RUNNER_TEMP:?}/buddy-avd"
export ANDROID_SERIAL=emulator-5554
mkdir -p "$ANDROID_AVD_HOME"
adb start-server
if [[ "$(adb devices)" == *"$ANDROID_SERIAL"* ]]; then
    echo 'The emulator console port is already in use.' >&2
    exit 1
fi
"$buddy_sdk/emulator/emulator" -version
"$buddy_sdk/emulator/emulator" -accel-check
printf 'no\n' | "$buddy_sdk/cmdline-tools/latest/bin/avdmanager" create avd --force --name buddy-ci \
    --package "system-images;android-${BUDDY_IMAGE_API:?};${BUDDY_IMAGE_TARGET:?};x86_64" --device pixel_7
buddy_emulator_log="$RUNNER_TEMP/buddy-emulator.log"
"$buddy_sdk/emulator/emulator" -avd buddy-ci -port 5554 -accel on -no-window -no-audio \
    -no-boot-anim -no-snapshot -gpu swiftshader -memory 4096 -cores 2 > "$buddy_emulator_log" 2>&1 &
buddy_emulator_pid=$!
cleanup() {
    local result=$?
    trap - EXIT
    timeout 5 adb emu kill >/dev/null 2>&1 || true
    for cleanup_attempt in 1 2 3 4 5; do
        if ! kill -0 "$buddy_emulator_pid" 2>/dev/null; then break; fi
        sleep 1
    done
    kill -TERM "$buddy_emulator_pid" 2>/dev/null || true
    sleep 1
    kill -KILL "$buddy_emulator_pid" 2>/dev/null || true
    wait "$buddy_emulator_pid" 2>/dev/null || true
    exit "$result"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
buddy_boot_deadline=$((SECONDS + 300))
buddy_booted=false
while (( SECONDS < buddy_boot_deadline )); do
    if ! kill -0 "$buddy_emulator_pid" 2>/dev/null; then
        echo 'Emulator exited before boot.' >&2
        tail -30 "$buddy_emulator_log"
        exit 1
    fi
    if [[ "$(timeout 5 adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == 1 ]]; then
        buddy_booted=true
        break
    fi
    sleep 2
done
if [[ "$buddy_booted" != true ]]; then
    echo 'Emulator did not boot within five minutes.' >&2
    tail -30 "$buddy_emulator_log"
    exit 1
fi
# This script performs no unlock/input operations. check-device waits for stable services first.
bash scripts/check-device.sh
