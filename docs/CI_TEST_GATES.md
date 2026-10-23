# PR test gates

`Scala CI` requires both bounded smoke tests and tests selected from the PR diff.
`ci-ok` accepts an empty affected-test matrix only when selection explicitly
returns `[]`; a selected job that is skipped, canceled or fails blocks merging.

`scripts/changed_test_suites.py` selects concrete suites in changed Scala test
files, consumers of changed test fixtures (including inherited suites), and
same-path `FooSpec.scala` tests for changed production `Foo.scala` files. It
discovers suite declarations rather than assuming the filename is a suite.
CI plumbing changes run `GpuSimSpec` through the matrix to exercise that gate.

Each selected suite runs serially in its own job, with up to four jobs in
parallel, one C++ compiler, optimized simulation and cached models. The 90-minute
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
