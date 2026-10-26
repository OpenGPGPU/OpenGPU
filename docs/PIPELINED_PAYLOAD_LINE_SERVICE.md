# Pipelined shader payload line service

`BankedShaderPayloadStore` overlaps a queued SRAM operation with retirement of
the preceding reply. Sixteen single-port 32-bit banks serve each 64-byte line.
The two-entry admission queue captures the physical row before SRAM service;
client arbitration cannot bypass it into the memory ports.

| Prepared stream | Previous interval | Current interval |
|---|---:|---:|
| Masked line reads | 2 clocks | 1 clock |
| Masked writes containing complete 32-bit words | 2 clocks | 1 clock |
| Writes containing partial words | 2 clocks | 2 clocks |

Partial words retain exclusive read/modify/write ownership. No byte-write SRAM
port or extra memory port is assumed. Lease limits, macro capacities, request
and response ABIs, reply credits and task-pin ownership remain unchanged.

Cancellation suppresses uncommitted writes and poisons unfinished reads or
read/modify/write operations. A completed physical write retains its successful
reply through later cancellation, including the clock before reply enqueue.
Offered replies remain immutable. Release waits for accepted operations,
initialization, held replies and task pins; healthy reuse initializes the new
lease and rejects the old generation.

All 39 component checks pass with no failed, canceled, ignored or pending cases.
Behavioral and characterized SRAM models cover exact byte masks, same-line
ordering, full-line and partial-word traffic, independent backpressure, all
fourteen default reservations, physical segment boundaries and 31 task pins.
Each model also covers 18 cancellation offsets/classes with retained committed
replies, peer-lease guards and healthy reuse. The rate checks include 48 reads
and separate 24-write streams per model; mixed traffic adds 144 operations
across two physical segments per model.

Three further checks pass through real shared CUs: packed/FP32 fragment delivery,
all 64 varying components across 128 physical lanes, and pool-pressure spill
with held exports, launch cancellation and reuse.

The complete native-capacity payload component passes standard synthesis at
1000 ps, TC/SLVT with 25% I/O budgets: core/I/O slack is +57.718/+71.466 ps,
with zero skipped or unresolved logic and no retiming, false paths or mocked
operators. Its declared SRAM capacity remains 512 KiB. Complete native-host
workloads, whole-host timing, frame rates and H0–H8 completion remain required.

```sh
git submodule update --init depends/asap7_sram_0p0
sbt -J-Xmx2G -J-XX:+ExitOnOutOfMemoryError \
  -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  -Dopengpu.simSeparateBuild=1 -Dopengpu.simReleaseHeap=1 \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.BankedShaderPayloadStoreSpec'

sbt -J-Xmx3G -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 \
  -Dopengpu.simLowMemOpt=1 -Dopengpu.simSeparateBuild=1 \
  -Dopengpu.simReleaseHeap=1 -Dopengpu.simHierarchy=GpuComputeUnit \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.FragmentShaderStageSpec -- -t "should deliver FP32 and typed raw fragment quads through real shared CUs local=true" -t "should carry all64 varying components and128 physical lanes with oversizeSpill=false" -t "should spill under pool pressure and retain offered exports and launches through cancellation"'

# Actual four-engine payload capacity and task-pin interface:
sbt -J-Xmx2G \
  'runMain opengpu.elaboration.EmitPpaRtl mobile-public-frame-shader-payload .artifacts/payload-line-service-rtl'
CHIPAGENT_DIR=../chipagent ../chipagent/.venv/bin/python scripts/qualify_ppa.py \
  .artifacts/payload-line-service-rtl BankedShaderPayloadStore \
  --stage synthesis --period-ps 1000 --io-delay 0.25 --synthesis-engine syn \
  --timeout 21600 --output .artifacts/payload-line-service-synthesis
```

Raw logs, manifests and intermediate reports stay local under `.artifacts/`.
