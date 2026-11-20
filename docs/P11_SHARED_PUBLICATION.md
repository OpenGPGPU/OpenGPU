# P11 shared publication

Original Mesa shared→SSBO publication corpus and complete-owner continuous
execution for P11's shared domain: GPU producer stages words through workgroup
shared scratch, then publishes to an original SSBO that warmed/reload consumers
observe on one assertion-enabled float DUT, same RAM, one reset, with Verilator
observation of the uncached SSBO fabric traffic on the retained data line.

Shared SRAM remains private to the producer workgroup. Publication lands in the
original SSBO whose word path bypasses private L1/L2. This closes the shared
float DUT gate and its publication fabric witness. It does **not** close P11.
Ordinary L1 cached-alias consumers, and the remaining attachment/DMA domains,
stay open. Count remains **10/40**.

## Corpus

| Case | Role | Source | Expected data/flag | Local | Shared bytes |
| --- | --- | ---: | --- | ---: | ---: |
| `shared-publication-warm` | warm | `warm.comp` | 0 / 0 | 1 | 0 |
| `shared-publication-1` | producer | `publication-1.comp` | 7 / 1 | 16 | 128 |
| `shared-publication-reload` | reload | `reload.comp` | 7 / 1 | 1 | 0 |

Producer uses one 16-lane warp and a 32-word shared scratch (two own-stripe
spans). The emitter CF path requires shared atomics, and `EXPR_PEER` only admits
`local_size=128` with `id^16`, so this float DUT corpus is branch-free: immediate
`scratch[id]` / `scratch[16u+id]` fills, `memoryBarrierShared();barrier()`, then
every lane publishes the same own-stripe loads through `memoryBarrierBuffer()`
into `data.v[1]` / `data.v[17]`. Warm and reload consumers read the SSBO words
into an output window. All three phases share physical base `0x01000000`.
Retained SSBO extents keep measured DUT bytes across Mesa relaunches; other
exact physical overlaps are replaced from the new upload.

The continuous session records warm AR → producer AW → reload AR on the retained
data line through the Verilator cache observer (`publication.cache-target` /
`publication.cache-observation.json`). Full workgroup shared publication remains
the separate component contract in
[GALLIUM_SHARED_PUBLICATION.md](GALLIUM_SHARED_PUBLICATION.md).

Qualify entry: `scripts/qualify_compute_owner.py --case shared-publication`
(requires the Verilator complete-float executor).

## Actual complete float DUT execution

Execution source `4122c13f43d39eb14b699f868b46209216243f8f` on the assertion-enabled
`two-cu-vga60-owner-v2-image-float-stage` Verilator compute executor.
All three phases passed independent returned-byte/guard oracles, cleanup ACK,
one DUT/RAM/single reset, uncached fabric observation, and unchanged
source/model postchecks:

```text
original shared publication phase=0 role=warm full bytes/cleanup PASS
original shared publication phase=1 role=producer full bytes/cleanup PASS
original shared publication phase=2 role=reload full bytes/cleanup PASS
all three original shared publication phases PASS; one DUT/RAM/reset,
warm/producer/reload, uncached fabric observation, original source/model postchecks
original Mesa compute shared-publication: actual full-DUT returned bytes
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
  --case shared-publication --timeout 7200
```

## Local checks

```sh
PYTHONDONTWRITEBYTECODE=1 python3 -B scripts/test_compute_shared_publication.py
PYTHONDONTWRITEBYTECODE=1 python3 -B scripts/test_qualify_compute_owner.py -k publication
git diff --check
```

## Limits

- Count stays **10/40** until the full P11 matrix closes.
- Ordinary L1 cached-alias of the published GEM remains open; the witnessed path
  here is the uncached SSBO publication of shared-derived words.
- Seed component corpus in
  [GALLIUM_SHARED_PUBLICATION.md](GALLIUM_SHARED_PUBLICATION.md) /
  `scripts/test_gallium_shared_publication.py` is not a complete-owner substitute
  for the remaining domains.
- Attachment/DMA publication, P12 full resources, and product clock/FPS keep
  their own gates.
