# Compute texture submission

`DRM_IOCTL_OPENGPU_COMPUTE_TEXTURES` extends the existing compute launch with
an array of up to 32 texture-unit views. A zero slot leaves that unit unbound;
other slots name `OPENGPU_RESOURCE_COMPUTE_TEXTURE` bindings in the launch
context. Each view has a target, format, dimensions, swizzle, sampler and up
to 16 level records. Level offsets are relative to the bound resource, not GPU
addresses. Sparse levels remain sparse. The driver copies the view array once,
checks metadata and binding ranges, snapshots the shader, builds job-owned
records, and retains GEM dependencies through the scheduler completion fence.
Texture inputs wait for prior writers and publish read fences; kernarg publishes
a write fence. VM contexts use private mappings for texture data and records.

The current path rejects foreign dma-buf provenance when disjointness from the
writable kernarg cannot be proven, distinct wrappers that share a reservation,
packet-based texture fetch from writable kernargs, and texture-plus-word-atomic
shaders. A context has 34 resource slots, with slots 17–34 reserved for
compute texture backing. This permits 32 distinct textures alongside shader
and kernarg resources. Private VMs can allocate up to 64 second-level tables
on demand. `OPENGPU_DRIVER_FEATURE_COMPUTE_TEXTURE` is set only when GTM1 and
unified commands are available. Texture cache/coherence across all domains,
imported backing provenance and the wider H7 requirements remain open.

Local validation:

```sh
python3 scripts/test_driver.py
python3 scripts/test_compute_texture_prepare.py
python3 scripts/test_compute_texture_ioctl_flow.py
scripts/run_arti_small_smoke.sh bind
```

The host tests exercise actual preparation with mock GEM and DRM services,
including Bare and VM addresses, dependency uses, alias rejection and error
cleanup. The bind smoke checks malformed requests and unavailable-transport
rejection. A separate 16×16, one-CU, four-lane ARTI model with GTM1 enabled
passed one kernel that queried the width of a 4×4 `R32UI` texture and fetched a
known texel through this ioctl. It used a translated context and a held completion
fence. This is a bounded integration check, not full texture, coherence or H7
qualification. The model and smoke can be reproduced locally with an existing
configured ARTI/QEMU build:

```sh
scripts/build_arti_compute_texture_model.sh "$QEMU_BUILD" "$QEMU_SRC" "$ARTI_EMBEDDED_WRAPPER"
ARTI_SMOKE_WIDTH=16 ARTI_SMOKE_HEIGHT=16 QEMU="$ISOLATED_QEMU" \
  scripts/run_arti_small_smoke.sh texture
```

The first command prints the isolated QEMU path; set `ISOLATED_QEMU` to that
path and provide the usual `ARTI_DIR` and `ARTI_WORK` environment if needed.
This opt-in smoke is outside regular PR and nightly CI.

The host MMU check maps 32 texture regions plus code, kernarg and record
windows, then checks the 64-table boundary. A small ARTI bind smoke binds and
unbinds 32 separate texture backings; this does not execute a 32-texture GPU
workload.
