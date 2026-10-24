# Paired sample SRAM service

The optional `samplePairBanks` mapping supplies eight 64-bit ports over sixteen
single-port banks in the existing 32 KiB canonical tile array. Four old-word
reads can overlap four complete writebacks. It requires `xorBanks=true` and
sixteen banks; existing callers retain their original mapping.

The physical bank is `b0=a0 XOR a7`, `b1=a1`, `b2=a2 XOR a9`,
`b3=a3 XOR a8`, with row `address >> 4`. This is a permutation within each
row and separates aligned adjacent words. Three independent row folds spread
tightly packed 16x16 RGBA8/D24S8 planes at 1x, 2x and 4x MSAA. Padded layouts
and other pipeline ages can still conflict; callers must measure their streams.

All ten SRAM regressions pass with zero skipped or pending cases. Both behavioral
and vendor models check every one of the 4,096 words, legacy masked operations,
fair arbitration, held replies and 256 consecutive clocks of four reads plus
four full-word writes. Complete mapped synthesis passes at 1,000 ps with 25% I/O
delay: +200.573 ps core/+196.011 ps I/O slack, zero skipped or unresolved mappings.
The PR emission is byte-identical to that qualified RTL.

This memory service does not qualify complete pixel-engine timing, native frame
throughput or 720p/30 FPS. Pixel arithmetic, ownership and renderer integration
are separate requirements.

```sh
sbt -J-Xmx6G -Dopengpu.simJobs=1 'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.BankedTypedTileMemorySpec'
sbt 'runMain opengpu.elaboration.EmitPpaRtl mobile-paired-sample-memory .artifacts/paired-memory-rtl'
python scripts/qualify_ppa.py .artifacts/paired-memory-rtl BankedTypedTileMemory \
  --stage synthesis --period-ps 1000 --io-delay 0.25 \
  --output .artifacts/paired-memory-synthesis
```
