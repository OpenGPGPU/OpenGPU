# Resident native sample updates

`ResidentTypedSamplePipeline` connects the native quad expander, 128-context
packet window and acknowledged sample worker to one 32 KiB tile SRAM. Eight
64-bit ports serve sixteen banks: four old-word reads and four full-word
writebacks can proceed together for conflict-free RGBA8+D24S8 packets.
Eight read buffers retain arithmetic inputs; lifetime contexts retain word
addresses and completion state until write acknowledgements arrive.

The enclosing renderer validates descriptors and owns clear/load, dirty state,
refill, export and native-quad completion aggregation. Other formats require
its generic path. Maintenance accesses the same array after complete drain.
Virtual old words can merge a clear with the update; read-only depth/stencil
tests materialize each virtual word once without counting a logical write.
Two-entry request queues register each maintenance port before bank arbitration.
Their accepted requests and held replies participate in complete drain before
the array returns to resident sample work.

Eight tests pass with zero skipped or pending cases. Behavioral and SRAM-macro
models each commit 768 packets (3,072 samples) in 823 clocks, including 768
consecutive packet retirements and exact final images. These fixtures reach
four reads plus four writes per clock without bank or context stalls. Native
1x/2x/4x expansion, padded layouts, overlapping words, channel masks, held
results, virtual initialization, cancellation, read faults and healthy reuse
also pass. Bank-conflicting layouts retain correctness but reduce throughput.
Sixteen queued maintenance operations retain their tags through eight held
replies before exact resident sample work resumes on the same array.

Complete subsystem synthesis passes at 1,000 ps with 25% I/O delay: core
slack +106.090 ps and I/O slack +147.779 ps, with zero skipped or unresolved
mappings. This includes the sample arithmetic, request queues and canonical
SRAM interface. Enclosing tile-policy and renderer timing remain separate gates.

Cancellation stops new effects while offered writes and accepted replies drain.
Held terminal results remain immutable, and only successful write replies
contribute write effects. A new cohort or maintenance mode requires complete
drain. These component results do not establish whole-renderer timing, frame
rates, arbitrary-format throughput or the complete mobile GPU resource budget.

```sh
git submodule update --init depends/asap7_sram_0p0
sbt -Dopengpu.simHierarchy=Rgba8BlendFmaEngine -Dopengpu.simLowMem=1 \
  -Dopengpu.simLowMemOpt=1 -Dopengpu.simJobs=1 \
  'testOnly opengpu.graphics.TaggedTypedSampleWorkerSpec'
sbt 'runMain opengpu.elaboration.EmitPpaRtl mobile-resident-sample-pipeline .artifacts/resident-sample-rtl'
CHIPAGENT_DIR=../chipagent ../chipagent/.venv/bin/python scripts/qualify_ppa.py \
  .artifacts/resident-sample-rtl ResidentTypedSamplePipeline \
  --stage synthesis --period-ps 1000 --io-delay 0.25 --timeout 14400 \
  --output .artifacts/resident-sample-synthesis
```
