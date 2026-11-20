# P11 ordinary L1 cached-alias publication

Original RV32 cached-kernarg / checked-UC storage same-GEM publication corpus
and complete-owner continuous execution for P11's ordinary L1 cached-alias
closeout: ordinary cached vector loads warm both CU private L1s and shared L2
on the retained GEM line, the checked UC storage producer invalidates the
cached aliases (L2 drop, both-CU L1 invalidations and ACKs) before writing the
published data/flag words, and the subsequent ordinary cached reload command
misses stale aliases, refetches from lower memory, and refills both CU L1s and
L2 on one assertion-enabled float DUT, same RAM, one reset.

Together with the seven domain publications ([SSBO](P11_STORAGE_PUBLICATION.md),
[image](P11_IMAGE_PUBLICATION.md), [texture](P11_TEXTURE_PUBLICATION.md),
[counter](P11_COUNTER_PUBLICATION.md), [shared](P11_SHARED_PUBLICATION.md),
[attachment](P11_ATTACHMENT_PUBLICATION.md), [DMA](P11_DMA_PUBLICATION.md)),
this closes **P11** (**11/40**, remaining **29**).

## Cached-alias coherence contract

Each command binds the same 8192-byte GEM through an explicit cached kernarg
mapping (`0x80800000`, `cache_policy=0`) and a checked uncached `GSB1` storage
descriptor view (`0xcfffc000`, `cache_policy=2`).

- **Warm (`l1-publication-warm`)**: all four workgroups execute ordinary cached
  vector loads (`vle32.v`) of `DATA_OFFSET=4` and `FLAG_OFFSET=68` into their
  per-group warm slots (`WARM_OFFSET + 8*group`), warming both CU L1 caches
  (`warmL1Mask=3`) and shared L2 (`warmL2=true`) with initial `0 / 0`.
- **Producer (`l1-publication-1`)**: publishes `data=7` (`DATA_OFFSET=4`),
  executes `fence rw,rw`, and publishes `flag=1` (`FLAG_OFFSET=68`) through the
  checked UC storage view while recording old words in `OUTPUT_OFFSET`. Before
  the first target lower write (`firstTargetWriteCycle`), the Verilator cache
  observer requires L2 drop (`l2DropCycle`), L1 invalidations on both CUs
  (`invalidateMask=3`), and completed invalidation acknowledgements
  (`invalidateAckMask=3`).
- **Reload (`l1-publication-reload`)**: all four workgroups re-read
  `DATA_OFFSET=4` and `FLAG_OFFSET=68` through the ordinary cached kernarg
  mapping into `REFETCH_OFFSET + 8*group`. The observer requires the reload
  lower read (`reloadReadRequestCycle`) to see no stale L1/L2 residency, followed
  by lower read response (`reloadReadResponseCycle`) and two-CU L1/L2 refill
  (`refilledL1Mask=3`, `refilledL2=true`, `refillCycle`).

## Corpus

| Case | Role | View path | Expected data/flag |
| --- | --- | --- | --- |
| `l1-publication-warm` | warm | ordinary cached `vle32.v` (`0x80800000`) | 0 / 0 |
| `l1-publication-1` | producer | checked UC storage `GSB1` (`0xcfffc000`) | 7 / 1 |
| `l1-publication-reload` | reload | ordinary cached `vle32.v` (`0x80800000`) | 7 / 1 |

All three phases share physical base `0x01000000` and retained 8192-byte GEM
extents (`0x01002000..0x01004000`).

Qualify entry: `scripts/qualify_compute_owner.py --case l1-publication`
(requires the Verilator complete-float executor).

## Actual complete float DUT execution

Execution source `6a0e9039c33b9ab70cd11975b3e509f7677af8c0` on the
assertion-enabled `two-cu-vga60-owner-v2-image-float-stage` Verilator compute
executor. All three phases passed independent returned-byte/guard oracles,
cleanup ACK, one DUT/RAM/single reset, two-CU L1/L2 warm → L2 drop → L1
invalidate/ACK → producer write → ordinary reload miss/refetch/refill
observation, and unchanged source/model postchecks:

```text
original L1 publication phase=0 role=warm full bytes/cleanup PASS
original L1 publication phase=1 role=producer full bytes/cleanup PASS
original L1 publication phase=2 role=reload full bytes/cleanup PASS
all three original L1 publication phases PASS; one DUT/RAM/reset, warm cached aliases invalidated before producer, ordinary cached reload refilled, original source/model postchecks
original RV32 compute l1-publication: actual full-DUT returned bytes and context cleanup PASS
```

Reproduce against a prepared float owner (see
[COMPLETE_OWNER_COMPUTE_EXECUTOR.md](COMPLETE_OWNER_COMPUTE_EXECUTOR.md)):

```sh
OWNER_OUTPUT=$PWD/.artifacts/p11-float-owner
PYTHONDONTWRITEBYTECODE=1 python3 -B scripts/qualify_compute_owner.py \
  --executor "$OWNER_OUTPUT/executors/compute/executor" \
  --executor-qualification "$OWNER_OUTPUT/executors/compute/qualification.json" \
  --executor-checker scripts/prepare_owner_executor.py \
  --executor-checker-python "$(command -v python3)" \
  --compiler-image sha256:cc860b33283c55bb906f73dbe48621b6dbb4dcce6d65098f7f29550e49459d72 \
  --case l1-publication --timeout 7200
```

## Local checks

```sh
PYTHONDONTWRITEBYTECODE=1 python3 -B scripts/test_compute_l1_publication.py
PYTHONDONTWRITEBYTECODE=1 python3 -B scripts/test_qualify_compute_owner.py -k publication
git diff --check
```

## Limits

- Closes P11 (**11/40**); next serial target is P12 full compute resource
  coexistence.
- P12 full resources, P13 compute ESSL/ISA/driver semantics, and product
  clock/FPS keep their own gates.
