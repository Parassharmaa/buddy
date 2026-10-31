from pathlib import Path
import hashlib
import json
import os
import subprocess
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET

SCRIPT = Path(__file__).resolve().parents[1] / "report-device-tests.py"


class DeviceResultReportingTest(unittest.TestCase):
    def report(self, expected, cases, summary=True):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest = {"schema": 1, "modules": {"app": expected}}
            (root / "build").mkdir()
            (root / "build/device-test-manifest.json").write_text(json.dumps(manifest))
            suite = ET.Element("testsuite")
            for identity, outcome in cases:
                name, method = identity.split("#")
                case = ET.SubElement(suite, "testcase", classname=name, name=method)
                if outcome == "skipped":
                    ET.SubElement(case, "skipped")
                elif outcome:
                    ET.SubElement(case, "failure").text = outcome
            reports = root / "app/build/outputs/androidTest-results"
            reports.mkdir(parents=True)
            ET.ElementTree(suite).write(reports / "TEST-fixture.xml")
            destination = root / "summary.md"
            environment = os.environ | {"GITHUB_STEP_SUMMARY": str(destination), "GITHUB_SHA": "a" * 40}
            result = subprocess.run(
                [sys.executable, str(SCRIPT)] + (["--summary"] if summary else []),
                cwd=root, env=environment, capture_output=True, text=True, timeout=5,
            )
            text = destination.read_text() if destination.exists() else ""
            fingerprint = hashlib.sha256(
                json.dumps(manifest, sort_keys=True, separators=(",", ":")).encode()
            ).hexdigest()
            return result, text, fingerprint

    def test_complete_inventory_passes_and_identifies_tested_revision(self):
        result, text, fingerprint = self.report(["Fixture#one"], [("Fixture#one", None)])
        self.assertEqual(0, result.returncode)
        self.assertIn("Android device tests: PASS", text)
        self.assertIn("a" * 40, text)
        self.assertIn(fingerprint, text)
        self.assertIn("| Total | 1 | 1 | 1 | 0 | 0 | 0 |", text)

    def test_failed_and_missing_cases_fail_without_copying_failure_contents(self):
        result, text, _ = self.report(
            ["Fixture#one", "Fixture#two"], [("Fixture#one", "PRIVATE_FAILURE_CANARY")]
        )
        self.assertNotEqual(0, result.returncode)
        self.assertIn("Android device tests: FAIL", text)
        self.assertIn("| Total | 2 | 1 | 0 | 1 | 0 | 0 |", text)
        self.assertIn("missing: 1", text)
        self.assertNotIn("PRIVATE_FAILURE_CANARY", text)

    def test_runner_failure_is_not_counted_as_a_completed_test(self):
        result, text, _ = self.report(
            ["Fixture#one"], [("android-test#testRunFailed", "Process crashed")]
        )
        self.assertNotEqual(0, result.returncode)
        self.assertIn("| Total | 1 | 0 | 0 | 0 | 0 | 0 |", text)
        self.assertIn("Runner failures: 1; missing: 1", text)

    def test_interrupted_skipped_duplicate_and_unexpected_results_fail(self):
        variants = [
            ([("Fixture#one", "Process crashed")], "| Total | 1 | 0 | 0 | 0 | 1 | 0 |"),
            ([("Fixture#one", "skipped")], "| Total | 1 | 0 | 0 | 0 | 0 | 1 |"),
            ([("Fixture#one", None), ("Fixture#one", None)], "duplicate: 1"),
            ([("Fixture#one", None), ("Fixture#other", None)], "unexpected: 1"),
        ]
        for cases, expected in variants:
            with self.subTest(cases=cases):
                result, text, _ = self.report(["Fixture#one"], cases)
                self.assertNotEqual(0, result.returncode)
                self.assertIn("Android device tests: FAIL", text)
                self.assertIn(expected, text)

    def test_internal_emulator_check_does_not_duplicate_the_summary(self):
        result, text, _ = self.report(["Fixture#one"], [("Fixture#one", None)], summary=False)
        self.assertEqual(0, result.returncode)
        self.assertEqual("", text)
        self.assertIn("DEVICE TOTAL: planned=1; completed=1; passed=1", result.stdout)


if __name__ == "__main__":
    unittest.main()
