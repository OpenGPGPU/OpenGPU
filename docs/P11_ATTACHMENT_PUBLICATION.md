# P11 color-attachment publication

Original Mesa framebuffer-blit color-attachment publication corpus and
complete-owner continuous execution for P11's attachment domain: GPU producer
writes a retained RGBA8 color render-target GEM that warmed/reload consumers
blit-read on one assertion-enabled float DUT, same RAM, one reset, with fabric
observation of AR → AW → AR on the retained attachment line.

This uses the float `framebuffer-blit-sample` executor
(`two-cu-vga60-owner-v2-image-float-stage`). Destination GEMs are
`PIPE_BIND_RENDER_TARGET` color attachments. Blit packets remain the private
checked-storage GPU pass (see [NATIVE_FRAMEBUFFER_BLIT_SAMPLE_OWNER.md](NATIVE_FRAMEBUFFER_BLIT_SAMPLE_OWNER.md));
the native tile-store draw path in
[NATIVE_RENDER_TEXTURE_VISIBILITY.md](NATIVE_RENDER_TEXTURE_VISIBILITY.md) stays
related component evidence. This closes the attachment float DUT gate and its
publication fabric witness. It does **not** close P11. Ordinary L1 cached-alias
consumers, and the remaining DMA domain, stay open. Count remains **10/40**.

## Corpus

| Case | Role | Packet | Expected data/flag |
| --- | --- | --- | ---: |
| `attachment-publication-warm` | warm | `same-gem` consumer reading zero attachment | 0 / 0 |
| `attachment-publication-1` | producer | `copy-region-0` into retained attachment | published RGBA words |
| `attachment-publication-reload` | reload | `same-gem` consumer reading published attachment | published RGBA words |

Producer copies distinctive RGBA8 texels into the retained color attachment at
physical base `0x01000000` (handle shared with warm/reload sources). Warm and
reload blit that attachment into a scratch destination. Retained attachment
extents keep measured DUT bytes across phase uploads; other exact physical
overlaps are replaced from the new upload.

The continuous session records warm AR → producer AW → reload AR on the
retained attachment line (`publication.cache-target` /
`publication.cache-observation.json`).

Qualify entry: `scripts/qualify_attachment_publication.py`
(requires the Verilator float `framebuffer-blit-sample` executor).

## Actual complete float DUT execution

PLACEHOLDER — record execution source SHA and phase PASS lines after the first
successful float DUT run.

Reproduce against a prepared float owner (see
[COMPLETE_OWNER_COMPUTE_EXECUTOR.md](COMPLETE_OWNER_COMPUTE_EXECUTOR.md) and
[NATIVE_FRAMEBUFFER_BLIT_SAMPLE_OWNER.md](NATIVE_FRAMEBUFFER_BLIT_SAMPLE_OWNER.md)):

```sh
OWNER_OUTPUT=$PWD/.artifacts/p11-float-owner
CALLER_INPUTS=$PWD/.artifacts/blit-callers-*/inputs   # from Mesa blit capture
PYTHONDONTWRITEBYTECODE=1 /usr/bin/python3 -B scripts/qualify_attachment_publication.py \
  --executor "$OWNER_OUTPUT/executors/framebuffer-blit-sample/executor" \
  --executor-qualification "$OWNER_OUTPUT/executors/framebuffer-blit-sample/qualification.json" \
  --producer-template "$CALLER_INPUTS/copy-region-0.blit" \
  --consumer-template "$CALLER_INPUTS/same-gem.blit" \
  --timeout 7200
```

## Local checks

```sh
PYTHONDONTWRITEBYTECODE=1 python3 -B scripts/test_attachment_publication.py
git diff --check
```

## Limits

- Count stays **10/40** until the full P11 matrix closes.
- Blit uses the checked-storage GPU path into a color render-target GEM; native
  tile-attachment store/draw publication remains the stronger related witness in
  [NATIVE_RENDER_TEXTURE_VISIBILITY.md](NATIVE_RENDER_TEXTURE_VISIBILITY.md).
- Ordinary L1 cached-alias of the published attachment GEM remains open.
- DMA publication, P12 full resources, and product clock/FPS keep their own gates.
