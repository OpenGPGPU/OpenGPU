# Execution validation

This PR adds scoped instruction-driven SIMT control, execution-service availability
checks, native control pruning and per-workgroup barrier lifetime repair.

| Final qualification scope | Passing checks | Coverage |
|---|---:|---|
| Execution composition | 145 | Core SIMT, native typed/binned programs, texture, grid and host compatibility |
| Sampler composition | 24 | Task ownership, legacy raster, native sampling and default-width graphics |
| Private shader availability | 50 | Missing scalar-FP service, precise faults and healthy reuse |
| Closed AXI-host availability | 21 | Real host commands, FP service profiles and native compatibility |
| Barrier lifetime | 23 | Controller backpressure, compact/default CU values and four-CU grid compatibility |

These source-specific scopes overlap; their counts must not be added. Default
cases include sixteen lanes/eight warps, 128-thread groups, plain/structured
programs and local/spilled payloads. The grid tests use all four configured CUs;
the native image fixture reaches one active CU.

Relevant emitted components pass synthesis setup at 1000 ps TC/SLVT, 25% I/O,
standard ABC, without retiming or false paths and with zero skipped instances.
Final whole-grid controller core/I/O slack is +383.666/+562.947 ps; the grid-slice
controller is +400.788/+560.754 ps. These component results do not qualify a
whole-CU or whole-host clock.

PR preparation preserves the qualified RTL, ABI, driver, build and runtime-test
inputs, with the uncached-vertex fixture correction from PR #2 and the capped-guest
discard oracle correction in this PR. Fresh
compilation discovers 531 suites: 508 scheduled once and 23 existing explicit
skips. Selection checks pass. A fresh default 128-thread barrier regression also
passes with the CI hierarchy, single-compiler and low-memory flags. The twelve-suite fast set also passes all 50 unit checks (66 seconds locally,
with cached models; Scala compilation took 83 seconds). A complete runtime
rerun on the final PR source is pending.

The reproduced capped one-warp guest case also passes after correcting its
expected discard result; texture reads, zero framebuffer writes, exact preserved
background and healthy completion/recovery are checked. Other large profiles
remain part of the separate regression.

Representative checks:

```sh
python3 scripts/test_test_selection.py
sbt -Dopengpu.simHierarchy=GpuComputeUnit -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 'set Test / parallelExecution := false' 'testOnly opengpu.core.GpuComputeUnitBarrierEpochSpec -- -z "full=true"'
```

Full scalar/quad raster timing and its separate comparison repair are outside
this PR. General resource/watchdog contracts, cross-domain coherence, sustained
rates, 720p workloads and H0–H8 completion remain open.

Detailed logs, source manifests, raw reports and unsuccessful/intermediate
attempts are retained locally under `.artifacts/`; they are not repository content.
