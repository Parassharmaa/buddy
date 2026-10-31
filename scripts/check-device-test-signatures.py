"""Check JUnit method contracts in compiled instrumentation classes before booting Android."""
from pathlib import Path
import re
import json
import hashlib
import subprocess

count = 0
manifest = {}
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
            method = re.search(r"([A-Za-z_$][\w$]*)\(\)", signature)
            if method is None:
                raise SystemExit(f"Could not identify JUnit method: {path}: {signature}")
            classname = str(path).split("/classes/", 1)[1].removesuffix(".class").replace("/", ".")
            case = f"{classname}#{method[1]}"
            cases = manifest.setdefault(path.parts[0], [])
            if case in cases:
                raise SystemExit(f"Duplicate compiled test: {case}")
            cases.append(case)
            count += 1
if count == 0:
    raise SystemExit("No compiled instrumentation tests were found")
for module, minimum in {"pairing-native": 2, "adb": 1, "storage": 3, "fixture": 2, "app": 17}.items():
    if len(manifest.get(module, [])) < minimum:
        raise SystemExit(f"Missing compiled fixture tests in {module}; expected at least {minimum}")
destination = Path("build/device-test-manifest.json")
destination.parent.mkdir(parents=True, exist_ok=True)
destination.write_text(json.dumps({"schema": 1, "modules": {module: sorted(cases) for module, cases in manifest.items()}}, indent=2) + "\n")
fingerprint = hashlib.sha256(json.dumps(manifest, sort_keys=True, separators=(",", ":")).encode()).hexdigest()
print(f"Device test manifest SHA256: {fingerprint}")
print(f"{count} instrumentation test methods have valid JUnit signatures")
