# Eight-port typed attachment SRAM

The wider H3 memory configuration has eight 64-bit request/reply ports and
sixteen single-port banks in a 32 KiB tile slot. It can service four old-value
reads and four full-word writebacks per clock for independent resident packets.
Two color words and two D24S8 words represent four complete sample records.
The native packet scheduler and arithmetic integration remain separate work;
this memory result does not qualify complete sample updates or renderer FPS.

Enable `xorBanks=true` with `ports=8,banks=16`. The hash retains the logical
word address's least significant bit, XORs row digits into the upper bank bits,
and retains the physical row. This is a permutation within each row, so it
preserves every canonical word address and aligned pair. It spreads aligned
color/depth planes that otherwise select the same low-bit banks. Bank conflicts
still serialize fairly; partial writes retain their two-clock atomic merge.
The existing default four-port/eight-bank configuration is preserved.

Eight executed tests pass with zero skipped or pending tests, including both
behavioral and vendor SRAM models. Each model sustains 128 consecutive mixed
issue/reply clocks with four reads and four full writes per clock. Tests also
write and read all 4,096 words, verify same-word masked updates and fairness,
and exercise independent reply stalls, held payloads, zero masks and faults.
The suite is included in the required PR gate.

Complete mapped synthesis at 1,000 ps with 25% I/O delay reports +245.415 ps core
and +189.222 ps I/O setup slack, with zero skipped or unresolved mappings.
The source selected for the PR emits byte-identical RTL to this qualification.
This is a component synthesis setup screen with sixteen 256x64 SRAM macros,
not a whole-GPU clock or physical implementation claim.

```sh
sbt -J-Xmx6G -Dopengpu.simJobs=1 'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.BankedTypedTileMemorySpec'
sbt 'runMain opengpu.elaboration.EmitPpaRtl mobile-wide-typed-memory .artifacts/wide-typed-memory-rtl'
python scripts/qualify_ppa.py .artifacts/wide-typed-memory-rtl BankedTypedTileMemory \
  --stage synthesis --period-ps 1000 --io-delay 0.25 \
  --output .artifacts/wide-typed-memory-synthesis
```
