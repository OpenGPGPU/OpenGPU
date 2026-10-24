# Prepared pixel totals

`PreparedUnsignedSum` accepts unsigned operands every clock and produces their
full `width + 1` sum after two clock edges. The first stage captures independent
short partial sums. The second captures their low bits and parallel carry
prefixes; only a short increment remains at the output. Gaps hold the latest
completed sum. Reset clears the result and discards in-flight captures.

Four tests cover widths 1, 9, 99 and 110: 3,378 exact completed sums, dense
admission, full-width carries, poisoned inputs during gaps and four captures
discarded by reset. The 110-bit component passes synthesis at 1,000 ps with
25% I/O delay: core slack +543.346 ps, I/O slack +585.879 ps, zero skipped or
unresolved mappings. This qualifies the component; pixel-owner integration,
whole-GPU timing and product frame rates remain separate requirements.

```sh
sbt 'testOnly opengpu.util.PreparedUnsignedSumSpec'
sbt 'runMain opengpu.elaboration.EmitPpaRtl mobile-prepared-pixel-total .artifacts/prepared-pixel-total-rtl'
CHIPAGENT_DIR=../chipagent ../chipagent/.venv/bin/python scripts/qualify_ppa.py \
  .artifacts/prepared-pixel-total-rtl PreparedUnsignedSum --stage synthesis \
  --period-ps 1000 --io-delay 0.25 --timeout 1800 \
  --output .artifacts/prepared-pixel-total-synthesis
```
