# Subtask 3 execution checklist, test obligations and goal coverage

## Boundary ownership

The executable spec is spec_subtask_3_serial-repair-reporting.md. This companion does not expand consumer projections. Use the accepted serial observation; never duplicate the executor.

| Work | Digest-backed owners |
| --- | --- |
| Runtime/CLI facts | FeatureTaskRuntimeStatusModels, StatusService, RunModels, runtime-cli featuretask StatusPresentation and RunReportPresentation |
| Common payload | runtime-ports idestatus IdeStatusModels/WorkDetailModels; engine work IdeStatusProjector/IdeStatusService; infra contracts IdeStatusSchemaValidator; CLI WorkDetailPayloadMapping; contracts IdeStatusContractVersions/keys; canonical ide-status-schema.yaml/work-detail-schema.yaml |
| Desktop | desktop-tray infrastructure/data/RuntimeWorkMapper.kt; domain/WorkModels.kt; ui/DetailPane.kt |
| IntelliJ transport/domain/cache | infrastructure/cli/IdeStatusJsonMapper.kt; domain/SkillBillStatusOutcome.kt, StatusExecutionMetadata.kt and LastKnownDisplayCache.kt; infrastructure/prefs/IntelliJPreferenceCache.kt and PreferenceSanitizer |
| IntelliJ presentation | presentation/SkillBillStatusUiState.kt, StatusUiMapper.kt, SkillBillStatusBarPresentation.kt; ui/StatusDetailsPopupContent.kt; existing application/StatusRefreshCoordinator.kt ordering |
| Guidance | docs/runtime-command-guidance.md; only necessary authored skills/skill-bill/content.md or authorized sidecars |

## Ordered checklist

1. Consume subtask 2's accepted optional read-only runtime facts. Define queue position/size and absent-current semantics once, retain phase/subtask meaning and explain mixed source-closed/deferred overlap.
2. Propagate common typed facts through status/report, parent-goal child context and work detail. Update actual wire maps as well as models. Readers remain observation-only.
3. Coordinate IDE 0.5 and work-detail 1.2 schemas, owning versions/keys, actual validators/packaged resources and exact legacy 0.4/1.1 absent-field admission. No unversioned reinterpretation or new serial defaults.
4. Map typed optional facts into existing desktop detail panels, preserving freshness/cursor and validation separation.
5. Propagate IntelliJ optional data through parser/outcomes/UI/details/accessibility/tooltip and persisted preference cache. Preserve response ordering, stale identity and sanitizer bounds; absence stays absent.
6. Update runtime command guidance and necessary authored skill guidance only. Preserve existing runtime/phase authority and command surface. Any required installation waits for its authorized later owner; generated outputs remain uncommitted.
7. Author consumer boundary tests below, avoiding trivial field-copy tests and exact whole-string prose assertions.

## test_obligations

| Named realistic bug | Observable boundary assertion | Criteria |
| --- | --- | --- |
| Status reports authored total minus gaps or phase retries as AC completion | Accepted active/exhausted/final/deferred states produce correct CLI/status facts, retained budget and mixed count overlap with phase progress unchanged | AC-006, AC-007; S3-001 |
| A model-only field never reaches validated common payload/MCP consumers | Real IDE/work-detail serialization, schema validation and consumer parsing carry required facts; version parity/golden fixtures agree and malformed nested data refuses | AC-007, AC-008; S3-002 |
| Legacy absent data invents zeros or writes adoption from a status read | Exact legacy 0.4/1.1 payloads remain absent; repeated status/detail reads leave accepted durable revision/reservation/queue unchanged | AC-007; S3-001/S3-002 |
| Desktop detail drops deferred or exhausted source facts | Representative validated work-detail reaches actual desktop domain/detail presentation with current AC/budget/counts/mode/gap and existing validation/freshness identity | AC-007; S3-003 |
| IntelliJ tooltip/accessibility omit the active gap or exhausted budget | Representative parsed active/exhausted/final/deferred payload reaches tooltip/accessibility/details with required facts, without exact full-prose equality | AC-007; S3-004 |
| IDE restart or stale fallback erases criterion facts or reorders observations | Real preference-cache write/read then stale restoration preserves optional facts/identity; legacy cache stays absent and sanitized invalid/bounded data follows existing policy; refresh ordering regressions remain intact | AC-007; S3-004 |

Reuse StatusServiceTest/StatusHistoryTest, CLI StatusPresentationTest, IdeStatusServiceGoalProjectionTest, IdeStatusModelsTest, actual IDE/work payload schema version/validator/golden tests, desktop ProjectionTest/MainWindowTest, and IntelliJ StatusUiMapperTest/StatusBarPresentationTest/StatusRefreshCoordinatorTest/PreferenceSanitizerAndCacheTest. Parameterize materially different states at the real boundary instead of separate tests per literal or mapper. Every new test names its realistic bug before authoring. Governed parity/rejection rules and real past regressions are mandatory.

## Full upstream acceptance coverage

The supplied AC-001 through AC-010 remain unchanged in spec.md. All required work is assigned below. Subtask criteria describe readable repository end states; future test/check/review/commit receipts are not acceptance criteria.

| Upstream criterion | Executable owners | Boundary proof/source fact |
| --- | --- | --- |
| AC-001 | S1-001/S1-002, S2-001/S2-007 | Complete catalog/dependency admission operations, plus structured discovery and queue use in the running workflow |
| AC-002 | S2-001/S2-002/S2-007 | Mode-selected bounded projection, current-only closure, composite completeness and prerequisite non-closure |
| AC-003 | S1-003, S2-002/S2-003 | Reserve-before-effects, once-only failures, retained success counts, third block and no semantic multiplier |
| AC-004 | S1-004/S1-005, S2-002/S2-003/S2-006 | Callable fenced transaction/recovery operations, plus production callers and commit-gated in-memory acceptance/publication |
| AC-005 | S1-003, S2-004 | Empty-queue final requirement, exact tree/catalog gates across review/resume/completion and retained-budget final reopening |
| AC-006 | S1-001/S1-003, S2-005, S3-001 | Separate source/validation axes, actual admitted counts and validation-owned evidence only |
| AC-007 | S2-006, S3-001/S3-002/S3-003/S3-004 | Actionable runtime block plus common CLI/MCP/desktop/IntelliJ/cache facts, absent/stale semantics and reader non-mutation |
| AC-008 | S1-006/S1-007, S2-001/S2-007, S3-002 | New artifact schema/key/version/validator/parity, exact legacy provenance/refusal and common payload versions |
| AC-009 | S1-008, S2-008, S3-006 | Behavior-focused production SQLite, runner, handoff and consumer regressions in their owning commits |
| AC-010 | S1-008, S2-007/S2-008, S3-005/S3-006 | Existing prompt owners/guidance/authored skill boundaries, guarded architecture/budgets/cancellation/diagnostics |

## Rollout and settled assumptions

New executions use structured discovery and semantic revision 4 only after subtask 2 activates the validated subtask 1 contracts. Supported stopped-pristine executions match an explicitly enumerated archived tuple and record preserved provenance, then perform fresh structured discovery. Previous repair/in-flight/ambiguous history refuses without deletion, counter invention or SKILL-422 resume. Status readers may observe genuine legacy absence but cannot adopt or mutate it.

The parent scope and manifest both describe three subtasks. The parent's ten acceptance criteria and safety/budget policies remain unchanged. All three implementation-plan companions describe their owning boundaries. Tests accompany behavior, never a test-only final task. Subtask 1 supplies working admission/transaction operations with boundary regressions and no executor activation; subtask 2 connects those operations and activates complete enforcement; subtask 3 supplies presentation/guidance. Subtask 1 alone does not establish the parent's executor requirements or authorize a serial executor release.

Keep repair-state/audit-result/repair-handoff/adoption contracts 0.1; phase output 0.7 with explicit supported 0.6 migration; handoff envelope 0.1; private/delivered persistence 0.2. Extend phase handoff to 0.3 with explicit 0.2 admission, IDE status to 0.5 with exact absent legacy 0.4 admission and work-detail to 1.2 with exact absent legacy 1.1 admission. Any proven necessary containing-shape change must update its own version/schema/consumers and exact admission/refusal together. Installed phase settlement remains the supplied runtime contract regardless of checkout changes.

The digest lacks literal archived policy hashes, installed rollout packaging facts and the remote default branch. Implement binds exact archived values using the named preserved fixture owners, confirms packaging and resolves actual default/active-fix worktree policy before mutating. main is the manifest planning assumption from the observed baseline. Missing tracker comments add no invented constraints and require no account. No open product decision remains for another planning workflow.

## Validation ownership

No build/test/check execution here; tests_executed is empty. Build owns only its pack command. Validate discovers and runs all required full pack/Gradle/architecture/repo/schema/SQLite/runner/CLI/desktop/IntelliJ checks and repairs failures, including packaging and required authorized ./install.sh if authored skill/render/pointer source changes. Required guards and rejection/parity evidence cannot be weakened. Review cites architecture rule IDs and owns findings; runtime owns history, one commit/checkpoint per subtask, push, PR and monitor. No new workflow/operation or workflow continue is launched by this bundle.
