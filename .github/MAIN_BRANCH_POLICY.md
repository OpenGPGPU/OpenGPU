# Main branch policy

Changes to `main` go through a pull request (MR) and require the `ci-ok` check
from the GitHub Actions app. Protection applies to administrators, requires an
up-to-date branch, and prohibits force pushes and branch deletion. No manual
approval is needed: on every open, non-draft PR to `main`, the Scala CI workflow
runs host tests, test selection/shards, and dependency submission automatically
against the PR's immutable merge commit.

New PR commits cancel the in-progress run and trigger a fresh run. Draft PRs do
not run tests; mark them ready for review to start CI. Closing the PR cancels
its previous run. The `ci-ok` job rejects a changed PR head, base branch, draft
or closed PR: a run that started before a push fails once a newer run is
pending. Scala may be skipped only when selection is `NONE`. Fork PRs skip
dependency submission because it needs a write token unavailable to fork
workflows.

`main-branch-protection.json` keeps PRs required with zero approving reviews
and no latest-push review requirement. Read the existing rule before applying
it, preserve unrelated required checks and push restrictions, and verify the
server configuration through GitHub's API.
