# P11 SSBO publication

Original Mesa SSBO publication corpus and complete-owner continuous execution
for P11's first domain: GPU producer → warmed/reload consumer on one
assertion-enabled float DUT, same RAM, one reset.

This closes the SSBO float DUT gate. It does **not** close P11. Ordinary L1
cached-alias observation and the remaining domains (image/texture/attachment/
DMA/counter/shared) remain open.

## Corpus

| Case | Role | Source | Expected data/flag |
| --- | --- | ---: | --- |
| `storage-publication-warm` | warm | `warm.comp` | 0 / 0 |
| `storage-publication-1` | producer | `publication-1.comp` | 7 / 1 |
| `storage-publication-reload` | reload | `reload.comp` | 7 / 1 |

Producer writes `data.v[1]=7`, executes `memoryBarrierBuffer()`, then
`data.v[17]=1`. Warm and reload consumers copy those words into an output
window. All three phases share physical base `0x01000000`. Retained data
extents keep measured DUT bytes across Mesa relaunches; other exact physical
overlaps are replaced from the new upload.

Qualify entry: `scripts/qualify_compute_owner.py --case storage-publication`.

## Actual complete float DUT execution

Execution source `dcd2a543b0f70550edccdc9f634ad31bfb1fa8a9` on the assertion-
enabled `two-cu-vga60-owner-v2-image-float-stage` Verilator compute executor.
All three phases passed independent returned-byte/guard oracles, cleanup ACK,
one DUT/RAM/single reset, and unchanged source/model postchecks:

```text
original storage publication phase=0 role=warm full bytes/cleanup PASS
original storage publication phase=1 role=producer full bytes/cleanup PASS
original storage publication phase=2 role=reload full bytes/cleanup PASS
all three original storage publication phases PASS; one DUT/RAM/reset,
warm/producer/reload, original source/model postchecks
original Mesa compute storage-publication: actual full-DUT returned bytes
and context cleanup PASS
```

Reproduce against a prepared float owner (see
[COMPLETE_OWNER_COMPUTE_EXECUTOR.md](COMPLETE_OWNER_COMPUTE_EXECUTOR.md)):

```sh
OWNER_OUTPUT=$PWD/.artifacts/p11-float-owner
PYTHONDONTWRITEBYTECODE=1 /usr/bin/python3 -B scripts/qualify_compute_owner.py \
  --executor "$OWNER_OUTPUT/executors/compute/executor" \
  --executor-qualification "$OWNER_OUTPUT/executors/compute/qualification.json" \
  --executor-checker scripts/prepare_owner_executor.py \
  --executor-checker-python /usr/bin/python3 \
  --compiler-image sha256:cc860b33283c55bb906f73dbe48621b6dbb4dcce6d65098f7f29550e49459d72 \
  --case storage-publication --timeout 7200
```

## Local checks

```sh
PYTHONDONTWRITEBYTECODE=1 python3 -B scripts/test_compute_storage_publication.py
PYTHONDONTWRITEBYTECODE=1 python3 -B scripts/test_qualify_compute_owner.py -k publication
git diff --check
```

## Limits

- Count stays **10/40** until the full P11 matrix closes.
- Ordinary L1 cached-alias observation of this publication path remains open.
- Component evidence in [GALLIUM_COMPUTE_MEMORY_BARRIERS.md](GALLIUM_COMPUTE_MEMORY_BARRIERS.md)
  is not a complete-owner substitute for the remaining domains.
- P12 full resources and product clock/FPS keep their own gates.
