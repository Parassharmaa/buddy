#!/usr/bin/env bash
set -euo pipefail
if [[ "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" != 1 ]]; then
    echo 'Device checks require a disposable Android emulator.' >&2
    exit 1
fi
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard
adb shell settings put system screen_off_timeout 1800000
adb shell settings put global development_settings_enabled 1
device_gradle_args=(--no-daemon --no-parallel --continue
    -Pandroid.testInstrumentationRunnerArguments.buddyPairingFixture=true
    -Pandroid.testInstrumentationRunnerArguments.timeout_msec=60000)
device_result=0
./gradlew "${device_gradle_args[@]}" :pairing-native:connectedDebugAndroidTest :storage:connectedDebugAndroidTest :fixture:connectedDebugAndroidTest || device_result=1
# AGP owns installation/cleanup of each instrumentation target. Install the final
# rebuilt fixture after its own suite, before the independent Buddy suite.
python3 scripts/check-apk-signatures.py
adb install -r fixture/build/outputs/apk/debug/fixture-debug.apk
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard
./gradlew "${device_gradle_args[@]}" :app:connectedDebugAndroidTest || device_result=1
exit "$device_result"
