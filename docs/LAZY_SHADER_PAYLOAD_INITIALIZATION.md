# Lazy shader payload initialization

`BankedShaderPayloadStore` keeps eager initialization as a reference and enables
lazy initialization in the shared-CU system. Allocation invalidates per-line
metadata. An untouched read returns zero without reading SRAM; a first masked
write materializes every word bank with zero-filled neighboring bytes. A zero
write mask does not materialize a line. Queued first writes forward their
materialization decision to the following request.

The line-validity lookup is registered before physical service. Address
registers belong to active word-bank groups. Whole-word operations retain
one-clock initiation and byte-partial read/modify/write retains two-clock
initiation; the extra preparation clock affects latency rather than that rate.

Cancellation compares against the request at each service phase: the issuing
lease before SRAM access and the held lease at retirement. A previously served
peer cannot hide a winning cancellation. Writes committed on an earlier edge
retain their immutable replies. Release still waits for data replies and task
pins before recycling a lease.

The full native pool has 524,288 physical SRAM bytes in 512 real macros. Its
seven complete reservations cover 487,424 bytes; this is reserved address space,
not measured runtime occupancy. Lazy metadata is separate from those SRAM
bytes. The native check retains twelve ports and thirty-one task pins.

All 94 eager/lazy contract cases, eight transport cases and three real-CU
cases pass. The explicit native pool check also passes with segment edges,
final rows, masked first writes, held replies and poisoned reuse. Four native
pin regressions cover seven cancellation placements each; earlier committed
replies remain healthy. The full 128-lane, four-color export check retains its
32-clock maximum gap. A poisoned 2 KiB lease returns its first zero read in
four clocks instead of 95, with zero initialization writes and cold SRAM reads.
Complete native RTL emission passes. Raw failure/qualification reports stay
local under `.artifacts/`.

Reproduce bounded contracts and transport checks:

```sh
sbt -J-Xmx3G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.BankedShaderPayloadStoreSpec opengpu.graphics.LazyBankedShaderPayloadStoreSpec opengpu.graphics.FragmentPayloadTransportSpec'
```

Run the explicit full-capacity and real-CU qualification locally:

```sh
sbt -J-Xmx3G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.simHierarchy=GpuComputeUnit -Dopengpu.simLowMem=1 \
  -Dopengpu.simLowMemOpt=1 -Dopengpu.simSeparateBuild=1 \
  -Dopengpu.simReleaseHeap=1 'set Test / parallelExecution := false' \
  'Test / runMain opengpu.graphics.QualifyLazyNativePayload' \
  'testOnly opengpu.graphics.FragmentShaderStageSpec -- -z "deliver FP32 and typed raw fragment quads through real shared CUs local=true" -z "spill under pool pressure" -z "oversizeSpill=false"'
```

Reproduce the separate complete-component timing gate with the pinned real SRAM
libraries and the repository's PPA toolchain:

```sh
sbt -J-Xmx3G 'runMain opengpu.elaboration.EmitPpaRtl mobile-native-four-engine-shader-payload .artifacts/lazy-payload/emitted'
python3 scripts/extract_emitted_rtl.py .artifacts/lazy-payload/emitted \
  BankedShaderPayloadStore .artifacts/lazy-payload/complete
python3 scripts/qualify_ppa.py .artifacts/lazy-payload/complete \
  BankedShaderPayloadStore --stage synthesis --synthesis-engine yosys \
  --yosys-preserve-hierarchy --period-ps 1000 --io-delay 0.25 \
  --timeout 604800 --output .artifacts/lazy-payload/ppa
```

The gate requires every reachable definition, all 512 characterized macros,
zero skipped logic and nonnegative TC/SLVT setup slack with 25% I/O delay.
Functional checks and component timing do not establish a complete native
frame, a whole-host clock, product FPS or H0–H8 completion.
