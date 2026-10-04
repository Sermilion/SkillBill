# SKILL-396 - runtime-infra-boundary-repairs

## Mode

decomposed

## Intended Outcome

runtime-infra keeps the structure SKILL-376 left. The git sessions are gone, ambient time is absent from main, test packages mirror main, and the package-acyclicity guard is live. The investigation (`investigation.md`, baseline `ae23f4f28`) found one correctness regression and a few moved-not-removed leftovers:

- F-003 (P1): the SQLite adapter resolves `RuntimeDiagnostics` through a process-global registry keyed by `Connection`, with a silent no-op fallback, so the review-stats path drops its degradation records today.
- F-001 (P2): the runtime-infra/contracts test source set still depends on runtime-application.
- F-002 (P2): `InfrastructureSkillsImportDirectionArchitectureTest` is vacuous.
- F-004 (P2): the two external add-on source readers duplicate each other.
- F-005 (P2): the goal-planning status policy `planningStatusSnapshot` lives in the SQLite adapter.
- F-006 (P3): the workflow `git.workflow` package stutters, and gh adapters have three homes.

Every fix removes code or moves it to its owner. Nothing adds a layer, module, guard class or baseline row, and wire and persisted output stay byte-identical.

## Subtasks

1. `spec_subtask_1_infra-boundary-and-diagnostics-repairs.md`: F-001 to F-005 (semantic).
2. `spec_subtask_2_workflow-git-and-github-package-consolidation.md`: F-006 (mechanical package moves). Depends on subtask 1.

Split reason: subtask 2 is an import-only move across about 31 files, kept apart so it does not bury the semantic diagnostics change.

## Acceptance Criteria

The subtask specs hold the checkable criteria. The feature is done when all of them hold:

1. runtime-infra/contracts declares no runtime-application dependency, and no Kotlin import or qualified code reference under runtime-infra names `skillbill.application.*` (subtask 1 AC 1-3). String literals such as sample stack-trace text in test fixtures do not count.
2. `InfrastructureSkillsImportDirectionArchitectureTest.kt` is gone, and no new `*ArchitectureTest.kt` exists (subtask 1 AC 4).
3. SQLite main has no connection-keyed diagnostics registry and no defaulted `RuntimeDiagnostics` parameter. Diagnostics reach the review, workflow-stats, telemetry, workflow-state and migration code through constructors or arguments, and one sqlite test proves a review-stats degradation record reaches the session diagnostics (subtask 1 AC 5-10).
4. `FileExternalAgentAddonSourceConfigStore` lives in `skillbill.infrastructure.skills.externaladdon` with one shared `resolveSourcePath`, neither store calls `System.getProperty`, and the two ambient-environment baseline rows are removed (subtask 1 AC 11-12).
5. `planningStatusSnapshot` is declared in runtime-domain `skillbill.goalrunner.model`, and `GoalPlanningStatusProjectionSql.kt` has no `"prepared"` literal (subtask 1 AC 13).
6. No `skillbill.infrastructure.workflow.git.workflow` or `skillbill.infrastructure.workflow.git.github` package exists, and the gh adapters live in `skillbill.infrastructure.workflow.github` (subtask 2 AC 1-6).
7. No architecture baseline file gains a row, and no `settings.gradle.kts` include or `build.gradle.kts` dependency line is added (subtask 1 AC 12 and 14, subtask 2 AC 7).

## Constraints

- No new module, framework, port, dependency bag or architecture-test class; no baseline growth.
- Wire, JSON, YAML, telemetry and persisted values stay byte-identical.
- The `SQLiteDatabaseSessionFactory` constructor stays source-compatible with SKILL-389's inlining.
- Follow `../../../runtime-kotlin/ARCHITECTURE.md` Design Principles and `docs/code-principles.md`.

## Non-Goals

- Breaking the nativeagent/scaffold package cycle or making the skills import-direction guard live.
- Moving the `DatabaseRuntime` test entry points or their test call sites.
- Changing the SKILL-388 exact-int parse changes or engine-owned SQLITE_BUSY handling.
- Renaming classes or changing behavior in the workflow package moves.

## Dependency Notes

This bundle runs on the current tree and waits for no other issue. Overlaps with SKILL-388, SKILL-389, SKILL-390, SKILL-392, SKILL-395 and SKILL-397 are listed in the investigation's Coordination table. Each follows the second-lander rule: whichever bundle lands second applies its edit to the files present and keeps the other's change. If SKILL-388 or SKILL-389 has landed, rebase onto it first; if not, implement against the current tree.

## Suggested landing order

Across the concurrent runtime architecture bundles, as of 2026-10-01:

1. SKILL-387 (prose phase output) and SKILL-388 (runtime-application): already launched, both blocked at audit on 2026-10-01. Unblock them first if possible.
2. SKILL-389 (runtime-core)
3. SKILL-393 (runtime-ports)
4. SKILL-395 (runtime-mcp)
5. SKILL-391 (runtime-contracts)
6. SKILL-392 (runtime-cli)
7. SKILL-396 (runtime-infra) **(this bundle)**
8. SKILL-397 (runtime-domain)
9. SKILL-390 (runtime-engine)

This order keeps rebases small. SKILL-390 goes last because it touches the most engine files. It is a preference, not a prerequisite: this bundle waits for no other issue and follows the second-lander rules in its Dependency Notes. Run the goals one at a time per checkout, because each one switches branches.

## Validation Strategy

Each subtask is validated by the goal runner's build gate (compile, unit and repoTest architecture guards across runtime-kotlin, and spotless). Subtask 1 adds one sqlite test proving the review-stats degradation record reaches the session diagnostics. Subtask 2 is import-only and relies on compilation plus the acyclicity guard.
