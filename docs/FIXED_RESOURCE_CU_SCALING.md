# Fixed-resource shader scaling

The parallel legacy renderer can size fragment workers independently of its
physical CU pool. `SharedCuShaderConfig.fragmentWorkerCount` and the matching
`GpuHostSystemAxi` argument select 1–16 workers. Zero preserves automatic sizing:
one worker per CU in parallel mode, or one worker in serial mode. An explicit
count requires parallel shared graphics. Native typed/binned profiles retain
their existing clients and reject this override.

The elaborator exposes `--fragment-workers N` with `--frag-core`,
`--shared-graphics` and `--parallel-graphics`. It cannot be combined with
`--binned-tile-jobs`. No software descriptor or register layout changes.

In parallel mode, each worker has two guarded fragment scratch banks and eight staging credits.
The shared staging window rounds the total to a power of two; the word-memory
namespace also reserves the existing DMA IDs. The explicit upper bound keeps
the credit count within the event counters. Task tags must represent every
worker plus the vertex client. Bank counts must fit the existing resource word.

`SHADER_CONFIG` at 0x158 reports physical executors and scratch banks separately.
Drivers must allocate the advertised bank count rather than twice the CU count.
The current driver/userspace helpers already follow that rule. Four workers
retain eight banks, 32 staging credits and 64 kernel-word IDs across 1/2/4 CUs.
Three workers use six banks; a single worker may feed a larger CU pool.

## Measurements and interpretation

Changing worker count with CU count also changes front-end buffering, samplers
and staging resources. Such a comparison does not establish fixed-resource CU
scaling. For a fixed comparison, retain the same shader binary, image, batch
capacity, worker count, scratch layout, OM settings, L2/cache geometry, AXI beat
width and response delays. Per-CU execution and private caches scale with CUs.
L2 load-miss slots, store credits and request-queue depth remain fixed. Per-CU
request tags and coherence metadata grow with the pool; this is not a claim of
identical non-CU logic area.

The existing arithmetic probe has an explicit qualification mode:

```sh
sbt -J-Xmx6G -Dopengpu.simJobs=4 \
  -Dopengpu.scalingFragmentWorkers=4 \
  -Dopengpu.scalingCuCounts=1,2,4 \
  -Dopengpu.scalingMinSpeedup=3.2 \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.system.ArithmeticCoalescedWideAxiSharedGraphicsSpec'
```

It uses identical 64x64 images, 16 lanes/eight warps per CU, uncapped 128-thread
batch capacity, four workers/eight banks and the same 64-loop integer shader. The OM,
texture/staging clients, 512-bit memory port, L2 geometry and response delays
remain fixed. Every pixel, depth word and scratch guard is checked. Qualification
requires all three configurations to complete and compares one-CU cycles with
four-CU cycles against a target of at least 3.2x. Filtering away a configuration
cannot produce a qualification pass. The ordinary suite retains its original
automatic-worker 1/4-CU cases unless these properties are explicitly set.

The 2026-10-22 qualification passes the 3.2x target with **3.374x** scaling.
All three exact image/depth/scratch checks pass, and the invariant check confirms
the same shader, draw and non-CU resource configuration.

| Physical CUs | Cycles | Active CU-cycles | Peak active CUs / workers |
| --- | ---: | ---: | ---: |
| 1 | 382,860 | 381,166 | 1 / 4 |
| 2 | 193,556 | 381,409 | 2 / 4 |
| 4 | 113,483 | 381,435 | 4 / 4 |

The four-CU pool is active for 84.0% of its available CU-cycles. Its four workers
accumulate 43,877 idle, 5,371 write, 18 launch, 381,471 run, 1,301 read and 21,894
emit worker-cycles. Output blocking occurs for 15,295 cycles, OM stalls for
13,134 and staging blocking for 32. These overlapping counters explain retained
front-end/output overhead; they must not be added as independent stall time.
This is a measured result for this workload, not a general scaling guarantee.

This is a shader-bound legacy-renderer cycle comparison. It does not establish
native renderer concurrency, elapsed time at a qualified whole-host clock,
balanced-resource scaling, bandwidth saturation, a 720p frame budget or H0–H8
completion. Preserve the separate textured workload and its measured limits.

Routine ownership regressions use 16x16 images, four lanes/two warps and two
arithmetic loops. They cover four workers with 1/2/4 CUs, a three-worker/two-CU
profile, and one worker with four CUs. Run them with:

```sh
sbt -Dopengpu.simJobs=4 'set Test / parallelExecution := false' \
  'testOnly opengpu.system.FixedFragmentWorkersSpec opengpu.system.NonPowerFragmentWorkersSpec opengpu.system.UnderfilledFragmentWorkersSpec'
python3 scripts/test_analyze_shared_graphics_trace.py
```

Optional raw traces belong under `.artifacts/` through `WIDE_GRAPHICS_TRACE_DIR`.
Metadata includes the actual worker count and checked scratch layout. The
analyzer checks each interval against workers, independently of CUs, and rejects
incomplete pixel checks, inconsistent bank counts and empty/nonpositive traces.
Historical traces without the new field retain the workers-equal-CUs rule.
