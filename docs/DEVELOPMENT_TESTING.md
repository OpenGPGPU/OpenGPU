# Development test size and product qualification

## PR validation

Open PRs targeting `main`, including drafts, run the same bounded checks with a
15-minute job limit. Scala/build/CI changes compile all sources and execute the
selected suites from the fast PR catalog; documentation-only changes run host
script checks. The job summary reports the executed test and suite counts.
Zero-test, incomplete, failed, canceled or pending ScalaTest runs fail validation.

Host checks, full Scala compilation and affected-case registration finish first.
The bounded unit catalog and exact affected-test partitions then run in parallel,
sharing the completed compilation cache. Both test lanes remain required by
`ci-ok`; a failed, canceled or unexpectedly skipped lane prevents merging.

The required `ci-ok` gate also checks that the PR is ready for review and that
the tested head is still current. Drafts therefore receive test feedback while
remaining ineligible to merge. Marking a PR ready triggers fresh validation.
Broad integration regressions run in the separate regression workflow.
Each selected regression shard streams test output and records its completed
ScalaTest count in the job summary. An unexpectedly empty shard or an empty,
incomplete, failed, canceled or pending ScalaTest run fails validation.

## Regression simulation builds

The [native render-to-texture visibility qualification](NATIVE_RENDER_TEXTURE_VISIBILITY.md)
runs explicitly outside PR/nightly lanes with 25x4 frames and separate JVMs for
1x/4x sampling. Its strict result gate requires both selected hardware cases.

The [native texture characteristic qualification](NATIVE_TEXTURE_CHARACTERISTIC_RATES.md)
separately measures trilinear, miss, same-bank and dependent sampling with exact
results. Run its four profiles explicitly in fresh JVMs outside PR/nightly lanes.

The [concurrent texture binding qualification](NATIVE_TEXTURE_BINDING_CAPACITY.md)
checks all 32 bindings per stage through actual 128-invocation CU programs.
Its local/spill profiles also run explicitly outside PR/nightly lanes.

Graphics validation shares compiled CU and native raster-engine libraries,
with one compiler and the
existing C++ source/function splits, with `-Dopengpu.simLowMemOpt=1` selecting
`-O1`. Other low-memory profiles keep the default `0`, including the widest
32-lane runtime shard. Compiler options remain part of the model-cache key.
This follows [Verilator's optimization guidance](https://verilator.org/guide/latest/simulating.html).

`-Dopengpu.simCcache=target/ccache` enables a separate compiler-object cache.
It requires `ccache` and Python 3, limits that directory to 512 MB, and checks
compiler content. Verilator input paths become workspace-relative so temporary
JVM/workspace names do not invalidate otherwise identical generated C++.
Compiler flags and RTL assertions are retained; diagnostics use build-relative
paths. Executable models use a separate cache namespace for this mode.
Precompiled headers are compiled directly in each workspace: restoring a Clang
PCH from another workspace can reference deleted headers. Ordinary compiler
objects continue to use ccache with the same compiler arguments.
Omit the property or use `-Dopengpu.simCcache=off` to disable object caching.

A local control with executable caching disabled passes all five `GpuSimSpec`
cases in two fresh JVMs and a hierarchical build, using the same object-cache
directory and low-memory `-O1`. Controls cover counter restart, changed RTL
width, a failing host expectation and an actual failing RTL assertion. These
small-model results do not establish full-host build times.

Reproduce object reuse by running this command twice with different
`opengpu.simWorkspace` directories, retaining the same compiler-cache directory:

```sh
sbt -Dopengpu.simCache=off -Dopengpu.simWorkspace=target/cache-check-a \
  -Dopengpu.simCcache=target/ccache-check -Dopengpu.simJobs=1 \
  -Dopengpu.simHierarchy=Counter,AssertionSentinel \
  -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  'testOnly opengpu.testutil.GpuSimSpec'
CCACHE_DIR="$PWD/target/ccache-check" ccache --show-stats
```

Native raster callers use `Fp32PrimitiveRasterEngine`, a port-only wrapper
containing the owner and its verification layers. This permits hierarchy at
the engine boundary without external assertions reaching across a compiled
library boundary. Assertions remain enabled and the wrapper adds no cycles.
Use `-Dopengpu.simHierarchy=GpuComputeUnit,Fp32PrimitiveRasterEngine,Rgba8BlendFmaEngine` for native
pipeline/host tests. A direct split at `Fp32PrimitiveRasterStage` does not
contain its assertion captures. The blend-engine boundary also shares compiled FP32 FMA libraries across
typed/native pixel models and retains their verification layers. Local complete
pixel-owner and macro-backed component checks use this same boundary. This is
a build configuration, not a GPU throughput or clock qualification.

A local cached-model comparison of the same six-frame, 8×8 native-host case
(two CUs, four lanes, two warps, 1x/4x samples and three primitive kinds) passed
at both settings: approximately 163 seconds at `-O0` and 56 seconds at `-O1`.
These are simulator wall times. Linux g++ compilation of the three largest
corresponding C++ units peaked at 399 MiB RSS for `-O0` and 351 MiB for `-O1`
under a 4 GiB limit with swap disabled. Cache isolation/failure controls,
selection checks and host scripts also pass; whole-nightly runtime remains to
be measured on GitHub.

```sh
sbt -Dopengpu.simHierarchy=GpuComputeUnit -Dopengpu.simJobs=1 \
  -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  'testOnly opengpu.graphics.NativeTypedGraphicsHostAxiSpec -- -t "should execute GPU-memory triangle line and point shader draws with translated resources and cached image consumers local=true"'
```

The fast catalog includes the uniform-load and instruction-retirement state
machines, alongside provider-permission decoding. These component tests execute
real descriptor reads, bounds and permission faults, lane coalescing, held
results and cancellation drain without compiling a complete CU or host. Full
native-frame and concurrent-capacity checks remain separate integration work.

## Tile transfer performance qualification

The persistent-tile pipeline suite keeps ordered multi-draw, attachment locality,
load/clear and sample checks in routine validation. Transfer-credit performance
comparisons rebuild the complete renderer at several queue capacities and run
only with the explicit local flag below. They are omitted from the routine test
catalog rather than reported as skipped tests. The qualification retains the
original image, traffic, latency, credit and external-window assertions.

```sh
sbt -J-Xmx2G -Dopengpu.simJobs=1 \
  -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  -Dopengpu.simSeparateBuild=1 -Dopengpu.simReleaseHeap=1 \
  -Dopengpu.bankedRenderer=true -Dopengpu.tileStoreCredits=8 \
  -Dopengpu.tileTransferQualification=1 \
  'testOnly opengpu.graphics.PersistentTileRenderPipelineSpec'
```

## Full Mobile binned-host qualification

`BinnedTileResourceQualificationSpec` remains outside PR/nightly CI. It requires
`-Dopengpu.fullHostQualification=1` and rejects `CI=true` before elaboration.
Its four-CU fixtures retain sixteen lanes, eight warps, the Mobile execution
profile's 128 KiB L2 and the characterized SRAM models. Compact host fixtures
keep their existing small caches. This distinguishes resource qualification
from a small rendering check without changing product RTL capacities.

Four-CU runs require at least 12 GiB free on the simulation workspace filesystem
before building. Generated C++, object files and archive/link temporaries can
exhaust a nearly full disk. Run each selected case in a fresh JVM so a retained
elaborated model cannot consume the next case's heap:

```sh
git submodule update --init depends/asap7_sram_0p0
qualify_binned_case() {
  sbt -J-Xmx6G -Dopengpu.fullHostQualification=1 -Dopengpu.productQualification=0 \
    -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
    -Dopengpu.simSeparateBuild=1 -Dopengpu.simReleaseHeap=1 \
    -Dopengpu.simHierarchy=GpuComputeUnit \
    'set Test / parallelExecution := false' \
    "testOnly opengpu.graphics.BinnedTileResourceQualificationSpec -- $1"
}
qualify_binned_case '-z "CUs=4 lanes=16 warps=8 banked=true local=true payloadBytes=65536 defaultPayload=true"'
qualify_binned_case '-t "should compute FP32 coordinates and sample textures through native AXI payloads mode=full-batch CUs=4 lanes=16 warps=8"'
```

Both cases render 32×16 images with two tile engines and a 64 KiB payload arena.
The first checks AXI ownership and queued copies with bounded renderer batches;
the second exercises a full 128-thread fragment batch and changed textures in
fresh VMs. These are functional/resource checks, not 720p or FPS qualification.

## Frame sizes

Routine rendering tests use **640×480 or smaller**. Prefer small tiles and images
for shader, format, fault, ownership and reset checks. Full-frame regressions
use `GraphicsTestResolution`: the default is 640×480, or 1200 tiles at 16×16,
instead of 3600 tiles at 1280×720. Complete image, byte-count, continuation and
two-engine assertions still apply to every tested pixel and tile.

The normal PR and nightly workflows set `-Dopengpu.productQualification=0`.
They retain their existing suite catalogs. Product RTL capacities, Mobile
resource budgets and the 720p product acceptance target remain unchanged.
Historical 720p measurements keep their original workload scope; VGA cycle
counts cannot be presented as new 720p results.

For example, run a default frame check with:

```sh
sbt 'testOnly opengpu.graphics.TileBinRenderPipelineSpec -- -z "clear and store a complete 640x480 frame"'
```

Run 720p deliberately outside the routine CI lanes:

```sh
sbt -Dopengpu.productQualification=1 'testOnly opengpu.graphics.TileBinRenderPipelineSpec -- -z "1280x720"'
```

The same switch selects the full-frame grid, directory, scissor, traversal and
persistent-tile viewport fixtures. It does not change production defaults.
Test names and measurement logs include the selected full-frame size.

Large scalar coordinates, texture dimensions, address limits and synthetic
metadata indices still belong in bounded boundary tests. For example, report
bits on either side of index 8192 must exercise both SRAM banks even though
a VGA 8×8 grid contains only 4800 tiles. Such checks do not render or traverse
an oversized framebuffer.

Required case partitions keep up to eight tests of one hardware profile together,
so they reuse the compiled model. The same exact case set must appear once across
all partitions; the runner rejects missing, duplicated or unexpected executed
cases. Different hardware profiles retain separate cache groups. Small suites
with at most two cases of one profile may run as a whole suite.

`TypedTileRenderPipelineSpec` keeps all seven cases in three model groups:
five use the default local payload pool, one uses global payloads, and one
exercises a single payload slot. The groups can compile independently;
the default local model remains shared by its five cases. Validate this
assignment with `python3 scripts/test_ci_case_partitions.py`.

For host-test edits consisting only of additions inside existing test bodies,
the PR gate uses ScalaTest's registered source locations to run the affected
cases in every subclass that registers them. It does not rerun unrelated tests
from the same base class. Fixture, registration, removal and matching production
changes retain whole-suite selection; missing locations also fall back to it.
Case subsets always use explicit test names and exact XML result verification.
The nightly/manual regression still runs the complete catalog.
Validate the selector with `python3 scripts/test_ci_changed_host_cases.py`;
`bash scripts/test_scripts.sh` also checks partition and XML-result validation.

Replay image cases name both payload locality and one-slot configuration so
their complete case set shares three bounded model groups. Required PR test
steps have a 75-minute limit within the 90-minute job limit. Completed,
content-addressed simulation executables are saved after failures as well as
success; incomplete temporary copies are removed first. A restored model still
executes every selected test and must pass fresh XML validation.

Passive fragment-event forwarding does not require another full image/replay or
generic-host sweep when the exact native typed and binned AXI witness cases are
already required for both local and global payloads. The planner verifies that
wrapper changes consist only of the new output and its direct assignment; the
host exception permits only the native counter-source selection with the old
fallback unchanged. Wrapper test bodies and fixtures must be unchanged (replay
case-name metadata may change). Missing witnesses or any broader edit retains
the original suites. Native fragment tests, changed aggregation tests and raster
hierarchy tests remain required; full suites remain in nightly/manual CI.
Validate with `python3 scripts/test_ci_telemetry_coverage.py`.
