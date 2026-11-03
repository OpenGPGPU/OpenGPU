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

The standard host RTL emitter can opt into this service with `--atomic-counters`;
it reserves sixteen CU transaction IDs. The default profile remains unchanged.
The ARTI runner exposes this as `GPU_ATOMIC_COUNTERS=1` and can select a small
compute profile through `GPU_COMPUTE_UNITS`, `GPU_CU_LANES` and `GPU_CU_WARPS`.
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

The runner requires the operation/fault/recovery marker, a clean QEMU exit and
guest power-down. The output is a local test profile, not a product-capacity
result. Bounded host validation also exercises capability gating, descriptors,
shader admission, ioctl ownership, VM mapping, invalidation and cleanup with
mocked hardware. Broader resource coexistence remains open.

```sh
python3 scripts/test_compute_feature_query.py
python3 scripts/test_compute_texture_ioctl_flow.py
python3 scripts/test_compute_texture_job_records.py
python3 scripts/test_compute_texture_hw_prepare.py
python3 scripts/test_compute_texture_submit.py
python3 scripts/test_driver.py
```
