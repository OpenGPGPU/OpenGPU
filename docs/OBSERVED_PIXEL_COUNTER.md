# Observed pixel counter

`ObservedCarrySaveAccumulator` accepts a full-width unsigned addend every
clock and retains the exact modular sum with bitwise feedback. Registered
32-bit partial sums expose the accumulated value one clock after its update.
Consumers must allow a zero-addend observation clock after the last event
before taking a final snapshot. Clear overrides the addend and clears both
feedback and observation state on the same edge.

Five tests cover widths 1, 33, 99, 110 and 128, including long carries,
overflow, random increments, zero-addend drain and repeated clear priority.
Fresh current-main RTL matches the qualified 110-bit component. Synthesis
passes at 1,000 ps with 25% I/O delay: core slack +331.250 ps, I/O slack
+300.986 ps, zero skipped or unresolved mappings. This component result does
not qualify a complete pixel backend or establish a product frame rate.

```sh
sbt 'testOnly opengpu.util.ObservedCarrySaveAccumulatorSpec'
sbt 'runMain opengpu.elaboration.EmitPpaRtl mobile-observed-pixel-counter .artifacts/observed-counter-rtl'
CHIPAGENT_DIR=../chipagent ../chipagent/.venv/bin/python scripts/qualify_ppa.py \
  .artifacts/observed-counter-rtl ObservedCarrySaveAccumulator \
  --stage synthesis --period-ps 1000 --io-delay 0.25 --timeout 1800 \
  --output .artifacts/observed-counter-synthesis
```
