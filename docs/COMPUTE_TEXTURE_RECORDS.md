# Compute texture resource and packet records

The checked compute encoders write GXR1 roots and GXB1 bindings with stage 2,
GXI1 image headers, GXL1 level layouts, GXQ1 coordinate packets for integer
fetches, and resolved GXS1 sampler records.
Compute decodes a two-bit stage; the existing generated graphics ABI describes
its one-bit vertex/fragment stage and is unchanged. The remaining word offsets,
magic and sizes are shared. Output consists of host words that the caller must
serialize little-endian.

The encoder checks the root's count, table alignment, exclusive 33-bit limits,
record strides and binding-table extent, plus the binding's unit, expected type
and target, the packet's face field, sampler filter/wrap and LOD encoding, and
the image header's target, representable dimensions, swizzle and first-level
table extent. Level records additionally check block size, pitch relationships
and the full allocation span using wide arithmetic. Image limits can vary by RTL configuration and are checked at
admission; representability here does not promise a particular device limit.
Sampler infinities and reversed LOD clamps are accepted; NaNs are rejected.
Coordinates remain full-width for
subsequent image/target checks. A size-only root may explicitly omit its sampler table; fetch and
sample callers must provide one. `size_only` is an encoder option, not an
encoded permission: the caller must restrict operations accordingly. Failed
encoding leaves the destination unchanged. It does not
validate image or sampler handles against those tables, pin resources, publish
commands, or admit a native driver job.
The caller must retain records, data and VM through completion. Full H7 resource
and driver integration remain open.

The separate level-consistency helper checks caller-owned snapshots against an
image header. It validates either one selected level or the sampler-required
chain, including identity, handles, mip dimensions, allocation, format block
size and nonoverlap. It checks at most 16 levels. It does not dereference user
pointers, pin resources, check sampler compatibility or prove device-specific
limits; callers must establish those conditions separately.

The sampler-consistency helper checks kernel-owned binding, image and sampler
snapshots for matching identity, handles and target, then applies the RTL's
typed-format, filter and comparison rules for fetch or sampling. Its generated
format-policy table derives from the existing GTF1 format ABI. It does not prove
root table membership, mip completeness, resource ownership or device-specific
limits; callers must establish those conditions separately.

The snapshot helper combines those checks with record-address membership in the
root and image tables. It validates size, packet fetch, captured fetch and
sampling snapshots; non-mip fetches also require the base-level snapshot when
the selected level differs. Selected levels are absolute, and captured callers
must resolve relative LOD first. It checks the intended GPU addresses, not the
contents of GPU memory: the driver must control publication, prevent unsafe
aliasing or mutation, and retain ownership and mappings through completion. It
is not yet wired into native driver submission or a substitute for device
admission and resource pinning.

The bound-view helper resolves copied per-level offsets against one authorized
GPU binding and rejects wrapping addresses, out-of-range spans and inconsistent
layouts before producing inputs for the table builder. It does not look up or
retain a GEM object, establish mappings or prove alias and cache safety; a
submission path must do that work before use.

The table builder serializes a kernel-owned set of up to 32 binding slots into
one little-endian root/binding/image/sampler/level buffer. It assigns handles
and table addresses, validates every supplied level, and leaves absent slots and
mips zero. It accepts sparse or incomplete chains; hardware still decides
operation-specific sampler compatibility and mip completeness. Callers must
provide stable, non-overlapping host inputs and verify ownership, aliases,
mappings and lifetime for all backing allocations. The compute-texture ioctl
uses it with copied view metadata; broader GPU qualification remains open.

```sh
python3 scripts/test_driver.py
python3 scripts/test_compute_texture_level.py
python3 scripts/generate_compute_texture_format_info.py --check
sbt 'runMain opengpu.elaboration.EmitTextureImageFormatAbi --check'
MAKE=gmake ARCH=arm64 CROSS_COMPILE=aarch64-linux-gnu- \
  scripts/test_compute_texture_records_kernel.sh "$KDIR"
```

The small C checks cover stage 2, exact root/binding/image/level/packet/sampler words from the existing
binding-31 CU fixture, the graphics word-offset contract, exclusive 4 GiB
limits, malformed tables and untouched output on failure. The AArch64
kernel probe builds the encoder in a configured kernel tree; it does not execute
on hardware. `KDIR` names a prepared kernel build tree.
The deterministic level check compares 5,000 bounded numeric layouts against
an independent wide-integer model; it does not render a frame. Driver tests
also cover all 64 format IDs in 2D and separate cases for all four targets.
Sampler checks cover all 64 format IDs across filter, comparison and shadow
combinations as bounded host arithmetic; they do not render frames.

Snapshot checks exercise table membership, sampler and mip interactions, and
packet identity with small host-only fixtures.

Compute scheduler jobs now have a private descriptor buffer retained until
retirement. When texture staging is enabled, the root must name the buffer
start; translated jobs map it in command window slot 2 and retire that mapping
with the job. The compute path orders CPU writes and invalidates the buffer
before the kernel doorbell. The caller must construct records for that VA and
retain and validate all referenced backing data. The compute-texture ioctl
constructs this buffer from bound resources; broader enabled-path GPU
qualification remains open. The mock-VM job check runs with
`python3 scripts/test_compute_texture_job_records.py`; a mock lock/invalidation
check runs with `python3 scripts/test_compute_texture_hw_prepare.py`. Neither
executes the texture path on a GPU.

The table builder host test covers sparse slots, all 64 formats, four targets,
malformed records, unchanged output on failure and the 4 GiB boundary.

The bound-view host check covers sparse levels, failed-output preservation,
all four targets and an exclusive 4 GiB limit.

The resource bind ioctl accepts `OPENGPU_RESOURCE_COMPUTE_TEXTURE` as raw GEM
backing, including byte-aligned subranges. View and sampler metadata are not
part of the binding. The existing context mapping and unbind lifetime apply;
the compute-texture ioctl consumes it after alias and bounds checks, but this
binding alone does not establish a cross-resource coherence contract. A 64×64 ARTI bind smoke checks valid,
invalid and uncached bindings through `scripts/run_arti_small_smoke.sh bind`.

The ioctl preparation checks run with `python3 scripts/test_compute_texture_prepare.py`
and `python3 scripts/test_compute_texture_ioctl_flow.py`. They exercise actual
submission preparation with mock GEM/DRM objects, including alias rejection,
reservation dependencies and Bare/VM table addresses. The small ARTI bind
smoke checks malformed arguments and unavailable GTM1. A separate small
ARTI model passed one enabled size/fetch/relaxed-word-atomic job through the
ioctl. Foreign dma-buf aliases remain unsupported when provenance cannot prove
disjointness. Relaxed
word atomics can use the writable kernarg alongside proven-disjoint textures;
cross-resource ordering remains unqualified. The advertised driver bit describes
this bounded interface, not full H7 qualification.
