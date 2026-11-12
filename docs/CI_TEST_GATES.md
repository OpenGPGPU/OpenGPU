# PR test gates

`Scala CI` requires both bounded smoke tests and tests selected from the PR diff.
`ci-ok` accepts an empty affected-test matrix only when selection explicitly
returns `[]`; a selected job that is skipped, canceled or fails blocks merging.
Draft PRs receive the same test feedback as `ci-draft`; that check does not
satisfy the protected `ci-ok` requirement. A ready PR receives `ci-ok` only
after its tests pass and its current head, base and readiness are rechecked.
Changing a PR between draft and ready starts a fresh workflow run.

`scripts/changed_test_suites.py` selects concrete suites in changed Scala test
files, consumers of changed test fixtures (including inherited suites), and
same-path `FooSpec.scala` tests for changed production `Foo.scala` files. It
discovers suite declarations rather than assuming the filename is a suite.
CI plumbing changes run `GpuSimSpec` through the matrix to exercise that gate.

Selected suites run serially, with up to eight jobs in parallel, one C++ compiler,
optimized simulation and cached models. Graphics `HostAxiSpec` suites and the
full GPU host-system AXI suites run locally, because even a small frame elaborates
a full GPU. Changes to the graphics host fixtures select smaller query, feedback,
job-reader and admission tests; host-system changes select register and ownership
tests. These component checks do not prove full rendering integration. Run a
particular local suite with `sbt 'testOnly opengpu.graphics.NativeBinnedGraphicsHostAxiSpec'`.
The five-case `GpuHostAxiSpec` also runs locally; ownership and command-MMIO
component checks remain in CI. Reproduce it with
`sbt 'testOnly opengpu.system.GpuHostAxiSpec'`.
The two-domain image-owner admission simulations also run locally; CI retains
the domain probe, router, feedback admission, bridge and owner elaboration
checks. Reproduce the full simulations with
`sbt 'testOnly opengpu.system.TessellationNativeDomainJobImageAdmissionSpec opengpu.system.TessellationNativeDomainFeedbackAdmissionSpec'`.
Quad sequence and job image-owner admission simulations also remain local;
CI selects their bounded readers, sequencer, input DMA, ABI and elaboration
checks. For example, run the full native job admission with
`sbt 'testOnly opengpu.graphics.tessellation.TessellationNativeQuadJobImageAdmissionSpec'`.
Full GPU system, persistent tile, tile-bin rendering, and vertex/fragment
stage suites are also local qualifications. Their changes select small host,
command, reader, shader ABI, texture-lane, and admission tests. For example,
run `sbt 'testOnly opengpu.graphics.TileBinRenderPipelineSpec'` locally for
full tile-bin rendering coverage. Other selected suites may still use exact-case
partitions grouped by model configuration for cache reuse.
`GpuSimSpec` canary exercises this same case-selection path when CI changes.
The 90-minute
job timeout bounds failure; it is not an expected runtime. Routine rendering
keeps `opengpu.productQualification=0` (at most 640×480).

Successful sbt exit alone is insufficient. The gate checks positive completed
ScalaTest counts, zero failed/canceled/ignored/pending tests, and a fresh XML
report containing executed test cases for every selected suite. Report totals
must match the ScalaTest summary. Each job publishes its result in the Actions
summary; selection also lists the required suites there.

This selector is conservative about fixture references and is not a complete
production dependency graph. Selected broader integration is available through
manual `Scala regression`; its scheduled nightly trigger is disabled. A success
on an older revision does not qualify a newer PR. Documentation-only
PRs explicitly run host checks without Scala simulation.

Reproduce selection with `python3 scripts/changed_test_suites.py <changed-path>...`.
Shader-router changes select `SharedShaderTaskRouterSmallSpec`, which checks
two-CU image and resource ownership plus texture, trap and completion routing.
The broader router suite, including four-CU and extended ownership cases, runs
locally. Reproduce the bounded suite with
`sbt -Dopengpu.simJobs=1 'set Test / parallelExecution := false' 'testOnly opengpu.graphics.SharedShaderTaskRouterSmallSpec'`.
`python3 scripts/test_changed_test_suites.py` reuses an explicit snapshot of
the unchanged repository test tree across its cases; temporary fixture trees
and ordinary selector calls still scan fresh. On one local development machine,
the original 119-case test took 108 seconds; the indexed 121-case test took
about 2 seconds. This is not a CI runtime guarantee.
The shard-selection host test also reuses the per-shard results it already
collected when checking the generated shard matrix, avoiding a second query
for every shard without changing the matrix or dropping assertions. One local
run of `python3 scripts/test_test_selection.py` fell from 133 to 107 seconds;
CI timings can differ.
The shell shard selector loads the local-only catalog once per invocation and
matches exact lines without a process per suite; it also uses shell path
extraction and glob conversion without repeated subprocesses. On the same
machine, the bounded selection test took 34 seconds with these changes. Its
catalog fixture retains more than
64 KiB of declared names and checks exact matches, missing catalogs and
unassigned system suites.
Validate the gate with `bash scripts/test_scripts.sh` and
`actionlint .github/workflows/scala_test.yml`.

Simulation setup bounds apt network requests and retries failed package updates
or installs up to three times. The 2026-10-31 nightly had two jobs spend their
entire three-hour limit waiting for an unreachable Ubuntu mirror; exhausted
retries still fail the job, so a missing toolchain cannot pass the test gate.

The pinned simulator and firtool caches can be warmed once on `main` with
`gh workflow run warm_simulation_cache.yml --ref main`. This manual workflow
only sets up the existing toolchain; it does not run tests or have a schedule.
PRs can restore default-branch caches, whereas a cache created for one PR is
not available to another. If the runner image or tool versions change, or
GitHub evicts the cache, rerun the manual workflow. A cache miss still builds
the toolchain in the ordinary PR gate; no test or protection is bypassed.
See [GitHub's cache scope rules](https://docs.github.com/en/actions/reference/workflows-and-actions/dependency-caching#restrictions-for-accessing-a-cache).

## Local resource-heavy qualification

The opt-in `GpuWorkloadSpec` registers its twelve measured workloads only when
`GPU_BENCHMARK=1`. It belongs in the local catalog; scheduling its empty default
registration in nightly CI fails the execution gate. Run
`python3 scripts/benchmark_gpu.py --output .artifacts/workloads` locally. The
driver enables the workloads, requires all twelve result records and keeps raw
logs, manifests and counters under `.artifacts/`.

`scripts/ci_local_only_suites.txt` lists resource-heavy suites outside PR and
manual CI; graphics `HostAxiSpec` suites are also excluded by name. The legacy
tessellation host compatibility suite inherits the same full host fixture and
is local even though its name does not end in `HostAxiSpec`. These are
omitted from CI selection, rather than reported
as skipped or passed tests. CI success does not qualify these host profiles. Tests that cannot fit hosted-runner
resources after bounded build/partition fixes belong in this catalog, with a
reproducible local command; do not mark them skipped to make CI green.
The four-engine full-task rendering suite keeps both original local/global
cases, all 1x/4x image checks and the one-million-cycle guard. Run it locally:

```sh
sbt -J-Xms512M -J-Xmx6G -J-Xss6M \
  -J-XX:+UseG1GC -J-XX:MinHeapFreeRatio=10 -J-XX:MaxHeapFreeRatio=20 \
  -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  -Dopengpu.simHierarchy=GpuComputeUnit,Fp32PrimitiveRasterEngine,Rgba8BlendFmaEngine \
  -Dopengpu.simReleaseHeap=1 -Dopengpu.simSeparateBuild=1 \
  'testOnly opengpu.graphics.NativeFourEngineDefaultHostAxiSpec'
```

`VertexShaderTextureMatrixSpec` owns the two exhaustive native typed-texture
cases, retaining all format/instruction combinations, faults and original cycle
guards. Each case took over 20 minutes on hosted runners even after partitioning,
so this matrix also runs locally. `VertexShaderStageSpec` retains the other stage
cases, including projective sampling, payload spill, uniforms and fault recovery;
new product-upload cases use that ordinary suite as well. No test is ignored or
marked pending. Run the full matrix explicitly:

```sh
sbt -J-Xms512M -J-Xmx6G -J-Xss6M -Dopengpu.productQualification=0 \
  -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  -Dopengpu.simHierarchy=GpuComputeUnit \
  'testOnly opengpu.graphics.VertexShaderTextureMatrixSpec'
```

`FragmentShaderTextureMatrixSpec` likewise owns the two unchanged exhaustive
fragment format/instruction sweeps. The spilled sweep took 13m24s on a hosted
runner; combining it with seven other native cases made that partition exceed
21 minutes. Run both payload modes locally with the same command above, replacing
the suite with `opengpu.graphics.FragmentShaderTextureMatrixSpec`.
`FragmentShaderStageSpec` keeps the real shared-CU fragment transport smoke in
both local-payload and global-memory modes, plus geometry metadata delivery,
in routine CI. The broader
`FragmentShaderStageQualificationSpec` runs all stage cases locally, including
product colors, texture policy, uniforms, cancellation and fault recovery. A
fragment-only PR previously produced 17 affected-test jobs and took more than
20 minutes; the bounded suite keeps executable coverage without that fan-out.
Run the full stage qualification with the command above, replacing the suite
with `opengpu.graphics.FragmentShaderStageQualificationSpec`. No selected CI
test is ignored or pending.

Nine binned integration cases live in `BinnedTileResourceQualificationSpec`:
three four-CU/16-lane/eight-warp ownership/image cases and all six native FP32
texture payload modes. The ownership/image cases took 47–51 minutes each on
hosted runners; the full-capacity texture case took one hour before the next
elaboration exhausted its 6 GiB JVM heap. Even the smaller two-CU resident
texture case took 44 minutes, and its four-mode partition timed out at 75
minutes. These tests therefore run locally rather than extending hosted limits.
All 24 original cases remain: 15 ordinary binned host cases stay in CI, and nine
resource-heavy cases retain their original bodies and assertions in the local
suite. The ordinary cases still check ownership, images, payload residency,
spills, faults, cancellation and reuse; native texture stage checks also remain
in CI. CI does not qualify the local host texture integrations or full capacity.

```sh
sbt -J-Xmx10G -Dopengpu.productQualification=0 \
  -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  -Dopengpu.simHierarchy=GpuComputeUnit -Dopengpu.simReleaseHeap=1 \
  -Dopengpu.simSeparateBuild=1 \
  'testOnly opengpu.graphics.BinnedTileResourceQualificationSpec'
```

## Regression partitions

The regression workflow keeps 16 stable buckets for ordinary graphics suites.
Large host suites fan out by registered cases rather than accumulating every
GPU configuration in one job. `CiSuiteCases` discovers ScalaTest registrations
without executing test bodies. The planner retains every selected suite and
case and rejects missing catalogs and duplicate suites. PR plans retain a
256-job matrix limit. Full regression plans use up to two sequential matrices
of 256 jobs each; the second runs even if the first fails, and the final gate
requires both selected batches to succeed. Each batch uses at most nine runners, leaving room for the PR lane and the
nightly driver and dependency jobs within the 20-job account cap. Plans beyond 512 jobs fail
closed. Texture, wide-graphics, and host shards normally run one
concrete suite per job, so a new class there becomes its own runner. An ordinary
graphics shard holds at most twelve whole suites and spills the rest into
another job with the same simulator flags. A new host AXI case past the
three-case group spills the same way. Local `sbt test` registrations are
unchanged.

Every `ProgrammableTexture` / Mesa pixel-format AXI suite separates its
fragment, vertex and CPU-staging models the same way
`CoalescedParallelCuProgrammableTextureAxiSpec` does. A cold fragment build and
a cold vertex build each take about twenty minutes; keeping both in one job
timed out the 75-minute PR step after every shorter scene had already passed.
Runtime-only scenarios stay with their shared model. Each model group is
bounded at 24 cases; larger groups spill, 16x16 guest-sized extents separate,
and unfamiliar case names get independent jobs. Both PR and regression plans
retain every case. The jobs share the suite cache namespace so content-validated
models remain reusable. No assertion, resolution, timeout or resource cap
changes. Validate the scheduling contract with
`python3 scripts/test_ci_case_partitions.py`.

`scripts/run_ci_partition.py` runs whole suites or exact ScalaTest `-t` names.
For case partitions, executed XML names must equal the planned names; all jobs
also require fresh reports, positive counts and zero skipped/failed cases.
`GpuSystemSpec` cases are grouped by their known elaborated model; unfamiliar
cases receive their own partition. Its 20 currently registered cases are
assigned exactly once across ten model groups. Cache prefixes group matching
model configurations; each partition gets a unique save key. Job timeouts remain failure limits, not runtime guarantees.

Affected-test jobs also restore a 512 MB compiler-object cache, preferring the
same suite and falling back to the same runner/Verilator toolchain. New objects
are saved under a unique partition/run key when compilation records misses,
including after a failed test if the runner remains available. Cache counters
appear in the job summary. Restored objects still require model linking and
every planned test must execute and pass the fresh-result checks.

The partition runner reports a heartbeat every 60 seconds while sbt is active:
elapsed time, observed process names, summed process-tree RSS and free disk.
RSS can count shared pages more than once; it is not total system memory use.
The largest RSS consumers are listed by command; Linux also reports system
available/total memory separately. Affected jobs retain a 6 GiB maximum heap for
elaboration. `opengpu.simReleaseHeap=1` requests collection through the current
JDK's `jcmd` immediately before a native build; G1 heap-free ratios return unused
committed heap so Verilator and the single C++ compiler have more headroom.
Cache hits do not need this collection. The default does not request it, and an
explicit request fails if the current JDK cannot perform the collection.
G1 resizes during full collection, as described in the
[JDK 11 G1 guide](https://docs.oracle.com/en/java/javase/11/gctuning/garbage-first-g1-garbage-collector3.html).
Local probes retaining each exact four-CU fixture released about 2.3 GiB of
committed heap after elaboration. Five simulation controls pass with collection,
including hierarchy/object caching; denying attachment rejects the build.
These checks establish collection behavior, not a hosted-runner memory bound.
Affected jobs also set `opengpu.simSeparateBuild=1`: Verilator finishes all
hierarchical code generation before GNU make starts native compilation. This
prevents Verilator's retained design from overlapping the C++ compiler's peak
memory. It removes only `--build` from generation and invokes the generated
model makefile with the requested job limit; RTL, assertions, compiler flags and
simulation steps are unchanged. Generation or compilation failure fails the
partition. This follows the [Verilator hierarchical build flow](https://verilator.org/guide/latest/verilating.html#hierarchical-verilation).
Low-memory builds explicitly set `--output-groups 0`. Verilator 5.050 otherwise
derives grouping from `--build-jobs`; a one-job build recombines split files into
one large compilation unit. Disabling regrouping keeps the split sources separate while compilation remains
serial, retaining `-O1` in CI. See the [5.050 grouping option](https://github.com/verilator/verilator/blob/v5.050/docs/guide/exe_verilator.rst).
Five flat and five hierarchical simulator controls pass with regrouping disabled;
a bounded generation comparison preserves all 265 underlying C++ files exactly.
Five flat and five hierarchical controls pass, including cached assertions;
five more pass in a fresh JVM using compiler objects. Reproduce with
`-Dopengpu.simSeparateBuild=1 -Dopengpu.simHierarchy=Counter,AssertionSentinel`
and `testOnly opengpu.testutil.GpuSimSpec`, optionally with
`-Dopengpu.simCcache=target/ccache -Dopengpu.simCache=off`.
These observations do not estimate completion or impose another timeout.
Child exit status, exact case selection and fresh XML validation remain required.
Exercise quiet-child output, nonzero exits and stale-report rejection with
`python3 scripts/test_run_ci_partition.py` (also in `test_scripts.sh`).

Manual regression dispatch accepts `full` (default) or `graphics`. The scope is
visible in the run name and concurrency group. A graphics-only success cannot
satisfy scheduled full-regression deduplication, which only considers successful
scheduled runs on the same revision.

Reproduce the graphics plan with:

```sh
python3 scripts/ci_case_partitions.py graph ALL --scope graphics > /tmp/selection.json
python3 scripts/ci_case_partitions.py candidates /tmp/selection.json > /tmp/suites.txt
sbt "Test / runMain opengpu.testutil.CiSuiteCases /tmp/cases.tsv $(cat /tmp/suites.txt)"
python3 scripts/ci_case_partitions.py plan /tmp/selection.json /tmp/cases.tsv
```

Generated catalogs, plans and simulation logs are local artifacts, not committed
qualification results. Full regression completion requires actual successful
jobs on the revision being qualified.
