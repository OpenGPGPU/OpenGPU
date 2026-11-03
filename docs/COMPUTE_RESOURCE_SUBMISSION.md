# Combined compute storage and texture submission

For a combined texture and image job, see [compute image submission](COMPUTE_IMAGE_SUBMISSION.md).

`DRM_IOCTL_OPENGPU_COMPUTE_RESOURCES` submits one compute job with up to four
storage bindings and 32 texture-unit views. Query
`OPENGPU_DRIVER_FEATURE_COMPUTE_RESOURCES` before use. It combines the existing
[storage](COMPUTE_STORAGE_SUBMISSION.md) and
[texture](COMPUTE_TEXTURE_SUBMISSION.md) validation and retained descriptor
records, and publishes the corresponding reservation fences through the job's
completion. Storage backings must be provably disjoint from the shader, kernarg
and other storage backings; textures must also be disjoint from the writable
kernarg. When `OPENGPU_DRIVER_FEATURE_COMPUTE_TEXTURE_STORAGE_ALIAS` is set, a
texture and storage binding may name the exact same local GEM object and byte
range through their separate VM windows. Their reservation dependency is merged,
including writable storage's stronger dependency. With
`OPENGPU_DRIVER_FEATURE_COMPUTE_TEXTURE_STORAGE_RANGE_ALIAS`, the texture and
storage bindings may cover different ranges of the same local GEM object. Other
shared reservations and unprovable foreign aliases are rejected. A small
[image resource guest](COMPUTE_IMAGE_SUBMISSION.md) validates a 4 KiB texture
range overlapping an 8 KiB image/storage binding of the same object. Multiple
texture views may share a backing. Workgroup barriers are admitted at validated
convergent points and the canonical compute fence is admitted; shaders must use
the synchronization required by their access pattern. This does not establish a
general cross-resource coherence guarantee.
Relaxed word atomics on the writable kernarg remain feature-gated.

Storage slots 35–38 allow four buffers alongside 32 textures and shader and
kernarg resources. Existing slots 13–16 remain supported; slot 13 shares a VM
window with slot 35, and likewise for each following pair. The driver rejects
binding both members of a pair simultaneously. Each storage view supports up to
128 MiB in its private uncached window. Combined submissions require a private
VM. GPU API/compiler integration, broader resource coexistence, cross-domain
ordering and the full H7 gates remain open.

The host fixture exercises 32 distinct textures with four storage bindings,
deduplication, read/write fences, exact shared-object alias admission,
foreign/mismatched alias rejection, feature fallback, and allocation/submission
failures without rendering a frame:

```sh
python3 scripts/test_driver.py
CC='cc -fsanitize=address,undefined -fno-omit-frame-pointer' \\
  python3 scripts/test_compute_texture_ioctl_flow.py
```

A separate 16×16, one-CU, four-lane ARTI Linux model passed a translated
combined submission. The guest checked paired-slot conflict and writable-kernarg
alias rejection, then verified the sizes of two textures and a fetched texel, four lanes storing
and loading distinct values through four storage backings, and relaxed kernarg
atomic old/new values after the completion fence. This is a bounded integration check, not full coexistence qualification.
With an existing configured ARTI/QEMU build, reproduce it with:

```sh
scripts/build_arti_compute_resources_model.sh "$QEMU_BUILD" "$QEMU_SRC" "$ARTI_EMBEDDED_WRAPPER"
LLVM_MC="$LLVM_MC" LLVM_OBJCOPY="$LLVM_OBJCOPY" \
  ARTI_SMOKE_WIDTH=16 ARTI_SMOKE_HEIGHT=16 QEMU="$ISOLATED_QEMU" \
  scripts/run_arti_small_smoke.sh resources
```

Set `ISOLATED_QEMU` to the printed path and `ARTI_DIR`/`ARTI_WORK` when needed.
Set `LLVM_MC` and `LLVM_OBJCOPY` to the OpenGPU LLVM tools. The smoke assembles
`userspace/shaders/compute_resources.S` and runs the resulting image through
`userspace/examples/compute_resources.c` and `userspace/opengpu.c`. Texture
operations use OpenGPU mnemonics; storage operations still use explicit `.insn`
encodings because LLVM storage mnemonics are not yet available. The smoke is
opt-in and runs outside regular PR and nightly CI.

A local 16×16, one-CU Linux/ARTI smoke binds the exact same local GEM range
through separate texture and storage VM windows. Two warps fetch the old texel,
write that word through storage, synchronize with a fence and workgroup barrier,
and refetch the new texel. Both values and the backing word pass. The feature
bit is set only when both compute transports and unified commands are available;
older drivers do not advertise this alias contract. This does not qualify
foreign or differently ranged aliases or general GLES coherence. Reproduce with
the isolated combined-resource model above:

```sh
ARTI_SMOKE_WIDTH=16 ARTI_SMOKE_HEIGHT=16 ARTI_SMOKE_TIMEOUT=1200 QEMU="$ISOLATED_QEMU" \
  scripts/run_arti_small_smoke.sh resources-alias
```
