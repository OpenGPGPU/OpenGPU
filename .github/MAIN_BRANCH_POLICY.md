# Main branch and review-gated CI

Changes to `main` go through a pull request (MR). Branch protection requires
an approving review and the `ci-ok` check, applies to administrators, and allows
no review bypass actors, force pushes or branch deletion. New commits dismiss
stale approvals; the latest push must be approved by someone else. The branch
must be up to date before merging.

The Scala CI workflow runs its metadata gate when a PR opens or changes, and
when a review is submitted or dismissed. Host tests, Scala selection/shards,
and dependency submission wait for a reviewer with write/maintain/admin access
to approve the latest PR head. A draft PR or an outstanding authorized
changes-requested review does not unlock tests.

All test jobs check out the same immutable merge commit selected by the gate.
A changed head, changed merge snapshot, withdrawn approval or new blocking
review prevents the final required check from passing. PR updates/reviews
cancel the previous run. The final job checks the current approval again.

Before approval, `ci-ok` reports that review is required; it deliberately does
not turn skipped tests into a successful required check. The only jobs that
run at that point query PR metadata. There is no push-triggered test run.
Fork PRs run the same approved tests but skip dependency submission, which
requires a write token unavailable to fork workflows.

`main-branch-protection.json` describes the requested minimum GitHub rule.
Repository Administration permission is needed to apply it. Read the existing
rule first and preserve any stronger review counts, required checks, code-owner
requirements and push restrictions. The JSON file alone does not configure the
server; verify the applied rule through GitHub's branch-protection API.
