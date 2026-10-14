# SKILL-426 subtask 3: Criterion reporting, existing UI consumers and guidance

## Scope

Present the executor's committed criterion context as prose in existing runtime reports, CLI/MCP text, desktop details and IntelliJ details. Preserve stale caveats, unavailable history and phase/subtask progress. Update runtime command guidance and necessary authored skill instructions. Readers remain read-only.

This respecification supersedes the previous versioned status-payload design on 2026-10-14. It adds no criterion-reporting schema, structured status group, parser validator or contract version bump. Use existing report/detail/explanation text channels.

## Acceptance Criteria

1. S3-001. Runtime status and budget blocks explain current authored criterion, used and remaining failures, queue position, latest gap and final-audit requirement from committed accounting. Source acceptance and later test/build obligations remain distinct. Unknown historical counts stay unavailable and phase/subtask progress keeps its meaning. Covers AC-006 and AC-007.
2. S3-002. CLI/MCP and existing common report/detail channels carry the same readable context for the selected workflow. Existing transport versions and field shapes remain unchanged. No new nested criterion payload or semantic prose validator is introduced. Missing context remains absent rather than invented zeros. Covers AC-007 and AC-008.
3. S3-003. Desktop's existing details display that context with execution association, freshness and pagination preserved. Exhaustion and final-audit-required are readable. Detail/status readers cannot reserve, close, reopen or advance criteria. Covers AC-007.
4. S3-004. IntelliJ's existing explanation/detail text reaches tooltip, accessibility description, details and its existing persisted display cache where those channels support it. Cached facts retain stale caveats across restart/poll failure. Preserve response ordering and sanitization without a new criterion field group or schema. Covers AC-007.
5. S3-005. Runtime command guidance describes prose findings/plans, focused repair, one failure per cycle, third-failure blocking, retained budgets on final regressions and source versus later quality work. Preserve commands, worker/checkpoint/commit rules and review/quality gates. Necessary skill edits use content.md or governed sidecars, with generated outputs uncommitted. Covers AC-010.
6. S3-006. A few consumer regressions demonstrate readable exhaustion/final-audit/deferred-work context, correct selected-workflow association and stale/cache retention. Tests assert meaningful visible facts without pinning entire English strings or adding schema/rejection suites. Existing unrelated guards, transport compatibility and diagnostics remain intact. Covers AC-009 and AC-010.

## Non-goals

No new command, endpoint, schema, wire field group, UI redesign, progress reinterpretation or reader mutation authority. No migration or incident resume. Do not infer full criterion completion from aggregate gap counts or fill unknown history with zero.

## Dependency notes

Depends on subtask 2's connected prose executor and committed accounting. Reporting does not duplicate counters, settlement or criterion selection. Keep the existing three-subtask manifest and runtime-owned commit/checkpoint rules.

## Implementation Details

This section is the implementation plan for this subtask. It uses only the supplied preplan digest. `E/` below means `runtime-kotlin/runtime-engine/src/main/kotlin/skillbill/engine/featuretask/`. All paths are repository-relative. Tasks run in the order below; the regression obligations accompany their owning changes, and execution of tests belongs to validate.

1. Project committed accounting into a detached status explanation. Serves S3-001 and the read-only boundary in S3-003.

   Touch `E/runner/FeatureTaskRuntimeStatusService.kt`, its `status(request)` operation, and `E/model/core/FeatureTaskRuntimeStatusModels.kt`. Consume committed observations through the existing read boundary in `E/persist/FeatureTaskRuntimeWorkflowPersistence.kt`, including `readAcceptanceCriterionRepairState(...)`. Keep selection, counters, reservations, closure and final-audit decisions with the executor and domain owners delivered by subtask 2. Readers receive neither `AcquiredFeatureTaskRuntimeWorker` nor a mutation-capable run context. A detached internal explanation may be added to the internal projection; it must not become a new transport field group.

   Build one readable explanation from the selected workflow's committed authored identity and text, retained unsuccessful count, remaining allowance, authored queue position, latest prose gap and final-audit state. Preserve composite criteria and original aliases. Report exhaustion through the existing budget/block reason channels. Distinguish active focus, an empty focused queue awaiting final full source audit, and accepted final source audit. Source acceptance must leave pending test/build and other project-quality work visible without claiming it ran. Keep phase totals, completed phases and subtask progress unchanged.

   Place criterion identity, used/remaining allowance, queue position and final-audit state before long text so they survive IntelliJ's 512-character summary limit. Existing full report/detail channels carry the authored text and latest gap where available. Identify an abbreviated summary as abbreviated when it omits gap text; never imply it contains the full gap. Do not parse rendered prose back into facts or infer acceptance from gap counts.

   Assumption for implement to confirm: subtask 2 exposes the committed authored criterion and relevant prose reference through its narrow read model. Resolve any reference through the existing workflow artifact reader, not a second accounting owner. The digest does not establish a separate machine-readable catalog of deferred quality obligations, so describe known phase obligations and retain their existing sections rather than inventing per-criterion declarations. Missing repair context stays absent. Older aggregate attempts with unavailable per-criterion attribution remain unavailable, not zero. Preserve the dependency's readable operator-decision refusal when prospective accounting has not been authorized; reporting must not authorize, reset or resume it.

   Regression obligation: an exhausted committed criterion must report its authored identity, three retained failures, zero remaining allowance, queue position and latest gap, while an empty queue with final audit pending must not report source acceptance. The realistic bug is a status reader showing a fresh allowance or completed source acceptance after restart despite different committed facts. Exercise these states through the actual service in the shared service fixture used in task 3, rather than testing a formatting helper separately.

2. Carry that explanation through CLI/MCP and run-report text. Serves S3-001 and S3-002.

   Touch `runtime-kotlin/runtime-cli/src/main/kotlin/skillbill/cli/featuretask/FeatureTaskRuntimeStatusPresentation.kt`, including `runtimeStatusText(...)` and `toRuntimeStatusCliMap(...)`, and sibling `FeatureTaskRuntimeRunReportPresentation.kt`, including `runtimeRunText(...)` and `toRuntimeRunCliMap()`. Reuse the committed explanation from task 1. Run reports already have `blocked_reason` and `pause_reason`; preserve their exact underlying reasons and include useful criterion context through those established channels when applicable. Active/final-audit context must use an existing supported explanation or detail text channel, not masquerade as a block.

   Keep CLI/MCP transport versions, keys, nesting, optionality and field shapes unchanged. Do not reinterpret `completed_phases`, add criterion identifiers to an agent contract, or introduce an English-language validator. Missing context adds no fabricated count or empty criterion group. Preserve diagnostic text and projection-failure reporting rather than turning a projection failure into a new repair allowance.

   Assumption for implement to confirm: MCP receives these existing shared presentation/report channels. The digest names no independent MCP formatter or active run-report explanation field. Follow the existing presentation path during implementation and use its already-supported text fields; when a run report lacks such a field, retain context in its existing status/detail channel instead of adding a wire field. Extend the existing transport presentation fixture only if the service/consumer regressions in task 3 do not exercise the changed channel. The realistic bug for such an extension is CLI or MCP dropping exhaustion context that the service already reports. Assert visible facts and existing field compatibility, not complete sentences or a new schema rejection matrix.

3. Attach context to the common selected-workflow status and details. Serves S3-002, S3-003 and S3-006.

   Touch `runtime-kotlin/runtime-engine/src/main/kotlin/skillbill/engine/work/IdeStatusProjector.kt` and `IdeStatusService.kt`, particularly `project(candidate, context)`, `childOptionalContext(...)` and `detailRuntimeStatus(workflowId)`. Carry the task 1 explanation for exactly the workflow/current child already selected by these owners. Preserve completed-child suppression during parent finalization and monitor. Keep optional-context failures attributable under the existing degradation policy; absence must not be replaced with another workflow's facts.

   Use existing summary/explanation text through `runtime-kotlin/runtime-cli/src/main/kotlin/skillbill/cli/work/WorkDetailPayloadMapping.kt` and `workDetailItemWireMap(item, snapshot)`. Retain workflow/execution association, phase/subtask progress, quality information and surrounding freshness. Do not independently rank workflows, add a nested criterion payload or give detail readers settlement authority.

   Extend `runtime-kotlin/runtime-engine/src/test/kotlin/skillbill/engine/work/IdeStatusServiceGoalProjectionTest.kt` with a selected-child scenario whose other child has different committed accounting. Assert the selected child's identity, allowance and gap in shared status/details, then show final-audit-required with later quality work still pending. Include task 1's exhausted observation through the same real service boundary. The realistic bugs are another child's allowance leaking into the selected workflow and an empty focused queue hiding the final audit or pending quality work. Retain existing parent-finalization, monitor, full pause-reason and optional-context diagnostic coverage. Reuse its isolated fixtures; do not touch production workflow history.

4. Display the context in desktop's existing detail flow. Serves S3-003 and S3-006.

   Touch `desktop-tray/src/main/kotlin/dev/skillbill/tray/infrastructure/data/RuntimeWorkMapper.kt`, including `IdeStatusSnapshot.toDomain(...)` and `WorkDetailItem.toDomain(...)`, and `desktop-tray/src/main/kotlin/dev/skillbill/tray/ui/DetailPane.kt`. Keep criterion text visible for active final-audit-required and source-accepted states as well as blocked/paused states. Preserve exact block-reason selection, matching history and full block-reason copying. Keep phase, subtask and quality sections alongside the explanation.

   Preserve workflow/execution identity, revision/sequence, freshness deadlines, stale caveats, cursors and page offsets. Use existing summary/detail fields and UI structure. Add no mutation controls or criterion-specific transport/domain field group. Showing context must not reserve, close, reopen or advance a criterion.

   Extend `desktop-tray/src/test/kotlin/dev/skillbill/tray/ui/MainWindowTest.kt` with one final-audit-required detail scenario that remains visible with a stale caveat and later quality work pending. The realistic bug is the active detail pane suppressing criterion context because its summary was previously shown only for blocked/paused states. Assert meaningful visible facts and retained phase/quality sections. Run existing `desktop-tray/src/test/kotlin/dev/skillbill/tray/infrastructure/data/RoomBoardSourceTest.kt` coverage for association, freshness and pagination. Add a boundary assertion there only if a changed mapping exposes an uncovered execution-association or pagination bug; do not duplicate UI prose assertions.

5. Preserve IntelliJ explanations through stale display and cache restoration. Serves S3-004 and S3-006.

   Touch `intellij-plugin/src/main/kotlin/dev/skillbill/intellij/infrastructure/cli/IdeStatusJsonMapper.kt`, `presentation/StatusUiMapper.kt`, `presentation/SkillBillStatusBarPresentation.kt` and `ui/StatusDetailsPopupContent.kt`. Retain path redaction and the 512-character sanitized summary bound. Keep the status-bar label short. Carry the useful summary into existing detail, tooltip and accessibility text. Compose committed context with poll-failure/stale explanations instead of allowing popup preference for `problemSummary` to hide `detail`. Preserve response ordering and prevent older observations from replacing newer execution-associated context.

   Reuse `intellij-plugin/src/main/kotlin/dev/skillbill/intellij/domain/LastKnownDisplayCache.kt` and `infrastructure/prefs/IntelliJPreferenceCache.kt` without new criterion cache fields or a parser. Sanitized summaries restored after restart remain stale cached observations, with their existing execution association and caveats. Long gaps may be abbreviated, but the essential identity, allowance, queue position and final-audit facts must remain readable within the existing bound. Do not enlarge the cache limit or weaken sanitization to make room.

   Extend `intellij-plugin/src/test/kotlin/dev/skillbill/intellij/infrastructure/prefs/PreferenceSanitizerAndCacheTest.kt` with one restart scenario using bounded exhaustion context and a long gap. The realistic bug is cached allowance disappearing or stale cached facts appearing current after restoration. Assert retained essential facts, the stale caveat, sanitization and bounds without matching the whole summary.

   Extend `intellij-plugin/src/test/kotlin/dev/skillbill/intellij/presentation/SkillBillStatusBarPresentationTest.kt` with a final-audit-required/deferred-quality observation followed by poll failure. Exercise existing mapper/presentation paths where the fixture supports them and assert context plus caveat in tooltip/accessibility text. The realistic bug is a poll failure replacing useful context with only its error explanation. Confirm popup composition through its existing consumer fixture if available; the digest supplies no popup-test path, so implement must locate that fixture rather than invent a separate UI test framework. Run existing `presentation/StatusUiMapperTest.kt` and response-ordering coverage; add no sibling test that repeats the same branch with different wording.

6. Correct runtime guidance and necessary authored instructions. Serves S3-005 and S3-006.

   Update `docs/runtime-command-guidance.md`, especially acceptance-audit repair planning. Update `skills/skill-bill/content.md` or existing governed sidecars only where their instructions contradict the revised loop. Explain ordinary prose findings/plans, one authored criterion at a time, shared prerequisite edits without silently accepting another criterion, one unsuccessful outcome per reserved repair cycle, third-failure blocking, success retaining prior failures, and final full source audit reopening regressions with the same retained budget. Distinguish source gaps/source acceptance from later tests, build and project-quality work.

   Remove directions requiring broad remaining-list formats, permanent closure, non-shrinking aggregate retries, strict admission/adoption receipts or completion markers for this feature. Preserve commands, selected strategy/worker ownership, cancellation, checkpoints, per-subtask commits and existing review/finding-verification/build/quality gates. Explain unavailable older attribution and the separate explicit operator decision for prospective accounting. An ambiguous final-audit regression uses the dependency's existing read-only prose follow-up before reopening; it creates neither a required phrase nor an agent-authored identifier protocol.

   Before actual authored skill changes, implement reads `docs/skill-source-generation.md`. Use `content.md` or authorized sidecars, keep generated wrappers/pointers/provider outputs uncommitted, and leave installation refresh to its authorized parent or later owner. No installer or install-sync task belongs to this child plan. Guidance edits need no exact-wording tests; source audit checks the behavior described against S3-005.

7. Preserve architecture and hand off validation evidence to its owning phase. Serves all S3 criteria, particularly S3-006.

   Before changing runtime code, implement applies `docs/architecture-guidelines.md`, the design principles in `runtime-kotlin/ARCHITECTURE.md`, `docs/code-principles.md` and `docs/observability-policy.md`. Use A1/A2 for domain accounting and existing engine transaction ownership; A3/A4 for narrow detached readers without broad context bags or unrestricted persistence ports; A5 for deleting obsolete reporting-only machinery rather than renaming it; A6 for owning wire-key constants and enum wire values; A7 for typed failures, cancellation propagation and attributable optional-context degradation; A8 for injected clock/environment behavior; A9 through A12 for package cohesion, test placement, visibility and typed boundaries. Preserve G1 through G7 guards, baselines and unrelated transport validators. Kotlin comments remain limited to permitted interface KDoc.

   Dependencies own execution, durable accounting and superseded schema removal. Implement confirms their completed read boundary when consuming it, but this plan neither performs dependency work nor treats missing test execution as a planning obstacle. Necessary production-wiring, test-setup, formatting and lint repairs remain authorized in their owning implementation/review/validation phases. No scope restriction here prevents those repairs.

   Validate discovers and runs the required repository checks and affected service, CLI/MCP, desktop and IntelliJ consumer regressions under normal ownership. Preserve existing transport compatibility, architecture, diagnostics, cache/order and unrelated UI tests. Do not replace full required validation with compilation or add a criterion schema/rejection suite. Audit checks every S3 criterion against repository end states; review applies the architecture section 5 checklist with rule IDs. History, commit, PR, monitoring and installation remain with their owners. This planning phase executes no tests, build or checks and supplies no executed-test evidence.

## Validation strategy

No checks execute during respecification. Later owning phases run required project checks and meaningful affected consumer regressions. Prose-only guidance changes need no exact-wording tests. Existing transport and architecture checks remain unchanged; there is no new criterion-reporting schema suite.

## Next path

After subtask 2 completes the revised executor, the runtime refreshes this subtask's briefing and runs normal implementation/review/quality phases. Parent history, commit, PR and monitoring remain runtime-owned. This document change launches nothing.
