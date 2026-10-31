#!/usr/bin/env bash
set -euo pipefail
wait_for_framework() {
    local framework_deadline=$((SECONDS + 180))
    local stable_since=0 last_server_pid='' server_pid framework_service services_ready response
    echo 'Waiting for stable Android framework services.'
    while (( SECONDS < framework_deadline )); do
        services_ready=true
        for framework_service in input window activity package settings; do
            if ! response="$(timeout 5 adb shell service check "$framework_service" 2>/dev/null)" ||
                [[ "$response" != *': found'* ]]; then
                services_ready=false
                break
            fi
        done
        server_pid="$(timeout 5 adb shell pidof system_server 2>/dev/null | tr -d '\r')" || server_pid=''
        if [[ "$services_ready" == true && "$server_pid" =~ ^[0-9]+$ ]]; then
            if [[ "$server_pid" != "$last_server_pid" || "$stable_since" == 0 ]]; then
                if [[ -n "$last_server_pid" && "$server_pid" != "$last_server_pid" ]]; then
                    echo 'Android framework process restarted during readiness checks.'
                fi
                last_server_pid="$server_pid"
                stable_since=$SECONDS
            fi
            if (( SECONDS - stable_since >= 30 )); then
                echo 'Android framework services and process are stable.'
                return 0
            fi
        else
            stable_since=0
        fi
        sleep 2
    done
    echo 'Android framework services did not become stable.' >&2
    return 1
}
prepare_device() {
    local preparation_attempt
    for preparation_attempt in 1 2 3; do
        printf 'Preparing emulator (attempt %s).\n' "$preparation_attempt"
        if timeout 10 adb shell input keyevent KEYCODE_WAKEUP &&
            timeout 10 adb shell wm dismiss-keyguard &&
            timeout 10 adb shell settings put system screen_off_timeout 1800000 &&
            timeout 10 adb shell settings put global window_animation_scale 0 &&
            timeout 10 adb shell settings put global transition_animation_scale 0 &&
            timeout 10 adb shell settings put global animator_duration_scale 0 &&
            timeout 10 adb shell settings put global development_settings_enabled 1; then
            return 0
        fi
        echo 'Framework restarted during preparation; waiting again.'
        wait_for_framework || return 1
    done
    return 1
}
wait_for_framework
if [[ "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" != 1 ]]; then
    echo 'Device checks require a disposable Android emulator.' >&2
    exit 1
fi
device_page_size="$(adb shell getconf PAGE_SIZE | tr -d '\r')"
printf 'Device page size: %s\n' "$device_page_size"
if [[ -n "${BUDDY_EXPECTED_PAGE_SIZE:-}" && "$device_page_size" != "$BUDDY_EXPECTED_PAGE_SIZE" ]]; then
    echo 'Emulator page size differs from its required test profile.' >&2
    exit 1
fi
prepare_device
device_gradle_args=(--no-daemon --no-parallel --max-workers=2 --continue
    -Pkotlin.compiler.execution.strategy=in-process
    -Pandroid.testInstrumentationRunnerArguments.buddyPairingFixture=true
    -Pandroid.testInstrumentationRunnerArguments.timeout_msec=60000)
device_result=0
if ! ./gradlew "${device_gradle_args[@]}" :pairing-native:connectedDebugAndroidTest :adb:connectedDebugAndroidTest :storage:connectedDebugAndroidTest :fixture:connectedDebugAndroidTest; then
    device_result=1
    python3 scripts/report-device-exits.py
fi
# AGP owns installation/cleanup of each instrumentation target. Install the final
# rebuilt fixture after its own suite, before the independent Buddy suite.
python3 scripts/check-apk-signatures.py
wait_for_framework
adb install -r fixture/build/outputs/apk/debug/fixture-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
if (( $(adb shell getprop ro.build.version.sdk | tr -d '\r') >= 37 )); then
    # Disposable emulator only. Production requests this permission through its setup UI.
    adb shell pm grant dev.buddy android.permission.ACCESS_LOCAL_NETWORK
fi
prepare_device
if ! ./gradlew "${device_gradle_args[@]}" :app:connectedDebugAndroidTest; then
    device_result=1
    python3 scripts/report-device-exits.py
fi
exit "$device_result"
