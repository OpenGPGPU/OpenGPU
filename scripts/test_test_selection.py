#!/usr/bin/env python3
"""Check that boundary edits cannot silently omit system regressions."""
from pathlib import Path
import json
import re
import subprocess
import shutil
import tempfile

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
def declared_suites(path):
    source = path.read_text()
    package = re.search(r"^package\s+([\w.]+)", source, re.M).group(1)
    return {
        package + "." + match.group(1)
        for match in re.finditer(r"^(?:final\s+)?class\s+(\w+Spec)\b", source, re.M)
    }


suites_by_file = {
    str(p.relative_to(root / "src/test/scala").with_suffix("")).replace("/", "."):
        declared_suites(p)
    for p in (root / "src/test/scala").rglob("*Spec.scala")
}
all_specs = set().union(*suites_by_file.values())
ci_skip_files = {
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
ci_skip = set().union(*(suites_by_file[name] for name in ci_skip_files))
graphics_shards = tuple(f"graphics-{i}" for i in range(16))
texture_shards = tuple(f"system-tex-{i}" for i in range(8))
wide_shards = tuple(f"system-wide-{i}" for i in range(4))
shards = graphics_shards + (
    "system-mem",
    "system-host",
    "system-walk",
) + texture_shards + wide_shards + (
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
assert assigned["opengpu.system.ComputeResourceCapabilitiesAxiSpec"] == "system-mem"
assert assigned["opengpu.system.NativeUniformCapabilitiesAxiSpec"] == "system-mem"
assert assigned["opengpu.system.NativeRgba8TextureMemoryFabricSpec"] == "system-mem"
assert assigned["opengpu.system.ComputeResourceFullHostSpec"] == "system-host"
assert assigned["opengpu.system.HostExecutionAvailabilitySpec"] == "system-host"
assert assigned["opengpu.system.GpuHostIndirectComputeSpec"] == "system-host"
assert assigned["opengpu.system.GpuSystemComputeGeometrySpec"] == "system-core"
assert assigned["opengpu.system.GpuSystemIndirectComputeSpec"] == "system-core"
assert assigned["opengpu.system.GpuSystemIndirectTranslationResetSpec"] == "system-core"
if (root / "src/test/scala/opengpu/system/ComputeTextureAvailabilitySpec.scala").exists():
    assert assigned["opengpu.system.ComputeTextureAvailabilitySpec"] == "system-core"
for filename, suites in {
    "NativeStructuredSimtHostAxiSpec.scala": (
        "NativeTypedStructuredSimtHostAxiSpec", "NativeBinnedStructuredSimtHostAxiSpec",
        "NativeDefaultStructuredSimtHostAxiSpec"),
    "NativeShaderExecutionHostAxiSpec.scala": (
        "NativeTypedShaderExecutionHostAxiSpec", "NativeBinnedShaderExecutionHostAxiSpec"),
    "NativeShaderRunningResetHostAxiSpec.scala": (
        "NativeTypedShaderRunningResetHostAxiSpec", "NativeBinnedShaderRunningResetHostAxiSpec"),
    "NativeShaderAvailabilityHostAxiSpec.scala": (
        "NativeTypedShaderAvailabilityHostAxiSpec", "NativeBinnedShaderAvailabilityHostAxiSpec"),
}.items():
    if (root / "src/test/scala/opengpu/graphics" / filename).exists():
        for suite in suites:
            assert assigned["opengpu.graphics." + suite] in graphics_shards
for phantom in (
    "NativeStructuredSimtHostAxiSpec", "NativeShaderExecutionHostAxiSpec",
    "NativeShaderRunningResetHostAxiSpec", "NativeShaderAvailabilityHostAxiSpec",
):
    assert "opengpu.graphics." + phantom not in assigned
assert assigned["opengpu.core.frontend.decode.DecoderSpec"] == "core-b"
assert "opengpu.core.frontend.decode.ScalarDecoderSpec" not in assigned
for suite in ("Fp32CentroidInterpolationSpec", "Fp32DepthResolutionRasterSpec", "PolygonDepthOffsetSpec", "PolygonDepthOffsetRasterSpec", "PolygonDepthResolutionSpec"):
    assert assigned["opengpu.graphics." + suite] in graphics_shards
populated_shards = [shard for shard in shards if shard_cmd("suites", shard, "ALL")]
assert json.loads(shard_cmd("shards-json", "ALL")) == populated_shards
assert json.loads(shard_cmd("shards-json", "NONE")) == []
assert json.loads(shard_cmd("shards-json", "opengpu.graphics.*")) == list(graphics_shards)
assert shard_cmd("flags", "system-rt-32") == "-Dopengpu.simHierarchy=GpuComputeUnit -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1"
assert shard_cmd("flags", "system-rt-narrow") == "-Dopengpu.simHierarchy=GpuComputeUnit -Dopengpu.simJobs=2"
for shard in texture_shards + wide_shards:
    assert shard_cmd("flags", shard) == "-Dopengpu.simHierarchy=GpuComputeUnit -Dopengpu.simJobs=1"
assert shard_cmd("flags", "system-mem") == ""
for shard in graphics_shards:
    assert shard_cmd("flags", shard) == "-Dopengpu.simHierarchy=GpuComputeUnit,Fp32PrimitiveRasterEngine -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1"
for shard in ("system-host", "core-b"):
    assert shard_cmd("flags", shard) == "-Dopengpu.simHierarchy=GpuComputeUnit -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1"
# Keep all concrete suites in a source file on one runner; inherited scenarios
# then reuse the same cached Verilator model rather than compiling it twice.
for name, suites in suites_by_file.items():
    if name.startswith("opengpu.graphics."):
        assert len({assigned[suite] for suite in suites}) <= 1, name
one_suite = "opengpu.graphics.Fp32CentroidInterpolationSpec"
assert json.loads(shard_cmd("shards-json", one_suite)) == [assigned[one_suite]]
graphics_fanout = " ".join(select("src/main/scala/opengpu/graphics/RenderHost.scala"))
scheduled = json.loads(shard_cmd("shards-json", graphics_fanout))
assert set(graphics_shards).issubset(scheduled)
for shard in populated_shards:
    if shard.startswith("system-"):
        assert shard in scheduled, shard
assert json.loads(shard_cmd("shards-json", " ".join(vector_selected))) == [
    "core-b"
]


# Changes to the independent compressed-texture oracle must exercise consumers.
for name in ("decompress-etc.c", "decompress-eac.c", "detex.h", "oracle.c", "UPSTREAM.json"):
    assert select("src/test/resources/opengpu/texture/detex/" + name) == ["opengpu.graphics.*"]

print("Integration test selection: PASS")

# The PR lane is bounded independently of broad integration fan-out.
pr_suites = shard_cmd("pr-suites", "ALL").split()
assert len(pr_suites) == len(set(pr_suites)) == 21
assert "opengpu.graphics.BankedTypedTileMemorySpec" in pr_suites
assert "opengpu.graphics.StreamingRgba8EncoderSpec" in pr_suites
assert "opengpu.core.frontend.decode.UniformExecutionAvailabilityDecodeSpec" in pr_suites
assert {"opengpu.graphics.ShaderUniformLoadUnitSpec",
        "opengpu.graphics.ShaderUniformExecuteUnitSpec"} <= set(pr_suites)
assert "opengpu.graphics.TextureLaneStartSpec" in pr_suites
assert set(pr_suites) <= all_specs
assert "opengpu.graphics.BankedTypedTileMemorySpec" in shard_cmd(
    "pr-suites", " ".join(select("src/main/scala/opengpu/graphics/BankedTypedTileMemory.scala"))).split()
assert not any("GpuComputeUnit" in suite or "HostAxi" in suite for suite in pr_suites)
assert shard_cmd("pr-suites", "NONE") == ""
assert shard_cmd("pr-suites", "opengpu.core.trap.*").split() == pr_suites
assert set(shard_cmd("pr-suites", "opengpu.graphics.*").split()) == {
    suite for suite in pr_suites if suite.startswith("opengpu.graphics.")
}
for path in ("scripts/ci_pr_suites.txt", ".github/workflows/scala_regression.yml",
             ".github/actions/setup-simulation/action.yml", "scripts/prefetch_scala_bridge.sh"):
    assert select(path) == ["ALL"], path

# A valid early match must survive catalogs larger than the shell pipe buffer.
# Exercise the real selector in isolation; also retain missing-suite rejection.
with tempfile.TemporaryDirectory(prefix="gpu-pr-catalog-") as temporary:
    fixture = Path(temporary)
    (fixture / "scripts").mkdir()
    sources = fixture / "src/test/scala/opengpu/graphics"
    sources.mkdir(parents=True)
    selector = fixture / "scripts/ci_shard_tests.sh"
    shutil.copy2(root / "scripts/ci_shard_tests.sh", selector)
    names = [f"CapacityCatalog{i:05d}Spec" for i in range(12000)]
    (sources / "CapacityCatalogSpec.scala").write_text(
        "package opengpu.graphics\n"
        + "".join(f"class {name} extends AnyFlatSpec\n" for name in names)
    )
    expected = ["opengpu.graphics." + names[i] for i in (0, -1)]
    catalog = fixture / "scripts/ci_pr_suites.txt"
    catalog.write_text("\n".join(expected) + "\n")
    command = ["bash", str(selector), "pr-suites", "ALL"]
    assert subprocess.check_output(command, text=True).split() == expected
    catalog.write_text("opengpu.graphics.MissingSpec\n")
    missing = subprocess.run(command, text=True, capture_output=True)
    assert missing.returncode != 0 and "MissingSpec" in missing.stderr

print("Large PR suite catalog selection: PASS")
