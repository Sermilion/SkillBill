# SKILL-416 IDE status shows live work on the current branch

For the current checkout branch, `skill-bill work status` and the IntelliJ / VS Code status bar must show live work on that branch. A later completed standalone phase must not win.

Incident 2026-10-09: checkout was `feat/SKILL-414-stabilization-pass-stale-workflow-state-quiet-output-consistent-terminology-review-quality-measurement`. `skill-bill goal SKILL-414` was live (`current_subtask` 1, step review, `execution_liveness` live). `skill-bill work status --format json`, which the IntelliJ plugin polls, returned the finished SKILL-415 standalone plan (`issue_key` SKILL-415, `execution_scope` standalone_phase, `lifecycle_state` terminal, `run_sequence` 11, summary completed). The widget showed Phase · done, not the running goal.

## What is wrong

`IdeStatusService` already scopes candidates to the current branch (`scopeToBranch`). `IdeStatusSelectionPolicy` then ranks by `run_sequence` before `selectionTier`. Sequences come from a repo-global `ide_status_execution_registry` high-water, not per issue. A later plan for a different key on the same branch steals the status bar.

Even if work status later returned the live SKILL-414 snapshot, IntelliJ `StatusRefreshCoordinator.acceptsNewerStatus` and the VS Code copy reject an incoming snapshot whose `run_sequence` is lower than the cached terminal one (`sequenceOrder < 0` returns false). A live goal with a lower or missing sequence cannot replace Phase · done until the cache is cleared.

## Required policy (runtime)

After `retainedAt`, partition live vs non-live. Live means `selectionTier` in `{ACTIVE, PAUSED, BLOCKED}` (lifecycle ACTIVE, PAUSED, BLOCKED). If the live cohort is non-empty, select only from it. Otherwise select from the remaining retained cohort (`FAILED`, `RECENTLY_TERMINAL`, `IDLE`) with the existing comparator, retention already applied, and the existing equal-sequence conflict scan on that cohort.

Among live, keep the current comparator (sequence, then freshness, then tier, then goal-authoritative, then `updated_at`, then `workflowId`). Do not reorder freshness ahead of or behind tier among live. `compareRunSequence` and null-sequence-last stay for within-cohort ranking so two live roots on the same branch still prefer the higher sequence.

This preserves `a finished goal still claiming running loses to the work that is moving` (stale ACTIVE vs fresh BLOCKED, both live, freshness currently before tier).

## Required accept gate (plugins)

IntelliJ and VS Code keep polling work status. For the same `repository_identity` and `branch_correlation`: if incoming is live display (Active / Paused / Blocked) and current is completed Done, and this is not same-execution resurrection, return true even when incoming `run_sequence` is lower or null. Same-execution Done must not regress to Active. Among two live snapshots, keep existing sequence comparison (older live must not replace newer live). Store-id replacement, correlation change, and uncorroborated-idle tolerance stay.

Do not use `SkillBillStatusOutcome.isLiveOutcome()` as the exception predicate (IntelliJ includes Failed and Stale). Do not treat cached Blocked as the freeze-worthy terminal. Leave Failed vs Done on existing sequence rules. Do not tighten VS Code's missing-sequence-among-live accept. Runtime selection remains the source of "current work"; coordinators must not invent a second ranking of all workflows.

## Decomposition

Two independently resumable, dependency-ordered subtasks. Both are required for the end state.

1. Runtime live-cohort selection. Source of current work. Without this, plugins display whatever wrong snapshot work status returns.
2. Plugin accept live over stale Done. Without this, a cached Done stays on screen until the coordinators change.

## Scope

- `IdeStatusSelectionPolicy` live-cohort-first selection after existing branch scoping.
- IntelliJ and VS Code `acceptsNewerStatus` live-over-Done exception, kept aligned.
- Named tests below. Review and validate may repair production wiring, test setup, formatting, and lint in the touched areas. The planned file lists are the intended path, not a freeze that blocks required-check repairs.

## Acceptance Criteria

1. For the current checkout branch, after existing branch scoping, `IdeStatusSelectionPolicy` projects live work on that branch (lifecycle ACTIVE, PAUSED, or BLOCKED: a running, paused, or blocked goal, child, or standalone phase) whenever any such retained candidate exists. A later terminal standalone phase with a higher `run_sequence` does not win.
2. Terminal / recently-settled work (completed plan, completed goal, FAILED, RECENTLY_TERMINAL, IDLE) is selected only when the branch has no live retained candidate. Then existing sequence, retention (6h settled / 24h live), freshness, timestamps, and equal-sequence conflict rules still apply on that remaining cohort.
3. Ranking among live candidates on the branch still uses the current within-cohort comparator: sequenced before unsequenced, higher `run_sequence` first, then freshness, then tier (active > paused > blocked), then goal-authoritative (goal outranks a child of the same issue within a tier), then descending `updated_at`, then `workflowId`.
4. IntelliJ and VS Code `StatusRefreshCoordinator.acceptsNewerStatus` accept a live snapshot (Active, Paused, or Blocked) for the same `repository_identity` and `branch_correlation` even when its `run_sequence` is lower than, or missing relative to, a currently displayed Done snapshot, except same-execution terminal regression. Sequence still rejects an older live snapshot overwriting a newer live snapshot. Store-id replacement, correlation change, and uncorroborated-idle tolerance stay.
5. The plugins do not invent a second ranking of all workflows. Runtime selection is the source of current work. The coordinator only must not freeze a stale terminal Done winner.
6. Branch scoping is unchanged: protected/detached behavior, issue-key-in-branch matching, and planning-on-base-branch stay. CLI command shape and `IDE_STATUS_CONTRACT_VERSION` do not change.

## Non-goals

- No new status-bar UI.
- No new CLI flags.
- No change to how standalone phases publish or register.
- No change to `ide_status_execution_registry` high-water.
- No operator requirement to close IntelliJ or delete cache.
- No SKILL-415 build-gate work.
- No SKILL-414 stale-row work except that this ranking must not depend on those fixes.
- Incoming Failed replacing Done is not required.
- No new snapshot or wire field.

## Constraints

- Supplied requirements are authoritative and need no tracker lookup.
- Mocks use `relaxUnitFun = true`, never `relaxed = true`.
- Tests that build a runtime environment map pass an explicit non-empty map.
- Authored Kotlin has no `//` or non-KDoc block comments.
- Wire keys stay in owning `*Keys` objects.
- Runtime candidates cannot carry a null `runSequence` string on an execution object. Absence is `execution == null`. Plugin snapshots can carry null `runSequence` on metadata.

## Settled decisions

- Among-live key order vs AC item 3 wording: preserve the current comparator within each cohort and only add live-cohort-first. There is no named live-vs-live incident this task must also fix.
- Plugin exception predicate is live display (Active / Paused / Blocked) over Done, not `isLiveOutcome()` and not `isTerminal`.
- VS Code already accepts when a sequence is missing among live. Keep that. Add live-over-Done on the `sequenceOrder < 0` path that rejects when both sequences are present. IntelliJ currently rejects when either sequence is null once both have execution metadata; accept incoming null over cached Done there.
- Equal-sequence conflict still throws when two executionIds share `statusStoreId+runSequence` in the selected cohort. The incident pair uses different sequences.
- `IdeStatusService` dual `select(selectable) ?: select(candidates)` both go through the policy. Do not add a third ranking.

## Assumptions for implement to confirm

- Repository default branch is `main`. The digest did not name it. Planning-on-base-branch behavior is unchanged and does not require renaming the default branch.
- The service-level standalone fixture uses `lifecycleState` `"completed"` (maps to TERMINAL). The incident summary was completed.
- Harness names in `IdeStatusServiceBranchScopingTest` / `IdeStatusServiceTestSupport.kt` match the digest (`gitRepoFixture`, `testGoalRepositoryIdentity`, `workItem` with hardcoded issueKey SKILL-148, `goalOnlyDatabase`, `ideStatusService`, `ideStatusObservedAt` = 2026-08-06T12:00:00Z). If a helper name differs, follow the existing test class patterns the digest describes rather than inventing a new harness.

## Validation strategy

Named tests in the subtask specs are the implement and review proof. Implement and audit do not run them. The validate phase owns `validation_gate.collect_all_full_gate_command` / `./gradlew check`. Leave `tests_executed` empty in earlier phases.

## Next path

```bash
skill-bill goal SKILL-416
```
