# SKILL-396 Subtask 2 - Workflow git and GitHub package consolidation

Parent spec: [.feature-specs/SKILL-396-runtime-infra-boundary-repairs/spec.md](./spec.md)
Issue key: SKILL-396

## Scope

F-006, mechanical only.
- Move GitReadinessTreeIdentityOperations.kt, GitWorkflowGitOperations.kt and GitWorkflowGitOperationsFingerprint.kt from skillbill.infrastructure.workflow.git.workflow to skillbill.infrastructure.workflow.git.
- Move GhCommandRunner.kt, GhGoalPullRequestPort.kt and GhPullRequestIdentityLookup.kt (from workflow.git.goal) and GhPullRequestReviewThreads.kt (from workflow.git.github) into skillbill.infrastructure.workflow.github, beside GitHubPullRequestCheckDiscovery.kt.
- Move the matching test files to mirror the new packages.
- Rewrite imports in every consumer: workflow, engine test, core main and test, cli test and repoTest, mcp test.
- No declaration, signature, visibility or behaviour change.

## Acceptance Criteria

1. No .kt file under runtime-kotlin declares or imports a package starting with skillbill.infrastructure.workflow.git.workflow.
2. No .kt file under runtime-kotlin declares or imports package skillbill.infrastructure.workflow.git.github.
3. GitReadinessTreeIdentityOperations.kt, GitWorkflowGitOperations.kt and GitWorkflowGitOperationsFingerprint.kt declare package skillbill.infrastructure.workflow.git.
4. GhCommandRunner.kt, GhGoalPullRequestPort.kt, GhPullRequestIdentityLookup.kt and GhPullRequestReviewThreads.kt declare package skillbill.infrastructure.workflow.github, and no file in skillbill.infrastructure.workflow.git.goal references GhCommandRunner.
5. GhPullRequestReviewThreadsTest and GhPullRequestIdentityLookupTest declare package skillbill.infrastructure.workflow.github.
6. The moved declarations keep their names, visibility and bodies, apart from package and import lines.
7. No file under runtime-kotlin/runtime-core/src/repoTest/kotlin/skillbill/architecture/baselines gained a row.

## Non-Goals

- Renaming classes or changing GhCommandRunner visibility or its test seam.
- Moving any other workflow package.
- Behaviour or wire changes.

## Dependency Notes

Depends on: 1
Depends on subtask 1. Consumers overlap SKILL-390 (engine test, 11 files), SKILL-392 (cli test and repoTest), SKILL-395 (mcp test) and SKILL-389/388 (core main). It waits for no other issue: move the files present and rewrite the import lines in every consumer present when it runs. Whichever bundle lands second keeps the other's edits.

## Validation Strategy

Goal build gate: compile all runtime-kotlin modules and run the workflow, engine, core, cli and mcp tests, the repoTest acyclicity guard and spotless. There is no new test because the change is import-only.

## Next Path

skill-bill goal SKILL-396

## Spec Path

.feature-specs/SKILL-396-runtime-infra-boundary-repairs/spec_subtask_2_workflow-git-and-github-package-consolidation.md
