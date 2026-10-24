# SKILL-442 subtask 7 - goal assessment PR, status, and desktop surfaces

## Scope

Parent criterion AC-005, part 3 of 3: open findings reach the PR description, `skill-bill goal status`, and the desktop goal view, each with its evidence. Every surface reads the subtask 5 assessment record through `GoalRunnerWorkflowOutcomeStore`. Telemetry belongs to subtask 8.

## Acceptance Criteria

1. The goal PR body reports the assessment.
   `GoalRunnerBranchPlan.toPullRequestRequest` takes the goal's assessment record, populated in `GoalRunnerFinalization.openPullRequest` the way `goalQaSummary` is, and renders a `Goal assessment` block after the existing `- Goal:` line.
   The block states the exit reason and the number of rounds. It then lists, each with ref, severity, `path:line`, failure scenario, and verification evidence: open findings (every ref in `open_finding_refs`, marking those fixed in the last round as `fixed, not re-verified`), unverified Blockers and Majors, refuted findings with the refuting evidence, `pr_only` Minors and test-value findings (with `guarded_ref`), and `not_restored` regressions still open. A failed or malformed assessor or verifier run adds a warning line naming its role and subtask.
   A record with `skipped_no_changes`, or no record, renders one line saying assessment did not run and why. The block sits next to subtask 3's per-subtask `Interaction scenarios not traced:` lines without replacing them. Audit confirms it by reading the renderer and its call site.
2. The goal status projection and CLI expose the stage.
   `GoalRunnerStatusProjectionContext` and the models in `runtime-domain/.../goalrunner/model/GoalRunnerStatusProjectionModels.kt` gain a goal assessment result. It holds `state` (`pending`, `running`, `completed`, or `skipped`), `round`, `exitReason`, and `findings`, each with ref, severity, path, line, failure scenario, `verification` (`confirmed`, `refuted`, `unverified`, `not_required`), `state`, and evidence.
   A new file beside `GoalRunnerStatusProjectionAssembler` fills it from the record, because the assembler is near the line ceiling. When the projection's wire form is governed by `IdeStatusContractVersions` or `DesktopReadSchema`, that version moves forward per the AGENTS.md sequence, with its parity test.
   `GoalCliStatusTextLines` prints `goal_assessment: round=<n> exit=<reason> confirmed=<n> refuted=<m> open=<refs>` when a record exists. Audit confirms it by reading the models, the filler, the version constant, and the CLI line.
3. The desktop goal view shows the stage, round, and findings.
   `desktop-tray/core/data/.../runtime/RuntimeWorkMapper.kt` maps the projection's goal assessment result into a desktop model beside `GoalQaFinding`. `desktop-tray/feature/detail` (`DetailContent.kt`, `PhaseResults.kt`) renders a `Goal assessment` section in the goal activity view. The section shows the state, the round, the exit reason, and each finding with severity, `path:line`, failure scenario, evidence, and a confirmed, refuted, or unverified label. Open findings are listed first.
   The progress pipeline shows `goal_assessment` (and `goal_assessment_fix` when a fix ran) as stage pills only for goals whose record exists. `PhaseResultsPreview.kt` gets a preview sample. Audit confirms it by reading the mapper, the composables, and the pipeline pill rule.

## Criterion Sizing

1. About 110 production lines, 2 files, 1 area (`runtime-engine` goalrunner execution). Fits.
2. About 170 production lines, 5 files, 3 areas (`runtime-domain` goalrunner model, `runtime-engine` goalrunner status, `runtime-cli` goal status). Fits.
3. About 220 production lines, 5 files, 2 areas (`desktop-tray` core/data, `desktop-tray` feature/detail). Fits.

## Interaction Scenarios

1. IS-001. Criteria: AC-001, AC-002, AC-003.
   Entry point: goal finalization opening the PR, then `skill-bill goal status`, then the desktop goal view.
   Preconditions: assessment exited `persistent_finding` with GA-002 (Major) open and GA-004 refuted.
   Steps: the PR is built; the operator reads status; the operator opens the goal in the desktop app.
   Outcome: all three show GA-002 as open and confirmed with its `path:line` and failure scenario, and GA-004 as refuted with its evidence.
2. IS-002. Criteria: AC-001, AC-003.
   Entry point: the same surfaces.
   Preconditions: the goal had no changes, so the record says `skipped_no_changes`.
   Steps: the PR is built and the desktop view opens.
   Outcome: the PR has one line saying assessment did not run and why; the desktop pipeline shows the stage as skipped, with no findings section content.
3. IS-003. Criteria: AC-002, AC-003.
   Entry point: the desktop goal view during a running round 2.
   Preconditions: the stored record has round 1 complete and round 2 assessors running.
   Steps: the view refreshes.
   Outcome: it shows `running`, round 2, and round 1's findings with their states.

## Non-Goals

- Telemetry and `goal-stats` (subtask 8).
- Changing the goal QA presentation.

## Dependency Notes

Depends on subtask 6 for complete records and on subtask 3 for the neighbouring per-subtask PR lines.

## Test Obligations

- PR body test from the parent constraints: a finding that survived two fix rounds appears in the PR body with its `path:line`, failure scenario, and evidence. Bug caught: the loop ends correctly but the operator never sees what was left open.
- Desktop mapping test: a refuted finding renders with the refuted label and an open one with confirmed. Bug caught: the mapper drops `verification` and every finding looks open. Extend `DetailPhaseResultsTest`.

## Validation Strategy

The validate phase runs the runtime-kotlin Gradle `check` and the desktop-tray build and tests. Nothing runs here.

## Implementation Details

### Evidence and implementation boundaries

Start from the supplied preplan digest. Targeted source inspection at revision `5c27271b9d4f5af618a78e00220cf0606f2e3360` resolves the presentation seams below. No external service capability is needed. This subtask renders locally owned assessment data; it does not launch assessors, change assessment decisions, or infer verification from pipeline verdicts.

- AC-001: `runtime-kotlin/runtime-engine/src/main/kotlin/skillbill/engine/goalrunner/execution/support/GoalRunnerBranchPlan.kt`, `toPullRequestRequest`, renders the existing `- Goal:` line at line 55. `execution/core/GoalRunnerFinalization.kt`, `openPullRequest`, supplies the goal QA summary and child outcome lookups at lines 138-160. Extend that production call path with the parent assessment record and a dedicated assessment renderer. Later validation observes the actual generated PR request body.
- AC-002: `runtime-kotlin/runtime-domain/src/main/kotlin/skillbill/goalrunner/model/GoalRunnerStatusProjectionContext.kt:75` copies `extras.goalQaResult` into the projection. `runtime-engine/.../goalrunner/status/GoalRunnerStatusProjectionAssembler.kt` fills QA data in both status assembly paths at lines 157 and 301 and propagates it through its assembly data at lines 321 and 364. Add assessment alongside these paths, using a new assessment filler beside the assembler. Later validation observes the status service result and CLI output, including a refresh during round 2.
- AC-002 and AC-003: `runtime-engine/.../work/IdeStatusService.kt:611` provides the finish pipeline and `runtime-ports/.../idestatus/model/WorkDetailModels.kt:59` carries it beside typed `goalStatus`. `desktop-tray/core/data/src/jvmMain/kotlin/dev/skillbill/tray/core/data/runtime/RuntimeWorkMapper.kt:116` maps `WorkDetailItem` directly into `Detail`. The desktop therefore needs typed projection plumbing, not a new database reader or a second assessment store.
- AC-002: `orchestration/contracts/work-detail-schema.yaml:40` allows extension properties on the item, while explicitly governing `goal_finish_pipeline` at line 71. `runtime-cli/.../kernel/work/WorkDetailPayloadMapping.kt:78` serializes the goal projection through `goalStatusWireMap`. Explicitly govern and serialize the new assessment result there. `IdeStatusContractVersions.kt` currently declares work-detail version `1.2`; `WorkPayloadSchemaContractVersionTest` proves schema/version parity. `DesktopReadSchema.VERSION` is a database-read schema version, currently 83. This presentation-only extension needs a work-detail contract bump, not a database migration or an IDE snapshot field.
- AC-003: `desktop-tray/feature/detail/src/commonMain/kotlin/dev/skillbill/tray/feature/detail/DetailPresentation.kt:90`, `goalFinishSteps`, marks every step complete for terminal goals. `DetailContent.kt:320` renders those steps as chips and lines 359-384 gate the activity panel and render QA. Extend the assessment-specific stage handling so a skipped assessment stays skipped, and ensure an assessment-only activity record opens the panel.

Subtask 5 owns the authoritative assessment record contract and `GoalRunnerWorkflowOutcomeStore` read capability. Subtask 6 supplies complete durable rounds, open refs, fix state, exit reasons, and failed-role diagnostics. These are authorized local prerequisites, not assumptions about an external API. Consume those owners' typed models and wire tokens after those dependencies land. The required input is the current round/state plus retained findings, verification evidence, `open_finding_refs`, regression dispositions, `pr_only` dispositions, test `guarded_ref` values, and role/subtask diagnostics. Subtask 3 supplies the neighbouring unknown-interaction PR lines. Do not recreate any of these records from audit receipts or change their owners here.

### Ordered tasks

1. Define the assessment presentation contract and typed result. Serves AC-002 and supplies AC-001/AC-003.
   - Extend `orchestration/contracts/work-detail-schema.yaml` with an optional, explicitly shaped assessment result. Govern state, round, nullable in-progress exit reason, finding ref/severity/location/failure scenario/verification/state/evidence, and the data required to identify open findings. Preserve the record's distinction between verification and fix state. A fixed finding awaiting another read remains `fixed, not re-verified` even when its last verification was confirmed.
   - Follow schema-first sequencing. Advance `WORK_DETAIL_CONTRACT_VERSION` from `1.2` to `1.3`, or the next version if a prerequisite already advanced it. Add keys to the owning work-detail vocabulary, reusing upstream assessment keys and enum `wireValue` tokens rather than restating them. Update `WorkPayloadSchemaContractVersionTest` and the governed schema validator fixtures. Retain `WorkFailureCode.INVALID_WORK_DETAIL_SCHEMA` as the typed failure for malformed work-detail output; this extends an existing contract, so it does not add a second parse failure category.
   - Add a goal assessment result beside the domain goalrunner projection models and carry it through `GoalRunnerStatusProjectionRuntimeInputs`, `GoalRunnerStatusProjectionContext`, and `assembleGoalRunnerStatusProjection`. Engine and CLI are the cross-module consumers of the shared result; desktop mapping consumes it through `WorkDetailItem`. Keep domain values pure and typed. No raw maps or I/O enter domain.
   - Test obligations: retain parity coverage and add one schema rejection case for an invalid assessment verification token, with a valid result as its positive boundary. This catches malformed presentation data escaping through the item's open extension shape. Do not add field-by-field getter tests.

2. Fill status from the existing outcome store and expose it in CLI and work detail. Serves AC-002 and IS-001/IS-003. Depends on task 1 and subtasks 5/6.
   - Add a narrowly scoped assessment projection filler in `runtime-engine/.../goalrunner/status/`, using `GoalRunnerWorkflowOutcomeStore` for the parent workflow identity. Wire it into both assembler paths and the assembly-to-runtime-input handoff without growing the assembler with rendering or decoding logic.
   - Derive pending/running/completed/skipped and round from the durable record. During running round 2, retain round 1 findings and their verification/fix states until newer observations replace them. An absent record stays absent; a `skipped_no_changes` record stays present with state skipped. Missing data must not become a completed empty assessment. Use the existing durable-read degradation tracker and diagnostics for read failures; propagate cancellation.
   - Extend `runtime-cli/.../goal/status/GoalCliStatusTextLines.kt` and its call site in `GoalCliStatusFormatting.kt` with the specified `goal_assessment: round=<n> exit=<reason> confirmed=<n> refuted=<m> open=<refs>` line. Use a stable pending representation when the running record has no exit reason. Add finding detail lines carrying location, scenario, and evidence so IS-001 remains observable through goal status as well as the other surfaces. Counts and open refs come from the same projected findings, without a second acceptance decision.
   - Extend `runtime-cli/.../kernel/work/WorkDetailPayloadMapping.kt`, `goalStatusWireMap`, to emit the governed assessment object. Keep IDE snapshot and database schema versions unchanged because their shapes do not change.
   - Test obligations: one status-service regression case refreshes a durable round-2 running record containing prior confirmed and refuted findings and asserts round/state/evidence retention. Extend the CLI/work-detail boundary coverage to assert the summary, finding evidence, and governed serialized result. Bug caught: one assembly or serializer path drops the record even though the desktop model exists.

3. Render the assessment in the production PR request. Serves AC-001 and IS-001/IS-002. Depends on the record prerequisites.
   - Add the assessment argument to `toPullRequestRequest` and populate it in `GoalRunnerFinalization.openPullRequest` from the same parent workflow's outcome store. Put substantial formatting in a cohesive sibling renderer under goalrunner execution support.
   - Insert the `Goal assessment` block immediately after the existing `- Goal:` line. Render exit reason and round count, then all categories named in AC-001. Resolve every `open_finding_refs` entry from the retained finding history, preserving last-round fixed-but-unverified entries, unresolved regressions, refuting evidence, test guarded behavior, and failed role/subtask warnings. Deduplicate repeated refs without losing category or verification information. Do not silently omit an unresolved reference; use the record's contract failure/diagnostic policy.
   - Render one explanatory line for no record or `skipped_no_changes`. Preserve subtask 3's interaction lines and existing QA output. Do not call a PR service from a renderer.
   - Test obligations: add one PR request boundary case with the persistent Major from the Test Obligations, a refuted finding, a fixed-but-unverified open ref, a PR-only test-value finding, an unrestored regression, and a failed assessor diagnostic. Assert identity, evidence, guarded ref, warning, and neighbouring interaction text survive. Add the distinct absent/skipped branch to the same renderer test suite. Bug caught: the loop finishes but publication hides or mislabels unresolved work.

4. Map the typed result and select assessment stages. Serves AC-003 and all interaction scenarios. Depends on tasks 1/2.
   - Add desktop assessment models beside `GoalQaFinding` in `desktop-tray/core/domain/src/commonMain/kotlin/dev/skillbill/tray/core/domain/model/`, keeping verification, open/fix state, and evidence separate. Add an optional result to `Detail` and map it in `RuntimeWorkMapper.kt` without defaulting unknown or unverified findings to confirmed.
   - Extend `IdeStatusProjector.goalFinishPipeline` and its `IdeStatusService` call site with the assessment facts already loaded in `goalStatus`. Include `goal_assessment` only when a record exists. Include `goal_assessment_fix` only when a fix ran, using durable round facts rather than severity or open-ref guesses. Assessment stages remain visible when the pack has no QA capability; the existing QA-only filtering still applies.
   - Extend `DetailPresentation.goalFinishSteps` and its caller to use the assessment state for these stages, including an explicit skipped rendering path. Keep skipped distinct from terminal-complete. Preserve all existing QA step behavior.
   - Test obligations: extend `RuntimeWorkMapperTest` with one mixed confirmed/refuted/unverified record and assert location/evidence and state survive mapping. Extend finish-pipeline boundary coverage for record absent, skipped, and fix-ran branches. Bug caught: old goals acquire assessment pills, or a skipped assessment appears completed merely because the goal is terminal.

5. Show the assessment in goal activity. Serves AC-003 and IS-001/IS-002/IS-003. Depends on task 4.
   - Wire the optional result through `DetailContent.kt` and `PhaseResults.kt`, with a focused assessment composable beside existing result components when needed. Include assessment presence in the activity-panel visibility condition. Show state, current round, exit reason, and open findings first, followed by the remaining findings in stable record order.
   - Render each finding's ref, severity, location, failure scenario, evidence, fix state, and verification label. Preserve `not_required` as an explicit verification-not-required label rather than inventing a confirmed verdict. Render confirmed/refuted/unverified exactly for those states. A skipped record shows its skipped stage and reason with no finding rows. A running round shows its retained findings.
   - Add a mixed-state preview in `PhaseResultsPreview.kt`. Use existing Compose semantics and typography so finding text and labels remain available to accessibility tools, and long paths/evidence wrap without hiding content. This does not reshape goal QA presentation.
   - Test obligations: extend `DetailPhaseResultsTest` to exercise goal activity with an open confirmed finding and a refuted finding, asserting both labels and their evidence. Include the running-round refresh and skipped empty-result branches where they protect the activity visibility and stage rules. Bug caught: mapped verification is discarded by the composable, or an assessment-only goal has no activity panel.

6. Hand off validation against the complete production path. Serves AC-001 through AC-003.
   - Keep the PR renderer, status service, work-detail serializer, mapper, activity panel, and stage selection connected to one authoritative assessment record. Use IS-001's persistent/refuted pair as the shared behavioral fixture where module boundaries permit, without adding dependencies solely to share test data.
   - Leave all test execution and the full runtime-kotlin and desktop checks to validate, as the unchanged Validation Strategy requires. Build proof belongs to build. Later audit reads the criterion paths above and traces the three scenarios; later QA checks the desktop presentation. This planning phase executes no build, tests, checks, publication, or workflow advancement.

### Constraints and handoff

Apply architecture rules A1-A12 and the section 5 review checklist. Keep store access and coordination in engine, pure projection data in domain, CLI formatting in CLI, and desktop presentation in desktop modules. Reuse the dependency-owned assessment transitions and record validation. Add no alternate assessment authority, silent fallback, telemetry work, or goal-stats work. Use injected diagnostics and preserve cancellation. Keep visibility narrow, imports explicit, payload keys single-owned, and enum tokens canonical. Add no Kotlin line/block comments or class/function KDoc. Keep helpers cohesive and below file/package limits without exemptions, dependency bags, or forwarding interfaces. Preserve existing regression tests and all governed parity/validator coverage. Required review and validation repairs may touch production wiring, test setup, formatting, and lint within the feature's scope.

The current phase changes only this assigned sub-spec. Its title, scope, acceptance criteria, dependencies, validation strategy, interaction scenarios, and next path remain unchanged. Future implementation owns the production and test edits listed above. No unresolved external capability or product decision blocks this presentation plan; dependency-owned record field names and helper names are local implementation choices, while their required semantics are fixed above.

## Next Path

```bash
skill-bill goal SKILL-442
```
