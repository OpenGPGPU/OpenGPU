# Native typed sample packets

`TypedSamplePacketExpander` converts an eligible RGBA8+D24S8 quad into packets
of four sample records. Each packet names two color words and two
depth/stencil words, each containing adjacent sample records. At 1× it groups
four pixels, at 2× two pixels, and at 4× one pixel. The stream emits one packet per clock after
startup; a quad produces one, two or four packets respectively.

The caller performs ordinary descriptor/quad validation and routes other legal
formats through its general backend. The subset selector requires 8×8 or
16×16 tiles, even origins, aligned attachment words and one RGBA8 target with
D24S8. Packets retain masks, front/back state, blend state, source colors and
effective per-sample depth through stalls. Cancellation stops new quad
admissions while every accepted quad finishes packetization. Already offered
packets remain immutable; rebind waits for the complete stream to drain.

Four tests pass, including a dense 768-packet/3,072-sample stream at 1×/2×/4×,
sparse masks, signed coordinate limits, held replies, unsupported layouts,
sticky cancellation and healthy rebind. All ten current-main RTL files match
the qualified snapshot. Complete component synthesis passes at 1,000 ps with
25% I/O delay: core slack +424.479 ps, I/O slack +426.671 ps, zero skipped or
unresolved mappings. This transport result does not qualify a complete pixel
backend, whole-GPU clock or product frame rate.

```sh
sbt 'testOnly opengpu.graphics.TypedSamplePacketExpanderSpec'
sbt 'runMain opengpu.elaboration.EmitPpaRtl mobile-typed-sample-packets .artifacts/typed-sample-packets-rtl'
CHIPAGENT_DIR=../chipagent ../chipagent/.venv/bin/python scripts/qualify_ppa.py \
  .artifacts/typed-sample-packets-rtl TypedSamplePacketExpander \
  --stage synthesis --period-ps 1000 --io-delay 0.25 --timeout 1800 \
  --output .artifacts/typed-sample-packets-synthesis
```
