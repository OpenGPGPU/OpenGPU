# Compute image submission

The [small userspace API](../userspace/README.md) exposes helpers for each
image submission variant and a driver feature query.

The opt-in compute-image ioctl submits up to four image-unit views through the
existing native GIU1 image path. `OPENGPU_DRIVER_FEATURE_COMPUTE_IMAGES` is
reported only when the image command transport and unified commands are
available. Applications must query it before using the ioctl. The default
hardware profile leaves image execution disabled.

Image views refer to previously bound `OPENGPU_RESOURCE_COMPUTE_STORAGE`
slots. The driver snapshots the shader and descriptor table, maps the table
and image backings into the submitting context's VM, and retains their GEM
objects and reservation dependencies through completion. Writable images wait
for prior readers and writers and publish a write fence; read-only images wait
for prior writers and publish a read fence. The driver requires locally proven
pairwise disjoint shader, kernarg and distinct image bindings. With
`OPENGPU_DRIVER_FEATURE_COMPUTE_IMAGE_VIEW_ALIAS`, multiple units may share the
exact same local binding; one reservation is retained and promoted to write
usage if any view is writable. Aliases through different bindings and foreign
backing with unproven identity remain rejected. A zeroed view is unbound. View offsets, pitches, target, dimensions, level and 13 image-unit
formats are checked before a descriptor is published. Hardware validates each
access against its retained descriptor and permissions.

The image-enabled shader validation profile admits unmasked image parameter,
load, store and loaded-component instructions only after tracking their tuple
state through forward control flow. With
`OPENGPU_DRIVER_FEATURE_COMPUTE_TEXTURED_IMAGES`, the
`DRM_IOCTL_OPENGPU_COMPUTE_TEXTURED_IMAGES` ioctl also admits texture operations
in the same shader. It snapshots texture views and retains their read
reservations along with the image dependencies. With
`OPENGPU_DRIVER_FEATURE_COMPUTE_IMAGE_TEXTURE_ALIAS`, a texture and image may
share the exact same local GEM range; the image dependency retains the strongest
usage. With `OPENGPU_DRIVER_FEATURE_COMPUTE_IMAGE_TEXTURE_RANGE_ALIAS`, the
views may instead cover different ranges of the same local GEM object. Other
image/texture backings must be proven disjoint, including their reservations and
DMA ranges. `DRM_IOCTL_OPENGPU_COMPUTE_IMAGE_RESOURCES` additionally submits
storage bindings with images under `OPENGPU_DRIVER_FEATURE_COMPUTE_IMAGE_STORAGE`;
query `OPENGPU_DRIVER_FEATURE_COMPUTE_IMAGE_STORAGE_TEXTURE` before adding
textures to the same job. With
`OPENGPU_DRIVER_FEATURE_COMPUTE_IMAGE_STORAGE_ALIAS`, an image and storage
binding may share the exact same local storage slot. With
`OPENGPU_DRIVER_FEATURE_COMPUTE_IMAGE_STORAGE_RANGE_ALIAS`, they may use
different storage slots and ranges of the same local GEM object. This does not
extend aliases between distinct image slots or distinct storage bindings. The driver
retains one dependency and promotes it to write if either client writes. Other
image/storage backings must be proven disjoint, including their reservations
and DMA ranges.
Texture/storage views may also cover different ranges of the same local GEM
object when `OPENGPU_DRIVER_FEATURE_COMPUTE_TEXTURE_STORAGE_RANGE_ALIAS` is
reported. When all three views share an eligible backing, the driver retains one
dependency with the strongest usage. Resource slots and VM windows
remain shared between storage and image bindings. Query
`OPENGPU_DRIVER_FEATURE_COMPUTE_IMAGE_ATOMICS` before issuing unmasked image
atomics. Admission requires all three parameter pairs on every forward path;
the image hardware checks R32I/R32UI format, format qualification, bounds and
both read and write permissions. The image backing retains its write reservation
when writable. Exchange, add, bitwise operations, min/max and compare-exchange
are supported; general cross-domain ordering remains unqualified. Query `OPENGPU_DRIVER_FEATURE_COMPUTE_IMAGE_BARRIER` before using
converged workgroup barriers; [a bounded two-warp test](WORKGROUP_BARRIER_EPOCH.md)
checks image write acknowledgement and fault recovery. Query
`OPENGPU_DRIVER_FEATURE_COMPUTE_IMAGE_FENCE` before using the canonical
`fence rw,rw`; [CU ordering tests](CU_FENCE_ORDERING.md) cover image and ordinary
memory with delayed acknowledgements and fault recovery. General image
coherence, cross-resource ordering, compiler/API integration, full limits and
physical qualification remain open; this interface is not a GLES conformance
claim.

Local host validation covers descriptor format compatibility and bounds,
shader tuple and component lifetime, MMIO publication, reservation usage,
unsupported and foreign resources, and cleanup after allocation/submission
failure. A small Linux/ARTI guest passed a read-only permission fault and a subsequent
writable image store/load on an image-enabled one-CU model, with fences after
the store and load, checking the loaded value, backing word and neighboring
guard. A separate two-warp guest passed an image exchange across two barrier
generations and checked both observed values and a guard. A third small guest
passed a read-only fault followed by a fenced store through image unit 0 and
load through unit 1 over the same binding, checking the value and guard. A
16×16 one-CU guest also passed a combined texture fetch and fenced image
store/load with disjoint backings, checking both values and neighboring guards.
A one-lane guest also fetched a texel through a texture view, wrote it through
an image view of the same local range, fenced, and refetched the new value;
the backing and neighboring guard matched. Another one-lane guest used a
4 KiB texture binding inside an 8 KiB image/storage binding of the same GEM
object. A fenced texture fetch, storage write, image load, image write and
texture refetch produced the expected values and preserved a guard. These are
bounded checks, not a general coherence claim. A separate 16×16 one-CU guest passed texture fetch plus fenced storage and
image store/load in one submission, checking results and guards for all three
resources. A one-lane guest sharing one storage slot between image and storage
passed fenced store/load exchanges in both directions and preserved a neighboring
guard. Another one-lane guest passed the same exchange through distinct image
and storage slots over overlapping 4 KiB and 8 KiB ranges of one GEM object.
These qualify bounded access patterns, not general cross-domain ordering. A separate one-lane guest passed an image atomic add, checking the
old and new values and a neighboring guard. Another one-lane guest passed a
fenced image store, atomic add and image load on the same R32UI word: the add
returned 7, the load and backing contained 14, and the neighbor was unchanged.
This bounds one submission and does not establish general cross-domain coherence.
The three-resource guest
first timed out at the smoke harness's 300-second emulated
draw budget; the same workload passed with a 720-second budget. This does not
qualify production scheduling latency or cross-client image aliases. None of
these guests renders a frame or runs in PR CI or a nightly lane.

```sh
python3 scripts/test_driver.py
python3 scripts/test_compute_feature_query.py
python3 scripts/test_compute_texture_ioctl_flow.py
python3 scripts/test_compute_texture_job_records.py
python3 scripts/test_compute_texture_hw_prepare.py
python3 scripts/test_compute_texture_submit.py
python3 scripts/test_arti_small_smoke_args.py
ARTI_IMAGE_ATOMICS=1 bash scripts/build_arti_compute_images_model.sh "$QEMU_BUILD" "$QEMU_SRC" "$ARTI_EMBEDDED_WRAPPER"
ARTI_SMOKE_WIDTH=16 ARTI_SMOKE_HEIGHT=16 ARTI_SMOKE_TIMEOUT=1800 \
  ARTI_SMOKE_DRAW_WAIT_MS=720000 QEMU="$IMAGE_ATOMIC_ENABLED_QEMU" \
  bash scripts/run_arti_small_smoke.sh image-atomic
ARTI_SMOKE_WIDTH=16 ARTI_SMOKE_HEIGHT=16 ARTI_SMOKE_TIMEOUT=1800 \
  ARTI_SMOKE_DRAW_WAIT_MS=720000 QEMU="$IMAGE_ATOMIC_ENABLED_QEMU" \
  bash scripts/run_arti_small_smoke.sh image-atomic-fence
bash scripts/build_arti_compute_images_model.sh "$QEMU_BUILD" "$QEMU_SRC" "$ARTI_EMBEDDED_WRAPPER"
ARTI_SMOKE_WIDTH=16 ARTI_SMOKE_HEIGHT=16 ARTI_SMOKE_TIMEOUT=1200 \
  QEMU="$IMAGE_ENABLED_QEMU" \
  bash scripts/run_arti_small_smoke.sh image
ARTI_SMOKE_WIDTH=16 ARTI_SMOKE_HEIGHT=16 ARTI_SMOKE_TIMEOUT=1200 \
  QEMU="$IMAGE_ENABLED_QEMU" \
  bash scripts/run_arti_small_smoke.sh image-alias
ARTI_SMOKE_WIDTH=16 ARTI_SMOKE_HEIGHT=16 ARTI_SMOKE_TIMEOUT=1200 \
  QEMU="$IMAGE_AND_TEXTURE_ENABLED_QEMU" \
  bash scripts/run_arti_small_smoke.sh image-texture
ARTI_SMOKE_WIDTH=16 ARTI_SMOKE_HEIGHT=16 ARTI_SMOKE_TIMEOUT=1800 \
  ARTI_SMOKE_DRAW_WAIT_MS=720000 QEMU="$IMAGE_AND_TEXTURE_ENABLED_QEMU" \
  bash scripts/run_arti_small_smoke.sh image-texture-alias
ARTI_SMOKE_WIDTH=16 ARTI_SMOKE_HEIGHT=16 ARTI_SMOKE_TIMEOUT=1800 \
  ARTI_SMOKE_DRAW_WAIT_MS=720000 QEMU="$IMAGE_AND_TEXTURE_ENABLED_QEMU" \
  bash scripts/run_arti_small_smoke.sh image-resource-ranges
ARTI_SMOKE_WIDTH=16 ARTI_SMOKE_HEIGHT=16 ARTI_SMOKE_TIMEOUT=1800 \
  ARTI_SMOKE_DRAW_WAIT_MS=720000 QEMU="$IMAGE_AND_TEXTURE_ENABLED_QEMU" \
  bash scripts/run_arti_small_smoke.sh image-resources
ARTI_SMOKE_WIDTH=16 ARTI_SMOKE_HEIGHT=16 ARTI_SMOKE_TIMEOUT=1800 \
  ARTI_SMOKE_DRAW_WAIT_MS=720000 QEMU="$IMAGE_AND_TEXTURE_ENABLED_QEMU" \
  bash scripts/run_arti_small_smoke.sh image-storage-alias
ARTI_SMOKE_WIDTH=16 ARTI_SMOKE_HEIGHT=16 ARTI_SMOKE_TIMEOUT=1800 \
  ARTI_SMOKE_DRAW_WAIT_MS=720000 QEMU="$IMAGE_AND_TEXTURE_ENABLED_QEMU" \
  bash scripts/run_arti_small_smoke.sh image-storage-ranges
```
