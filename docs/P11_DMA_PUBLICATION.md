# P11 DMA/host-visibility publication

Original Mesa SSBO→host-visible GEM publication corpus and complete-owner
continuous execution for P11's DMA/host-visibility domain: GPU producer writes
uncached SSBO words that remain host-visible in retained DUT/GEM physical bytes
on one assertion-enabled float DUT, same RAM, one reset, with Verilator
observation of warm AR → producer AW → reload AR on the retained data line.

This closes the DMA/host-visibility float DUT gate and its uncached publication
fabric witness. It does **not** close P11 by itself. Ordinary L1 cached-alias
consumers remain open until P11 closeout. Count remains **10/40** until the full
P11 matrix closes.

## Host-visibility contract

GPU publishes the same data/flag pattern as SSBO storage publication
(`data.v[1]=7`, `memoryBarrierBuffer()`, `data.v[17]=1`). Warm is a GPU read of
zeros. After the producer fence and independent cleanup authorization, the
complete-owner path proves **host visibility** by reading the retained physical
GEM bytes from DUT memory (`hostVisibleData=7`, `hostVisibleFlag=1` in
`publication.cache-observation.json`, plus the controller's independent DUT
memory oracle). The reload GPU consumer models a host-mapped view of that same
GEM and supplies fabric reload AR; it is not the authority for host visibility.

## Corpus

| Case | Role | Source | Expected data/flag |
| --- | --- | ---: | --- |
| `dma-publication-warm` | warm | `warm.comp` | 0 / 0 |
| `dma-publication-1` | producer | `publication-1.comp` | 7 / 1 |
| `dma-publication-reload` | reload (host-mapped model) | `reload.comp` | 7 / 1 |

All three phases share physical base `0x01000000`. Retained data extents keep
measured DUT bytes across Mesa relaunches; other exact physical overlaps are
replaced from the new upload.

Checked storage word accesses bypass private L1/L2. The continuous session
records warm AR → producer AW → reload AR on the retained data line through the
Verilator cache observer (`publication.cache-target` /
`publication.cache-observation.json`), with `hostVisibleAfterProducer=true`.

Qualify entry: `scripts/qualify_compute_owner.py --case dma-publication`
(requires the Verilator complete-float executor).

## Actual complete float DUT execution

Pending: record the execution SHA after DUT PASS on the assertion-enabled
`two-cu-vga60-owner-v2-image-float-stage` Verilator compute executor.

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
  --case dma-publication --timeout 7200
```

## Local checks

```sh
PYTHONDONTWRITEBYTECODE=1 python3 -B scripts/test_compute_dma_publication.py
PYTHONDONTWRITEBYTECODE=1 python3 -B scripts/test_qualify_compute_owner.py -k publication
git diff --check
```

## Limits

- Count stays **10/40** until the full P11 matrix closes.
- Ordinary L1 cached-alias of this GEM remains open.
- Component evidence in [GALLIUM_COMPUTE_MEMORY_BARRIERS.md](GALLIUM_COMPUTE_MEMORY_BARRIERS.md)
  is not a complete-owner substitute for remaining P11 gaps.
- Attachment publication, P12 full resources, and product clock/FPS keep their
  own gates.
