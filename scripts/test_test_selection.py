#!/usr/bin/env python3
"""Check that boundary edits cannot silently omit system regressions."""
from pathlib import Path
import json
import subprocess

root = Path(__file__).resolve().parents[1]


def select(path):
    return subprocess.check_output(
        ["bash", "scripts/select_tests.sh", path], cwd=root, text=True
    ).split()


def shard_cmd(*args):
    return subprocess.check_output(
        ["bash", "scripts/ci_shard_tests.sh", *args], cwd=root, text=True
    ).strip()


for path in (
    "graphics/RenderHost.scala",
    "command/GpuCommandRouter.scala",
    "dma/CopyEngine.scala",
    "core/memory/TranslatedWordClient.scala",
    "system/GpuHostAxi.scala",
):
    selected = select("src/main/scala/opengpu/" + path)
    assert "opengpu.system.*" in selected, (path, selected)
    assert "opengpu.graphics.*" in selected, (path, selected)
assert select("src/main/scala/opengpu/config/GpuConfig.scala") == ["ALL"]
assert select("src/main/scala/yunsuan/fpu/FloatFMA.scala") == ["ALL"]
assert select("README.md") == ["NONE"]
assert select(".github/workflows/scala_test.yml") == ["ALL"]
assert select("scripts/select_tests.sh") == ["ALL"]
vector_selected = select("src/main/scala/opengpu/core/vector/VectorFdivAlu.scala")
assert "opengpu.core.vector.*" in vector_selected, vector_selected
assert "opengpu.core.backend.VectorBackendSpec" in vector_selected, vector_selected

# CI shard partition: every Spec belongs to exactly one shard, except the
# desktop scaling sweeps that local `sbt test` still runs. Selection
# intersects so a graphics-only change only schedules the graphics runner.
all_specs = {
    str(p.relative_to(root / "src/test/scala").with_suffix("")).replace("/", ".")
    for p in (root / "src/test/scala").rglob("*Spec.scala")
}
ci_skip = {
    "opengpu.system.FullPoolRuntimeScalingSpec",
    "opengpu.system.RuntimeSharedGraphicsPerformanceSpec",
    "opengpu.system.FullPoolTextureScalingSpec",
    "opengpu.system.PersistentTileHostAxiSpec",
    "opengpu.system.PersistentTileStoreFaultSpec",
    "opengpu.system.PersistentTileTextureVisibilitySpec",
    "opengpu.system.PersistentTileProductMsaaSpec",
    "opengpu.system.DrmProgrammableMsaaAxiSpec",
    "opengpu.system.DrmFragmentImageAxiSpec",
    "opengpu.system.DrmComputeProgramReplaySpec",
    "opengpu.system.GpuSystemPayloadSpec",
    "opengpu.system.SharedPayloadClientSystemSpec",
}
shards = (
    "graphics",
    "system-mem",
    "system-host",
    "system-walk",
    "system-tex",
    "system-wide",
    "system-core",
    "system-rt-narrow",
    "system-rt-16",
    "system-rt-32",
    "system-rt-batch",
    "system-pool",
    "core-a",
    "core-b",
)
assigned = {}
for shard in shards:
    suites = shard_cmd("suites", shard, "ALL").split()
    for suite in suites:
        assert suite not in assigned, (suite, assigned[suite], shard)
        assigned[suite] = shard
assert set(assigned) == all_specs - ci_skip, (
    sorted(all_specs - ci_skip - set(assigned)),
    sorted(set(assigned) - (all_specs - ci_skip)),
)
assert ci_skip.isdisjoint(assigned)
assert assigned["opengpu.system.GpuHostAxiOwnershipSpec"] == "system-mem"
for suite in ("Fp32CentroidInterpolationSpec", "Fp32DepthResolutionRasterSpec", "PolygonDepthOffsetSpec", "PolygonDepthOffsetRasterSpec", "PolygonDepthResolutionSpec"):
    assert assigned["opengpu.graphics." + suite] == "graphics"
assert json.loads(shard_cmd("shards-json", "ALL")) == list(shards)
assert json.loads(shard_cmd("shards-json", "NONE")) == []
assert json.loads(shard_cmd("shards-json", "opengpu.graphics.*")) == ["graphics"]
assert shard_cmd("flags", "system-rt-32") == "-Dopengpu.simHierarchy=GpuComputeUnit -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1"
assert shard_cmd("flags", "system-rt-narrow") == "-Dopengpu.simHierarchy=GpuComputeUnit -Dopengpu.simJobs=2"
assert shard_cmd("flags", "system-wide") == "-Dopengpu.simHierarchy=GpuComputeUnit -Dopengpu.simJobs=2"
assert shard_cmd("flags", "system-mem") == ""
assert shard_cmd("flags", "graphics") == ""
graphics_fanout = " ".join(select("src/main/scala/opengpu/graphics/RenderHost.scala"))
scheduled = json.loads(shard_cmd("shards-json", graphics_fanout))
assert "graphics" in scheduled
for shard in shards:
    if shard.startswith("system-"):
        assert shard in scheduled, shard
assert json.loads(shard_cmd("shards-json", " ".join(vector_selected))) == [
    "core-b"
]

print("Integration test selection: PASS")
