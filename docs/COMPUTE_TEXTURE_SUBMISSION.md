# Compute texture submission (unqualified)

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
shaders. The existing context has 16 resource slots shared with shader and
kernarg, even though up to 32 texture units can refer to those bindings. It does
not yet advertise a qualified driver feature. Texture cache/coherence across all
domains, imported backing provenance and the wider H7 requirements remain open.

Local validation:

```sh
python3 scripts/test_driver.py
python3 scripts/test_compute_texture_prepare.py
python3 scripts/test_compute_texture_ioctl_flow.py
scripts/run_arti_small_smoke.sh bind
```

The host tests exercise actual preparation with mock GEM and DRM services,
including Bare and VM addresses, dependency uses, alias rejection and error
cleanup. The ARTI smoke uses a configuration without compute texture transport;
it checks binding, malformed ioctl requests and unavailable-transport rejection.
No texture-enabled GPU kernel has yet been executed through this ioctl, so this
implementation is not qualified for production use.
