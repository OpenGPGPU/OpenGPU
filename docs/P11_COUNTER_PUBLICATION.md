# P11 atomic-counter publication

Original Mesa atomic-counter publication corpus and complete-owner continuous
execution for P11's counter domain: GPU producer → warmed/reload consumer on
one assertion-enabled float DUT, same RAM, one reset, with Verilator
observation of the uncached counter fabric traffic on the retained data line.

Counter binding zero owns a separate original GEM through
`COUNTER_SOURCE_RESOURCES`; it does not consume an SSBO window. This closes the
atomic-counter float DUT gate and its uncached publication fabric witness. It
does **not** close P11. Ordinary L1 cached-alias consumers, and the remaining
attachment/DMA/shared domains, stay open. Count remains **10/40**.

## Corpus

| Case | Role | Source | Expected data/flag |
| --- | --- | ---: | --- |
| `counter-publication-warm` | warm | `warm.comp` | 0 / 0 |
| `counter-publication-1` | producer | `publication-1.comp` | 7 / 1 |
| `counter-publication-reload` | reload | `reload.comp` | 7 / 1 |

Producer unrolls seven `atomicCounterIncrement(counters[1])` calls (control-flow
counter ops remain rejected), executes `memoryBarrierAtomicCounter()`, then
increments `counters[0]`. Warm and reload consumers `atomicCounter` those words
into an SSBO output window. All three phases share physical base `0x01000000`.
Retained counter extents keep measured DUT bytes across Mesa relaunches; other
exact physical overlaps are replaced from the new upload. Hardware counter
capacity remains eight words, so data and flag share one retained fabric line.

Counter values are not cached by the service (see
[GALLIUM_ATOMIC_COUNTERS.md](GALLIUM_ATOMIC_COUNTERS.md)). The continuous session
therefore records warm AR → producer AW → reload AR on the retained data
line through the Verilator cache observer (`publication.cache-target` /
`publication.cache-observation.json`).

Qualify entry: `scripts/qualify_compute_owner.py --case counter-publication`
(requires the Verilator complete-float executor).

## Actual complete float DUT execution

Execution source `be3d9446f457c1bea68b48a233b583aee6af84bc` on the assertion-enabled
`two-cu-vga60-owner-v2-image-float-stage` Verilator compute executor.
All three phases passed independent returned-byte/guard oracles, cleanup ACK,
one DUT/RAM/single reset, uncached fabric observation, and unchanged
source/model postchecks:

```text
original counter publication phase=0 role=warm full bytes/cleanup PASS
original counter publication phase=1 role=producer full bytes/cleanup PASS
original counter publication phase=2 role=reload full bytes/cleanup PASS
all three original counter publication phases PASS; one DUT/RAM/reset,
warm/producer/reload, uncached fabric observation, original source/model postchecks
original Mesa compute counter-publication: actual full-DUT returned bytes
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
  --case counter-publication --timeout 7200
```

## Local checks

```sh
PYTHONDONTWRITEBYTECODE=1 python3 -B scripts/test_compute_counter_publication.py
PYTHONDONTWRITEBYTECODE=1 python3 -B scripts/test_qualify_compute_owner.py -k publication
git diff --check
```

## Limits

- Count stays **10/40** until the full P11 matrix closes.
- Ordinary L1 cached-alias of this GEM remains open; counter data already bypasses
  L1 on the uncached path witnessed here.
- Seed component corpus in [GALLIUM_ATOMIC_COUNTERS.md](GALLIUM_ATOMIC_COUNTERS.md)
  / `scripts/test_gallium_atomic_counter.py` and the P07 per-case reset live owner
  in [ATOMIC_COUNTER_LIVE_OWNER.md](ATOMIC_COUNTER_LIVE_OWNER.md) are not
  complete-owner substitutes for the remaining domains.
- Attachment/DMA publication, P12 full resources, and product clock/FPS keep
  their own gates.
