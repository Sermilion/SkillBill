# SKILL-424 Subtask 3 - SKILL-423 data repair handoff

Parent spec: [.feature-specs/SKILL-424-readian-architecture-parity-for-the-desktop-app/spec.md](spec.md)
Issue key: SKILL-424

## Scope

Close the seven repair obligations transferred from SKILL-423 workflow `wftr-20261012-112951-so8l`. For each obligation, first reconcile the reported gap against the current production tree. Keep a source-backed satisfied verdict where the obligation is already met and preserve its coverage. Implement and test only the actual remaining gap. The scope transfer itself is not passing evidence. Runtime changes follow runtime architecture rules and report A1-A12 and G1-G7.

Parent sections: Data repair handoff from SKILL-423; Data and preference integration; Architecture enforcement and evidence.

## Acceptance Criteria

1. AC-28 (from SKILL-423 AC-002): shared aggregate status, membership, attention, phase grouping, sort, and control policy has one owning runtime domain implementation, and desktop consumers render its typed facts. Declaration-aware guards resolve governed edges through real consumers and fail on unresolved edges and empty scans, with rejection fixtures.
2. AC-29 (from SKILL-423 AC-003): shared and desktop live/history selection is bounded at persistence and projection boundaries, including legacy identity and coherence retries, with request-wide query-work accounting. A fixture with at least 5,000 newer terminal records, older live work, rejected candidates, and forced retries proves it.
3. AC-30 (from SKILL-423 AC-004): the exact durable pause/block reason reaches board, detail, history, copy actions, and visible text for the selected identity. Typed facts separate bounded labels from full text and expose legacy restoration outcomes.
4. AC-31 (from SKILL-423 AC-009): one database-session owner closes and joins connections, observers, callbacks, owner registrations, timers, and in-flight reads before a replacement or reconnect publishes readiness, without holding a mutex cleanup callbacks need. Barrier-driven replacement, cancellation, reconnect, and failed-initialization tests prove it.
5. AC-32 (from SKILL-423 AC-010): every growing detail collection has an independent bounded selector and continuation fenced by store identity, revision, query, parent, and list. Retained pages publish one coherent observation and keep a usable identity/scroll anchor through insert, update, delete, and refresh.
6. AC-33 (from SKILL-423 AC-011): shared control admission validates the exact opened store through the owning write connection. Same-path replacement cannot write to the prior file. Durable intent, accepted request, attempted termination, confirmed exit, and incomplete termination stay distinct typed outcomes through desktop feedback.
7. AC-34 (from SKILL-423 AC-017): store/query/identity-owned deadline scheduling reprojects live and independently subscribed detail data without writes or periodic reads. Append, eviction, refresh, selection change, and replacement recompute the earliest deadline, and obsolete callbacks cannot invalidate a new owner.
8. A handoff record lists, per obligation, the SKILL-423 source ID, the current production trace, the actual remaining gap or already-satisfied evidence, the final owner, and the executed regression evidence.

## Execution rule

This subtask has no manifest dependencies. It runs on the current tree. It creates any missing module, convention, or contract its criteria need, keeps a change that is already present, and applies its rule to whatever anchors exist, whether moved, renamed, or deleted. When it moves or deletes a symbol, it updates every caller wherever that caller lives. It leaves `desktop-tray` configuring, compiling, and passing `./gradlew check`; it never lands a half-migrated tree that only a later subtask can repair.

The parent spec is authoritative for module ownership, allowed dependencies, lifetimes, reference patterns, and non-goals. Read its sections named under Scope before changing code. The Readian reference root is `/Users/braian.gapur/StudioProjects/readian-android`; record its current HEAD and any drift from the recorded `36d1dd9f902a7ab8b7eb2117e898e911fe51120d` before adopting a pattern.

## Non-goals

- Weakening runtime guards, growing baselines or exemptions, mutating SKILL-423's manifest, or resetting its retry history.
- Repeating an obsolete repair or treating the transfer report as proof of current behavior.
- Any parent non-goal.

## Validation Strategy

Run affected runtime tests, `cd runtime-kotlin && ./gradlew check`, and the desktop `./gradlew check`. Use deterministic clocks, test dispatchers, barriers, and disposable runtime-produced databases.

## Next Path

Feature and navigation subtasks render the typed facts this subtask settles.

## Spec Path

.feature-specs/SKILL-424-readian-architecture-parity-for-the-desktop-app/spec_subtask_3_skill-423-data-repair-handoff.md

## Implementation Details

This plan comes from the preplan digest only. Nothing here was checked against the tree during planning. Every step that depends on current code starts by confirming its anchor. Anchors may have moved if subtasks 1 or 2 landed first, so look for the symbol rather than the path. Steps marked **Assumption** record a decision made without a confirmed fact. Implement confirms or corrects each one and writes the outcome into the handoff record.

### Anchors named by the digest

- **Desktop**:
  - `desktop-tray/src/main/kotlin/dev/skillbill/tray/application/Ports.kt`, which holds `WorkBoardRepository`, `WorkDetailRepository` (`detail(identity, cursor, list: DetailList?, startOffset)`), `WorkControlRepository`, `WorkStoreSession` and `Outcome`/`Problem`.
  - `application/DashboardController.kt`, including `TransitionTracker`, `BoardProjection`, `project(board, now)` and `displayedDuration`.
  - `domain/WorkModels.kt`.
  - `infrastructure/data/RoomBoardSource.kt` (`mutex`, `selectDatabase`, `observe`, `board`, `observeDetail`, `detail`, `refresh`, `retainPages`, `control`, `runAction`, `closeAndJoin`), `RuntimeWorkMapper.kt` and `BoardPagingSource.kt`.
  - `infrastructure/storage/WorkCandidateQueries.kt`, `DesktopCommitSignalObserver.kt`, `JdbcRoomConnection.kt` and `ReadOnlyRoomDriver.kt`.
  - `ui/DetailPane.kt`, `ui/ReasonTooltip.kt` and `ui/RunActionButtons.kt`.
- **Desktop tests**:
  - `architecture/DesktopArchitectureTest.kt`
  - `infrastructure/data/RoomBoardSourceTest.kt`, which already holds the 5,000-record fixture, and `RoomBoardObservationFailureTest.kt`
  - `infrastructure/storage/DesktopCommitSignalObserverTest.kt` and `RoomRuntimeCompatibilityTest.kt`
  - `application/DashboardControllerTest.kt`
  - `ui/ReasonTooltipTest.kt`
- **Runtime**:
  - `../../../runtime-kotlin/runtime-engine/src/main/kotlin/skillbill/engine/work`, covering `IdeStatusService` (`board`, `projectBoardSelection`, `detail`), `control/WorkControlService.request` and the `pagination/` family.
  - The runtime ports and SQLite persistence implementations.
  - `RuntimeComponent` exports `databaseSession`, `workControlService`, `FeatureTaskRuntimeRunEntry` and `workObservationService`. The inbound API inventory pins these names.
- **Gap statement**: `../../../desktop-tray/ARCHITECTURE.md` lists these remaining data gaps:
  - The desktop still owns grouping and control-eligibility checks.
  - The Room projection runs beside a separate runtime JDBC snapshot through `IdeStatusService`.
  - `IdeStatusService.board` loads the full work list and standalone candidates.
  - Shared detail still materializes some growing collections.
  - The live-board query walks historical rows.

### Ordered tasks

**T1. Reconciliation census and handoff record skeleton.** Serves AC-8, and opens every obligation in AC-28 to AC-34.

- For each obligation, trace the current production path from the desktop consumer through the repository or session to the runtime service and SQL. Record the file and symbol at each hop.
- Give each obligation a verdict:
  - `satisfied`: name the production trace and the existing test that proves it.
  - `gap`: state the remaining defect in one sentence.
  - `partial`: list the satisfied parts and the gap separately.
- The report descriptions predate repairs to bounded shared reads, store/revision cursors, exact accepted execution output, owner observation and detail deadlines. Treat each report claim as a hypothesis and check it in source.
- Create the handoff record at `../../../desktop-tray/docs/skill-423-handoff.md`. It has one row per obligation with these columns:
  - SKILL-423 source ID
  - SKILL-424 AC
  - current production trace
  - verdict and remaining gap, or satisfied evidence
  - final owner (module or source set, and symbol)
  - regression evidence (test class and method, plus the command that runs it)
  - executed result
- **Assumption**: the record lives under `../../../desktop-tray/docs` beside `data-layer-validation.md`, and subtask 6's inventories link to it rather than copying it.
- Phase feasibility for the executed-result column:
  - Implement fills every column except "executed result" and marks that column `pending validate`.
  - The validate phase writes the observed result from the commands it runs.
  - Nothing invents a pass.
- Also record:
  - The Readian HEAD and any drift from `36d1dd9f…`. If `/Users/braian.gapur/StudioProjects/readian-android` is unreadable, write that down; only the `ReadianRoomDatabaseProvider` pattern bears on this subtask (AC-31).
  - The current branch against the manifest's `feature_branch` and against the AGENTS.md active-fix-branch rule. Report a mismatch; do not switch branches inside implement.

**T2. AC-31: one database-session owner.** Do this first because AC-32, AC-33 and AC-34 all fence on its identity and generation.

- Reconcile first:
  - Subtask 2 also targets this owner. If a split session owner already exists, verify it against the AC instead of rebuilding it.
  - Otherwise apply the rule to `RoomBoardSource` (`mutex`, `selectDatabase`, `closeAndJoin`) and `DesktopCommitSignalObserver`.
- Required end state:
  - One owner type holds the selected store identity, a monotonically increasing generation, the read connection, the commit-signal observer registration, callbacks, owner registrations, deadline timers and in-flight read jobs. It holds them in one child scope per generation.
  - Replacement works in this order:
    1. Under the lock, swap the state to a closing record for the old generation.
    2. Release the lock.
    3. Cancel the old generation and join it.
    4. Close the connection and the observer.
    5. Under the lock, publish the new generation's readiness.
  - Cleanup callbacks never need the lock while the swap holds it.
  - Late results carry their generation, and the owner drops a result whose generation is not current.
  - Reconnect after an observer failure happens only on explicit refresh. Keep the existing no-timed-reconnect rule.
  - A failed initialization closes whatever it opened and publishes a typed failure, never a partial session.
  - Keep bounded manual-refresh coalescing and healthy-session reuse: selecting the same healthy path does not reopen.
  - If a same-path file replacement is detected, it counts as a new generation (this links to T3).
- Tests (one per realistic bug):
  - **Barrier replacement**: a read blocks on a barrier, the database is switched, and the barrier is released. Assert that the old read's result is never published and that the open connection and observer counters return to 1.
  - **Failed initialization**: the store is missing or incompatible. Assert that no connection or observer leaks and that a typed failure is the only readiness emission.
  - **Cleanup callback**: an observer close callback calls into the owner. With a timeout guard, assert that it does not deadlock.
  - **Cancellation and exit**: `closeAndJoin` during an in-flight read leaves zero open resources.
  - Extend `DesktopCommitSignalObserverTest` or `RoomBoardObservationFailureTest` where they already own the fixture. Do not duplicate it.

**T3. AC-33: control admission bound to the exact opened store.**

- Reconcile:
  - Trace `WorkControlRepository.pause/stop/cancel/launch` → `RoomBoardSource.control/runAction` → `WorkControlService.request` (and `WorkRunActions`/`WorkRunLauncher` for launch and resume).
  - Find where the desktop checks the path and where the write connection opens.
- Required end state:
  - Admission compares the identity the session opened against the identity of the file the write connection actually holds. It does this inside the admitting transaction, alongside the existing repository, execution, revision and lease fences.
  - A mismatch returns a typed refusal and never writes.
- **Assumption**: a durable store identity value already exists in runtime-owned metadata. Implement confirms this. If none exists:
  - Use the file identity of the opened write connection (`Files.readAttributes(path).fileKey()` captured when opened, compared to the session's captured key), then read through that connection.
  - Only if neither is sound, add a store-identity value through a new named runtime `DatabaseMigration`. Never add it through an existing heal step.
  - A new migration must bump the desktop `DesktopReadSchema` version and keep the read-only compatibility test green.
  - The desktop never writes the identity.
- Typed outcomes:
  - Extend the shared result to a sealed set: `IntentRecorded`, `RequestAccepted`, `TerminationAttempted`, `ExitConfirmed`, `TerminationIncomplete`, and `Refused(reason)` with typed reasons, including `StoreReplaced`.
  - Map each outcome to distinct desktop feedback through the existing `Outcome`/`Problem` path.
  - Host waits for process exit stay outside the transaction. Acceptance never reports a lifecycle as complete.
- Tests:
  - **Same-path replacement**: replace the file between the desktop path check and admission, using a barrier. Assert the prior file is byte-unchanged and the result is the `StoreReplaced` refusal.
  - **Incomplete termination**: the process ignores termination within the bound. Assert the result is `TerminationIncomplete`, not stopped, and the desktop feedback text differs from confirmed exit.
  - **Ownership race**: two admissions for the same execution. Assert one is accepted and one is refused under the lease or revision fence.
  - Use disposable runtime-migrated databases.

**T4. AC-28: one runtime owner for shared work policy.**

- Reconcile:
  - List every place that computes aggregate status, membership (live, recent or history), attention ("Needs you"), keyless phase grouping (repository, phase and branch, with latest attempt first), sort order and control eligibility.
  - The digest points at desktop `DashboardController`/`BoardProjection`, `RuntimeWorkMapper`, `WorkCandidateQueries` and `RunActionButtons`, and at runtime `IdeStatusService`.
- Required end state:
  - A single runtime domain implementation owns these rules.
  - **Assumption**: it lives beside `IdeStatusService` under `skillbill/engine/work/` as a policy type. The implementer must name it and move it to runtime domain if runtime architecture rules place policy there.
  - It emits typed facts on the existing board and detail projection: status, attention flag, group key, sort key, and allowed controls with a refusal reason.
  - The desktop maps and renders those facts and deletes its own copies of these decisions. Engine adapters may narrow candidates conservatively in SQL, but they must not decide the outcome.
  - Update every caller of moved symbols, including `DashboardControllerTest` and `ProjectionTest`.
- Guard work, in the desktop architecture guard wherever it lives now:
  - Replace simple-name resolution with declaration-aware resolution. Key it by package, receiver and arity, and cover imports with aliases, qualified calls, overloads, extension receivers, expression bodies and typed bodies, and interface dispatch to the real implementing consumer.
  - Use the manifest-declared runtime roots read from `RuntimeModuleCatalog.kt`.
  - A governed edge that cannot be resolved fails. An empty scan root fails.
  - Pin the governed edges: the desktop reaches policy facts only through the runtime projection and control services (`IdeStatusService` or its successor, and `WorkControlService.request`), and no desktop production file re-implements eligibility. A forbidden-token check on the old policy symbol names suffices only if it runs through the same scanner.
  - Add rejection fixtures: synthetic Kotlin sources fed through the real scanner, covering an aliased import of a forbidden adapter, an extension call into a governed edge, an unresolvable edge, and an empty root. Each must fail with an attributable message. Add one accepted fixture.
- Tests: one projection test per policy rule that had divergent desktop and runtime behavior, asserting the typed fact. Skip rules that never diverged.

**T5. AC-29: bounded selection and request-wide query-work accounting.**

- Reconcile:
  - `IdeStatusService.board` (full work list and standalone candidates).
  - The live-board query (historical walk).
  - The legacy-identity path.
  - Coherence retries (how many, and whether each one re-reads unbounded).
  - The desktop's `WorkCandidateQueries` and `BoardPagingSource`.
- Required end state:
  - Live selection uses an indexed predicate on live state, keyset continuation and `LIMIT pageSize+1`.
  - History uses a retention cutoff with keyset paging.
  - Legacy identity rows are fetched through the same bounded selectors.
  - Coherence retries are capped by a budget, and each retry is itself bounded. When the budget runs out, the result is a typed outcome, not an unbounded fallback.
  - Do not add a new index unless it can go through a runtime-owned named migration (same rule as in T3).
- Accounting:
  - One request-scoped accumulator is passed through the persistence boundary.
  - It counts statements executed, rows returned, candidates rejected, items projected, and attempts split into succeeded, failed, retried and budget-exhausted. It records elapsed time from an injected clock.
  - It never logs rows or parameter values.
  - **Assumption**: SQLite filter and index work is attributed through `EXPLAIN QUERY PLAN` assertions in tests, checking that an index search is used and no full scan, plus a per-statement rows-examined proxy. JDBC exposes no VM-step counter. Implement confirms whether the driver offers a better counter.
- Tests:
  - Extend the existing 5,000-record fixture rather than adding a sibling.
  - Add older live work, rejected candidates, and a forced coherence retry that is injected through a test seam on the revision check.
  - Assert that live results include the older live work, that rows examined and returned stay within the page bound times attempts, that attempts are counted including the failed one, and that the query plan shows index use.
  - Add one runtime-side test at the same scale if the shared runtime selector changes.

**T6. AC-30: exact durable reasons.**

- Reconcile:
  - The decisions record says the full block reason is saved separately from bounded status text and that copy copies the full text. Verify this for board, detail, history, the copy action and visible tooltip text.
  - Check the selected-identity binding (latest execution ordering, keyless attempts) and the legacy restoration path.
- Required end state:
  - A typed reason fact with `label` (bounded) and `fullText` (exact).
  - A typed legacy restoration outcome: restored exactly, restored truncated legacy text, or unavailable.
  - Every consumer reads it for the selected identity's latest execution.
  - Keep the existing exact accepted execution output and latest-execution ordering.
- Tests:
  - Two repositories and two executions with long, distinct reasons. Assert that board, detail and history each show the selected identity's own reason, and that copy returns the exact full text.
  - A legacy row asserting the attributable restoration outcome.
  - If this is already satisfied, cite the existing tests and add nothing.

**T7. AC-32: independent bounded detail collections.**

- Reconcile: enumerate the growing detail collections (commands, checks, gate evidence, dependencies, validation, execution history) and how each is read by the runtime `IdeStatusService.detail` and the desktop `detail(identity, cursor, list, startOffset)`.
- Required end state:
  - Each collection has its own runtime selector in the `pagination/` family, bounded with keyset continuation.
  - Its cursor encodes store identity and generation, the relevant revision, the query, the parent identity and the list. A mismatched cursor is a typed refusal.
  - Each list owns its own append and retry. No generic action picks another list's cursor.
  - Keep the existing bounded status summaries.
  - Retained pages are re-read in one read transaction and published as one observation. They are anchored by item identity, not by offset. **Assumption**: `startOffset` turns into an identity anchor, and offset is kept only as a scroll hint.
- Tests:
  - Large nested evidence, for example 1,000 commands and 1,000 checks under one parent. Assert that the initial read is bounded per list and that appending to commands leaves checks unchanged.
  - Concurrent insert, update and delete between retained-page refreshes. Assert one coherent emission and that the anchor item stays visible.
  - A wrong-list or stale-revision cursor is refused.

**T8. AC-34: deadline ownership.**

- Reconcile:
  - Preserve the implemented owner-exit, freshness, lease and retained-page scheduling.
  - Find which deadlines (owner death, lease expiry, shared freshness, recent-completion expiry, history cutoff) are scheduled for the board versus independently subscribed detail.
  - Check whether one scheduler is keyed to the store generation, query and identity.
- Required end state:
  - A deadline scheduler per subscription owner is keyed by session generation, query, identity and retained range, and is owned by the T2 session scope.
  - Append, eviction, refreshed ranges, selection change and replacement recompute the earliest deadline and reschedule.
  - When a deadline fires, it reprojects from already-held data or through one bounded read triggered by the deadline. There is no periodic polling and no writes.
  - A callback whose owner token is obsolete does nothing.
- Tests:
  - Use a virtual-time scheduler.
  - Controlled time with an earlier deadline arriving on append, so the earlier deadline wins.
  - A selection change cancels the old identity's timer.
  - Replacement: an old-generation timer fires after replacement and does not invalidate the new owner.
  - Assert a store-read counter of zero between deadlines.

**T9. Close out the handoff.**

- Update the "remaining data gaps" paragraph in `../../../desktop-tray/ARCHITECTURE.md` to match the verdicts. Remove fixed gaps and keep honest residuals.
- Complete every row of `../../../desktop-tray/docs/skill-423-handoff.md` except the executed results.
- Produce the A1-A12 and G1-G7 report for the touched runtime code in the implement summary.

### Constraints

- Runtime architecture rules stay authoritative:
  - No baseline, exemption or suppression grows.
  - Keep the inbound API inventory names and the rejection fixtures.
  - Guards anchor `package` and `import` at column 0.
  - Raw-map guards stay tied to live contracts.
  - New component exports need production readers.
  - Composition tests live under `skillbill.di` in runtime-core.
- The desktop reaches the runtime only through the existing composite graph (`RuntimeComponent`). There is no second graph, no CLI fallback and no polling.
- Room access stays read-only and never migrates. Control writes go through runtime admission only.
- Keep the sidecar commit-signal observation, silent baselines and epoch fencing.
- Placement:
  - New desktop code follows the parent module ownership if subtask 1 or 2 modules exist: domain types in `core:domain`, session, repositories and the runtime adapter in `core:data` (JVM adapter in `jvmMain`).
  - Otherwise new code extends the current owners. No placeholder modules.
  - Do not add `java.time` to common domain code.
- Mocks use `relaxUnitFun = true` and never `relaxed = true`.
- Test environments pass an explicit non-empty environment map.
- Storage tests use disposable runtime-migrated databases. Never touch production databases or delete WAL/SHM files.
- The tree must leave `desktop-tray` and `runtime-kotlin` both compiling with checks green. When a symbol moves, update every caller.
- No install, uninstall or install-sync commands in this subtask.

### Test obligations summary

Each obligation names its AC and the bug it catches:

- **AC-31**: stale read published after replacement; leak on failed initialization; deadlock in a cleanup callback.
- **AC-33**: write to the replaced file; fabricated "stopped" feedback.
- **AC-28**: scanner misses an aliased or extension edge; scanner passes vacuously; policy diverges.
- **AC-29**: history walk starves live work; undercounted retry work.
- **AC-30**: wrong-execution or truncated reason.
- **AC-32**: full materialization; wrong-list append; displaced anchor.
- **AC-34**: missed earlier deadline; obsolete timer invalidates the new owner.

Where an obligation is already satisfied, the existing tests are its evidence and no new test is added.

### Validation (owned by the validate phase, not implement)

Validate runs the affected runtime and desktop tests, then `cd runtime-kotlin && ./gradlew check`, then `cd desktop-tray && ./gradlew check`. It records each result in the handoff record's executed-result column.
