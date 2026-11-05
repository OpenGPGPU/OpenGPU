# Linux compute atomic-counter submission

On hardware advertising GCR1 atomic-counter commands, the driver validates
GAM1 discovery and exposes `OPENGPU_DRIVER_FEATURE_COMPUTE_ATOMIC_COUNTERS`
when unified commands and protected VM state are present. Use
`DRM_IOCTL_OPENGPU_COMPUTE_COUNTER_RESOURCES` or the
`opengpu_compute_counter_resources` userspace helper. A private VM is required.

The counter view specifies a bound `OPENGPU_RESOURCE_COMPUTE_STORAGE` slot,
a four-byte-aligned offset and one to eight adjacent 32-bit counters. The
backing must be a locally provable allocation disjoint from the shader,
kernarg, all enabled storage and image bindings, and texture views. The driver
retains the backing with write reservation semantics, snapshots a separate
GSB1 table, maps it uncached until retirement, invalidates the table before
submission, and clears GAM1 publication before a command without counters.
Optional storage and image arrays are enabled by their resource flags; unused
arrays must be zero. Texture views use the existing copied-view format. Other
resource features must be advertised independently.

The compute validator admits unmasked CUSTOM-1 counter read, increment and
decrement only through this path. The vector source supplies each lane's
counter index; the hardware checks that index against the view. Read and
increment return the old value; decrement returns the new value. A later lane
fault cannot undo already acknowledged atomic updates. This path does not
claim alias coherence, general cross-domain ordering, a GLES compiler, or full
H7 conformance.

## Retained descriptor reuse

The CU service keeps one successfully checked counter view for its retained
task. Its key includes the complete GSB1 table publication: version, base,
exclusive limit, context, pass and generation. The task's captured Sv32 root
owns the view; task start/retirement, any VM flush, cancellation and failed
operation retirement invalidate it. An invalidation also prevents a pending
descriptor reply from filling later. A vector abort invalidates the view even
between scalar lane requests, while an already offered lane still drains.
Standalone `CheckedAtomicCounter` remains uncached unless its owner explicitly
opts in and supplies these lifecycle invalidations.

Every counter operation still checks its index, view permissions and complete
word/line bounds and issues its own acknowledged atomic. No counter value,
atomic result, memory fence or completion is cached. Descriptor faults and
malformed or denied views never fill. The publisher must keep descriptor bytes
and allocation ownership immutable until the task and accepted traffic drain.
This reuse does not establish cross-resource coherence or a new API capability.

All sixteen cases in the four suites below pass locally with no skipped cases.
The existing four-lane increment/decrement/read fixture now fetches one
descriptor instead of twelve while retaining all twelve atomic transactions,
returned values and fault checks, in both bare and Sv32 modes. Separate bounded
cases cover eight offsets at the exclusive 4 GiB endpoint, pending-fill
invalidation, permission revocation, changed task/root, held results and both
vector abort timings. Their atomic responders are mocked; these are service
and protocol checks, not full-profile L2, performance or H7 qualification.

Run the bounded service and vector regressions without rendering a frame:

```sh
sbt -J-Xmx3G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.productQualification=0 'set Test / parallelExecution := false' \
  'testOnly opengpu.core.memory.CheckedAtomicCounterSpec opengpu.core.memory.AtomicCounterCuServiceSpec opengpu.core.memory.AtomicCounterVectorExecuteUnitSpec opengpu.core.memory.AtomicCounterVectorCuServiceSpec'
```

The complete descriptor/translation service at the sixteen-lane/eight-warp
profile passes a 1,000 ps TC/SLVT synthesis screen with 25% I/O delays. Core and
I/O setup slacks are +395.320 ps and +361.948 ps. Hierarchical mapping and
constraint coverage pass with zero skipped or unresolved instances, no
retiming and no false paths. This SRAM-free component does not include the
external atomic translator/L2, and is not a routed or whole-CU timing result.

```sh
sbt 'Test/runMain opengpu.core.memory.EmitAtomicCounterCuServiceTiming .artifacts/counter-service-rtl'
CHIPAGENT_DIR=../chipagent ../chipagent/.venv/bin/python scripts/qualify_ppa.py \
  .artifacts/counter-service-rtl AtomicCounterCuService --stage synthesis \
  --synthesis-engine yosys --yosys-preserve-hierarchy --period-ps 1000 \
  --io-delay 0.25 --output .artifacts/counter-service-synthesis
```

## Host and Linux qualification

The standard host RTL emitter can opt into this service with `--atomic-counters`;
it reserves sixteen CU transaction IDs. The default profile remains unchanged.
Add `--compute-resources` to enable the storage, image and texture services in
the same model. The ARTI runner exposes these flags as `GPU_ATOMIC_COUNTERS=1`
and `GPU_COMPUTE_RESOURCES=1`; it can select a small compute profile through
`GPU_COMPUTE_UNITS`, `GPU_CU_LANES` and `GPU_CU_WARPS`.
For example, emit a 16×16 one-CU test profile without rendering a frame:

```sh
sbt 'runMain opengpu.elaboration.EmitGpuHostSystemAxi .artifacts/counter-rtl --width 16 --height 16 --compute-units 1 --cu-lanes 4 --cu-warps 2 --memory-axi-data-bytes 8 --atomic-counters'
make -C userspace examples/compute_counter
./userspace/examples/compute_counter /dev/dri/renderD128
```

A current-RTL Linux guest passes the counter ioctl on one four-lane, two-warp
CU with an eight-byte AXI interface and a 16×16 device extent; it renders no
frames. The driver and hardware sources are from
`1598376701ca2779ffea69c716e5bf3a0dd646fb`, with the emitter option in this
change enabled. Four lanes increment, read and decrement a word-aligned counter, check
all returned values and surrounding guards, and verify invalid views and a
kernarg alias are rejected. A dynamic out-of-range index fails the completion
fence with the expected fault, leaves the counter unchanged, and is followed by
a successful submission in the same context. QEMU exits zero after guest
power-down. This validates the direct path at that small profile, not full
capacity, concurrent CUs, coenabled resource execution, alias coherence or H7.

A second current-RTL Linux guest uses one lane on the same one-CU profile and
submits counter, texture, storage and image operations in a single kernel with
explicit fences and separate backings. It checks each result and guard, rejects
counter aliases with each other resource, reports an out-of-range counter
fault, and successfully repeats the job in the same context. QEMU exits zero
after guest power-down. The driver and hardware sources are from
`36fd12ae422ed6731489b6c1211abb53d5356f26`, with the emitter option in this
change enabled. The test does not establish simultaneous multi-CU execution,
general alias coherence, full resource limits or H7 completion.

For a reproducible private model and guest, set `QEMU_BUILD` to an existing
configured ARTI QEMU build and `ARTI_EMBEDDED_WRAPPER` to its compatible
8-byte AXI Verilator wrapper directory. Set `ARTI_WORK` and `ARTI_DIR` if they
are not adjacent to the checkout. The model builder prints its private QEMU and
matching device-tree paths; use those as `COUNTER_QEMU` and `COUNTER_DTB`:

```sh
scripts/build_arti_compute_counter_model.sh "$QEMU_BUILD" "$ARTI_EMBEDDED_WRAPPER"
ARTI_SMOKE_WIDTH=16 ARTI_SMOKE_HEIGHT=16 QEMU="$COUNTER_QEMU" \
  ARTI_SMOKE_DTB="$COUNTER_DTB" scripts/run_arti_small_smoke.sh counter
```

For the combined-resource case, build the model with the resource option and
use its printed paths with the combined smoke mode:

```sh
ARTI_COUNTER_RESOURCES=1 scripts/build_arti_compute_counter_model.sh "$QEMU_BUILD" "$ARTI_EMBEDDED_WRAPPER"
ARTI_SMOKE_WIDTH=16 ARTI_SMOKE_HEIGHT=16 QEMU="$COUNTER_QEMU" \
  ARTI_SMOKE_DTB="$COUNTER_DTB" scripts/run_arti_small_smoke.sh counter-resources
```

The runner requires the operation/fault/recovery marker, a clean QEMU exit and
guest power-down. The output is a local test profile, not a product-capacity
result. Bounded host validation also exercises capability gating, descriptors,
shader admission, ioctl ownership, VM mapping, invalidation and cleanup with
mocked hardware. Broader resource and concurrency qualification remains open.

```sh
python3 scripts/test_compute_feature_query.py
python3 scripts/test_compute_texture_ioctl_flow.py
python3 scripts/test_compute_texture_job_records.py
python3 scripts/test_compute_texture_hw_prepare.py
python3 scripts/test_compute_texture_submit.py
python3 scripts/test_driver.py
```
