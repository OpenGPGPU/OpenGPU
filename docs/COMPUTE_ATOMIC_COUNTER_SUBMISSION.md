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

Bounded host validation exercises capability gating, descriptor boundaries,
shader admission, ioctl ownership and alias rejection, VM mapping and cleanup,
cache invalidation failures, and publication/clearing using the production
code with mocked hardware. The AArch64 driver module also builds. These tests
do not execute this ioctl on a counter-enabled current-RTL GPU; that guest and
full resource coexistence qualification remain open.

```sh
python3 scripts/test_compute_feature_query.py
python3 scripts/test_compute_texture_ioctl_flow.py
python3 scripts/test_compute_texture_job_records.py
python3 scripts/test_compute_texture_hw_prepare.py
python3 scripts/test_compute_texture_submit.py
python3 scripts/test_driver.py
```
