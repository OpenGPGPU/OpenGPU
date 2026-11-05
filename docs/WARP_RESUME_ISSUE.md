# Resume into an empty registered issue slot

When no warp is ready, `WarpScheduler` can use a committed resume to prepare
the next registered issue offer. The PC and active mask come from that resume.
The small scheduler control observes one clock from resume to offer, instead
of two. No instruction or required shader work is removed.

This fallback requires an active, blocked, valid warp and no launch or finish
offer. It excludes the warp already offered. Existing ready warps retain their
round-robin order, held issue bits stay stable, and the final issue acquisition
still wins the blocked-register update. Register-file writeback and resume
arbitration retain their existing order. `resumeToIssue=false` exists only for
the component control; no new execution parameter is passed through a CU.

## Matched native draw result

The baseline uses the prior scheduler; the candidate changes only the actual
nested `WarpScheduler` module in the complete emitted input set. Both use two
real CUs, 16 lanes/eight warps, native SRAM/FPU, default L2/private caches,
128-item capacity, 64 varying components, four color outputs and two staging
IDs. The 35-quad draw retains all 140 items, including the 12-item tail.

Each variant starts cold after reset. The identical 393-word shader runs with
reordered 5–8-clock lower-memory replies, request backpressure on two of seven
clocks and resident reply holds on four of thirteen clocks. The complete
completion value, exports, accepted staging data/masks/order and backing
memory are compared byte for byte. Shader words and all existing ownership,
drain and workload assertions remain checked.

| Resident draw | Original clocks | Candidate clocks | Original phase 3 | Candidate phase 3 |
| --- | ---: | ---: | ---: | ---: |
| 1 quad | 6,402 | 6,002 | 5,821 | 5,425 |
| 35 quads | 19,823 | 19,238 | 12,397 | 11,808 |

The draw reductions are 6.248% and 2.951%; the 35-quad phase-3 reduction is
4.751%. The 35-quad phase vectors are respectively
`[97,5411,12,12397,1859,47]` and `[97,5416,12,11808,1858,47]`, each summing
to its draw clocks. Both variants accept 26 external reads and 810 staging
requests. Phase 3 is the combined `waitKernel` interval, not an internal CU
stall counter. These component draws do not establish a 720p, FPS or
whole-host clock improvement; H6 remains open.

The original model was a normal cache hit; the candidate was one fresh normal
native build. Complete new input captures contain 693 files each, with only
the scheduler body changing apart from the exact owned-workspace path envelope.
Source, classes, JARs, binaries and raw reports were pinned separately. Missing
historical original support-C++ bytes remain unknown; a new capture does not
reconstruct that old evidence. Raw process and model records stay local under
`.artifacts/` and can be removed after merge when no task needs them.

## Control and timing checks

The 12 scheduler/frontend/control checks pass, covering actual resume PC/mask,
one-clock improvement, held offers, blocked ownership, ordinary round-robin
priority, invalid resumes, launch/finish fences, commit redirects, fetch fault
and SIMT restore priority. Run them sequentially:

```sh
sbt -J-Xmx3G -J-XX:ActiveProcessorCount=2 -J-XX:+ExitOnOutOfMemoryError \
  -Dopengpu.simJobs=1 -Dopengpu.productQualification=0 \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.core.frontend.warp.WarpSchedulerSpec' \
  'testOnly opengpu.core.frontend.GpuFrontendSpec' \
  'testOnly opengpu.core.frontend.SimtFrontendControlSpec'
```

Default 16-lane/eight-warp scheduler synthesis passes TC/SLVT at 1,000 ps with
25% I/O delays and complete mapping/constraint coverage. Original core/I/O
setup slacks are +498.783/+402.947 ps; candidate slacks are +385.029/+408.158 ps.
The new resume/finish-to-registered-issue path costs 113.754 ps of core margin.
This is standalone synthesis timing, before placement/routing. The timing
emitter uses standard `disallowLocalVariables,disallowPackedArrays` lowering;
its emitted bytes are separate from the actual simulation inputs.

```sh
sbt -J-Xmx3G -J-XX:ActiveProcessorCount=2 -J-XX:+ExitOnOutOfMemoryError \
  -Dopengpu.warpResumePpa=1 \
  'Test / runMain opengpu.core.frontend.warp.EmitWarpResumePpa .artifacts/warp-resume/rtl'
python3 scripts/qualify_ppa.py .artifacts/warp-resume/rtl/candidate WarpScheduler \
  --stage synthesis --synthesis-engine yosys --clock clock --period-ps 1000 \
  --io-delay 0.25 --timeout 600 --output .artifacts/warp-resume/timing
```

The full native comparison remains opt-in and outside regular CI. Compile the
test classes once, plus unchanged baseline Main classes from `afe6246a` and
candidate Main classes from this change. Run the same test twice with those
respective Main directories after the test classes in the Java classpath;
keep the JAR classpath identical. Set `MODE`, `MAIN_CLASSES`, `TEST_CLASSES`
and `JAR_CP` to the corresponding mode and compiled paths, baseline first:

```sh
java -Xmx3G -XX:ActiveProcessorCount=2 -XX:+ExitOnOutOfMemoryError \
  -Dopengpu.simJobs=1 -Dopengpu.simHierarchy=GpuComputeUnit \
  -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  -Dopengpu.simSeparateBuild=1 -Dopengpu.simReleaseHeap=1 \
  -Dopengpu.productQualification=0 -Dopengpu.fragmentPhaseAudit=1 \
  -Dopengpu.fragmentResumeFallbackQualification=1 \
  -Dopengpu.fragmentResumeFallbackMode="$MODE" \
  -Dopengpu.fragmentResumeBaselineDir=.artifacts/warp-resume/semantic-baseline \
  -Dopengpu.fragmentPhaseAuditDir=".artifacts/warp-resume/observations/$MODE" \
  -cp "$TEST_CLASSES:$MAIN_CLASSES:$JAR_CP" org.scalatest.tools.Runner \
  -R "$TEST_CLASSES" -s opengpu.graphics.FragmentShaderStageQualificationSpec \
  -z 'compare the full resident native warp-resume candidate with unchanged cold shader work'
```

Use new observation and semantic-baseline directories. The test requires exact
baseline clocks, candidate one-quad non-regression and strict 35-quad draw and
phase-3 improvement, in addition to the complete semantic comparison.
