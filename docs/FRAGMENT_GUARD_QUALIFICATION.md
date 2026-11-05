# Execution-limited fragment qualification

The opt-in `FragmentShaderStageQualificationSpec` case
`compare execution-limited full resident native draws with unchanged shader work`
exercises actual `WarpInstructionGuard` instances. The harness enables
`GpuSystem.shaderExecutionLimits`, snapshots the native per-warp quota of
1,048,576 instructions, and uses the product payload pool: 262,144 bytes,
69,632 bytes maximum payload, three owners and eight ports. Other harness
profiles retain their existing settings.

The profile has two actual CUs, default 16 lanes × 8 warps, 128-item capacity,
native SRAM, FPU, default L2/private caches, all 16 varyings (64 components),
four color targets and the unchanged 393-word shader. Cold draws contain one
and 35 quads; the latter processes all 140 items as a full batch and a tail.
Both variants retain the same delayed/reordered memory replies, request
backpressure and payload-response stalls. Checks cover independent shader
output oracles, exports, completion fields, accepted staging bytes/masks/order,
backing memory, metadata, both staging IDs and complete ownership drain.

## Rejected queue-pipeline experiment

The control used normal production `Decision` queue behavior (`pipe=false`,
`flow=false`). The candidate changed only that queue to `pipe=true`, retaining
registered decisions. Both used production source at `c001a3d0`; the fixture
also enabled the guard and full pool above on both sides. The qualification
retains the fixture and leaves production queue behavior unchanged.

| Cold draw | Control draw clocks | Candidate draw clocks | Control phase 3 | Candidate phase 3 |
| --- | ---: | ---: | ---: | ---: |
| 1 quad | 6,796 | 6,796 | 6,219 | 6,219 |
| 35 quads | 20,501 | 20,293 | 13,074 | 12,868 |

The predeclared gate required no regression for one quad and at least 1%
total-draw and 2% phase-3 improvement for 35 quads. The candidate saved
1.014585% total clocks and 1.575646% phase-3 clocks. Its shader outputs,
exports and final backing memory matched the control exactly, but it failed
the phase-3 gate and was rejected. No timing/PPA flow followed this result.
Phase 3 is the combined `waitKernel` interval, not a direct measurement of an
internal CU stall. These are Stage/CU draws; they do not write a product
framebuffer or establish complete-frame performance, physical clock or FPS.

The test is qualification-only, disabled unless explicitly selected, and adds
no routine CI/nightly models. It writes metrics and canonical results before
checking performance, so a rejected experiment retains its actual result.

## Reproduce

Initialize the pinned SRAM submodule and choose fresh, separate output paths:

```sh
git submodule update --init depends/asap7_sram_0p0
guard_root="$PWD/.artifacts/fragment-guard-qualification"
sbt -J-Xmx3G -J-XX:+ExitOnOutOfMemoryError -J-XX:ActiveProcessorCount=2 \
  -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=0 \
  -Dopengpu.simSeparateBuild=1 -Dopengpu.simReleaseHeap=1 \
  -Dopengpu.simCcache=off -Dopengpu.simHierarchy=GpuComputeUnit \
  "-Dopengpu.simCache=$guard_root/control-model" \
  -Dopengpu.fragmentGuardPipeQualification=1 \
  -Dopengpu.fragmentGuardPipeMode=control \
  "-Dopengpu.fragmentGuardPipeOutput=$guard_root/control-output" \
  -Dopengpu.fragmentPhaseAudit=1 \
  "-Dopengpu.fragmentPhaseAuditDir=$guard_root/control-audit" \
  'testOnly opengpu.graphics.FragmentShaderStageQualificationSpec -- -z "compare execution-limited full resident native draws with unchanged shader work"'
```

To reproduce the rejected proposal on a separate topic, change the guard's
queue instantiation to `new Queue(new Decision,1,pipe=true)` and compile
normally. Run the same case with mode `candidate`, fresh candidate model,
output and audit paths, plus
`-Dopengpu.fragmentGuardPipeBaseline=<absolute control-output path>`.
The normal simulator derives a distinct cache key from the actual inputs.
The 35-quad phase-3 assertion is expected to fail for that proposal.
