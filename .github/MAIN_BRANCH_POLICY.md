# Main branch and manually approved CI

Changes to `main` go through a pull request (MR) and require the `ci-ok` check
from the GitHub Actions app. Protection applies to administrators, requires an
up-to-date branch, and prohibits force pushes and branch deletion. An approving
PR review is not required: the PR author may approve their own CI run instead.

The Scala CI `approval` job uses the protected `ci-approval` environment. GitHub
pauses the run before allocating its runner or running repository code. Open
the latest Scala CI run from the PR's Checks tab, click **Review deployments**,
select **ci-approval**, and click **Approve and deploy**. This authorizes tests;
it does not merge the PR or deploy the GPU project. Review comments such as
“ok” do not approve CI. GitHub does not allow authors to approve their own PR
reviews, which is why CI uses environment approval instead.

The environment must have required reviewers configured (currently
`donaldkuck`) and **Prevent self-review** disabled. New PR commits cancel the
previous run and need fresh approval. Draft PRs do not request approval or run
tests. Closing the PR cancels its previous run. Ordinary PR reviews do not
restart tests or change this manual approval decision.

After approval, host tests, Scala selection/shards and dependency submission
all use the same immutable merge commit. The gate and final job reject a
changed PR head, merge snapshot, base branch, draft or closed PR. They also
fail if the environment has no required reviewers or prevents self-review.
While waiting for approval, the run stays pending instead of reporting an
approval failure. Rejection or cancellation cannot satisfy `ci-ok`, and Scala
may be skipped only when selection is `NONE`. Fork PRs skip dependency
submission because it needs a write token unavailable to fork workflows.

The JSON files describe server configuration; committing them does not apply
it. With Repository Administration permission, apply the environment first:

```sh
gh api --method PUT repos/OpenGPGPU/gpu/environments/ci-approval \
  --input .github/ci-approval-environment.json
```

Keep the environment free of secrets: it approves tests that execute PR code.
The reviewer ID in that file is specific to `donaldkuck`; update it when the
maintainers change. All PR refs must be allowed by its branch policy.

`main-branch-protection.json` keeps PRs required with zero approving reviews
and no latest-push review requirement. This intentionally replaces the former
independent-review policy. Read the existing rule before applying it, preserve
unrelated required checks and push restrictions, and verify both server
configurations through GitHub's API. Configure the protected environment before
pushing the workflow so a newly created unprotected environment cannot run tests.
