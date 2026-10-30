"""Summarize fixture results without collecting screenshots or device logcat."""
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

missing = False
failed = False
for module, minimum in {"pairing-native": 2, "adb": 1, "storage": 2, "fixture": 2, "app": 5}.items():
    executed = 0
    for report in Path(module, "build/outputs/androidTest-results").glob("**/TEST-*.xml"):
        root = ET.parse(report).getroot()
        for case in root.iter("testcase"):
            if case.find("skipped") is None:
                executed += 1
            for failure in list(case.findall("failure")) + list(case.findall("error")):
                failed = True
                print(f"FAIL {module}: {case.get('classname')}#{case.get('name')}")
                details = failure.text or failure.get("message", "")
                print(details[:2000])
                if len(details) > 2000:
                    print(details[-4000:])
    print(f"{module}: {executed} executed tests; minimum {minimum}")
    missing |= executed < minimum
if missing or failed:
    sys.exit("Device suites failed or did not execute the required tests")
