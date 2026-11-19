# P11 SSBO publication source stage

This stage delivers the original Mesa SSBO publication corpus and complete-owner
continuous wiring for P11's first domain: GPU producer → warmed/reload consumer
on one assertion-enabled float DUT, same RAM, one reset.

It does **not** close P11. Actual float DUT execution, ordinary L1 cached-alias
observation, and the remaining domains (image/texture/attachment/DMA/counter/
shared) remain open.

## Corpus

| Case | Role | Source | Expected data/flag |
| --- | --- | ---: | --- |
| `storage-publication-warm` | warm | `warm.comp` | 0 / 0 |
| `storage-publication-1` | producer | `publication-1.comp` | 7 / 1 |
| `storage-publication-reload` | reload | `reload.comp` | 7 / 1 |

Producer writes `data.v[1]=7`, executes `memoryBarrierBuffer()`, then
`data.v[17]=1`. Warm and reload consumers copy those words into an output
window. All three phases share physical base `0x01000000` and retain the data
GEM through `append_retained`.

Qualify entry: `scripts/qualify_compute_owner.py --case storage-publication`.

## Local checks

```sh
PYTHONDONTWRITEBYTECODE=1 python3 -B scripts/test_compute_storage_publication.py
PYTHONDONTWRITEBYTECODE=1 python3 -B scripts/test_qualify_compute_owner.py -k publication
git diff --check
```

CPU Mesa capture of the three sources still requires the pinned Mesa/DRM host
environment used by other complete-owner producers. Continuous same-GEM Linux
lease across the three frontend launches and the actual float DUT matrix remain
the next P11 SSBO gates.

## Limits

- Count stays **10/40** until the full P11 matrix closes.
- Component evidence in [GALLIUM_COMPUTE_MEMORY_BARRIERS.md](GALLIUM_COMPUTE_MEMORY_BARRIERS.md)
  is not a complete-owner substitute.
- P12 full resources and product clock/FPS keep their own gates.
