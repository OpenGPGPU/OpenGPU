# Compiled native product qualification

This explicit local runner executes the same full-profile native host, six input recipes and independent stored-image oracle as `QualifyNativeProductRecipes`. The C++ driver services external AXI transactions inside the Verilator process. Rasterization, shaders, texture sampling, depth/stencil, blending, stores and resolves execute in the generated RTL.

The hardware profile retains four CUs, sixteen lanes, eight resident warps, four native fragment workers, the original storage capacities, characterized SRAM models and a 256-bit AXI port. Smoke mode renders 32×32 scenes; it does not reduce execution or storage capacities. Product mode renders 1280×720 and explicitly sets `opengpu.productQualification=1` when invoking Scala. Both modes require all six scenes. Neither the RTL build nor these simulations belongs in regular PR/nightly CI.

## Reproduce

Initialize the pinned SRAM submodule and install the repository's Scala/C++/Verilator toolchain. Use an absolute output directory under `.artifacts/`:

```sh
python3 scripts/qualify_native_product_compiled.py --smoke \
  --output "$PWD/.artifacts/native-compiled-smoke" --jobs 2
```

Run a deliberate product qualification separately:

```sh
python3 scripts/qualify_native_product_compiled.py --product \
  --output "$PWD/.artifacts/native-compiled-product" --jobs 2
```

`--stage prepare|build|run|verify` operates one stage. Preserve source and output bytes while a stage is live. If a stage fails, inspect its actual exit and log before retrying. An observation timeout does not terminate a build or simulation. The build cache is bound to emitted RTL, hardware profile, driver inputs, compiler/tool versions, flags and executable hash. Preparation, execution and verification reject stale source/model/fixture/output bytes. Raw outputs remain local under `.artifacts/`.

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

The product `--stage prepare` also succeeds with the 1280×720 fixture
manifest (`frameWidth=1280`, `frameHeight=720`, product true). The 720p
execution, whole-host clock and FPS qualification were not run here, so this
note does not establish a 720p product result, clock or frame rate.

## Bounded scene parallelism

The default `--scene-jobs 1` preserves sequential execution with one shared
model and a reset before each scene. Use `--scene-jobs 2` through `6` to run
independent scenes in fresh processes, with at most that many live children.
Choose a limit that fits local CPU and memory availability; three is a useful
starting point. `--jobs` still controls model compilation only.

```sh
python3 scripts/qualify_native_product_compiled.py --product \
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
not current-main 720p, clock or FPS qualification. The 19 product-tool checks,
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
python3 scripts/qualify_native_product_flashsim.py --smoke \
  --flashsim-dir /path/to/FlashSim --output "$PWD/.artifacts/flashsim-smoke" --jobs 2
# Explicit 720p qualification, outside the PR/nightly lanes:
python3 scripts/qualify_native_product_flashsim.py --product \
  --flashsim-dir /path/to/FlashSim --output "$PWD/.artifacts/flashsim-product" --jobs 2
```

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
CIRCT, resharded C++ and `-O0`; it does not qualify current RTL, 720p, a
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
python3 scripts/qualify_native_product_flashsim.py --smoke --stage prepare \
  --flashsim-dir "$FLASH_GENERATOR" --output "$out"
python3 scripts/qualify_native_product_flashsim.py --smoke --stage generate \
  --flashsim-dir "$FLASH_GENERATOR" --output "$out"
PYTHONPATH="$FLASH_RESHARD" python3 -m flashsim.reshard create \
  --source "$out/flashsim-dut" --output "$out/resharded" --max-mib 16 --commit-parts 16
for stage in build run verify; do
  python3 scripts/qualify_native_product_flashsim.py --smoke --stage "$stage" \
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

If source changes after a completed run, a fresh `--stage prepare` may be
requalified without repeating execution only when every emitted RTL file,
assertion layer, fixture and profile manifest is byte-identical. The equivalent
verifier checks the original FlashSim generator, build, executable, scene
outputs and immutable execution record, then runs the stored-image oracle
again from the current source. It writes a separate
`qualification-equivalent.json` and never rewrites the original evidence.
Use the original generation environment and build options, for example:

```sh
python3 scripts/qualify_equivalent_flashsim_product.py --product \
  --prepared-output "$CURRENT_PREPARATION" --execution-output "$COMPLETED_RUN" \
  --flashsim-dir "$FLASH_GENERATOR" --scene-jobs 2 \
  --reshard-dir "$FLASH_RESHARDED" --reshard-tool "$FLASH_RESHARD" \
  --precompiled-header --shard-opt=-O0
python3 scripts/test_qualify_equivalent_flashsim_product.py
```

The verifier requires all six original scenes to have completed; equal RTL or
a successful model build alone is not a product qualification.

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
remain required; they do not establish a 720p result, clock or FPS.

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
python3 scripts/qualify_native_product_compiled.py --smoke \
  --hierarchy-profile fp32 --jobs 2 --output "$PWD/.artifacts/native-current-smoke"
```

These are small-frame correctness and traffic measurements. They do not
qualify a 720p frame, a whole-host clock, FPS or H6 acceptance.

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
establishes no current whole-host clock, 720p FPS or H0–H8 completion. Raw
logs, complete source/build inventories and qualification outputs stay local
under `.artifacts/`.

Reproduce a matched single-job pair on a fixed source revision:

```sh
python3 scripts/qualify_native_product_compiled.py --smoke --jobs 1 \
  --output "$PWD/.artifacts/native-matched-baseline"
python3 scripts/qualify_native_product_compiled.py --smoke --jobs 1 \
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
small local measurements, not a general speed guarantee or a 720p/FPS result.
The production-shaped driver separately reproduced the same scene's counters
and stored memory. All six scenes and the product gates remain required.

Reproduce the small phase and protocol controls with:

```sh
python3 scripts/test_qualify_native_product_compiled.py
python3 scripts/test_qualify_native_api_compiled.py
```

## Qualification gates and limits

Execution alone does not create a passing qualification. Every scene must complete without unmapped traffic and drain all AXI ownership. Scala then reloads the full output memory and runs the original independent oracle on every stored color, depth/stencil, resolve and guard region. Only successful verification of all six named scenes creates `qualification.json`.

CPU-only AXI protocol and stale-artifact guard tests run through `bash scripts/test_scripts.sh`. They do not replace the complete native image gate. Simulation wall time is separate from GPU cycles. Frame-rate qualification additionally requires a current complete-host clock and matching memory assumptions. This runner by itself closes no feature/resource/coherence, whole-host timing or H0–H8 gate.

## Verified development result

The complete image result below used the hardware baseline at `cc6377b24db956acb18df8917039d6b1f89920d2` (PR #108), before the shared-memory SRAM change in PR #109. The runner was subsequently rebased onto PR #109 with its memory/codec and host-script checks passing. That rebase does not qualify the changed complete host; prepare, build, run and verify a fresh model to qualify a newer hardware revision. The source guards reject reuse of the earlier model against changed hardware sources.

All six 32×32 scenes passed with the original Scala stored-image oracle: desktop composition, textured 3D, opaque overdraw, transparent layers, small triangles and 4× MSAA. Service/GPU cycles, read/write bus bytes, enabled write bytes and all six fragment phase counters match the retained Scala reference exactly for every scene. The complete build, execution and independent verification each completed with exit zero.

The 13 memory/codec checks and seven CPU runner guard checks pass. The latter execute eleven C++ AXI protocol cases, including narrow upper-beat words, held responses, W before AW, concurrent reads/writes, masked traffic and fault replies. The full host-script entry point also passes.

That earlier six-scene result used approximately 49 minutes of simulator loop time on this host with concurrent local validation. Build time is separate. It established correctness of the compiled stimulus path; it did not qualify compiler-profile improvements, a whole-host clock, 720p frame rate or H0–H8 completion.
