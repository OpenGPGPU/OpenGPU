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
    "opengpu.system.ControlledIsolineSharedCuClipQualificationSpec": {
        "opengpu.system.ControlledIsolineSharedCuClipAdmissionSpec",
        "opengpu.system.ControlledIsolineSharedCuRendererAdmissionSpec",
        "opengpu.graphics.PrimitiveClipStageSpec",
    },
    "opengpu.system.ControlledIsolineSharedCuRendererQualificationSpec": {
        "opengpu.system.ControlledIsolineSharedCuRendererAdmissionSpec",
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
    for name in changed:
        if name.startswith("src/main/scala/") and name.endswith(".scala"):
            if name == "src/main/scala/opengpu/system/ControlledIsolineSharedCuViewport.scala":
                affected.add("src/test/scala/opengpu/system/ControlledIsolineSharedCuViewportAdmissionSpec.scala")
                affected.add("src/test/scala/opengpu/system/ControlledIsolineSharedCuClipAdmissionSpec.scala")
                affected.add("src/test/scala/opengpu/graphics/Fp32ViewportStageSpec.scala")
            if name == "src/main/scala/opengpu/system/ControlledIsolineSharedCuClip.scala":
                affected.add("src/test/scala/opengpu/system/ControlledIsolineSharedCuClipAdmissionSpec.scala")
                affected.add("src/test/scala/opengpu/system/ControlledIsolineSharedCuRendererAdmissionSpec.scala")
                affected.add("src/test/scala/opengpu/graphics/PrimitiveClipStageSpec.scala")
            if name == "src/main/scala/opengpu/system/ControlledIsolineSharedCuRenderer.scala":
                affected.add("src/test/scala/opengpu/system/ControlledIsolineSharedCuRendererAdmissionSpec.scala")
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
            if name == "src/main/scala/opengpu/system/GeometryReplayRasterClient.scala":
                affected.add("src/test/scala/opengpu/graphics/GeometryReplayRasterClientSpec.scala")
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
