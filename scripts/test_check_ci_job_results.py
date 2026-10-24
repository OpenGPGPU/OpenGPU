#!/usr/bin/env python3
"""Exercise the required gate against missing, failed and stale parallel work."""
import itertools
import os
from pathlib import Path
import subprocess
import unittest


SCRIPT = Path(__file__).with_name("check_ci_job_results.sh")


class RequiredJobsTest(unittest.TestCase):
    def run_gate(self, **values):
        keys = ("SNAPSHOT_CURRENT", "CHECKS_RESULT", "SELECTED", "UNIT_RESULT",
                "AFFECTED", "PARTITIONS", "AFFECTED_RESULT")
        env = {key: value for key, value in os.environ.items() if key not in keys}
        env.update(values)
        return subprocess.run(["bash", str(SCRIPT)], env=env,
                              capture_output=True).returncode == 0

    def test_required_results_for_each_selection(self):
        statuses = ("success", "failure", "cancelled", "skipped", "")
        profiles = (
            ("NONE", "[]", "[]", "skipped", "skipped"),
            ("ALL", "[]", "[]", "success", "skipped"),
            ("opengpu.graphics.*", '["Spec"]', '[{"id":"Spec"}]',
             "success", "success"),
        )
        for selected, affected, partitions, required_unit, required_affected in profiles:
            for checks, unit, changed in itertools.product(statuses, repeat=3):
                with self.subTest(selected=selected, checks=checks, unit=unit, changed=changed):
                    self.assertEqual(self.run_gate(
                        SNAPSHOT_CURRENT="true", CHECKS_RESULT=checks, SELECTED=selected,
                        UNIT_RESULT=unit, AFFECTED=affected, PARTITIONS=partitions,
                        AFFECTED_RESULT=changed),
                        (checks, unit, changed) ==
                        ("success", required_unit, required_affected))

    def test_current_snapshot_and_complete_selection_are_required(self):
        valid = dict(SNAPSHOT_CURRENT="true", CHECKS_RESULT="success", SELECTED="ALL",
                     UNIT_RESULT="success", AFFECTED='["Spec"]',
                     PARTITIONS='[{"id":"Spec"}]', AFFECTED_RESULT="success")
        for key in valid:
            missing = dict(valid)
            del missing[key]
            with self.subTest(missing=key):
                self.assertFalse(self.run_gate(**missing))
        for stale in ("false", ""):
            self.assertFalse(self.run_gate(**(valid | {"SNAPSHOT_CURRENT": stale})))
        self.assertFalse(self.run_gate(**(valid | {"PARTITIONS": "[]"})))
        self.assertFalse(self.run_gate(**(valid | {"AFFECTED": "[]"})))


if __name__ == "__main__":
    unittest.main()
