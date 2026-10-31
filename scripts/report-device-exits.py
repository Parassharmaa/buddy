"""Print numeric crash metadata for fixed disposable test packages, without logcat or traces."""
import re
import subprocess

for package in ("dev.buddy.storage.test", "dev.buddy"):
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
