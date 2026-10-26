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

These are component functionality and service-rate results. Native workload
integration and current-source synthesis timing remain required. They do not
qualify the whole-host clock, frame rates or H0–H8 completion.

```sh
git submodule update --init depends/asap7_sram_0p0
sbt -J-Xmx2G -J-XX:+ExitOnOutOfMemoryError \
  -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  -Dopengpu.simSeparateBuild=1 -Dopengpu.simReleaseHeap=1 \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.BankedShaderPayloadStoreSpec'

# Actual four-engine payload capacity and task-pin interface:
sbt -J-Xmx2G \
  'runMain opengpu.elaboration.EmitPpaRtl mobile-public-frame-shader-payload .artifacts/payload-line-service-rtl'
```

Raw logs, manifests and intermediate reports stay local under `.artifacts/`.
