# Compute image submission

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
pairwise disjoint shader, kernarg and image backings; repeated image objects,
foreign backing with unproven identity, and aliases are rejected. A zeroed view
is unbound. View offsets, pitches, target, dimensions, level and 13 image-unit
formats are checked before a descriptor is published. Hardware validates each
access against its retained descriptor and permissions.

The image-enabled shader validation profile admits unmasked image parameter,
load, store and loaded-component instructions only after tracking their tuple
state through forward control flow. The driver does not currently combine image
views with texture or storage instructions in one submission, admit image
atomics, or allow workgroup barriers or fences in image jobs. General image
coherence, cross-resource ordering, compiler/API integration, full limits and
physical qualification remain open; this interface is not a GLES conformance
claim.

Local host validation covers descriptor format compatibility and bounds,
shader tuple and component lifetime, MMIO publication, reservation usage,
unsupported and foreign resources, and cleanup after allocation/submission
failure. A small Linux/ARTI guest passed a read-only permission fault and a subsequent
writable image store/load on an image-enabled one-CU model, checking the
loaded value, backing word and neighboring guard. The guest renders
no frame and is not part of PR CI or a nightly lane.

```sh
python3 scripts/test_driver.py
python3 scripts/test_compute_feature_query.py
python3 scripts/test_compute_texture_ioctl_flow.py
python3 scripts/test_compute_texture_job_records.py
python3 scripts/test_compute_texture_hw_prepare.py
python3 scripts/test_compute_texture_submit.py
python3 scripts/test_arti_small_smoke_args.py
bash scripts/build_arti_compute_images_model.sh "$QEMU_BUILD" "$QEMU_SRC" "$ARTI_EMBEDDED_WRAPPER"
ARTI_SMOKE_WIDTH=16 ARTI_SMOKE_HEIGHT=16 ARTI_SMOKE_TIMEOUT=1200 \
  QEMU="$IMAGE_ENABLED_QEMU" \
  bash scripts/run_arti_small_smoke.sh image
```
