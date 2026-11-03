#!/usr/bin/env python3
"""Exercise the required gate against missing, failed and stale parallel work."""
import itertools
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
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
            ("NONE", '["Spec"]', '[{"id":"Spec"}]', "skipped", "success"),
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

    def test_repository_steps_have_a_prior_checkout(self):
        workflow = SCRIPT.parents[1] / ".github/workflows/scala_test.yml"
        jobs = workflow.read_text().split("\njobs:\n", 1)[1]
        blocks = re.findall(r"^  ([\w-]+):\n(.*?)(?=^  [\w-]+:\n|\Z)",
                            jobs, flags=re.M | re.S)
        checked = set()
        for name, body in blocks:
            script = re.search(r"(?:run:\s*(?:bash|python3)|^\s*(?:bash|python3))\s+scripts/", body, re.M)
            if script is None:
                continue
            with self.subTest(job=name):
                checkout = body.find("uses: actions/checkout@")
                self.assertGreaterEqual(checkout, 0)
                self.assertLess(checkout, script.start())
                self.assertIn("ref: ${{ github.sha }}", body[checkout:script.start()])
            checked.add(name)
        self.assertEqual(checked, {"checks", "unit-tests", "affected-tests", "ci-ok"})

    def test_host_only_affected_cases_enable_compilation_and_registration(self):
        from changed_test_suites import changed_suites
        from ci_case_partitions import TESS_ABI_SUITES
        root = SCRIPT.parents[1]
        changed = "driver/tests/opengpu_tessellation_job_fixture_test.c"
        selected = subprocess.check_output(["bash", str(root / "scripts/select_tests.sh"), changed], text=True).strip()
        affected = changed_suites(root, [changed])
        self.assertEqual(selected, "NONE")
        self.assertTrue(TESS_ABI_SUITES <= set(affected))
        with tempfile.TemporaryDirectory() as directory:
            selection = Path(directory) / "affected.json"
            selection.write_text(json.dumps(affected))
            candidates = subprocess.check_output([sys.executable, str(root / "scripts/ci_case_partitions.py"),
                                                  "candidates", str(selection)], text=True).splitlines()
        self.assertTrue(TESS_ABI_SUITES <= set(candidates))
        workflow = (root / ".github/workflows/scala_test.yml").read_text()
        condition = "steps.select.outputs.selected != 'NONE' || steps.select.outputs.has_case_suites == 'true'"
        for name in ("Set up simulation toolchain", "Cache incremental Scala compilation",
                     "Compile all sources and register affected cases"):
            match = re.search(r"- name: " + re.escape(name) + r"\n\s+if: ([^\n]+)", workflow)
            self.assertIsNotNone(match)
            self.assertEqual(match.group(1), condition)
        self.assertIn('echo "has_case_suites=true"', workflow)
        partition = re.search(r"- name: Run required partition\n(.*?)(?=\n\s+- name:)", workflow, re.S)
        self.assertIsNotNone(partition)
        self.assertIn("PARTITION_ID: ${{ matrix.partition.id }}", partition.group(1))
        self.assertIn("EXPECTED_JSON: ${{ toJson(matrix.partition.expected) }}", partition.group(1))

    def test_toolchain_cache_warmer_is_manual_and_main_only(self):
        workflow = (SCRIPT.parents[1] / ".github/workflows/warm_simulation_cache.yml").read_text()
        triggers = re.search(r"^on:\n(.*?)(?=^[^\s#]|\Z)", workflow, re.M | re.S)
        self.assertIsNotNone(triggers)
        self.assertEqual(triggers.group(1).strip(), "workflow_dispatch:")
        self.assertIn("if: github.ref == 'refs/heads/main'", workflow)
        self.assertIn("uses: ./.github/actions/setup-simulation", workflow)
        self.assertLess(workflow.index("uses: actions/checkout@v4"),
                        workflow.index("uses: ./.github/actions/setup-simulation"))


if __name__ == "__main__":
    unittest.main()
