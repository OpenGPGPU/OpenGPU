# PR test gates

`Scala CI` requires both bounded smoke tests and tests selected from the PR diff.
`ci-ok` accepts an empty affected-test matrix only when selection explicitly
returns `[]`; a selected job that is skipped, canceled or fails blocks merging.

`scripts/changed_test_suites.py` selects concrete suites in changed Scala test
files, consumers of changed test fixtures (including inherited suites), and
same-path `FooSpec.scala` tests for changed production `Foo.scala` files. It
discovers suite declarations rather than assuming the filename is a suite.
CI plumbing changes run `GpuSimSpec` through the matrix to exercise that gate.

Selected suites run serially, with up to eight jobs in parallel, one C++ compiler,
optimized simulation and cached models. Large graphics host suites and the
persistent shared-system suite are split into partitions of at most eight exact
registered test names, grouped by model configuration for cache reuse. The
grouping also applies to suites containing only two cases: distinct local/global
payload profiles run separately, while a same-model bare/Sv32 pair stays together.
`TypedTileImagePipelineSpec` also partitions by hardware profile. Untagged
cases use its local-payload, ordinary-slot harness defaults; explicit local or
slot labels override them. The current nine cases form two model groups, so
local/global builds can run independently while same-model cases reuse their
cache. Exact-case execution and fresh-report checks still retain every case.
`VertexShaderStageSpec` uses the same exact-case partitioning, retaining all
registered cases in bounded local/global profile groups. It must not run all
vertex models serially in a single affected-test job.
Vertex cases without hardware-profile labels use at most two cases per job:
their individually bounded models otherwise accumulated 28 minutes in one
eight-case partition. Labeled profile groups retain the eight-case limit and
their existing cache namespace. The PR workflow still allows eight parallel jobs.
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
production dependency graph. Broad integration remains in `Scala regression`;
its success on an older revision does not qualify a newer PR. Documentation-only
PRs explicitly run host checks without Scala simulation.

Reproduce selection with `python3 scripts/changed_test_suites.py <changed-path>...`.
Validate the gate with `bash scripts/test_scripts.sh` and
`actionlint .github/workflows/scala_test.yml`.

## Local resource-heavy qualification

`scripts/ci_local_only_suites.txt` lists resource-heavy suites outside both PR
and nightly/manual CI. These are omitted from CI selection, rather than reported
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
`FragmentShaderStageSpec` continues to require product colors, transport,
texture policy, projective sampling, uniforms, cancellation and fault recovery
in CI. Every original case belongs to exactly one suite; no case is skipped.

## Regression partitions

The regression workflow keeps 16 stable buckets for ordinary graphics suites.
Large host suites fan out by registered cases rather than accumulating every
GPU configuration in one job. `CiSuiteCases` discovers ScalaTest registrations
without executing test bodies. The planner retains every selected suite and
case, rejects missing catalogs and duplicate suites, and fails if its 200-job
budget is exceeded. Local `sbt test` registrations are unchanged.

`scripts/run_ci_partition.py` runs whole suites or exact ScalaTest `-t` names.
For case partitions, executed XML names must equal the planned names; all jobs
also require fresh reports, positive counts and zero skipped/failed cases.
Cache prefixes group matching model configurations; each partition gets a
unique save key. Job timeouts remain failure limits, not runtime guarantees.

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
