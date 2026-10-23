# SKILL-435 subtask 5: shared incident presentation in terminal and desktop

## Scope

Show the same saved incident account in the terminal and in the desktop activity panel for goals and standalone phases: progress, cause, next action, evidence, recovery availability and progress, report status and telemetry availability, with history kept after resume. Add the desktop Recover button on top of subtask 3's runtime control. All decisions stay in the runtime; the terminal and desktop only render one runtime-built projection.

This subtask covers intake paragraph 5 and the presentation parts of paragraphs 3, 6 and 9, which make up parent criterion AC-004.

## Acceptance Criteria

1. The runtime builds one incident projection for work detail, shared by every presentation.
   `WorkDetailItem` in `runtime-ports/.../idestatus/model/WorkDetailModels.kt` gains `incident: WorkIncidentProjection?` and `incidentHistory: List<WorkIncidentProjection>` (newest first, max 10), with the new types in a new file beside it. `WorkIncidentProjection` holds `incidentToken`, `revision`, `progress`, `summary`, `cause`, `certainty`, `responsibleCategory`, `nextAction` text, `noWorkaroundKnown`, `prerequisites` (kind and instruction), `evidence` (kind, reference id, summary, `trusted`), `degradations`, `recovery` (eligibility result, preview label, effects, latest attempt id and status, failure), `report` (status, failure reason, issue url), `telemetry` (availability and search identifiers), `observedAt` and `settledAt`.
   `progress` is enum `WorkIncidentProgress {INVESTIGATION_PENDING, INVESTIGATING, DIAGNOSIS_COMPLETE, DIAGNOSIS_PARTIAL, DIAGNOSIS_FAILED, NEEDS_PREREQUISITES, RECOVERY_AVAILABLE, RECOVERING, RECOVERY_FAILED, RESUMED}`, derived in this order: latest recovery attempt `RESUMED` gives `RESUMED`; `REQUESTED`, `APPLYING`, `APPLIED` or `RESUMING` gives `RECOVERING`; `FAILED` gives `RECOVERY_FAILED`; then eligibility `Available` gives `RECOVERY_AVAILABLE` and `NeedsPrerequisites` gives `NEEDS_PREREQUISITES`; then diagnosis `PENDING` gives `INVESTIGATION_PENDING`, `INVESTIGATING` gives `INVESTIGATING`, `COMPLETE` gives `DIAGNOSIS_COMPLETE`, `PARTIAL` or `UNAVAILABLE_AGENT` gives `DIAGNOSIS_PARTIAL`, and `EXHAUSTED`, `TIMED_OUT`, `CANCELED`, `PERSIST_FAILED` or `CRASHED` gives `DIAGNOSIS_FAILED`.
   `nextAction` is composed in the runtime: the preview label when recovery is available, the first prerequisite instruction when prerequisites are unmet, `No known workaround. The block has been reported.` or `No known workaround.` when `noWorkaroundKnown`, and `Waiting for investigation.` while pending.
   A new `WorkIncidentProjectionBuilder` in `runtime-engine/.../engine/work/` reads incidents linked to the work item's workflow or invocation through the subtask 1 to 4 repositories and `BlockIncidentTelemetry.availability`; `IdeStatusService` only calls it and does not grow beyond the call. Incidents remain in `incidentHistory` after the workflow resumes.
   Rendering is criteria 2 through 5. Audit confirms by reading the model, the progress order and the builder call.
2. The CLI JSON work detail carries the projection under stable wire keys.
   `workDetailItemWireMap` in `runtime-cli/.../cli/work/WorkDetailPayloadMapping.kt` adds `incident` and `incident_history`, each mapped by a new function in a new file `WorkIncidentPayloadMapping.kt`. Wire keys are snake case of the field names above and live in a new runtime-contracts keys object; enum values use the wire values defined in subtasks 2 to 4. Absent optional fields are omitted, and `incident` is `null` when no incident exists.
   Each serialized incident includes `recover_command`: the exact `skill-bill work recover <work-id> --incident <token> --revision <n>` text, plus one `--confirm-prerequisite <kind>` per unmet prerequisite, present only when `progress` is `RECOVERY_AVAILABLE` or `NEEDS_PREREQUISITES`.
   Terminal text is criterion 3. Audit confirms by reading the mapping and the keys object.
3. Terminal status and run output print the same saved cause, next action and recover command.
   `GoalCliStatusTextLines` and `GoalCliStatusFormatting` under `runtime-cli/.../cli/goal/status/`, `FeatureTaskRuntimeRunReportPresentation` and `RuntimeRunProgressOutput` for run output, and the phase output in `cli/phase/PhaseCommand.kt` print an incident block when a projection exists. It holds a status label line (`Investigating block`, `Investigation queued`, `Diagnosis ready`, `Partial diagnosis`, `Diagnosis unavailable`, `Needs your action`, `Recovery available`, `Recovering`, `Recovery failed`, `Resumed`), `Cause:` with the cause or `Unknown` and the certainty word `diagnosed`, `suspected` or `unknown`, `Next:` with `nextAction`, `Recover:` with `recover_command` when present, `Report:` with the status and issue url or failure reason, and `Telemetry:` with availability and the search identifiers. Evidence references appear only in verbose status output.
   Example for a blocked goal: `Recovery available` / `Cause: Provider returned 529 overloaded (suspected)` / `Next: Reopen blocked phase` / `Recover: skill-bill work recover SKILL-435 --incident bi_3fa1... --revision 4`.
   Text is built from the same projection the JSON uses, so terminal and JSON never disagree. Audit confirms by reading the formatters and their projection input.
4. The desktop data and domain layers map the projection and a typed Recover control without holding recovery logic.
   `core/domain/.../model/WorkModels.kt` gains `Incident` and `IncidentHistory` domain types and `Detail.incident`. `RuntimeWorkMapper` maps `WorkIncidentProjection` field by field. Port type `ControlAction` gains `Recover(incidentToken, revision, confirmedPrerequisites)` and `ControlRefusal` gains `StaleIncident`, `RecoveryUnavailable`, `PrerequisiteUnmet` and `RecoveryInProgress`.
   `RuntimeWorkAdapter` in `core/data/src/jvmMain/.../runtime/` maps `Recover` to `runtime.workControlService.recover(...)` and fetches previews through `recoveryPreview`, mapping each `WorkControlRejection` to the matching refusal. The desktop keeps no writable database and no recovery coordinator; it re-reads detail after every control result.
   When the installed runtime predates the incident projection, `InstalledRuntimeBuildGate` reports the runtime as incompatible for incident detail and the panel shows `Incident details need a newer Skill Bill runtime.` instead of failing, following `DesktopReadSchema.VERSION` and the existing gate response.
   Rendering is criterion 5. Audit confirms by reading the mapper, the adapter control branch and the gate check.
5. The desktop activity panel renders the incident accessibly, with a confirmed Recover action and retained history, for goals and standalone phases.
   In `feature/detail/DetailContent.kt`, the existing `Goal activity`, `Phase activity` and `Operation activity` panel shows an incident section above the activity list for any lifecycle, including standalone phases, in a new `IncidentPanel.kt` file so `DetailContent.kt` does not grow past its ceiling. The status uses `DesignLabel` with the same label text as the terminal plus a color from the design system (orange for blocked and failed states, muted for pending and resumed), so color never carries meaning alone. Cause and next action are the first two lines in body size; prerequisites follow as instructions.
   Technical evidence sits in a collapsed `Evidence` section that expands on click, Enter or Space, and each reference id and the search identifiers have a copy affordance with an accessible name `Copy <reference>`. The report line shows its status and an `Open issue` link when an issue url exists, or the failure reason (for example `Not submitted: GitHub credentials not found`).
   `RunActionButtons.kt` shows `Recover` only when `progress` is `RECOVERY_AVAILABLE`, or `NEEDS_PREREQUISITES` once every prerequisite checkbox is ticked. Activation opens a confirmation showing the preview label, effects and target, with `Apply and resume` and `Cancel`; focus moves into the confirmation and returns to `Recover` on close. While recovering, the button is disabled with label `Recovering...`; a refusal shows its message inline. `DetailContract` and `DetailViewModel` add `DetailAction.Recover` and `DetailAction.ConfirmPrerequisite`.
   A `History` subsection lists earlier incidents with label, cause and time, and stays after resume.
   Audit confirms by reading the panel, button visibility rule, confirmation focus handling and history rendering.

## Criterion Sizing

1. About 280 production lines, 4 files, 2 areas (`runtime-ports` idestatus model, `runtime-engine` work). Fits.
2. About 160 production lines, 3 files, 2 areas (`runtime-cli` work, `runtime-contracts` keys). Fits.
3. About 200 production lines, 6 files, 1 area (`runtime-cli` goal, phase and featuretask presentation). Fits.
4. About 220 production lines, 6 files, 2 areas (desktop `core/data`, desktop `core/domain`). Fits.
5. About 300 production lines, 5 files, 1 area (desktop `feature/detail`). Fits.

## Non-Goals

No new desktop writable database or UI recovery coordinator. No policy in the CLI, desktop adapters or Compose code: eligibility, progress and next-action text all come from the runtime. No PostHog URL. No layout-pinning or copy-pinning tests.

## Dependency Notes

Depends on subtasks 1 through 4: incident lifecycle, diagnosis record, recovery control and preview, report record and telemetry availability.

## Test Obligations

1. T5.1 progress derivation. In `IdeStatusServiceTest` or a new builder test, an incident whose latest attempt is `FAILED` but whose eligibility is again `Available` reports `RECOVERY_FAILED`, and a resumed incident stays in `incidentHistory`. Realistic bug: a failed recovery is shown as available and history disappears after resume.
2. T5.2 terminal and JSON agree. One projection with unmet prerequisites yields a `recover_command` with `--confirm-prerequisite` in JSON and the same command in status text. Realistic bug: the terminal prints a command that the runtime rejects.
3. T5.3 desktop recover control. In desktop `jvmTest` with `WorkFixtures`, `Recover` is hidden for `DIAGNOSIS_PARTIAL`, shown for `RECOVERY_AVAILABLE`, and a `StaleIncident` refusal renders its message and re-reads detail. Realistic bug: the button shows for an unrecoverable block or a stale click silently does nothing.
4. T5.4 accessibility. The evidence section expands with the keyboard and the confirmation returns focus to `Recover`. Realistic bug: keyboard users cannot reach evidence or lose focus after cancel.

## Validation Strategy

Implement and audit read the tree only. Validate runs runtime-kotlin `./gradlew check --no-build-cache`, `../../../scripts/validate_agent_configs`, the affected desktop module checks (Linux Xvfb where needed), and under desktop-tray `./gradlew clean check createDistributable --no-build-cache --no-configuration-cache` plus `packagedDataSmoke`. `ProductionFileLineCeilingArchitectureTest` covers `IdeStatusService.kt` and `DetailContent.kt`. Review applies architecture-guidelines section 5 with rule IDs and the UI and accessibility checklists.

## Implementation Details

Ordered tasks. Each names the criteria it serves, the paths or symbols it touches, its tests and its constraints. Symbol names that come from subtasks 1 to 4 (diagnosis record, recovery attempt and eligibility, recovery preview, report record, `BlockIncidentTelemetry.availability`, `WorkControlService.recover` and `recoveryPreview`, the new `WorkControlRejection` values) are assumed as written in those sub-specs. Implement confirms the landed names first and adapts to them without changing behavior.

1. Projection model (AC 1).
   - New file `runtime-ports/src/main/kotlin/skillbill/ports/idestatus/model/WorkIncidentModels.kt` beside `WorkDetailModels.kt`. It holds `WorkIncidentProjection` with the fields listed in criterion 1, enum `WorkIncidentProgress` in the order given there, and small value types for prerequisite (kind, instruction), evidence (kind, reference id, summary, `trusted`), recovery (eligibility, preview label, effects, latest attempt id and status, failure), report (status, failure reason, issue url) and telemetry (availability, search identifiers).
   - Reuse the subtask 2 to 4 enums for certainty, responsible category, attempt status, report status and telemetry availability, so no second vocabulary exists. If an enum lives in a module `runtime-ports` cannot see, add a ports-side enum whose values map one to one, and note the mapping in the builder.
   - `WorkDetailItem` in `WorkDetailModels.kt` gains `incident: WorkIncidentProjection? = null` and `incidentHistory: List<WorkIncidentProjection> = emptyList()`. Defaults keep the existing constructors and fixtures compiling.
   - Interface KDoc follows `CommentAndInterfaceKdocArchitectureTest`. No inline FQNs.

2. Projection builder (AC 1).
   - New file `runtime-engine/src/main/kotlin/skillbill/engine/work/WorkIncidentProjectionBuilder.kt`, `@Inject`-constructed, taking the incident, diagnosis, recovery and report repositories via `UnitOfWork` and the `BlockIncidentTelemetry` availability source.
   - `build(unitOfWork, workflowId, invocationId): Pair<current, history>` or a small result type. It reads incidents linked by `workflowId` (goal and feature-task work) or by `invocationId` (standalone phases), newest first by `observedAt`. Assumption for implement to confirm: the subtask 1 repository exposes a by-workflow and by-invocation listing. If it only has `find`/`pending`, add a bounded `listForWork(workflowId, invocationId, limit)` read to the port, the in-memory fixture and `SqliteBlockIncidentRepository`, backed by the existing `idx_block_incidents_workflow` index. Do not grow `SqliteBlockIncidentRepository.kt` past its line ceiling; put the query in a sibling file if needed.
   - Current incident: the newest one whose block is still the workflow's live block. History: up to 10 others, newest first. A resumed incident (latest attempt `RESUMED`) always moves to history and stays there.
   - `progress` is a pure private function implementing the exact order in criterion 1. `nextAction` is a pure function in the same file: preview label when eligibility is `Available`, first unmet prerequisite instruction when `NeedsPrerequisites`, `No known workaround. The block has been reported.` when `noWorkaroundKnown` and the report status is delivered, `No known workaround.` when `noWorkaroundKnown` otherwise, `Waiting for investigation.` while diagnosis is `PENDING` or `INVESTIGATING`. Assumption: when none of these apply (a settled diagnosis with no recovery and no flag), `nextAction` is the diagnosis's own next-step text from subtask 2. Implement confirms that field.
   - Recovery eligibility comes from the subtask 3 eligibility evaluation, called read-only. Never mutate.
   - `IdeStatusService.kt` gains only the injected builder and one call that sets `incident` and `incidentHistory` on the built `WorkDetailItem`. It must not grow beyond that (`ProductionFileLineCeilingArchitectureTest`). Bind nothing outside `RuntimeComponent`.
   - Tests: T5.1 in a new `WorkIncidentProjectionBuilderTest` under `runtime-engine/src/test/kotlin/skillbill/engine/work/`, using `InMemoryBlockIncidentRepository` and the subtask 2 to 4 fixtures. One case: latest attempt `FAILED` with eligibility `Available` gives `RECOVERY_FAILED`. One case: a `RESUMED` incident sits in `incidentHistory` after the workflow is no longer blocked. No per-enum mirror tests.

3. Wire keys and CLI JSON (AC 2).
   - New keys object in `runtime-contracts`, e.g. `skillbill.contracts.work.WorkIncidentWireKeys` next to the existing work detail keys, holding the snake-case keys for every projection field plus `incident`, `incident_history` and `recover_command`. `WireVocabularyArchitectureTest` requires wire strings to live there.
   - New file `runtime-cli/src/main/kotlin/skillbill/cli/work/WorkIncidentPayloadMapping.kt` with `workIncidentWireMap(projection, workId)`. Enum values use their subtask 2 to 4 wire values. Optional fields are omitted when absent.
   - `recoverCommand(workId, projection): String?` lives in that file and is the single source for the command text: `skill-bill work recover <work-id> --incident <token> --revision <n>` plus `--confirm-prerequisite <kind>` per unmet prerequisite, only when progress is `RECOVERY_AVAILABLE` or `NEEDS_PREREQUISITES`. Terminal text calls the same function.
   - `workDetailItemWireMap` in `WorkDetailPayloadMapping.kt` adds `incident` (explicit `null` when absent) and `incident_history`.
   - Assumption: the subtask 3 CLI command is `skill-bill work recover` with exactly these flags. If it landed with other flag names, the command text follows the landed command.

4. Terminal text (AC 3).
   - New file `runtime-cli/src/main/kotlin/skillbill/cli/work/WorkIncidentTextLines.kt` (or under `cli/goal/status/` if the existing formatters need same-package access) with `incidentTextLines(projection, workId, verbose): List<String>`. It emits the status label mapped from `WorkIncidentProgress` (`INVESTIGATION_PENDING` to `Investigation queued`, `INVESTIGATING` to `Investigating block`, `DIAGNOSIS_COMPLETE` to `Diagnosis ready`, `DIAGNOSIS_PARTIAL` to `Partial diagnosis`, `DIAGNOSIS_FAILED` to `Diagnosis unavailable`, `NEEDS_PREREQUISITES` to `Needs your action`, `RECOVERY_AVAILABLE` to `Recovery available`, `RECOVERING` to `Recovering`, `RECOVERY_FAILED` to `Recovery failed`, `RESUMED` to `Resumed`), then `Cause:`, `Next:`, `Recover:` from `recoverCommand`, `Report:` and `Telemetry:`. Evidence lines only when `verbose`.
   - The label map is presentation vocabulary, not policy. Put it in one place the CLI owns. Desktop keeps its own copy of the same strings in task 7 because it cannot depend on `runtime-cli`. Assumption: if a shared runtime-contracts label table is cleaner, implement may move the labels there and both sides read it.
   - Call `incidentTextLines` from `GoalCliStatusTextLines` / `GoalCliStatusFormatting`, `FeatureTaskRuntimeRunReportPresentation`, `RuntimeRunProgressOutput` (blocked run output) and `PhaseCommand.kt` (standalone phase output) whenever a projection exists. Each caller passes the projection it already has from work detail. Where a caller does not have the detail item (run report and phase output), it reads it through `IdeStatusService.detail` for the run's work id after the block, read-only.
   - Tests: T5.2 in one CLI test (e.g. `WorkIncidentPayloadMappingTest`). A projection with two unmet prerequisites gives a `recover_command` containing both `--confirm-prerequisite` flags in the JSON map, and the `Recover:` line of `incidentTextLines` equals the same string.

5. Desktop domain and port types (AC 4).
   - `desktop-tray/core/domain/.../model/WorkModels.kt`: add `Incident`, `IncidentHistory` (or `List<Incident>`), the small value types, an `IncidentProgress` enum mirroring `WorkIncidentProgress`, and `Detail.incident` plus `Detail.incidentHistory`, with defaults.
   - Port types in `core/data/port`: `ControlAction.Recover(incidentToken, revision, confirmedPrerequisites: Set<String>)` and `ControlRefusal.StaleIncident`, `RecoveryUnavailable`, `PrerequisiteUnmet`, `RecoveryInProgress`. Add a `recoveryPreview(workId, incidentToken)` read on `RuntimeWorkGateway` if the panel needs a fresh preview beyond the projection's preview label.
   - `RuntimeWorkMapper.kt`: map `WorkIncidentProjection` field by field. No derivation.
   - Update `core/testing/WorkFixtures.kt` with an incident fixture builder.

6. Desktop adapter and compatibility gate (AC 4).
   - `RuntimeWorkAdapter.kt` (`core/data/src/jvmMain/.../runtime/`): map `ControlAction.Recover` to `runtime.workControlService.recover(...)`, map each recovery `WorkControlRejection` (stale revision or changed target to `StaleIncident`, ineligible to `RecoveryUnavailable`, unmet prerequisite to `PrerequisiteUnmet`, attempt in flight to `RecoveryInProgress`, others to the existing refusals) and route preview reads to `recoveryPreview`. After every control result the view model re-reads detail. The adapter holds no state.
   - `InstalledRuntimeBuildGate.kt`: add an incident-detail capability check. Decision: compare the installed runtime's `DesktopReadSchema.VERSION` with a new constant for the first version that carries the incident tables from subtasks 1 to 4. Implement confirms whether those subtasks already bumped `DesktopReadSchema.VERSION` with their migrations (76+). If not, bump it here in `runtime-contracts/.../persistence/DesktopReadSchema.kt` and set the constant to the new value. An older runtime gives an `incidentDetailUnavailable` flag on the detail projection, not an exception, and the panel shows `Incident details need a newer Skill Bill runtime.`
   - No writable database, no recovery coordinator in desktop.

7. Desktop incident panel (AC 5).
   - New file `desktop-tray/feature/detail/.../IncidentPanel.kt`. `DetailContent.kt` gets only the call that places it above the activity list in the `Goal activity`, `Phase activity` and `Operation activity` panel for every lifecycle, including standalone phases. Drop the FEATURE_GOAL-only condition for the incident section. Do not grow `DetailContent.kt` past its ceiling.
   - Status: `DesignLabel` with the terminal label text. Orange for `DIAGNOSIS_FAILED`, `NEEDS_PREREQUISITES`, `RECOVERY_FAILED`, `DIAGNOSIS_PARTIAL` and `RECOVERY_AVAILABLE`; muted for `INVESTIGATION_PENDING`, `INVESTIGATING`, `RECOVERING` and `RESUMED`; default for `DIAGNOSIS_COMPLETE`. The text label always carries the meaning.
   - Body: cause and next action as the first two body-size lines, then prerequisites as instructions with a checkbox each when progress is `NEEDS_PREREQUISITES`.
   - `Evidence`: collapsed by default, toggled by click, Enter or Space through a focusable header with expanded/collapsed semantics. Each reference id and each telemetry search identifier gets a copy button with content description `Copy <reference>`.
   - Report line: status plus an `Open issue` link when an issue url exists, else the failure reason text from the projection.
   - `History`: earlier incidents with label, cause and observed time. It renders from `incidentHistory` and stays after resume.
   - Incompatible runtime: the single message from task 6.

8. Recover button and view model (AC 5).
   - `RunActionButtons.kt`: a `Recover` `DesignButton`, visible when progress is `RECOVERY_AVAILABLE`, or `NEEDS_PREREQUISITES` with every prerequisite ticked. While `RECOVERING` or while a request is in flight, it is disabled with label `Recovering...`.
   - Activation opens a confirmation dialog showing the preview label, effects and target, with `Apply and resume` and `Cancel`. A `FocusRequester` moves focus into the dialog on open and back to `Recover` on close.
   - `DetailContract`: `DetailAction.Recover` and `DetailAction.ConfirmPrerequisite(kind, checked)`. `DetailViewModel` keeps only the ticked set and the in-flight flag as UI state, sends `ControlAction.Recover`, shows a refusal message inline and re-reads detail after every result.
   - Tests: T5.3 in `feature/detail/src/jvmTest` with `WorkFixtures`. `Recover` is hidden for `DIAGNOSIS_PARTIAL` and shown for `RECOVERY_AVAILABLE`. A `StaleIncident` refusal renders its message and triggers a detail re-read (assert the fake gateway served a second detail and the new state shows). T5.4 in the same area: Enter on the evidence header expands it, and cancelling the confirmation returns focus to `Recover`. No layout- or copy-pinning tests.

9. Docs.
   - `../../../docs/runtime-command-guidance.md`: describe the incident block in status output and the `recover_command` field. Keep it short. Subtask 3 owns the command's own doc.

Constraints across tasks:
- All eligibility, progress and next-action logic stays in the runtime builder. CLI and desktop only render.
- New production code goes in new files. `IdeStatusService.kt`, `DetailContent.kt`, `BlockIncident.kt` and `SqliteBlockIncidentRepository.kt` do not grow beyond a call or a field.
- Wire strings live in runtime-contracts. No inline FQNs. Composition only in `RuntimeComponent`.
- Preserve the unrelated WIP edits in desktop `WorkFormat.kt`, `DetailContent.kt` and `PhaseResults.kt`.
- No PostHog URL. Show search identifiers only.
- This plan runs no build, test or check. Validate owns that.

## Next path

```bash
skill-bill goal SKILL-435
```
