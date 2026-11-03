# Compute storage submission

`DRM_IOCTL_OPENGPU_COMPUTE_STORAGE` submits a compute shader with up to four
storage bindings. The driver advertises `OPENGPU_DRIVER_FEATURE_COMPUTE_STORAGE`
when GSM1 and unified commands are available. Each nonzero entry names an
`OPENGPU_RESOURCE_COMPUTE_STORAGE` binding and read/write access; a zero slot
with zero access leaves the entry unbound. Storage resources use slots 13–16,
page-aligned offsets and 64-byte-aligned nonzero sizes up to 128 MiB. Contexts
must have a private VM; each slot maps into a separate uncached 128 MiB window. Rebinding to a
mapped resource reserves page tables before atomically replacing the old
range, including overlapping pages and a shorter tail. A failed shootdown
restores the original leaves; a second failed shootdown wedges submissions.

The driver snapshots and validates the shader, checks and builds a private GSB1
table, and retains the table, VM and GEM objects through completion. It waits
on prior writers for read-only inputs, and on prior readers and writers for
writable inputs; the completion fence carries the matching use. Distinct
storage resources, shader and writable kernarg must have provably disjoint local
backing and DMA ranges; unknown foreign provenance and shared reservation
objects are rejected. Storage instructions receive their own admission profile;
texture instructions, atomics and acquire/release ordering are not admitted in
this ioctl. This is not a cross-domain coherence or general API contract.

Local host tests exercise four bindings, read/write fence usage, malformed
metadata, 128 MiB address limits, alias and provenance rejection, and allocation
and submission failures without traversing a large frame:

```sh
python3 scripts/test_driver.py
CC='cc -fsanitize=address,undefined -fno-omit-frame-pointer' \
  python3 scripts/test_compute_texture_ioctl_flow.py
```

A separate 16×16, one-CU, four-lane ARTI Linux model passed a translated
submission: a two-page storage buffer was rebound to a smaller range, the real
ioctl rejected an aliased writable kernarg, then a kernel stored and loaded one
word through storage and checked both the backing and returned value after the
fence. Host MMU tests additionally check revoked tail pages and rollback. This is a bounded integration check. Reproduce
with an existing configured ARTI/QEMU build:

```sh
scripts/build_arti_compute_storage_model.sh "$QEMU_BUILD" "$QEMU_SRC" "$ARTI_EMBEDDED_WRAPPER"
ARTI_SMOKE_WIDTH=16 ARTI_SMOKE_HEIGHT=16 QEMU="$ISOLATED_QEMU" \
  scripts/run_arti_small_smoke.sh storage
```

Set `ISOLATED_QEMU` to the path printed by the build; set `ARTI_DIR` and
`ARTI_WORK` when needed.
This opt-in smoke runs outside regular PR and nightly CI. H7 resource coexistence,
broader ordering, runtime/compiler integration and full product qualification
remain open.
