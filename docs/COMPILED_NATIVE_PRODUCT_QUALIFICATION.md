# Compiled native product qualification

This explicit local runner executes the same full-profile native host, six input recipes and independent stored-image oracle as `QualifyNativeProductRecipes`. The C++ driver services external AXI transactions inside the Verilator process. Rasterization, shaders, texture sampling, depth/stencil, blending, stores and resolves execute in the generated RTL.

The legacy hardware profile retains four CUs, sixteen lanes, eight resident warps, four native fragment workers, the original storage capacities, characterized SRAM models and a 256-bit AXI port. Smoke mode renders 32×32 scenes; it does not reduce execution or storage capacities. The mandatory two-CU product profile is selected explicitly below. Both modes require all six scenes. Neither the RTL build nor these simulations belongs in regular PR/nightly CI.

## Two-CU VGA target

The mandatory alignment target is two CUs at 640×480 and 60 FPS. Select
`--two-cu-vga` with either `--smoke` or `--product` in the compiled or FlashSim
runner. This explicit profile uses two CUs with sixteen lanes and eight warps
per CU, four native tile/fragment engines, one shared texture cluster, 128 KiB
L2, a 512 KiB payload pool and a 256-bit AXI port. It keeps the complete native
API capacities: 2048×2048 maximum extent, 65,536 directory entries, sixteen
attribute/binding/varying slots, four color outputs and four samples.
The engine count is independent of the CU count; the balanced configuration's
smaller engine count is not substituted for this product profile.

The display stays 640×480 in both modes. Smoke renders six 32×32 frames on the
same hardware; product renders all six complete 640×480 frames. Both use v2
frame descriptors and 8×8 tiles through `encodeApiExtent`. The runner enables
the API-capacity and full-host guards and sets `opengpu.productQualification=0`.
GCR1 and all eight GNC3 capability words are checked against the executing
hardware before submission. Manifests, execution metrics and the image-oracle
marker bind results to `two-cu-vga60-v1`; four-CU or reduced-capacity results
cannot qualify this profile.

```sh
python3 scripts/qualify_native_product_compiled.py --smoke --two-cu-vga \
  --output "$PWD/.artifacts/two-cu-vga-smoke" --jobs 2
python3 scripts/qualify_native_product_compiled.py --product --two-cu-vga \
  --output "$PWD/.artifacts/two-cu-vga-product" --jobs 2
```

For FlashSim, use `scripts/qualify_native_product_flashsim.py` with the same
mode/profile arguments plus `--flashsim-dir /path/to/FlashSim`. The equivalent,
parallel and extra-scene reverification tools also require `--two-cu-vga`.

The fresh assertion-enabled Verilator model passed all six 32×32 smoke scenes
on 2026-11-13, using one fresh model per scene and at most three concurrent
scenes. Every scene passed the independent complete stored-image, sample and
guard oracle with external traffic drained. Repeating the complete oracle
from the integrated source also passed; its emitted RTL, fixtures and profile
manifest matched the executing model's inputs byte for byte, and the model
and output bytes were unchanged across verification.

| Smoke scene | Service cycles | FlashSim cycles/s | Observed elapsed speedup |
| --- | ---: | ---: | ---: |
| Desktop composition | 90,223 | 3,209 | 18.06× |
| Textured 3D | 118,352 | 6,287 | 35.22× |
| Opaque overdraw | 123,913 | 2,091 | 11.71× |
| Transparent layers | 121,265 | 2,154 | 12.69× |
| Small triangles | 41,490 | 9,792 | 58.39× |
| 4× MSAA | 148,326 | 5,183 | 31.46× |

The assertion-enabled FlashSim model at source `3059d0c65` also passed all six
independent stored-image/sample/guard oracles. Both backends used a fresh model
per scene with at most three concurrent workers. Every service/GPU cycle count,
read/write/enabled byte count and all six fragment-phase counters matched
exactly. FlashSim used a verified resharded model, guarded precompiled header,
64 generation shards, 16 MiB reshard partitions, sixteen commit parts,
commit-block specialization, `-O2` and conservative aliasing.

The rates and elapsed ratios above are observations on this loaded machine;
system load was not standardized between backends. They do not predict other
models, product-frame throughput, whole-host clock or FPS. This is the frozen
v1 graphics-capacity host and does not qualify the integrated v2 feature set.
To reproduce with a matching FlashSim/CIRCT/reshard checkout and verified
resharded directory, use the preparation/generation/resharding steps below and
keep these build/execution settings consistent:

```sh
python3 scripts/qualify_native_product_flashsim.py --smoke --two-cu-vga \
  --flashsim-dir "$FLASH_GENERATOR" --output "$RUN" --jobs 3 --scene-jobs 3 \
  --shards 64 --reshard-dir "$RESHARDED_DUT" --reshard-tool "$FLASH_RESHARD" \
  --precompiled-header --shard-opt=-O2 --conservative-alias
```

Reproduce with the smoke command above and `--scene-jobs 3`. These counts
describe complete smoke frames on the full two-CU hardware profile; they do
not measure complete 640×480 product frames.

The same frozen `3059d0c65` v1 host subsequently completed all six **640×480**
product scenes with assertion-enabled FlashSim. The unchanged independent
Scala oracle passed every stored image, sample, resolve and guard. Each scene
used a fresh model; all external traffic drained. Matching RTL, fixtures,
generator, resharded model, executable and output bytes were checked before
and after execution and verification.

| Product scene | Service cycles |
| --- | ---: |
| Desktop composition | 14,411,950 |
| Textured 3D | 6,759,137 |
| Opaque overdraw | 29,074,499 |
| Transparent layers | 28,835,475 |
| Small triangles | 11,150,203 |
| 4× MSAA | 11,398,012 |

This establishes full-frame functional correctness for that v1 configuration.
Opaque and transparent overdraw exceed the 16,666,666-cycle planning budget at
1 GHz; no physically qualified clock or 60 FPS result is established. Current
v2 features, production driver snapshots and whole-host timing still require
their own matching complete-host qualification. Reproduce on the frozen source
with the matching assertion-enabled generator/reshard profile:

```sh
python3 scripts/qualify_native_product_flashsim.py --product --two-cu-vga \
  --flashsim-dir "$FLASH_GENERATOR" --output "$PRODUCT_RUN" --jobs 3 --scene-jobs 3 \
  --shards 64 --reshard-dir "$PRODUCT_RESHARDED_DUT" --reshard-tool "$FLASH_RESHARD" \
  --precompiled-header --shard-opt=-O2 --conservative-alias
```

These commands establish functional execution only after all scenes pass the
independent complete-image, sample and guard oracle with external traffic
drained. The 60 FPS requirement additionally needs every complete frame to fit
the matching physically qualified whole-host clock. At the 1 GHz planning
clock, the budget is 16,666,666 service cycles per frame; simulator wall time
and smoke counts cannot establish FPS. Whole-host timing, full-frame rate and
the remaining H6–H8 requirements remain open.

The frozen v1 whole-host synthesis attempt failed at its 14,400-second timeout
while Yosys was still in the 4.3 optimization stage. The container had two CPU
cores, 12 GiB of memory and no additional swap. It produced no completed QoR,
constraint-coverage or clock result. The 1 ns clock and 25% I/O delay below are
requested constraints; they are not measured timing. The later coverage check
was configured for 1,800 seconds and 10 GiB but was not reached. Whole-host
physical timing remains open.

Reproduce against RTL emitted for the frozen v1 two-CU full-capacity host,
limiting the synthesis container to the resources above:

```sh
python3 scripts/qualify_ppa.py "$RTL" GpuHostSystemAxi \
  --expected-sram-macro srambank_64x4x32_6t122=728 \
  --expected-sram-macro srambank_64x4x64_6t122=812 \
  --stage synthesis --synthesis-engine yosys --yosys-preserve-hierarchy \
  --clock io_s_axi_aclk --period-ps 1000 --io-delay 0.25 --timeout 14400 \
  --coverage-timeout 1800 --coverage-memory-gib 10 --output "$PPA_RUN"
```

These are the frozen v1 emission's source-instance macro expectations for the
current count gate. The timed-out historical run preceded that gate; it did
not establish mapped macro retention. Recompute expectations for another
emission with `scripts/report_ppa_sram_inventory.py` and require the actual ODB
comparison to pass.

## Integrated alignment host v2

`--alignment-v2` selects the separate `two-cu-vga60-v2` host. It implies the
two-CU/full-capacity guards and is mutually exclusive with `--two-cu-vga`,
which continues to select the frozen v1 graphics-capacity profile. V1 models
and results cannot qualify v2.

V2 retains the target dimensions and complete native capacities above. It
reserves sixteen memory transaction IDs per CU and connects compute images,
storage, uniforms, textures, atomic counters, image/storage atomics, indirect
dispatch, native fragment images, visibility/primitive queries, transform
feedback and the existing native tessellation paths together. Submission
checks every address and value in 72 distinct discovery words: GCR1, GNC3,
GCI1, GQV1, GFC1, GQP1 and GUC1. Manifest, metrics, replay guards and the image
oracle all require the explicit v2 identity and complete discovery contract.

These capabilities describe connected native paths. Ordinary native geometry
draw admission and native advanced-blend packets remain unavailable, and
patch shader image routing remains separate work. V2 records
`nativeGeometryDraw=false`, `nativeAdvancedBlend=false` and
`nativePatchShaderImages=false`; normal native fragment images remain enabled.
It does not claim
GLES conformance or completion of H7/H8.

Local validation passes the target/configuration rejection checks and complete
FIRRTL conversion of a bounded 4×4 host with the same feature connections,
two CUs and sixteen transaction IDs per CU. That routine fixture bounds lanes,
warps, attribute counts and native storage: four lanes, two warps, one native
engine, eight directory entries and a 4 KiB payload pool. The product factory
keeps the full capacities listed above.
All six generated 640×480 product inputs pass the real C++ command and memory
parsers, including every discovery word. CPU regressions reject mutations of
each discovery address/value and reject mixed v1/v2 profiles. These checks do
not establish v2 execution, complete-frame cycles, whole-host timing or FPS.

After integrating PR #769, all three profile/connection cases pass with a
3 GiB heap, including the complete feature connections in the bounded host.
The seven product/API/parallel/discovery/topology guard suites pass 91 cases
with no skips. These are connection, parser and orchestration checks; they do
not execute the complete v2 product frames.

```sh
python3 scripts/qualify_native_product_compiled.py --smoke --alignment-v2 \
  --output "$PWD/.artifacts/alignment-v2-smoke" --jobs 2
python3 scripts/qualify_native_product_flashsim.py --product --alignment-v2 \
  --flashsim-dir /path/to/FlashSim \
  --output "$PWD/.artifacts/alignment-v2-product" --jobs 2
python3 scripts/test_qualify_native_product_compiled.py
python3 scripts/test_qualify_equivalent_flashsim_product.py
python3 scripts/test_qualify_parallel_flashsim_product.py
sbt -J-Xmx3G 'testOnly opengpu.system.NativeAlignmentHostProfileSpec'
```

Run the full-model commands only after selecting the final feature source.
Build, execute and independently verify that source; do not relabel a v1 model
or a prior feature revision as v2.

### Compute/native command reuse

`scripts/qualify_native_alignment_sequence.py` uses that complete v2 hardware
with six 32×32 workloads. Each scene resets once, then executes
compute → native draw → compute → native draw without another reset. Both
compute epochs dispatch four 128-item groups and require both CUs active
together. The checker compares all 1,024 vector results, 64 scalar FP results,
their allocation guards, and all twelve stored native images against the
independent Scala oracle. Native shaders, descriptors, textures, vertices,
compute code and inputs must remain byte-identical. The existing native frame
clear operations initialize attachments again for the second draw; the driver
does not replace GPU results with software images.

```sh
python3 scripts/qualify_native_alignment_sequence.py \
  --flashsim-dir "$FLASH_GENERATOR" \
  --output "$PWD/.artifacts/alignment-sequence" --jobs 1
python3 scripts/test_native_alignment_sequence.py
sbt -J-Xmx3G 'testOnly opengpu.system.NativeAlignmentSequenceDataSpec opengpu.testutil.NativeFrameMemoryCodecSpec'
```

The runner also supports `--stage prepare|generate|build|run|verify` and the
same verified resharding, precompiled-header and conservative-alias options as
the product runner. Preparation emits the full target with all assertion bind
layers. Build and verification reject changed source, RTL, discovery profile,
generator, driver, executable or output bytes. Use a separate output directory
from the ordinary six-frame product run. Delete temporary outputs once their
active validation use ends.

This is a functional command-reuse qualification. It does not execute the
tessellation commands, establish complete feature/API coverage, render
640×480 product frames, or qualify whole-host timing and 60 FPS. CPU fixture,
parser and oracle tests alone do not establish RTL execution.

### Native tile decomposition comparison

`PrepareNativeTileComparison` emits explicit 8×8 and 16×16 alternatives for
the same six scenes: 32×32 with `--smoke` (also the default for the legacy
`--output DIR` syntax), or 640×480 with explicit `--product`. Both modes retain
the complete two-CU/API profile. Geometry, programs, state records, image allocations,
texture inputs and the full 65,536-entry directory allocation are identical;
only the GNF tile dimensions/count and resident attachment offsets change.
The ordinary product encoder still uses 8×8. Both tile sizes are already
advertised by the complete target; RGBA8 plus D24S8 at four samples occupies
8 KiB with 16×16 tiles, within the original 32 KiB resident store.

On the assertion-enabled frozen v1 model at `3059d0c65`, both alternatives
passed all six independent stored-image, sample and guard oracles. Each scene
used a fresh model. The original qualified model/generator/reshard identities
were checked before and after execution, and output/source bytes were unchanged
across verification.

| Scene | 8×8 service cycles | 16×16 service cycles |
| --- | ---: | ---: |
| Desktop composition | 90,223 | 66,779 |
| Textured 3D | 118,352 | 100,932 |
| Opaque overdraw | 123,913 | 102,422 |
| Transparent layers | 121,265 | 101,654 |
| Small triangles | 41,490 | 42,264 |
| 4× MSAA | 148,326 | 125,051 |

Five scenes use fewer cycles; small triangles regress by 1.9%. Larger tiles
are therefore a workload-dependent choice, not an unconditional throughput
improvement. This smoke comparison does not qualify v2, full-frame cycles,
physical timing or FPS, and does not replace the mandatory 8×8 grid/capacity
qualification. Wider formats and MRT combinations must fit resident storage
without dropping attachments or samples.

To repeat, first qualify a complete assertion-enabled v1 model with the normal
`--smoke --two-cu-vga` runner. Keep its source and model bytes fixed, then
prepare the alternatives from the matching input/shader ABI:

```sh
sbt -J-Xmx3G -Dopengpu.fullHostQualification=1 \
  -Dopengpu.productQualification=0 -Dopengpu.productTwoCuVga=1 \
  -Dopengpu.productApiCapacity=1 -Dopengpu.apiExtentQualification=1 \
  "Test / runMain opengpu.system.PrepareNativeTileComparison --smoke --output $PROBE"
for side in 8 16; do
  mkdir -p "$PROBE/tile$side/output"
  while IFS= read -r scene; do
    "$MODEL/native_product_driver" "$PROBE/tile$side/fixtures" \
      "$PROBE/tile$side/output" --scene "$scene"
  done < "$PROBE/tile$side/fixtures/suite.txt"
done
sbt -J-Xmx3G -Dopengpu.fullHostQualification=1 \
  -Dopengpu.productQualification=0 -Dopengpu.productTwoCuVga=1 \
  -Dopengpu.productApiCapacity=1 -Dopengpu.apiExtentQualification=1 \
  "Test / runMain opengpu.system.VerifyNativeTileComparison --smoke --output $PROBE"
```

For a full VGA comparison, use `--product` in both preparation and verification,
keeping `productQualification=0` for the mandatory two-CU target. Before reusing
a qualified model, require the 8×8 inputs and commands to match the original
640×480 product fixtures byte for byte, and verify its original source, RTL,
assertion, generator, reshard, build and executable guards before and after use.
Do not relabel the model as a newer hardware qualification. Verification checks
all twelve independent images/samples and pitched guards, plus every shader,
descriptor, vertex and texture byte outside actual writable allocations. CPU
tests cover partial tiles and deliberately corrupted pixels and read-only data;
they are not rendered-image evidence.

`PROBE` must be a temporary directory under this repository's `.artifacts/`.
Keep JVM/native temporary paths inside the GPU checkout; on macOS sbt appends
roughly 51 bytes to its temporary directory for a Unix socket, so choose a
unique absolute temporary path no longer than 52 bytes. Delete the comparison
inputs, outputs and logs once verification/diagnosis ends.

With shared MSHR, fragment, raster and texture-address definitions, the complete
target passed raw elaboration and FIRRTL conversion in
one JVM capped at 10 GiB. It retained two 16-lane/eight-warp CUs, sixteen
transactions per CU, four engines, 65,536 directory entries and the 512 KiB
payload pool, with black boxes and performance ports enabled. Raw elaboration
contained 16,831 modules and 10,938,756 recursive commands; conversion consumed
the same elaborated annotations without a second elaboration and exited zero.
Adding texture-address definition sharing reduced those counts from 19,771
modules and 13,122,056 commands: 2,183,300 fewer commands (16.64%), with the full
resource configuration unchanged. Compilation was included in the later run,
so these checks do not provide a comparable construction-time measurement.

This result precedes the parallel typed-blend integration in PR #769. It
establishes complete Chisel/FIRRTL construction for that implementation, with
no SystemVerilog, model, execution or physical qualification. Subsequent source
changes require their own matching qualification. An earlier implementation
before MSHR sharing passed raw elaboration but exhausted a 6 GiB heap during
conversion; that historical failure does not establish the current minimum
memory requirement. The bounded same-feature connection test passes conversion
at 3 GiB and exercises connections only.

To repeat the full-target conversion API check, place this temporary helper at
`.artifacts/AlignmentConversion.scala`:

```scala
package opengpu.system
object AlignmentConversion extends App {
  val p = NativeAlignmentHostProfile.target
  require(p.computeUnits == 2 && p.execution.lanes == 16 && p.execution.warps == 8 &&
    p.transactionsPerCu == 16 && p.native.engines == 4 &&
    p.native.pagedCapacity == 65536 && p.native.payload.get.capacityBytes == 524288)
  val raw = new chisel3.stage.phases.Elaborate().transform(Seq(
    chisel3.stage.ChiselGeneratorAnnotation(() => new NativeAlignmentHostSystemAxi(p,
      useBlackBoxes = true, exposePerformance = true))))
  require(raw.exists(_.isInstanceOf[chisel3.stage.ChiselCircuitAnnotation]))
  val converted = new chisel3.stage.phases.Convert().transform(raw)
  require(converted.nonEmpty)
  println("ALIGNMENT_CONVERSION_PASS noRtlEmitted=true noExecutionQualification=true")
}
```

```sh
sbt -J-Xmx10G -J-XX:+ExitOnOutOfMemoryError -J-XX:ActiveProcessorCount=2 \
  'set Test / unmanagedSources += baseDirectory.value / ".artifacts/AlignmentConversion.scala"' \
  'Test / runMain opengpu.system.AlignmentConversion'
```

Require the success marker and exit zero; remove the helper, build outputs and
logs after active use. This is an explicit full-target resource check outside
routine CI, and does not emit RTL or replace complete model/image/timing gates.

## Reproduce

Initialize the pinned SRAM submodule and install the repository's Scala/C++/Verilator toolchain. Use an absolute output directory under `.artifacts/`:

```sh
python3 scripts/qualify_native_product_compiled.py --smoke --two-cu-vga \
  --output "$PWD/.artifacts/native-compiled-smoke" --jobs 2
```

Run a deliberate product qualification separately:

```sh
python3 scripts/qualify_native_product_compiled.py --product --two-cu-vga \
  --output "$PWD/.artifacts/native-compiled-product" --jobs 2
```

`--stage prepare|build|run|verify` operates one stage. Preserve source and output bytes while a stage is live. If a stage fails, inspect its actual exit and log before retrying. An observation timeout does not terminate a build or simulation. The build cache is bound to emitted RTL, hardware profile, driver inputs, compiler/tool versions, flags and executable hash. Preparation, execution and verification reject stale source/model/fixture/output bytes. Raw outputs under `.artifacts/` are temporary: delete them when their active validation or diagnosis use ends, without waiting for PR merge. Retain concise final results, limits and reproduction commands rather than evidence archives.

Each new preparation invalidates the previous preparation record, manifest and
prepare log before starting. Both simulator runners require a fresh manifest
with `rtlEmitted: true` and exactly one final `NATIVE_COMPILED_INPUTS` line
matching all six scenes and the requested frame size. A zero process exit
without this completion line cannot certify retained RTL or fixture files.
The separate API-capacity preparation requires its own two-frame
`NATIVE_API_COMPILED_INPUTS` completion line and a fresh RTL-emitted manifest.
The regression executes real child processes, including a background worker
failure that leaves the parent exit code zero, against populated output trees:

```sh
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s scripts \
  -p test_qualify_native_product_compiled.py
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s scripts \
  -p test_qualify_native_api_compiled.py
```

Both compiled product and API runners explicitly add generated assertion bind
layers omitted from CIRCT's `filelist.f`, including their parent include
directories. Their hashes enter the build identity and final reports; older
build records without this identity must be rebuilt. A tiny Verilator probe
checks private register reads, healthy execution and deliberate failures in both
instances. Run it with `python3 scripts/test_qualify_native_product_compiled.py`;
it skips that probe when Verilator is unavailable and never builds the GPU.

Earlier compiled-native reports using only `filelist.f` retain their measured
image, traffic and cycle evidence. They do not establish execution of the
generated bound checks. Fresh complete-model runs remain required for that
gate and for the complete product qualification.

During execution, `state.json` identifies the child and run directory. Its
`progress.json` records the scene, service/GPU cycles, AXI traffic, elapsed
time and the configured cycle bound. The driver checks for an update every
256 cycles, throttles in-scene updates to one per 15 seconds, and publishes
immediately at scene start and successful end, using an atomic rename.
Reporting is best effort: a stale or absent snapshot alone does not prove the
process stalled. The bound is a timeout limit, not a completion estimate;
`scene-complete` is not the independent image verification or qualification.
Existing running executables do not acquire the new reporting.

The CPU-only progress, source-binding and runner guards can be reproduced with
`python3 scripts/test_qualify_native_product_compiled.py` and
`python3 scripts/test_qualify_native_api_compiled.py`.
On the `7617dcf4` full-host RTL, a local side-by-side 32×32 desktop scene
matched the original and monitored drivers at 70,908 service cycles, with
identical reported counters and stored-memory bytes. Live progress advanced
while it ran. This single-scene comparison is not the image-oracle gate.

The optional `--reference PATH` binds smoke qualification to a retained Scala six-scene report. It requires exact agreement in service/GPU cycles, external bus bytes, enabled write bytes and all six fragment phase counters.

## Current-main smoke and product preparation, 2026-11-09

On frozen commit `85866da6`, `--smoke --scene-jobs 3` completed the six
full-capacity 32×32 scenes in fresh processes and passed the independent
image/guard verification (`passed=true`, `product=false`,
`exact_stored_images_and_guards=true`). Service cycles were:

| Scene | Service cycles |
|---|---|
| desktop-composition | 58,341 |
| textured-3d | 86,072 |
| opaque-overdraw | 101,355 |
| transparent-layers | 100,587 |
| small-triangles | 35,748 |
| msaa-4x | 114,444 |


## Bounded scene parallelism

The default `--scene-jobs 1` preserves sequential execution with one shared
model and a reset before each scene. Use `--scene-jobs 2` through `6` to run
independent scenes in fresh processes, with at most that many live children.
Choose a limit that fits local CPU and memory availability; three is a useful
starting point. `--jobs` still controls model compilation only.

```sh
python3 scripts/qualify_native_product_compiled.py --product --two-cu-vga \
  --output "$PWD/.artifacts/native-product-parallel" --jobs 2 --scene-jobs 3
```

Every process reads the complete six-scene fixture suite, validates scene IDs
and resolution, and runs its assigned scene with the original reset, resource,
cycle-bound, AXI-drain, fault and completion checks. Each scene has its own
`scenes/NAME/progress.json` and `execution.log`. `state.json` lists live child
PIDs and completed scenes. The runner collects six metrics in canonical order
and links all six stored memories into the unchanged independent Scala oracle's
input layout. Links avoid a second copy of full-frame memory. A missing scene,
failed child or missing output prevents a successful execution report; an actual
batch failure stops its remaining children and preserves their logs. Execution
alone still cannot write a passing qualification.

Execution and qualification reports record `scene_execution.mode` and
`max_workers`. Use the same `--scene-jobs` when running individual stages.
Fresh startup can change arbitration timing relative to a reset on an existing
model, so compare cycles only within the same startup mode. A reference without
a mode label is treated as the historical `sequential-shared-model` mode;
parallel reference reports must explicitly name `fresh-model-per-scene`.
The reference must still include all six scene IDs and match every checked
counter exactly. No timing tolerance or startup-mode difference counts as an
RTL performance improvement. Parallelism changes local scheduling, not the
workloads, hardware capacities, assertion coverage or whole-host clock/FPS gates.

On frozen hardware `9ed185d7029c`, all six full-capacity 32×32 scenes pass in
fresh processes on three models: original RTL with all 258 generated bind
layers, FlashSim with 8004 bound checks, and an unasserted FlashSim control.
Every model matches the fresh original-RTL cycle, traffic and phase counters
exactly, retains identical complete stored-memory bytes, and passes all six
independent Scala image and guard oracles. The same selected-scene driver is
used by these controls. Fresh `small-triangles` takes 41,286 service cycles
versus 41,287 on the historical shared model; original RTL reproduces this
startup difference. This is a startup-mode control on that frozen hardware,
not current-main product, clock or FPS qualification. The 19 product-tool checks,
seven API-tool checks and complete `bash scripts/test_scripts.sh` entry point
also pass; routine CI adds no full-model or product-frame run.

## FlashSim assertion models

The explicit FlashSim runner uses the same preparation, six scenes, AXI
drain checks and independent stored-image oracle as the compiled product
runner. It checks that FlashSim reads the same emitted RTL and bind layers,
requires an assertion-enabled split model, and binds the generated sources,
generator, CIRCT libraries, driver and executable to the build record.
Use Python 3.10 or newer with a FlashSim checkout and its CIRCT frontend:

```sh
python3 scripts/qualify_native_product_flashsim.py --smoke --two-cu-vga \
  --flashsim-dir /path/to/FlashSim --output "$PWD/.artifacts/flashsim-smoke" --jobs 2
# Explicit 640×480 two-CU qualification, outside the PR/nightly lanes:
python3 scripts/qualify_native_product_flashsim.py --product --two-cu-vga \
  --flashsim-dir /path/to/FlashSim --output "$PWD/.artifacts/flashsim-product" --jobs 2
```

Product preparation and image verification default to a 24 GiB maximum JVM
heap. Set `OPENGPU_QUALIFY_JAVA_HEAP=32G` (or another positive JVM size with
K/M/G suffix) to choose a different maximum; this also applies to equivalent
and parallel verification. Smoke defaults remain 6 GiB for preparation and
3 GiB for verification.

`--stage prepare|build|run|verify` and `--scene-jobs` have the same meanings
as the compiled runner. `--shards` controls the generated C++ method partitions
(default 32); use the same value for build, run and verification. If native
compilation is interrupted, the next build reuses generated C++ only after
checking every shard and header, the MLIR, RTL, generator, toolchain and CIRCT
environment; changed generated C++ is rebuilt from the matching MLIR, and a
changed generator or MLIR forces a fresh import. The backend
is recorded in execution and qualification
results. `python3 scripts/test_qualify_native_product_flashsim.py` exercises
the CPU-only source, assertion, build and stale-artifact guards. Setting
`OPENGPU_FLASHSIM_DIR` also runs a tiny generated model with a real bound
assertion. Neither check executes the GPU. A nonzero generated assertion count
is necessary but does not alone establish complete bound-check coverage or
parity with the reference model.

On GPU snapshot `e9715d78`, an assertion-enabled FlashSim model with the
full 4-CU/16-lane/8-warp host completed the six sequential 32×32 development
scenes. All stored images, guards and AXI drain checks passed with 8,096 bound
assertions. This used FlashSim `6861c42`, a locally modified experimental
CIRCT, resharded C++ and `-O0`; it does not qualify current RTL, complete product frames, a
whole-host clock or FPS. The CIRCT changes are not part of the GPU repository.

To build a similarly resharded model, use Python 3.10+, Clang and compatible
FlashSim/CIRCT checkouts. The separate generate stage leaves the verified model
available for resharding without starting native compilation. Use the same
paths and options for each later stage:

```sh
export PYTHONPATH="$FLASH_GENERATOR"
export CIRCT_VERILOG=/path/to/compatible/circt-verilog
export FLASHSIM_CIRCT_SERIAL=1
out="$PWD/.artifacts/flashsim-smoke"
python3 scripts/qualify_native_product_flashsim.py --smoke --two-cu-vga --stage prepare \
  --flashsim-dir "$FLASH_GENERATOR" --output "$out"
python3 scripts/qualify_native_product_flashsim.py --smoke --two-cu-vga --stage generate \
  --flashsim-dir "$FLASH_GENERATOR" --output "$out"
PYTHONPATH="$FLASH_RESHARD" python3 -m flashsim.reshard create \
  --source "$out/flashsim-dut" --output "$out/resharded" --max-mib 16 --commit-parts 16
for stage in build run verify; do
  python3 scripts/qualify_native_product_flashsim.py --smoke --two-cu-vga --stage "$stage" \
    --flashsim-dir "$FLASH_GENERATOR" --output "$out" --jobs 2 \
    --reshard-dir "$out/resharded" --reshard-tool "$FLASH_RESHARD" \
    --precompiled-header --shard-opt=-O0
done
```

The reshard manifest and tool are rechecked before execution and verification.
Precompiled headers require Clang. Lower optimization levels can make a large
model build feasible but are not a simulation throughput result. The smoke
above also enabled an experimental local CIRCT canonicalizer, so reproducing
that exact run requires the same local compiler build and setting.
The experimental CIRCT branch contains local changes to hierarchical reference
routing and LLHD liveness in addition to the uncommitted canonicalizer; it links
the official LLVM/MLIR 1.158 libraries and does not modify OpenGPU's LLVM branch.
The optional `--conservative-alias` compiles optimized shards with
`-fno-strict-aliasing`. Use the same setting for build, run and verify,
including the equivalent verifier and parallel recorder/verifier: it is part
of the build identity, including the precompiled-header flags. The default is unchanged.
Complete-frame performance and compiler speed must be measured on the current
selected source and profile after a full build. Partial relinks and short
prefix comparisons cannot qualify a product frame or FPS. An optional source
transformation can be prepared with a FlashSim checkout that supports
`--commit-blocks`:

```sh
PYTHONPATH="$FLASH_RESHARD" python3 -m flashsim.reshard create \
  --source "$out/flashsim-dut" --output "$out/resharded" --max-mib 16 \
  --commit-parts 16 --local-store-helpers --commit-blocks
```

Use the preceding strict build/run/verify stages with the same explicit
profile and all matching generator, reshard and compiler options. All six
independent stored-image checks remain required.

If source changes after a completed run, a fresh `--stage prepare` may be
requalified without repeating execution only when every emitted RTL file,
assertion layer, fixture and profile manifest is byte-identical. The equivalent
verifier checks the original FlashSim generator, build, executable, scene
outputs and immutable execution record, then runs the stored-image oracle
again from the current source. It writes a separate
`qualification-equivalent.json` and never rewrites the original evidence.
If the driver or qualification scripts changed after the original build, pass
`--original-driver-checkout /path/to/original-gpu-checkout` to the equivalent
verifier, parallel recorder or parallel verifier. The original build's recorded
driver hashes must match that checkout; the new preparation and oracle still
use the current checkout. A historical driver checkout cannot build a new model.
Use the original generation environment and build options, for example:

```sh
python3 scripts/qualify_equivalent_flashsim_product.py --product --two-cu-vga \
  --prepared-output "$CURRENT_PREPARATION" --execution-output "$COMPLETED_RUN" \
  --flashsim-dir "$FLASH_GENERATOR" --scene-jobs 2 \
  --reshard-dir "$FLASH_RESHARDED" --reshard-tool "$FLASH_RESHARD" \
  --precompiled-header --shard-opt=-O0
python3 scripts/test_qualify_equivalent_flashsim_product.py
```

The verifier requires all six original scenes to have completed; equal RTL or
a successful model build alone is not a product qualification.

When completed scenes come from separate fresh-model batches, the parallel
verifier accepts a recorded completion witness from the original controller
and a completed additional-scene record. It checks the model and input
identities, every scene's command, metrics and output hashes, and then reruns
the current-source stored-image oracle across all six memories. It writes a
separate result without changing either batch; an unfinished or changed scene
cannot be qualified. The qualification output must share a filesystem with the
scene memories because it links them without copying. With the original
two-scene batch still running, launch the other scenes and record the original
completed scenes in a second shell:

```bash
args=(--product --two-cu-vga --prepared-output "$CURRENT_PREPARATION" --original-output "$ORIGINAL_RUN"
  --flashsim-dir "$FLASH_GENERATOR" --scene-jobs 2 --reshard-dir "$FLASH_RESHARDED"
  --reshard-tool "$FLASH_RESHARD" --precompiled-header --shard-opt=-O0)
python3 scripts/record_parallel_flashsim_product.py extra "${args[@]}" \
  --output "$ADDITIONAL_BATCH" --jobs 4 --scene opaque-overdraw \
  --scene transparent-layers --scene small-triangles --scene msaa-4x
# Once the original controller reports both scenes complete:
python3 scripts/record_parallel_flashsim_product.py witness "${args[@]}" \
  --run-directory "$ORIGINAL_SCENE_RUN" --output "$ADDITIONAL_BATCH/first-two-witness.json" \
  --scene desktop-composition --scene textured-3d
# After both batches finish:
python3 scripts/qualify_parallel_flashsim_product.py --product --two-cu-vga \
  --prepared-output "$CURRENT_PREPARATION" --original-output "$ORIGINAL_RUN" \
  --witness "$ADDITIONAL_BATCH/first-two-witness.json" \
  --extra-record "$ADDITIONAL_BATCH/extra-execution.json" \
  --output "$SEPARATE_QUALIFICATION" --flashsim-dir "$FLASH_GENERATOR" \
  --scene-jobs 2 --reshard-dir "$FLASH_RESHARDED" --reshard-tool "$FLASH_RESHARD" \
  --precompiled-header --shard-opt=-O0
python3 scripts/test_qualify_parallel_flashsim_product.py
```

The optional FlashSim adapter accepts models generated with FlashSim's
`compile --frontend circt --assertions` option. Generate a separate model and
build directory; preserve any model serving a live run. The native builder
accepts the standard exception/string headers used by these models while
rejecting untracked local includes and files that shadow those headers.
The adapter forwards both clock levels when the model exposes its clock,
ticks only on rising edges, and propagates a failed check before state updates.
Older models with an implicit clock remain supported.

The five builder checks and two adapter checks use tiny C++ models, covering
successful execution, failure propagation, clock levels, unchanged state on a
failed edge, AXI word packing and dependency/cache guards:

```sh
python3 scripts/test_build_native_flashsim.py
python3 scripts/test_flashsim_native_adapter.py
```

These checks validate the adapter boundary. Actual full-host assertion
coverage, native trace parity and all six independent stored-image checks
remain required; they do not establish a product-frame result, clock or FPS.

## Reusing unchanged native compilation shards

The optional compiler adapter can reuse independently recorded objects from an
earlier native build when the generated header, individual shard, compiler
version, flags and recorded environment match. It refuses unsupported
preprocessor directives and path-sensitive C++ in reuse candidates. A changed
shard or a different optimization level is compiled normally. All sources,
objects, compiler invocations and reuse events are checked before a separate
build witness is written. Use new plan and event paths for each build:

```sh
python3 scripts/cache_native_flashsim_compiler.py prepare \
  --dut-dir "$DUT" --build-dir "$NEW_BUILD" --cache-build "$OLD_BUILD" \
  --plan "$PLAN" --events-dir "$EVENTS"
OPENGPU_NATIVE_CACHE_PLAN="$PLAN" python3 scripts/build_native_flashsim.py \
  --dut-dir "$DUT" --build-dir "$NEW_BUILD" \
  --cxx "$PWD/scripts/cache_native_flashsim_cxx" --jobs 2 --shard-opt=-O2
python3 scripts/cache_native_flashsim_compiler.py verify --plan "$PLAN" --witness "$WITNESS"
python3 scripts/test_cache_native_flashsim_compiler.py
```

The earlier builder records compiler version and environment, but does not
record the historical compiler binary or system-header contents. Reuse is
therefore appropriate only when the compiler, SDK and standard headers have
remained unchanged since that build. The adapter refuses unrecorded compiler
overrides such as `LIBRARY_PATH` or `TOOLCHAINS`; use an interpreter and
environment that leave them unset for both builds. The witness covers the
build and the recorded inputs; it does not replace assertion, scene or
independent image qualification.

## Compiler hierarchy profiles

The default `flat` profile compiles the complete RTL and generated checks
together. Current check modules read private state, which is incompatible
with [Verilator hierarchy-block boundaries](https://verilator.org/guide/latest/verilating.html#limitations).
The runner rejects `baseline` and `fp32` when generated bind layers are present.
No RTL, assertion, resource capacity or workload is removed by `flat`.

The historical `baseline` profile separates CU/blend blocks. The experimental
`fp32` profile adds separately compiled FP32 clip, raster and fragment-preparation
wrappers, including specialized variants; general-format `FloatFMA` stays
inside its FP32 wrappers. Both profiles remain available for inputs without
generated bind layers. The selected profile and module list enter the build
identity; run and verify reject a different requested profile. Measurements
below predate the bound-check correction and do not qualify current assertion
execution. Use `flat` for fresh current-source qualification.

Use distinct output directories for a comparison and run all four stages with the same profile. Both profiles must use identical RTL, fixtures, toolchain and driver, pass all six image oracles, and agree exactly on GPU and traffic counters. Report build and simulation wall times separately. CPU guard checks alone establish neither model correctness nor a performance improvement; the matched six-scene result below qualifies the optional profile on its stated hardware revision.

## Current-source small-frame result, 2026-11-03

The FP32 hierarchy profile passed all six 32×32 scenes on source
`28c2aadd5141`, whose complete Git tree is identical to merged `main` at
`f606810939ee`. The full four-CU, sixteen-lane/eight-warp host, four fragment
workers, original capacities and characterized SRAM models were retained.
Each scene drained its external traffic, and the independent Scala oracle
checked all stored color, depth/stencil, resolve and guard bytes. No separate
Scala cycle/traffic reference was supplied for this run.

| Scene | GPU cycles | Read bus bytes | Enabled write bytes |
| --- | ---: | ---: | ---: |
| Desktop composition | 70,991 | 95,424 | 21,248 |
| Textured 3D | 100,050 | 164,032 | 45,500 |
| Opaque overdraw | 140,067 | 133,696 | 33,664 |
| Transparent layers | 139,299 | 133,696 | 33,664 |
| Small triangles | 42,287 | 42,816 | 32,704 |
| 4× MSAA | 152,897 | 174,016 | 74,172 |

Reproduce locally with the pinned SRAM submodule initialized:

```sh
python3 scripts/qualify_native_product_compiled.py --smoke --two-cu-vga \
  --hierarchy-profile fp32 --jobs 2 --output "$PWD/.artifacts/native-current-smoke"
```

These are small-frame correctness and traffic measurements. They do not
qualify a product frame, a whole-host clock, FPS or H6 acceptance.

## Matched hierarchy result, 2026-10-28

Both profiles pass all six independent 32×32 stored-image/guard oracles and drain
all external traffic. GPU/service cycles, bus/enabled bytes and fragment phase
counters match exactly. The experiment uses identical prepared RTL, fixtures,
driver, tools and flags outside the selected hierarchy profile, using one
build job, four CUs, sixteen lanes, eight warps and full execution/storage capacities.

| Measurement | Baseline (seconds) | FP32 profile (seconds) |
|---|---:|---:|
| Complete model build | 3,588.07 | 1,901.99 |
| Six-scene simulation loop | 2,447.42 | 1,450.53 |

Preparation is shared and measured separately. These are one sequential pair
on a contended host: build time was 47.0% lower and simulation-loop time 40.7%
lower in this experiment. They are not an idle-host or general speedup guarantee.
The default remains `baseline`; the optional profile has eight passing CPU
guard/protocol tests and a passing full host-script entry point.

The qualified source is `15e2b8f580c0` with hardware base `645a46a506b5`.
Rebasing/publishing the compiler option does not qualify subsequent RTL changes;
prepare, build, run and verify fresh models for that hardware. This comparison
establishes no current whole-host clock, product FPS or H0–H8 completion. Raw
logs, complete source/build inventories and qualification outputs are temporary
under `.artifacts/`; delete them when active validation ends.

Reproduce a matched single-job pair on a fixed source revision:

```sh
python3 scripts/qualify_native_product_compiled.py --smoke --two-cu-vga --jobs 1 \
  --output "$PWD/.artifacts/native-matched-baseline"
python3 scripts/qualify_native_product_compiled.py --smoke --two-cu-vga --jobs 1 \
  --hierarchy-profile fp32 --output "$PWD/.artifacts/native-matched-fp32"
```

## Settled AXI step

The shared native host driver now drives the memory response, settles and
captures the pre-edge request, observes its handshakes, and advances the clock
without evaluating the unchanged low phase a second time. Reset and MMIO
ticks still settle newly changed control inputs before their edge. Both the
product and API drivers use this same step.

On the frozen `7617dcf4` host, one instrumented 32×32 desktop-composition
scene produced the same 70,908 service cycles, traffic/phase counters,
20,988,768-byte per-cycle AXI trace and stored memory before and after the
change. The original Scala image and guard oracle passed. A single contended
pair used 170.352 versus 166.180 user-CPU seconds; two balanced 12,000-cycle
prefix runs per variant averaged 27.047 versus 26.035 seconds. These are
small local measurements, not a general speed guarantee or a product FPS result.
The production-shaped driver separately reproduced the same scene's counters
and stored memory. All six scenes and the product gates remain required.

Reproduce the small phase and protocol controls with:

```sh
python3 scripts/test_qualify_native_product_compiled.py
python3 scripts/test_qualify_native_api_compiled.py
```

## Qualification gates and limits

### Captured production owner submission

`native_owner_driver.cpp` replays a production submitter's original physical
`OGMEM001` memory image and ordered `OGCAP001` control trace. It never generates
shader code, descriptor words, virtual addresses or rendered outputs. This
executor requires the complete two-CU/16-lane/eight-warp/four-engine API profile,
including GAB1, GNP1 and explicit protected-source discovery (GCR bit 17);
the older frozen v2 model and GNP-only combinations are intentionally rejected.
The required profile has 96 discovery words, including the eight GSE limits.
The executor reads and verifies GCR first, regardless of capture-table order,
before probing GUC, GSE or GNC. GNP by itself does not guarantee those windows.
MMIO reads and writes service external AXI at the same pre-edge observation,
so preceding invalidations retain their actual completion/POP waits.

`OGCAP001` is little endian, with no implicit structure padding:

| Section, in order | Encoding |
|---|---|
| Header | Eight ASCII magic bytes, u64 total cycle budget, u32 held clocks (8..4096), u32 final command ID |
| Discovery | u32 count, then `{u32 address, u32 expected}`; all 96 required profile words (GCR flags `0x3ffff`) plus any additional captured words |
| Writable physical views | u32 count, then `{u64 first, u64 exclusive_end}`; sorted, disjoint, within original allocations |
| Ordered trace | u32 count, then `{u32 kind, u32 address, u32 value, u32 mask}`; WRITE=1 uses zero mask, POLL=2 retries a predicate, CHECK=3 tests once |
| Held checks | u32 count, then `{u32 address, u32 mask, u32 expected}`; exact final command identity and all 12 GNX words required |
| Retirement | u32 count, then `{u32 address, u32 value}`; exactly one completion POP, optional IRQ acknowledgement |

Trace predicates describe the real driver's waits, not CPU-stub poll counts.
Prior submissions must be invalidations and retire before the final native
GNJ1 enqueue. That last enqueue retains the real enabled Sv32 context, source
VA and 512-byte size, and ends the trace without popping its completion. GNJ1
uses opcode 7 (`GPU_UCMD_OP_NATIVE_BINNED_RENDER`), as routed by
`TileBinHostCommandPort`; legacy opcode 6 has a different descriptor ABI and
is rejected. `DESTINATION` plus `PATTERN` bit 0 carry the 33-bit exclusive GNJ
view limit, with at least 512 bytes after the aligned source and no bound past
2^32. Other pattern bits and DMA/event flags must be zero. Native feedback
flags retain their advertised semantics. All these command fields must be
present in the actual capture rather than inherited from a preceding command.
The executor checks drain and stable held completion (including all captured
GNX words), rejects late memory
traffic, retires the slot, and checks every byte outside the declared writable
views, including newly materialized sparse pages. The output still requires an
independent stored-image/sample/guard oracle and matched-source qualification.

Each successful record also prints the actual held MMIO `completion`, twelve
`gnx` words, 64-bit `appendReadBytes`, `heldStatus`, `heldIrq` and IRQ pin, plus
the status, IRQ register/pin and external-drain observation after POP. Raw GNX
values preserve bits outside a capture predicate's mask. These values are
copied before POP and checked across the hold; expected predicates are never
used as returned observations. A live original driver session must retain its
owners, mappings and GEM references until actual complete-DUT execution and
the independent image oracle finish, then use these observations to authorize
its real completion/retirement path. CPU control-peer results cannot authorize
that release or establish GPU execution.

`--series` executes at least two captured submissions on one model, memory
agent and monotonically advancing clock, with exactly one initial reset. Each
record has its own cycle budget, writable views, held completion and retirement;
all other bytes are checked against that record's starting memory. Earlier
outputs remain in RAM and become read-only unless explicitly writable in the
next record. Records are parsed and their views checked before model creation.
The output directory must be new; `record-0.memory`, `record-1.memory`, etc. are
written only after that record passes its drain, completion and byte guards.

Prepare all independent owners before exporting the single initial physical
memory, keeping their actual mappings, references and leases alive. For a
source/default rebind test, rebind between the real snapshots: the export must
contain the original GEM's final contents and both owners' different private
copies. Do not retire owners on the CPU and reconstruct their PTEs, replace RAM
between records, reset the model or clear its caches. The independent oracle
must check every record's image against its corresponding snapshot.

For a live original driver session, use `--active-series` so the second
submission is not generated by clearing a still-pending first command in a
CPU model. The same DUT, memory agent, RAM and clock stay alive across records.
Both owners are prepared before the initial union export; defer their actual
free-job/revocation until every record and independent image oracle finish.
Execution-completion dependencies may release the next native job while the
first owner's mappings remain held; ordinary writers still wait for full
retirement. No RAM replacement, PTE reconstruction or reset occurs in this
handoff.

First, `--probe-profile NEW_OUTPUT.json` reads the actual device ID/capabilities,
then GCR before dependent windows, and checks all 96 native profile words. Its
`opengpu-native-owner-profile-v1` JSON contains `words` (96 address/value pairs)
and `registers` (ID, capabilities, native capability pointer, live execution
quota at 0x320 and shader resources at 0x158). This read-only probe does not execute a GPU job. The original
driver must validate and cache these raw observations; active execution checks
them again on the same qualified compiled factory before starting its series.

The active session uses atomic files under a fresh GPU-local session directory:

1. The driver writes `record-N.capture`, using `OGCAP001` with an empty finish
   section. Only active mode accepts that prefix; ordinary replay still requires
   its complete retirement plan. Every active capture includes the five actual
   identity/quota/shader register values as well as the 96 mandatory discovery words.
2. The DUT executes and checks the command, then writes `record-N.held.json`
   with the actual completion, twelve GNX words, 64-bit append telemetry,
   held status/IRQ and drain observation. It does not POP or report retired
   fields at this point.
3. The actual driver drain consumes that held receipt and records its actual
   reads and POP/IRQ writes in `record-N.retire`. `OGRTR001` consists of its
   eight-byte magic, a little-endian u32 action count, then four-u32
   `{kind,address,value,mask}` actions with the same kinds as `OGCAP001`.
   Completion/telemetry and all twelve raw GNX words must be read before the
   unique POP. Reads of their cleared windows after POP and unrelated writes
   are rejected.
4. The DUT verifies and applies those actions, checks drain and retired
   status/IRQ, writes `record-N.memory` in the output directory and atomically
   publishes `record-N.retired.json` in the session directory. Only then may
   the driver dispatch the next native job and produce its capture.

A missing response times out; an `error` marker aborts the session. Responses
cannot overwrite existing files. Both records' independent stored-image and
source/guard checks remain required before authorizing full owner release.
The CPU control peer passes this delayed two-phase handshake, retirement wire
rejection and profile-probe checks with ASan/UBSan; it is not GPU evidence.

```sh
"$BUILD/native_product_driver" --probe-profile "$NEW_PROFILE_JSON"
# Start the live original driver bridge with that validated profile and wait
# for its actual union export and record-0.capture before this invocation:
"$BUILD/native_product_driver" --active-series "$UNION.memory" \
  "$NEW_OUTPUT_DIR" "$SESSION_DIR" 2
```

Build with the complete assertion-enabled FlashSim DUT and explicitly tracked
driver headers, using a separate task build directory:

```sh
python3 scripts/build_native_flashsim.py --dut-dir "$DUT" --build-dir "$BUILD" \
  --driver scripts/native_owner_driver.cpp \
  --driver-dependency scripts/native_owner_capture.hpp \
  --driver-dependency driver/gpu_abi.h \
  --driver-dependency driver/opengpu_formats.h --jobs 1
"$BUILD/native_product_driver" "$INPUT.memory" "$INPUT.capture" "$OUTPUT.memory"
# Separate invocation for two owners prepared together in one initial export:
"$BUILD/native_product_driver" --series "$UNION.memory" "$NEW_OUTPUT_DIR" \
  "$FIRST.capture" "$SECOND.capture"
```

CPU parsing, concurrent MMIO/external-AXI stimulus, malformed trace/capability,
read-only-byte, exclusive-4-GiB-bound and two-record persistent-memory checks
pass with ASan/UBSan. A CPU control peer tests separate cycle budgets and
rejects changing held GNX state, changing held append telemetry or a second
record corrupting the first record's scratch bytes. It preserves unmasked raw
GNX bits and telemetry above 4 GiB in its result JSON. It does
not execute GPU instructions or establish owner snapshot correctness. Builder
checks pass for declared relative dependencies, changed headers and shadow
rejection. These are protocol checks; actual production owner capture, matched
complete-DUT execution and independent image verification remain pending.

```sh
OPENGPU_TEST_SANITIZERS=1 python3 scripts/test_native_owner.py
python3 scripts/test_build_native_flashsim.py
```

Execution alone does not create a passing qualification. Every scene must complete without unmapped traffic and drain all AXI ownership. Scala then reloads the full output memory and runs the original independent oracle on every stored color, depth/stencil, resolve and guard region. Only successful verification of all six named scenes creates `qualification.json`.

CPU-only AXI protocol and stale-artifact guard tests run through `bash scripts/test_scripts.sh`. They do not replace the complete native image gate. Simulation wall time is separate from GPU cycles. Frame-rate qualification additionally requires a current complete-host clock and matching memory assumptions. This runner by itself closes no feature/resource/coherence, whole-host timing or H0–H8 gate.

## Verified development result

The complete image result below used the hardware baseline at `cc6377b24db956acb18df8917039d6b1f89920d2` (PR #108), before the shared-memory SRAM change in PR #109. The runner was subsequently rebased onto PR #109 with its memory/codec and host-script checks passing. That rebase does not qualify the changed complete host; prepare, build, run and verify a fresh model to qualify a newer hardware revision. The source guards reject reuse of the earlier model against changed hardware sources.

All six 32×32 scenes passed with the original Scala stored-image oracle: desktop composition, textured 3D, opaque overdraw, transparent layers, small triangles and 4× MSAA. Service/GPU cycles, read/write bus bytes, enabled write bytes and all six fragment phase counters match the retained Scala reference exactly for every scene. The complete build, execution and independent verification each completed with exit zero.

The 13 memory/codec checks and seven CPU runner guard checks pass. The latter execute eleven C++ AXI protocol cases, including narrow upper-beat words, held responses, W before AW, concurrent reads/writes, masked traffic and fault replies. The full host-script entry point also passes.

That earlier six-scene result used approximately 49 minutes of simulator loop time on this host with concurrent local validation. Build time is separate. It established correctness of the compiled stimulus path; it did not qualify compiler-profile improvements, a whole-host clock, product frame rate or H0–H8 completion.
