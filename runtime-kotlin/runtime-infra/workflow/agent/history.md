## [2026-10-24] SKILL-420 subtask 3: invocation-owned QA environment resources
Areas: runtime-infra/workflow (workflow.qa), runtime-ports (qa), runtime-contracts (qa, featuretask error codes), runtime-core (di), runtime-engine (QA lifecycle, QA prompt), runtime-cli (qa), docs
- New QaOwnedResourcePort (register, release, releaseAllFor, reclaimAbandoned) with a file-backed adapter that keeps one record per resource under the QA root resources directory, next to the existing leases directory; invocation ids named leases or resources are now reserved.
- Six resource kinds (process, directory, docker container, network, volume, compose project) with per-kind identity validation; Docker names must equal the invocation environment name or extend it with a separator, so a run can only claim and tear down its own names.
- Teardown runs through a command-runner seam with fixed argv templates and deadlines; order is process, compose project, container, network, volume, directory. A record is deleted only after teardown succeeded or found the resource gone.
- reclaimAbandoned runs on every non-OFF QA run before target resolution and reaps only groups whose owner is dead or missing; live or unconfirmed owners are left alone.
- QaExecutionLifecycle closes resources as the innermost owner, surfacing failed teardown as retained resources while the primary execute exception stays primary.
- CLI `qa resource register|release`, a QA_OWNED_RESOURCE_FAILED failure code, qa_cleanup and qa_reconcile diagnostics, and an environment-resources block in the QA prompt tell agents to register every resource they start (reusable seam for later QA subtasks).
- Limitation: the Docker smoke test skips when Docker is absent, so real compose teardown is proven only on a Docker runner. Unrecorded Docker names sharing a prefix across nested invocation ids are not covered by the conflict check. Retained-report guidance text still mentions only leases; report vocabulary belongs to subtask 5.
Feature flag: N/A
Acceptance criteria: 4/4 implemented

## [2026-10-01] SKILL-396 subtask 2: workflow git and GitHub package consolidation
Areas: runtime-infra/workflow, runtime-core (di/core, di/operation), runtime-engine tests, runtime-cli tests, runtime-mcp tests
- Import-only move (finding F-006): GitWorkflowGitOperations, its Fingerprint helper and GitReadinessTreeIdentityOperations moved from git/workflow/ up into git/ (package skillbill.infrastructure.workflow.git).
- GhCommandRunner, GhGoalPullRequestPort, GhPullRequestIdentityLookup (from git/goal/) and GhPullRequestReviewThreads (from git/github/) now live in workflow/github/ (package skillbill.infrastructure.workflow.github), so gh-CLI code sits in one package outside git/.
- Moves done with git mv so history follows; 7 git/workflow tests moved to test git/, two Gh tests moved to test workflow/github/. Consumer imports rewritten in workflow, runtime-core, engine, cli and mcp; same-package imports dropped.
- Follows the rule of keeping package names equal to directory layout and splitting git plumbing from GitHub API access; no behavior change, no new tests, no baseline or repoTest guard edits.
- Validate-phase repairs of subtask 1 landed in the same tree: sqlite telemetry reconciler overload removed, GoalIssueIdentity moved into telemetry/goal, externaladdon config parsing file renamed ExternalAddonSourceEntries.kt.
- Limitation: empty leftover source directories (git/workflow, git/github, git/goal) may remain on disk; git ignores them. `npx agnix --strict` was not run headless; CI should run it.
Feature flag: N/A
Acceptance criteria: 6/6 implemented
