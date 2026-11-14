# Complete owner compute executors

The original Mesa compute producer can submit opcode 0 to the complete geometry
or sample-enabled owner factory. Each executor has one source-selected factory,
probes every discovery word through actual MMIO before launch, and rejects
captures from another factory. The native GNJ1 replay executor stays frozen.

| Factory | Discovery words | Probe schema / wire | Compute capture | Fixed driver |
| --- | ---: | --- | --- | --- |
| `two-cu-vga60-owner-v1` | 96 | profile-v1 / `OGCP0001` | `OGCMP001` | `compute_owner_driver.cpp` |
| `two-cu-vga60-owner-v2-compute-stage` | 96 | profile-v2 / `OGCP0002` | `OGCMP001` | `compute_stage_owner_driver.cpp` |
| `two-cu-vga60-owner-v2` | 104 | profile-v3 / `OGCP0003` | `OGCMP002` | `geometry_compute_owner_driver.cpp` |
| `two-cu-vga60-owner-v2-sample-stage` | 112 | profile-v3 / `OGCP0003` | `OGCMP002` | `geometry_compute_sample_owner_driver.cpp` |

Schema names have prefix `opengpu-compute-owner-`. The v3 JSON record requires
the exact profile ID, version 2, stage `geometry-compute` or
`geometry-compute-sample`, and `fullResourceQualification=false`. The registry
contains two explicit ordered address/value tables. The sample table retains
the original 104 words except GCR flags `0x1fffff`, and appends eight GSS1 words
at `0x520`. The original 104-word table remains unchanged.

Probe wires retain the original little-endian container: magic, u32 discovery
count, discovery address/value pairs, u32 auxiliary count, and ten actual
auxiliary address/value pairs. V3 accepts exactly 104 or 112 words for its named
contract. The controller checks the table and raw device/capability/shader
observations before starting the original producer. The Linux frontend admits
the corresponding wire, runs the original resource admission/MMU path and emits
`OGCMP002`; its discovery count is 114 or 122 including auxiliary reads. The
fixed C++ executor requires its selected count and exact table. Old capture
magic and old 96-word factories remain distinct.

Execution retains the original live producer, held completion, telemetry reads,
retirement, independent returned-byte/read-only/source/guard oracles and
synchronous PTE revoke/shootdown/context cleanup. Case IDs and Mesa source
programs are unchanged. Opcode 0 receipts do not substitute for GNJ2/GNF3/GNY2,
GS, transform feedback, sample shading or stored-frame qualification.

`prepare_owner_executor.py --kind compute` selects the wrapper from the exact
prepared manifest. The qualification rechecks source, manifest, complete RTL
and assertion files, generator/frontend, MLIR, every generated shard, original
driver translation unit and includes, compiler/options/environment, linked
objects and executable. The bound checker inputs also include the compute
controller, protocol, oracle, case registry, Mesa host frontend and Linux
capture producer. Changing any of these invalidates an existing qualification.

CPU validation passes for all four fixed factories under ASan/UBSan: complete
actual-bus peer probes, every discovery-word mutation, cross-factory capture
rejection, held/retired receipts, read-only guards, cleanup ordering and phase
deadlines. Both new tables also pass the production Linux ioctl, real MMU/PTE,
snapshot, scheduler, IRQ and cleanup boundary into the canonical C++ parser;
bad cleanup ACKs retain reachable allocations. These peers execute no GPU
instructions. Python schema, source/model/build/binary identity and entry-point
negative tests pass. Reproduce with:

```sh
export PYTHONDONTWRITEBYTECODE=1 OPENGPU_TEST_SANITIZERS=1
python3 scripts/test_compute_owner.py
python3 scripts/test_compute_owner_protocol.py
python3 scripts/test_prepare_owner_executor.py
python3 scripts/test_prepare_native_owner_flashsim.py
python3 scripts/test_qualify_compute_owner.py
python3 scripts/test_native_owner.py
```

The complete source matrix also passes a new v3/112-word CPU capture run:
`shared`, `uniforms`, the sixteen original dynamic atomic cases and
`shared-address-add`, `shared-address-cas-match`, `shared-address-cas-miss`.
All 21 use actual Mesa 22.3.6 ESSL/NIR/TGSI compilation, Gallium submission and
the production Linux admission/MMU/capture path under GCC ASan/UBSan. Each
`OGCMP002` capture passes the source-selected sample-factory C++ parser, and
each untouched original-memory output fails the independent oracle as required.
The shader limit remains 512 instructions, shared storage 4096 words, and
workloads four groups of 128 invocations. No case is omitted or incomplete.

This run used a synthetic, explicitly CPU-only v3 profile fixture from the exact
112-word registry, rather than a DUT probe. Compilation was sequential in one
read-only, network-disabled container capped at 512 MiB with no extra swap and
one CPU; no OOM occurred. All compiler/linker temporary files, private NTT,
builds, sessions and logs were in a managed checkout `.artifacts/` directory.
Each case's outputs were deleted after its consumers exited; the container and
remaining private files were removed at terminal completion.

To reproduce, use the configured Mesa inputs from
[the owner guide](COMPUTE_HOST_EXECUTION.md). Create `CPU_CAPTURE` inside this
checkout's `.artifacts/`. Stage the checked NTT on the host (the compiler image
need not contain Git), and make the CPU profile fixture:

```sh
python3 scripts/stage_mesa_shared_memory.py --mesa "$GPU_ROOT/depends/mesa" \
  --out "$CPU_CAPTURE/checked-ntt"
PYTHONPATH=scripts python3 - "$CPU_CAPTURE/profile.bin" <<'PY'
import sys
from pathlib import Path
from test_compute_owner_protocol import profile
from compute_owner_protocol import profile_bytes
from owner_resource_contract import FULL_COMPUTE_SCHEMA, SAMPLE_PROFILE, SAMPLE_STAGE, SAMPLE_WORDS
p = profile()
p.update(schema=FULL_COMPUTE_SCHEMA, ownerProfile=SAMPLE_PROFILE,
         ownerResourceStage=SAMPLE_STAGE, ownerResourceVersion=2,
         fullResourceQualification=False, words=SAMPLE_WORDS)
Path(sys.argv[1]).write_bytes(profile_bytes(p))
PY
TMPDIR="$CPU_CAPTURE" c++ -std=c++17 -O1 -fsanitize=address,undefined -fno-omit-frame-pointer \
  -DOPENGPU_OWNER_GEOMETRY_COMPUTE_SAMPLE=1 \
  -Iscripts/test_stubs/native_host -Iscripts scripts/test_compute_owner.cpp \
  -o "$CPU_CAPTURE/canonical-parser"
```

Run the following for each name printed by
`PYTHONPATH=scripts python3 -c 'from compute_owner_cases import CASES; print(*CASES, sep="\n")'`.
Resolve `CPU_IMAGE` from the local `gpu-gtk-ci-check:latest` image ID. Give each
case a fresh `CPU_CAPTURE/$CASE` directory and preserve the original absolute
Mesa build paths. The container's only writable mount is `CPU_CAPTURE`:

```sh
mkdir "$CPU_CAPTURE/$CASE"
docker run --rm --init --memory=512m --memory-swap=512m --cpus=1 \
  --network=none --read-only \
  -v "$GPU_ROOT:$GPU_ROOT:ro" -v "$CPU_CAPTURE:$CPU_CAPTURE:rw" -w "$PWD" \
  -e PYTHONDONTWRITEBYTECODE=1 -e MESA_SHADER_CACHE_DISABLE=true \
  -e TMPDIR="$CPU_CAPTURE/$CASE" -e XDG_CACHE_HOME="$CPU_CAPTURE/$CASE/cache" \
  -e OPENGPU_MESA_DIR="$GPU_ROOT/depends/mesa" \
  -e OPENGPU_MESA_BUILD="$GPU_ROOT/depends/mesa-build" \
  -e OPENGPU_DRM_INCLUDE="$GPU_ROOT/depends/aarch64-root/include" \
  -e LD_LIBRARY_PATH="$GPU_ROOT/depends/aarch64-root/lib" \
  -e OPENGPU_MESA_LINK_LIBS="-l:libz.so.1 $GPU_ROOT/depends/aarch64-root/lib/libexpat.so.1.6.7" \
  "$CPU_IMAGE" python3 scripts/test_gallium_compute_host.py --sanitize --capture-only \
  --build "$CPU_CAPTURE/$CASE/build" --session "$CPU_CAPTURE/$CASE/session" \
  --profile-wire "$CPU_CAPTURE/profile.bin" \
  --mesa-shared-ntt "$CPU_CAPTURE/checked-ntt/src/gallium/auxiliary/nir/nir_to_tgsi.c" --case "$CASE"
"$CPU_CAPTURE/canonical-parser" "$CPU_CAPTURE/$CASE/session/union.memory" \
  "$CPU_CAPTURE/$CASE/session/record-0.capture"
```

For each original capture, `compute_owner_oracle.py` must reject using that same
`union.memory` as both initial and returned memory with its `oracle.json`.
The positive/corruption checks in `test_compute_owner_oracle.py` separately
exercise all nineteen dynamic cases. The nonuniform cases accept both tested
arbitration orders and reject the address/group mutations. Remove ended private data after checking
container and parser exit. This CPU matrix supplies no held or retired GPU
receipt: actual DUT execution is still unverified for every one of the 21 cases.

No fresh complete RTL/model or actual DUT shader execution qualifies either new
factory yet. Keep this work draft until a fresh full-capacity host is generated
and the complete guarded execution succeeds. Preserve two CUs, sixteen lanes,
eight warps, the full native API capacities and every assertion. Do not rename
an existing model or inherit previous compute-stage results.

Once that factory has been prepared and generated in its own `.artifacts/`
directory, build and check its executor with the generation-matched Python,
FlashSim and CIRCT environment:

```sh
python3 scripts/prepare_owner_executor.py --stage build --kind compute \
  --owner-output "$OWNER_OUTPUT" --flashsim-dir "$FLASHSIM_DIR" --shards 32 --jobs 1
python3 scripts/prepare_owner_executor.py --stage check --kind compute \
  --qualification "$OWNER_OUTPUT/executors/compute/qualification.json" \
  --executor "$OWNER_OUTPUT/executors/compute/executor"
python3 scripts/qualify_compute_owner.py \
  --executor "$OWNER_OUTPUT/executors/compute/executor" \
  --executor-qualification "$OWNER_OUTPUT/executors/compute/qualification.json" \
  --executor-checker "$PWD/scripts/prepare_owner_executor.py" \
  --executor-checker-python "$GENERATION_PYTHON" --case mandatory-compute
```

The qualification command requires local Mesa/compiler dependencies. All raw
CPU builds, captures and IPC are temporary and removed after their consumers
exit. Successful execution of the complete case registry still leaves full API,
clock and 60 FPS qualification separate.
