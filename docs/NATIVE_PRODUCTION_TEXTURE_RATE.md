# Production native texture rate

The explicit local `QualifyNativeHostTextureRate` qualification passes one
hardware case using unchanged SV copied from the emitted four-engine host.
The vertex and fragment endpoint closures contain 101 definitions each; the
shared pool contains 104, including sixteen pinned 32-bit SRAMs and twelve
pinned 64-bit SRAMs. Their union is 178 SV files. Endpoint uniform lookup remains enabled in RTL;
all five pool clients and the 128-row shared windows remain configured.

Two native endpoints target opposite CU pairs. The other three pool clients
remain inactive through their public inputs. Each active endpoint issues 32
sixteen-lane vector sampling instructions across eight warp contexts after
warming its own four-color RGBA8 image. An independent FP32 filter oracle
checks all 1,024 returned lane words, covering all four component selectors.

| Measured property | Result |
|---|---|
| Native vector results / four-tap samples | 64 / 1,024 |
| Simultaneous steady acceptance and replies | Two samples every clock for 448 clocks, 896 samples |
| Group request-to-reply latency | 93 clocks on both clients |
| Warm descriptor, texel and page-walk reads | Zero |
| Ordering and drain | Private request/reply tag order, all vector commits and pending memory replies drained |
| Measured phase including startup/drain | 613 clocks |

The test wrapper only wires existing production ports and observes existing
handshake/tag wires. It does not add DUT observation ports, replace internal
logic or project away counters. Source, emitted RTL, wrapper and vendor SRAM
hashes are checked before simulation. Keep the source and prepared model frozen
until the run finishes; regenerate the emission after production/build changes.

This is an endpoint/service rate gate. CU cores and whole-frame instruction
issue are outside this harness. The selected workload does not consume uniform
blocks. Cold/translated traffic, other sampling patterns, render-to-texture and
actual CU binding programs have separate qualifications. The complete component synthesis screens now pass 1,000 ps including I/O;
see [H4 acceptance](MOBILE_GPU_MILESTONE_ACCEPTANCE.md#h4-acceptance-2026-10-30)
for the combined exit evidence. A whole-host clock and six actual 720p workloads
at 30/60 FPS remain product requirements. This endpoint result alone establishes
neither frame rate nor the full alignment goal.

Run explicitly outside PR/nightly lanes, from the repository root:

```sh
git submodule update --init depends/asap7_sram_0p0
python3 scripts/prepare_native_host_texture_rate.py emit .artifacts/native-rate-emission
python3 scripts/prepare_native_host_texture_rate.py prepare \
  .artifacts/native-rate-emission/emission.json .artifacts/native-rate-model
sbt -J-Xmx5G -J-XX:ActiveProcessorCount=2 -Dopengpu.productQualification=0 \
  -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  -Dopengpu.simSeparateBuild=1 -Dopengpu.simReleaseHeap=1 \
  -Dopengpu.nativeHostTextureModel="$PWD/.artifacts/native-rate-model" \
  'Test / runMain opengpu.graphics.QualifyNativeHostTextureRate --reports .artifacts/native-rate-reports'
```

Use fresh output directories. Host emission requests a 10 GiB JVM heap; the
functional run requests 5 GiB plus native build headroom. Admit each separately
against other live jobs. Both use the development extent, without the product
qualification flag. The entry point requires exactly one completed case and
rejects failed, skipped, missing or incomplete results. Raw RTL, manifests,
logs and reports stay in `.artifacts/` and are not committed.
