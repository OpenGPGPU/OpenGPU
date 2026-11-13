# Agent contribution rules

These contribution rules apply to the GPU repository only. Do not impose its
PR, CI or branch-protection requirements on other repositories, including
worktrees checked out under this repository's `.artifacts/` directory.

All changes must be submitted through a pull request. Work and create commits
on topic branches; never commit, merge, or push directly to `main` or `master`.
Keep existing unpublished work intact and submit it from a topic branch.
Required CI must pass before a PR is merged. Do not bypass branch protection.

After implementation and relevant local validation pass, automatically mark a
Codex PR ready for review (or create it without draft mode) and enable GitHub
auto-merge with the rebase method. The user has authorized this workflow; do not
ask for another merge confirmation. Preserve logical commits; do not squash.
GitHub must still require the current protected `ci-ok` check and an up-to-date
branch. Update the topic branch when needed; never bypass protection or push to
`main`/`master`. Keep genuinely unfinished implementation in draft mode.

After a PR merges, promptly clean up its temporary local worktrees, local topic
branches and task-specific build outputs, simulation data, caches and logs so
completed work does not fill the disk. Verify the worktree is clean, no active
process or other worktree needs the data, and the local branch still matches
the merged PR head before deleting it. Preserve unmerged or unpublished work,
shared tools or artifacts still in use, and data the user explicitly wants kept.
Never remove the main workspace as part of this cleanup.

Keep GitHub's `delete_branch_on_merge` enabled. Delete a leftover remote head
only when it still matches the merged PR head and has no open PR. Preserve
branches used by active local worktrees.

# Validation documentation

Commit concise final results, relevant limits and reproducible test commands.
During active work, keep raw performance logs, source manifests, emission hashes,
pending/resume records and intermediate qualification reports local under
`.artifacts/`. After merge, remove task-specific data when it is no longer needed,
following the cleanup rules above. Do not append running progress diaries or
commit generated process data. Frozen evidence JSONs (formerly
`docs/performance/`) are not committed; docs record only final results,
limits and reproduction commands.

# Development test resolution

Routine rendering tests must use frame sizes no larger than 640x480; prefer
smaller images when they cover the behavior. Use `GraphicsTestResolution` for
frame-scale fixtures. Run 1280x720 only as an explicit extended qualification
with `-Dopengpu.productQualification=1`, outside the regular PR/nightly lanes.
Keep supported RTL capacities. The mandatory GPU alignment product target is
2 CUs at 640x480 and 60 FPS; 720p and four-CU profiles are optional extensions.
Large numeric/address inputs for bounded arithmetic or metadata boundary tests
are allowed when they do not render or exhaustively traverse a large frame.
