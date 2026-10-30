#!/usr/bin/env python3
import base64
import os
import subprocess
import textwrap
from pathlib import Path
import tempfile
import unittest

from ci_case_partitions import (
    PROFILE_DEFAULTS, MAX_CASES, HOST_CASE_LIMIT, WHOLE_SUITES_PER_JOB,
    COALESCED_TEXTURE_SUITE, TEXTURE_MODEL_CASE_LIMIT,
    catalog_from, plan, profile, regression_batches, case_profile, texture_model_suite)
from run_ci_partition import check_cases, command_for


class CasePartitionsTest(unittest.TestCase):
    def test_coalesced_texture_preserves_cases_and_caches_in_three_model_jobs(self):
        suite = COALESCED_TEXTURE_SUITE
        def scenario(name):
            return f"should complete programmable texture rendering with {name} and recover"
        cases = [scenario(n) for n in ("translation", "invalid PTE", "staging translation",
                                      "instruction vertex translation", "instruction vertex guest shader")]
        cases += ["should observe a CPU update to translated uncached vertex staging across draws"]
        for selection in ([suite], {"system-tex-2": [suite]}):
            with self.subTest(selection=selection):
                parts = plan(selection, {suite: cases})
                self.assertEqual(sorted(len(p["cases"]) for p in parts), [1, 2, 3])
                self.assertEqual(sorted(n for p in parts for n in p["cases"]), sorted(cases))
                self.assertTrue(all(p["suites"] == [suite] and p["cache"] == suite for p in parts))
                self.assertEqual(len({p["id"] for p in parts}), 3)
                self.assertTrue(all(len({case_profile(suite, n) for n in p["cases"]}) == 1 for p in parts))
        with self.assertRaisesRegex(ValueError, "missing its case catalog"):
            plan({"system-tex-2": [suite]}, {})

    def test_coalesced_new_extents_unknown_cases_and_growth_stay_separate(self):
        suite = COALESCED_TEXTURE_SUITE
        prefix = "should complete programmable texture rendering with "
        cases = [prefix + f"runtime input {i} and recover" for i in range(TEXTURE_MODEL_CASE_LIMIT + 1)]
        cases += [prefix + "instruction guest-sized fragment and recover",
                  prefix + "instruction vertex guest-sized fragment and recover",
                  "should exercise a future independent model"]
        parts = plan([suite], {suite: cases})
        self.assertEqual(sorted(len(p["cases"]) for p in parts), [1, 1, 1, 1, TEXTURE_MODEL_CASE_LIMIT])
        self.assertEqual(sorted(n for p in parts for n in p["cases"]), sorted(cases))
        self.assertTrue(all(len(p["cases"]) <= TEXTURE_MODEL_CASE_LIMIT for p in parts))
        self.assertNotEqual(case_profile(suite, prefix + "translation and recover"),
                            case_profile(suite, prefix + "instruction guest-sized fragment and recover"))

    def test_local_qualification_is_rejected_by_explicit_ci_plans(self):
        for suite in ("opengpu.graphics.NativeFourEngineDefaultHostAxiSpec",
                      "opengpu.graphics.VertexShaderTextureMatrixSpec",
                      "opengpu.graphics.FragmentShaderTextureMatrixSpec",
                      "opengpu.system.GpuWorkloadSpec",
                      "opengpu.graphics.BinnedTileResourceQualificationSpec",
                      "opengpu.graphics.MemoryNativeBinnedGraphicsPipelineSpec",
                      "opengpu.graphics.EarlyDepthRenderQualificationSpec"):
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

    def test_regression_batches_preserve_large_plans_and_bound_each_matrix(self):
        suites = [f"opengpu.Suite{i}Spec" for i in range(300)]
        batches = regression_batches(suites, {})
        self.assertEqual([len(batch) for batch in batches], [256, 44])
        self.assertEqual([part["suites"][0] for batch in batches for part in batch], suites)
        self.assertEqual(regression_batches([], {}), [[], []])
        with self.assertRaisesRegex(ValueError, "job budget"):
            plan(suites, {})
        with self.assertRaisesRegex(ValueError, "job budget"):
            regression_batches([f"opengpu.Suite{i}Spec" for i in range(513)], {})

    def test_regression_gate_requires_each_selected_batch(self):
        workflow = (Path(__file__).resolve().parents[1] / ".github/workflows/scala_regression.yml").read_text()
        script = textwrap.dedent(workflow.split("  regression-ok:", 1)[1].split("      run: |\n", 1)[1])
        base = dict(os.environ, DRIVER="success", SELECT="success", DEPENDENCIES="success",
                    SELECTED="ALL", BATCH1="[1]", BATCH2="[2]", SCALA="success", OVERFLOW="success")
        def run(**changes):
            return subprocess.run(["bash", "-e", "-c", script], env=dict(base, **changes)).returncode
        self.assertEqual(run(), 0)
        for changes in ({"SCALA":"failure"}, {"OVERFLOW":"failure"}, {"OVERFLOW":"skipped"},
                        {"BATCH1":"[]"}, {"SELECT":"failure"}):
            self.assertNotEqual(run(**changes), 0)
        self.assertEqual(run(BATCH2="[]", OVERFLOW="skipped"), 0)
        self.assertEqual(run(SELECTED="NONE", BATCH1="[]", BATCH2="[]", SCALA="skipped", OVERFLOW="skipped"), 0)
        self.assertNotEqual(run(SELECTED="NONE", BATCH1="[]", BATCH2="[2]", SCALA="skipped", OVERFLOW="skipped"), 0)

    def test_render_host_cases_reuse_default_model_and_isolate_other_models(self):
        suite = "opengpu.graphics.RenderHostSpec"
        cases = ["RenderHost should latch an external completion in the shared IRQ pending bit",
                 "RenderHost should copy selected rows through the hardware strided engine",
                 "RenderHost should advertise the compute scalar FPU only when elaborated with it",
                 "RenderHost should render offscreen extents independently of the scanout mode",
                 "RenderHost should exercise an unfamiliar configuration",
                 "RenderHost should run a unified render command (mode=0) through the command port",
                 "RenderHost should run a unified render command (mode=2) through the command port"]
        parts = plan([suite], {suite: cases})
        grouped = [set(part["cases"]) for part in parts]
        self.assertIn(set(cases[:2]), grouped)
        for name in cases[2:5]:
            self.assertIn({name}, grouped)
        self.assertIn(set(cases[5:]), grouped)
        self.assertEqual(sorted(name for part in parts for name in part["cases"]), sorted(cases))
        with self.assertRaisesRegex(ValueError, "missing its case catalog"):
            plan([suite], {})

    def test_gpu_system_cases_preserve_model_reuse_and_unknown_cases(self):
        suite = "opengpu.system.GpuSystemSpec"
        cases = ["should fill a cache line through the shared L2",
                 "should copy strided rows through the shared L2",
                 "should execute a fill through the unified command interface",
                 "should resolve a multi-row 4x region through the shared L2",
                 "should execute graphics tasks and compute on the same 1 CUs with texture writeback",
                 "should route faults from graphics tasks separately from compute commands",
                 "should execute graphics tasks and compute on the same 2 CUs with texture writeback",
                 "should execute a new, unfamiliar GPU system scenario"]
        cases = ["GpuSystem " + name for name in cases]
        parts = plan([suite], {suite: cases})
        self.assertEqual(sorted(name for part in parts for name in part["cases"]), sorted(cases))
        grouped = [set(part["cases"]) for part in parts]
        self.assertIn(set(cases[:2]), grouped)
        self.assertIn(set(cases[2:4]), grouped)
        self.assertIn(set(cases[4:6]), grouped)
        self.assertIn({cases[6]}, grouped)
        self.assertIn({cases[7]}, grouped)
        self.assertEqual(len(parts), 5)
        with self.assertRaisesRegex(ValueError, "missing its case catalog"):
            plan([suite], {})

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
        self.assertEqual(len(parts), 8)
        self.assertEqual(sorted(name for part in parts for name in part["cases"]), sorted(cases))
        for part in parts:
            self.assertLessEqual(len(part["cases"]), HOST_CASE_LIMIT)
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

    def test_full_system_shards_give_every_suite_its_own_job(self):
        fresh = "opengpu.system.FutureTextureSpec"
        current = "opengpu.system.FutureImageTransportSpec"
        wide = "opengpu.system.WideSharedGraphicsSpec"
        host = "opengpu.system.GpuHostSystemAxiSpec"
        parts = plan({
            "system-tex-2": [fresh, current],
            "system-wide-0": [wide],
            "system-host": [host],
        }, {})
        self.assertEqual(sorted(part["id"] for part in parts), sorted([fresh, current, wide, host]))
        self.assertTrue(all(part["cases"] == [] and part["suites"] == [part["id"]] for part in parts))
        self.assertEqual({part["id"]: part["shard"] for part in parts}, {
            fresh: "system-tex-2", current: "system-tex-2", wide: "system-wide-0", host: "system-host"})

    def test_graphics_whole_suites_spill_into_another_job(self):
        suites = [f"opengpu.graphics.Suite{i}Spec" for i in range(WHOLE_SUITES_PER_JOB + 3)]
        parts = plan({"graphics-14": suites}, {})
        self.assertEqual(len(parts), 2)
        self.assertEqual([len(part["suites"]) for part in parts], [WHOLE_SUITES_PER_JOB, 3])
        self.assertEqual(sorted(name for part in parts for name in part["suites"]), sorted(suites))
        self.assertTrue(all(part["shard"] == "graphics-14" and part["cases"] == [] for part in parts))
        self.assertEqual(parts[0]["id"], "graphics-14")

    def test_programmable_texture_splits_fragment_vertex_and_guest_models(self):
        suite = "opengpu.system.BufferedTextureParallelCuProgrammableTextureAxiSpec"
        self.assertTrue(texture_model_suite(suite))
        prefix = "should complete programmable texture rendering with "
        cases = [prefix + name + " and recover" for name in (
            "translation", "invalid PTE", "staging translation",
            "instruction vertex translation", "instruction vertex guest shader",
            "instruction guest-sized fragment",
            "instruction vertex guest-sized fragment")]
        for selection in ([suite], {"system-tex-0": [suite]}):
            with self.subTest(selection=type(selection).__name__):
                parts = plan(selection, {suite: cases})
                self.assertEqual(sorted(len(p["cases"]) for p in parts), [1, 1, 2, 3])
                self.assertEqual(sorted(n for p in parts for n in p["cases"]), sorted(cases))
                self.assertTrue(all(p["cache"] == suite for p in parts))
                self.assertTrue(all(len({case_profile(suite, n) for n in p["cases"]}) == 1 for p in parts))
        with self.assertRaisesRegex(ValueError, "missing its case catalog"):
            plan({"system-tex-0": [suite]}, {})
    def test_a_new_host_axi_case_spills_into_the_next_job(self):
        suite = "opengpu.graphics.FutureHostAxiSpec"
        cases = [f"typed case{i} local=false" for i in range(HOST_CASE_LIMIT + 1)]
        parts = plan([suite], {suite: cases})
        self.assertEqual(sorted(len(part["cases"]) for part in parts), [1, HOST_CASE_LIMIT])
        self.assertEqual(sorted(name for part in parts for name in part["cases"]), sorted(cases))

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
