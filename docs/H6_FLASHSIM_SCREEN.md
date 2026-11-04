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
Full 720p images, assertion-enabled execution, physical timing and H6–H8
acceptance remain separate work.

## Build and run

Use a sibling [FlashSim](https://github.com/OpenGPGPU/FlashSim) checkout with
the clocked-process partitioner and commit-part support. The commands below
prepare a fresh 32×32 smoke profile. The generated DUT is large; choose the
compiler job count for the available memory. The builder verifies cached
object hashes and accepts the generated `dut.h` plus `dut_*.cpp` files. It
rejects additional generated includes that its cache does not track, and
rechecks the inputs before publishing the linked driver.

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
  --build-dir "$H6_SCRATCH/build" --jobs 3 --conservative-alias
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
