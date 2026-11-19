# P11 buffer-texture publication

Original Mesa buffer-texture publication corpus and complete-owner continuous
execution for P11's texture domain: GPU image producer → warmed/reload
`usamplerBuffer`/`texelFetch` consumer on one assertion-enabled float DUT, same
RAM, one reset, with Verilator observation of the uncached texture fabric
traffic on the retained data line.

This closes the buffer-texture float DUT gate and its uncached publication
fabric witness. It does **not** close P11. Ordinary L1 cached-alias consumers,
and the remaining attachment/DMA/counter/shared domains, stay open. Count
remains **10/40**.

## Corpus

| Case | Role | Source | Expected data/flag |
| --- | --- | ---: | --- |
| `texture-publication-warm` | warm | `warm.comp` | 0 / 0 |
| `texture-publication-1` | producer | `publication-1.comp` | 7 / 1 |
| `texture-publication-reload` | reload | `reload.comp` | 7 / 1 |

Producer `imageStore(image0,1,uvec4(7))`, executes `memoryBarrierImage()`, then
`imageStore(image0,17,uvec4(1))`. Warm and reload consumers `texelFetch` those
texels through `usamplerBuffer` into an SSBO output window. All three phases
share physical base `0x01000000`. Retained texture extents keep measured DUT
bytes across Mesa relaunches; other exact physical overlaps are replaced from
the new upload.

Checked buffer-texture accesses bypass private L1/L2 (see
[GALLIUM_IMAGE_ORDERING.md](GALLIUM_IMAGE_ORDERING.md)). The continuous session
therefore records warm AR → producer AW → reload AR on the retained data
line through the Verilator cache observer (`publication.cache-target` /
`publication.cache-observation.json`).

Qualify entry: `scripts/qualify_compute_owner.py --case texture-publication`
(requires the Verilator complete-float executor).

## Actual complete float DUT execution

Execution source `d5734b4b3010c45547eecb7e490588ebe5ea4f58` on the assertion-enabled
`two-cu-vga60-owner-v2-image-float-stage` Verilator compute executor.
All three phases passed independent returned-byte/guard oracles, cleanup ACK,
one DUT/RAM/single reset, uncached fabric observation, and unchanged
source/model postchecks:

```text
original texture publication phase=0 role=warm full bytes/cleanup PASS
original texture publication phase=1 role=producer full bytes/cleanup PASS
original texture publication phase=2 role=reload full bytes/cleanup PASS
all three original texture publication phases PASS; one DUT/RAM/reset,
warm/producer/reload, uncached fabric observation, original source/model postchecks
original Mesa compute texture-publication: actual full-DUT returned bytes
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
  --case texture-publication --timeout 7200
```

## Local checks

```sh
PYTHONDONTWRITEBYTECODE=1 python3 -B scripts/test_compute_texture_publication.py
PYTHONDONTWRITEBYTECODE=1 python3 -B scripts/test_qualify_compute_owner.py -k publication
git diff --check
```

## Limits

- Count stays **10/40** until the full P11 matrix closes.
- Ordinary L1 cached-alias of this GEM remains open; texture data already bypasses
  L1 on the uncached path witnessed here.
- Seed component corpus in [GALLIUM_IMAGE_ORDERING.md](GALLIUM_IMAGE_ORDERING.md)
  / `scripts/test_gallium_image_ordering.py` is not a complete-owner substitute
  for the remaining domains.
- Attachment/DMA/counter/shared publication, P12 full resources, and product
  clock/FPS keep their own gates.
