# Subtask 1: Runtime live-cohort selection

Make `skill-bill work status` project live work on the current checkout branch whenever a live candidate exists after existing branch scoping. A later terminal standalone with a higher `run_sequence` must not win.

## Scope

Change ranking only in `IdeStatusSelectionPolicy`. After `retainedAt`, partition live vs non-live. Live means `selectionTier` in `{ACTIVE, PAUSED, BLOCKED}`. If the live cohort is non-empty, select only from it. Otherwise select from the remaining retained cohort (`FAILED`, `RECENTLY_TERMINAL`, `IDLE`) with the existing comparator, retention already applied, and the existing equal-sequence conflict scan on that cohort.

Among the selected cohort, keep the current comparator:

1. sequenced before unsequenced (`execution?.runSequence == null` last)
2. higher `runSequence` first (`compareRunSequence(right, left)`; length then lexicographic)
3. freshnessKey (`IdeStatusFreshness.STALE` → 1 else 0)
4. `selectionTier.rank`
5. `isGoalAuthoritative` true first
6. descending `updatedAt`
7. `workflowId`

Do not reorder freshness ahead of or behind tier. Goal rows can carry a WORKFLOW `runSequence` while a later standalone carries a higher STANDALONE_PHASE sequence; the live partition must ignore that comparison across the live/terminal cut.

Intended files (review and validate may repair wiring, test setup, formatting, and lint in this area):

- `runtime-kotlin/runtime-engine/src/main/kotlin/skillbill/engine/work/IdeStatusSelectionPolicy.kt`
- `runtime-kotlin/runtime-engine/src/test/kotlin/skillbill/engine/work/IdeStatusSelectionPolicyTest.kt`
- `runtime-kotlin/runtime-engine/src/test/kotlin/skillbill/engine/work/IdeStatusServiceBranchScopingTest.kt`

`IdeStatusService.status` already scopes then delegates ranking (`scopeToBranch` → `selectable` filter → `IdeStatusSelectionPolicy.select(selectable, observedAt) ?: select(candidates, observedAt)` → project). Do not add a third ranking. Do not change `scopeToBranch`, `matchesBranch`, `isPlanningOnBaseBranch`, or protected-branch behavior.

## Acceptance Criteria

1. After `retainedAt`, `IdeStatusSelectionPolicy.select` partitions candidates into a live cohort (`selectionTier` ACTIVE, PAUSED, or BLOCKED) and a remaining retained cohort (FAILED, RECENTLY_TERMINAL, IDLE). When the live cohort is non-empty, the winner comes only from that cohort. When it is empty, the winner comes from the remaining retained cohort.
2. Within the selected cohort, ranking still uses the current comparator: sequenced before unsequenced, higher `runSequence` first, then freshness, then `selectionTier.rank`, then goal-authoritative, then descending `updatedAt`, then `workflowId`.
3. Equal-sequence execution-id conflict (same `statusStoreId` and `runSequence`, different `executionId`) still throws `invalidWorkflowStateSchemaError`, scanned on the selected cohort.
4. `IdeStatusService.status` still uses existing `scopeToBranch` / `matchesBranch` / `isPlanningOnBaseBranch` / protected-branch behavior, then `IdeStatusSelectionPolicy.select(selectable, observedAt) ?: select(candidates, observedAt)`. It does not add a third ranking path.
5. `IdeStatusSelectionPolicyTest` replaces or inverts ``newer persisted standalone sequence wins over an older active workflow despite timestamp tie`` so a TERMINAL standalone with `runSequence` `"11"` does not beat an ACTIVE workflow with a lower or absent sequence. Use different issue keys and the same timestamp (`OBSERVED` = 2026-08-06T12:00:00Z). Assert the ACTIVE `workflowId` wins.
6. `IdeStatusSelectionPolicyTest` keeps ``active outranks paused blocked failed and terminal competitors`` (no-sequence) and ``feature-goal outranks child runtime for the same issue within a tier``. It adds a case where two ACTIVE candidates with execution `runSequence` and the same timestamp prefer the higher sequence. It does not invert ``a finished goal still claiming running loses to the work that is moving`` or ``a live run quiet inside the fresh window keeps its tier lead``.
7. `IdeStatusServiceBranchScopingTest` includes a case that would catch work status still returning a later terminal standalone: checkout branch contains SKILL-148 as a whole token (existing `feat/SKILL-148-status-fix` pattern), live FEATURE_GOAL `workflowId` `"goal-1"` with WORKFLOW `runSequence` `"2"`, plus a later terminal standalone_phase for SKILL-415 with distinct `workflowId` / `executionId` / `statusStoreId`, `runSequence` `"11"`, `lifecycleState` `"completed"`, and `branchCorrelation` equal to the checked-out feature branch. `IdeStatusService.status` returns `workflowId` `"goal-1"`. The standalone still matches branch scoping because `matchesBranch` allows `candidate.standaloneStatus != null` even when the standalone issue key is not in the branch name.

## Non-goals

- No CLI command, flag, or `IDE_STATUS_CONTRACT_VERSION` change.
- No change to how standalone phases publish or register, and no change to `ide_status_execution_registry` high-water.
- No status-bar UI work and no plugin coordinator edits (subtask 2).
- No SKILL-414 stale-row repair and no SKILL-415 build-gate work.
- Do not reorder among-live comparator keys to sequence then tier then freshness.
- Do not touch protected-base, detached, prefix-token, or planning-on-base-branch tests.
- A wrapper that only forwards `IdeStatusService.status` is not in scope.

## Dependency notes

None. First subtask.

## Implementation Details

Implement produces the repository end states below. Do not compile, run the named tests, or invoke the pack validation gate. Validate owns those commands. Leave `tests_executed` empty.

### Task 1. Live-cohort-first selection in `IdeStatusSelectionPolicy.select`

Serves acceptance criteria 1, 2, 3, and 4.

Path: `runtime-kotlin/runtime-engine/src/main/kotlin/skillbill/engine/work/IdeStatusSelectionPolicy.kt`. Symbol: `object IdeStatusSelectionPolicy`, public `fun select(candidates: List<IdeStatusCandidate>, observedAt: Instant): IdeStatusCandidate?`.

Change only `select`. Keep `retainedAt`, `selectionTier(lifecycle)`, `lifecycleFromDurableState`, `lifecycleFromDurableStateWire`, private `comparator(observedAt)`, and private `compareRunSequence`. Keep retention constants: `LIVE_RETENTION` and `BLOCKED_RETENTION` 24h, `SETTLED_RETENTION` 6h. `retainedAt` stays ACTIVE and PAUSED → live 24h, BLOCKED → blocked 24h, FAILED and RECENTLY_TERMINAL → settled 6h, IDLE always true, negative age kept.

Today `select` filters `retainedAt`, scans retained rows with `execution != null` grouped by `execution?.statusStoreId to execution?.runSequence`, throws `invalidWorkflowStateSchemaError` from `skillbill.error.shellcontent.invalidWorkflowStateSchemaError` with message `IDE status execution sequence conflict: sequence=...; execution_ids=...` when a group has more than one distinct `executionId`, then returns `retained.sortedWith(comparator(observedAt)).first()` or null when retained is empty.

After `retainedAt`:

1. Compute `live` as retained rows whose `selectionTier` is ACTIVE, PAUSED, or BLOCKED. `selectionTier` already maps ACTIVE→ACTIVE, PAUSED→PAUSED, BLOCKED→BLOCKED, FAILED→FAILED, TERMINAL→RECENTLY_TERMINAL, IDLE→IDLE. Durable wire `completed` is TERMINAL through `lifecycleFromDurableState`. Service `standaloneLifecycle("completed")` is TERMINAL. Do not change those mappings.
2. Selected cohort is `live` when non-empty, otherwise the remaining retained rows (FAILED, RECENTLY_TERMINAL, IDLE).
3. Run the existing equal-sequence execution-id conflict scan on that cohort only, not on the full retained list. Settled from the digest: a live row and a terminal row that share `statusStoreId+runSequence` with different `executionId`s no longer throw. Spec requires the throw only inside the selected cohort. The incident pair uses sequences `"2"` and `"11"`, so it would not hit this scan anyway.
4. Return `cohort.sortedWith(comparator(observedAt)).first()`, or null when the cohort is empty.

Keep the private `comparator(observedAt)` key order exactly: `compareBy { it.execution?.runSequence == null }` (unsequenced last), `thenComparator { left, right -> compareRunSequence(right, left) }` (higher sequence first, length then lexicographic, nulls already handled), `thenBy { freshnessKey }` where STALE is 1 else 0 via `IdeStatusFreshnessClassifier.classify`, `thenBy { it.selectionTier.rank }`, `thenBy { if (it.isGoalAuthoritative) 0 else 1 }`, `thenByDescending { it.updatedAt }`, `thenBy { it.workflowId }`. Do not reorder freshness ahead of or behind tier. Among live, a stale ACTIVE still loses to a fresh BLOCKED. Goal WORKFLOW `runSequence` `"2"` versus standalone STANDALONE_PHASE `"11"` is ignored across the live/terminal cut and used only inside a cohort.

Do not add a snapshot or wire field. Do not change `IdeStatusModels.kt` (`IdeStatusSelectionTier` order ACTIVE, PAUSED, BLOCKED, FAILED, RECENTLY_TERMINAL, IDLE; `rank` is `ordinal`) unless a later compile repair in this area is required. Do not change `IdeStatusExecutionIdentity` in `runtime-kotlin/runtime-ports/src/main/kotlin/skillbill/ports/idestatus/model/IdeStatusExecutionModels.kt`. `runSequence` stays a positive decimal via `isPositiveDecimal`. Runtime absence of sequence is `execution == null`, never a null `runSequence` string. WORKFLOW forbids `invocationId` and `phaseId`. STANDALONE_PHASE requires both non-blank. `IdeStatusExecutionScope.WORKFLOW.wireValue` is `workflow`. `STANDALONE_PHASE.wireValue` is `standalone_phase`.

Do not change `IdeStatusService` at `runtime-kotlin/runtime-engine/src/main/kotlin/skillbill/engine/work/IdeStatusService.kt`. `fun status` already does `scopeToBranch(collectCandidates(...), currentBranch, repoRoot)`, `selectable = candidates.filter { projector.readableForSelection(it, context) }`, then `IdeStatusSelectionPolicy.select(selectable, observedAt) ?: IdeStatusSelectionPolicy.select(candidates, observedAt)`. Policy change alone is enough for both `select` calls. Do not add a third ranking path. Do not edit `scopeToBranch`, `matchesBranch`, or `isPlanningOnBaseBranch`. If selectable live is empty, fallback can still pick from unreadable candidates. That is existing behavior, not a new path.

Do not change CLI, `IDE_STATUS_CONTRACT_VERSION`, standalone publish or register, `ide_status_execution_registry`, or plugins. Authored Kotlin in the policy file must have no `//` or non-KDoc block comments.

test_obligations: none on the production edit itself. Coverage is the named tests in tasks 2–4.

### Task 2. Invert the terminal-standalone-beats-active policy test

Serves acceptance criterion 5.

Path: `runtime-kotlin/runtime-engine/src/test/kotlin/skillbill/engine/work/IdeStatusSelectionPolicyTest.kt`. `OBSERVED` is `Instant.parse("2026-08-06T12:00:00Z")`. Private `candidate(issueKey, lifecycle, workflowId, updatedAt)` builds FEATURE_TASK_RUNTIME, `isGoalAuthoritative = false`, `execution` absent, `selectionTier = IdeStatusSelectionPolicy.selectionTier(lifecycle)`, `startedAt = 2026-08-06T08:00:00Z`.

Replace or invert ``newer persisted standalone sequence wins over an older active workflow despite timestamp tie``. Today it uses the same issue `SKILL-411`, ACTIVE `workflowId` `"workflow"` with no execution, TERMINAL copy `workflowId` `"phase"` with `IdeStatusExecutionIdentity(scope=STANDALONE_PHASE, executionId="execution", statusStoreId="store", runSequence="2", statusRevision="3", invocationId="invocation", phaseId="review")`, same timestamp `2026-08-06T12:00:00Z`, and asserts `"phase"` wins.

After the change the TERMINAL standalone uses `runSequence` `"11"` and a different issue key from the ACTIVE workflow, same `OBSERVED` timestamp, and the assertion is the ACTIVE `workflowId` wins.

Assumption for implement to confirm: keep ACTIVE `workflowId` `"workflow"` with no execution, keep TERMINAL `workflowId` `"phase"` with a STANDALONE_PHASE identity as today except `runSequence` `"11"`, and switch issue keys to `SKILL-414` (ACTIVE) and `SKILL-415` (TERMINAL) so the case matches the incident at unit level. The digest requires different issue keys and the ACTIVE `workflowId` as the winner. It does not require renaming the workflow ids.

test_obligations: this inverted case. Realistic bug: a TERMINAL standalone with `run_sequence` `"11"` still beats an ACTIVE workflow with a lower or absent sequence (the 2026-10-09 SKILL-414 versus SKILL-415 incident).

### Task 3. Keep named live-ranking tests and add one within-live sequence case

Serves acceptance criteria 2 and 6.

Path: same `IdeStatusSelectionPolicyTest.kt`.

Keep ``active outranks paused blocked failed and terminal competitors`` (no-sequence ACTIVE still wins). test_obligations: keep this case. Realistic bug: live-cohort partition mis-maps ACTIVE when `execution == null`.

Keep ``feature-goal outranks child runtime for the same issue within a tier`` (`goal-1` FEATURE_GOAL versus `runtime-child` FEATURE_TASK_RUNTIME, both ACTIVE, SKILL-148). test_obligations: keep this case. Realistic bug: live-cohort-first drops goal-authoritative ordering inside a live tier.

Do not invert ``a finished goal still claiming running loses to the work that is moving`` (stale ACTIVE SKILL-190 at 02:00 versus fresh BLOCKED SKILL-201 at 11:58, winner `w-201`) or ``a live run quiet inside the fresh window keeps its tier lead`` (ACTIVE at 11:45 versus BLOCKED at 11:59, winner `w-201`). Freshness still beats tier among live. That is required.

Add one case: two ACTIVE candidates with execution `runSequence` and the same timestamp prefer the higher sequence. Use WORKFLOW identities (no `invocationId` or `phaseId`), distinct `statusStoreId` and `executionId` so the conflict scan does not fire, same `updatedAt`, sequences `"2"` and `"11"`, assert the higher sequence `workflowId`. Assumption for implement to confirm: any two distinct WORKFLOW `workflowId` values are fine; pick ids that make the winner obvious, for example `"seq-low"` and `"seq-high"`.

test_obligations: this new case. Realistic bug: live-cohort-first drops within-live sequence ranking so the lower sequence wins.

Do not add extra sibling tests that re-cover the same branch with different literals. Leave the other existing policy tests in this class as they are.

### Task 4. Service incident case: live goal beats later terminal standalone on the same branch

Serves acceptance criteria 4 and 7.

Path: `runtime-kotlin/runtime-engine/src/test/kotlin/skillbill/engine/work/IdeStatusServiceBranchScopingTest.kt`. Harness: `IdeStatusServiceTestSupport.kt` in the same directory. Do not edit the harness unless a later compile repair in this area is required. Assumption for implement to confirm: helper names match the digest (`gitRepoFixture`, `testGoalRepositoryIdentity`, `workItem`, `goalOnlyDatabase`, `ideStatusService`, `ideStatusObservedAt`, `TrackingDatabase`, `registeredGoalDatabase`). If a name differs, follow the existing test class rather than inventing a harness.

Add one case that would catch work status still returning a later terminal standalone:

- Checkout via `gitRepoFixture(prefix, branch = "feat/SKILL-148-status-fix")`. That helper writes `.git/HEAD` as `ref: refs/heads/$branch`. Default branch when omitted is `feat/SKILL-148-fixture`. Planning-on-base-branch still uses whatever default branch the existing tests already use. The parent assumption that it is `main` does not require renaming.
- Identity via `testGoalRepositoryIdentity(fixture)`.
- `workItem(workflowId, kind, state, updatedAt)` hardcodes `issueKey = "SKILL-148"`.
- Start from `goalOnlyDatabase(goalState = "running")`: one FEATURE_GOAL `workItem("goal-1", FEATURE_GOAL, "running", "2026-08-06T10:00:00Z")`.
- Follow `registeredGoalDatabase` in that class: `object : StandalonePhaseStatusRepository by database.unitOfWork().standalonePhaseStatuses` with `override fun latestWorkflowExecution(workflowId: String): IdeStatusWorkflowExecution?` returning `IdeStatusWorkflowExecution(repositoryIdentity, branchCorrelation = checkout feature branch, issueKey = "SKILL-148", workflowId = "goal-1", invocationId = "parent-invocation", executionId = "parent-execution", statusStoreId = "status-store", runSequence = "2", statusRevision = "1", lifecycleState = "active", startedAt = ideStatusObservedAt.minusSeconds(600), updatedAt = ideStatusObservedAt).takeIf { workflowId == "goal-1" }`.
- Also override `readEligible` to return one `StandalonePhaseStatusRecord` with `issueKey = "SKILL-415"`, distinct `workflowId` / `executionId` / `statusStoreId` (standalone `workflowId` must not be `"goal-1"`; `collectCandidates` drops workflow candidates whose `workflowId` is in standalone `workflowId`s), `runSequence = "11"`, `lifecycleState = "completed"`, `branchCorrelation` equal to the checked-out feature branch, `terminalResult` e.g. `"completed"`, and required lease and timing fields filled (`invocationId`, `phaseId`, `statusRevision`, `currentStep`, `startedAt`, `updatedAt`, `leaseOwner`, `leaseGeneration`, `leaseExpiresAt`; nullable activity, finished, duration as needed). Assumption for implement to confirm: `"completed"` is the fixture token because it maps to TERMINAL through `standaloneLifecycle`. Assumption: any distinct standalone `workflowId` other than `"goal-1"` is fine, for example `"standalone-415"`.
- `TrackingDatabase(work, workflows, exists = true, controls = EmptyGoalRunnerControlRepository, statusRepository)` assigns `standalonePhaseStatuses = statusRepository ?: super.standalonePhaseStatuses`. Default `readEligible` is empty and default `latestWorkflowExecution` is null when not overridden.
- Call `ideStatusService(database).status(IdeStatusRequest(repoRoot = fixture.toString(), observedAt = ideStatusObservedAt))` and assert `snapshot.workflowId == "goal-1"`.
- `ideStatusObservedAt` is `2026-08-06T12:00:00Z`.

The standalone still matches branch scoping because `matchesBranch` allows `candidate.standaloneStatus != null` even when the standalone issue key is not in the branch name. Do not change that. Do not touch protected-base, detached, prefix-token, or planning-on-base-branch tests in that class.

`StandalonePhaseStatusRecord` fields live in `runtime-kotlin/runtime-ports/src/main/kotlin/skillbill/ports/idestatus/model/StandalonePhaseStatusModels.kt`: `repositoryIdentity`, `branchCorrelation`, `issueKey`, `workflowId`, `invocationId`, `phaseId`, `executionId`, `statusStoreId`, `runSequence`, `statusRevision`, `lifecycleState`, `currentStep`, `currentActivity`, `startedAt`, `updatedAt`, `finishedAt`, `activeDurationMs`, `activeDurationAsOf`, `leaseOwner`, `leaseGeneration`, `leaseExpiresAt`, `terminalResult`. `IdeStatusWorkflowExecution` fields match the `registeredGoalDatabase` call above. Do not change those model files.

test_obligations: this service case. Realistic bug: policy unit tests pass but `IdeStatusService.status` still projects the later terminal standalone because `collectCandidates` unions rows and dual `select` still surfaces the terminal winner.

### Constraints for all tasks

- `relaxUnitFun = true`, never `relaxed = true`. These named tests do not need mocks or environment maps if they follow the existing helpers. If a test does build a runtime environment map, pass an explicit non-empty map.
- Wire keys stay in owning `*Keys` objects. No new CLI flags. No new snapshot or wire field.
- Authored Kotlin has no `//` or non-KDoc block comments.
- No SKILL-414 stale-row repair and no SKILL-415 build-gate work. Ranking must not depend on those fixes.
- Incoming Failed replacing Done is not required.
- Plugin `acceptsNewerStatus` can still freeze a cached Done after this subtask. That is subtask 2. Do not invent plugin ranking here.
- Do not run `./install.sh`, uninstall, or install-sync.
- Implement and audit do not run the named tests or `./gradlew check`. Validate owns the pack full gate.

### Out of scope for implement

Production `IdeStatusService.kt`, `IdeStatusModels.kt` unless a compile repair in this area is required later, execution-identity and standalone-record model files, CLI, `IDE_STATUS_CONTRACT_VERSION`, plugins, registry high-water, standalone publish and register.

## Validation strategy

The named tests above are the implement and review proof. Implement and audit do not run them, compile, or invoke `./gradlew check`. The validate phase owns the pack validation gate. Leave `tests_executed` empty.

## Next path

Subtask 2 changes IntelliJ and VS Code `acceptsNewerStatus` so a live snapshot can replace a cached Done even when its `run_sequence` is lower or missing. After this subtask's commit, continue with `skill-bill goal SKILL-416`.
