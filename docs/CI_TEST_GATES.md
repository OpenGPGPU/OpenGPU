# PR test gates

`Scala CI` requires both bounded smoke tests and tests selected from the PR diff.
`ci-ok` accepts an empty affected-test matrix only when selection explicitly
returns `[]`; a selected job that is skipped, canceled or fails blocks merging.

`scripts/changed_test_suites.py` selects concrete suites in changed Scala test
files, consumers of changed test fixtures (including inherited suites), and
same-path `FooSpec.scala` tests for changed production `Foo.scala` files. It
discovers suite declarations rather than assuming the filename is a suite.
CI plumbing changes run `GpuSimSpec` through the matrix to exercise that gate.

Selected suites run serially, with up to four jobs in parallel, one C++ compiler,
optimized simulation and cached models. Large graphics host suites and the
persistent shared-system suite are split into partitions of at most two exact
registered test names, grouped by model configuration for cache reuse. The
grouping also applies to suites containing only two cases: distinct local/global
payload profiles run separately, while a same-model bare/Sv32 pair stays together.
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
