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

# Classify native failures in test processes. Never export abort text, addresses or traces.
try:
    crash_buffer = subprocess.run(
        ["adb", "logcat", "-b", "crash", "-d", "-t", "1000", "-v", "brief"],
        stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True, timeout=5,
    ).stdout
except (OSError, subprocess.TimeoutExpired):
    crash_buffer = ""
libraries = {
    "libbuddy_pairing.so": "PAIRING_JNI",
    "libconscrypt_jni.so": "BUNDLED_TLS",
    "libjavacrypto.so": "PLATFORM_TLS",
    "libcrypto.so": "PLATFORM_CRYPTO",
    "libart.so": "ART",
    "libandroid_runtime.so": "ANDROID_FRAMEWORK",
    "libbinder.so": "BINDER",
    "libhwui.so": "GRAPHICS",
    "libandroidx.graphics.path.so": "GRAPHICS_PATH",
    "libsqlite.so": "SQLITE",
    "libc.so": "LIBC",
}
abort_codes = {
    "jni detected error": "JNI_CONTRACT",
    "pending exception": "JNI_PENDING_EXCEPTION",
    "deleted reference": "JNI_DELETED_REFERENCE",
    "scudo error": "HEAP_CORRUPTION",
    "fdsan": "FD_OWNERSHIP",
    "getrandom": "RANDOM_SOURCE",
    "assertion": "NATIVE_ASSERTION",
    "check failed": "NATIVE_ASSERTION",
    "pthread_mutex": "NATIVE_MUTEX",
}
frame_categories = {
    "art::jit::": "ART_JIT",
    "art::gc::": "ART_GC",
    "art::ClassLinker::": "ART_CLASS_LINKER",
    "art::JNI<": "ART_JNI",
    "art::JavaVMExt::JniAbort": "ART_JNI_ABORT",
    "art::interpreter::": "ART_INTERPRETER",
    "art_quick_": "ART_QUICK_ENTRY",
    "art::Thread::": "ART_THREAD",
    "art::SignalChain::": "ART_SIGNAL",
    "memcpy": "MEMORY_COPY",
    "memmove": "MEMORY_COPY",
    "pthread_mutex": "NATIVE_MUTEX",
}
records = []
record = None
for line in crash_buffer.splitlines():
    body = re.sub(r"^[A-Z]/[^()]+\(\s*\d+\):\s*", "", line).strip()
    if body.startswith("*** ***"):
        record = None
    command = re.fullmatch(r"Cmdline: ([A-Za-z0-9_.:]+)", body)
    process = re.search(r">>>\s*([A-Za-z0-9_.:]+)\s*<<<", body)
    package = command[1] if command else process[1] if process else None
    if package is not None:
        if package not in packages:
            record = None
        elif record is None or record["package"] != package:
            record = {"package": package, "modules": set(), "causes": set(), "frames": []}
            records.append(record)
    if record is None:
        continue
    if body.startswith("Abort message:"):
        record["causes"].update(code for marker, code in abort_codes.items() if marker in body.lower())
        if not record["causes"]:
            record["causes"].add("UNCLASSIFIED_ABORT")
    if re.match(r"#\d+\s+pc\s+[0-9a-fA-F]+\b", body):
        record["modules"].update(code for library, code in libraries.items() if library in body)
        categories = sorted({code for marker, code in frame_categories.items() if marker in body})
        if categories and len(record["frames"]) < 8:
            record["frames"].append("+".join(categories))
for record in records[-5:]:
    if record["modules"] or record["causes"]:
        causes = ",".join(sorted(record["causes"])) or "UNCLASSIFIED"
        modules = ",".join(sorted(record["modules"])) or "UNCLASSIFIED"
        frames = ",".join(record["frames"]) or "UNCLASSIFIED"
        print(f"Native crash classification ({record['package']}): causes={causes}; modules={modules}; frames={frames}")
