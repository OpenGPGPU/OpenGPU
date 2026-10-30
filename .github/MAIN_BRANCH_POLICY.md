# Main branch policy

Changes to `main` require a PR and the current `ci-ok` check from GitHub Actions.
Protection includes administrators, requires an up-to-date branch, and blocks
force pushes and deletion. Work and commit on topic branches only.

Codex PRs automatically move to ready after implementation and relevant local
validation pass, then enable GitHub auto-merge using **rebase**. This preserves
the logical commits. No additional user merge confirmation is required. GitHub
performs the merge only when the protected current-head checks and branch
requirements pass. Incomplete implementation remains draft; failing checks
block merging. The agent updates an outdated topic branch rather than bypassing
protection. Repository auto-merge must remain enabled.

Keep automatic deletion of merged PR branches enabled. Clean up leftover
temporary remote heads only after verifying they still match their merged PR
and have no open PR. Preserve unmerged work and active local worktrees.

Open PRs, including drafts, run host checks, compile all main/test Scala sources,
and run an affected subset of nineteen bounded unit suites in `scripts/ci_pr_suites.txt`.
The PR lane uses one runner with a 15-minute timeout. Documentation-only changes
run host checks. Code changes with no affected suite in that catalog run the
bounded catalog as smoke coverage. A code PR must finish a nonzero ScalaTest run;
the Actions summary records its suites and test count. This does not establish
integration coverage for the changed component.

Draft PRs receive test feedback, but `ci-ok` rejects them until ready. New commits
cancel superseded runs; `ci-ok` rejects stale heads, drafts, changed base
branches and failed/canceled/skipped checks. Closing or merging a PR does not
start another CI run, so a redundant skipped run cannot obscure the real result.

`Scala regression` retains the broad integration matrix separately. It runs
nightly, skipping an exact revision already covered by a successful regression,
and can be run manually through Actions on a selected ref. Its matrix allows
sixteen concurrent runners, leaving the account's twenty-job cap room for the
eight-runner pull-request lane. Whole-CU/host, native graphics, configuration sweeps
and dependency submission do not block routine PR feedback. A green `ci-ok`
means the fast gate passed; it does not mean the broad regression passed.
Existing desktop exclusions in the CI catalog remain unchanged. Use the broad
regression for integration/release qualification.

Scala compilation and content-addressed simulation models are cached. Simulation
cache snapshots get a new outer key per run, so newly built models are saved
after restoring an older cache. Per-run simulation workdirs are excluded.

`main-branch-protection.json` preserves PRs with zero required approving reviews.
Read the current rule before applying it and preserve unrelated restrictions.
