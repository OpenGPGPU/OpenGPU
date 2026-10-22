# Main branch policy

Changes to `main` require a PR and the current `ci-ok` check from GitHub Actions.
Protection includes administrators, requires an up-to-date branch, and blocks
force pushes and deletion. Work and commit on topic branches only.

Open, non-draft PRs run host checks, compile all main/test Scala sources, and run
an affected subset of eight bounded unit suites in `scripts/ci_pr_suites.txt`.
The PR lane uses one runner with a 15-minute timeout. Documentation-only changes
run host checks. New commits cancel superseded runs; `ci-ok` rejects stale heads,
drafts, changed base branches and failed/canceled checks.

`Scala regression` retains the broad integration matrix separately. It runs
nightly, skipping an exact revision already covered by a successful regression,
and can be run manually through Actions on a selected ref. Its matrix allows
eight concurrent runners. Whole-CU/host, native graphics, configuration sweeps
and dependency submission do not block routine PR feedback. A green `ci-ok`
means the fast gate passed; it does not mean the broad regression passed.
Existing desktop exclusions in the CI catalog remain unchanged. Use the broad
regression for integration/release qualification.

Scala compilation and content-addressed simulation models are cached. Simulation
cache snapshots get a new outer key per run, so newly built models are saved
after restoring an older cache. Per-run simulation workdirs are excluded.

`main-branch-protection.json` preserves PRs with zero required approving reviews.
Read the current rule before applying it and preserve unrelated restrictions.
