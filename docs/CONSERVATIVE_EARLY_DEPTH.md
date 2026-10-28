# Conservative early depth rejection

`ConservativeD24DepthSummary` tracks an interval containing every current D24
sample in a resident depth plane. A clear initializes a uniform interval. An
unknown binding needs complete observed word coverage before becoming valid.
Write inputs immediately withhold the bounds while two observation stages
drain. The interval expands for every observed write; tightening requires a complete
coverage epoch. Repeated writes retain all observed values in that epoch, so
overwrites and independently reordered ports remain conservative. The observer
accepts writes every clock without controlling SRAM readiness. Binding or
invalidation flushes both observation stages before a new generation can be trusted.

The native shared store observes full, irrevocable depth writes at the canonical
SRAM request ports. These taps do not control producer readiness. Generic depth
traffic, generic color ownership, maintenance writes, cancellation and faults
invalidate the interval. A later drained native bind starts unknown; it cannot
resurrect the original clear after a transfer or generic update. A new pass
binds a new summary even when it reuses the same physical plane.

Local validation covers the full 512-word plane, duplicated/reordered two-port
writes against actual resident values, native write observations, transfer
invalidation, partial recovery, cold loads, pending observation flush and generation reuse. All seven cases
in these two suites pass. Existing canonical-image, cancellation and MSAA
1x/2x/4x store regressions retain their assertions and four-sample steady rate.

```sh
sbt -J-Xmx3G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  'testOnly opengpu.graphics.ConservativeD24DepthSummarySpec opengpu.graphics.SharedTypedSampleTileStoreSpec'
```

The resident bounds reach the fragment shader owner through the typed tile
renderer. `ConservativeD24EarlyDepthTest` converts all covered per-sample depths
with the same exact RNE rule as late D24 testing. Its four-clock pipeline accepts
one vector per clock. Rejection requires every covered depth to fail against
every resident value in the interval; empty coverage or unknown bounds stay late.

`FragmentShaderDraw.earlyDepthSafe` is an explicit compiler/draw promise: the
shader cannot discard, override depth, perform user-visible memory/atomic side
effects, or require invocation-sensitive query semantics. Its GFS word is 12;
its native GTD word is 543. All legacy offsets remain unchanged, and the legacy
zero value retains late execution. `allowDepthOverride` also disables skipping.
The caller must withhold the promise for unknown shader effects or incompatible
queries; the hardware does not infer shader semantics from its PC.

Only a whole quad at an empty batch can bypass shader dispatch. It allocates no
payload and launches no CU work, while retaining metadata, helpers, coverage
and raster depths for normal late depth/stencil retirement. Stencil operations
and visibility/sample accounting therefore remain late. Older in-flight writes
must preserve rejection: LESS/LEQUAL only decrease depth, GREATER/GEQUAL only
increase it, EQUAL preserves it, and NEVER writes no depth. NOTEQUAL with depth
writes stays late. Eligible/rejected counters accompany actual shader launch
and shaded-lane counts.

Five additional bounded tests pass: 640 numeric vectors across all eight
comparison functions, shader bypass/EOF/held-export cancellation, conservative
fallback admission, and both ABI readers including noncanonical Boolean padding.

```sh
sbt -J-Xmx3G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  'testOnly opengpu.graphics.ConservativeD24EarlyDepthTestSpec opengpu.graphics.EarlyDepthFragmentShaderStageSpec'
sbt -J-Xmx3G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  'testOnly opengpu.graphics.TypedFragmentStateReaderSpec opengpu.graphics.NativeTypedGraphicsJobReaderSpec -- -z "early depth"'
```

The real-CU rendering qualification runs locally, outside PR/nightly budgets.
One unchanged pipeline executes early-disabled and early-enabled 8x8 passes at
1x and 4x MSAA. Six draws per pass cover a full occluder, depth failure with
stencil updates, discard, depth override, a global-memory side effect and partial
coverage/helper lanes. Canonical color/depth/stencil words and logical sample
retirements match. Each full occluded draw removes eight actual shader launches
and 64 shaded lanes; unsafe shaders still execute. Uniform tails use immutable
scratch views per draw/pass rather than direct mutation of cached backing data.

```sh
sbt -J-Xmx3G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.simHierarchy=GpuComputeUnit,Fp32PrimitiveRasterEngine,Rgba8BlendFmaEngine \
  -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 -Dopengpu.simSeparateBuild=1 \
  -Dopengpu.simReleaseHeap=1 -Dopengpu.productQualification=0 \
  -Dopengpu.earlyDepthQualification=1 \
  'testOnly opengpu.graphics.EarlyDepthRenderQualificationSpec'
```

Complete RTL emission targets are `mobile-conservative-d24-depth-summary`,
`mobile-conservative-d24-early-depth-test`,
`mobile-native-early-depth-fragment-shader` and the existing
`mobile-native-typed-pixel-owner`. Both isolated depth components pass complete
1000 ps TC/SLVT synthesis with 25% I/O budgets, standard ABC, preserved hierarchy
and no skipped logic, retiming, false paths or memory mocks:

| Component | Core / I/O setup margin (ps) |
| --- | ---: |
| Pipelined 512-word depth summary | +498.018 / +407.981 |
| Four-clock numeric early-depth test | +468.416 / +437.398 |

Integrated pixel/fragment timing remains unqualified, so H3
remains open. These local semantics do not establish a whole-GPU clock, full
native resource qualification or a product frame rate.
