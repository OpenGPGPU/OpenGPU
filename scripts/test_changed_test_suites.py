#!/usr/bin/env python3
from pathlib import Path
import tempfile
import unittest

from changed_test_suites import changed_suites as select_changed_suites, index_test_sources

# The repository is immutable during this test process. Temporary fixture trees
# still use fresh scans so tests can verify additions and edits immediately.
REPO_ROOT = Path(__file__).resolve().parents[1]
REPO_INDEX = index_test_sources(REPO_ROOT)


def changed_suites(root, changed):
    index = REPO_INDEX if root == REPO_ROOT else None
    return select_changed_suites(root, changed, test_index=index)


class ChangedTestSuitesTest(unittest.TestCase):
    def test_native_advanced_discovery_selects_cpu_contract_and_real_axi_checks(self):
        for path in ("src/main/scala/opengpu/graphics/NativeAdvancedBlendCapabilities.scala",
                     "src/main/scala/opengpu/system/NativeAlignmentHostProfile.scala",
                     "src/main/scala/opengpu/elaboration/EmitNativeAdvancedBlendAbi.scala",
                     "driver/opengpu_native_advanced_blend_abi.h", "driver/opengpu_native_advanced_blend_features.h",
                     "driver/opengpu_native_advanced_blend_gfs.h", "driver/tests/opengpu_native_advanced_blend_gfs_test.c",
                     "driver/tests/opengpu_native_advanced_blend_kernel_abi_test.c",
                     "driver/tests/opengpu_native_advanced_blend_discovery_test.c"):
            with self.subTest(path=path):
                self.assertTrue({"opengpu.graphics.NativeAdvancedBlendAbiSpec",
                                 "opengpu.system.NativeAdvancedBlendCapabilitiesAxiSpec"} <= set(changed_suites(REPO_ROOT, [path])))
    def test_native_preflight_selects_real_metadata_reader_and_axi_checks(self):
        for path in ("src/main/scala/opengpu/graphics/NativeDescriptorPreflightCapabilities.scala",
                     "src/main/scala/opengpu/graphics/NativeBinnedDescriptorPreflight.scala",
                     "src/main/scala/opengpu/elaboration/EmitNativeDescriptorPreflightAbi.scala",
                     "driver/opengpu_native_preflight_abi.h", "driver/opengpu_native_preflight_features.h",
                     "driver/tests/opengpu_native_preflight_discovery_test.c"):
            with self.subTest(path=path):
                self.assertTrue({"opengpu.graphics.NativeAdvancedBlendAbiSpec",
                                 "opengpu.graphics.NativeBinnedGraphicsJobReaderSpec",
                                 "opengpu.system.NativeDescriptorPreflightCapabilitiesAxiSpec"} <= set(changed_suites(REPO_ROOT, [path])))
    def test_compute_resource_discovery_selects_existing_cpu_abi_checks(self):
        root = Path(__file__).resolve().parents[1]
        expected = {f"opengpu.graphics.{suite}" for suite in
                    ("ShaderImageCommandAbiSpec", "StorageCommandAbiSpec", "ComputeUniformCommandAbiSpec")}
        for path in ("src/main/scala/opengpu/system/ComputeResourceCapabilities.scala",
                     "src/main/scala/opengpu/elaboration/EmitComputeResourceAbi.scala",
                     "driver/opengpu_compute_resource_abi.h", "docs/abi/compute-resource-v1.json"):
            self.assertEqual(set(changed_suites(root, [path])), expected)

    def test_texture_image_schemas_select_existing_cpu_abi_check(self):
        paths = [f"src/main/scala/opengpu/elaboration/Emit{name}Abi.scala"
                 for name in ("ShaderImage", "TextureImageBinding", "TextureImageLevel", "TextureImageFormat")]
        paths += [f"driver/opengpu_{name}_abi.h"
                  for name in ("shader_image", "texture_image_binding", "texture_image_level", "texture_image_format")]
        paths += [f"docs/abi/{name}-v1.json"
                  for name in ("shader-image", "texture-image-binding", "texture-image-level", "texture-image-format")]
        paths += [f"src/main/scala/opengpu/graphics/{name}.scala"
                  for name in ("ShaderImageDescriptorReader", "TextureImageAddressUnit", "TextureImageBindingUnit",
                               "TextureImageResourceAddressUnit", "TextureImageFormat")]
        for path in paths:
            with self.subTest(path=path):
                self.assertIn("opengpu.graphics.ShaderImageAbiSpec", changed_suites(REPO_ROOT, [path]))

    def test_texture_header_and_generator_select_existing_kernel_abi_check(self):
        for path in ("src/main/scala/opengpu/elaboration/EmitNativeTextureAbi.scala",
                     "driver/opengpu_native_texture_abi.h", "docs/abi/native-texture-v1.json"):
            with self.subTest(path=path):
                self.assertIn("opengpu.system.TessellationNativeRecordKernelAbiSpec",
                              changed_suites(REPO_ROOT, [path]))

    def test_uniform_header_and_generator_select_existing_schema_check(self):
        for path in ("src/main/scala/opengpu/elaboration/EmitNativeUniformAbi.scala",
                     "driver/opengpu_uniform_abi.h", "docs/abi/native-uniform-v1.json"):
            with self.subTest(path=path):
                selected = set(changed_suites(REPO_ROOT, [path]))
                self.assertIn("opengpu.graphics.ComputeUniformRecordAbiSpec", selected)
                self.assertNotIn("opengpu.system.GpuHostComputeUniformCommandSpec", selected)
                self.assertNotIn("opengpu.system.GpuSystemComputeUniformSpec", selected)

    def test_primitive_record_cache_changes_require_protocol_and_full_record_control(self):
        for path in ("src/main/scala/opengpu/graphics/WindowPrimitiveRecordReadCache.scala",
                     "src/main/scala/opengpu/graphics/WindowPrimitiveRecords.scala",
                     "src/main/scala/opengpu/graphics/WindowPrimitiveBinReplayPass.scala",
                     "src/main/scala/opengpu/elaboration/EmitWindowPrimitiveRecordReadCache.scala"):
            with self.subTest(path=path):
                selected = set(changed_suites(REPO_ROOT, [path]))
                self.assertTrue({"opengpu.graphics.WindowPrimitiveRecordReadCacheSpec",
                                 "opengpu.graphics.WindowPrimitiveBinReplayPassSpec"} <= selected)
                self.assertNotIn("opengpu.graphics.NativeFourEngineDefaultHostAxiSpec", selected)

    def test_repository_index_matches_fresh_selection(self):
        for paths in ([], ["src/test/scala/opengpu/testutil/GpuSimSpec.scala"],
                      ["src/main/scala/opengpu/graphics/TypedFragmentState.scala"],
                      ["scripts/ci_local_only_suites.txt"]):
            with self.subTest(paths=paths):
                self.assertEqual(select_changed_suites(REPO_ROOT, paths, test_index=REPO_INDEX),
                                 select_changed_suites(REPO_ROOT, paths))

    def test_binned_image_qualification_selects_small_admission_checks(self):
        root = Path(__file__).resolve().parents[1]
        for path in ("src/test/scala/opengpu/graphics/TypedBinnedFragmentImageElaborationSpec.scala",
                     "src/main/scala/opengpu/graphics/TypedVertexBinImagePipeline.scala"):
            with self.subTest(path=path):
                actual = set(changed_suites(root, [path]))
                self.assertNotIn("opengpu.graphics.TypedVertexBinImagePipelineSpec", actual)
                self.assertNotIn("opengpu.graphics.TypedBinnedFragmentImageElaborationSpec", actual)
                self.assertIn("opengpu.graphics.TypedFragmentImageStateReaderSpec", actual)
                self.assertIn("opengpu.graphics.BinnedImageRoutingSpec", actual)

    def test_replay_image_qualification_selects_small_admission_checks(self):
        root = Path(__file__).resolve().parents[1]
        actual = set(changed_suites(root, ["src/test/scala/opengpu/graphics/TypedReplayFragmentImageElaborationSpec.scala"]))
        self.assertNotIn("opengpu.graphics.TypedReplayFragmentImageElaborationSpec", actual)
        self.assertIn("opengpu.graphics.TypedFragmentImageStateReaderSpec", actual)
        self.assertIn("opengpu.graphics.GraphicsImageDrawCaptureSpec", actual)
        actual = set(changed_suites(root, ["src/main/scala/opengpu/graphics/TypedReplayTileImagePipeline.scala"]))
        self.assertNotIn("opengpu.graphics.TypedReplayTileImagePipelineSpec", actual)
        self.assertIn("opengpu.graphics.TypedFragmentImageStateReaderSpec", actual)
        self.assertIn("opengpu.graphics.GeometryReplayFragmentAdmissionSpec", actual)

    def test_shader_image_large_cases_use_bounded_checks(self):
        root = Path(__file__).resolve().parents[1]
        for name in ("KernelShaderStageSpec", "VertexFragmentImageAdmissionSpec"):
            with self.subTest(name=name):
                actual = set(changed_suites(root, [f"src/test/scala/opengpu/graphics/{name}.scala"]))
                self.assertNotIn(f"opengpu.graphics.{name}", actual)
                self.assertIn("opengpu.graphics.KernelShaderStageImageRoutingSpec", actual)

        actual = set(changed_suites(root, ["src/test/scala/opengpu/graphics/TypedTileFragmentImageElaborationSpec.scala"]))
        self.assertNotIn("opengpu.graphics.TypedTileFragmentImageElaborationSpec", actual)
        self.assertIn("opengpu.graphics.GraphicsImageDrawCaptureSpec", actual)

    def test_fragment_state_source_selects_legacy_and_image_readers(self):
        root = Path(__file__).resolve().parents[1]
        actual = set(changed_suites(root, ["src/main/scala/opengpu/graphics/TypedFragmentState.scala"]))
        self.assertIn("opengpu.graphics.TypedFragmentStateReaderSpec", actual)
        self.assertIn("opengpu.graphics.TypedFragmentImageStateReaderSpec", actual)

    def test_quad_image_admission_uses_small_components(self):
        root = Path(__file__).resolve().parents[1]
        cases = {
            "TessellationQuadSequenceImageAdmissionSpec": "opengpu.system.TessellationQuadPatchSequenceSpec",
            "TessellationQuadTranslatedSequenceImageAdmissionSpec": "opengpu.system.TessellationPatchInputDmaSpec",
            "TessellationNativeQuadSequenceImageAdmissionSpec": "opengpu.system.TessellationNativeQuadSequenceReaderSpec",
            "TessellationNativeQuadJobImageAdmissionSpec": "opengpu.graphics.tessellation.TessellationNativeQuadJobReaderSpec",
        }
        for suite, expected in cases.items():
            with self.subTest(suite=suite):
                selected = set(changed_suites(root, [f"src/test/scala/opengpu/graphics/tessellation/{suite}.scala"]))
                self.assertNotIn(f"opengpu.graphics.{suite}", selected)
                self.assertIn(expected, selected)

    def test_domain_image_admission_uses_small_components(self):
        root = Path(__file__).resolve().parents[1]
        cases = {
            "TessellationNativeDomainJobImageAdmissionSpec": {
                "opengpu.system.TessellationNativeJobDomainProbeSpec",
                "opengpu.system.TessellationNativeJobDomainRouterSpec",
            },
            "TessellationNativeDomainFeedbackAdmissionSpec": {
                "opengpu.system.TessellationDomainFeedbackAdmissionSpec",
                "opengpu.graphics.tessellation.TessellationDomainFeedbackBridgeSpec",
            },
        }
        for suite, expected in cases.items():
            with self.subTest(suite=suite):
                selected = set(changed_suites(root, [f"src/test/scala/opengpu/system/{suite}.scala"]))
                self.assertNotIn(f"opengpu.system.{suite}", selected)
                self.assertTrue(expected <= selected)

    def test_feedback_payload_handshake_changes_select_cycle_regression(self):
        root = Path(__file__).resolve().parents[1]
        for name in ("TessellationThreeDomainFeedbackAdmission", "TessellationThreeDomainPayloadOwner",
                     "TessellationNativeThreeDomainJobImage"):
            with self.subTest(name=name):
                self.assertIn("opengpu.system.TessellationFeedbackPayloadAdmissionSpec",
                              changed_suites(root, [f"src/main/scala/opengpu/system/{name}.scala"]))

    def test_native_frame_abi_changes_select_tessellation_precheck(self):
        root = Path(__file__).resolve().parents[1]
        for path in ("driver/opengpu_native_binned_abi.h",
                     "src/main/scala/opengpu/elaboration/EmitNativeBinnedGraphicsAbi.scala"):
            with self.subTest(path=path):
                self.assertIn("opengpu.system.TessellationNativeJobValidateSpec", changed_suites(root, [path]))

    def test_tessellation_record_headers_select_kernel_abi_check(self):
        root = Path(__file__).resolve().parents[1]
        paths = [f"src/main/scala/opengpu/elaboration/EmitTessellationNative{domain}{kind}Abi.scala"
                 for domain in ("", "Triangle", "Quad") for kind in ("Job", "Sequence")]
        paths += [f"driver/opengpu_native_tessellation{domain}_{kind}_abi.h"
                  for domain in ("", "_triangle", "_quad") for kind in ("job", "sequence")]
        paths += ["driver/tests/opengpu_tessellation_record_kernel_abi_test.c",
                  "driver/tests/tessellation_record_kernel_stubs/linux/types.h"]
        for path in paths:
            with self.subTest(path=path):
                self.assertIn("opengpu.system.TessellationNativeRecordKernelAbiSpec", changed_suites(root, [path]))
                self.assertIn("opengpu.system.TessellationNativeJobValidateSpec", changed_suites(root, [path]))
        for path in ("driver/opengpu_tessellation_job_validate.h", "driver/tests/opengpu_tessellation_job_validate_test.c"):
            # Both checks performed by this Scala wrapper run in the host suite.
            self.assertNotIn("opengpu.system.TessellationNativeJobValidateSpec", changed_suites(root, [path]))

    def test_three_domain_feedback_query_host_selects_bounded_checks(self):
        root = Path(__file__).resolve().parents[1]
        selected = set(changed_suites(root, ["src/test/scala/opengpu/graphics/tessellation/TessellationThreeDomainFeedbackQueryHostAxiSpec.scala"]))
        self.assertTrue({"opengpu.system.TessellationNativeThreeDomainJobImageElaborationSpec",
                         "opengpu.system.TessellationFeedbackAdmissionSpec",
                         "opengpu.graphics.tessellation.TessellationThreeDomainFeedbackBridgeSpec",
                         "opengpu.graphics.tessellation.TessellationThreeDomainVisibilityQuerySpec",
                         "opengpu.system.TessellationHostShaderRouterSpec"} <= selected)
        self.assertNotIn("opengpu.graphics.tessellation.TessellationThreeDomainFeedbackQueryHostAxiSpec", selected)

    def test_three_domain_visibility_selection(self):
        root = Path(__file__).resolve().parents[1]
        for path in ("src/main/scala/opengpu/system/TessellationThreeDomainSampleMux.scala",
                     "src/main/scala/opengpu/system/TessellationNativeThreeDomainJobImage.scala",
                     "src/main/scala/opengpu/system/GpuHostSystemAxi.scala",
                     "src/main/scala/opengpu/graphics/NativeVisibilityQueryEngine.scala"):
            with self.subTest(path=path):
                self.assertIn("opengpu.graphics.tessellation.TessellationThreeDomainVisibilityQuerySpec", changed_suites(root, [path]))

    def test_three_domain_feedback_host_qualification_selects_bounded_checks(self):
        root = Path(__file__).resolve().parents[1]
        selected = set(changed_suites(root, ["src/test/scala/opengpu/graphics/tessellation/TessellationNativeThreeDomainFeedbackHostAxiSpec.scala"]))
        self.assertTrue({"opengpu.system.TessellationNativeThreeDomainJobImageElaborationSpec",
                         "opengpu.system.TessellationThreeDomainFeedbackAdmissionSpec",
                         "opengpu.graphics.tessellation.TessellationThreeDomainFeedbackBridgeSpec",
                         "opengpu.system.TessellationHostShaderRouterSpec"} <= selected)
        self.assertNotIn("opengpu.graphics.tessellation.TessellationNativeThreeDomainFeedbackHostAxiSpec", selected)

    def test_quad_host_qualification_selects_bounded_checks(self):
        root = Path(__file__).resolve().parents[1]
        selected = set(changed_suites(root, ["src/test/scala/opengpu/graphics/tessellation/TessellationNativeQuadHostAxiImageSpec.scala"]))
        self.assertTrue({"opengpu.system.TessellationNativeThreeDomainJobImageElaborationSpec",
                         "opengpu.system.TessellationNativeJobDomainRouterSpec",
                         "opengpu.graphics.tessellation.TessellationHostCommandPortSpec",
                         "opengpu.system.TessellationHostShaderRouterSpec"} <= selected)
        self.assertNotIn("opengpu.graphics.tessellation.TessellationNativeQuadHostAxiImageSpec", selected)


    def test_host_axi_suite_uses_small_checks(self):
        root = Path(__file__).resolve().parents[1]
        selected = set(changed_suites(root, ["src/test/scala/opengpu/system/GpuHostAxiSpec.scala"]))
        self.assertNotIn("opengpu.system.GpuHostAxiSpec", selected)
        self.assertTrue({"opengpu.system.GpuHostAxiOwnershipSpec",
                         "opengpu.graphics.GpuCommandMmioSpec"} <= selected)

    def test_native_host_fixture_selects_record_abi_checks(self):
        root = Path(__file__).resolve().parents[1]
        path = "src/test/scala/opengpu/graphics/tessellation/TessellationNativeHostDomainFixtureSpec.scala"
        selected = set(changed_suites(root, [path]))
        self.assertTrue({"opengpu.system.TessellationNativeSequenceAbiSpec",
                         "opengpu.system.TessellationNativeTriangleSequenceAbiSpec",
                         "opengpu.system.TessellationNativeJobAbiSpec",
                         "opengpu.system.TessellationNativeTriangleJobAbiSpec"} <= selected)
        self.assertNotIn("opengpu.graphics.tessellation.TessellationNativeHostDomainFixtureSpec", selected)
        for path in ("src/test/scala/opengpu/graphics/tessellation/TessellationNativeHostDomainFixtureSpec.scala",
                     "driver/tests/opengpu_tessellation_job_fixture_test.c"):
            selected = set(changed_suites(root, [path]))
            self.assertIn("opengpu.system.TessellationNativeJobValidateSpec", selected)
            self.assertNotIn("opengpu.graphics.tessellation.TessellationNativeHostDomainFixtureSpec", selected)

    def test_full_host_elaboration_keeps_small_admission_checks(self):
        root = Path(__file__).resolve().parents[1]
        selected = set(changed_suites(root, ["src/test/scala/opengpu/system/TessellationNativeHostElaborationSpec.scala"]))
        self.assertNotIn("opengpu.system.TessellationNativeHostElaborationSpec", selected)
        self.assertTrue({"opengpu.system.TessellationFeedbackAdmissionSpec",
                         "opengpu.system.TessellationHostShaderRouterSpec"} <= selected)

    def test_legacy_graphics_host_compatibility_stays_local(self):
        root = Path(__file__).resolve().parents[1]
        selected = set(changed_suites(root, ["src/test/scala/opengpu/graphics/NativeBinnedGraphicsHostAxiSpec.scala"]))
        self.assertNotIn("opengpu.graphics.TessellationNativeHostLegacyCompatibilitySpec", selected)
        self.assertNotIn("opengpu.graphics.TessellationNativeFragmentImageHostAxiSpec", selected)
        self.assertTrue({"opengpu.graphics.tessellation.TessellationHostCommandPortSpec",
                         "opengpu.graphics.NativeBinnedGraphicsJobReaderSpec"} <= selected)

    def test_full_integrations_select_small_components(self):
        root = Path(__file__).resolve().parents[1]
        for package, suite, small in (
            ("system", "GpuSystemSpec", "GpuHostAxiOwnershipSpec"),
            ("graphics", "PersistentTileSharedSystemSpec", "TileCommandPassControllerSpec"),
            ("graphics", "TileBinRenderPipelineSpec", "TileBinRenderPassAdmissionSpec"),
            ("graphics", "VertexShaderStageSpec", "VertexShaderClearLinesSpec"),
            ("graphics", "FragmentShaderStageSpec", "FragmentShaderAbiSpec"),
        ):
            with self.subTest(suite=suite):
                selected = set(changed_suites(root, [f"src/test/scala/opengpu/{package}/{suite}.scala"]))
                self.assertNotIn(f"opengpu.{package}.{suite}", selected)
                self.assertIn(f"opengpu.{package}.{small}", selected)

    def test_full_graphics_host_fixtures_keep_small_checks(self):
        root = Path(__file__).resolve().parents[1]
        for path in ("src/test/scala/opengpu/graphics/NativeBinnedGraphicsHostAxiSpec.scala",
                     "src/test/scala/opengpu/graphics/NativeTypedGraphicsHostAxiSpec.scala"):
            selected = set(changed_suites(root, [path]))
            self.assertFalse(any(s.startswith("opengpu.graphics.") and s.endswith("HostAxiSpec") for s in selected))
            self.assertTrue({"opengpu.graphics.NativeVisibilityQueryEngineSpec",
                             "opengpu.graphics.NativePrimitiveQueryEngineSpec",
                             "opengpu.graphics.NativeTransformFeedbackControlSpec",
                             "opengpu.graphics.TileBinHostQueryCommandPortSpec"} <= selected)

    def test_domain_feedback_host_qualification_selects_bounded_checks(self):
        root = Path(__file__).resolve().parents[1]
        path = "src/test/scala/opengpu/graphics/tessellation/TessellationNativeDomainFeedbackHostAxiSpec.scala"
        selected = set(changed_suites(root, [path]))
        self.assertTrue({"opengpu.system.TessellationDomainFeedbackAdmissionSpec",
                         "opengpu.system.TessellationDomainFeedbackAdmissionSpec",
                         "opengpu.graphics.tessellation.TessellationDomainFeedbackBridgeSpec",
                         "opengpu.system.TessellationHostShaderRouterSpec"} <= selected)
        self.assertNotIn("opengpu.graphics.tessellation.TessellationNativeDomainFeedbackHostAxiSpec", selected)
        self.assertFalse(any(suite.endswith("HostAxiSpec") for suite in selected))


    def test_native_triangle_host_qualification_selects_bounded_checks(self):
        root = Path(__file__).resolve().parents[1]
        path = "src/test/scala/opengpu/graphics/tessellation/TessellationNativeTriangleHostAxiImageSpec.scala"
        selected = set(changed_suites(root, [path]))
        self.assertTrue({"opengpu.system.TessellationNativeJobDomainProbeSpec",
                         "opengpu.system.TessellationDomainFeedbackAdmissionSpec",
                         "opengpu.graphics.tessellation.TessellationHostCommandPortSpec",
                         "opengpu.system.TessellationNativeJobDomainRouterSpec",
                         "opengpu.system.TessellationHostShaderRouterSpec"} <= selected)
        self.assertNotIn("opengpu.graphics.tessellation.TessellationNativeTriangleHostAxiImageSpec", selected)

    def test_native_domain_image_selects_feedback_checks(self):
        root = Path(__file__).resolve().parents[1]
        selected = set(changed_suites(root, ["src/main/scala/opengpu/system/TessellationNativeDomainJobImage.scala"]))
        self.assertTrue({"opengpu.system.TessellationDomainFeedbackAdmissionSpec",
                         "opengpu.system.TessellationNativeDomainFeedbackElaborationSpec"} <= selected)

    def test_domain_feedback_bridge_selects_transfer_checks(self):
        root = Path(__file__).resolve().parents[1]
        for path in ("src/main/scala/opengpu/system/TessellationDomainFeedbackBridge.scala",
                     "src/main/scala/opengpu/system/TessellationDomainFeedbackAdmission.scala"):
            selected = set(changed_suites(root, [path]))
            self.assertTrue({"opengpu.graphics.tessellation.TessellationDomainFeedbackBridgeSpec",
                             "opengpu.graphics.TransformFeedbackOwnerTransferSpec"} <= selected)

    def test_domain_samples_and_query_select_integration_check(self):
        root = Path(__file__).resolve().parents[1]
        for path in ("src/main/scala/opengpu/system/TessellationDomainSampleMux.scala",
                     "src/main/scala/opengpu/graphics/NativeVisibilityQueryEngine.scala",
                     "src/main/scala/opengpu/system/TessellationNativeDomainJobImage.scala",
                     "src/main/scala/opengpu/system/GpuHostSystemAxi.scala"):
            self.assertIn("opengpu.graphics.tessellation.TessellationDomainVisibilityQuerySpec",
                          changed_suites(root, [path]))

    def test_three_domain_feedback_selects_bridge(self):
        root = Path(__file__).resolve().parents[1]
        for path in ("src/main/scala/opengpu/system/TessellationThreeDomainFeedbackBridge.scala",
                     "src/main/scala/opengpu/system/TessellationThreeDomainFeedbackAdmission.scala",
                     "src/main/scala/opengpu/graphics/TransformFeedbackThreeDomainTransfer.scala"):
            with self.subTest(path=path):
                selected = changed_suites(root, [path])
                self.assertIn("opengpu.graphics.tessellation.TessellationThreeDomainFeedbackBridgeSpec", selected)
                self.assertIn("opengpu.system.TessellationNativeThreeDomainJobImageElaborationSpec", selected)
        selected = changed_suites(root, ["src/main/scala/opengpu/system/TessellationThreeDomainSampleMux.scala"])
        self.assertIn("opengpu.system.TessellationThreeDomainSampleMuxSpec", selected)
        self.assertIn("opengpu.system.TessellationNativeThreeDomainJobImageElaborationSpec", selected)

    def test_three_domain_job_image_selects_elaboration(self):
        root = Path(__file__).resolve().parents[1]
        selected = set(changed_suites(root, ["src/main/scala/opengpu/system/TessellationNativeThreeDomainJobImage.scala"]))
        self.assertIn("opengpu.system.TessellationNativeThreeDomainJobImageElaborationSpec", selected)
        self.assertIn("opengpu.system.TessellationNativeJobDomainRouterSpec", selected)
        selected = set(changed_suites(root, ["src/test/scala/opengpu/system/TessellationNativeThreeDomainJobImageAdmissionSpec.scala"]))
        self.assertTrue({"opengpu.system.TessellationNativeThreeDomainJobImageElaborationSpec",
                         "opengpu.system.TessellationNativeJobDomainProbeSpec",
                         "opengpu.system.TessellationNativeJobDomainRouterSpec"} <= selected)
        self.assertNotIn("opengpu.system.TessellationNativeThreeDomainJobImageAdmissionSpec", selected)
        selected = set(changed_suites(root, ["src/test/scala/opengpu/system/TessellationNativeThreeDomainFeedbackAdmissionSpec.scala"]))
        self.assertTrue({"opengpu.system.TessellationNativeThreeDomainJobImageElaborationSpec",
                         "opengpu.system.TessellationThreeDomainFeedbackAdmissionSpec",
                         "opengpu.graphics.tessellation.TessellationThreeDomainFeedbackBridgeSpec"} <= selected)
        self.assertNotIn("opengpu.system.TessellationNativeThreeDomainFeedbackAdmissionSpec", selected)

    def test_native_domain_image_selects_bounded_tests(self):
        root = Path(__file__).resolve().parents[1]
        selected = set(changed_suites(root, ["src/main/scala/opengpu/system/TessellationNativeDomainJobImage.scala"]))
        self.assertTrue({"opengpu.system.TessellationNativeJobDomainProbeSpec",
                         "opengpu.system.TessellationNativeDomainJobImageElaborationSpec"} <= selected)

    def test_triangle_native_job_image_selects_bounded_checks(self):
        root = Path(__file__).resolve().parents[1]
        for path in ("src/main/scala/opengpu/system/TessellationNativeTriangleJobImage.scala",
                     "src/test/scala/opengpu/graphics/tessellation/TessellationNativeTriangleJobImageSharedSpec.scala"):
            with self.subTest(path=path):
                selected = set(changed_suites(root, [path]))
                self.assertTrue({"opengpu.graphics.tessellation.TessellationNativeTriangleJobImageAdmissionSpec",
                                 "opengpu.graphics.tessellation.TessellationNativeTriangleJobReaderSpec",
                                 "opengpu.system.TessellationNativeJobMemoryFabricSpec",
                                 "opengpu.graphics.tessellation.TessellationNativeTriangleSequenceImageAdmissionSpec"} <= selected)
                self.assertNotIn("opengpu.graphics.tessellation.TessellationNativeTriangleJobImageSharedSpec", selected)

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
                self.assertTrue({"opengpu.graphics.tessellation.TessellationNativeTriangleJobReaderSpec",
                                 "opengpu.system.TessellationNativeTriangleJobAbiSpec"} <= selected)

    def test_triangle_native_image_qualification_keeps_bounded_coverage(self):
        root = Path(__file__).resolve().parents[1]
        for path in ("src/main/scala/opengpu/system/TessellationNativeTriangleSequenceImage.scala",
                     "src/test/scala/opengpu/graphics/tessellation/TessellationNativeTriangleSequenceImageSharedSpec.scala"):
            with self.subTest(path=path):
                selected = set(changed_suites(root, [path]))
                self.assertTrue({"opengpu.graphics.tessellation.TessellationNativeTriangleSequenceImageAdmissionSpec",
                                 "opengpu.system.TessellationNativeTriangleSequenceReaderSpec",
                                 "opengpu.system.TessellationNativeRecordMemoryFabricSpec"} <= selected)
                self.assertNotIn("opengpu.graphics.tessellation.TessellationNativeTriangleSequenceImageSharedSpec", selected)

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
        path = "src/test/scala/opengpu/graphics/tessellation/TessellationTriangleTranslatedSequenceImageSharedSpec.scala"
        selected = set(changed_suites(root, [path]))
        self.assertTrue({"opengpu.graphics.tessellation.TessellationTriangleTranslatedSequenceImageAdmissionSpec",
                         "opengpu.graphics.tessellation.TessellationTriangleSequenceImageAdmissionSpec",
                         "opengpu.system.TessellationInputMemoryFabricSpec",
                         "opengpu.system.TessellationTrianglePatchSequenceSpec"} <= selected)
        self.assertNotIn("opengpu.graphics.tessellation.TessellationTriangleTranslatedSequenceImageSharedSpec", selected)

    def test_triangle_translated_sequence_dependencies_select_tests(self):
        root = Path(__file__).resolve().parents[1]
        for name in ("TessellationTriangleTranslatedSequenceImage", "TessellationInputMemoryFabric"):
            with self.subTest(name=name):
                selected = changed_suites(root, [f"src/main/scala/opengpu/system/{name}.scala"])
                self.assertIn("opengpu.graphics.tessellation.TessellationTriangleTranslatedSequenceImageAdmissionSpec", selected)
                self.assertIn("opengpu.system.TessellationInputMemoryFabricSpec", selected)

    def test_triangle_sequence_image_qualification_keeps_bounded_coverage(self):
        root = Path(__file__).resolve().parents[1]
        for path in ("src/main/scala/opengpu/system/TessellationTriangleSequenceImage.scala",
                     "src/test/scala/opengpu/graphics/tessellation/TessellationTriangleSequenceImageSharedSpec.scala"):
            with self.subTest(path=path):
                selected = set(changed_suites(root, [path]))
                self.assertTrue({"opengpu.graphics.tessellation.TessellationTriangleSequenceImageAdmissionSpec",
                                 "opengpu.system.TessellationTrianglePatchSequenceSpec"} <= selected)
                self.assertNotIn("opengpu.graphics.tessellation.TessellationTriangleSequenceImageSharedSpec", selected)

    def test_triangle_patch_sequence_dependencies_select_test(self):
        root = Path(__file__).resolve().parents[1]
        for name in ("TessellationSystemTriangleInputImage", "TessellationTrianglePatchSequence"):
            with self.subTest(name=name):
                selected = changed_suites(root, [f"src/main/scala/opengpu/system/{name}.scala"])
                self.assertIn("opengpu.system.TessellationTrianglePatchSequenceSpec", selected)

    def test_triangle_input_image_qualification_selects_bounded_coverage(self):
        root = Path(__file__).resolve().parents[1]
        for path in ("src/main/scala/opengpu/system/TessellationSystemTriangleInputImage.scala",
                     "src/test/scala/opengpu/graphics/tessellation/TessellationSystemTriangleInputImageSharedSpec.scala"):
            with self.subTest(path=path):
                selected = set(changed_suites(root, [path]))
                self.assertTrue({"opengpu.graphics.tessellation.TessellationSystemTriangleInputImageAdmissionSpec",
                                 "opengpu.system.TessellationPatchInputDmaSpec"} <= selected)
                self.assertNotIn("opengpu.graphics.tessellation.TessellationSystemTriangleInputImageSharedSpec", selected)

    def test_triangle_image_qualification_selects_bounded_coverage(self):
        root = Path(__file__).resolve().parents[1]
        for path in ("src/main/scala/opengpu/system/TessellationSystemTriangleImagePipeline.scala",
                     "src/test/scala/opengpu/graphics/tessellation/TessellationSystemTriangleImageSharedSystemSpec.scala"):
            with self.subTest(path=path):
                selected = set(changed_suites(root, [path]))
                self.assertTrue({"opengpu.graphics.tessellation.TessellationSystemTriangleImageAdmissionSpec",
                                 "opengpu.graphics.GeometryFragmentTessellationRouterSpec",
                                 "opengpu.graphics.tessellation.TessellationTrianglePrimitiveMapperSpec"} <= selected)
                self.assertNotIn("opengpu.graphics.tessellation.TessellationSystemTriangleImageSharedSystemSpec", selected)

    def test_quad_native_job_image_qualification_selects_bounded_checks(self):
        root = Path(__file__).resolve().parents[1]
        selected = set(changed_suites(root, ["src/test/scala/opengpu/graphics/tessellation/TessellationNativeQuadJobImageSharedSpec.scala"]))
        self.assertTrue({"opengpu.system.TessellationNativeQuadJobAbiSpec",
                         "opengpu.graphics.tessellation.TessellationNativeQuadJobReaderSpec",
                         "opengpu.graphics.tessellation.TessellationQuadSequenceImageElaborationSpec",
                         "opengpu.system.TessellationNativeQuadSequenceReaderSpec"} <= selected)
        self.assertNotIn("opengpu.graphics.tessellation.TessellationNativeQuadJobImageSharedSpec", selected)

    def test_quad_native_job_abi_is_selected(self):
        root = Path(__file__).resolve().parents[1]
        for path in ("src/main/scala/opengpu/system/TessellationNativeQuadJobReader.scala",
                     "src/main/scala/opengpu/elaboration/EmitTessellationNativeQuadJobAbi.scala",
                     "driver/opengpu_native_tessellation_quad_job_abi.h",
                     "driver/tests/opengpu_tessellation_quad_job_abi_test.c",
                     "docs/abi/native-tessellation-quad-job-v3.json"):
            self.assertIn("opengpu.system.TessellationNativeQuadJobAbiSpec", changed_suites(root, [path]))

    def test_quad_native_job_reader_selects_its_test(self):
        root = Path(__file__).resolve().parents[1]
        for path in ("src/main/scala/opengpu/system/TessellationNativeQuadJobReader.scala",
                     "src/main/scala/opengpu/system/TessellationNativeQuadSequenceImageSubmit.scala"):
            self.assertIn("opengpu.graphics.tessellation.TessellationNativeQuadJobReaderSpec", changed_suites(root, [path]))

    def test_quad_native_image_qualification_selects_bounded_checks(self):
        root = Path(__file__).resolve().parents[1]
        selected = set(changed_suites(root, ["src/test/scala/opengpu/graphics/tessellation/TessellationNativeQuadSequenceImageSharedSpec.scala"]))
        self.assertTrue({"opengpu.system.TessellationNativeQuadSequenceAbiSpec",
                         "opengpu.graphics.tessellation.TessellationQuadSequenceImageElaborationSpec",
                         "opengpu.system.TessellationNativeQuadSequenceReaderSpec",
                         "opengpu.system.TessellationQuadPatchSequenceSpec"} <= selected)
        self.assertNotIn("opengpu.graphics.tessellation.TessellationNativeQuadSequenceImageSharedSpec", selected)

    def test_quad_sequence_image_selects_admission_and_elaboration(self):
        root = Path(__file__).resolve().parents[1]
        selected = set(changed_suites(root, ["src/main/scala/opengpu/system/TessellationQuadSequenceImage.scala"]))
        self.assertTrue({"opengpu.system.TessellationQuadPatchSequenceSpec",
                         "opengpu.graphics.tessellation.TessellationQuadSequenceImageElaborationSpec"} <= selected)
        selected = set(changed_suites(root, ["src/main/scala/opengpu/system/TessellationQuadTranslatedSequenceImage.scala"]))
        self.assertTrue({"opengpu.system.TessellationPatchInputDmaSpec",
                         "opengpu.graphics.tessellation.TessellationQuadSequenceImageElaborationSpec"} <= selected)
        selected = set(changed_suites(root, ["src/main/scala/opengpu/system/TessellationNativeQuadSequenceImage.scala"]))
        self.assertTrue({"opengpu.system.TessellationNativeQuadSequenceAbiSpec",
                         "opengpu.graphics.tessellation.TessellationQuadSequenceImageElaborationSpec",
                         "opengpu.system.TessellationNativeQuadSequenceReaderSpec"} <= selected)
        selected = set(changed_suites(root, ["src/main/scala/opengpu/system/TessellationNativeQuadJobImage.scala"]))
        self.assertTrue({"opengpu.system.TessellationNativeQuadJobAbiSpec",
                         "opengpu.graphics.tessellation.TessellationNativeQuadJobReaderSpec",
                         "opengpu.graphics.tessellation.TessellationQuadSequenceImageElaborationSpec"} <= selected)

    def test_quad_native_sequence_abi_and_reader_are_selected(self):
        root = Path(__file__).resolve().parents[1]
        for path in ("src/main/scala/opengpu/system/TessellationNativeQuadSequenceReader.scala",
                     "src/main/scala/opengpu/system/TessellationQuadPatchSequence.scala",
                     "src/main/scala/opengpu/elaboration/EmitTessellationNativeQuadSequenceAbi.scala",
                     "driver/opengpu_native_tessellation_quad_sequence_abi.h",
                     "driver/tests/opengpu_tessellation_quad_sequence_abi_test.c",
                     "docs/abi/native-tessellation-quad-sequence-v3.json"):
            with self.subTest(path=path):
                selected = set(changed_suites(root, [path]))
                self.assertTrue({"opengpu.system.TessellationNativeQuadSequenceAbiSpec",
                                 "opengpu.system.TessellationNativeQuadSequenceReaderSpec"} <= selected)

    def test_triangle_bin_qualification_keeps_bounded_coverage(self):
        root = Path(__file__).resolve().parents[1]
        for path in ("src/main/scala/opengpu/system/TessellationSystemTriangleBinPass.scala",
                     "src/test/scala/opengpu/system/TessellationSystemTriangleBinPassSpec.scala"):
            with self.subTest(path=path):
                selected = set(changed_suites(root, [path]))
                self.assertTrue({"opengpu.graphics.tessellation.TessellationTrianglePrimitiveMapperSpec",
                                 "opengpu.graphics.GeometryWindowBinAdmissionSpec",
                                 "opengpu.graphics.WindowPrimitiveBinReplayPassSpec"} <= selected)
                self.assertNotIn("opengpu.system.TessellationSystemTriangleBinPassSpec", selected)

    def test_triangle_system_viewport_dependencies_select_bounded_checks(self):
        root = Path(__file__).resolve().parents[1]
        paths = ["src/main/scala/opengpu/system/TessellationCuTriangleSystemClient.scala",
                 "src/main/scala/opengpu/system/TessellationSystemTriangleViewport.scala",
                 "src/main/scala/opengpu/graphics/tessellation/TessellationTrianglePrimitiveMapper.scala",
                 "src/test/scala/opengpu/system/TessellationSystemTriangleViewportSpec.scala"]
        for path in paths:
            with self.subTest(path=path):
                selected = changed_suites(root, [path])
                self.assertIn("opengpu.graphics.tessellation.TessellationTrianglePrimitiveMapperSpec", selected)
                self.assertNotIn("opengpu.system.TessellationSystemTriangleViewportSpec", selected)

    def test_triangle_control_cu_dependencies_keep_bounded_coverage(self):
        root = Path(__file__).resolve().parents[1]
        paths = [f"src/main/scala/opengpu/graphics/tessellation/{name}.scala" for name in (
            "TessellationCuTrianglePatch", "TessellationControlWarpScheduler",
            "TessellationTriangleGenerator", "TessellationTriangleEvaluation")]
        paths += ["src/main/scala/opengpu/system/TessellationCuTriangleSharedEvaluator.scala",
                  "src/test/scala/opengpu/system/TessellationCuTriangleSharedEvaluatorSpec.scala"]
        for path in paths:
            with self.subTest(path=path):
                selected = changed_suites(root, [path])
                self.assertIn("opengpu.graphics.tessellation.TessellationCuTriangleAdmissionSpec", selected)
                self.assertNotIn("opengpu.system.TessellationCuTriangleSharedEvaluatorSpec", selected)

    def test_triangle_patch_cu_dependencies_and_local_qualification(self):
        root = Path(__file__).resolve().parents[1]
        paths = [f"src/main/scala/opengpu/graphics/tessellation/{name}.scala" for name in (
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
                selected = changed_suites(root, [f"src/main/scala/opengpu/graphics/tessellation/{name}.scala"])
                self.assertIn("opengpu.graphics.tessellation.TessellationTrianglePatchEvaluationSpec", selected)

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
                selected = changed_suites(root, [f"src/main/scala/opengpu/graphics/tessellation/{name}.scala"])
                self.assertIn("opengpu.graphics.tessellation.TessellationTriangleEvaluationPipelineSpec", selected)

    def test_triangle_generator_dependencies_select_composed_test(self):
        root = Path(__file__).resolve().parents[1]
        for name in ("TessellationLevels", "TessellationEdgeCoordinate", "TessellationTriangleBoundary",
                     "TessellationTriangleInterior", "TessellationTriangleStrip", "TessellationTriangleGenerator"):
            with self.subTest(name=name):
                selected = changed_suites(root, [f"src/main/scala/opengpu/graphics/tessellation/{name}.scala"])
                self.assertIn("opengpu.graphics.tessellation.TessellationTriangleGeneratorSpec", selected)

    def test_triangle_boundary_dependencies_select_its_test(self):
        root = Path(__file__).resolve().parents[1]
        for name in ("TessellationLevels", "TessellationEdgeCoordinate", "TessellationTriangleBoundary"):
            with self.subTest(name=name):
                selected = changed_suites(root, [f"src/main/scala/opengpu/graphics/tessellation/{name}.scala"])
                self.assertIn("opengpu.graphics.tessellation.TessellationTriangleBoundarySpec", selected)

    def test_triangle_interior_dependencies_select_its_test(self):
        root = Path(__file__).resolve().parents[1]
        for name in ("TessellationLevels", "TessellationEdgeCoordinate", "TessellationTriangleInterior"):
            with self.subTest(name=name):
                selected = changed_suites(root, [f"src/main/scala/opengpu/graphics/tessellation/{name}.scala"])
                self.assertIn("opengpu.graphics.tessellation.TessellationTriangleInteriorSpec", selected)

    def test_quad_components_select_integrated_generator_and_evaluation(self):
        root = Path(__file__).resolve().parents[1]
        for name in ("TessellationLevels", "TessellationEdgeCoordinate", "TessellationQuadBoundary",
                     "TessellationQuadInterior", "TessellationQuadStrip", "TessellationQuadGenerator"):
            with self.subTest(name=name):
                actual = set(changed_suites(root, [f"src/main/scala/opengpu/graphics/tessellation/{name}.scala"]))
                self.assertIn("opengpu.graphics.tessellation.TessellationQuadGeneratorSpec", actual)
                self.assertIn("opengpu.graphics.tessellation.TessellationQuadEvaluationPipelineSpec", actual)
                self.assertIn("opengpu.graphics.tessellation.TessellationQuadPatchEvaluationSpec", actual)
                self.assertIn("opengpu.system.TessellationQuadPatchCuEvaluationSpec", actual)
        actual = set(changed_suites(root, ["src/main/scala/opengpu/graphics/tessellation/TessellationQuadEvaluation.scala"]))
        self.assertIn("opengpu.graphics.tessellation.TessellationQuadEvaluationSpec", actual)
        self.assertIn("opengpu.graphics.tessellation.TessellationQuadEvaluationPipelineSpec", actual)

    def test_quad_cu_bridge_dependencies_select_its_test(self):
        root = Path(__file__).resolve().parents[1]
        for name in ("src/main/scala/opengpu/graphics/tessellation/TessellationQuadEvaluation.scala",
                     "src/main/scala/opengpu/system/IsolinePatchInputReader.scala",
                     "src/main/scala/opengpu/system/IsolineGeometryCuExecutor.scala"):
            with self.subTest(name=name):
                self.assertIn("opengpu.system.TessellationQuadCuEvaluatorSpec", set(changed_suites(root, [name])))

    def test_quad_cu_qualification_selects_bounded_checks(self):
        root = Path(__file__).resolve().parents[1]
        selected = set(changed_suites(root, ["src/test/scala/opengpu/system/TessellationQuadCuQualificationSpec.scala"]))
        self.assertNotIn("opengpu.system.TessellationQuadCuQualificationSpec", selected)
        self.assertIn("opengpu.system.TessellationQuadCuEvaluatorSpec", selected)
        self.assertIn("opengpu.graphics.tessellation.TessellationQuadEvaluationSpec", selected)
        self.assertIn("opengpu.graphics.tessellation.TessellationQuadEvaluationPipelineSpec", selected)

    def test_quad_patch_cu_qualification_selects_bounded_checks(self):
        root = Path(__file__).resolve().parents[1]
        local = "opengpu.system.TessellationQuadPatchCuQualificationSpec"
        selected = set(changed_suites(root, ["src/test/scala/opengpu/system/TessellationQuadPatchCuQualificationSpec.scala"]))
        self.assertNotIn(local, selected)
        self.assertTrue({"opengpu.system.TessellationQuadPatchCuEvaluationSpec",
                         "opengpu.graphics.tessellation.TessellationQuadPatchPipelineSpec",
                         "opengpu.graphics.tessellation.TessellationQuadPatchEvaluationSpec"} <= selected)

    def test_quad_control_cu_qualification_selects_bounded_checks(self):
        root = Path(__file__).resolve().parents[1]
        local = "opengpu.system.TessellationCuQuadSharedEvaluatorSpec"
        selected = set(changed_suites(root, ["src/test/scala/opengpu/system/TessellationCuQuadSharedEvaluatorSpec.scala"]))
        self.assertNotIn(local, selected)
        self.assertTrue({"opengpu.graphics.tessellation.TessellationCuQuadAdmissionSpec",
                         "opengpu.system.TessellationQuadPatchCuEvaluationSpec",
                         "opengpu.system.TessellationQuadCuEvaluatorSpec"} <= selected)

    def test_quad_viewport_qualification_selects_bounded_checks(self):
        root = Path(__file__).resolve().parents[1]
        local = "opengpu.system.TessellationSystemQuadViewportSpec"
        selected = set(changed_suites(root, ["src/test/scala/opengpu/system/TessellationSystemQuadViewportSpec.scala"]))
        self.assertNotIn(local, selected)
        self.assertTrue({"opengpu.graphics.tessellation.TessellationQuadPrimitiveMapperSpec",
                         "opengpu.graphics.tessellation.TessellationCuQuadAdmissionSpec",
                         "opengpu.graphics.PrimitiveClipStageSpec",
                         "opengpu.graphics.Fp32ViewportStageSpec"} <= selected)

    def test_quad_bin_qualification_selects_bounded_checks(self):
        root = Path(__file__).resolve().parents[1]
        local = "opengpu.system.TessellationSystemQuadBinPassSpec"
        selected = set(changed_suites(root, ["src/test/scala/opengpu/system/TessellationSystemQuadBinPassSpec.scala"]))
        self.assertNotIn(local, selected)
        self.assertTrue({"opengpu.graphics.tessellation.TessellationQuadPrimitiveMapperSpec",
                         "opengpu.graphics.GeometryWindowBinAdmissionSpec",
                         "opengpu.graphics.WindowPrimitiveBinReplayPassSpec"} <= selected)

    def test_quad_image_qualification_selects_bounded_checks(self):
        root = Path(__file__).resolve().parents[1]
        local = "opengpu.graphics.tessellation.TessellationSystemQuadImageSharedSystemSpec"
        selected = set(changed_suites(root, ["src/test/scala/opengpu/graphics/tessellation/TessellationSystemQuadImageSharedSystemSpec.scala"]))
        self.assertNotIn(local, selected)
        self.assertTrue({"opengpu.graphics.tessellation.TessellationSystemQuadImageAdmissionSpec",
                         "opengpu.graphics.SharedShaderTessellationRouterSpec",
                         "opengpu.graphics.GeometryFragmentTessellationRouterSpec",
                         "opengpu.graphics.GeometryWindowBinAdmissionSpec",
                         "opengpu.graphics.tessellation.TessellationQuadPrimitiveMapperSpec"} <= selected)

    def test_quad_input_image_qualification_selects_bounded_checks(self):
        root = Path(__file__).resolve().parents[1]
        local = "opengpu.graphics.tessellation.TessellationSystemQuadInputImageSharedSpec"
        selected = set(changed_suites(root, ["src/test/scala/opengpu/graphics/tessellation/TessellationSystemQuadInputImageSharedSpec.scala"]))
        self.assertNotIn(local, selected)
        self.assertTrue({"opengpu.graphics.tessellation.TessellationSystemQuadInputImageAdmissionSpec",
                         "opengpu.graphics.tessellation.TessellationSystemQuadImageAdmissionSpec",
                         "opengpu.system.TessellationPatchInputDmaSpec",
                         "opengpu.graphics.tessellation.TessellationQuadPrimitiveMapperSpec"} <= selected)

    def test_quad_feedback_qualification_selects_bounded_checks(self):
        root = Path(__file__).resolve().parents[1]
        local = "opengpu.system.TessellationSystemQuadFeedbackSpec"
        selected = set(changed_suites(root, ["src/test/scala/opengpu/system/TessellationSystemQuadFeedbackSpec.scala"]))
        self.assertNotIn(local, selected)
        self.assertTrue({"opengpu.graphics.tessellation.TessellationQuadPrimitiveMapperSpec",
                         "opengpu.graphics.TransformFeedbackExportSpec",
                         "opengpu.graphics.tessellation.TessellationCuQuadAdmissionSpec"} <= selected)

    def test_graphics_image_qualification_keeps_bounded_feedback(self):
        root = Path(__file__).resolve().parents[1]
        for name in ("GpuSystemGraphicsImageSpec", "GpuSystemGraphicsImageRoutedSpec"):
            with self.subTest(name=name):
                local = "opengpu.system." + name
                selected = set(changed_suites(root,
                    ["src/test/scala/opengpu/system/" + name + ".scala"]))
                self.assertNotIn(local, selected)
                self.assertTrue({"opengpu.graphics.SharedShaderTaskRouterSmallSpec",
                                 "opengpu.dispatch.GpuImageCommandContextSpec",
                                 "opengpu.graphics.ShaderImageExecuteUnitSpec"} <= selected)

    def test_quad_patch_dependencies_select_integrated_checks(self):
        root = Path(__file__).resolve().parents[1]
        for name in ("src/main/scala/opengpu/graphics/tessellation/TessellationPatchContext.scala",
                     "src/main/scala/opengpu/graphics/tessellation/TessellationQuadPatchPipeline.scala",
                     "src/main/scala/opengpu/graphics/tessellation/TessellationQuadEvaluation.scala",
                     "src/main/scala/opengpu/graphics/tessellation/TessellationQuadPatchEvaluation.scala",
                     "src/main/scala/opengpu/system/TessellationQuadCuEvaluator.scala"):
            with self.subTest(name=name):
                actual = set(changed_suites(root, [name]))
                self.assertIn("opengpu.graphics.tessellation.TessellationQuadPatchEvaluationSpec", actual)
                self.assertIn("opengpu.system.TessellationQuadPatchCuEvaluationSpec", actual)

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

    def test_default_selection_rescans_edits_but_explicit_index_is_a_snapshot(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            sources = root / "src/test/scala/opengpu"
            sources.mkdir(parents=True)
            path = sources / "Example.scala"
            changed = ["src/test/scala/opengpu/Example.scala"]
            path.write_text("package opengpu\nclass FirstSpec extends AnyFlatSpec\n")
            snapshot = index_test_sources(root)
            path.write_text("package opengpu\nclass SecondSpec extends AnyFlatSpec\n")
            self.assertEqual(select_changed_suites(root, changed), ["opengpu.SecondSpec"])
            self.assertEqual(select_changed_suites(root, changed, test_index=snapshot),
                             ["opengpu.FirstSpec"])

    def test_full_host_qualification_is_local_for_direct_and_fixture_changes(self):
        root = Path(__file__).resolve().parents[1]
        local = "opengpu.graphics.NativeFourEngineDefaultHostAxiSpec"
        for changed in ("src/test/scala/opengpu/graphics/NativeFourEngineDefaultHostAxiSpec.scala",
                        "src/test/scala/opengpu/graphics/NativeFourEngineHostBaseSpec.scala",
                        "src/test/scala/opengpu/testutil/GpuSim.scala"):
            self.assertNotIn(local, changed_suites(root, [changed]))
        self.assertIn("opengpu.testutil.GpuSimSpec",
                      changed_suites(root, ["scripts/ci_local_only_suites.txt"]))

    def test_shader_router_changes_keep_small_coverage(self):
        root = Path(__file__).resolve().parents[1]
        for path in ("src/main/scala/opengpu/graphics/SharedShaderTaskRouter.scala",
                     "src/test/scala/opengpu/graphics/SharedShaderTaskRouterSpec.scala"):
            selected = set(changed_suites(root, [path]))
            self.assertNotIn("opengpu.graphics.SharedShaderTaskRouterSpec", selected)
            self.assertIn("opengpu.graphics.SharedShaderTaskRouterSmallSpec", selected)
            self.assertIn("opengpu.graphics.SharedShaderTaskRouterSmallSpec",
                          {suite for _, _, suite in REPO_INDEX[1] if suite})

    def test_l2_changes_keep_small_component_feedback(self):
        root = Path(__file__).resolve().parents[1]
        for path in ("src/main/scala/opengpu/core/memory/SharedL2Cache.scala",
                     "src/test/scala/opengpu/core/memory/SharedL2CacheSpec.scala"):
            selected = set(changed_suites(root, [path]))
            self.assertNotIn("opengpu.core.memory.SharedL2CacheSpec", selected)
            declared = {suite for _, _, suite in REPO_INDEX[1] if suite}
            self.assertLessEqual(selected, declared)
            self.assertTrue({"opengpu.core.memory.L2AtomicWriteFaultSpec",
                             "opengpu.core.memory.L2MshrTableSpec",
                             "opengpu.core.memory.L2StoreTransactionTableSpec",
                             "opengpu.core.memory.L2VictimReservationTableSpec",
                             "opengpu.core.memory.L2SimultaneousFillSharerSpec"} <= selected)

    def test_image_atomic_integration_suites_are_local_only(self):
        root = Path(__file__).resolve().parents[1]
        for path, suite in (
            ("src/test/scala/opengpu/core/GpuComputeUnitImageAtomicL2Spec.scala",
             "opengpu.core.GpuComputeUnitImageAtomicL2Spec"),
            ("src/test/scala/opengpu/system/GpuSystemImageAtomicSpec.scala",
             "opengpu.system.GpuSystemImageAtomicSpec"),
            ("src/test/scala/opengpu/system/GpuHostImageAtomicCommandSpec.scala",
             "opengpu.system.GpuHostImageAtomicCommandSpec"),
        ):
            self.assertNotIn(suite, changed_suites(root, [path]))

    def test_image_atomic_operation_qualification_keeps_bounded_feedback(self):
        root = Path(__file__).resolve().parents[1]
        selected = set(changed_suites(root,
            ["src/test/scala/opengpu/core/GpuComputeUnitImageAtomicOpsSpec.scala"]))
        self.assertNotIn("opengpu.core.GpuComputeUnitImageAtomicOpsSpec", selected)
        self.assertTrue({"opengpu.core.GpuComputeUnitImageAtomicSpec",
                         "opengpu.graphics.ShaderImageAtomicAccessSpec"} <= selected)

    def test_storage_atomic_system_qualification_keeps_bounded_ci_feedback(self):
        root = Path(__file__).resolve().parents[1]
        selected = set(changed_suites(root,
            ["src/test/scala/opengpu/system/GpuSystemStorageAtomicSpec.scala"]))
        self.assertNotIn("opengpu.system.GpuSystemStorageAtomicSpec", selected)
        self.assertTrue({"opengpu.core.memory.StorageAtomicVectorCuServiceSpec",
                         "opengpu.core.frontend.decode.StorageAtomicAvailabilityDecodeSpec"} <= selected)

    def test_storage_atomic_host_qualification_keeps_bounded_ci_feedback(self):
        root = Path(__file__).resolve().parents[1]
        selected = set(changed_suites(root,
            ["src/test/scala/opengpu/system/GpuHostStorageAtomicCommandSpec.scala"]))
        self.assertNotIn("opengpu.system.GpuHostStorageAtomicCommandSpec", selected)
        self.assertTrue({"opengpu.core.memory.StorageAtomicVectorCuServiceSpec",
                         "opengpu.core.frontend.decode.StorageAtomicAvailabilityDecodeSpec"} <= selected)

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
        self.assertTrue({"opengpu.system.GpuHostAxiOwnershipSpec",
                         "opengpu.graphics.GpuCommandMmioAtomicCounterSpec"} <= selected)

    def test_compute_texture_host_qualification_keeps_bounded_feedback(self):
        root = Path(__file__).resolve().parents[1]
        selected = set(changed_suites(root,
            ["src/test/scala/opengpu/graphics/GpuHostComputeTextureCommandSpec.scala"]))
        self.assertNotIn("opengpu.graphics.GpuHostComputeTextureCommandSpec", selected)
        self.assertTrue({"opengpu.system.GpuHostAxiOwnershipSpec",
                         "opengpu.graphics.GpuCommandMmioComputeTextureSpec"} <= selected)

    def test_fragment_full_qualification_stays_local_with_real_smoke_coverage(self):
        root = Path(__file__).resolve().parents[1]
        selected = set(changed_suites(root, ["src/test/scala/opengpu/graphics/FragmentShaderStageSpec.scala"]))
        self.assertNotIn("opengpu.graphics.FragmentShaderStageQualificationSpec", selected)
        self.assertNotIn("opengpu.graphics.FragmentShaderStageSpec", selected)
        self.assertIn("opengpu.graphics.FragmentInputCoalescerSpec", selected)
        self.assertIn("opengpu.graphics.FragmentMetadataReaderSpec", selected)

    def test_fragment_returned_credit_qualification_selects_only_bounded_stage_checks(self):
        root = Path(__file__).resolve().parents[1]
        selected = set(changed_suites(root,
            ["src/test/scala/opengpu/graphics/FragmentReturnedCreditQualificationSpec.scala"]))
        self.assertEqual(selected, {"opengpu.graphics.FragmentInputCoalescerSpec",
                                    "opengpu.graphics.FragmentShaderAbiSpec",
                                    "opengpu.graphics.FragmentMetadataReaderSpec"})

    def test_geometry_fragment_qualification_selects_bounded_admission(self):
        root = Path(__file__).resolve().parents[1]
        selected = set(changed_suites(root, ["src/test/scala/opengpu/graphics/GeometryFragmentPipelineSpec.scala"]))
        self.assertNotIn("opengpu.graphics.GeometryFragmentPipelineSpec", selected)
        self.assertIn("opengpu.graphics.GeometryFragmentAdmissionSpec", selected)

    def test_vertex_stage_and_matrix_are_local_with_small_checks(self):
        root = Path(__file__).resolve().parents[1]
        for changed in ("src/test/scala/opengpu/graphics/VertexShaderStageSpec.scala",
                        "src/main/scala/opengpu/graphics/VertexShaderStage.scala"):
            actual = changed_suites(root, [changed])
            self.assertNotIn("opengpu.graphics.VertexShaderStageSpec", actual)
            self.assertIn("opengpu.graphics.VertexShaderClearLinesSpec", actual)
            self.assertIn("opengpu.graphics.VertexInputCoalescerSpec", actual)
            self.assertNotIn("opengpu.graphics.VertexShaderTextureMatrixSpec", actual)

    def test_early_render_qualification_stays_local_while_shader_owner_checks_remain_required(self):
        root = Path(__file__).resolve().parents[1]
        local = "opengpu.graphics.EarlyDepthRenderQualificationSpec"
        for changed in ("src/test/scala/opengpu/graphics/EarlyDepthRenderQualificationSpec.scala",
                        "src/test/scala/opengpu/testutil/GpuSim.scala"):
            self.assertNotIn(local, changed_suites(root, [changed]))
        self.assertIn("opengpu.graphics.EarlyDepthFragmentShaderStageSpec", changed_suites(root,
                      ["src/test/scala/opengpu/graphics/EarlyDepthFragmentShaderStageSpec.scala"]))

    def test_fragment_stage_and_matrix_are_local_with_small_checks(self):
        root = Path(__file__).resolve().parents[1]
        actual = changed_suites(root, ["src/test/scala/opengpu/graphics/FragmentShaderStageSpec.scala"])
        self.assertNotIn("opengpu.graphics.FragmentShaderStageSpec", actual)
        self.assertIn("opengpu.graphics.FragmentInputCoalescerSpec", actual)
        self.assertNotIn("opengpu.graphics.FragmentShaderTextureMatrixSpec", actual)

    def test_opt_in_workload_benchmark_is_not_an_empty_ci_suite(self):
        root = Path(__file__).resolve().parents[1]
        for changed in ("src/test/scala/opengpu/system/GpuWorkloadSpec.scala",
                        "src/test/scala/opengpu/system/GpuHostTestSupport.scala"):
            self.assertNotIn("opengpu.system.GpuWorkloadSpec", changed_suites(root, [changed]))

    def test_binned_host_uses_bounded_checks(self):
        root = Path(__file__).resolve().parents[1]
        actual = changed_suites(root, ["src/test/scala/opengpu/graphics/BinnedTileHostAxiSpec.scala"])
        self.assertNotIn("opengpu.graphics.BinnedTileHostAxiSpec", actual)
        self.assertIn("opengpu.graphics.NativeBinnedGraphicsJobReaderSpec", actual)
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
            ("NativeDefaultProfileHostAxiSpec.scala", {"NativeRenderTargetExtentSpec", "StagedTypedImageAdmissionSpec"}),
            ("NativeTypedGraphicsHostAxiSpec.scala", {"NativeRenderTargetExtentSpec", "StagedTypedImageAdmissionSpec"}),
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
                self.assertIn("opengpu.graphics.tessellation.TessellationControlledIsolineRendererSpec", actual)
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
        self.assertIn("opengpu.graphics.tessellation.TessellationNativeJobImageAdmissionSpec", actual)
        self.assertIn("opengpu.graphics.tessellation.TessellationNativeJobReaderSpec", actual)
        self.assertIn("opengpu.system.TessellationNativeJobMemoryFabricSpec", actual)
        self.assertNotIn("opengpu.graphics.tessellation.TessellationNativeJobImageSharedSpec", actual)

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
                self.assertIn("opengpu.graphics.tessellation.TessellationHostCommandPortSpec", set(changed_suites(root, [path])))

    def test_tessellation_host_memory_fabric_selects_its_spec(self):
        root = Path(__file__).resolve().parents[1]
        actual = set(changed_suites(root, ["src/main/scala/opengpu/system/TessellationHostMemoryFabric.scala"]))
        self.assertIn("opengpu.system.TessellationHostMemoryFabricSpec", actual)
        self.assertIn("opengpu.system.TessellationThreeDomainMemoryFabricSpec", actual)
        self.assertIn("opengpu.system.TessellationThreeDomainMemoryFabricSpec", set(changed_suites(root, ["src/main/scala/opengpu/system/TessellationThreeDomainMemoryFabric.scala"])))

    def test_tessellation_host_payload_owner_selects_its_spec(self):
        root = Path(__file__).resolve().parents[1]
        self.assertIn("opengpu.system.TessellationHostPayloadOwnerSpec", set(changed_suites(root, ["src/main/scala/opengpu/system/TessellationHostPayloadOwner.scala"])))
        self.assertIn("opengpu.system.TessellationThreeDomainPayloadOwnerSpec", set(changed_suites(root, ["src/main/scala/opengpu/system/TessellationHostPayloadOwner.scala"])))
        self.assertIn("opengpu.system.TessellationThreeDomainPayloadOwnerSpec", set(changed_suites(root, ["src/main/scala/opengpu/system/TessellationThreeDomainPayloadOwner.scala"])))

    def test_tessellation_native_job_reader_selects_its_spec(self):
        root = Path(__file__).resolve().parents[1]
        actual = set(changed_suites(root, ["src/main/scala/opengpu/system/TessellationNativeJobReader.scala"]))
        self.assertIn("opengpu.graphics.tessellation.TessellationNativeJobReaderSpec", actual)

    def test_tessellation_native_image_selects_bounded_checks(self):
        root = Path(__file__).resolve().parents[1]
        actual = set(changed_suites(root, ["src/main/scala/opengpu/system/TessellationNativeSequenceImage.scala"]))
        self.assertIn("opengpu.graphics.tessellation.TessellationNativeSequenceImageAdmissionSpec", actual)
        self.assertIn("opengpu.system.TessellationNativeSequenceReaderSpec", actual)
        self.assertIn("opengpu.system.TessellationNativeRecordMemoryFabricSpec", actual)
        self.assertNotIn("opengpu.graphics.tessellation.TessellationNativeSequenceImageSharedSpec", actual)

    def test_tessellation_translated_image_selects_bounded_checks(self):
        root = Path(__file__).resolve().parents[1]
        actual = set(changed_suites(root, ["src/main/scala/opengpu/system/TessellationTranslatedSequenceImage.scala"]))
        self.assertIn("opengpu.graphics.tessellation.TessellationTranslatedSequenceImageAdmissionSpec", actual)
        self.assertIn("opengpu.graphics.tessellation.TessellationSequenceImageAdmissionSpec", actual)
        self.assertIn("opengpu.system.TessellationPatchSequenceSpec", actual)
        self.assertIn("opengpu.system.TessellationInputMemoryFabricSpec", actual)
        self.assertNotIn("opengpu.graphics.tessellation.TessellationTranslatedSequenceImageSharedSpec", actual)

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
        self.assertIn("opengpu.graphics.tessellation.TessellationSequenceImageAdmissionSpec", actual)
        self.assertIn("opengpu.system.TessellationPatchSequenceSpec", actual)
        self.assertIn("opengpu.graphics.tessellation.TessellationSystemIsolineInputImageAdmissionSpec", actual)
        self.assertIn("opengpu.system.TessellationSystemIsolineInputSpec", actual)
        self.assertNotIn("opengpu.graphics.tessellation.TessellationSequenceImageSharedSpec", actual)

    def test_tessellation_input_image_selects_bounded_checks(self):
        root = Path(__file__).resolve().parents[1]
        actual = set(changed_suites(root, ["src/main/scala/opengpu/system/TessellationSystemIsolineInputImage.scala"]))
        self.assertIn("opengpu.graphics.tessellation.TessellationSystemIsolineInputImageAdmissionSpec", actual)
        self.assertIn("opengpu.graphics.tessellation.TessellationSystemIsolineImageAdmissionSpec", actual)
        self.assertIn("opengpu.system.TessellationPatchInputDmaSpec", actual)
        self.assertIn("opengpu.system.TessellationSystemIsolineInputSpec", actual)
        self.assertNotIn("opengpu.graphics.tessellation.TessellationSystemIsolineInputImageSharedSpec", actual)

    def test_tessellation_patch_dma_selects_context_check(self):
        root = Path(__file__).resolve().parents[1]
        actual = set(changed_suites(root, ["src/main/scala/opengpu/system/TessellationPatchInputDma.scala"]))
        self.assertIn("opengpu.system.TessellationPatchInputDmaSpec", actual)
        self.assertIn("opengpu.system.TessellationPatchInputDmaContextSpec", actual)

    def test_tessellation_system_image_selects_bounded_checks(self):
        root = Path(__file__).resolve().parents[1]
        actual = set(changed_suites(root, ["src/main/scala/opengpu/system/TessellationSystemIsolineImagePipeline.scala"]))
        self.assertIn("opengpu.graphics.tessellation.TessellationSystemIsolineImageAdmissionSpec", actual)
        self.assertIn("opengpu.graphics.SharedShaderTessellationRouterSpec", actual)
        self.assertIn("opengpu.graphics.GeometryFragmentTessellationRouterSpec", actual)
        self.assertIn("opengpu.system.TessellationSystemIsolineBinPassSpec", actual)
        self.assertIn("opengpu.graphics.ControlledIsolineWindowBinTypedImagePipelineSpec", actual)
        self.assertNotIn("opengpu.graphics.tessellation.TessellationSystemIsolineImageSharedSystemSpec", actual)

    def test_tessellation_shader_routing_selects_owner_checks(self):
        root = Path(__file__).resolve().parents[1]
        actual = set(changed_suites(root, ["src/main/scala/opengpu/graphics/SharedShaderTaskRouter.scala"]))
        self.assertIn("opengpu.graphics.SharedShaderTessellationRouterSpec", actual)
        self.assertIn("opengpu.graphics.GeometryFragmentTessellationRouterSpec", actual)
        actual = set(changed_suites(root, ["src/main/scala/opengpu/system/GeometryFragmentShaderRouter.scala"]))
        self.assertIn("opengpu.graphics.GeometryFragmentTessellationRouterSpec", actual)

    def test_tessellation_cu_isoline_uses_admission_and_real_cu_checks(self):
        root = Path(__file__).resolve().parents[1]
        actual = set(changed_suites(root, ["src/main/scala/opengpu/graphics/tessellation/TessellationCuIsolinePatch.scala"]))
        self.assertIn("opengpu.graphics.tessellation.TessellationCuIsolineAdmissionSpec", actual)
        self.assertIn("opengpu.graphics.tessellation.TessellationCuIsolinePatchSpec", actual)

    def test_tessellation_cu_barrier_uses_real_cu_check(self):
        root = Path(__file__).resolve().parents[1]
        actual = set(changed_suites(root, ["src/main/scala/opengpu/graphics/tessellation/TessellationCuBarrier.scala"]))
        self.assertIn("opengpu.graphics.tessellation.TessellationCuBarrierSpec", actual)
        self.assertIn("opengpu.core.GpuComputeUnitTessellationSpec", actual)

    def test_tessellation_cu_finish_uses_real_cu_check(self):
        root = Path(__file__).resolve().parents[1]
        actual = set(changed_suites(root, ["src/main/scala/opengpu/graphics/tessellation/TessellationCuFinish.scala"]))
        self.assertIn("opengpu.graphics.tessellation.TessellationCuFinishSpec", actual)
        self.assertIn("opengpu.core.GpuComputeUnitTessellationSpec", actual)

    def test_tessellation_control_vector_access_uses_context_check(self):
        root = Path(__file__).resolve().parents[1]
        actual = set(changed_suites(root, ["src/main/scala/opengpu/graphics/tessellation/TessellationControlVectorAccess.scala"]))
        self.assertIn("opengpu.graphics.tessellation.TessellationControlVectorAccessSpec", actual)
        self.assertIn("opengpu.graphics.tessellation.TessellationControlVectorContextSpec", actual)
        self.assertIn("opengpu.core.GpuComputeUnitTessellationSpec", actual)
        self.assertIn("opengpu.core.frontend.decode.TessellationExecutionAvailabilitySpec", actual)

    def test_tessellation_warp_patch_access_uses_context_check(self):
        root = Path(__file__).resolve().parents[1]
        actual = set(changed_suites(root, ["src/main/scala/opengpu/graphics/tessellation/TessellationWarpPatchAccess.scala"]))
        self.assertIn("opengpu.graphics.tessellation.TessellationWarpPatchAccessSpec", actual)
        self.assertIn("opengpu.graphics.tessellation.TessellationWarpPatchContextSpec", actual)

    def test_isoline_patch_shared_cu_uses_bounded_checks(self):
        root = Path(__file__).resolve().parents[1]
        for path in ("src/main/scala/opengpu/system/IsolinePatchSharedCu.scala",
                     "src/test/scala/opengpu/system/IsolinePatchSharedCuQualificationSpec.scala"):
            with self.subTest(path=path):
                actual = set(changed_suites(root, [path]))
                self.assertIn("opengpu.system.IsolinePatchSharedCuAdmissionSpec", actual)
                self.assertIn("opengpu.graphics.tessellation.TessellationIsolinePatchEvaluationSpec", actual)
                self.assertIn("opengpu.system.IsolineGeometryCuExecutorSpec", actual)
                self.assertNotIn("opengpu.system.IsolinePatchSharedCuQualificationSpec", actual)

    def test_isoline_patch_pipeline_selects_cancellation_coverage(self):
        root = Path(__file__).resolve().parents[1]
        actual = set(changed_suites(root, ["src/main/scala/opengpu/graphics/tessellation/TessellationIsolinePatchPipeline.scala"]))
        self.assertIn("opengpu.graphics.tessellation.TessellationIsolinePatchPipelineSpec", actual)
        self.assertIn("opengpu.graphics.tessellation.TessellationIsolinePatchCancellationSpec", actual)

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
                {"opengpu.graphics.FragmentShaderAbiSpec", "opengpu.graphics.GeometryReplayFragmentAdmissionSpec", "opengpu.graphics.FragmentInputCoalescerSpec"},
            "src/main/scala/opengpu/graphics/VertexFragmentPipeline.scala":
                {"opengpu.graphics.FragmentShaderAbiSpec", "opengpu.graphics.GeometryFragmentAdmissionSpec", "opengpu.graphics.FragmentInputCoalescerSpec"},
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
                                 "opengpu.graphics.FragmentShaderAbiSpec"} <= actual)
                self.assertNotIn("opengpu.graphics.TypedGeometryImageQualificationSpec", actual)
                self.assertNotIn("opengpu.graphics.TypedTileImagePipelineSpec", actual)
                self.assertNotIn("opengpu.graphics.TypedTileRenderPipelineSpec", actual)


if __name__ == "__main__":
    unittest.main()
