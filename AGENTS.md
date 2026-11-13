# Agent contribution rules

These contribution rules apply to the GPU repository only. Do not impose its
PR, CI or branch-protection requirements on other repositories, including
worktrees checked out under this repository's `.artifacts/` directory.

Create temporary worktrees and experimental checkouts for GPU tasks only under
this repository's `.artifacts/worktrees/` directory. Do not create `gpu-*` or
`FlashSim-*` sibling workspaces under `~/workspace`. Keep independent
repositories' primary directories in place; their location does not subject
them to the GPU PR rules. Remove ended outputs and temporary worktrees under
the cleanup rules below.

All changes must be submitted through a pull request. Work and create commits
on topic branches; never commit, merge, or push directly to `main` or `master`.
Keep existing unpublished work intact and submit it from a topic branch.
Required CI must pass before a PR is merged. Do not bypass branch protection.

Keep at most two pull requests open at once, including drafts. Finish and merge
ready work before opening another PR. Prefer logical commits on an existing
topic branch over additional stacked PRs. Close superseded or deferred PRs while
preserving all unmerged source branches; reopen an existing PR when it is ready
and a slot is available.

After implementation and relevant local validation pass, automatically mark a
Codex PR ready for review (or create it without draft mode) and enable GitHub
auto-merge with the rebase method. The user has authorized this workflow; do not
ask for another merge confirmation. Preserve logical commits; do not squash.
GitHub must still require the current protected `ci-ok` check and an up-to-date
branch. Update the topic branch when needed; never bypass protection or push to
`main`/`master`. Keep genuinely unfinished implementation in draft mode.

Remove task-specific build outputs, simulation data, caches and logs as soon as
their development, validation or diagnosis use ends; do not wait for PR merge.
Verify no active process or other worktree needs the data. Preserve unpublished
source changes, shared tools or artifacts still in use, and data the user
explicitly wants kept. An unmerged PR alone is not a reason to retain generated
process data.
Check symbolic links and cross-worktree artifact references before deleting
shared outputs. An empty open-file listing alone does not establish that an
artifact is unused; a later verification step may still need it.

After a PR merges, promptly clean up its temporary local worktrees and local
topic branches. Verify the worktree is clean and no active process needs it,
and the local branch still matches the merged PR head before deleting it.
Preserve unmerged or unpublished source work. Never remove the main workspace
as part of this cleanup.

Keep GitHub's `delete_branch_on_merge` enabled. Delete a leftover remote head
only when it still matches the merged PR head and has no open PR. Preserve
branches used by active local worktrees.

# Validation documentation

Commit source changes, concise final results, relevant limits and reproducible
test commands. Do not retain or archive raw evidence or process data by default:
this includes performance logs, source manifests, emission hashes, pending/resume
records, intermediate qualification reports, evidence snapshots and duplicate
generated models. Use `.artifacts/` only for temporary files needed by active
work, then delete them when that use ends, following the cleanup rules above.
Do not append running progress diaries or commit generated process data or
frozen evidence JSONs (formerly `docs/performance/`). Documentation records only
final results, limits and reproduction commands; keep raw data only when the
user explicitly requests it.
