#!/usr/bin/env python3
from pathlib import Path
import tempfile
import unittest

from changed_test_suites import changed_suites


class ChangedTestSuitesTest(unittest.TestCase):
    def test_native_domain_image_selects_bounded_tests(self):
        root = Path(__file__).resolve().parents[1]
        selected = set(changed_suites(root, ["src/main/scala/opengpu/system/TessellationNativeDomainJobImage.scala"]))
        self.assertTrue({"opengpu.system.TessellationNativeDomainJobImageAdmissionSpec",
                         "opengpu.system.TessellationNativeDomainJobImageElaborationSpec"} <= selected)

    def test_triangle_native_job_image_selects_bounded_checks(self):
        root = Path(__file__).resolve().parents[1]
        for path in ("src/main/scala/opengpu/system/TessellationNativeTriangleJobImage.scala",
                     "src/test/scala/opengpu/graphics/TessellationNativeTriangleJobImageSharedSpec.scala"):
            with self.subTest(path=path):
                selected = set(changed_suites(root, [path]))
                self.assertTrue({"opengpu.graphics.TessellationNativeTriangleJobImageAdmissionSpec",
                                 "opengpu.graphics.TessellationNativeTriangleJobReaderSpec",
                                 "opengpu.system.TessellationNativeJobMemoryFabricSpec",
                                 "opengpu.graphics.TessellationNativeTriangleSequenceImageAdmissionSpec"} <= selected)
                self.assertNotIn("opengpu.graphics.TessellationNativeTriangleJobImageSharedSpec", selected)

    def test_triangle_native_job_dependencies_select_reader_and_abi(self):
        root = Path(__file__).resolve().parents[1]
        for path in ("src/main/scala/opengpu/system/TessellationNativeTriangleJobReader.scala",
                     "src/main/scala/opengpu/elaboration/EmitTessellationNativeTriangleJobAbi.scala",
                     "src/main/scala/opengpu/system/TessellationNativeTriangleSequenceReader.scala",
                     "driver/opengpu_native_tessellation_triangle_job_abi.h",
                     "driver/tests/opengpu_tessellation_triangle_job_abi_test.c",
                     "docs/abi/native-tessellation-triangle-job-v2.json"):
            with self.subTest(path=path):
                selected = set(changed_suites(root, [path]))
                self.assertTrue({"opengpu.graphics.TessellationNativeTriangleJobReaderSpec",
                                 "opengpu.system.TessellationNativeTriangleJobAbiSpec"} <= selected)

    def test_triangle_native_image_qualification_keeps_bounded_coverage(self):
        root = Path(__file__).resolve().parents[1]
        for path in ("src/main/scala/opengpu/system/TessellationNativeTriangleSequenceImage.scala",
                     "src/test/scala/opengpu/graphics/TessellationNativeTriangleSequenceImageSharedSpec.scala"):
            with self.subTest(path=path):
                selected = set(changed_suites(root, [path]))
                self.assertTrue({"opengpu.graphics.TessellationNativeTriangleSequenceImageAdmissionSpec",
                                 "opengpu.system.TessellationNativeTriangleSequenceReaderSpec",
                                 "opengpu.system.TessellationNativeRecordMemoryFabricSpec"} <= selected)
                self.assertNotIn("opengpu.graphics.TessellationNativeTriangleSequenceImageSharedSpec", selected)

    def test_triangle_native_sequence_artifacts_select_abi_check(self):
        root = Path(__file__).resolve().parents[1]
        for path in ("driver/opengpu_native_tessellation_triangle_sequence_abi.h",
                     "docs/abi/native-tessellation-triangle-sequence-v2.json"):
            with self.subTest(path=path):
                self.assertIn("opengpu.system.TessellationNativeTriangleSequenceAbiSpec", changed_suites(root, [path]))

    def test_triangle_native_sequence_dependencies_select_reader_and_abi(self):
        root = Path(__file__).resolve().parents[1]
        for path in ("src/main/scala/opengpu/system/TessellationNativeTriangleSequenceReader.scala",
                     "src/main/scala/opengpu/elaboration/EmitTessellationNativeTriangleSequenceAbi.scala",
                     "src/main/scala/opengpu/system/TessellationTrianglePatchSequence.scala"):
            with self.subTest(path=path):
                selected = set(changed_suites(root, [path]))
                self.assertTrue({"opengpu.system.TessellationNativeTriangleSequenceReaderSpec",
                                 "opengpu.system.TessellationNativeTriangleSequenceAbiSpec"} <= selected)

    def test_triangle_translated_qualification_keeps_bounded_coverage(self):
        root = Path(__file__).resolve().parents[1]
        path = "src/test/scala/opengpu/graphics/TessellationTriangleTranslatedSequenceImageSharedSpec.scala"
        selected = set(changed_suites(root, [path]))
        self.assertTrue({"opengpu.graphics.TessellationTriangleTranslatedSequenceImageAdmissionSpec",
                         "opengpu.graphics.TessellationTriangleSequenceImageAdmissionSpec",
                         "opengpu.system.TessellationInputMemoryFabricSpec",
                         "opengpu.system.TessellationTrianglePatchSequenceSpec"} <= selected)
        self.assertNotIn("opengpu.graphics.TessellationTriangleTranslatedSequenceImageSharedSpec", selected)

    def test_triangle_translated_sequence_dependencies_select_tests(self):
        root = Path(__file__).resolve().parents[1]
        for name in ("TessellationTriangleTranslatedSequenceImage", "TessellationInputMemoryFabric"):
            with self.subTest(name=name):
                selected = changed_suites(root, [f"src/main/scala/opengpu/system/{name}.scala"])
                self.assertIn("opengpu.graphics.TessellationTriangleTranslatedSequenceImageAdmissionSpec", selected)
                self.assertIn("opengpu.system.TessellationInputMemoryFabricSpec", selected)

    def test_triangle_sequence_image_qualification_keeps_bounded_coverage(self):
        root = Path(__file__).resolve().parents[1]
        for path in ("src/main/scala/opengpu/system/TessellationTriangleSequenceImage.scala",
                     "src/test/scala/opengpu/graphics/TessellationTriangleSequenceImageSharedSpec.scala"):
            with self.subTest(path=path):
                selected = set(changed_suites(root, [path]))
                self.assertTrue({"opengpu.graphics.TessellationTriangleSequenceImageAdmissionSpec",
                                 "opengpu.system.TessellationTrianglePatchSequenceSpec"} <= selected)
                self.assertNotIn("opengpu.graphics.TessellationTriangleSequenceImageSharedSpec", selected)

    def test_triangle_patch_sequence_dependencies_select_test(self):
        root = Path(__file__).resolve().parents[1]
        for name in ("TessellationSystemTriangleInputImage", "TessellationTrianglePatchSequence"):
            with self.subTest(name=name):
                selected = changed_suites(root, [f"src/main/scala/opengpu/system/{name}.scala"])
                self.assertIn("opengpu.system.TessellationTrianglePatchSequenceSpec", selected)

    def test_triangle_input_image_qualification_selects_bounded_coverage(self):
        root = Path(__file__).resolve().parents[1]
        for path in ("src/main/scala/opengpu/system/TessellationSystemTriangleInputImage.scala",
                     "src/test/scala/opengpu/graphics/TessellationSystemTriangleInputImageSharedSpec.scala"):
            with self.subTest(path=path):
                selected = set(changed_suites(root, [path]))
                self.assertTrue({"opengpu.graphics.TessellationSystemTriangleInputImageAdmissionSpec",
                                 "opengpu.system.TessellationPatchInputDmaSpec"} <= selected)
                self.assertNotIn("opengpu.graphics.TessellationSystemTriangleInputImageSharedSpec", selected)

    def test_triangle_image_qualification_selects_bounded_coverage(self):
        root = Path(__file__).resolve().parents[1]
        for path in ("src/main/scala/opengpu/system/TessellationSystemTriangleImagePipeline.scala",
                     "src/test/scala/opengpu/graphics/TessellationSystemTriangleImageSharedSystemSpec.scala"):
            with self.subTest(path=path):
                selected = set(changed_suites(root, [path]))
                self.assertTrue({"opengpu.graphics.TessellationSystemTriangleImageAdmissionSpec",
                                 "opengpu.graphics.GeometryFragmentTessellationRouterSpec",
                                 "opengpu.graphics.TessellationTrianglePrimitiveMapperSpec"} <= selected)
                self.assertNotIn("opengpu.graphics.TessellationSystemTriangleImageSharedSystemSpec", selected)

    def test_triangle_bin_qualification_keeps_bounded_coverage(self):
        root = Path(__file__).resolve().parents[1]
        for path in ("src/main/scala/opengpu/system/TessellationSystemTriangleBinPass.scala",
                     "src/test/scala/opengpu/system/TessellationSystemTriangleBinPassSpec.scala"):
            with self.subTest(path=path):
                selected = set(changed_suites(root, [path]))
                self.assertTrue({"opengpu.graphics.TessellationTrianglePrimitiveMapperSpec",
                                 "opengpu.graphics.GeometryWindowBinAdmissionSpec",
                                 "opengpu.graphics.WindowPrimitiveBinReplayPassSpec"} <= selected)
                self.assertNotIn("opengpu.system.TessellationSystemTriangleBinPassSpec", selected)

    def test_triangle_system_viewport_dependencies_select_bounded_checks(self):
        root = Path(__file__).resolve().parents[1]
        paths = ["src/main/scala/opengpu/system/TessellationCuTriangleSystemClient.scala",
                 "src/main/scala/opengpu/system/TessellationSystemTriangleViewport.scala",
                 "src/main/scala/opengpu/graphics/TessellationTrianglePrimitiveMapper.scala",
                 "src/test/scala/opengpu/system/TessellationSystemTriangleViewportSpec.scala"]
        for path in paths:
            with self.subTest(path=path):
                selected = changed_suites(root, [path])
                self.assertIn("opengpu.graphics.TessellationTrianglePrimitiveMapperSpec", selected)
                self.assertNotIn("opengpu.system.TessellationSystemTriangleViewportSpec", selected)

    def test_triangle_control_cu_dependencies_keep_bounded_coverage(self):
        root = Path(__file__).resolve().parents[1]
        paths = [f"src/main/scala/opengpu/graphics/{name}.scala" for name in (
            "TessellationCuTrianglePatch", "TessellationControlWarpScheduler",
            "TessellationTriangleGenerator", "TessellationTriangleEvaluation")]
        paths += ["src/main/scala/opengpu/system/TessellationCuTriangleSharedEvaluator.scala",
                  "src/test/scala/opengpu/system/TessellationCuTriangleSharedEvaluatorSpec.scala"]
        for path in paths:
            with self.subTest(path=path):
                selected = changed_suites(root, [path])
                self.assertIn("opengpu.graphics.TessellationCuTriangleAdmissionSpec", selected)
                self.assertNotIn("opengpu.system.TessellationCuTriangleSharedEvaluatorSpec", selected)

    def test_triangle_patch_cu_dependencies_and_local_qualification(self):
        root = Path(__file__).resolve().parents[1]
        paths = [f"src/main/scala/opengpu/graphics/{name}.scala" for name in (
            "TessellationPatchContext", "TessellationTrianglePatchPipeline", "TessellationTrianglePatchEvaluation")]
        paths += [f"src/main/scala/opengpu/system/{name}.scala" for name in (
            "TessellationTriangleCuEvaluator", "TessellationTrianglePatchCuEvaluation",
            "IsolinePatchInputReader", "IsolineGeometryCuExecutor")]
        paths += ["src/test/scala/opengpu/system/TessellationTrianglePatchCuQualificationSpec.scala"]
        for path in paths:
            with self.subTest(path=path):
                selected = changed_suites(root, [path])
                self.assertIn("opengpu.system.TessellationTrianglePatchCuEvaluationSpec", selected)
                self.assertNotIn("opengpu.system.TessellationTrianglePatchCuQualificationSpec", selected)

    def test_triangle_patch_evaluation_dependencies_select_test(self):
        root = Path(__file__).resolve().parents[1]
        for name in ("TessellationPatchContext", "TessellationTrianglePatchPipeline", "TessellationTriangleGenerator",
                     "TessellationTriangleEvaluation", "TessellationTrianglePatchEvaluation"):
            with self.subTest(name=name):
                selected = changed_suites(root, [f"src/main/scala/opengpu/graphics/{name}.scala"])
                self.assertIn("opengpu.graphics.TessellationTrianglePatchEvaluationSpec", selected)

    def test_triangle_cu_evaluator_dependencies_select_test(self):
        root = Path(__file__).resolve().parents[1]
        for name in ("IsolinePatchInputReader", "IsolineGeometryCuExecutor", "TessellationTriangleCuEvaluator"):
            with self.subTest(name=name):
                selected = changed_suites(root, [f"src/main/scala/opengpu/system/{name}.scala"])
                self.assertIn("opengpu.system.TessellationTriangleCuEvaluatorSpec", selected)

    def test_triangle_evaluation_dependencies_select_pipeline_test(self):
        root = Path(__file__).resolve().parents[1]
        for name in ("TessellationLevels", "TessellationEdgeCoordinate", "TessellationTriangleBoundary",
                     "TessellationTriangleInterior", "TessellationTriangleStrip", "TessellationTriangleGenerator",
                     "TessellationTriangleEvaluation", "TessellationTriangleEvaluationPipeline"):
            with self.subTest(name=name):
                selected = changed_suites(root, [f"src/main/scala/opengpu/graphics/{name}.scala"])
                self.assertIn("opengpu.graphics.TessellationTriangleEvaluationPipelineSpec", selected)

    def test_triangle_generator_dependencies_select_composed_test(self):
        root = Path(__file__).resolve().parents[1]
        for name in ("TessellationLevels", "TessellationEdgeCoordinate", "TessellationTriangleBoundary",
                     "TessellationTriangleInterior", "TessellationTriangleStrip", "TessellationTriangleGenerator"):
            with self.subTest(name=name):
                selected = changed_suites(root, [f"src/main/scala/opengpu/graphics/{name}.scala"])
                self.assertIn("opengpu.graphics.TessellationTriangleGeneratorSpec", selected)

    def test_triangle_boundary_dependencies_select_its_test(self):
        root = Path(__file__).resolve().parents[1]
        for name in ("TessellationLevels", "TessellationEdgeCoordinate", "TessellationTriangleBoundary"):
            with self.subTest(name=name):
                selected = changed_suites(root, [f"src/main/scala/opengpu/graphics/{name}.scala"])
                self.assertIn("opengpu.graphics.TessellationTriangleBoundarySpec", selected)

    def test_triangle_interior_dependencies_select_its_test(self):
        root = Path(__file__).resolve().parents[1]
        for name in ("TessellationLevels", "TessellationEdgeCoordinate", "TessellationTriangleInterior"):
            with self.subTest(name=name):
                selected = changed_suites(root, [f"src/main/scala/opengpu/graphics/{name}.scala"])
                self.assertIn("opengpu.graphics.TessellationTriangleInteriorSpec", selected)

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


    def test_controlled_isoline_shared_viewport_uses_bounded_checks(self):
        root = Path(__file__).resolve().parents[1]
        for path in ("src/main/scala/opengpu/system/ControlledIsolineSharedCuViewport.scala",
                     "src/test/scala/opengpu/system/ControlledIsolineSharedCuViewportQualificationSpec.scala"):
            with self.subTest(path=path):
                actual = set(changed_suites(root, [path]))
                self.assertIn("opengpu.system.ControlledIsolineSharedCuViewportAdmissionSpec", actual)
                self.assertIn("opengpu.system.ControlledIsolineSharedCuClipAdmissionSpec", actual)
                self.assertIn("opengpu.graphics.Fp32ViewportStageSpec", actual)
                self.assertNotIn("opengpu.system.ControlledIsolineSharedCuViewportQualificationSpec", actual)

    def test_controlled_isoline_window_bin_pass_keeps_bounded_admission(self):
        root = Path(__file__).resolve().parents[1]
        for path in ("src/main/scala/opengpu/system/ControlledIsolineWindowBinPass.scala",
                     "src/test/scala/opengpu/system/ControlledIsolineWindowBinPassSpec.scala"):
            with self.subTest(path=path):
                actual = set(changed_suites(root, [path]))
                self.assertNotIn("opengpu.system.ControlledIsolineWindowBinPassSpec", actual)
                self.assertIn("opengpu.system.ControlledIsolineWindowBinAdmissionSpec", actual)

    def test_controlled_isoline_shared_clip_uses_bounded_checks(self):
        root = Path(__file__).resolve().parents[1]
        for path in ("src/main/scala/opengpu/system/ControlledIsolineSharedCuClip.scala",
                     "src/test/scala/opengpu/system/ControlledIsolineSharedCuClipQualificationSpec.scala"):
            with self.subTest(path=path):
                actual = set(changed_suites(root, [path]))
                self.assertIn("opengpu.system.ControlledIsolineSharedCuClipAdmissionSpec", actual)
                self.assertIn("opengpu.system.ControlledIsolineSharedCuRendererAdmissionSpec", actual)
                self.assertIn("opengpu.graphics.PrimitiveClipStageSpec", actual)
                self.assertNotIn("opengpu.system.ControlledIsolineSharedCuClipQualificationSpec", actual)

    def test_controlled_isoline_shared_renderer_uses_bounded_checks(self):
        root = Path(__file__).resolve().parents[1]
        for path in ("src/main/scala/opengpu/system/ControlledIsolineSharedCuRenderer.scala",
                     "src/test/scala/opengpu/system/ControlledIsolineSharedCuRendererQualificationSpec.scala"):
            with self.subTest(path=path):
                actual = set(changed_suites(root, [path]))
                self.assertIn("opengpu.system.ControlledIsolineSharedCuRendererAdmissionSpec", actual)
                self.assertIn("opengpu.system.IsolinePatchInputReaderSpec", actual)
                self.assertIn("opengpu.graphics.TessellationControlledIsolineRendererSpec", actual)
                self.assertIn("opengpu.system.IsolineGeometryCuExecutorSpec", actual)
                self.assertNotIn("opengpu.system.ControlledIsolineSharedCuRendererQualificationSpec", actual)

    def test_tessellation_completion_abi_selects_its_spec(self):
        root = Path(__file__).resolve().parents[1]
        for path in ("src/main/scala/opengpu/elaboration/EmitTessellationNativeCompletionAbi.scala",
                     "src/main/scala/opengpu/system/TessellationNativeCompletionInfo.scala",
                     "driver/opengpu_native_tessellation_completion_abi.h",
                     "driver/opengpu_tessellation_completion.h",
                     "driver/tests/opengpu_tessellation_completion_test.c",
                     "driver/tests/opengpu_tessellation_completion_kernel_test.c",
                     "driver/tests/tessellation_abi_stubs/linux/types.h",
                     "scripts/test_tessellation_completion_kernel.sh",
                     "docs/abi/native-tessellation-completion-v1.json"):
            with self.subTest(path=path):
                self.assertIn("opengpu.system.TessellationNativeCompletionInfoSpec", changed_suites(root, [path]))

    def test_tessellation_native_job_abi_selects_its_spec(self):
        root = Path(__file__).resolve().parents[1]
        for path in ("src/main/scala/opengpu/system/TessellationNativeJobReader.scala",
                     "src/main/scala/opengpu/system/TessellationNativeSequenceReader.scala",
                     "src/main/scala/opengpu/graphics/NativeBinnedGraphicsDescriptors.scala",
                     "src/main/scala/opengpu/elaboration/EmitTessellationNativeJobAbi.scala",
                     "driver/opengpu_native_tessellation_job_abi.h",
                     "driver/tests/opengpu_tessellation_job_abi_test.c",
                     "docs/abi/native-tessellation-job-v1.json"):
            with self.subTest(path=path):
                actual = set(changed_suites(root, [path]))
                self.assertIn("opengpu.system.TessellationNativeJobAbiSpec", actual)

    def test_tessellation_native_job_image_selects_bounded_checks(self):
        root = Path(__file__).resolve().parents[1]
        actual = set(changed_suites(root, ["src/main/scala/opengpu/system/TessellationNativeJobImage.scala"]))
        self.assertIn("opengpu.graphics.TessellationNativeJobImageAdmissionSpec", actual)
        self.assertIn("opengpu.graphics.TessellationNativeJobReaderSpec", actual)
        self.assertIn("opengpu.system.TessellationNativeJobMemoryFabricSpec", actual)
        self.assertNotIn("opengpu.graphics.TessellationNativeJobImageSharedSpec", actual)

    def test_tessellation_native_job_fabric_selects_its_spec(self):
        root = Path(__file__).resolve().parents[1]
        actual = set(changed_suites(root, ["src/main/scala/opengpu/system/TessellationNativeJobMemoryFabric.scala"]))
        self.assertIn("opengpu.system.TessellationNativeJobMemoryFabricSpec", actual)

    def test_tessellation_host_axi_selects_its_spec(self):
        root = Path(__file__).resolve().parents[1]
        self.assertIn("opengpu.system.TessellationHostAxiCommandSpec", set(changed_suites(root, ["src/main/scala/opengpu/system/GpuHostAxi.scala"])))

    def test_tessellation_host_shader_router_selects_its_spec(self):
        root = Path(__file__).resolve().parents[1]
        self.assertIn("opengpu.system.TessellationHostShaderRouterSpec", set(changed_suites(root, ["src/main/scala/opengpu/system/TessellationHostShaderRouter.scala"])))

    def test_tessellation_host_command_selects_its_spec(self):
        root = Path(__file__).resolve().parents[1]
        for path in ("src/main/scala/opengpu/graphics/TileBinHostCommandPort.scala",
                     "src/main/scala/opengpu/command/GpuCommandRouter.scala"):
            with self.subTest(path=path):
                self.assertIn("opengpu.graphics.TessellationHostCommandPortSpec", set(changed_suites(root, [path])))

    def test_tessellation_host_memory_fabric_selects_its_spec(self):
        root = Path(__file__).resolve().parents[1]
        actual = set(changed_suites(root, ["src/main/scala/opengpu/system/TessellationHostMemoryFabric.scala"]))
        self.assertIn("opengpu.system.TessellationHostMemoryFabricSpec", actual)

    def test_tessellation_host_payload_owner_selects_its_spec(self):
        root = Path(__file__).resolve().parents[1]
        self.assertIn("opengpu.system.TessellationHostPayloadOwnerSpec", set(changed_suites(root, ["src/main/scala/opengpu/system/TessellationHostPayloadOwner.scala"])))

    def test_tessellation_native_job_reader_selects_its_spec(self):
        root = Path(__file__).resolve().parents[1]
        actual = set(changed_suites(root, ["src/main/scala/opengpu/system/TessellationNativeJobReader.scala"]))
        self.assertIn("opengpu.graphics.TessellationNativeJobReaderSpec", actual)

    def test_tessellation_native_image_selects_bounded_checks(self):
        root = Path(__file__).resolve().parents[1]
        actual = set(changed_suites(root, ["src/main/scala/opengpu/system/TessellationNativeSequenceImage.scala"]))
        self.assertIn("opengpu.graphics.TessellationNativeSequenceImageAdmissionSpec", actual)
        self.assertIn("opengpu.system.TessellationNativeSequenceReaderSpec", actual)
        self.assertIn("opengpu.system.TessellationNativeRecordMemoryFabricSpec", actual)
        self.assertNotIn("opengpu.graphics.TessellationNativeSequenceImageSharedSpec", actual)

    def test_tessellation_translated_image_selects_bounded_checks(self):
        root = Path(__file__).resolve().parents[1]
        actual = set(changed_suites(root, ["src/main/scala/opengpu/system/TessellationTranslatedSequenceImage.scala"]))
        self.assertIn("opengpu.graphics.TessellationTranslatedSequenceImageAdmissionSpec", actual)
        self.assertIn("opengpu.graphics.TessellationSequenceImageAdmissionSpec", actual)
        self.assertIn("opengpu.system.TessellationPatchSequenceSpec", actual)
        self.assertIn("opengpu.system.TessellationInputMemoryFabricSpec", actual)
        self.assertNotIn("opengpu.graphics.TessellationTranslatedSequenceImageSharedSpec", actual)

    def test_tessellation_native_sequence_selects_schema_check(self):
        root = Path(__file__).resolve().parents[1]
        for path in ("src/main/scala/opengpu/system/TessellationNativeSequenceReader.scala",
                     "src/main/scala/opengpu/elaboration/EmitTessellationNativeSequenceAbi.scala",
                     "driver/opengpu_native_tessellation_sequence_abi.h",
                     "docs/abi/native-tessellation-sequence-v1.json"):
            with self.subTest(path=path):
                actual = set(changed_suites(root, [path]))
                self.assertIn("opengpu.system.TessellationNativeSequenceAbiSpec", actual)

    def test_tessellation_sequence_image_selects_bounded_checks(self):
        root = Path(__file__).resolve().parents[1]
        actual = set(changed_suites(root, ["src/main/scala/opengpu/system/TessellationSequenceImage.scala"]))
        self.assertIn("opengpu.graphics.TessellationSequenceImageAdmissionSpec", actual)
        self.assertIn("opengpu.system.TessellationPatchSequenceSpec", actual)
        self.assertIn("opengpu.graphics.TessellationSystemIsolineInputImageAdmissionSpec", actual)
        self.assertIn("opengpu.system.TessellationSystemIsolineInputSpec", actual)
        self.assertNotIn("opengpu.graphics.TessellationSequenceImageSharedSpec", actual)

    def test_tessellation_input_image_selects_bounded_checks(self):
        root = Path(__file__).resolve().parents[1]
        actual = set(changed_suites(root, ["src/main/scala/opengpu/system/TessellationSystemIsolineInputImage.scala"]))
        self.assertIn("opengpu.graphics.TessellationSystemIsolineInputImageAdmissionSpec", actual)
        self.assertIn("opengpu.graphics.TessellationSystemIsolineImageAdmissionSpec", actual)
        self.assertIn("opengpu.system.TessellationPatchInputDmaSpec", actual)
        self.assertIn("opengpu.system.TessellationSystemIsolineInputSpec", actual)
        self.assertNotIn("opengpu.graphics.TessellationSystemIsolineInputImageSharedSpec", actual)

    def test_tessellation_patch_dma_selects_context_check(self):
        root = Path(__file__).resolve().parents[1]
        actual = set(changed_suites(root, ["src/main/scala/opengpu/system/TessellationPatchInputDma.scala"]))
        self.assertIn("opengpu.system.TessellationPatchInputDmaSpec", actual)
        self.assertIn("opengpu.system.TessellationPatchInputDmaContextSpec", actual)

    def test_tessellation_system_image_selects_bounded_checks(self):
        root = Path(__file__).resolve().parents[1]
        actual = set(changed_suites(root, ["src/main/scala/opengpu/system/TessellationSystemIsolineImagePipeline.scala"]))
        self.assertIn("opengpu.graphics.TessellationSystemIsolineImageAdmissionSpec", actual)
        self.assertIn("opengpu.graphics.SharedShaderTessellationRouterSpec", actual)
        self.assertIn("opengpu.graphics.GeometryFragmentTessellationRouterSpec", actual)
        self.assertIn("opengpu.system.TessellationSystemIsolineBinPassSpec", actual)
        self.assertIn("opengpu.graphics.ControlledIsolineWindowBinTypedImagePipelineSpec", actual)
        self.assertNotIn("opengpu.graphics.TessellationSystemIsolineImageSharedSystemSpec", actual)

    def test_tessellation_shader_routing_selects_owner_checks(self):
        root = Path(__file__).resolve().parents[1]
        actual = set(changed_suites(root, ["src/main/scala/opengpu/graphics/SharedShaderTaskRouter.scala"]))
        self.assertIn("opengpu.graphics.SharedShaderTessellationRouterSpec", actual)
        self.assertIn("opengpu.graphics.GeometryFragmentTessellationRouterSpec", actual)
        actual = set(changed_suites(root, ["src/main/scala/opengpu/system/GeometryFragmentShaderRouter.scala"]))
        self.assertIn("opengpu.graphics.GeometryFragmentTessellationRouterSpec", actual)

    def test_tessellation_cu_isoline_uses_admission_and_real_cu_checks(self):
        root = Path(__file__).resolve().parents[1]
        actual = set(changed_suites(root, ["src/main/scala/opengpu/graphics/TessellationCuIsolinePatch.scala"]))
        self.assertIn("opengpu.graphics.TessellationCuIsolineAdmissionSpec", actual)
        self.assertIn("opengpu.graphics.TessellationCuIsolinePatchSpec", actual)

    def test_tessellation_cu_barrier_uses_real_cu_check(self):
        root = Path(__file__).resolve().parents[1]
        actual = set(changed_suites(root, ["src/main/scala/opengpu/graphics/TessellationCuBarrier.scala"]))
        self.assertIn("opengpu.graphics.TessellationCuBarrierSpec", actual)
        self.assertIn("opengpu.core.GpuComputeUnitTessellationSpec", actual)

    def test_tessellation_cu_finish_uses_real_cu_check(self):
        root = Path(__file__).resolve().parents[1]
        actual = set(changed_suites(root, ["src/main/scala/opengpu/graphics/TessellationCuFinish.scala"]))
        self.assertIn("opengpu.graphics.TessellationCuFinishSpec", actual)
        self.assertIn("opengpu.core.GpuComputeUnitTessellationSpec", actual)

    def test_tessellation_control_vector_access_uses_context_check(self):
        root = Path(__file__).resolve().parents[1]
        actual = set(changed_suites(root, ["src/main/scala/opengpu/graphics/TessellationControlVectorAccess.scala"]))
        self.assertIn("opengpu.graphics.TessellationControlVectorAccessSpec", actual)
        self.assertIn("opengpu.graphics.TessellationControlVectorContextSpec", actual)
        self.assertIn("opengpu.core.GpuComputeUnitTessellationSpec", actual)
        self.assertIn("opengpu.core.frontend.decode.TessellationExecutionAvailabilitySpec", actual)

    def test_tessellation_warp_patch_access_uses_context_check(self):
        root = Path(__file__).resolve().parents[1]
        actual = set(changed_suites(root, ["src/main/scala/opengpu/graphics/TessellationWarpPatchAccess.scala"]))
        self.assertIn("opengpu.graphics.TessellationWarpPatchAccessSpec", actual)
        self.assertIn("opengpu.graphics.TessellationWarpPatchContextSpec", actual)

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
        self.assertIn("opengpu.graphics.ControlledIsolineReplayRasterClientSpec", actual)


    def test_geometry_replay_fragment_keeps_bounded_admission(self):
        root = Path(__file__).resolve().parents[1]
        for path in ("src/main/scala/opengpu/system/GeometryReplayFragmentPipeline.scala",
                     "src/test/scala/opengpu/graphics/GeometryReplayFragmentPipelineSpec.scala"):
            with self.subTest(path=path):
                actual = set(changed_suites(root, [path]))
                self.assertNotIn("opengpu.graphics.GeometryReplayFragmentPipelineSpec", actual)
                self.assertIn("opengpu.graphics.GeometryReplayFragmentAdmissionSpec", actual)


    def test_controlled_isoline_replay_fragment_keeps_bounded_checks(self):
        root = Path(__file__).resolve().parents[1]
        for path in ("src/main/scala/opengpu/system/ControlledIsolineReplayFragmentPipeline.scala",
                     "src/test/scala/opengpu/graphics/ControlledIsolineReplayFragmentPipelineSpec.scala"):
            with self.subTest(path=path):
                actual = set(changed_suites(root, [path]))
                self.assertNotIn("opengpu.graphics.ControlledIsolineReplayFragmentPipelineSpec", actual)
                self.assertIn("opengpu.graphics.ControlledIsolineReplayFragmentAdmissionSpec", actual)
                self.assertIn("opengpu.graphics.ControlledIsolineReplayRasterClientSpec", actual)


    def test_controlled_isoline_window_bin_typed_image_keeps_bounded_checks(self):
        root = Path(__file__).resolve().parents[1]
        for path in ("src/main/scala/opengpu/system/ControlledIsolineWindowBinTypedImagePipeline.scala",
                     "src/test/scala/opengpu/graphics/ControlledIsolineWindowBinTypedImagePipelineSpec.scala",
                     "src/test/scala/opengpu/graphics/ControlledIsolineWindowBinTypedImageSharedSystemSpec.scala"):
            with self.subTest(path=path):
                actual = set(changed_suites(root, [path]))
                self.assertIn("opengpu.graphics.ControlledIsolineWindowBinTypedImagePipelineSpec", actual)
                self.assertNotIn("opengpu.graphics.ControlledIsolineWindowBinTypedImageSharedSystemSpec", actual)
                self.assertIn("opengpu.system.ControlledIsolineWindowBinAdmissionSpec", actual)
                self.assertIn("opengpu.graphics.TypedTileImageTransferSpec", actual)


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
