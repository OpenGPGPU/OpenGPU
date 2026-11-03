#!/usr/bin/env python3
"""Exercise quiet child execution and strict partition validation end to end."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


SCRIPT = Path(__file__).with_name("run_ci_partition.py").resolve()
SUITE = "opengpu.testutil.QuietSpec"
CASE = "should execute the planned case"


class PartitionExecutionTest(unittest.TestCase):
    def execute(self, status=0, reports=True):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            report = root / "target/test-reports" / ("TEST-" + SUITE + ".xml")
            report.parent.mkdir(parents=True)
            # A previous successful report must never rescue a fresh empty run.
            xml = (f'<testsuite name="{SUITE}" tests="1">'
                   f'<testcase name="{CASE}"/></testsuite>')
            report.write_text(xml)
            fake = root / "sbt"
            fake.write_text(f"#!{sys.executable}\n" +
                "import pathlib, sys, time\n" +
                "time.sleep(0.4)\n" +
                (f"p = pathlib.Path({str(report)!r})\n"
                 "p.parent.mkdir(parents=True, exist_ok=True)\n"
                 f"p.write_text({xml!r})\n" if reports else "") +
                "print('Total number of tests run: 1')\n"
                "print('Suites: completed 1, aborted 0')\n"
                "print('Tests: succeeded 1, failed 0, canceled 0, ignored 0, pending 0')\n" +
                f"sys.exit({status})\n")
            fake.chmod(0o755)
            log = root / "run.log"
            env = dict(os.environ, PATH=str(root) + os.pathsep + os.environ["PATH"],
                       SUITES_JSON=json.dumps([SUITE]), CASES_JSON=json.dumps([CASE]),
                       EXPECTED_JSON="{}", PARTITION_ID="ordinary",
                       CI_TEST_LOG=str(log), OPENGPU_CI_HEARTBEAT_SECONDS="0.05",
                       SBT_FLAGS="", GITHUB_STEP_SUMMARY=str(root / "summary"))
            result = subprocess.run([sys.executable, str(SCRIPT)], cwd=root, env=env,
                                    capture_output=True, text=True, timeout=15)
            return result, log.read_text()

    def test_quiet_child_has_diagnostics_and_fresh_exact_case_results(self):
        result, log = self.execute()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("[ci heartbeat]", result.stdout)
        self.assertIn("summed RSS=", log)
        self.assertIn("disk free=", log)
        self.assertIn("ScalaTest: 1 tests passed across 1 suites", result.stdout)
        self.assertEqual(log.count("Total number of tests run:"), 1)

    def test_child_failure_is_preserved_despite_successful_reports(self):
        result, _ = self.execute(status=7)
        self.assertEqual(result.returncode, 7)
        self.assertNotIn("ScalaTest: 1 tests passed", result.stdout)

    def test_success_exit_without_fresh_report_rejects_stale_report(self):
        result, _ = self.execute(reports=False)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Missing test report", result.stderr)

    def test_grouped_runner_checks_each_catalog_and_requires_metadata(self):
        suites = ["opengpu.system.OneSpec", "opengpu.system.TwoSpec"]
        expected = {suites[0]: ["first"], suites[1]: ["second"]}
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            fake = root / "sbt"
            marker = root / "started"
            def run(second_case, catalog):
                fake.write_text(f"#!{sys.executable}\n" +
                    "import pathlib\n" +
                    f"pathlib.Path({str(marker)!r}).touch()\n" +
                    "p = pathlib.Path('target/test-reports'); p.mkdir(parents=True, exist_ok=True)\n" +
                    f"(p / 'TEST-{suites[0]}.xml').write_text('<testsuite name=\"{suites[0]}\" tests=\"1\"><testcase name=\"first\"/></testsuite>')\n" +
                    f"(p / 'TEST-{suites[1]}.xml').write_text('<testsuite name=\"{suites[1]}\" tests=\"1\"><testcase name=\"{second_case}\"/></testsuite>')\n" +
                    "print('Total number of tests run: 2')\n" +
                    "print('Suites: completed 2, aborted 0')\n" +
                    "print('Tests: succeeded 2, failed 0, canceled 0, ignored 0, pending 0')\n")
                fake.chmod(0o755)
                marker.unlink(missing_ok=True)
                env = dict(os.environ, PATH=str(root) + os.pathsep + os.environ["PATH"],
                           SUITES_JSON=json.dumps(suites), CASES_JSON="[]", EXPECTED_JSON=json.dumps(catalog),
                           PARTITION_ID="tessellation-abi-test", CI_TEST_LOG=str(root / "run.log"),
                           OPENGPU_CI_HEARTBEAT_SECONDS="0.05", SBT_FLAGS="",
                           GITHUB_STEP_SUMMARY=str(root / "summary"))
                result = subprocess.run([sys.executable, str(SCRIPT)], cwd=root, env=env,
                                        capture_output=True, text=True, timeout=15)
                return result, marker.exists()

            result, started = run("second", expected)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertTrue(started)
            result, started = run("unexpected", expected)
            self.assertNotEqual(result.returncode, 0)
            self.assertTrue(started)
            self.assertIn("do not match", result.stderr)
            result, started = run("second", {})
            self.assertNotEqual(result.returncode, 0)
            self.assertFalse(started)
            self.assertIn("must include its expected catalog", result.stderr)


if __name__ == "__main__":
    unittest.main()
