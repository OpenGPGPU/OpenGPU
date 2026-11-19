# P11 buffer-image publication

Original Mesa coherent r32ui buffer-image publication corpus and complete-owner
continuous execution for P11's image domain: GPU producer → warmed/reload
consumer on one assertion-enabled float DUT, same RAM, one reset, with
Verilator observation of the uncached image fabric traffic on the retained
data line.

This closes the buffer-image float DUT gate and its uncached publication fabric
witness. It does **not** close P11. Ordinary L1 cached-alias consumers, and the
remaining texture/attachment/DMA/counter/shared domains, stay open. Count
remains **10/40**.

## Corpus

| Case | Role | Source | Expected data/flag |
| --- | --- | ---: | --- |
| `image-publication-warm` | warm | `warm.comp` | 0 / 0 |
| `image-publication-1` | producer | `publication-1.comp` | 7 / 1 |
| `image-publication-reload` | reload | `reload.comp` | 7 / 1 |

Producer `imageStore(image0,1,uvec4(7))`, executes `memoryBarrierImage()`, then
`imageStore(image0,17,uvec4(1))`. Warm and reload consumers `imageLoad` those
texels into an SSBO output window. All three phases share physical base
`0x01000000`. Retained image extents keep measured DUT bytes across Mesa
relaunches; other exact physical overlaps are replaced from the new upload.

Checked buffer-image accesses bypass private L1/L2 (see
[GALLIUM_IMAGE_ORDERING.md](GALLIUM_IMAGE_ORDERING.md)). The continuous session
therefore records warm AR → producer AW → reload AR on the retained data
line through the Verilator cache observer (`publication.cache-target` /
`publication.cache-observation.json`).

Qualify entry: `scripts/qualify_compute_owner.py --case image-publication`
(requires the Verilator complete-float executor).

## Actual complete float DUT execution

Execution source `158652f5c4a5dd87c74d753597bf5e9b89e84b3e` on the assertion-
enabled `two-cu-vga60-owner-v2-image-float-stage` Verilator compute executor.
All three phases passed independent returned-byte/guard oracles, cleanup ACK,
one DUT/RAM/single reset, uncached fabric observation, and unchanged
source/model postchecks:

```text
original image publication phase=0 role=warm full bytes/cleanup PASS
original image publication phase=1 role=producer full bytes/cleanup PASS
original image publication phase=2 role=reload full bytes/cleanup PASS
all three original image publication phases PASS; one DUT/RAM/reset,
warm/producer/reload, uncached fabric observation, original source/model postchecks
original Mesa compute image-publication: actual full-DUT returned bytes
and context cleanup PASS
```

Reproduce against a prepared float owner (see
[COMPLETE_OWNER_COMPUTE_EXECUTOR.md](COMPLETE_OWNER_COMPUTE_EXECUTOR.md)):

```sh
OWNER_OUTPUT=$PWD/.artifacts/p11-float-owner
MESA_ROOT=/Users/duckdonald/workspace/gpu/depends
PYTHONDONTWRITEBYTECODE=1 /usr/bin/python3 -B scripts/qualify_compute_owner.py \
  --executor "$OWNER_OUTPUT/executors/compute/executor" \
  --executor-qualification "$OWNER_OUTPUT/executors/compute/qualification.json" \
  --executor-checker scripts/prepare_owner_executor.py \
  --executor-checker-python /usr/bin/python3 \
  --compiler-image sha256:cc860b33283c55bb906f73dbe48621b6dbb4dcce6d65098f7f29550e49459d72 \
  --mesa "$MESA_ROOT/mesa" \
  --mesa-build "$MESA_ROOT/mesa-build" \
  --drm-include "$MESA_ROOT/aarch64-root/include" \
  --library-directory "$MESA_ROOT/aarch64-root/lib" \
  --case image-publication --timeout 7200
```

## Local checks

```sh
PYTHONDONTWRITEBYTECODE=1 python3 -B scripts/test_compute_image_publication.py
PYTHONDONTWRITEBYTECODE=1 python3 -B scripts/test_qualify_compute_owner.py -k publication
git diff --check
```

## Limits

- Count stays **10/40** until the full P11 matrix closes.
- Ordinary L1 cached-alias of this GEM remains open; image data already bypasses
  L1 on the uncached path witnessed here.
- Seed component corpus in [GALLIUM_IMAGE_ORDERING.md](GALLIUM_IMAGE_ORDERING.md)
  / `scripts/test_gallium_image_ordering.py` is not a complete-owner substitute
  for the remaining domains.
- Texture/attachment/DMA/counter/shared publication, P12 full resources, and
  product clock/FPS keep their own gates.
