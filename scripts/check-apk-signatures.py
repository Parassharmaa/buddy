"""Verify matching development signatures and 16 KB APK alignment."""
import os
from pathlib import Path
import re
import subprocess

sdk = Path(os.environ.get("ANDROID_HOME") or os.environ["ANDROID_SDK_ROOT"])
signer = sdk / "build-tools/37.0.0/apksigner"
digests = []
for module in ("app", "fixture"):
    apk = Path(module, f"build/outputs/apk/debug/{module}-debug.apk")
    subprocess.run(
        [str(sdk / "build-tools/37.0.0/zipalign"), "-c", "-P", "16", "4", str(apk)],
        check=True, capture_output=True, text=True,
    )
    result = subprocess.run(
        [str(signer), "verify", "--print-certs", str(apk)],
        check=True, capture_output=True, text=True,
    )
    found = set(re.findall(r"certificate SHA-256 digest: ([a-fA-F0-9]+)", result.stdout))
    if len(found) != 1:
        raise SystemExit(f"Could not verify the {module} APK signing identity")
    digests.append(found.pop().lower())
if digests[0] != digests[1]:
    raise SystemExit("Buddy and fixture APK signing identities differ")
print("Buddy and controlled fixture APK signatures match")
