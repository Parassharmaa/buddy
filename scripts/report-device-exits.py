"""Report fixed test-package crash metadata without raw messages or traces."""
import re
import subprocess

packages = (
    "dev.buddy.pairing.test", "dev.buddy.adb.test", "dev.buddy.storage.test", "dev.buddy.fixture", "dev.buddy",
)
for package in packages:
    try:
        result = subprocess.run(
            ["adb", "shell", "dumpsys", "activity", "exit-info", package],
            stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True, timeout=5,
        )
    except (OSError, subprocess.TimeoutExpired):
        continue
    fields = []
    for line in result.stdout.splitlines():
        match = re.match(r"\s*(pid|realUid|reason|subreason|status|importance|pss|rss)=(\d+)\b", line)
        if match:
            fields.append(f"{match[1]}={match[2]}")
        if len(fields) >= 64:
            break
    if fields:
        print(f"Test process exit metadata ({package}): " + ", ".join(fields))

# Package removal can erase exit-info before AGP returns. System crash events survive
# that cleanup; retain only the process, exception type and recognized signal names.
try:
    events = subprocess.run(
        ["adb", "logcat", "-b", "events", "-d", "-v", "brief", "-s", "am_crash:I", "am_kill:I", "am_proc_died:I", "*:S"],
        stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True, timeout=5,
    ).stdout
except (OSError, subprocess.TimeoutExpired):
    events = ""
signals = {"Segmentation fault", "Aborted", "Bus error", "Illegal instruction", "Floating point exception"}
for line in events.splitlines():
    event = re.match(r"^[A-Z]/(am_crash|am_kill|am_proc_died)\(\s*\d+\):\s*\[(.*)", line.strip())
    if not event:
        continue
    tag, payload = event.groups()
    values = [value.strip() for value in payload.rstrip("]").split(",", 8)]
    if len(values) < 5:
        continue
    user, pid, package = values[:3]
    if package not in packages or not user.isdecimal() or not pid.isdecimal():
        continue
    if tag != "am_crash":
        if re.fullmatch(r"-?\d+", values[3]):
            state = f"; state={values[4]}" if tag == "am_proc_died" and values[4].isdecimal() else ""
            print(f"Test process lifecycle ({package}): event={tag}; pid={pid}; oomAdj={values[3]}{state}")
        continue
    if len(values) < 8:
        continue
    kind, message = values[4:6]
    if len(kind) > 160 or (
        kind != "Native crash" and not re.fullmatch(r"(?:[A-Za-z_$][\w$]*\.)+[A-Za-z_$][\w$]*", kind)
    ):
        continue
    signal = f"; signal={message}" if message in signals else ""
    print(f"Test process crash ({package}): pid={pid}; kind={kind}{signal}")


# Fixed enum labels locate an aborted pairing fixture; all other log contents are discarded.
pairing_stages = {
    "ENABLE_WIRELESS_DEBUGGING",
    "WIRELESS_SUPPORT",
    "JOIN_EMULATOR_WIFI",
    "RESET_WIRELESS_DEBUGGING",
    "OPEN_WIRELESS_SETTINGS",
    "ENABLE_WIRELESS_DETAIL_PAGE",
    "WIRELESS_DAEMON_READY",
    "OPEN_PAIRING_DIALOG",
    "READ_PAIRING_DIALOG",
    "LOCAL_TLS_PAIRING",
    "FINISH_PAIRING_DIALOG",
    "WIRELESS_CONNECTION_PORT",
    "AUTHORIZED_TLS_CONNECTION",
    "AUTHENTICATED_HELPER_HANDOFF",
    "HELPER_AFTER_TRANSPORT_CLOSE",
    "FRESH_TRANSPORT_AUTHORIZATION_PROOF",
    "WIRELESS_DEBUGGING_REVOCATION",
}
try:
    qualification = subprocess.run(
        ["adb", "logcat", "-b", "main", "-d", "-v", "brief", "-s", "BuddyQualification:I", "*:S"],
        stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True, timeout=5,
    ).stdout
except (OSError, subprocess.TimeoutExpired):
    qualification = ""
reported = []
for line in qualification.splitlines():
    stage = re.fullmatch(r"I/BuddyQualification\(\s*\d+\):\s*pairing_stage=([A-Z_]+)", line.strip())
    if stage and stage[1] in pairing_stages:
        reported.append(stage[1])
for stage in reported[-20:]:
    print(f"Pairing qualification stage: {stage}")
