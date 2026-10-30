"""Inspect the actual optimized APK, including entry points used outside normal app code."""
import os
from pathlib import Path
import subprocess
import struct
import tempfile
import xml.etree.ElementTree as ET
from zipfile import ZipFile

sdk = Path(os.environ.get("ANDROID_HOME") or os.environ["ANDROID_SDK_ROOT"])
tools = sdk / "build-tools/37.0.0"
app = Path("app/build/outputs/apk/release/app-release-unsigned.apk")
for apk in (app, Path("fixture/build/outputs/apk/release/fixture-release-unsigned.apk")):
    subprocess.run(
        [str(tools / "zipalign"), "-c", "-P", "16", "4", str(apk)],
        check=True, capture_output=True,
    )

expected = {
    "dev.buddy.helper.HelperMain", "dev.buddy.helper.RevalidateMain",
    "dev.buddy.pairing.NativeSpake2", "dev.buddy.HistoryRetentionWorker",
}
found = set()
with tempfile.TemporaryFile() as output:
    subprocess.run([str(tools / "dexdump"), "-l", "xml", str(app)], stdout=output, check=True)
    output.seek(0)
    package = ""
    for event, element in ET.iterparse(output, events=("start", "end")):
        if event == "start" and element.tag == "package":
            package = element.get("name", "")
        if event != "end" or element.tag != "class":
            continue
        name = package + "." + element.get("name", "")
        if name in expected:
            methods = element.findall("method")
            if name.endswith("Main"):
                valid = any(
                    method.get("name") == "main" and method.get("static") == "true"
                    and method.get("visibility") == "public" and method.get("return") == "void"
                    and [parameter.get("type") for parameter in method.findall("parameter")] == ["java.lang.String[]"]
                    for method in methods
                )
            elif name.endswith("HistoryRetentionWorker"):
                valid = any(
                    constructor.get("visibility") == "public"
                    and [parameter.get("type") for parameter in constructor.findall("parameter")]
                    == ["android.content.Context", "androidx.work.WorkerParameters"]
                    for constructor in element.findall("constructor")
                )
            else:
                natives = {method.get("name") for method in methods if method.get("native") == "true"}
                valid = {"create", "generate", "process", "destroy"} <= natives
            if not valid:
                raise SystemExit(f"Optimized APK lost required entry points in {name}")
            found.add(name)
        element.clear()
if found != expected:
    raise SystemExit(f"Optimized APK lost required classes: {sorted(expected - found)}")
with ZipFile(app) as archive:
    libraries = {name for name in archive.namelist() if name.startswith("lib/") and name.endswith(".so")}
    abis = {name.split("/")[1] for name in libraries}
    if abis != {"arm64-v8a", "x86_64"}:
        raise SystemExit("Optimized APK contains an unsupported or missing native ABI")
    for abi in abis:
        if not {f"lib/{abi}/libbuddy_pairing.so", f"lib/{abi}/libconscrypt_jni.so"} <= libraries:
            raise SystemExit(f"Optimized APK has an incomplete pairing/TLS stack for {abi}")
    for name in sorted(libraries):
        elf = archive.read(name)
        if elf[:6] != b"\x7fELF\x02\x01":
            raise SystemExit(f"Expected a 64-bit little-endian native library: {name}")
        offset = struct.unpack_from("<Q", elf, 32)[0]
        entry_size, count = struct.unpack_from("<HH", elf, 54)
        if entry_size < 56 or offset + entry_size * count > len(elf):
            raise SystemExit(f"Invalid ELF program header: {name}")
        loads = 0
        for index in range(count):
            header = offset + index * entry_size
            if struct.unpack_from("<I", elf, header)[0] != 1:  # PT_LOAD
                continue
            loads += 1
            file_offset, address = struct.unpack_from("<QQ", elf, header + 8)
            alignment = struct.unpack_from("<Q", elf, header + 48)[0]
            if alignment < 16384 or file_offset % 16384 != address % 16384:
                raise SystemExit(f"Native library does not support 16 KB pages: {name}")
        if not loads:
            raise SystemExit(f"Native library has no loadable segments: {name}")
    if archive.read("assets/licenses/boringssl.txt") != Path("pairing-native/boringssl/LICENSE").read_bytes():
        raise SystemExit("Packaged native license differs from the pinned source")
print("Optimized entry points, JNI, license and 16 KB native/APK alignment verified")
