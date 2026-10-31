"""Compare device results with the compiled test manifest; runner failures are not tests."""
from collections import Counter, defaultdict
from pathlib import Path
import argparse
import hashlib
import html
import os
import re
import json
import sys
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--summary", action="store_true", help="Write counts to the GitHub step summary")
arguments = parser.parse_args()

manifest_path = Path("build/device-test-manifest.json")
if not manifest_path.exists():
    sys.exit("Missing compiled test manifest; run check-device-test-signatures.py first")
manifest = json.loads(manifest_path.read_text())
if manifest.get("schema") != 1 or not manifest.get("modules"):
    sys.exit("Invalid compiled test manifest")
failed = False
totals = Counter()
rows = []
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
    counts["missing"] = len(missing)
    counts["unexpected"] = len(unexpected)
    counts["duplicates"] = len(duplicates)
    totals.update(counts)
    rows.append((module, counts))
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
if arguments.summary and (summary_path := os.environ.get("GITHUB_STEP_SUMMARY")):
    revision = os.environ.get("GITHUB_SHA", "")
    if not re.fullmatch(r"[0-9a-f]{40}|[0-9a-f]{64}", revision):
        revision = "unavailable"
    fingerprint = hashlib.sha256(
        json.dumps(manifest, sort_keys=True, separators=(",", ":")).encode()
    ).hexdigest()
    fields = ("planned", "completed", "passed", "failed", "interrupted", "skipped")
    lines = [
        f"### Android device tests: {'FAIL' if failed else 'PASS'}\n\n",
        f"Tested commit: `{revision}`  \nCompiled test manifest: `{fingerprint}`\n\n",
        "| Module | Planned | Completed | Passed | Failed | Interrupted | Skipped |\n",
        "| --- | ---: | ---: | ---: | ---: | ---: | ---: |\n",
    ]
    for label, counts in rows + [("Total", totals)]:
        safe_label = html.escape(label).replace("|", "&#124;").replace("`", "&#96;")
        lines.append(f"| {safe_label} | " + " | ".join(str(counts[field]) for field in fields) + " |\n")
    lines.append(
        f"\nRunner failures: {totals['runner_failures']}; missing: {totals['missing']}; "
        f"unexpected: {totals['unexpected']}; duplicate: {totals['duplicates']}.\n\n"
        "Every planned test must finish without skips. New tests change a revision's manifest; "
        "compare fingerprints when comparing OS runs.\n"
    )
    with open(summary_path, "a", encoding="utf-8") as summary:
        summary.write("".join(lines))
if failed:
    sys.exit("Device suites failed or did not complete the same compiled test manifest")
