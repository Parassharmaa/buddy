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
        ["adb", "logcat", "-b", "events", "-d", "-v", "raw", "-s", "am_crash:I", "*:S"],
        stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True, timeout=5,
    ).stdout
except (OSError, subprocess.TimeoutExpired):
    events = ""
signals = {"Segmentation fault", "Aborted", "Bus error", "Illegal instruction", "Floating point exception"}
for line in events.splitlines():
    values = line.strip().removeprefix("[").split(",", 8)
    if len(values) < 8:
        continue
    user, pid, package, _flags, kind, message = (value.strip() for value in values[:6])
    if package not in packages or not user.isdecimal() or not pid.isdecimal():
        continue
    if len(kind) > 160 or (
        kind != "Native crash" and not re.fullmatch(r"(?:[A-Za-z_$][\w$]*\.)+[A-Za-z_$][\w$]*", kind)
    ):
        continue
    signal = f"; signal={message}" if message in signals else ""
    print(f"Test process crash ({package}): pid={pid}; kind={kind}{signal}")
