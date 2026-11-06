# Native FlashSim screen

The native product driver can use a split FlashSim `GpuHostSystemAxiDut`
through `scripts/flashsim_native`. This is a local functional and performance
screen. FlashSim's CIRCT source discovery omits the verification assertion
layer, so it does not replace the assertion-enabled H6 qualification. Do not
run 720p in the regular PR or nightly lanes.

On the frozen four-CU H6 profile, the adapter matched the first 12,000 AXI
cycles at both 32×32 and 1280×720. All six 32×32 scenes matched the serial
driver's counters and stored memory, and the independent image/guard oracle
passed. The first 32×32 scene took 16.7 seconds in FlashSim and 191.9 seconds
in an earlier Verilator run; those runs were not paired under identical load.
A paired 720p **prefix** measured 0.259 seconds in FlashSim and 20.97/20.68
seconds in Verilator. The prefix does not establish a full-frame rate.

Running each small scene in a fresh process preserved all six images, but the
small-triangles scene took two fewer cycles than in the serial suite after a
textured-3D scene. Keep the product suite serial when comparing its counters.
The [completed frozen 720p baseline](H6_720P_BASELINE.md) now passes all six
stored-image/guard checks on the assertion-enabled model. Its counters apply
to the recorded historical RTL and fixture, not later optimizations. Physical
timing and H6–H8 acceptance remain separate work.

## Cycle diagnosis

The product driver's `progress.json` now includes cumulative `busyCuCycles`,
the six `fragmentPhaseWorkerCycles`, staging outstanding/blocked cycles,
fragment-output blocked cycles and payload spills. These are monitoring
counters; the cycle guard remains a liveness limit, not a frame-budget pass.
For the four-CU profile, divide the change in `busyCuCycles` by four times the
change in `gpuCycles` to measure CU ownership. A busy CU can still stall
internally, so this is not ALU utilization.

The fragment phases are idle/collect/control, staging, launch/invalidate,
kernel wait, readback, and emit/release. They count workers and span four
engines; the first phase includes time when the image owner is publishing a
tile. Staging/output stall counters can overlap other phases and must not be
added to them. Use differences between snapshots to separate initial binning
from rendering. FlashSim refreshes its lazy monitoring outputs without
advancing the clock.

The current planning assumption, selected by the user, is 1000 MHz (1 GHz).
The 30 FPS budget is about 33.33 million end-to-end service cycles per frame
and the 60 FPS budget is about 16.67 million. Exceeding a budget
before the first frame finishes fails that assumed-clock first-frame target;
it does not establish the final frame cost or a qualified clock. A faster
simulator or compiler does not reduce these hardware cycles.

An opt-in `-O2` commit-part build matched all six 32×32 scenes' counters and
stored memory, plus the verified 12,000-cycle 720p AXI trace. In a small-frame
baseline/candidate/baseline comparison, it took 18.87 seconds versus
18.13/16.85 seconds. This sample shows no runtime improvement; retain `-O0`
for commit parts unless the target workload demonstrates a benefit.

## Build and run

Use a sibling [FlashSim](https://github.com/OpenGPGPU/FlashSim) checkout with
the clocked-process partitioner and commit-part support. The commands below
prepare a fresh 32×32 smoke profile. The generated DUT is large; choose the
compiler job count for the available memory. The builder verifies cached
object hashes and accepts the generated `dut.h` plus `dut_*.cpp` files. It
rejects additional generated includes that its cache does not track, and
rechecks the inputs before publishing the linked driver.

The build directory's `native-flashsim-progress.json` reports hashing,
active compiler PIDs and shard names, completed/cache-hit counts, elapsed
time and the final `built`, `failed` or `interrupted` state. Writes are atomic;
the shard-compilation stage refreshes at least every five seconds while the
builder is scheduled. Check the actual process before treating an old
timestamp as a failure. Console messages identify each compiler start and
completed shard duration; newly compiled object metadata also records
`compile_elapsed_seconds`. These observations are separate from cache/build
identity and do not establish runtime, assertion or product qualification.
`python3 scripts/test_build_native_flashsim.py` validates live progress,
termination and reuse of older cache records without timing fields. The
malformed-source case keeps the healthy commit object cached and checks the
compiler's exact failing shard. A separate two-worker handshake checks that a
shard failure stops the sibling compiler and its child, retains the primary
diagnostic and publishes no partial objects or completed build. These six
tiny-shard tests pass without emitting RTL or running a rendering model.

With a supporting compiler, `--strict-flex-arrays` adds
`-fstrict-flex-arrays=1` to the native build. This opt-in treats trailing arrays
of two or more elements as fixed-size; use it only with bounds-correct generated
code and compare the target model's results. On Apple Clang 16, one 18 MiB H6
commit part compiled at `-O2` in 50.2 seconds with this option; the default build
was stopped after 389.5 seconds while still parsing. This is a compiler result,
not a GPU-cycle or full-frame speedup. Peak compiler RSS was about 9.4 GB for
that part, so start with one job for this large model. The option is recorded
in the build manifest and invalidates cached objects when changed.

```bash
export H6_SCRATCH="$(pwd)/.artifacts/h6-flashsim-local"
# Select a Python 3.10+ environment with FlashSim's dependencies installed.
export H6_PYTHON=python
export PYTHONPATH="$(cd ../FlashSim && pwd)${PYTHONPATH:+:$PYTHONPATH}"
"$H6_PYTHON" scripts/qualify_native_product_compiled.py --smoke --stage prepare \
  --output "$H6_SCRATCH/prepared"

# Keep the top-level file local: FlashSim resolves it before reading filelist.f.
"$H6_PYTHON" - "$H6_SCRATCH/prepared/rtl" "$H6_SCRATCH/derived-rtl" <<'PY'
from pathlib import Path
import shutil, sys
source, output = Path(sys.argv[1]).resolve(), Path(sys.argv[2]).resolve()
output.mkdir(parents=True, exist_ok=False)
for entry in source.iterdir():
    if entry.name == 'StreamingTypedTileColorLanes.sv':
        continue
    destination = output / entry.name
    if entry.name == 'GpuHostSystemAxi.sv':
        shutil.copy2(entry, destination)
    else:
        destination.symlink_to(entry.resolve(), target_is_directory=entry.is_dir())
PY

"$H6_PYTHON" -m flashsim.partition_clocked \
  "$H6_SCRATCH/prepared/rtl/StreamingTypedTileColorLanes.sv" \
  "$H6_SCRATCH/derived-rtl/StreamingTypedTileColorLanes.sv" --groups 128 \
  --assume-default-initialization --assume-no-instance-drivers \
  --assume-stable-edge-inputs
FLASHSIM_COMMIT_PARTS=16 "$H6_PYTHON" -m flashsim compile \
  "$H6_SCRATCH/derived-rtl/GpuHostSystemAxi.sv" \
  -o "$H6_SCRATCH/model/dut.h" --frontend circt
"$H6_PYTHON" scripts/build_native_flashsim.py --dut-dir "$H6_SCRATCH/model" \
  --build-dir "$H6_SCRATCH/build" --jobs 1 --conservative-alias
"$H6_SCRATCH/build/native_product_driver" "$H6_SCRATCH/prepared/fixtures" \
  "$H6_SCRATCH/images" > "$H6_SCRATCH/metrics.jsonl"
sbt -J-Xmx3G -Dopengpu.fullHostQualification=1 -Dopengpu.productQualification=0 \
  "Test / runMain opengpu.system.VerifyCompiledNativeProduct --smoke --output \"$H6_SCRATCH/images\""
```

The partitioner assumes default initialization, no instance-driven targets,
and stable guard/RHS inputs across the clock event. Verify those assumptions
for the emitted RTL and simulator profile; it rejects unsupported syntax and
detectable extra writes but is not a general Verilog equivalence checker. Use
a fresh scratch directory so the DUT and fixtures come from the same prepared
RTL. The manual FlashSim build record hashes its inputs and objects, but it
does not itself prove that an arbitrary emitted DUT matches those fixtures.

For explicit product work, use `--product` for preparation and verification,
set `-Dopengpu.productQualification=1` and `-J-Xmx6G` for the Scala verifier,
and run outside CI. The existing `qualify_native_product_compiled.py` remains the
assertion-enabled qualification path.

Focused adapter and builder checks:

```bash
"$H6_PYTHON" scripts/test_flashsim_native_adapter.py
"$H6_PYTHON" scripts/test_build_native_flashsim.py
```
