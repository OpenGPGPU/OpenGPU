#!/usr/bin/env python3
import base64
from pathlib import Path
import tempfile
import unittest

from ci_case_partitions import PROFILE_DEFAULTS, MAX_CASES, catalog_from, plan, profile
from run_ci_partition import check_cases, command_for


class CasePartitionsTest(unittest.TestCase):
    def test_local_qualification_is_rejected_by_explicit_ci_plans(self):
        for suite in ("opengpu.graphics.NativeFourEngineDefaultHostAxiSpec",
                      "opengpu.graphics.VertexShaderTextureMatrixSpec",
                      "opengpu.graphics.FragmentShaderTextureMatrixSpec",
                      "opengpu.system.GpuWorkloadSpec",
                      "opengpu.graphics.BinnedTileResourceQualificationSpec"):
            with self.subTest(suite=suite), self.assertRaisesRegex(ValueError, "Local-only qualification"):
                plan([suite], {suite: ["local=false", "local=true"]})

    def test_replay_cases_share_three_complete_hardware_model_groups(self):
        suite = "opengpu.graphics.TypedReplayTileImagePipelineSpec"
        cases = [f"{task} local={local} oneSlot=false" for task in ("polygon", "centroid", "publish")
                 for local in ("true", "false")]
        cases += [f"control{i} local=true oneSlot=false" for i in range(4)]
        cases += ["integer local=false oneSlot=false"]
        cases += [f"concurrency local=true oneSlot={value}" for value in ("true", "false")]
        parts = plan([suite], {suite: cases})
        self.assertEqual(sorted(len(part["cases"]) for part in parts), [1, 4, 8])
        self.assertEqual(sorted(name for part in parts for name in part["cases"]), sorted(cases))
        self.assertEqual(len({part["cache"] for part in parts}), 3)
        for part in parts:
            self.assertEqual(len({profile(name) for name in part["cases"]}), 1)

    def test_typed_image_cases_share_only_their_actual_payload_models(self):
        suite = "opengpu.graphics.TypedTileImagePipelineSpec"
        cases = [f"{operation} local={local}" for operation in
                 ("publish images", "point and line", "sample depth")
                 for local in ("true", "false")]
        cases += ["store acknowledgement", "final publication cancel", "resolve acknowledgement"]
        parts = plan([suite], {suite: cases})
        self.assertEqual(sorted(len(part["cases"]) for part in parts), [3, 6])
        self.assertEqual(sorted(name for part in parts for name in part["cases"]), sorted(cases))
        self.assertEqual(len({part["cache"] for part in parts}), 2)
        for part in parts:
            self.assertEqual(len({profile(name, PROFILE_DEFAULTS[suite]) for name in part["cases"]}), 1)
            self.assertLessEqual(len(part["cases"]), MAX_CASES)
        with self.assertRaisesRegex(ValueError, "missing its case catalog"):
            plan([suite], {})

    def test_explicit_typed_image_profiles_override_harness_defaults(self):
        defaults = PROFILE_DEFAULTS["opengpu.graphics.TypedTileImagePipelineSpec"]
        self.assertEqual(profile("untagged", defaults), profile("local=true oneSlot=false", defaults))
        self.assertNotEqual(profile("untagged", defaults), profile("local=false", defaults))
        self.assertNotEqual(profile("untagged", defaults), profile("oneSlot=true", defaults))
        self.assertEqual(profile("untagged"), ())

    def test_typed_renderer_preserves_all_cases_in_three_actual_models(self):
        suite = "opengpu.graphics.TypedTileRenderPipelineSpec"
        cases = [f"retain MRT and depth stencil local={local}" for local in ("true", "false")]
        cases += ["make fragment spill progress oneSlot=true", "intersect scissors",
                  "reject unsafe bounds", "retain queued launches during cancellation",
                  "drain attachment refills and pixel faults"]
        parts = plan([suite], {suite: cases})
        self.assertEqual(sorted(len(part["cases"]) for part in parts), [1, 1, 5])
        self.assertEqual(sorted(name for part in parts for name in part["cases"]), sorted(cases))
        self.assertEqual(len({part["cache"] for part in parts}), 3)
        defaults = PROFILE_DEFAULTS[suite]
        for part in parts:
            self.assertEqual(part["suites"], [suite])
            self.assertEqual(len({profile(name, defaults) for name in part["cases"]}), 1)
        with self.assertRaisesRegex(ValueError, "missing its case catalog"):
            plan([suite], {})

    def test_two_case_suite_splits_distinct_hardware_profiles(self):
        suite = "opengpu.graphics.ExampleHostAxiSpec"
        cases = [f"render a full native batch local={local}" for local in ("true", "false")]
        parts = plan([suite], {suite: cases})
        self.assertEqual(len(parts), 2)
        self.assertEqual(sorted(name for part in parts for name in part["cases"]), sorted(cases))
        self.assertEqual(len({part["cache"] for part in parts}), 2)

    def test_two_case_suite_keeps_one_elaborated_profile_together(self):
        suite = "opengpu.graphics.ExampleHostAxiSpec"
        cases = [f"render local=true Sv32={virtual}" for virtual in ("true", "false")]
        parts = plan([suite], {suite: cases})
        self.assertEqual(len(parts), 1)
        self.assertEqual(parts[0]["suites"], [suite])
        self.assertEqual(parts[0]["cases"], [])

    def test_every_case_once_and_same_model_profiles_together(self):
        suite = "opengpu.graphics.ExampleHostAxiSpec"
        cases = [f"operation{i} local={local}" for local in ("true", "false") for i in range(MAX_CASES + 3)]
        parts = plan([suite], {suite: cases})
        self.assertEqual(len(parts), 4)
        self.assertEqual(sorted(name for part in parts for name in part["cases"]), sorted(cases))
        for part in parts:
            self.assertLessEqual(len(part["cases"]), MAX_CASES)
            self.assertEqual(len({profile(name) for name in part["cases"]}), 1)
        self.assertEqual(len({part["cache"] for part in parts}), 2)

    def test_native_vertex_suite_preserves_all_cases_in_bounded_profile_groups(self):
        suite = "opengpu.graphics.VertexShaderStageSpec"
        cases = [f"ordinary{i} local={local}" for local in ("true", "false") for i in range(MAX_CASES + 1)]
        cases += [f"execute product vertex uploads local={local}" for local in ("true", "false")]
        cases += ["full interface spill", "held launch"]
        parts = plan([suite], {suite: cases})
        self.assertEqual(sorted(n for p in parts for n in p["cases"]), sorted(cases))
        self.assertTrue(all(0 < len(p["cases"]) <= MAX_CASES for p in parts))
        for part in parts:
            self.assertEqual(len({profile(n) for n in part["cases"]}), 1)
        with self.assertRaisesRegex(ValueError, "missing its case catalog"):
            plan([suite], {})

    def test_native_fragment_suite_keeps_every_case_in_bounded_partitions(self):
        suite = "opengpu.graphics.FragmentShaderStageSpec"
        cases = [f"fragment case{i} local={local}" for local in ("true", "false") for i in range(MAX_CASES + 1)]
        cases += ["drain staging faults", "carry all64 varying components with oversizeSpill=true"]
        parts = plan([suite], {suite: cases})
        self.assertGreater(len(parts), 1)
        self.assertEqual(sorted(name for part in parts for name in part["cases"]), sorted(cases))
        self.assertTrue(all(0 < len(part["cases"]) <= 2 for part in parts))
        for part in parts:
            self.assertEqual(part["suites"], [suite])
            self.assertEqual(len({profile(name) for name in part["cases"]}), 1)
        with self.assertRaises(ValueError):
            plan([suite], {})

    def test_native_reservation_size_changes_the_elaborated_profile(self):
        case = "carry all64 varying components with oversizeSpill=true"
        self.assertNotEqual(profile(case), profile(case.replace("true", "false")))

    def test_tile_bin_render_cases_leave_graphics_9_in_pairs(self):
        suite = "opengpu.graphics.TileBinRenderPipelineSpec"
        ordinary = "opengpu.graphics.DrawContextFifoSpec"
        cases = [f"frame case{i}" for i in range(5)]
        parts = plan({"graphics-9": [suite, ordinary]}, {suite: cases})
        whole = next(part for part in parts if part["id"] == "graphics-9")
        self.assertEqual(whole["suites"], [ordinary])
        self.assertEqual(whole["cases"], [])
        tiled = [part for part in parts if part["cases"]]
        self.assertEqual(sorted(len(part["cases"]) for part in tiled), [1, 2, 2])
        self.assertEqual(sorted(name for part in tiled for name in part["cases"]), sorted(cases))
        self.assertTrue(all(part["shard"] == "graphics-9" and part["suites"] == [suite] for part in tiled))

    def test_vertex_cases_without_profile_labels_keep_a_two_case_budget(self):
        suite = "opengpu.graphics.VertexShaderStageSpec"
        cases = [f"ordinary case{i}" for i in range(9)]
        cases += [f"stage case{i} local={local}" for local in ("true", "false") for i in range(6)]
        parts = plan([suite], {suite: cases})
        self.assertEqual(sorted(len(p["cases"]) for p in parts), [1, 2, 2, 2, 2, 6, 6])
        self.assertEqual(sorted(name for p in parts for name in p["cases"]), sorted(cases))
        self.assertEqual(len({p["cache"] for p in parts}), 3)
        self.assertTrue(all(len(p["cases"]) <= 2 for p in parts if not profile(p["cases"][0])))

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
