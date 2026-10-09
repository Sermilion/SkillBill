# Subtask 2: Plugin accept live over stale Done

IntelliJ and VS Code keep polling work status. They must accept a live snapshot for the same `repository_identity` and `branch_correlation` even when its `run_sequence` is lower than, or missing relative to, a currently displayed Done snapshot, so a cached Phase · done cannot freeze out the live goal subtask 1 now returns.

## Scope

Change `acceptsNewerStatus` in both coordinators and keep them aligned. For the same repository identity and branch correlation: if incoming is live display (Active / Paused / Blocked) and current is completed Done, and this is not same-execution resurrection, return true even when incoming `run_sequence` is lower or null.

Same-execution Done must not regress to Active (keep the existing terminal-regression reject when `executionId` matches). Among two live snapshots, keep existing sequence comparison (older live must not replace newer live). Store-id replacement, correlation change, and uncorroborated-idle tolerance stay.

Do not use `SkillBillStatusOutcome.isLiveOutcome()` as the exception predicate: IntelliJ `isLiveOutcome` includes Failed and Stale. Do not treat cached Blocked as the freeze-worthy terminal; the incident is cached Done. Leave Failed vs Done on existing sequence rules.

Do not tighten VS Code's missing-sequence-among-live accept (today missing sequence returns true). Only add the live-over-Done exception on the `sequenceOrder < 0` / missing-incoming path that currently rejects in IntelliJ and rejects in VS Code when both sequences are present.

Intended files (review and validate may repair wiring, test setup, formatting, and lint in this area):

- `intellij-plugin/src/main/kotlin/dev/skillbill/intellij/application/StatusRefreshCoordinator.kt`
- `vscode-extension/src/application/StatusRefreshCoordinator.ts`
- `intellij-plugin/src/test/kotlin/dev/skillbill/intellij/application/StatusRefreshCoordinatorTest.kt`
- `vscode-extension/src/test/StatusRefreshCoordinator.test.ts`

Do not rank workflows in the coordinator. Runtime selection remains the source of current work.

## Acceptance Criteria

1. IntelliJ `StatusRefreshCoordinator.acceptsNewerStatus`, after matching `repositoryIdentity`, `branchCorrelation`, and `statusStoreId`: if incoming is live display (Active, Paused, or Blocked) and current is Done, and `executionId` does not match, return true even when incoming `runSequence` is lower than current or null.
2. VS Code `acceptsNewerStatus` applies the same live-over-Done exception when both sequences are present and `sequenceOrder < 0`. It does not tighten the existing missing-sequence-among-live accept (missing sequence still returns true).
3. Same-execution Done does not regress to Active: when `executionId` matches and current is Done and incoming is Active, both coordinators keep the existing terminal-regression reject.
4. Among two live snapshots, existing sequence comparison remains: an incoming Active with a lower `runSequence` does not replace a displayed newer Active.
5. The live-over-Done exception does not use `isLiveOutcome()` (Failed and Stale stay out). Cached Blocked is not treated as the freeze-worthy terminal. Failed vs Done stays on existing sequence rules. `isStoreReplacement`, correlation change, and uncorroborated-idle tolerance stay. Coordinators do not add a ranking of all workflows.
6. `StatusRefreshCoordinatorTest` (IntelliJ) includes: accepted Done `runSequence` `"11"` then incoming Active `"10"` with a different `executionId`, same repo and branch, displays Active; the same with incoming `runSequence` null is accepted; accepted Active `"11"` then incoming Active `"10"` stays on the newer Active; same `executionId` Done then Active stays Done. Pattern: `FakeStatusRepository`, `FakePreferenceCache`, `Path.of("/tmp/a")`, mutate the response between `requestRefresh` calls, `withTimeout { coordinator.outcomes.first { ... } }`. Existing higher-sequence Active replacing Done remains.
7. `StatusRefreshCoordinator.test.ts` (VS Code) includes the same four cases, driven through `acceptedOutcome` via sequential `requestRefresh` like the existing "accepts the next run after a terminal result..." test. Flattened outcomes use `kind` `"done"` | `"active"`, `repositoryIdentity` `"repo"`, `branchCorrelation` `"main"`. Existing higher-sequence Active replacing Done remains. `hasLegacyExecutionCorrelation` stays.

## Non-goals

- No new status-bar UI and no new CLI flags.
- No second ranking of all workflows in the plugin.
- No operator requirement to close IntelliJ or delete cache.
- Incoming Failed replacing Done is not required.
- No new snapshot field. Plugins already receive lifecycle via outcome kind.
- No runtime policy edits (subtask 1). No standalone publish path, no contract_version bump.
- Do not change `isStoreReplacement`, correlation change, uncorroborated idle (`UNCORROBORATED_IDLE_TOLERANCE`), or transport fallback except as needed to keep them intact.

## Dependency notes

Depends on subtask 1. Runtime selection is the source of current work. This subtask only stops the coordinator from freezing a stale terminal Done winner. Plugin-only still displays whatever wrong snapshot work status returns; runtime-only still leaves a cached Done on screen.

## Implementation Details

Ordered implementable tasks for this subtask only. Do not edit runtime selection, `IdeStatusSelectionPolicy`, CLI, or `IDE_STATUS_CONTRACT_VERSION`. Do not run compile, tests, or the pack validation gate. Leave `tests_executed` empty. Do not run `./install.sh` or any install refresh.

The supplied preplan digest described subtask 1 (runtime live-cohort selection) and stated that plugin `acceptsNewerStatus` freeze of a cached Done is this subtask. Coordinator control-flow facts below are taken from this sub-spec's existing description of the two `acceptsNewerStatus` paths plus the run-invariant acceptance criteria. Implement confirms those insertion points against the current sources; if a helper or early-return order differs, follow the existing coordinator rather than inventing a second ranking.

Live display for the exception is outcome kind Active, Paused, or Blocked. Do not call IntelliJ `SkillBillStatusOutcome.isLiveOutcome()` (Failed and Stale stay out). Do not treat cached Blocked as the freeze-worthy terminal. Failed versus Done stays on existing sequence rules. Incoming Failed replacing Done is not required.

### Task 1 — IntelliJ live-over-Done accept

Serves AC 1, 3, 4, and 5.

Path: `intellij-plugin/src/main/kotlin/dev/skillbill/intellij/application/StatusRefreshCoordinator.kt`, private `acceptsNewerStatus(current: SkillBillStatusOutcome?, incoming: SkillBillStatusOutcome): Boolean`.

After both sides have execution metadata and `repositoryIdentity`, `branchCorrelation`, and `statusStoreId` match, insert the exception on the path that today rejects a lower or missing incoming sequence (`currentExecution.runSequence ?: return false`, `incomingExecution.runSequence ?: return false`, then `sequenceOrder = compareDecimalStrings(incomingSequence, currentSequence)` with `< 0` returning false). If incoming is live display (Active, Paused, or Blocked), current is Done, and `executionId` does not match, return true even when incoming `runSequence` is lower than current or null.

Do not change `isStoreReplacement`, correlation-change accept, or uncorroborated-idle tolerance (`UNCORROBORATED_IDLE_TOLERANCE`). Do not change `isTerminal` (Done, Blocked, Failed) into the exception predicate. Same-execution Done must not regress to Active: when `executionId` matches, keep the existing terminal-regression reject (`isTerminal(current) && current::class != incoming::class`). Among two live snapshots, current is not Done, so existing sequence comparison remains (incoming Active `"10"` does not replace displayed Active `"11"`). Coordinators do not rank all workflows.

Assumption for implement to confirm: `StatusExecutionMetadata.runSequence` is nullable; runtime absence of sequence on a plugin snapshot is a null field, not a missing execution object. Authored Kotlin in this file must have no `//` or non-KDoc block comments.

test_obligations: none in this task (tests are Task 3).

### Task 2 — VS Code live-over-Done accept, aligned with IntelliJ

Serves AC 2, 3, 4, and 5.

Path: `vscode-extension/src/application/StatusRefreshCoordinator.ts`, `acceptsNewerStatus`.

Keep the existing missing-sequence-among-live accept: today `if (!current || !current.runSequence || !incoming.runSequence) return true`. Do not turn that into a reject. Outcomes are flattened (`runSequence` on `SkillBillStatusOutcome`). After same repo, branch, and store via `correlationOfOutcome` and `current.branchCorrelation` / `current.statusStoreId`, add the live-over-Done exception on the `sequenceOrder < 0` path that currently returns false when both sequences are present: incoming live display and current Done and `executionId` does not match → return true.

Keep same-execution terminal-regression reject when `executionId` matches. Keep among-live sequence comparison. Keep `hasLegacyExecutionCorrelation`. Do not invent a second ranking. Keep store-id replacement, correlation change, and uncorroborated-idle tolerance intact.

Assumption for implement to confirm: VS Code already accepts when a sequence is missing; the new exception is only required on `sequenceOrder < 0` with both sequences present. Align intent with IntelliJ (same live-over-Done rule), not identical control-flow, because the two coordinators handle missing sequence differently today.

test_obligations: none in this task (tests are Task 4).

### Task 3 — IntelliJ coordinator tests

Serves AC 6 (and guards AC 1, 3, 4).

Path: `intellij-plugin/src/test/kotlin/dev/skillbill/intellij/application/StatusRefreshCoordinatorTest.kt`.

Pattern: `FakeStatusRepository`, `FakePreferenceCache`, `Path.of("/tmp/a")`, mutate the repository response between `requestRefresh` calls, `withTimeout { coordinator.outcomes.first { ... } }`. Follow the existing ``newer active execution replaces the previous terminal execution`` case (`StatusExecutionMetadata` with `executionScope="standalone_phase"`, `branchCorrelation="main"`, `SkillBillStatusOutcome.Done` / `Active` with `repositoryIdentity="repo"`).

Add these four cases (one strong test per rule; no Paused/Blocked sibling literals — the production predicate includes those kinds and audit can read it):

1. Accepted Done `runSequence` `"11"` then incoming Active `"10"` with a different `executionId`, same repo and branch, displays Active. Realistic bug: coordinators still reject `sequenceOrder < 0`, so the live goal never replaces Phase · done.
2. Same as (1) with incoming `runSequence` null, accepted. Realistic bug: IntelliJ still returns false when either sequence is null once both have execution metadata.
3. Accepted Active `"11"` then incoming Active `"10"` stays on the newer Active. Realistic bug: live-over-Done is written too broadly and an older live overwrites a newer live.
4. Same `executionId` Done then Active stays Done. Realistic bug: same-execution terminal regression is lost and a completed phase flickers back to Active.

Keep the existing higher-sequence Active replacing Done case. Do not run these tests in implement, audit, or this plan; validate owns the pack full gate.

Assumption for implement to confirm: if a fake or helper name differs from `FakeStatusRepository` / `FakePreferenceCache`, follow the existing test class rather than inventing a harness.

### Task 4 — VS Code coordinator tests

Serves AC 7 (and guards AC 2, 3, 4).

Path: `vscode-extension/src/test/StatusRefreshCoordinator.test.ts`.

Drive the same four cases through `acceptedOutcome` via sequential `requestRefresh`, matching the existing "accepts the next run after a terminal result..." test. Flattened outcomes use `kind` `"done"` | `"active"`, `repositoryIdentity` `"repo"`, `branchCorrelation` `"main"`. Keep `hasLegacyExecutionCorrelation`. Keep the existing higher-sequence Active replacing Done case.

Assumption for implement to confirm: local `FakePreferences` currently does not persist last-known cache (`getLastKnownDisplayCache` always undefined); sequential `requestRefresh` in one test remains the way to observe accept/reject. If the fake still does not persist, do not add a cache-persistence harness for this subtask.

Do not run these tests in implement or audit.

### Constraints

- Mocks use `relaxUnitFun = true`, never `relaxed = true`. Named coordinator tests should follow existing fakes and need no environment maps.
- Authored Kotlin has no `//` or non-KDoc block comments.
- Wire keys stay in owning `*Keys` objects. No new snapshot or wire field. No new CLI flags. No new status-bar UI.
- Runtime candidates cannot carry a null `runSequence` string on an execution object; plugin snapshots can carry null `runSequence` on metadata (AC 1 / test 2).
- Review and validate may repair production wiring, test setup, formatting, and lint in the touched coordinator and test files. The intended paths are not a freeze against those repairs.
- Execution model `same_branch_commit_per_subtask`. Feature branch `feat/SKILL-416-ide-status-shows-live-work-on-the-current-branch`. This subtask depends on subtask 1 for the product end state (runtime must return the live snapshot); the coordinator change itself does not edit runtime sources and is not blocked on that commit being present while planning.
- Operators do not need to close the IDE or delete cache. After later install/validate, the next poll should replace Phase · done with the live goal. That observation belongs to validate / operator use, not implement.

### Deliberately out of this plan

Runtime live-cohort selection (subtask 1). SKILL-414 stale-row repair. SKILL-415 build-gate work. Ranking of all workflows in the plugin. Changing VS Code's missing-sequence-among-live accept into a reject. Treating cached Blocked as freeze-worthy. Using `isLiveOutcome()`. Compile, `./gradlew check`, and pack `validation_gate` (validate). Build proof (build phase). Install refresh. History, commit, PR.

## Validation strategy

The named coordinator tests above are the implement and review proof. Implement and audit do not run them, compile, or invoke `./gradlew check`. The validate phase owns the pack validation gate. Leave `tests_executed` empty.

After install, the next poll should replace Phase · done with the live goal without clearing cache or restarting the IDE. That runtime behavior is observed in validate / operator use, not as an implement criterion.

## Next path

```bash
skill-bill goal SKILL-416
```
