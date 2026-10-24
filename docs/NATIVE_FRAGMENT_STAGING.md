# Native fragment staging

`FragmentShaderStage` owns two input transfers concurrently. Staging uses
transaction IDs 0–1 and texture traffic uses IDs 2–3. Each staging ID retains
its fault address until its response returns. Input fields advance when their
request is offered; shader launch waits for all accepted writes to drain.
Input writes retain the existing 16-byte quad mask within a 64-byte line.

The native `FragmentPayloadTransport` uses two request/reply slots. IDs remain
owned through response consumption, including reversed replies and stalled
consumers. Cancellation retains posted requests and offered replies, rejects
new work, and drains the lease before reuse. Other clients retain the default
single-operation transport configuration.

Input acceptance prepares exact signed quarter-pixel magnitudes. The existing
input-validation clock normalizes and rounds the FP32 pixel-center/sample
coordinates before field selection and request packing. Wide event counters
retain same-cycle updates and observe completion values through
registered chunks. Two settling clocks after drain precede report capture;
there is no added per-batch staging latency.

EOF validation checks the carry-save count directly against the supplied
99-bit expected count. Local carry witnesses replace a propagated binary sum
followed by equality. The parallel successor retains simultaneous final-quad
and EOF behavior, without an extra validation clock.

Two registered memory-response entries precede tag routing and fault/texture
control. Accepted replies retain their transaction ownership until the routed
consumer accepts them. Drain includes queued replies. External replies gain
one clock of latency; local payload SRAM keeps its direct response path.

`BankedShaderPayloadStore` has two ordered admission entries with an empty-queue
bypass. Per-port reply credits cover queued operations, the active SRAM operation
and held replies. Queued initializers retain ownership through completion.
Drain status uses registered occupancy. The characterized single-port SRAM
still serializes each masked read/modify/write across a read clock and a write
clock. Completion admits the next queued operation directly, removing the
inter-operation idle clock without overlapping accesses to a single-port array.

## Payload service capacity

Twenty-five store tests pass with behavioral and vendor SRAM models. A dense
three-port fixture commits 24 overlapping masked writes at an exact two-clock
interval, then verifies all 128 bytes against admission order. The existing
180-operation fixture with independent response stalls takes 364 clocks,
compared with 542 clocks before the handoff change (32.8% fewer). Cancellation,
held replies, initialization, allocation-tail boundaries and cross-owner reuse
retain their checks.
This increases queued line service capacity by 50%; it does not establish
fragment throughput or frame rate. The current complete fragment-stage gate passes 1,000 ps/25% I/O with
+41.534 ps core/+8.259 ps I/O slack and zero skipped or unresolved mappings.

## Validation and limits

The store checks cover same-line ordering, independent response stalls,
cancellation, rejected ranges and healthy reuse. Sixteen native fragment checks pass with the combined payload changes, including
local/global staging, phase counters, non-power-of-two batches, simultaneous
final-quad/EOF count errors, a high count bit, late input faults, cancellation,
coherent sample locations at 2x/4x MSAA, protected per-sample depths, shader
depth overrides and inactive-metadata checks. Both coordinate checks pass; the
prepared path covers 2,340 signed boundary/random cases, poisoned input fields,
held values and consecutive preparations. The 23 counter/arithmetic checks
retain their separate coverage. Two
captured-texture policy cases exercise 42 draws with fault drain and healthy reuse. Nine additional comparison checks cover complete small truth tables,
long carries, wraparound, corrupted candidate bits and initialized counter clear
priority through 128 bits.
Host-script checks also pass. The separate default native image fixture passes both 1x and
4x MSAA frames with native worker telemetry and no payload spills.

The two-axis prepared-coordinate packing harness passes 1,000 ps synthesis
with +224.392 ps core/+722.724 ps I/O margin and zero skipped or unresolved
mappings. Full fragment-stage timing remains a separate merge gate.

The matched five-quad fixture uses two shared CUs, four lanes, two warps, three
batches, identical shaders, memory latency and output stalls:

| Payload path | One-transfer baseline (clocks) | Current path (clocks) |
|---|---:|---:|
| Local pool | 6,139 | 5,917 |
| Global staging | 7,917 | 7,563 |

These current measurements include the SRAM handoff, release fence, registered
completion observations and memory-response ingress. The latter adds 127 clocks
to this global fixture; coordinate capture adds no further clocks.
The concurrent three-port fixture measures the queued
SRAM service rate separately. Both paths observe two accepted transfers in flight and verify all exported
values and metadata. These are bounded fixture results. Raw preparation,
readback issue width, concurrent four-CU fragment tasks, whole-GPU timing and
720p/30 FPS remain separate gates.

The native physical profiles derive the 64 KiB pool, 16 KiB reservation bound,
two owners and six ports from `NativeTypedGraphicsConfig`. The payload profile
includes all nine typed-host task pins (four CUs, DMA and four producer pins).
The two-engine binned payload profile uses three owners, eight ports and nineteen
task pins. Both payload profiles pass 1000 ps synthesis with 25% I/O delay and
zero skipped/unresolved mapping:

| Payload component | Core margin (ps) | I/O margin (ps) |
|---|---:|---:|
| Typed host, nine task pins | +114.846 | +86.811 |
| Two-engine binned host, nineteen task pins | +87.433 | +53.363 |

Pin checks cover release backpressure without blocking shader drain traffic
and rejection of stale generations. The shader profile binds fragment owner 1,
matching the typed host. This specialization changes only two static owner
output bits; neither has an internal consumer and all timing-bearing RTL is
identical. A registered release fence stops new
admission before release can commit. Current pins still veto release on the
same clock; a new pin clears the admission fence on the next clock so its
shader can continue draining. The 99-bit combinational expected-count comparator passes 1000 ps synthesis
including I/O with +318.330 ps margin and zero skipped/unresolved mappings.
Both complete native
fragment and payload components must pass 1000 ps synthesis with 25% I/O delay
and zero skipped/unresolved mapping before merge. The prerequisite eight-context
raster/interpolation owner passes +9.550/+477.735 ps with 475,284 µm² of library
area; shader, payload and attachment timing are separate. The current combined
vertex/raster/fragment local-payload check also passes with exact exports and
held VM ownership.

```sh
sbt -J-Xmx6G -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 \
  -Dopengpu.simLowMemOpt=1 'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.BankedShaderPayloadStoreSpec opengpu.graphics.FragmentPayloadTransportSpec'
sbt -J-Xmx6G -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 \
  -Dopengpu.simLowMemOpt=1 -Dopengpu.simHierarchy=GpuComputeUnit \
  -Dopengpu.productQualification=0 'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.FragmentCoordinateRegisterSpec opengpu.util.CarrySaveEventCounterSpec opengpu.util.BlockPrefixAdderSpec opengpu.util.CarrySaveEqualitySpec' \
  'testOnly opengpu.graphics.FragmentShaderStageSpec -- -z "deliver FP32 and typed raw fragment quads"' \
  'testOnly opengpu.graphics.FragmentShaderStageSpec -- -z "count held global staging requests"' \
  'testOnly opengpu.graphics.FragmentShaderStageSpec -- -z "drain staging, shader and malformed export faults"' \
  'testOnly opengpu.graphics.FragmentShaderStageSpec -- -z "require EOF while canceling an accepted staging offer"' \
  'testOnly opengpu.graphics.FragmentShaderStageSpec -- -z "transpose a non-power-of-two12lane batch"' \
  'testOnly opengpu.graphics.FragmentShaderStageSpec -- -z "attribute a late input write fault"' \
  'testOnly opengpu.graphics.FragmentShaderStageSpec -- -z "reject descriptor, quad and EOF errors"' \
  'testOnly opengpu.graphics.FragmentShaderStageSpec -- -z "carry coherent covered sample locations"' \
  'testOnly opengpu.graphics.FragmentShaderStageSpec -- -z "execute captured native texture policies and trap coordinate faults"' \
  'testOnly opengpu.graphics.FragmentShaderStageSpec -- -z "retain protected per-sample raster depths"' \
  'testOnly opengpu.graphics.FragmentShaderStageSpec -- -z "reject invalid active sample depths"' \
  'testOnly opengpu.graphics.KernelFragPayloadSpec -- -z "drain"' \
  'testOnly opengpu.graphics.VertexShaderStageSpec -- -z "newly accepted registered response"'
sbt 'Test / runMain opengpu.graphics.EmitFragmentCoordinateTiming .artifacts/coordinate-rtl'
CHIPAGENT_DIR=../chipagent ../chipagent/.venv/bin/python scripts/qualify_ppa.py \
  .artifacts/coordinate-rtl FragmentCoordinateTimingHarness --stage synthesis \
  --period-ps 1000 --io-delay 0.25 --output .artifacts/coordinate-synthesis
for ppa_case in \
  FragmentShaderStage:mobile-native-fragment-shader \
  BankedShaderPayloadStore:mobile-native-shader-payload \
  BankedShaderPayloadStore:mobile-native-binned-shader-payload; do
  ppa_module="${ppa_case%%:*}"
  ppa_profile="${ppa_case#*:}"
  sbt "runMain opengpu.elaboration.EmitPpaRtl $ppa_profile .artifacts/$ppa_profile-rtl"
  CHIPAGENT_DIR=../chipagent ../chipagent/.venv/bin/python scripts/qualify_ppa.py \
    ".artifacts/$ppa_profile-rtl" "$ppa_module" --stage synthesis \
    --period-ps 1000 --io-delay 0.25 --output ".artifacts/$ppa_profile-synthesis"
done
```
