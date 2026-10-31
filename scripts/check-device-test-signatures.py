"""Check JUnit method contracts in compiled instrumentation classes before booting Android."""
from pathlib import Path
import re
import subprocess

count = 0
for path in Path(".").glob("*/build/intermediates/built_in_kotlinc/debugAndroidTest/**/classes/**/*Test.class"):
    result = subprocess.run(["javap", "-v", "-p", str(path)], check=True, capture_output=True, text=True)
    descriptor = flags = signature = ""
    for line in result.stdout.splitlines():
        value = line.strip()
        if line.startswith("  ") and not line.startswith("    ") and value.endswith(";"):
            signature = value
        if value.startswith("descriptor:"):
            descriptor = value.removeprefix("descriptor:").strip()
        if value.startswith("flags:"):
            flags = value
        if re.match(r"org\.junit\.Test(?:\(|$)", value):
            if descriptor != "()V" or "ACC_PUBLIC" not in flags or "ACC_STATIC" in flags or "ACC_ABSTRACT" in flags:
                raise SystemExit(f"Invalid JUnit method: {path}: {signature}; expected a public, non-static void method without arguments")
            count += 1
if count == 0:
    raise SystemExit("No compiled instrumentation tests were found")
print(f"{count} instrumentation test methods have valid JUnit signatures")
