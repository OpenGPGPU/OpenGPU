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

`BankedShaderPayloadStore` has two ordered admission entries with an empty-queue
bypass. Per-port reply credits cover queued operations, the active SRAM operation
and held replies. Queued initializers retain ownership through completion.
Drain status uses registered occupancy. The characterized single-port SRAM
still performs masked read/modify/write operations across its existing phases.

## Validation and limits

Forty-three targeted data-path tests pass: seventeen behavioral/vendor-macro store tests,
eight transport tests, eight native fragment checks, eight legacy fragment
recovery checks and two vertex response-drain checks. They include same-line
ordering, independent response stalls, cancellation, rejected ranges, a delayed
input-write fault while younger writes retire, non-power-of-two batches and
healthy reuse. The separate default native image fixture passes both 1x and
4x MSAA frames with native worker telemetry and no payload spills.

The matched five-quad fixture uses two shared CUs, four lanes, two warps, three
batches, identical shaders, memory latency and output stalls:

| Payload path | One-transfer baseline (clocks) | Two-transfer path (clocks) |
|---|---:|---:|
| Local pool | 6,139 | 5,913 |
| Global staging | 7,917 | 7,434 |

Both paths observe two accepted transfers in flight and verify all exported
values and metadata. These are bounded fixture results. Raw preparation,
readback issue width, concurrent four-CU fragment tasks, whole-GPU timing and
720p/30 FPS remain separate gates.

The native physical profiles derive the 64 KiB pool, 16 KiB reservation bound,
two owners and six ports from `NativeTypedGraphicsConfig`. Both complete native
fragment and payload components must pass 1000 ps synthesis with 25% I/O delay
and zero skipped/unresolved mapping before merge. The prerequisite four-context
raster/interpolation owner passes +29.020/+477.735 ps with 2,041,453 cells and
292,126 µm² of library area; shader, payload and attachment timing are separate.

```sh
sbt -J-Xmx6G -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 \
  -Dopengpu.simLowMemOpt=1 'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.BankedShaderPayloadStoreSpec opengpu.graphics.FragmentPayloadTransportSpec'
sbt -J-Xmx6G -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 \
  -Dopengpu.simLowMemOpt=1 -Dopengpu.simHierarchy=GpuComputeUnit \
  -Dopengpu.productQualification=0 'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.FragmentShaderStageSpec -- -z "deliver FP32 and typed raw fragment quads"' \
  'testOnly opengpu.graphics.FragmentShaderStageSpec -- -z "count held global staging requests"' \
  'testOnly opengpu.graphics.FragmentShaderStageSpec -- -z "drain staging, shader and malformed export faults"' \
  'testOnly opengpu.graphics.FragmentShaderStageSpec -- -z "require EOF while canceling an accepted staging offer"' \
  'testOnly opengpu.graphics.FragmentShaderStageSpec -- -z "transpose a non-power-of-two12lane batch"' \
  'testOnly opengpu.graphics.FragmentShaderStageSpec -- -z "attribute a late input write fault"' \
  'testOnly opengpu.graphics.KernelFragPayloadSpec -- -z "drain"' \
  'testOnly opengpu.graphics.VertexShaderStageSpec -- -z "newly accepted registered response"'
for ppa_case in \
  FragmentShaderStage:mobile-native-fragment-shader \
  BankedShaderPayloadStore:mobile-native-shader-payload; do
  ppa_module="${ppa_case%%:*}"
  ppa_profile="${ppa_case#*:}"
  sbt "runMain opengpu.elaboration.EmitPpaRtl $ppa_profile .artifacts/$ppa_profile-rtl"
  CHIPAGENT_DIR=../chipagent ../chipagent/.venv/bin/python scripts/qualify_ppa.py \
    ".artifacts/$ppa_profile-rtl" "$ppa_module" --stage synthesis \
    --period-ps 1000 --io-delay 0.25 --output ".artifacts/$ppa_profile-synthesis"
done
```
