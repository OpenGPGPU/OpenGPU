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
}


def local_only_suites(root):
    path = root / "scripts/ci_local_only_suites.txt"
    if not path.exists():
        return set()
    return {line.strip() for line in path.read_text().splitlines()
            if line.strip() and not line.lstrip().startswith("#")}


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
    for name in changed:
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
    for qualification, checks in LOCAL_QUALIFICATION_CHECKS.items():
        if qualification in suites:
            suites.update(checks)
    return sorted(suites - local_only_suites(root))


if __name__ == "__main__":
    root = Path(__file__).resolve().parents[1]
    deferred = sorted(local_only_suites(root))
    if deferred:
        print("Local-only qualification outside CI: " + ", ".join(deferred), file=sys.stderr)
    print(json.dumps(changed_suites(root, sys.argv[1:]), separators=(",", ":")))
