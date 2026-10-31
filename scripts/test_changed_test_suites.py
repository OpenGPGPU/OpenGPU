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

    def test_counter_system_qualification_keeps_bounded_ci_feedback(self):
        root = Path(__file__).resolve().parents[1]
        selected = set(changed_suites(root,
            ["src/test/scala/opengpu/system/GpuSystemAtomicCounterSpec.scala"]))
        self.assertNotIn("opengpu.system.GpuSystemAtomicCounterSpec", selected)
        self.assertTrue({"opengpu.dispatch.AtomicCounterGridDispatchSpec",
                         "opengpu.dispatch.AtomicCounterCommandOwnershipSpec",
                         "opengpu.core.memory.AtomicCounterVectorCuServiceSpec"} <= selected)

    def test_counter_host_qualification_keeps_bounded_ci_feedback(self):
        root = Path(__file__).resolve().parents[1]
        selected = set(changed_suites(root,
            ["src/test/scala/opengpu/system/GpuHostAtomicCounterCommandSpec.scala"]))
        self.assertNotIn("opengpu.system.GpuHostAtomicCounterCommandSpec", selected)
        self.assertTrue({"opengpu.system.GpuHostAxiSpec",
                         "opengpu.graphics.GpuCommandMmioAtomicCounterSpec"} <= selected)

    def test_compute_texture_host_qualification_keeps_bounded_feedback(self):
        root = Path(__file__).resolve().parents[1]
        selected = set(changed_suites(root,
            ["src/test/scala/opengpu/graphics/GpuHostComputeTextureCommandSpec.scala"]))
        self.assertNotIn("opengpu.graphics.GpuHostComputeTextureCommandSpec", selected)
        self.assertTrue({"opengpu.system.GpuHostAxiSpec",
                         "opengpu.graphics.GpuCommandMmioComputeTextureSpec"} <= selected)

    def test_fragment_full_qualification_stays_local_with_real_smoke_coverage(self):
        root = Path(__file__).resolve().parents[1]
        selected = set(changed_suites(root, ["src/test/scala/opengpu/graphics/FragmentShaderStageSpec.scala"]))
        self.assertNotIn("opengpu.graphics.FragmentShaderStageQualificationSpec", selected)
        self.assertTrue({"opengpu.graphics.FragmentShaderStageSpec",
                         "opengpu.graphics.FragmentInputCoalescerSpec"} <= selected)

    def test_geometry_fragment_qualification_selects_bounded_admission(self):
        root = Path(__file__).resolve().parents[1]
        selected = set(changed_suites(root, ["src/test/scala/opengpu/graphics/GeometryFragmentPipelineSpec.scala"]))
        self.assertNotIn("opengpu.graphics.GeometryFragmentPipelineSpec", selected)
        self.assertIn("opengpu.graphics.GeometryFragmentAdmissionSpec", selected)

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


    def test_local_geometry_raster_keeps_admission_in_ci(self):
        root = Path(__file__).resolve().parents[1]
        for path in ("src/main/scala/opengpu/system/GeometryPrimitiveRasterClient.scala",
                     "src/test/scala/opengpu/system/GeometryPrimitiveRasterClientSpec.scala"):
            with self.subTest(path=path):
                actual = set(changed_suites(root, [path]))
                self.assertNotIn("opengpu.system.GeometryPrimitiveRasterClientSpec", actual)
                self.assertIn("opengpu.system.GeometryRasterAdmissionSpec", actual)


    def test_local_geometry_draw_pipeline_keeps_bounded_checks(self):
        root = Path(__file__).resolve().parents[1]
        for path in ("src/main/scala/opengpu/system/GeometryFragmentDrawPipeline.scala",
                     "src/test/scala/opengpu/graphics/GeometryFragmentDrawPipelineSpec.scala"):
            with self.subTest(path=path):
                actual = set(changed_suites(root, [path]))
                self.assertNotIn("opengpu.graphics.GeometryFragmentDrawPipelineSpec", actual)
                self.assertTrue({"opengpu.graphics.GeometryFragmentDrawStreamSpec",
                                 "opengpu.graphics.GeometryFragmentAdmissionSpec"} <= actual)


    def test_vertex_bin_changes_keep_bounded_record_and_producer_checks(self):
        root = Path(__file__).resolve().parents[1]
        for path in ("src/main/scala/opengpu/graphics/VertexBinPipeline.scala",
                     "src/test/scala/opengpu/graphics/VertexBinPipelineSpec.scala"):
            with self.subTest(path=path):
                actual = set(changed_suites(root, [path]))
                self.assertNotIn("opengpu.graphics.VertexBinPipelineSpec", actual)
                self.assertTrue({"opengpu.graphics.WindowPrimitiveRecordsSpec",
                                 "opengpu.graphics.WindowPrimitiveBinProducerSpec"} <= actual)


    def test_geometry_window_bin_pass_keeps_bounded_admission(self):
        root = Path(__file__).resolve().parents[1]
        for path in ("src/main/scala/opengpu/system/GeometryWindowBinPass.scala",
                     "src/test/scala/opengpu/graphics/GeometryWindowBinPassSpec.scala"):
            with self.subTest(path=path):
                actual = set(changed_suites(root, [path]))
                self.assertNotIn("opengpu.graphics.GeometryWindowBinPassSpec", actual)
                self.assertIn("opengpu.graphics.GeometryWindowBinAdmissionSpec", actual)


    def test_controlled_isoline_shared_renderer_uses_bounded_checks(self):
        root = Path(__file__).resolve().parents[1]
        for path in ("src/main/scala/opengpu/system/ControlledIsolineSharedCuRenderer.scala",
                     "src/test/scala/opengpu/system/ControlledIsolineSharedCuRendererQualificationSpec.scala"):
            with self.subTest(path=path):
                actual = set(changed_suites(root, [path]))
                self.assertIn("opengpu.system.ControlledIsolineSharedCuRendererAdmissionSpec", actual)
                self.assertIn("opengpu.graphics.TessellationControlledIsolineRendererSpec", actual)
                self.assertIn("opengpu.system.IsolineGeometryCuExecutorSpec", actual)
                self.assertNotIn("opengpu.system.ControlledIsolineSharedCuRendererQualificationSpec", actual)

    def test_isoline_patch_shared_cu_uses_bounded_checks(self):
        root = Path(__file__).resolve().parents[1]
        for path in ("src/main/scala/opengpu/system/IsolinePatchSharedCu.scala",
                     "src/test/scala/opengpu/system/IsolinePatchSharedCuQualificationSpec.scala"):
            with self.subTest(path=path):
                actual = set(changed_suites(root, [path]))
                self.assertIn("opengpu.system.IsolinePatchSharedCuAdmissionSpec", actual)
                self.assertIn("opengpu.graphics.TessellationIsolinePatchEvaluationSpec", actual)
                self.assertIn("opengpu.system.IsolineGeometryCuExecutorSpec", actual)
                self.assertNotIn("opengpu.system.IsolinePatchSharedCuQualificationSpec", actual)

    def test_isoline_patch_pipeline_selects_cancellation_coverage(self):
        root = Path(__file__).resolve().parents[1]
        actual = set(changed_suites(root, ["src/main/scala/opengpu/graphics/TessellationIsolinePatchPipeline.scala"]))
        self.assertIn("opengpu.graphics.TessellationIsolinePatchPipelineSpec", actual)
        self.assertIn("opengpu.graphics.TessellationIsolinePatchCancellationSpec", actual)

    def test_geometry_replay_raster_selects_graphics_reference_test(self):
        root = Path(__file__).resolve().parents[1]
        actual = set(changed_suites(root, ["src/main/scala/opengpu/system/GeometryReplayRasterClient.scala"]))
        self.assertIn("opengpu.graphics.GeometryReplayRasterClientSpec", actual)


    def test_geometry_replay_fragment_keeps_bounded_admission(self):
        root = Path(__file__).resolve().parents[1]
        for path in ("src/main/scala/opengpu/system/GeometryReplayFragmentPipeline.scala",
                     "src/test/scala/opengpu/graphics/GeometryReplayFragmentPipelineSpec.scala"):
            with self.subTest(path=path):
                actual = set(changed_suites(root, [path]))
                self.assertNotIn("opengpu.graphics.GeometryReplayFragmentPipelineSpec", actual)
                self.assertIn("opengpu.graphics.GeometryReplayFragmentAdmissionSpec", actual)


    def test_prepared_geometry_keeps_bounded_fragment_checks(self):
        root = Path(__file__).resolve().parents[1]
        cases = {
            "src/test/scala/opengpu/graphics/PreparedGeometryFragmentPipelineSpec.scala":
                {"opengpu.graphics.FragmentShaderStageSpec", "opengpu.graphics.GeometryReplayFragmentAdmissionSpec", "opengpu.graphics.FragmentInputCoalescerSpec"},
            "src/main/scala/opengpu/graphics/VertexFragmentPipeline.scala":
                {"opengpu.graphics.FragmentShaderStageSpec", "opengpu.graphics.GeometryFragmentAdmissionSpec", "opengpu.graphics.FragmentInputCoalescerSpec"},
        }
        for path, expected in cases.items():
            with self.subTest(path=path):
                actual = set(changed_suites(root, [path]))
                self.assertTrue(expected <= actual)
                self.assertNotIn("opengpu.graphics.PreparedGeometryFragmentPipelineSpec", actual)
                self.assertNotIn("opengpu.graphics.VertexFragmentPipelineSpec", actual)


    def test_typed_geometry_image_keeps_bounded_checks(self):
        root = Path(__file__).resolve().parents[1]
        for path in ("src/main/scala/opengpu/graphics/TypedTileImagePipeline.scala",
                     "src/main/scala/opengpu/graphics/TypedTileRenderPipeline.scala",
                     "src/test/scala/opengpu/graphics/TypedGeometryImageQualificationSpec.scala"):
            with self.subTest(path=path):
                actual = set(changed_suites(root, [path]))
                self.assertTrue({"opengpu.graphics.TypedTileImageTransferSpec",
                                 "opengpu.graphics.FragmentShaderStageSpec"} <= actual)
                self.assertNotIn("opengpu.graphics.TypedGeometryImageQualificationSpec", actual)
                self.assertNotIn("opengpu.graphics.TypedTileImagePipelineSpec", actual)
                self.assertNotIn("opengpu.graphics.TypedTileRenderPipelineSpec", actual)


if __name__ == "__main__":
    unittest.main()
