# Combined compute storage and texture submission

`DRM_IOCTL_OPENGPU_COMPUTE_RESOURCES` submits one compute job with up to four
storage bindings and 32 texture-unit views. Query
`OPENGPU_DRIVER_FEATURE_COMPUTE_RESOURCES` before use. It combines the existing
[storage](COMPUTE_STORAGE_SUBMISSION.md) and
[texture](COMPUTE_TEXTURE_SUBMISSION.md) validation and retained descriptor
records, and publishes the corresponding reservation fences through the job's
completion. Storage backings must be provably disjoint from the shader, kernarg,
other storage backings and every texture backing; textures must also be disjoint
from the writable kernarg. Multiple texture views may share a read-only backing.
Shared reservations across storage and other resources and unprovable foreign
aliases are rejected; no cross-resource ordering or coherence guarantee is established.
Relaxed word atomics on the writable kernarg remain feature-gated.

Storage slots 35–38 allow four buffers alongside 32 textures and shader and
kernarg resources. Existing slots 13–16 remain supported; slot 13 shares a VM
window with slot 35, and likewise for each following pair. The driver rejects
binding both members of a pair simultaneously. Each storage view supports up to
128 MiB in its private uncached window. Combined submissions require a private
VM. GPU API/compiler integration, broader resource coexistence, cross-domain
ordering and the full H7 gates remain open.

The host fixture exercises 32 distinct textures with four storage bindings,
deduplication, read/write fences, alias and provenance rejection, feature
fallback, and allocation/submission failures without rendering a frame:

```sh
python3 scripts/test_driver.py
CC='cc -fsanitize=address,undefined -fno-omit-frame-pointer' \\
  python3 scripts/test_compute_texture_ioctl_flow.py
```

A separate 16×16, one-CU, four-lane ARTI Linux model passed a translated
combined submission. The guest checked paired-slot conflict and texture/storage
alias rejection, then verified texture size and a fetched texel, storage
store/load, and relaxed kernarg atomic old/new values after the completion
fence. This is a bounded integration check, not full coexistence qualification.
With an existing configured ARTI/QEMU build, reproduce it with:

```sh
scripts/build_arti_compute_resources_model.sh "$QEMU_BUILD" "$QEMU_SRC" "$ARTI_EMBEDDED_WRAPPER"
ARTI_SMOKE_WIDTH=16 ARTI_SMOKE_HEIGHT=16 QEMU="$ISOLATED_QEMU" \
  scripts/run_arti_small_smoke.sh resources
```

Set `ISOLATED_QEMU` to the printed path and `ARTI_DIR`/`ARTI_WORK` when needed.
The smoke is opt-in and runs outside regular PR and nightly CI.
