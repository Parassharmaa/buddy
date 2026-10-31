"""Compare device results with the compiled test manifest; runner failures are not tests."""
from collections import Counter, defaultdict
from pathlib import Path
import json
import sys
import xml.etree.ElementTree as ET

manifest_path = Path("build/device-test-manifest.json")
if not manifest_path.exists():
    sys.exit("Missing compiled test manifest; run check-device-test-signatures.py first")
manifest = json.loads(manifest_path.read_text())
if manifest.get("schema") != 1 or not manifest.get("modules"):
    sys.exit("Invalid compiled test manifest")
failed = False
totals = Counter()
for module, names in sorted(manifest["modules"].items()):
    expected = set(names)
    completed = set()
    seen = Counter()
    counts = Counter()
    outcomes = defaultdict(set)
    for report in Path(module, "build/outputs/androidTest-results").glob("**/TEST-*.xml"):
        root = ET.parse(report).getroot()
        for case in root.iter("testcase"):
            identity = f"{case.get('classname')}#{case.get('name')}"
            failures = list(case.findall("failure")) + list(case.findall("error"))
            if identity == "android-test#testRunFailed":
                counts["runner_failures"] += 1
                failed = True
                print(f"RUNNER FAILURE {module}; this is not a test case")
            else:
                seen[identity] += 1
                if case.find("skipped") is not None:
                    outcomes["skipped"].add(identity)
                    continue
                details = "\n".join(item.text or item.get("message", "") for item in failures)
                interrupted = bool(failures) and any(marker in details for marker in (
                    "Test failed with status -1", "Instrumentation run failed", "Process crashed",
                ))
                if interrupted:
                    outcomes["interrupted"].add(identity)
                else:
                    completed.add(identity)
                    outcomes["failed" if failures else "passed"].add(identity)
                if failures:
                    failed = True
                    print(f"{'INTERRUPTED' if interrupted else 'FAIL'} {module}: {identity}")
            for failure in failures:
                details = failure.text or failure.get("message", "")
                print(details[:2000])
                if len(details) > 2000:
                    print(details[-4000:])
    missing = expected - completed
    unexpected = set(seen) - expected
    duplicates = {name for name, count in seen.items() if count > 1}
    for outcome in ("passed", "failed", "interrupted", "skipped"):
        counts[outcome] = len(outcomes[outcome] & expected)
    counts["planned"] = len(expected)
    counts["completed"] = len(completed & expected)
    totals.update(counts)
    print(
        f"{module}: planned={len(expected)}; completed={len(completed & expected)}; "
        f"passed={counts['passed']}; failed={counts['failed']}; interrupted={counts['interrupted']}; "
        f"skipped={counts['skipped']}; runner_failures={counts['runner_failures']}"
    )
    for label, values in (("INCOMPLETE", missing), ("UNEXPECTED", unexpected), ("DUPLICATE", duplicates)):
        for name in sorted(values)[:25]:
            print(f"{label} {module}: {name}")
    failed |= bool(missing or unexpected or duplicates)
print("DEVICE TOTAL: " + "; ".join(f"{key}={totals[key]}" for key in (
    "planned", "completed", "passed", "failed", "interrupted", "skipped", "runner_failures",
)))
if failed:
    sys.exit("Device suites failed or did not complete the same compiled test manifest")
