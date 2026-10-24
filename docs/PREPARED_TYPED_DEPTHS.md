# Streaming sample-depth preparation

`PreparedTypedDepths` accepts four FP32 depths per clock and returns their
attachment encodings three clocks later. Registered stages form an exact
shift/subtract product, align retained/guard/sticky bits, and round once to
D16/D24 precision. D32F preserves the existing clamp and signed-zero behavior.
Formats and tags travel with each vector. Consumers must capture every valid
output; an isolated result remains held until the next valid vector. There is
no downstream stall or cancellation interface.

Two numeric tests cover 73,788 densely streamed depths, all 65,536 D16 round
trips, rounding boundaries, special representations, changing formats, input
gaps and poisoned invalid inputs. They check three-clock latency, one vector
per clock and retained tags/results. The block is a preparation primitive for
parallel sample lanes; integrating those lanes and qualifying complete pixel
throughput remain separate work.

Standalone synthesis passes at 1,000 ps with 25% I/O budgets, ASAP7 TC/SLVT:
+136.739 ps core/+717.811 ps I/O setup margin. The constant-D24 wrapper passes
at +418.351/+718.232 ps. Both screens have zero skipped/unresolved mappings.
Fresh main-based emissions preserve all six generic and seven specialized RTL
files after removing comments, including verification logic.
These are component qualifications and establish no renderer FPS or GPU clock.

```sh
sbt -J-Xmx4G -Dopengpu.simJobs=1 'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.PreparedTypedDepthsSpec'
sbt 'runMain opengpu.elaboration.EmitPpaRtl mobile-prepared-typed-depths .artifacts/prepared-depth-rtl' \
  'runMain opengpu.elaboration.EmitPpaRtl mobile-prepared-d24-depths .artifacts/prepared-d24-rtl'
python scripts/qualify_ppa.py .artifacts/prepared-depth-rtl PreparedTypedDepths \
  --stage synthesis --period-ps 1000 --io-delay 0.25 \
  --output .artifacts/prepared-depth-synthesis
python scripts/qualify_ppa.py .artifacts/prepared-d24-rtl PreparedD24Depths \
  --stage synthesis --period-ps 1000 --io-delay 0.25 \
  --output .artifacts/prepared-d24-synthesis
```
