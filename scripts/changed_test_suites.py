#!/usr/bin/env python3
"""Select changed Scala tests, fixture consumers, and directly matching specs.

This supplements the bounded smoke catalog; it is not a production dependency
graph. Broad integration remains in scala_regression.yml.
"""
import json
from pathlib import Path
import re
import sys


# Keep bounded feedback when a full shared-CU integration qualifier is local.
# These checks cover configuration, descriptor ownership and image bounds;
# they do not replace the qualifier's actual shader/rendering evidence.
LOCAL_QUALIFICATION_CHECKS = {
    "opengpu.system.TessellationNativeHostElaborationSpec": {
        "opengpu.system.TessellationFeedbackAdmissionSpec",
        "opengpu.system.TessellationHostShaderRouterSpec",
    },
    "opengpu.graphics.TessellationNativeHostLegacyCompatibilitySpec": {
        "opengpu.graphics.TessellationHostCommandPortSpec",
        "opengpu.graphics.NativeBinnedGraphicsJobReaderSpec",
    },
    "opengpu.system.GpuHostSystemAxiSpec": {
        "opengpu.system.GpuHostAxiSpec",
        "opengpu.system.GpuHostAxiOwnershipSpec",
    },
    "opengpu.system.BufferedCommandGpuHostSystemAxiSpec": {
        "opengpu.system.GpuHostAxiSpec",
        "opengpu.system.GpuHostAxiOwnershipSpec",
    },
    "opengpu.graphics.TessellationNativeHostDomainFixtureSpec": {
        "opengpu.system.TessellationNativeSequenceAbiSpec",
        "opengpu.system.TessellationNativeTriangleSequenceAbiSpec",
        "opengpu.system.TessellationNativeJobAbiSpec",
        "opengpu.system.TessellationNativeTriangleJobAbiSpec",
    },
    "opengpu.graphics.TessellationNativeTriangleHostAxiImageSpec": {
        "opengpu.system.TessellationNativeDomainJobImageAdmissionSpec",
        "opengpu.system.TessellationNativeDomainFeedbackAdmissionSpec",
        "opengpu.graphics.TessellationHostCommandPortSpec",
        "opengpu.system.TessellationNativeJobDomainRouterSpec",
        "opengpu.system.TessellationFeedbackAdmissionSpec",
        "opengpu.system.TessellationHostShaderRouterSpec",
    },
    "opengpu.graphics.TessellationNativeDomainFeedbackHostAxiSpec": {
        "opengpu.system.TessellationNativeDomainFeedbackAdmissionSpec",
        "opengpu.system.TessellationDomainFeedbackAdmissionSpec",
        "opengpu.graphics.TessellationDomainFeedbackBridgeSpec",
        "opengpu.system.TessellationFeedbackAdmissionSpec",
        "opengpu.system.TessellationHostShaderRouterSpec",
    },
    "opengpu.system.TessellationNativeDomainJobImageAdmissionSpec": {
        "opengpu.system.TessellationNativeDomainJobImageElaborationSpec",
        "opengpu.system.TessellationNativeJobDomainProbeSpec",
        "opengpu.system.TessellationNativeJobDomainRouterSpec",
    },
    "opengpu.system.TessellationNativeDomainFeedbackAdmissionSpec": {
        "opengpu.system.TessellationNativeDomainFeedbackElaborationSpec",
        "opengpu.system.TessellationDomainFeedbackAdmissionSpec",
        "opengpu.graphics.TessellationDomainFeedbackBridgeSpec",
    },
    "opengpu.system.TessellationNativeThreeDomainJobImageAdmissionSpec": {
        "opengpu.system.TessellationNativeThreeDomainJobImageElaborationSpec",
        "opengpu.system.TessellationNativeJobDomainProbeSpec",
        "opengpu.system.TessellationNativeJobDomainRouterSpec",
    },
    "opengpu.graphics.TessellationNativeTriangleJobImageSharedSpec": {
        "opengpu.graphics.TessellationNativeTriangleJobImageAdmissionSpec",
        "opengpu.graphics.TessellationNativeTriangleJobReaderSpec",
        "opengpu.system.TessellationNativeJobMemoryFabricSpec",
        "opengpu.graphics.TessellationNativeTriangleSequenceImageAdmissionSpec",
    },
    "opengpu.graphics.TessellationNativeTriangleSequenceImageSharedSpec": {
        "opengpu.graphics.TessellationNativeTriangleSequenceImageAdmissionSpec",
        "opengpu.graphics.TessellationTriangleTranslatedSequenceImageAdmissionSpec",
        "opengpu.system.TessellationNativeTriangleSequenceReaderSpec",
        "opengpu.system.TessellationNativeRecordMemoryFabricSpec",
    },
    "opengpu.graphics.TessellationTriangleTranslatedSequenceImageSharedSpec": {
        "opengpu.graphics.TessellationTriangleTranslatedSequenceImageAdmissionSpec",
        "opengpu.graphics.TessellationTriangleSequenceImageAdmissionSpec",
        "opengpu.system.TessellationInputMemoryFabricSpec",
        "opengpu.system.TessellationTrianglePatchSequenceSpec",
    },
    "opengpu.graphics.TessellationTriangleSequenceImageSharedSpec": {
        "opengpu.graphics.TessellationTriangleSequenceImageAdmissionSpec",
        "opengpu.graphics.TessellationSystemTriangleInputImageAdmissionSpec",
        "opengpu.system.TessellationTrianglePatchSequenceSpec",
        "opengpu.graphics.TessellationTrianglePrimitiveMapperSpec",
    },
    "opengpu.graphics.TessellationSystemTriangleInputImageSharedSpec": {
        "opengpu.graphics.TessellationSystemTriangleInputImageAdmissionSpec",
        "opengpu.graphics.TessellationSystemTriangleImageAdmissionSpec",
        "opengpu.system.TessellationPatchInputDmaSpec",
        "opengpu.graphics.TessellationTrianglePrimitiveMapperSpec",
    },
    "opengpu.graphics.TessellationSystemTriangleImageSharedSystemSpec": {
        "opengpu.graphics.TessellationSystemTriangleImageAdmissionSpec",
        "opengpu.graphics.SharedShaderTessellationRouterSpec",
        "opengpu.graphics.GeometryFragmentTessellationRouterSpec",
        "opengpu.graphics.GeometryWindowBinAdmissionSpec",
        "opengpu.graphics.TessellationTrianglePrimitiveMapperSpec",
    },
    "opengpu.graphics.TessellationNativeQuadJobImageSharedSpec": {
        "opengpu.graphics.TessellationNativeQuadJobImageAdmissionSpec",
        "opengpu.graphics.TessellationNativeQuadJobReaderSpec",
        "opengpu.graphics.TessellationQuadSequenceImageElaborationSpec",
        "opengpu.system.TessellationNativeQuadSequenceReaderSpec",
    },
    "opengpu.graphics.TessellationNativeQuadSequenceImageSharedSpec": {
        "opengpu.graphics.TessellationNativeQuadSequenceImageAdmissionSpec",
        "opengpu.graphics.TessellationQuadSequenceImageElaborationSpec",
        "opengpu.system.TessellationNativeQuadSequenceReaderSpec",
        "opengpu.system.TessellationQuadPatchSequenceSpec",
    },
    "opengpu.system.TessellationSystemTriangleBinPassSpec": {
        "opengpu.graphics.TessellationTrianglePrimitiveMapperSpec",
        "opengpu.graphics.GeometryWindowBinAdmissionSpec",
        "opengpu.graphics.WindowPrimitiveBinReplayPassSpec",
    },
    "opengpu.system.TessellationSystemTriangleViewportSpec": {
        "opengpu.graphics.TessellationTrianglePrimitiveMapperSpec",
        "opengpu.graphics.TessellationCuTriangleAdmissionSpec",
        "opengpu.graphics.PrimitiveClipStageSpec",
        "opengpu.graphics.Fp32ViewportStageSpec",
    },
    "opengpu.system.TessellationCuTriangleSharedEvaluatorSpec": {
        "opengpu.graphics.TessellationCuTriangleAdmissionSpec",
        "opengpu.system.TessellationTrianglePatchCuEvaluationSpec",
        "opengpu.system.TessellationTriangleCuEvaluatorSpec",
    },
    "opengpu.system.TessellationTrianglePatchCuQualificationSpec": {
        "opengpu.graphics.TessellationTrianglePatchEvaluationSpec",
        "opengpu.system.TessellationTriangleCuEvaluatorSpec",
        "opengpu.system.TessellationTrianglePatchCuEvaluationSpec",
    },
    "opengpu.system.TessellationTriangleCuQualificationSpec": {
        "opengpu.graphics.TessellationTriangleEvaluationPipelineSpec",
        "opengpu.system.TessellationTriangleCuEvaluatorSpec",
    },
    "opengpu.system.TessellationSystemQuadFeedbackSpec": {
        "opengpu.graphics.TessellationQuadPrimitiveMapperSpec",
        "opengpu.graphics.TransformFeedbackExportSpec",
        "opengpu.graphics.TessellationCuQuadAdmissionSpec",
    },
    "opengpu.graphics.TessellationSystemQuadInputImageSharedSpec": {
        "opengpu.graphics.TessellationSystemQuadInputImageAdmissionSpec",
        "opengpu.graphics.TessellationSystemQuadImageAdmissionSpec",
        "opengpu.system.TessellationPatchInputDmaSpec",
        "opengpu.graphics.TessellationQuadPrimitiveMapperSpec",
    },
    "opengpu.graphics.TessellationSystemQuadImageSharedSystemSpec": {
        "opengpu.graphics.TessellationSystemQuadImageAdmissionSpec",
        "opengpu.graphics.SharedShaderTessellationRouterSpec",
        "opengpu.graphics.GeometryFragmentTessellationRouterSpec",
        "opengpu.graphics.GeometryWindowBinAdmissionSpec",
        "opengpu.graphics.TessellationQuadPrimitiveMapperSpec",
    },
    "opengpu.system.TessellationSystemQuadBinPassSpec": {
        "opengpu.graphics.TessellationQuadPrimitiveMapperSpec",
        "opengpu.graphics.GeometryWindowBinAdmissionSpec",
        "opengpu.graphics.WindowPrimitiveBinReplayPassSpec",
    },
    "opengpu.system.TessellationSystemQuadViewportSpec": {
        "opengpu.graphics.TessellationQuadPrimitiveMapperSpec",
        "opengpu.graphics.TessellationCuQuadAdmissionSpec",
        "opengpu.graphics.PrimitiveClipStageSpec",
        "opengpu.graphics.Fp32ViewportStageSpec",
    },
    "opengpu.system.TessellationCuQuadSharedEvaluatorSpec": {
        "opengpu.graphics.TessellationCuQuadAdmissionSpec",
        "opengpu.system.TessellationQuadPatchCuEvaluationSpec",
        "opengpu.system.TessellationQuadCuEvaluatorSpec",
    },
    "opengpu.system.TessellationQuadPatchCuQualificationSpec": {
        "opengpu.system.TessellationQuadPatchCuEvaluationSpec",
        "opengpu.graphics.TessellationQuadPatchPipelineSpec",
        "opengpu.graphics.TessellationQuadPatchEvaluationSpec",
    },
    "opengpu.system.TessellationQuadCuQualificationSpec": {
        "opengpu.system.TessellationQuadCuEvaluatorSpec",
        "opengpu.graphics.TessellationQuadEvaluationSpec",
        "opengpu.graphics.TessellationQuadEvaluationPipelineSpec",
    },
    "opengpu.graphics.TessellationNativeJobImageSharedSpec": {
        "opengpu.graphics.TessellationNativeJobImageAdmissionSpec",
        "opengpu.graphics.TessellationNativeJobReaderSpec",
        "opengpu.system.TessellationNativeJobMemoryFabricSpec",
        "opengpu.graphics.TessellationNativeSequenceImageAdmissionSpec",
    },
    "opengpu.graphics.TessellationNativeSequenceImageSharedSpec": {
        "opengpu.graphics.TessellationNativeSequenceImageAdmissionSpec",
        "opengpu.system.TessellationNativeSequenceReaderSpec",
        "opengpu.system.TessellationNativeRecordMemoryFabricSpec",
        "opengpu.graphics.TessellationTranslatedSequenceImageAdmissionSpec",
    },
    "opengpu.graphics.TessellationTranslatedSequenceImageSharedSpec": {
        "opengpu.graphics.TessellationTranslatedSequenceImageAdmissionSpec",
        "opengpu.graphics.TessellationSequenceImageAdmissionSpec",
        "opengpu.system.TessellationPatchSequenceSpec",
        "opengpu.system.TessellationInputMemoryFabricSpec",
    },
    "opengpu.graphics.TessellationSequenceImageSharedSpec": {
        "opengpu.graphics.TessellationSequenceImageAdmissionSpec",
        "opengpu.system.TessellationPatchSequenceSpec",
        "opengpu.graphics.TessellationSystemIsolineInputImageAdmissionSpec",
        "opengpu.system.TessellationSystemIsolineInputSpec",
    },
    "opengpu.graphics.TessellationSystemIsolineInputImageSharedSpec": {
        "opengpu.graphics.TessellationSystemIsolineInputImageAdmissionSpec",
        "opengpu.graphics.TessellationSystemIsolineImageAdmissionSpec",
        "opengpu.system.TessellationPatchInputDmaSpec",
        "opengpu.system.TessellationSystemIsolineInputSpec",
    },
    "opengpu.graphics.TessellationSystemIsolineImageSharedSystemSpec": {
        "opengpu.graphics.TessellationSystemIsolineImageAdmissionSpec",
        "opengpu.graphics.SharedShaderTessellationRouterSpec",
        "opengpu.graphics.GeometryFragmentTessellationRouterSpec",
        "opengpu.system.TessellationSystemIsolineBinPassSpec",
        "opengpu.graphics.ControlledIsolineWindowBinTypedImagePipelineSpec",
    },
    "opengpu.graphics.TypedGeometryImageQualificationSpec": {
        "opengpu.graphics.TypedTileImageTransferSpec",
        "opengpu.graphics.GeometryReplayFragmentAdmissionSpec",
        "opengpu.graphics.FragmentShaderStageSpec",
    },
    "opengpu.graphics.TypedTileImagePipelineSpec": {
        "opengpu.graphics.TypedTileImageTransferSpec",
        "opengpu.graphics.FragmentShaderStageSpec",
    },
    "opengpu.graphics.TypedTileRenderPipelineSpec": {
        "opengpu.graphics.TypedTileImageTransferSpec",
        "opengpu.graphics.FragmentShaderStageSpec",
    },
    "opengpu.system.ControlledIsolineSharedCuViewportQualificationSpec": {
        "opengpu.system.ControlledIsolineSharedCuViewportAdmissionSpec",
        "opengpu.system.ControlledIsolineSharedCuClipAdmissionSpec",
        "opengpu.graphics.Fp32ViewportStageSpec",
    },
    "opengpu.system.ControlledIsolineWindowBinPassSpec": {
        "opengpu.system.ControlledIsolineWindowBinAdmissionSpec",
        "opengpu.system.ControlledIsolineSharedCuViewportAdmissionSpec",
    },
    "opengpu.graphics.ControlledIsolineReplayFragmentPipelineSpec": {
        "opengpu.graphics.ControlledIsolineReplayFragmentAdmissionSpec",
        "opengpu.graphics.ControlledIsolineReplayRasterClientSpec",
    },
    "opengpu.graphics.ControlledIsolineWindowBinTypedImagePipelineSpec": {
        "opengpu.system.ControlledIsolineWindowBinAdmissionSpec",
        "opengpu.graphics.TypedTileImageTransferSpec",
    },
    "opengpu.graphics.ControlledIsolineWindowBinTypedImageSharedSystemSpec": {
        "opengpu.graphics.ControlledIsolineWindowBinTypedImagePipelineSpec",
        "opengpu.system.ControlledIsolineWindowBinAdmissionSpec",
        "opengpu.graphics.TypedTileImageTransferSpec",
    },
    "opengpu.system.ControlledIsolineSharedCuClipQualificationSpec": {
        "opengpu.system.ControlledIsolineSharedCuClipAdmissionSpec",
        "opengpu.system.ControlledIsolineSharedCuRendererAdmissionSpec",
        "opengpu.graphics.PrimitiveClipStageSpec",
    },
    "opengpu.system.ControlledIsolineSharedCuRendererQualificationSpec": {
        "opengpu.system.ControlledIsolineSharedCuRendererAdmissionSpec",
        "opengpu.system.IsolinePatchInputReaderSpec",
        "opengpu.graphics.TessellationControlledIsolineRendererSpec",
        "opengpu.system.IsolineGeometryCuExecutorSpec",
    },
    "opengpu.system.IsolinePatchSharedCuQualificationSpec": {
        "opengpu.system.IsolinePatchSharedCuAdmissionSpec",
        "opengpu.graphics.TessellationIsolinePatchEvaluationSpec",
        "opengpu.system.IsolineGeometryCuExecutorSpec",
    },
    "opengpu.graphics.PreparedGeometryFragmentPipelineSpec": {
        "opengpu.graphics.FragmentShaderStageSpec",
        "opengpu.graphics.GeometryReplayFragmentAdmissionSpec",
        "opengpu.graphics.FragmentInputCoalescerSpec",
    },
    "opengpu.graphics.VertexFragmentPipelineSpec": {
        "opengpu.graphics.FragmentShaderStageSpec",
        "opengpu.graphics.GeometryFragmentAdmissionSpec",
        "opengpu.graphics.FragmentInputCoalescerSpec",
    },
    "opengpu.graphics.GeometryReplayFragmentPipelineSpec": {
        "opengpu.graphics.GeometryReplayFragmentAdmissionSpec",
    },
    "opengpu.graphics.GeometryWindowBinPassSpec": {
        "opengpu.graphics.GeometryWindowBinAdmissionSpec",
    },
    "opengpu.graphics.VertexBinPipelineSpec": {
        "opengpu.graphics.WindowPrimitiveRecordsSpec",
        "opengpu.graphics.WindowPrimitiveBinProducerSpec",
    },
    "opengpu.graphics.GeometryFragmentDrawPipelineSpec": {
        "opengpu.graphics.GeometryFragmentDrawStreamSpec",
        "opengpu.graphics.GeometryFragmentAdmissionSpec",
    },
    "opengpu.graphics.GeometryFragmentPipelineSpec": {
        "opengpu.graphics.GeometryFragmentAdmissionSpec",
    },
    "opengpu.graphics.FragmentShaderStageQualificationSpec": {
        "opengpu.graphics.FragmentShaderStageSpec",
        "opengpu.graphics.FragmentInputCoalescerSpec",
    },
    "opengpu.system.GpuHostAtomicCounterCommandSpec": {
        "opengpu.system.GpuHostAxiSpec",
        "opengpu.graphics.GpuCommandMmioAtomicCounterSpec",
    },
    "opengpu.system.GeometryPrimitiveRasterClientSpec": {
        "opengpu.system.GeometryRasterAdmissionSpec",
    },
    "opengpu.system.GpuSystemAtomicCounterSpec": {
        "opengpu.dispatch.AtomicCounterGridDispatchSpec",
        "opengpu.dispatch.AtomicCounterCommandOwnershipSpec",
        "opengpu.core.memory.AtomicCounterVectorCuServiceSpec",
    },
    "opengpu.graphics.GpuHostComputeTextureCommandSpec": {
        "opengpu.system.GpuHostAxiSpec",
        "opengpu.graphics.GpuCommandMmioComputeTextureSpec",
    },
    "opengpu.graphics.MemoryNativeBinnedGraphicsPipelineSpec": {
        "opengpu.graphics.NativeRenderTargetExtentSpec",
        "opengpu.graphics.NativeBinnedGraphicsJobReaderSpec",
        "opengpu.graphics.StagedTypedImageAdmissionSpec",
    },
    "opengpu.system.GpuSystemSpec": {
        "opengpu.system.GpuHostAxiSpec", "opengpu.system.AxiStimulusOrderingSpec",
    },
    "opengpu.graphics.PersistentTileSharedSystemSpec": {
        "opengpu.graphics.TileCommandPassControllerSpec", "opengpu.graphics.TileBinReplaySchedulerSpec",
    },
    "opengpu.graphics.TileBinRenderPipelineSpec": {
        "opengpu.graphics.TileBinRenderPassAdmissionSpec", "opengpu.graphics.TileBinJobReaderSpec",
    },
    "opengpu.graphics.VertexShaderStageSpec": {
        "opengpu.graphics.VertexShaderClearLinesSpec", "opengpu.graphics.TextureLaneStartSpec",
    },
    "opengpu.graphics.FragmentShaderStageSpec": {
        "opengpu.graphics.FragmentInputCoalescerSpec", "opengpu.graphics.FragmentShaderAbiSpec",
    },
    "opengpu.system.GpuHostAxiSpec": {
        "opengpu.system.GpuHostAxiOwnershipSpec", "opengpu.graphics.GpuCommandMmioSpec",
    },
}


def local_only_suites(root):
    path = root / "scripts/ci_local_only_suites.txt"
    if not path.exists():
        return set()
    return {line.strip() for line in path.read_text().splitlines()
            if line.strip() and not line.lstrip().startswith("#")}


def is_local_only_suite(root, suite):
    # These graphics host suites elaborate a full GPU even for tiny frames.
    return (suite in local_only_suites(root) or
            (suite.startswith("opengpu.graphics.") and suite.endswith("HostAxiSpec")))


def changed_suites(root, changed):
    changed = set(changed)
    test_root = root / "src/test/scala"
    files = {}
    nodes = []
    for path in sorted(test_root.rglob("*.scala")):
        source = path.read_text()
        # Ignore comments so a documentation mention cannot create dependencies.
        source = re.sub(r"/\*.*?\*/|//[^\n]*", "", source, flags=re.S)
        package = re.search(r"^package\s+([\w.]+)", source, re.M)
        declarations = list(re.finditer(
            r"^((?:(?:abstract|final|sealed)\s+)*)(class|trait|object)\s+(\w+)",
            source, re.M,
        ))
        file_nodes = set()
        for index, declaration in enumerate(declarations):
            modifiers, kind, name = declaration.groups()
            end = declarations[index + 1].start() if index + 1 < len(declarations) else len(source)
            references = set(re.findall(r"\b\w+\b", source[declaration.end():end])) - {name}
            suite = (package[1] + "." + name
                     if package and kind == "class" and "abstract" not in modifiers
                     and name.endswith("Spec") else None)
            file_nodes.add(len(nodes))
            nodes.append((name, references, suite))
        files[path.relative_to(root).as_posix()] = file_nodes

    affected = set(changed) & files.keys()
    ci_inputs = {
        ".github/actions/setup-simulation/action.yml", "scripts/prefetch_scala_bridge.sh",
        ".github/workflows/scala_test.yml", "scripts/changed_test_suites.py",
        "scripts/check_pr_test_results.py", "scripts/ci_shard_tests.sh",
        "scripts/check_ci_job_results.sh",
        "scripts/ci_pr_suites.txt", "scripts/ci_local_only_suites.txt", "scripts/select_tests.sh",
        "scripts/ci_case_partitions.py", "scripts/ci_changed_host_cases.py", "scripts/ci_telemetry_coverage.py", "scripts/run_ci_partition.py",
        "src/test/scala/opengpu/testutil/CiSuiteCases.scala",
        ".github/workflows/scala_regression.yml",
    }
    if changed & ci_inputs:
        # Exercise the actual required matrix when its own plumbing changes.
        affected.add("src/test/scala/opengpu/testutil/GpuSimSpec.scala")
    if changed & {"src/main/scala/opengpu/graphics/TessellationLevels.scala",
                  "src/main/scala/opengpu/graphics/TessellationEdgeCoordinate.scala"}:
        affected.add("src/test/scala/opengpu/graphics/TessellationTriangleInteriorSpec.scala")
    if changed & {"src/main/scala/opengpu/system/TessellationNativeQuadSequenceReader.scala",
                  "src/main/scala/opengpu/system/TessellationQuadPatchSequence.scala",
                  "src/main/scala/opengpu/elaboration/EmitTessellationNativeQuadSequenceAbi.scala",
                  "driver/opengpu_native_tessellation_quad_sequence_abi.h",
                  "driver/tests/opengpu_tessellation_quad_sequence_abi_test.c",
                  "docs/abi/native-tessellation-quad-sequence-v3.json"}:
        affected.add("src/test/scala/opengpu/system/TessellationNativeQuadSequenceAbiSpec.scala")
        affected.add("src/test/scala/opengpu/system/TessellationNativeQuadSequenceReaderSpec.scala")
    if "src/main/scala/opengpu/system/TessellationQuadSequenceImage.scala" in changed:
        affected.add("src/test/scala/opengpu/graphics/TessellationQuadSequenceImageAdmissionSpec.scala")
        affected.add("src/test/scala/opengpu/graphics/TessellationQuadSequenceImageElaborationSpec.scala")
    if "src/main/scala/opengpu/system/TessellationQuadTranslatedSequenceImage.scala" in changed:
        affected.add("src/test/scala/opengpu/graphics/TessellationQuadTranslatedSequenceImageAdmissionSpec.scala")
        affected.add("src/test/scala/opengpu/graphics/TessellationQuadSequenceImageElaborationSpec.scala")
    if "src/main/scala/opengpu/system/TessellationNativeQuadSequenceImage.scala" in changed:
        affected.add("src/test/scala/opengpu/graphics/TessellationNativeQuadSequenceImageAdmissionSpec.scala")
        affected.add("src/test/scala/opengpu/graphics/TessellationQuadSequenceImageElaborationSpec.scala")
        affected.add("src/test/scala/opengpu/system/TessellationNativeQuadSequenceReaderSpec.scala")
    if "src/main/scala/opengpu/system/TessellationNativeQuadJobImage.scala" in changed:
        affected.add("src/test/scala/opengpu/graphics/TessellationNativeQuadJobImageAdmissionSpec.scala")
        affected.add("src/test/scala/opengpu/graphics/TessellationNativeQuadJobReaderSpec.scala")
        affected.add("src/test/scala/opengpu/graphics/TessellationQuadSequenceImageElaborationSpec.scala")
    if changed & {"src/main/scala/opengpu/system/TessellationNativeQuadJobReader.scala",
                  "src/main/scala/opengpu/system/TessellationNativeQuadSequenceImageSubmit.scala"}:
        affected.add("src/test/scala/opengpu/graphics/TessellationNativeQuadJobReaderSpec.scala")
    if changed & {"src/main/scala/opengpu/system/TessellationNativeQuadJobReader.scala",
                  "src/main/scala/opengpu/elaboration/EmitTessellationNativeQuadJobAbi.scala",
                  "driver/opengpu_native_tessellation_quad_job_abi.h",
                  "driver/tests/opengpu_tessellation_quad_job_abi_test.c",
                  "docs/abi/native-tessellation-quad-job-v3.json"}:
        affected.add("src/test/scala/opengpu/system/TessellationNativeQuadJobAbiSpec.scala")
    if changed & {f"src/main/scala/opengpu/graphics/{name}.scala" for name in (
            "TessellationLevels", "TessellationEdgeCoordinate", "TessellationTriangleBoundary",
            "TessellationTriangleInterior", "TessellationTriangleStrip")}:
        affected.add("src/test/scala/opengpu/graphics/TessellationTriangleGeneratorSpec.scala")
    if changed & {f"src/main/scala/opengpu/graphics/{name}.scala" for name in (
            "TessellationLevels", "TessellationEdgeCoordinate", "TessellationTriangleBoundary",
            "TessellationTriangleInterior", "TessellationTriangleStrip", "TessellationTriangleGenerator",
            "TessellationTriangleEvaluation")}:
        affected.add("src/test/scala/opengpu/graphics/TessellationTriangleEvaluationPipelineSpec.scala")
    if changed & {f"src/main/scala/opengpu/system/{name}.scala" for name in (
            "IsolinePatchInputReader", "IsolineGeometryCuExecutor")}:
        affected.add("src/test/scala/opengpu/system/TessellationTriangleCuEvaluatorSpec.scala")
    if changed & {f"src/main/scala/opengpu/graphics/{name}.scala" for name in (
            "TessellationPatchContext", "TessellationTrianglePatchPipeline", "TessellationTriangleEvaluation",
            "TessellationTriangleGenerator")}:
        affected.add("src/test/scala/opengpu/graphics/TessellationTrianglePatchEvaluationSpec.scala")
    if changed & ({f"src/main/scala/opengpu/graphics/{name}.scala" for name in (
            "TessellationPatchContext", "TessellationTrianglePatchPipeline", "TessellationTrianglePatchEvaluation")} |
            {f"src/main/scala/opengpu/system/{name}.scala" for name in (
            "TessellationTriangleCuEvaluator", "TessellationTrianglePatchCuEvaluation",
            "IsolinePatchInputReader", "IsolineGeometryCuExecutor")}):
        affected.add("src/test/scala/opengpu/system/TessellationTrianglePatchCuEvaluationSpec.scala")
    if changed & {f"src/main/scala/opengpu/graphics/{name}.scala" for name in (
            "TessellationCuTrianglePatch", "TessellationControlWarpScheduler", "TessellationTriangleGenerator",
            "TessellationTriangleEvaluation")}:
        affected.add("src/test/scala/opengpu/graphics/TessellationCuTriangleAdmissionSpec.scala")
    if "src/main/scala/opengpu/system/TessellationCuTriangleSharedEvaluator.scala" in changed:
        affected.add("src/test/scala/opengpu/graphics/TessellationCuTriangleAdmissionSpec.scala")
        affected.add("src/test/scala/opengpu/system/TessellationTrianglePatchCuEvaluationSpec.scala")
    if changed & {"src/main/scala/opengpu/system/TessellationCuTriangleSystemClient.scala",
                  "src/main/scala/opengpu/system/TessellationSystemTriangleViewport.scala",
                  "src/main/scala/opengpu/graphics/TessellationTrianglePrimitiveMapper.scala"}:
        affected.add("src/test/scala/opengpu/graphics/TessellationTrianglePrimitiveMapperSpec.scala")
        affected.add("src/test/scala/opengpu/graphics/TessellationCuTriangleAdmissionSpec.scala")
    if "src/main/scala/opengpu/system/TessellationSystemTriangleBinPass.scala" in changed:
        affected.add("src/test/scala/opengpu/graphics/GeometryWindowBinAdmissionSpec.scala")
        affected.add("src/test/scala/opengpu/graphics/WindowPrimitiveBinReplayPassSpec.scala")
        affected.add("src/test/scala/opengpu/graphics/TessellationTrianglePrimitiveMapperSpec.scala")
    if "src/main/scala/opengpu/system/TessellationSystemTriangleImagePipeline.scala" in changed:
        affected.add("src/test/scala/opengpu/graphics/TessellationSystemTriangleImageAdmissionSpec.scala")
        affected.add("src/test/scala/opengpu/graphics/GeometryFragmentTessellationRouterSpec.scala")
        affected.add("src/test/scala/opengpu/graphics/TessellationTrianglePrimitiveMapperSpec.scala")
    if "src/main/scala/opengpu/system/TessellationSystemTriangleInputImage.scala" in changed:
        affected.add("src/test/scala/opengpu/graphics/TessellationSystemTriangleInputImageAdmissionSpec.scala")
        affected.add("src/test/scala/opengpu/system/TessellationPatchInputDmaSpec.scala")
    if changed & {"src/main/scala/opengpu/system/TessellationSystemTriangleInputImage.scala",
                  "src/main/scala/opengpu/system/TessellationTrianglePatchSequence.scala"}:
        affected.add("src/test/scala/opengpu/system/TessellationTrianglePatchSequenceSpec.scala")
    if "src/main/scala/opengpu/system/TessellationTriangleSequenceImage.scala" in changed:
        affected.add("src/test/scala/opengpu/graphics/TessellationTriangleSequenceImageAdmissionSpec.scala")
        affected.add("src/test/scala/opengpu/system/TessellationTrianglePatchSequenceSpec.scala")
    if changed & {"src/main/scala/opengpu/system/TessellationTriangleTranslatedSequenceImage.scala",
                  "src/main/scala/opengpu/system/TessellationInputMemoryFabric.scala"}:
        affected.add("src/test/scala/opengpu/graphics/TessellationTriangleTranslatedSequenceImageAdmissionSpec.scala")
        affected.add("src/test/scala/opengpu/system/TessellationInputMemoryFabricSpec.scala")
    if changed & {"src/main/scala/opengpu/system/TessellationNativeTriangleSequenceReader.scala",
                  "src/main/scala/opengpu/elaboration/EmitTessellationNativeTriangleSequenceAbi.scala",
                  "src/main/scala/opengpu/system/TessellationTrianglePatchSequence.scala"}:
        affected.add("src/test/scala/opengpu/system/TessellationNativeTriangleSequenceReaderSpec.scala")
        affected.add("src/test/scala/opengpu/system/TessellationNativeTriangleSequenceAbiSpec.scala")
    if changed & {"src/main/scala/opengpu/system/TessellationDomainFeedbackBridge.scala",
                  "src/main/scala/opengpu/system/TessellationDomainFeedbackAdmission.scala"}:
        affected.add("src/test/scala/opengpu/graphics/TessellationDomainFeedbackBridgeSpec.scala")
        affected.add("src/test/scala/opengpu/graphics/TransformFeedbackOwnerTransferSpec.scala")
    if changed & {"src/main/scala/opengpu/system/TessellationDomainSampleMux.scala",
                  "src/main/scala/opengpu/graphics/NativeVisibilityQueryEngine.scala",
                  "src/main/scala/opengpu/system/TessellationNativeDomainJobImage.scala",
                  "src/main/scala/opengpu/system/GpuHostSystemAxi.scala"}:
        affected.add("src/test/scala/opengpu/graphics/TessellationDomainVisibilityQuerySpec.scala")
    if "src/main/scala/opengpu/system/TessellationHostPayloadOwner.scala" in changed:
        affected.add("src/test/scala/opengpu/system/TessellationThreeDomainPayloadOwnerSpec.scala")
    if "src/main/scala/opengpu/system/TessellationHostMemoryFabric.scala" in changed:
        affected.add("src/test/scala/opengpu/system/TessellationThreeDomainMemoryFabricSpec.scala")
    if "src/main/scala/opengpu/system/TessellationNativeThreeDomainJobImage.scala" in changed:
        affected.add("src/test/scala/opengpu/system/TessellationNativeThreeDomainJobImageElaborationSpec.scala")
        affected.add("src/test/scala/opengpu/system/TessellationNativeJobDomainRouterSpec.scala")
    if "src/main/scala/opengpu/system/TessellationNativeDomainJobImage.scala" in changed:
        affected.add("src/test/scala/opengpu/system/TessellationNativeDomainJobImageAdmissionSpec.scala")
        affected.add("src/test/scala/opengpu/system/TessellationNativeDomainJobImageElaborationSpec.scala")
        affected.add("src/test/scala/opengpu/system/TessellationNativeDomainFeedbackAdmissionSpec.scala")
        affected.add("src/test/scala/opengpu/system/TessellationNativeDomainFeedbackElaborationSpec.scala")
    if "src/main/scala/opengpu/system/TessellationNativeTriangleJobImage.scala" in changed:
        affected.add("src/test/scala/opengpu/graphics/TessellationNativeTriangleJobImageAdmissionSpec.scala")
        affected.add("src/test/scala/opengpu/graphics/TessellationNativeTriangleJobReaderSpec.scala")
        affected.add("src/test/scala/opengpu/system/TessellationNativeJobMemoryFabricSpec.scala")
        affected.add("src/test/scala/opengpu/graphics/TessellationNativeTriangleSequenceImageAdmissionSpec.scala")
    if "src/main/scala/opengpu/system/TessellationNativeTriangleSequenceImage.scala" in changed:
        affected.add("src/test/scala/opengpu/graphics/TessellationNativeTriangleSequenceImageAdmissionSpec.scala")
        affected.add("src/test/scala/opengpu/system/TessellationNativeTriangleSequenceReaderSpec.scala")
        affected.add("src/test/scala/opengpu/system/TessellationNativeRecordMemoryFabricSpec.scala")
    if changed & {"src/main/scala/opengpu/system/TessellationNativeTriangleJobReader.scala",
                  "src/main/scala/opengpu/elaboration/EmitTessellationNativeTriangleJobAbi.scala",
                  "src/main/scala/opengpu/system/TessellationNativeTriangleSequenceReader.scala",
                  "driver/opengpu_native_tessellation_triangle_job_abi.h",
                  "driver/tests/opengpu_tessellation_triangle_job_abi_test.c",
                  "docs/abi/native-tessellation-triangle-job-v2.json"}:
        affected.add("src/test/scala/opengpu/graphics/TessellationNativeTriangleJobReaderSpec.scala")
        affected.add("src/test/scala/opengpu/system/TessellationNativeTriangleJobAbiSpec.scala")
    for name in changed:
        if name in ("driver/opengpu_native_tessellation_triangle_sequence_abi.h",
                    "docs/abi/native-tessellation-triangle-sequence-v2.json"):
            affected.add("src/test/scala/opengpu/system/TessellationNativeTriangleSequenceAbiSpec.scala")
        if name in ("src/main/scala/opengpu/graphics/TessellationLevels.scala",
                    "src/main/scala/opengpu/graphics/TessellationEdgeCoordinate.scala"):
            affected.add("src/test/scala/opengpu/graphics/TessellationTriangleBoundarySpec.scala")
        if name == "src/main/scala/opengpu/system/GpuHostAxi.scala":
            affected.add("src/test/scala/opengpu/system/TessellationHostAxiCommandSpec.scala")
        if name in ("src/main/scala/opengpu/graphics/TileBinHostCommandPort.scala",
                    "src/main/scala/opengpu/command/GpuCommandRouter.scala"):
            affected.add("src/test/scala/opengpu/graphics/TessellationHostCommandPortSpec.scala")
        if name in ("src/main/scala/opengpu/system/TessellationNativeJobReader.scala",
                    "src/main/scala/opengpu/system/TessellationNativeSequenceReader.scala",
                    "src/main/scala/opengpu/graphics/NativeBinnedGraphicsDescriptors.scala",
                    "src/main/scala/opengpu/elaboration/EmitTessellationNativeJobAbi.scala",
                    "driver/opengpu_native_tessellation_job_abi.h",
                    "driver/tests/opengpu_tessellation_job_abi_test.c",
                    "docs/abi/native-tessellation-job-v1.json"):
            affected.add("src/test/scala/opengpu/system/TessellationNativeJobAbiSpec.scala")
        if name in ("src/main/scala/opengpu/elaboration/EmitTessellationNativeCompletionAbi.scala",
                    "src/main/scala/opengpu/system/TessellationNativeCompletionInfo.scala",
                    "driver/opengpu_native_tessellation_completion_abi.h",
                    "driver/opengpu_tessellation_completion.h",
                    "driver/tests/opengpu_tessellation_completion_test.c",
                    "driver/tests/opengpu_tessellation_completion_kernel_test.c",
                    "driver/tests/tessellation_abi_stubs/linux/types.h",
                    "scripts/test_tessellation_completion_kernel.sh",
                    "docs/abi/native-tessellation-completion-v1.json"):
            affected.add("src/test/scala/opengpu/system/TessellationNativeCompletionInfoSpec.scala")
        if name in ("driver/opengpu_native_tessellation_sequence_abi.h",
                    "docs/abi/native-tessellation-sequence-v1.json"):
            affected.add("src/test/scala/opengpu/system/TessellationNativeSequenceAbiSpec.scala")
        if name in {"src/main/scala/opengpu/graphics/" + unit + ".scala" for unit in (
                "TessellationLevels", "TessellationEdgeCoordinate", "TessellationQuadBoundary",
                "TessellationQuadInterior", "TessellationQuadStrip", "TessellationQuadGenerator")}:
            affected.add("src/test/scala/opengpu/graphics/TessellationQuadGeneratorSpec.scala")
            affected.add("src/test/scala/opengpu/graphics/TessellationQuadEvaluationPipelineSpec.scala")
            affected.add("src/test/scala/opengpu/graphics/TessellationQuadPatchEvaluationSpec.scala")
            affected.add("src/test/scala/opengpu/system/TessellationQuadPatchCuEvaluationSpec.scala")
        if name == "src/main/scala/opengpu/graphics/TessellationQuadEvaluation.scala":
            affected.add("src/test/scala/opengpu/graphics/TessellationQuadEvaluationPipelineSpec.scala")
        if name in ("src/main/scala/opengpu/graphics/TessellationQuadEvaluation.scala",
                    "src/main/scala/opengpu/system/IsolinePatchInputReader.scala",
                    "src/main/scala/opengpu/system/IsolineGeometryCuExecutor.scala"):
            affected.add("src/test/scala/opengpu/system/TessellationQuadCuEvaluatorSpec.scala")
        if name in ("src/main/scala/opengpu/graphics/TessellationPatchContext.scala",
                    "src/main/scala/opengpu/graphics/TessellationQuadPatchPipeline.scala",
                    "src/main/scala/opengpu/graphics/TessellationQuadEvaluation.scala",
                    "src/main/scala/opengpu/graphics/TessellationQuadPatchEvaluation.scala",
                    "src/main/scala/opengpu/system/TessellationQuadCuEvaluator.scala"):
            affected.add("src/test/scala/opengpu/graphics/TessellationQuadPatchEvaluationSpec.scala")
            affected.add("src/test/scala/opengpu/system/TessellationQuadPatchCuEvaluationSpec.scala")
        if name == "src/main/scala/opengpu/system/TessellationSystemQuadInputImage.scala":
            affected.add("src/test/scala/opengpu/graphics/TessellationSystemQuadInputImageAdmissionSpec.scala")
            affected.add("src/test/scala/opengpu/system/TessellationPatchInputDmaSpec.scala")
        if name == "src/main/scala/opengpu/system/TessellationSystemQuadImagePipeline.scala":
            affected.add("src/test/scala/opengpu/graphics/TessellationSystemQuadImageAdmissionSpec.scala")
            affected.add("src/test/scala/opengpu/graphics/GeometryFragmentTessellationRouterSpec.scala")
        if name == "src/main/scala/opengpu/system/TessellationSystemQuadBinPass.scala":
            affected.add("src/test/scala/opengpu/graphics/GeometryWindowBinAdmissionSpec.scala")
            affected.add("src/test/scala/opengpu/graphics/WindowPrimitiveBinReplayPassSpec.scala")
            affected.add("src/test/scala/opengpu/graphics/TessellationQuadPrimitiveMapperSpec.scala")
        if name in ("src/main/scala/opengpu/system/TessellationCuQuadSystemClient.scala",
                    "src/main/scala/opengpu/system/TessellationSystemQuadViewport.scala",
                    "src/main/scala/opengpu/graphics/TessellationQuadPrimitiveMapper.scala"):
            affected.add("src/test/scala/opengpu/graphics/TessellationQuadPrimitiveMapperSpec.scala")
            affected.add("src/test/scala/opengpu/graphics/TessellationCuQuadAdmissionSpec.scala")
        if name in ("src/main/scala/opengpu/graphics/TessellationCuQuadPatch.scala",
                    "src/main/scala/opengpu/system/TessellationCuQuadSharedEvaluator.scala"):
            affected.add("src/test/scala/opengpu/graphics/TessellationCuQuadAdmissionSpec.scala")
            affected.add("src/test/scala/opengpu/system/TessellationQuadPatchCuEvaluationSpec.scala")
        if name == "src/main/scala/opengpu/graphics/TessellationPatchContext.scala":
            affected.add("src/test/scala/opengpu/graphics/TessellationQuadPatchPipelineSpec.scala")
        if name.startswith("src/main/scala/") and name.endswith(".scala"):
            if name in ("src/main/scala/opengpu/system/TessellationNativeSequenceReader.scala",
                        "src/main/scala/opengpu/elaboration/EmitTessellationNativeSequenceAbi.scala"):
                affected.add("src/test/scala/opengpu/system/TessellationNativeSequenceAbiSpec.scala")
            if name == "src/main/scala/opengpu/system/TessellationNativeJobImage.scala":
                affected.add("src/test/scala/opengpu/graphics/TessellationNativeJobImageSharedSpec.scala")
            if name == "src/main/scala/opengpu/system/TessellationNativeJobReader.scala":
                affected.add("src/test/scala/opengpu/graphics/TessellationNativeJobReaderSpec.scala")
            if name == "src/main/scala/opengpu/system/TessellationNativeSequenceImage.scala":
                affected.add("src/test/scala/opengpu/graphics/TessellationNativeSequenceImageSharedSpec.scala")
            if name == "src/main/scala/opengpu/system/TessellationTranslatedSequenceImage.scala":
                affected.add("src/test/scala/opengpu/graphics/TessellationTranslatedSequenceImageSharedSpec.scala")
            if name == "src/main/scala/opengpu/system/TessellationSequenceImage.scala":
                affected.add("src/test/scala/opengpu/graphics/TessellationSequenceImageSharedSpec.scala")
            if name == "src/main/scala/opengpu/system/TessellationSystemIsolineInputImage.scala":
                affected.add("src/test/scala/opengpu/graphics/TessellationSystemIsolineInputImageSharedSpec.scala")
            if name == "src/main/scala/opengpu/system/TessellationPatchInputDma.scala":
                affected.add("src/test/scala/opengpu/system/TessellationPatchInputDmaContextSpec.scala")
            if name == "src/main/scala/opengpu/system/TessellationSystemIsolineImagePipeline.scala":
                affected.add("src/test/scala/opengpu/graphics/TessellationSystemIsolineImageSharedSystemSpec.scala")
            if name == "src/main/scala/opengpu/graphics/SharedShaderTaskRouter.scala":
                affected.add("src/test/scala/opengpu/graphics/SharedShaderTessellationRouterSpec.scala")
                affected.add("src/test/scala/opengpu/graphics/GeometryFragmentTessellationRouterSpec.scala")
            if name == "src/main/scala/opengpu/system/GeometryFragmentShaderRouter.scala":
                affected.add("src/test/scala/opengpu/graphics/GeometryFragmentTessellationRouterSpec.scala")
            if name == "src/main/scala/opengpu/graphics/TessellationCuIsolinePatch.scala":
                affected.add("src/test/scala/opengpu/graphics/TessellationCuIsolineAdmissionSpec.scala")
            if name == "src/main/scala/opengpu/graphics/TessellationCuBarrier.scala":
                affected.add("src/test/scala/opengpu/core/GpuComputeUnitTessellationSpec.scala")
            if name == "src/main/scala/opengpu/graphics/TessellationCuFinish.scala":
                affected.add("src/test/scala/opengpu/core/GpuComputeUnitTessellationSpec.scala")
            if name == "src/main/scala/opengpu/graphics/TessellationControlVectorAccess.scala":
                affected.add("src/test/scala/opengpu/graphics/TessellationControlVectorContextSpec.scala")
                affected.add("src/test/scala/opengpu/core/GpuComputeUnitTessellationSpec.scala")
                affected.add("src/test/scala/opengpu/core/frontend/decode/TessellationExecutionAvailabilitySpec.scala")
            if name == "src/main/scala/opengpu/graphics/TessellationWarpPatchAccess.scala":
                affected.add("src/test/scala/opengpu/graphics/TessellationWarpPatchContextSpec.scala")
            if name == "src/main/scala/opengpu/system/ControlledIsolineSharedCuViewport.scala":
                affected.add("src/test/scala/opengpu/system/ControlledIsolineSharedCuViewportAdmissionSpec.scala")
                affected.add("src/test/scala/opengpu/system/ControlledIsolineSharedCuClipAdmissionSpec.scala")
                affected.add("src/test/scala/opengpu/graphics/Fp32ViewportStageSpec.scala")
            if name == "src/main/scala/opengpu/system/ControlledIsolineWindowBinPass.scala":
                affected.add("src/test/scala/opengpu/system/ControlledIsolineWindowBinPassSpec.scala")
                affected.add("src/test/scala/opengpu/graphics/ControlledIsolineWindowBinTypedImagePipelineSpec.scala")
            if name == "src/main/scala/opengpu/system/ControlledIsolineWindowBinTypedImagePipeline.scala":
                affected.add("src/test/scala/opengpu/graphics/ControlledIsolineWindowBinTypedImagePipelineSpec.scala")
                affected.add("src/test/scala/opengpu/graphics/ControlledIsolineWindowBinTypedImageSharedSystemSpec.scala")
            if name == "src/main/scala/opengpu/system/ControlledIsolineSharedCuClip.scala":
                affected.add("src/test/scala/opengpu/system/ControlledIsolineSharedCuClipAdmissionSpec.scala")
                affected.add("src/test/scala/opengpu/system/ControlledIsolineSharedCuRendererAdmissionSpec.scala")
                affected.add("src/test/scala/opengpu/graphics/PrimitiveClipStageSpec.scala")
            if name == "src/main/scala/opengpu/system/ControlledIsolineSharedCuRenderer.scala":
                affected.add("src/test/scala/opengpu/system/ControlledIsolineSharedCuRendererAdmissionSpec.scala")
                affected.add("src/test/scala/opengpu/system/IsolinePatchInputReaderSpec.scala")
                affected.add("src/test/scala/opengpu/graphics/TessellationControlledIsolineRendererSpec.scala")
                affected.add("src/test/scala/opengpu/system/IsolineGeometryCuExecutorSpec.scala")
            if name == "src/main/scala/opengpu/system/IsolinePatchSharedCu.scala":
                affected.add("src/test/scala/opengpu/system/IsolinePatchSharedCuAdmissionSpec.scala")
                affected.add("src/test/scala/opengpu/graphics/TessellationIsolinePatchEvaluationSpec.scala")
                affected.add("src/test/scala/opengpu/system/IsolineGeometryCuExecutorSpec.scala")
            if name == "src/main/scala/opengpu/graphics/TessellationIsolinePatchPipeline.scala":
                affected.add("src/test/scala/opengpu/graphics/TessellationIsolinePatchCancellationSpec.scala")
            if name == "src/main/scala/opengpu/system/GeometryReplayFragmentPipeline.scala":
                affected.add("src/test/scala/opengpu/graphics/GeometryReplayFragmentPipelineSpec.scala")
            if name == "src/main/scala/opengpu/system/ControlledIsolineReplayFragmentPipeline.scala":
                affected.add("src/test/scala/opengpu/graphics/ControlledIsolineReplayFragmentPipelineSpec.scala")
            if name == "src/main/scala/opengpu/system/GeometryReplayRasterClient.scala":
                affected.add("src/test/scala/opengpu/graphics/GeometryReplayRasterClientSpec.scala")
                affected.add("src/test/scala/opengpu/graphics/ControlledIsolineReplayRasterClientSpec.scala")
            if name == "src/main/scala/opengpu/system/GeometryWindowBinPass.scala":
                affected.add("src/test/scala/opengpu/graphics/GeometryWindowBinPassSpec.scala")
            if name == "src/main/scala/opengpu/system/GeometryFragmentDrawPipeline.scala":
                affected.add("src/test/scala/opengpu/graphics/GeometryFragmentDrawPipelineSpec.scala")
            candidate = name.replace("src/main/scala/", "src/test/scala/", 1)
            candidate = candidate[:-6] + "Spec.scala"
            if candidate in files:
                affected.add(candidate)
    # Include inherited suites and consumers of shared test fixtures, even if
    # the fixture filename itself does not end in Spec.scala. References are
    # deliberately conservative across packages (extra coverage is safe).
    selected = set().union(*(files[path] for path in affected if path in files))
    while True:
        symbols = {nodes[index][0] for index in selected}
        consumers = {index for index, (_, references, _) in enumerate(nodes)
                     if references & symbols}
        expanded = selected | consumers
        if expanded == selected:
            break
        selected = expanded
    suites = {nodes[index][2] for index in selected if nodes[index][2]}
    if any(s.startswith("opengpu.graphics.") and s.endswith("HostAxiSpec") for s in suites):
        # Exercise small ownership and command components when full-host tests
        # or their shared fixtures change. Full rendering remains local.
        suites.update({"opengpu.graphics.NativeVisibilityQueryEngineSpec",
                       "opengpu.graphics.NativePrimitiveQueryEngineSpec",
                       "opengpu.graphics.NativeTransformFeedbackControlSpec",
                       "opengpu.graphics.TileBinHostQueryCommandPortSpec",
                       "opengpu.graphics.NativeBinnedGraphicsJobReaderSpec",
                       "opengpu.graphics.NativeRenderTargetExtentSpec",
                       "opengpu.graphics.StagedTypedImageAdmissionSpec"})
    for qualification, checks in LOCAL_QUALIFICATION_CHECKS.items():
        if qualification in suites:
            suites.update(checks)
    return sorted(suite for suite in suites if not is_local_only_suite(root, suite))


if __name__ == "__main__":
    root = Path(__file__).resolve().parents[1]
    deferred = sorted(local_only_suites(root))
    if deferred:
        print("Local-only qualification outside CI: " + ", ".join(deferred), file=sys.stderr)
    print(json.dumps(changed_suites(root, sys.argv[1:]), separators=(",", ":")))
