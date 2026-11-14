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

No fresh complete RTL/model or actual Mesa execution qualifies either new
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
