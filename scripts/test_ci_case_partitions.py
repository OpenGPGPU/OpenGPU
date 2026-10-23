#!/usr/bin/env python3
import base64
from pathlib import Path
import tempfile
import unittest

from ci_case_partitions import catalog_from, plan, profile
from run_ci_partition import check_cases, command_for


class CasePartitionsTest(unittest.TestCase):
    def test_every_case_once_and_same_model_profiles_together(self):
        suite = "opengpu.graphics.ExampleHostAxiSpec"
        cases = [f"operation{i} local={local}" for local in ("true", "false") for i in range(5)]
        parts = plan([suite], {suite: cases})
        self.assertEqual(len(parts), 6)
        self.assertEqual(sorted(name for part in parts for name in part["cases"]), sorted(cases))
        for part in parts:
            self.assertLessEqual(len(part["cases"]), 2)
            self.assertEqual(len({profile(name) for name in part["cases"]}), 1)
        self.assertEqual(len({part["cache"] for part in parts}), 2)

    def test_graph_preserves_ordinary_suites_and_every_heavy_case(self):
        heavy = "opengpu.graphics.ExampleHostAxiSpec"
        ordinary = "opengpu.graphics.SimpleSpec"
        graph = {"graphics-0": [heavy, ordinary], "core-a": ["opengpu.core.AluSpec"]}
        cases = [f"test{i}" for i in range(5)]
        parts = plan(graph, {heavy: cases})
        whole = [suite for part in parts if not part["cases"] for suite in part["suites"]]
        self.assertEqual(sorted(whole), sorted([ordinary, "opengpu.core.AluSpec"]))
        self.assertEqual(sorted(name for part in parts for name in part["cases"]), cases)
        self.assertEqual(plan([], {}), [])
        self.assertEqual(plan({}, {}), [])

    def test_missing_catalog_duplicate_suites_and_malformed_catalog_fail(self):
        suite = "opengpu.graphics.ExampleHostAxiSpec"
        for selection, catalog in (([suite], {}), ([suite, suite], {suite: ["test"]})):
            with self.assertRaises(ValueError):
                plan(selection, catalog)
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "catalog.tsv"
            encoded = base64.b64encode('a "quoted" test \\ unicodeλ'.encode()).decode()
            path.write_text(suite + "\t" + encoded + "\n")
            self.assertEqual(catalog_from(path), {suite: ['a "quoted" test \\ unicodeλ']})
            for content in (suite + "\t\n", suite + "\t" + encoded + "," + encoded + "\n",
                            (suite + "\t" + encoded + "\n") * 2):
                path.write_text(content)
                with self.assertRaises(ValueError):
                    catalog_from(path)

    def test_translation_runtime_inputs_share_the_elaborated_profile(self):
        bare = "execute shaders with 2 shared CUs (4 lanes/2 warps, Sv32=false)"
        virtual = bare.replace("false", "true")
        self.assertEqual(profile(bare), profile(virtual))
        self.assertNotEqual(profile(bare), profile(bare.replace("4 lanes", "16 lanes")))

    def test_runner_rejects_empty_or_ambiguous_case_selection(self):
        for suites, cases in (([], []), (["opengpu.ASpec", "opengpu.BSpec"], ["case"]),
                              (["opengpu.ASpec"], ["same", "same"])):
            with self.assertRaises(ValueError):
                command_for(suites, cases)

    def test_runner_rejects_unexpected_or_missing_executed_cases(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "TEST-opengpu.ASpec.xml"
            path.write_text('<testsuite><testcase name="one"/><testcase name="two"/></testsuite>')
            check_cases(temporary, ["opengpu.ASpec"], ["one", "two"])
            for expected in (["one"], ["one", "other"]):
                with self.assertRaises(ValueError):
                    check_cases(temporary, ["opengpu.ASpec"], expected)


if __name__ == "__main__":
    unittest.main()
