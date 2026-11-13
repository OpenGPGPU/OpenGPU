# CU vector access to resident shader payloads

`GpuComputeUnitCore` routes eligible vector word loads and stores directly to the task's
resident shader payload lease through `CuShaderPayloadAccess`. This removes
the vector coalescer, shared cache-line arbitration and local/VM splitting
from the common resident vector access. The same physical payload port and
checked lease remain in use.

## Admission and ownership

The direct path requires a nonempty mask, naturally aligned 32-bit words,
at most sixteen lanes and a contiguous vector contained in one 64-byte line.
Every active lane's complete word must lie inside the trusted resident prefix.
The binding must have an aligned base, a nonzero supported size, an exclusive
end no greater than 4 GiB, and valid handle/owner indices. Classification uses
the binding and complete vector captured at input acceptance. The prepared
request retains the handle, generation, context, owner, word offset, exact
byte mask and store data from that snapshot.

Bound requests register the eligibility decision so address/range checks do
not drive public ready/valid signals. An eligible decision can immediately
offer its prepared request to the registered payload arbiter. Other bound
vectors retain their complete original transaction and reach the general
coalescer/cache/translation path after two preparation clocks. This includes
narrow, unaligned, strided, cross-line, mixed-prefix and 32-lane requests.
Unbound requests use the general path without extra pipeline stages. A held
unbound offer cannot change paths when a binding appears.

The direct vector client and existing scalar/general line client share a
registered two-entry request queue. Private source tags route reordered
responses and restore each client's original tag. Each client owns at most
one outstanding access; held requests and completions retain their fields.
Task drain includes both clients and the queue before lease replacement or
release. Product CU, lane, warp, arena and lease capacities are retained.

Cancellation before a bound access is offered returns an attributed fault
without a memory request. Once offered, a request drains and its eventual
result is poisoned. Cancellation of a general vector remains latched while
the coalescer prepares its line access, preventing a short cancel pulse from
being forgotten at delayed line admission. Held final results remain stable.
Inactive lanes and store response data are zero; payload faults affect the
effective lane mask.

## Measured clocks

The matched baseline is `06b6a75a2178`. Six quads execute in two batches on
two real shared CUs with four lanes and four warps, four color outputs,
per-sample depth and identical deterministic memory stalls. All output values
and metadata match. Fragment input and output readback traffic remain
1,344 and 1,728 enabled bytes respectively.

| Payload path | Baseline draw clocks | Direct vector draw clocks | Read worker clocks, both versions |
|---|---:|---:|---:|
| Global memory | 6,374 | 6,374 | 544 |
| Resident SRAM | 4,593 | 3,619 | 434 |

The resident draw takes 21.2% fewer clocks in this bounded fixture. Staging
worker counts remain 956/global and 248/resident. The resident total budget
is tightened from 4,610 to 3,650 clocks, rejecting the previous implementation;
the global budget remains 6,390. These counts include held completion and
drain and do not measure a product frame.

## Validation

Twenty-two distinct functional checks pass: nine access-boundary checks,
three actual-CU checks and ten shared-CU fragment checks. Directed checks
cover four- and sixteen-lane masks, aligned word offsets,
binding changes, general-path fallbacks, the final addressable word,
cancellation before/after offers, delayed general-line admission, reordered
replies, backpressure and healthy reuse. Actual CU checks exercise resident
Sv32 bypass, uniform memory and mixed resident/private/global vectors.
Shared-CU fragment checks retain exact input/output traffic, metadata,
fault drain, full varying/export capacity and native floating texture work.
These checks use bounded fixtures; larger component configurations stay local.

Reproduce the functional checks:

```sh
git submodule update --init depends/asap7_sram_0p0
sbt -J-Xmx4G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=2 \
  -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  -Dopengpu.simSeparateBuild=1 -Dopengpu.simReleaseHeap=1 \
  -Dopengpu.simHierarchy=GpuComputeUnit \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.core.memory.VectorShaderPayloadRouterSpec opengpu.core.memory.ShaderPayloadAccessArbiterSpec opengpu.core.memory.CuShaderPayloadAccessSpec' \
  'testOnly opengpu.core.GpuComputeUnitPayloadSpec' \
  'testOnly opengpu.graphics.FragmentShaderStageQualificationSpec -- -z "coalesce four input quads" -z "deliver FP32 and typed raw fragment quads" -z "drain staging, shader and malformed export faults" -z "overlap metadata" -z "carry all64 varying components" -z "shade product recipe colors with native floating texture operands local=true"'
```

The complete changed access boundary, including the legacy line splitter and
queued arbiter, passes native sixteen-lane/eight-warp ASAP7 TC/SLVT synthesis
setup at 1,000 ps with 25% I/O constraints: core +63.555 ps and I/O +323.593 ps.
Mapping skips no functional instances and reports no unresolved logic;
`check_setup` confirms complete, clean constraint coverage. Source and emitted
RTL hashes remain unchanged through qualification.

```sh
sbt 'Test / runMain opengpu.core.memory.EmitCuShaderPayloadAccessTiming .artifacts/cu-payload-rtl'
CHIPAGENT_DIR=../chipagent ../chipagent/.venv/bin/python scripts/qualify_ppa.py \
  .artifacts/cu-payload-rtl CuShaderPayloadAccess --stage synthesis \
  $(python3 scripts/report_ppa_sram_inventory.py .artifacts/cu-payload-rtl CuShaderPayloadAccess) \
  --synthesis-engine yosys --yosys-preserve-hierarchy --period-ps 1000 \
  --io-delay 0.25 --timeout 7200 --output .artifacts/cu-payload-synthesis
```

This synthesis boundary contains the access logic, not the physical payload
SRAM, whole CU or host. Component clocks and setup do not qualify routed
setup/hold, a whole-host clock, 720p frame rates or H6 completion.
