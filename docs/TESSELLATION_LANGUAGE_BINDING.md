# Independent tessellation language binding

`TessellationShaderTaskBinding` is an internal, captured language-input record.
Its binding-format version is 1; GPU stage is exactly 4 (TCS) or 5 (TES).
`taskId` is the 32-bit ownership crosslink. The separate 32-bit
`inputPatchPrimitiveId` is the shader-visible input-patch ID. Neither the task
ID nor the generated TES primitive sequence supplies that language value.
This binding version does not authorize a native program-profile version.

`GpuComputeUnit.tessellationInfoAvailable` defaults to false. When explicitly
present, the CU captures the prospective `tessellationTask` only on actual
`kernel.fire`, tracks actual `core.launch.fire` for every physical warp, and
retains its lease until kernel completion transfers. Each worker supports the
full 16-lane, eight-warp configuration. Invalid binding versions/stages do not
enable the instruction. Unlaunched warps and cancelled or malformed requests
produce failed scalar resource commits, using the existing scalar fault path.
An already offered commit remains immutable through cancellation and must drain
before the binding can retire. Trap observation also stops further successful
language reads.

The read is CUSTOM0 (`0x0b`), function 37, funct3 0, selector 6 in the scalar
rs1 value, with instruction rs2 field zero. The new decoder permission grants
only function 37. It grants no geometry input/output, layer, emit/end or
primitive-ID output operations. TCS unsupported selectors fail locally. TES
selectors 0..5 retain the existing `GeometryScalarWorker` meanings only while
that actual geometry owner is active; otherwise they fail locally rather than
escaping to an unserviced external texture port. Selector 6 always uses the
independent tessellation binding. `GeometryInvocationLaunch` and its existing
selectors remain unchanged.

The optional enable is propagated through Core, Frontend, DecodePipe and the
unified decoder. Shared graphics ports and `SharedShaderTaskRouter` capture the
binding with launch acceptance and route cancellation by actual CU task
ownership. Previous full JVM constructor signatures are retained through
auxiliary constructors, with the added feature disabled.

## Final local results and limits

All **788 production and 1128 test Scala sources** were freshly compiled with
Scala 2.13.18 and the Chisel 7.15.0 plugin. Runtime used only these new classes
and 38 third-party JARs checked to contain no GPU classes. No historical GPU
class provider was used. Production/test compilation took 91.1/177.6 seconds,
with sampled aggregate peaks 3,648,128/4,137,104 KiB; each group had a
600-second/6144-MiB bound and a 4-GiB heap. All eight previous full JVM
constructor descriptors were independently derived from the preceding source
and found in the new class files.

Seven focused RTL cases passed with full 16-lane/eight-warp interfaces:

| Suite | Cases | Group seconds |
| --- | ---: | ---: |
| `TessellationScalarInfoWorkerSpec` | 3 | 25.9 |
| `TessellationInfoDecodeSpec` | 2 | 17.2 |
| `TessellationInfoMuxSpec` | 1 | 9.6 |
| `TessellationInfoRouterSpec` | 1 | 39.0 |

Coverage includes all eight physical warp slots, captured full-width IDs
independent of task IDs, unsupported selectors, held commits, cancellation,
healthy reuse, old geometry selector meanings, default-off permissions,
queued binding snapshots and actual-owner-directed cancellation. The router's
two CU ports have protocol peers; they are not two physical CUs.

`GpuComputeUnitTessellationInfoSpec` also passed on one actual full 16×8 CU
with default instruction/vector caches and `finishOnTrap=true` (80.8 seconds,
sampled aggregate peak 1,302,320 KiB). Its six real launches verify captured
TCS/TES ID writeback, unsupported TES selector faults, invalid binding versions,
forbidden geometry instructions, real trap/kernel retirement and no-reset
healthy reuse. Held completion reports and leases survive cancellation;
accepting the report releases the lease. TCS launches exercise two warps and
TES one; this does not prove simultaneous activity on all eight warps. The ID
binding is a direct hardware input; literal instruction lines come from a
physical-memory peer with SATP zero. No original Mesa shader or native submit
is executed by this test.

Each component/full-CU simulation had an independent 600-second/1536-MiB
aggregate bound, one CPU, one unoptimized Verilator job, assertions enabled
and simulation caching disabled. Component JVMs used 256-MiB heap/192-MiB
metaspace; the actual CU used 768/256 MiB. Code cache was 64 MiB throughout.
Source membership, source/ABI/compiler/plugin/JAR inputs, the Java/firtool/
Verilator executables and current compiled classes were checked around their
consumers; capacity was not reduced.

Four separately compiled source-copy controls each ran one original case and
failed an explicit behavior expectation, with production source unchanged:

- Removing `started(d.warpId)` granted an unlaunched warp a successful commit
  (writeRd 1 instead of 0, 9.6 seconds).
- Reading `io.start.bits.inputPatchPrimitiveId` instead of the captured binding
  returned `0xdeadbeef` instead of `0x81234567` (9.3 seconds).
- Removing `!io.geometryOwned` from the scalar mux's local-failure selection
  made the unsupported TES request not ready (0 instead of 1, 9.6 seconds).
- Replacing the decoder's function-37-only language predicate with `true.B`
  authorized an unrelated geometry instruction (legal 1 instead of 0, 9.4 seconds).

These were behavior failures, not timeouts, OOMs or compilation failures. Each
control had a 240-second/1536-MiB bound and the component JVM settings above.
All 165 selector unit checks, integration/large-PR catalog validation and
`git diff --check` passed. Existing VS and sample-image qualifier mappings and
local-only entries are retained alongside the language qualifier. Normal CI
selects the worker/mux/decoder/router checks, while full-CU qualification remains
explicitly local. Ended classes, models, caches, mutants and logs were removed
after their final consumers exited.

## Reproduction

Use a fresh topic checkout with the supported simulation toolchain:

```sh
sbt clean 'Test / compile'
sbt -Dopengpu.productQualification=0 \
  -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=0 \
  -Dopengpu.simCache=off \
  'testOnly opengpu.graphics.TessellationScalarInfoWorkerSpec opengpu.core.frontend.decode.TessellationInfoDecodeSpec opengpu.graphics.TessellationInfoMuxSpec opengpu.graphics.TessellationInfoRouterSpec'
# Explicit local full-capacity single-CU qualification, outside regular CI:
sbt -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=0 \
  -Dopengpu.simCache=off \
  'testOnly opengpu.core.GpuComputeUnitTessellationInfoSpec'
python3 scripts/test_changed_test_suites.py
bash scripts/ci_shard_tests.sh suites system-core ALL
bash scripts/ci_shard_tests.sh suites system-core-large-pr ALL
git diff --check
```

For each negative control, use a separate temporary source copy, make exactly
one change described above and freshly compile it ahead of the normal classes.
Select `-z "read one captured full-width"` for either worker control,
`-z "retire unsupported TES selectors"` for the mux, and
`-z "authorize only scalar info"` for the decoder. Require exactly one executed
test and the stated mismatch, then remove owned outputs after consumers exit.
Scala regression remains disabled.

## Remaining integration

This foundation does not connect three-domain executors/clients or the trusted
original-input-patch producer. Ordinary FS primitive inputs, ordinal-to-language
binding through production parents, joint-frame capture and cross-range cursors
remain separate stages. Existing native compiler/validator sources do not prove
this complete execution path. No factory, public capability or production
admission is enabled here. Two physical CUs, original shaders/resources,
protected/API execution and the **2-CU 640×480 at 60 FPS** product remain
unfinished; alignment acceptance stays 3/40.
