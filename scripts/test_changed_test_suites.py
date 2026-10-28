#!/usr/bin/env python3
from pathlib import Path
import tempfile
import unittest

from changed_test_suites import changed_suites


class ChangedTestSuitesTest(unittest.TestCase):
    def test_fixtures_inheritance_and_matching_production_spec(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            sources = root / "src/test/scala/opengpu"
            sources.mkdir(parents=True)
            for name, source in {
                "Support.scala": "trait Support",
                "BaseSpec.scala": "abstract class BaseSpec extends AnyFlatSpec with Support",
                "Profiles.scala": "class FirstSpec extends BaseSpec\nfinal class SecondSpec extends FirstSpec\nclass OtherSpec extends AnyFlatSpec",
                "AluSpec.scala": "class AluSpec extends AnyFlatSpec",
                "UnrelatedSpec.scala": "// Support\nclass UnrelatedSpec extends AnyFlatSpec",
            }.items():
                (sources / name).write_text("package opengpu\n" + source)
            expected = ["opengpu.FirstSpec", "opengpu.SecondSpec"]
            for name in ("Support.scala", "BaseSpec.scala"):
                self.assertEqual(changed_suites(root, ["src/test/scala/opengpu/" + name]), expected)
            self.assertEqual(changed_suites(root, ["src/test/scala/opengpu/Profiles.scala"]),
                             ["opengpu.FirstSpec", "opengpu.OtherSpec", "opengpu.SecondSpec"])
            self.assertEqual(changed_suites(root, ["src/main/scala/opengpu/Alu.scala"]), ["opengpu.AluSpec"])
            for paths in ([], ["docs/foo.md"], ["src/test/scala/opengpu/DeletedSpec.scala"]):
                self.assertEqual(changed_suites(root, paths), [])

    def test_full_host_qualification_is_local_for_direct_and_fixture_changes(self):
        root = Path(__file__).resolve().parents[1]
        local = "opengpu.graphics.NativeFourEngineDefaultHostAxiSpec"
        for changed in ("src/test/scala/opengpu/graphics/NativeFourEngineDefaultHostAxiSpec.scala",
                        "src/test/scala/opengpu/graphics/NativeFourEngineHostBaseSpec.scala",
                        "src/test/scala/opengpu/testutil/GpuSim.scala"):
            self.assertNotIn(local, changed_suites(root, [changed]))
        self.assertIn("opengpu.testutil.GpuSimSpec",
                      changed_suites(root, ["scripts/ci_local_only_suites.txt"]))

    def test_vertex_matrix_is_local_while_ordinary_inherited_cases_remain_required(self):
        root = Path(__file__).resolve().parents[1]
        actual = changed_suites(root, ["src/test/scala/opengpu/graphics/VertexShaderStageSpec.scala"])
        self.assertIn("opengpu.graphics.VertexShaderStageSpec", actual)
        self.assertNotIn("opengpu.graphics.VertexShaderTextureMatrixSpec", actual)

    def test_early_render_qualification_stays_local_while_shader_owner_checks_remain_required(self):
        root = Path(__file__).resolve().parents[1]
        local = "opengpu.graphics.EarlyDepthRenderQualificationSpec"
        for changed in ("src/test/scala/opengpu/graphics/EarlyDepthRenderQualificationSpec.scala",
                        "src/test/scala/opengpu/testutil/GpuSim.scala"):
            self.assertNotIn(local, changed_suites(root, [changed]))
        self.assertIn("opengpu.graphics.EarlyDepthFragmentShaderStageSpec", changed_suites(root,
                      ["src/test/scala/opengpu/graphics/EarlyDepthFragmentShaderStageSpec.scala"]))

    def test_fragment_matrix_is_local_while_ordinary_inherited_cases_remain_required(self):
        root = Path(__file__).resolve().parents[1]
        actual = changed_suites(root, ["src/test/scala/opengpu/graphics/FragmentShaderStageSpec.scala"])
        self.assertIn("opengpu.graphics.FragmentShaderStageSpec", actual)
        self.assertNotIn("opengpu.graphics.FragmentShaderTextureMatrixSpec", actual)

    def test_opt_in_workload_benchmark_is_not_an_empty_ci_suite(self):
        root = Path(__file__).resolve().parents[1]
        for changed in ("src/test/scala/opengpu/system/GpuWorkloadSpec.scala",
                        "src/test/scala/opengpu/system/GpuHostTestSupport.scala"):
            self.assertNotIn("opengpu.system.GpuWorkloadSpec", changed_suites(root, [changed]))

    def test_binned_capacity_is_local_while_ordinary_host_cases_remain_required(self):
        root = Path(__file__).resolve().parents[1]
        actual = changed_suites(root, ["src/test/scala/opengpu/graphics/BinnedTileHostAxiSpec.scala"])
        self.assertIn("opengpu.graphics.BinnedTileHostAxiSpec", actual)
        self.assertNotIn("opengpu.graphics.BinnedTileResourceQualificationSpec", actual)

    def test_native_pipeline_qualification_keeps_bounded_ci_feedback(self):
        root = Path(__file__).resolve().parents[1]
        required = {
            "opengpu.graphics.NativeRenderTargetExtentSpec",
            "opengpu.graphics.NativeBinnedGraphicsJobReaderSpec",
            "opengpu.graphics.StagedTypedImageAdmissionSpec",
        }
        for changed in (
            "src/main/scala/opengpu/graphics/MemoryNativeBinnedGraphicsPipeline.scala",
            "src/test/scala/opengpu/graphics/MemoryNativeBinnedGraphicsPipelineSpec.scala",
        ):
            with self.subTest(changed=changed):
                actual = set(changed_suites(root, [changed]))
                self.assertNotIn("opengpu.graphics.MemoryNativeBinnedGraphicsPipelineSpec", actual)
                self.assertTrue(required <= actual, actual)

    def test_recent_prs_cannot_fall_back_to_only_smoke_tests(self):
        root = Path(__file__).resolve().parents[1]
        for ci_input in (".github/workflows/scala_test.yml",
                         ".github/actions/setup-simulation/action.yml", "scripts/prefetch_scala_bridge.sh",
                         "scripts/ci_telemetry_coverage.py"):
            self.assertIn("opengpu.testutil.GpuSimSpec", changed_suites(root, [ci_input]))
        for filename, required in (
            ("Fp32FragmentInterpolatorSpec.scala", {"Fp32FragmentInterpolatorSpec"}),
            ("NativeDefaultProfileHostAxiSpec.scala", {"NativeDefaultProfileHostAxiSpec", "NativeDefaultStructuredSimtHostAxiSpec", "NativeFullVaryingInterpolationHostAxiSpec"}),
            ("NativeTypedGraphicsHostAxiSpec.scala", {"NativeTypedGraphicsHostAxiSpec", "NativeUniformRendererFourCuHostAxiSpec"}),
        ):
            with self.subTest(filename=filename):
                actual = changed_suites(root, ["src/test/scala/opengpu/graphics/" + filename])
                self.assertTrue(required <= {name.rsplit(".", 1)[1] for name in actual}, actual)


if __name__ == "__main__":
    unittest.main()
