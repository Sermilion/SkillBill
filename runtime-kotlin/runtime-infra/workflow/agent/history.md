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
