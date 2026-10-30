# Agent contribution rules

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

Delete temporary topic branches after their PR is merged. Keep GitHub's
`delete_branch_on_merge` enabled. Delete a leftover remote head only when it
still matches the merged PR head and has no open PR. Preserve unmerged or
unpublished work and branches used by active local worktrees.

When creating a Git commit, include one `Co-authored-by` trailer for the agent. The trailer must identify the agent and include the model name, for example:

```text
Co-authored-by: Codex (GPT-6 sol) <codex@openai.com>
```

Cursor appends `Co-authored-by: Cursor <cursoragent@cursor.com>` on its own. Do not write a second trailer in the commit message. If that automatic line will be present, leave the message without a `Co-authored-by` line so the commit has exactly one trailer.

# Language & Translation Rules

- When the user inputs in Chinese:
  1. First translate the user's message into English and show it to the user.
  2. Then continue executing the task in English.
- When the user inputs in English:
  1. First point out any grammatical errors or non-idiomatic expressions, and provide suggestions for correction.
  2. Then continue executing the task.
- The above "translation/proofreading" step is a mandatory prerequisite for every conversation, unless the user explicitly says "skip".

# Validation documentation

Commit concise final results, relevant limits and reproducible test commands.
Keep raw performance logs, source manifests, emission hashes, pending/resume
records and intermediate qualification reports local under `.artifacts/`.
Do not append running progress diaries or commit generated process data.

# Development test resolution

Routine rendering tests must use frame sizes no larger than 640x480; prefer
smaller images when they cover the behavior. Use `GraphicsTestResolution` for
frame-scale fixtures. Run 1280x720 only as an explicit product qualification
with `-Dopengpu.productQualification=1`, outside the regular PR/nightly lanes.
Keep product RTL capacities and the documented 720p qualification target.
Large numeric/address inputs for bounded arithmetic or metadata boundary tests
are allowed when they do not render or exhaustively traverse a large frame.
