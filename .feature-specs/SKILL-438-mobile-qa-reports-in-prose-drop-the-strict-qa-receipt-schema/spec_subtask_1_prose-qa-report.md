# SKILL-438 subtask 1 - Prose QA report across runtime, verify, persistence, and desktop tray

Parent spec: `.feature-specs/SKILL-438-mobile-qa-reports-in-prose-drop-the-strict-qa-receipt-schema/spec.md`

## Scope

The JSON QA receipt becomes a prose markdown report everywhere it is written, settled, stored, printed, and shown. That covers parent requirements 1, 2, 3, 4, 6, and 7, plus the parts of requirement 8 about receipts, schema validation, and downgrade rules. Device selection and `ambiguous_target` belong to subtask 2.

Areas touched. All runtime paths are under `runtime-kotlin/` unless noted.

- Settlement and model: `runtime-engine/.../featuretask/qa/receipt/` (`QaReceipt.kt`, `QaReceiptValidator.kt`, `QaReceiptDecoding.kt`, `QaReceiptNormalization.kt`, `QaReceiptSettlement.kt`, `QaPhaseOutputKeys.kt`, `FeatureTaskQaOutcomeRecords.kt`, `FeatureTaskQaOutcomeRecorder.kt`).
- Strategy and loop: `runtime-engine/.../featuretask/slot/qa/` (`QaStrategy.kt`, `QaPromptSections.kt`, `QaRepairHooks.kt`, `QaVerdictRule.kt`, `QaLoopRules.kt`), `featuretask/qa/execution/QaExecutionLifecycle.kt` (only the moved `QaRetainedResource` import), and `runtime-domain/.../model/qa/QaRepairState.kt`.
- Standalone and verify: `featuretask/phaserun/qa/QaStandalonePhaseRun.kt`, `QaRunReport.kt`, `operation/verify/VerifyQaStep.kt`, `VerifyStepSequence.kt`, `VerifyPromptSections`, `FeatureVerifyWorkflowDefinition.kt`, `VerifyArtifacts`, and `resources/.../verify/verify-directive.md`.
- Goal loop and status: `engine/goalrunner/qa/DefaultGoalRunnerQaGate.kt`, `engine/goalrunner/execution/support/GoalRunnerBranchPlan.kt`, `GoalRunnerStatusProjectionAssembler.kt`, `GoalRunnerStatusProjectionModels.kt`, `runtime-cli/.../goal/status/GoalCliStatusTextLines.kt`, and `GoalCliStatusFormatting.kt`.
- Ports and persistence: `ports/featuretask/model/FeatureTaskQaOutcome.kt`, `ports/idestatus/model/StandaloneQaReportModels.kt` and its consumers (`StandalonePhaseStatusModels.kt`, `WorkDetailModels.kt`, `PhaseRunModels.kt`, `StandalonePhaseStatusEventSink.kt`, `WorkExecutionHistory.kt`), `SqliteFeatureTaskQaOutcomeStore`, `StandalonePhaseStatusStore`, `core/migration/area/idestatus/StandaloneQaReportMigration.kt`, `DatabaseMigrationEntries.kt`, `runtime-contracts/.../contracts/persistence/DesktopReadSchema.kt`, and `featuretask/persist/FeatureTaskRuntimeWorkflowPersistence.kt` (`readQaRepairState`).
- Workspace, schema, and errors: `QaWorkspacePort`, `ports/qa/model/QaWorkspace.kt`, `FileSystemQaWorkspace`, `runtime-ports/.../qa/QaReceiptSchemaPort.kt`, `runtime-infra/contracts/.../qa/QaReceiptSchemaValidator.kt`, `QaReceiptSchemaPaths` in `FeatureTaskRuntimeSchemaPaths.kt`, the `qa-receipt-schema.yaml` copy in `runtime-infra/contracts/build.gradle.kts`, `RuntimeQaProvides.qaReceiptSchemaPort()`, `runtime-contracts/.../qa/QaReceiptContract.kt`, `runtime-contracts/.../error/featuretask/qa/QaRuntimeErrors.kt`, `FeatureTaskRuntimeFailureCode.kt`, and `orchestration/contracts/qa-receipt-schema.yaml`.
- Guidance and docs: the report-shape, verdict, fallback, and receipt wording in `platform-packs/kmp/qa/android-qa.md` and `platform-packs/ios/qa/ios-qa.md`, the QA paragraphs of `docs/runtime-command-guidance.md`, `docs/observability-policy.md` where it describes receipts, `runtime-kotlin/ARCHITECTURE.md` where it describes the receipt schema port, and any skill-bill skill content under `skills/` or `orchestration/` that describes receipts, schema validation, or downgrade rules.
- Desktop tray, a separate Gradle build under `desktop-tray/`: `core/domain/.../model/WorkModels.kt`, `core/data/.../runtime/RuntimeWorkMapper.kt`, and in `feature/detail` `QaCriteria.kt`, `QaFindings.kt`, `DetailContent.kt`, and `PhaseResultsPreview.kt`.

## Acceptance Criteria

1. QA settlement reads `report.md` from the QA invocation directory and settles from its text with no schema check, size cap, or format rejection. The verdict comes from the first report line that names `passed`, `findings` (or `findings_open`), `incomplete`, or `skipped`, case-insensitively. Finding ids and titles come from `### <finding-id> <title>` headings, and each finding's section text is kept verbatim. A report with no recognizable verdict settles `incomplete`. Every settled report's full text is stored with the QA outcome. Only a missing `report.md` or a stopped agent settles without report text.
2. Goal finalization runs `qa -> qa_fix -> build -> qa` from the extracted findings, with up to three repair attempts across relaunches. The `qa_fix` prompt carries each open finding's section verbatim plus the evidence paths named in it. Findings are matched by id across QA runs and recorded `fixed` or `not_fixed`. Findings still open after the third attempt appear in the PR body's Mobile QA section as not fixed. `skill-bill goal status` prints `qa_findings: fixed=<n> not_fixed=<m>` with the open ids and no `qa_criteria:` line. QA never blocks the goal, `qa:off` skips without probing a device, and workflows whose stored phase output still carries a legacy `qa_receipt` load and settle.
3. Standalone `skill-bill phase qa` and `operation:verify qa:on` print the verdict, the report text verbatim, warnings, and the runtime cleanup note instead of the flows/assertions/criteria rendering. The verify verdict directive embeds the prose report. It still turns APPROVE into APPROVE WITH FIXES when QA findings are open, and it still treats skipped or incomplete QA as untested coverage.
4. Agent-written QA output is described only in prose, and no JSON schema, enum, or length cap applies to it. `orchestration/contracts/qa-receipt-schema.yaml` is deleted, along with its schema port, adapter, locator paths, Gradle copy, DI provider, receipt contract version, claim-normalization code, strict decoding, the structured flow/assertion/fallback/target model and its enums, the criteria-matrix model and rendering, and the receipt-schema error constructors. The QA run briefing, `android-qa.md`, `ios-qa.md`, `docs/runtime-command-guidance.md`, and the skill content describe the `report.md` shape from parent requirement 1 instead of a receipt.
5. The desktop tray's QA panels show the stored prose report text and the extracted findings, each with its fixed or not fixed state. The desktop work models, `RuntimeWorkMapper`, and `feature/detail` contain no criteria-matrix types or criteria panel.

## Decisions

These settle the preplan's open questions. Implement follows them unless the tree contradicts a stated assumption. If it does, record the deviation in the subtask's census or history.

- **Report location and reading.** The QA workspace reads `report.md` instead of `receipt.json`. Rename the `readReceipt`, `receiptPath`, and `RECEIPT_FILE` names to report names. Remove the 256 KiB `MAX_RECEIPT_BYTES` cap and its `invalidQaReceipt` throw. Read the file once per settle. The retained-path containment and no-symlink checks stay.
- **Verdict extraction.** Scan lines from the top. The first line containing a whole word `passed`, `findings`, `findings_open`, `incomplete`, or `skipped`, case-insensitively, decides the verdict. If that line names several of them, the word that occurs first wins. Markdown markers and a `Verdict:` prefix are ignored. With no match the verdict is `incomplete`. Extraction never throws.
- **Finding extraction.** A level-3 heading (`### `) is a finding when its first token contains at least one digit, after trimming a trailing `:` or `.`. That token is the id and the rest of the heading is the title. If the heading has no title text, the first non-blank body line is the title. The section runs from the heading to the next heading of level 3 or higher. Headings like `### Cleanup` or `### Device` are not findings. Evidence paths are the absolute paths in the section text under the invocation's evidence prefix. Other paths are ignored, never rejected. No length cap applies to titles or sections, so the 160-character title cap is removed.
- **Verdict and findings reconciliation.** The runtime records the verdict the report states and never downgrades it. Open findings for the goal loop are the extracted ids when the verdict is `findings`. A `passed` report that still has finding headings keeps `passed`, adds a warning naming the ids, and opens no repair. A `findings` report with no finding headings records `findings_open` with no ids to repair, and the goal loop advances. Verify's APPROVE WITH FIXES override keys on the verdict alone.
- **Closure rule (one rule for repair state and outcome records).** A previously open id becomes `fixed` when a later settled report with verdict `passed` or `findings` doesn't list it. `incomplete` and `skipped` runs carry every previous status forward. That matches the existing `mergedFindings` behavior. Delete `QaFindingVerification`, `verifiedClosure`, `verification`, and `retainedQaReceipt`. Keep `MAX_ATTEMPTS = 3`, `consumedAttempts`, `resumedAfter`, the exhaustion warning, and the unchanged-tree `OweFindings` check in `QaRepairHooks`.
- **`qa_fix` input.** Each open finding's section is re-read from the `report.md` named by its repair-state reference. Keep the repair-state contract (0.2) and its persisted field names, so there's no repair-state schema bump. A legacy reference that ends in `receipt.json` gives the finding id and title only, plus a warning.
- **Runtime-authored outcomes.** Skipped, stopped, disabled, not-started, and unavailable outcomes become a runtime-authored report. Its text is the runtime's reason sentence, so standalone, verify, and goal share one shape. Identity binding stays runtime-side: `QaExpectedIdentity` goes into the prompt as context only and the agent doesn't echo HEAD or tree. The `QaExecutionLifecycle` warnings (`warnIfChanged`, `statusChange`, `outputWarnings`), lease release, and evidence under the invocation directory stay unchanged. `QaVerdict` and `QaRetainedResource` move out of the receipt package and stay. `QaSkipReason` keeps its runtime-authored values. Agent text is never parsed into `QaSkipReason` or any other enum.
- **Phase output keys.** New QA envelopes carry the verdict, the report text, the report reference, and the finding ids under report-named keys. The existing tree, evidence-prefix, warnings, criterion-id, open-finding, and exhaustion keys stay. `readQaRepairState`, `QaVerdictRule`, `QaLoopRules`, and `FeatureTaskQaOutcomeRecords.settled` treat a legacy `qa_receipt` output as QA output present and take its `verdict` when the new keys are absent.
- **Verify artifact.** New verify runs write a `qa_report` artifact with verdict, outcome, reason, report text, warnings, and cleanup note. `VerifyQaReport` falls back to a legacy `qa_receipt` artifact's `verdict`, `outcome`, and `reason` strings, and tolerates absence. `VERDICT_FIELD`, `VerifyQaOutcome`, and the `QA_APPROVE_WARNING` override stay. The warning text says "QA report" instead of "QA receipt".
- **Persistence.** Add the next free named `DatabaseMigration`. Assumption: that is v66, `record-qa-report-text`, because v65 `record-standalone-plan-report` is the latest per preplan. Implement confirms the number on the current tree. The migration adds a nullable `qa_report_text` TEXT column to the goal QA outcome table and to the standalone phase status table. Set `DesktopReadSchema.VERSION` to that migration number. Don't reuse `ensureColumn` in an older heal step, because it never reaches existing databases. The old `criteria_summary` and `qa_report` matrix columns stay physically in place and are no longer read or written. Legacy rows load with no report text. `findings_summary` keeps its JSON keys, including `receipt_path`, so old rows decode unchanged. `affected_criteria` is no longer written and is ignored on read. Kotlin names may be renamed while persisted keys stay.
- **Errors.** Delete `invalidQaReceipt`, `qaReceiptIdentityMismatch`, and `qaReceiptUnsupportedClaim` together with their `FeatureTaskRuntimeFailureCode` entries and `QA_FAILURE_CODES` membership. Implement greps for the code names first. A code that's read outside QA, for example in telemetry, history, or desktop mapping, keeps its entry. `qaReceiptMissing` is replaced by the missing-report settlement result and deleted if nothing else uses it. Expected outcomes are results, not exceptions.
- **Printing.** Standalone `value` becomes the verdict line plus reason. `targetReport` is the `Warning:` lines, then the report text verbatim, then the runtime cleanup note covering lease release and retained resources. `qaOutcome` stays. `PhaseRunResult.Completed.qaReport` is replaced by the report text. Verify prints the same blocks.
- **Desktop.** `GoalQaResult.criteria`, `QaRunReport`, `QaCriterionResult`, `QaCheckResult`, and `QaCriterionFinding` are replaced by a nullable report text. `GoalQaFinding(id, title, evidencePath, fixed)` stays. `QaCriteriaSection` becomes a report-text panel showing the stored text verbatim and selectable. `QaFindingsSection` stays. Previews are updated. Assumption: `desktop-tray/` compiles against the runtime-ports models (it maps `StandaloneQaReport` today), so these desktop edits ship in this commit to keep both builds compiling.
- **Skill content location.** The preplan didn't find the skill text that describes receipts. Implement greps `skills/`, `orchestration/`, `platform-packs/`, and `docs/` once for `QA receipt`, `receipt.json`, `qa-receipt`, and `downgrade` and updates every hit. `agent/history.md` and `agent/decisions.md` are historical records and stay as written.

## Non-Goals

- Device selection wording, the `ambiguous_target` guidance lines, and `QaSkipReason.AMBIGUOUS_TARGET`. Subtask 2 owns them. Leave them as present if this subtask runs first.
- Review, plan, and monitor agent contracts.
- The device lease mechanism (`QaDeviceLeasePort`, `FileSystemQaDeviceLeases`, `qa-device-lease-schema.yaml`) and the repair-state schema contract.
- Rewriting or deleting receipts already on disk, including the ENG-1587 `receipt.json`.
- Dropping legacy SQLite columns.

## Dependency Notes

- None. This subtask applies to the tree as present. If subtask 2 has already landed, keep its device-selection guidance as written.
- Architecture rules that still apply: domain model packages can't import `skillbill.text` or `java.nio`. Public `toPayload`-style raw maps live in contracts, not application, domain, or ports. Few custom exceptions. Keep the report parser in the engine QA package unless the domain import rules allow it.

## Test Obligations

Each obligation names the bug it catches. Rewrite existing tests in place where they exist.

- **Report extraction (criterion 1, parent AC 1).** A unit test with a report over 300,000 characters, unknown words, and extra sections, including a `### Cleanup` heading. It asserts the stated verdict, the ids, and each section verbatim. Catches format-based discarding and non-finding headings counted as findings.
- **No verdict (criterion 1).** A unit test: a report with no verdict word settles `incomplete` with its full text kept. Catches text dropped on a lenient fallback.
- **Reconciliation (criterion 1).** A unit test: a `passed` report with a finding heading keeps `passed`, emits the warning, and opens no repair. Catches silent verdict rewriting.
- **Missing report (criterion 1).** A settlement test: no `report.md` settles without text. Adapt the existing settlement test.
- **Goal loop (criterion 2, parent AC 2).** Rewrite `GoalQaRepairLoopTest` "a goal QA that passes after qa_fix..." and "findings QA cannot fix in three attempts..." to write prose `report.md` files through the test workspace double. The first asserts the `qa -> qa_fix -> build -> qa` order, the ids recorded `fixed`, and the `qa_fix` prompt containing the finding section verbatim and its evidence path. The second asserts the finding is `not_fixed` after three attempts and that the PR body has `not fixed, review them yourself: <id> "<title>"`. Keep the relaunch case.
- **Closure (criterion 2).** Rewrite `QaFindingSettlementTest` for the id-based closure rule: closed when absent from a `passed` or `findings` report, carried forward through `incomplete`. Catches regressions to evidence-based closure.
- **Legacy load (criterion 2).** One test that a stored envelope carrying legacy `qa_receipt` loads through `readQaRepairState` and settles its verdict. Catches in-flight goals stranded by the key change.
- **Printing (criterion 3, parent AC 3).** Update `QaStandalonePhaseRunTest` and `VerifyQaOperationTest` to assert the printed report text verbatim, and keep the verify APPROVE WITH FIXES override case.
- **Persistence (criterion 1).** A migration test for the new column next to `SqliteFeatureTaskQaOutcomeStoreTest`. A `StandalonePhaseStatusStoreTest` round trip of report text, and a legacy row with `criteria_summary` loading with no report text. Update `RoomRuntimeCompatibilityTest` if it pins the read-schema version.
- **Status (criterion 2).** Update `GoalCliStatusQaFindingsTest`: `qa_findings:` stays and `qa_criteria:` is gone.
- **Desktop (criterion 5).** Update `DetailQaFindingsTest`. Replace `DetailQaCriteriaTest` with a report-panel test that asserts the text renders.
- **Schema deletion (criterion 4).** Trim `QaSchemaContractVersionTest` to the repair-state and lease contracts. Delete it if nothing remains.
- **Compile-follow updates, no new assertions.** `QaRunTestDoubles.kt`, `QaTestReceipts.kt` (becomes prose report helpers), `QaExecutionLifecycleFixture.kt`, the QA parts of `FeatureTaskRuntimeRunnerTestSupport.kt`, `TestPhaseStrategies.kt`, `PhaseStrategyCompositionTest.kt`, `PhaseGoalQaRunTest`, `DefaultGoalRunnerQaGateTest` (keep the `qa:off` no-probe case), and `PhaseResultsPreview.kt`.

## Implementation Details

Tasks run in order. Each one names the criteria it serves, the paths or symbols it touches, and its tests. Paths are under `runtime-kotlin/` unless noted. Implement runs no build, test, or check command. The build phase proves compilation and the validate phase owns `./gradlew check` and the `desktop-tray/` build. Nothing here runs an install, uninstall, or install-sync step.

### Task 0: Census on the current tree (criteria 1, 2, 4)

Before editing, run single grep pipelines and record the results in the subtask census:

- `DatabaseMigrationEntries.kt`: the highest migration number and name. The new migration takes the next number. The assumption is v66 `record-qa-report-text`. If v66 is already taken, use the next free number and record the deviation.
- `FeatureTaskRuntimeFailureCode.kt` and the whole tree (runtime, desktop, docs): every use of `INVALID_QA_RECEIPT_SCHEMA`, `QA_RECEIPT_IDENTITY_MISMATCH`, `QA_RECEIPT_UNSUPPORTED_CLAIM`, and `QA_RECEIPT_MISSING`. A code read outside the QA package, in telemetry, history, or a desktop mapping, keeps its enum entry. Record which entries stay.
- `skills/`, `orchestration/`, `platform-packs/`, and `docs/`: hits for `QA receipt`, `receipt.json`, `qa-receipt`, `qa_receipt`, and `downgrade`. This list is the guidance worklist for Task 13.
- The runtime and desktop source and test trees: every reference to `QaReceipt`, `QaReceiptValidator`, `QaReceiptSettlement`, `decodeQaReceipt`, `normalizeQaReceiptClaims`, `restrictedToCriteria`, `StandaloneQaReport`, `StandaloneQaCriterion`, `QaRunReport`, `qaReport`, `QaReceiptSchemaPort`, `QaReceiptPayloadKeys`, and `QA_RECEIPT_CONTRACT_VERSION`. This is the compile-follow worklist for Tasks 1–15.
- `StandaloneQaReportMigration.kt`: whether it is pure DDL or references the matrix model types. If it references them, Task 9 inlines the few constants it needs, so the historical migration step stays byte-for-byte equivalent in effect.

### Task 1: Report model and lenient parser (criterion 1)

- Rename the engine package `featuretask/qa/receipt/` to `featuretask/qa/report/`. Move `QaVerdict` with its wire values `passed`, `findings_open`, `skipped`, and `incomplete`, and `QaRetainedResource`, into it unchanged. Assumption: neither type is referenced from `runtime-ports` or `runtime-domain`. If one is, it moves to a non-receipt package in its current module instead.
- Add `QaReport(text: String?, verdict: QaVerdict, reason: String?, findings: List<QaReportFinding>, reference: String?, warnings: List<String>)` and `QaReportFinding(id: String, title: String, section: String, evidencePaths: List<String>)`. These are plain value classes with no wire map.
- Add `QaReportParser.parse(text: String, evidencePrefix: String): QaReport`, which never throws. It implements the Decisions rules:
  - **Verdict:** use the first line that has a whole-word, case-insensitive `passed`, `findings`, `findings_open`, `incomplete`, or `skipped`. When a line has several, the earliest occurrence wins. Leading markdown markers and a `Verdict:` prefix are ignored. `findings` and `findings_open` map to `FINDINGS_OPEN`. With no match the verdict is `INCOMPLETE`.
  - **Findings:** a `### ` heading counts when its first token contains a digit, after trimming a trailing `:` or `.`. The rest of the heading is the title. When that is blank, the first non-blank body line is the title. The section runs verbatim from the heading line to before the next `#`, `##`, or `###` heading.
  - **Evidence paths:** absolute path tokens in the section that start with `evidencePrefix`, in order of appearance, without duplicates. Other paths are ignored.
  - **Reconciliation:** a `PASSED` verdict with finding headings keeps `PASSED`, adds the warning `QA report states passed but lists findings <ids>; no repair opened`, and keeps the findings in `findings` for display. The open-id derivation in Task 5 ignores them.
- No length cap applies anywhere. Delete the 160-character title cap.
- Constraint: the parser stays in the engine QA package. Domain model packages may not import `skillbill.text` or `java.nio`.
- Tests: the extraction, no-verdict, and reconciliation obligations in `QaReportParserTest`, which is new. Use one test per rule, three in total.

### Task 2: Workspace port reads `report.md` (criterion 1)

- `QaWorkspacePort`: rename `readReceipt(receiptPath)` to `readReport(reportPath)` and `readRetainedReceipt(qaRoot, reference)` to `readRetainedReport(qaRoot, reference)`.
- `ports/qa/model/QaWorkspace.kt`: rename `QaWorkspace.receiptPath` to `reportPath`, and `QaReceiptSource(content, evidencePrefix, receiptReference)` to `QaReportSource(content, evidencePrefix, reportReference)`.
- `FileSystemQaWorkspace`:
  - Rename `RECEIPT_FILE` to `REPORT_FILE = "report.md"`.
  - Delete `MAX_RECEIPT_BYTES` and its `invalidQaReceipt` throw.
  - Read the file once as UTF-8 text and return null when the file is absent.
  - Keep the retained-path containment and no-symlink checks exactly as they are.
- Rename the `InMemoryQaWorkspaces` test double in `QaRunTestDoubles.kt` to match, storing `report: String?` keyed by `reportPath`.

### Task 3: Settlement and runtime-authored reports (criteria 1, 2)

- Replace `QaReceiptSettlement` with `QaReportSettlement`:
  - `settle(qa): QaReport` reads `readReport`. Present text is parsed with the invocation's evidence prefix and `reference` is set to the report path. Absent text yields a runtime-authored `INCOMPLETE` report with `text = null` and the reason `QA agent wrote no report.md`.
  - Settling never calls the validator and never discards text.
  - `recordDiagnostics` keeps logging the verdict, warnings, and retained resources, and drops fallbacks and skip-reason decoding.
- Add `QaReports`, a runtime-authored factory in the same package:
  - `skipped(reason: QaSkipReason, detail: String)`, `incomplete(reason: String)`, and `stopped(...)`, each producing a `QaReport` whose `text` is the runtime reason sentence. This replaces `QaReceiptValidator.skipped`, `incomplete`, and `withRetainedResources`.
  - Retained resources travel as a cleanup note string built by the runtime. `QaSkipReason` values are written by the runtime only.
- Delete `QaReceiptValidator.kt`, `QaReceiptDecoding.kt`, `QaReceiptNormalization.kt`, and the structured model in `QaReceipt.kt`: `QaTarget`, `QaFlow`, `QaAssertion`, `QaFinding`, `QaFallback`, `QaCleanup`, `QaReceipt`, and the enums `QaTargetKind`, `QaTargetSelection`, `QaFlowStatus`, `QaAssertionOutcome`, `QaIncompleteReason`, `QaFallbackKind`, and `QaCleanupOutcome`.
  - Assumption: the cleanup outcome is now only the runtime note. If implement finds a runtime path that branches on `QaCleanupOutcome`, it replaces that branch with the runtime's own retained-resource list, which it already holds from `QaExecutionLifecycle`.
- `QaExecutionLifecycle`: update only the `QaRetainedResource` import. `QaExpectedIdentity` goes into the prompt as context, and the agent no longer echoes it.
- Tests: adapt the missing-report settlement test (no `report.md` settles `INCOMPLETE` with null text).

### Task 4: Phase output keys and legacy read helper (criterion 2)

- `QaPhaseOutputKeys`:
  - Add `qa_verdict`, `qa_report_text`, `qa_report_ref`, and `qa_finding_ids`.
  - Keep `qa_head_revision`, `qa_tree_identity`, `qa_evidence_prefix`, `qa_criterion_ids`, `qa_open_findings`, `qa_warnings`, and `qa_repairs_exhausted`.
  - Keep `qa_receipt` and `qa_receipt_ref` as `LEGACY_*` constants, read-only and never written.
- Add one helper, `qaVerdictOf(produced: Map<String, Any?>): QaVerdict?`. It reads `qa_verdict` first, then the legacy `qa_receipt` map's `verdict` string, and returns null when neither exists. Add `hasQaOutput(produced)`, which is true when either key is present.
- Rewrite `qaOutcomeReason` to take the report's reason text instead of the receipt map's `skip_reason`, `incomplete_reason`, and `reason_detail`. For legacy envelopes it falls back to those three strings read leniently.

### Task 5: Repair state and closure rule (criterion 2)

- `runtime-domain/.../model/qa/QaRepairState.kt`:
  - Delete `QaFindingVerification` and `verifiedClosure(evidence)`.
  - Change `observe` to take the settled verdict, the open ids (`findings.map { it.id }` when the verdict is `FINDINGS_OPEN`, otherwise empty), and the tree identity.
  - A previously open id closes when the verdict is `PASSED` or `FINDINGS_OPEN` and the id is absent. `INCOMPLETE` and `SKIPPED` carry every open finding forward unchanged.
  - New ids are appended with the report path as their reference.
- Keep these unchanged: `QaOpenFinding(id, affectedCriteria, receiptRefs)`, its persisted field names (`affectedCriteria` is written empty), `QaRepairAttempt`, `recordRepair`, `MAX_ATTEMPTS = 3`, `consumedAttempts`, `resumedAfter`, `lastReceiptTreeIdentity`, and the repair-state contract 0.2. Kotlin names may change, but persisted keys stay.
- Delete `QaReceiptSettlement.observe`, `verification`, and `retainedQaReceipt`.
- Tests: rewrite `QaFindingSettlementTest` in place for the closure obligation, with one test for closing on a conclusive report and one for carrying forward through `incomplete`.

### Task 6: Strategy, loop rules, and prompts (criteria 2, 4)

- `QaStrategy`:
  - Inject `QaReportSettlement` in place of the settlement and validator.
  - `hooks.interpretedOutput` calls `settle(qa)` and then `withReport(...)`, which replaces `withReceipt`. `withReport` drops `restrictedToCriteria`, appends execution warnings and the reconciliation warning, runs the Task 5 `observe`, keeps exhaustion from `MAX_QA_FIX_ATTEMPTS`, and writes the Task 4 keys.
  - `runtimeCompletion` uses `QaReports`.
  - `recordGoalOutcome` passes the `QaReport` to the recorder.
  - `findingSupplement` lists `reports=<ref>` instead of `receipts=`.
- `QaRepairHooks`:
  - Replace `receiptFindings(state)` with `openFindingSections(state)`. For each open id it reads the section through `readRetainedReport` on the last reference, then re-parses it with `QaReportParser`.
  - A reference ending in `receipt.json`, or one that can't be read, yields the id and stored title only, plus the warning `QA finding <id> has no readable report section; repairing from its id`.
  - Keep `checkValidatedOutput`'s `OweFindings` rule: unchanged tree, or a section marked `not changed:`. Keep `interpretedOutput` recording `QaRepairAttempt`.
- `QaVerdictRule`:
  - Stop re-validating.
  - Repairs exhausted means ADVANCE. Otherwise, retained open findings, or a `FINDINGS_OPEN` verdict with at least one open id, means QA_FINDINGS. Everything else ADVANCES, including `PASSED` with headings and `FINDINGS_OPEN` with no ids.
  - It reads the verdict through `qaVerdictOf`.
- `QaLoopRules`: read `skipped` through `qaVerdictOf`.
- `QaPromptSections`:
  - Rewrite `supplement(qa)` to describe the parent requirement 1 `report.md` shape. It covers the first-line verdict word; `### <finding-id> <title>` sections with steps, expected, observed, and evidence paths under the evidence prefix; free-form extra sections; and the device the agent used.
  - The runtime identity is shown as context.
  - Remove the schema path, `policy` echo, `device_contended`, `production_only`, `hardware_only`, and `cleanup.outcome` wording. The runtime authors those skips. `VerifyQaStep` keeps reusing this text.
  - `repairSupplement` emits each open finding's section verbatim plus its evidence paths.
  - `DIRECTIVE`, `VALUE_CONTENT`, and the fix constants change only where they say "receipt".
  - Constraint: leave any device-selection or `ambiguous_target` lines as present. Subtask 2 owns them.

### Task 7: Outcome records and recorder (criterion 2)

- `FeatureTaskQaOutcomeRecords.settled`:
  - Read the verdict via `qaVerdictOf`, the findings from `qa_finding_ids` plus the report, or the legacy receipt map's `findings` ids and `observed` first lines leniently.
  - An unknown verdict maps to `INCOMPLETE` instead of throwing `invalidQaReceipt`.
- `mergedFindings` keeps its id-based merge: `PASSED` and `FINDINGS_OPEN` close absent ids, and other verdicts carry statuses forward. Titles come from `QaReportFinding.title` with no cap.
- Delete `criteriaReport` and `findingTitle`'s cap.
- `FeatureTaskQaOutcomeRecorder.QaSettledRun(treeIdentity, verdict, report: QaReport, reportPath)` replaces the wire map, and the recorder stores `report.text`.

### Task 8: Port models (criteria 1, 3, 5)

- `FeatureTaskQaOutcome.report: StandaloneQaReport?` becomes `reportText: String?`. `FeatureTaskQaFinding` keeps `id`, `title`, `evidencePaths`, `receiptPath` (the Kotlin name may become `reportPath`), and `status`. `affectedCriteria` is dropped from the model.
- Delete `ports/idestatus/model/StandaloneQaReportModels.kt`.
- Replace each `StandaloneQaReport?` field with `qaReportText: String?` in `StandalonePhaseStatusModels.kt` (both sites), `WorkDetailModels.kt`, `PhaseRunModels.kt` (`PhaseRunResult.Completed.qaReport`), `StandalonePhaseStatusEventSink.kt`, and `WorkExecutionHistory.kt`.

### Task 9: SQLite migration and stores (criterion 1)

- Add the named `DatabaseMigration` from the Task 0 census (assumed v66 `record-qa-report-text`) to `DatabaseMigrationEntries.kt`. It adds a nullable `qa_report_text TEXT` column, with no `json_valid` check, to the goal QA outcome table and to the standalone phase status table.
- Set `DesktopReadSchema.VERSION` to the same number.
- `SqliteFeatureTaskQaOutcomeStore`:
  - Write and read `qa_report_text`. Stop writing and reading `criteria_summary`.
  - `findings_summary` keeps its JSON keys (`id`, `observed`, `evidence_paths`, `receipt_path`, `status`). `affected_criteria` is no longer written and is ignored on read.
- `StandalonePhaseStatusStore`: write and read `qa_report_text`, and stop reading and writing `qa_report`.
- `StandaloneQaReportMigration` stays registered as history. Per Task 0, inline any constant it took from deleted types.
- Trim `QaReceiptPayloadKeys` in `QaReceiptContract.kt` to the column and JSON key names the stores still use. It may be renamed, for example to `QaOutcomePayloadKeys`, while the string values stay.
- Constraints:
  - Do not use `ensureColumn` in an older heal step, and do not drop legacy columns.
  - Raw payload maps stay in contracts and do not move into application, domain, or ports.
- Tests:
  - A migration test next to `SqliteFeatureTaskQaOutcomeStoreTest` for the new column on an upgraded database.
  - A `StandalonePhaseStatusStoreTest` round trip of report text, plus a legacy row carrying `criteria_summary` that loads with null text.
  - Update `RoomRuntimeCompatibilityTest` if it pins the version.
  - Pass an explicit non-empty environment map in tests that build stores.

### Task 10: Legacy workflow load (criterion 2)

- In `FeatureTaskRuntimeWorkflowPersistence.readQaRepairState` (lines ~163–183), the "QA output present without repair state" guard uses `hasQaOutput`. A legacy envelope with `qa_receipt` and a stored repair-state artifact loads as before.
- Assumption: legacy in-flight QA envelopes always have a repair-state artifact, as today's guard requires. Implement keeps the existing `invalidQaRepairStateSchema` throw for the genuinely corrupt case.
- Test: one legacy-load test. An envelope with `qa_receipt` (`verdict: findings_open`, one finding) loads through `readQaRepairState` and settles that verdict through `FeatureTaskQaOutcomeRecords.settled`.

### Task 11: Standalone printing (criterion 3)

- `QaStandalonePhaseRun`:
  - `value` is `<verdict>` plus `: <reason>` when a reason exists.
  - `targetReport` is `Warning: <w>` lines, then `report.text` verbatim when present, then the runtime cleanup note (lease released, retained resources listed).
  - `qaOutcome` stays, and the completed result carries `qaReportText = report.text`.
  - Drop the `withRetainedResources` call.
- Delete `QaRunReport.kt`'s `targetReport()` and `qaReport()` matrix rendering. Keep `qaOutcome()` as a function on `QaVerdict` or `QaReport`.
- Test: update `QaStandalonePhaseRunTest` to assert the report text appears verbatim in the printed output.

### Task 12: Verify (criterion 3)

- `VerifyArtifacts`: add `QA_REPORT = "qa_report"` and keep `QA_RECEIPT` as a legacy read-only name.
- `VerifyQaStep`:
  - `artifact(report)` writes `verdict`, `outcome`, `reason`, `report_text`, `warnings`, and `cleanup` under `qa_report`.
  - `VerifyQaReport.of(...)` reads `qa_report`, then falls back to the legacy `qa_receipt` strings `verdict`, `outcome`, and `reason`, and tolerates absence.
  - The `disabled`, `notStarted`, `unavailable`, and `stopped` paths use `QaReports`.
  - `launch` keeps `VerifyPromptSections.qaDirective(...) + QaPromptSections.supplement(qa)`.
- `VerifyStepSequence` (~250–256): `verdict(...)` passes the prose report to `VerifyPromptSections.verdictDirective`, which embeds the verbatim text and the verdict line instead of a JSON block. Keep `VERDICT_FIELD`, `VerifyQaOutcome`, and the `QA_APPROVE_WARNING` override keyed on a `findings_open` verdict. At ~420, the wording "QA receipt" becomes "QA report".
- Change "receipt" to "report" in `VerifyPromptSections.qaDirective`, in `FeatureVerifyWorkflowDefinition.kt` (~225–244: step text and artifact name), and in `verify-directive.md` (Step 8 at ~126–139, Mobile QA Verdict Rules at ~250–261). Keep the rule that skipped or incomplete QA means untested coverage.
- Verify prints the same blocks as Task 11.
- Test: update `VerifyQaOperationTest` (with `VerifyOperationHarness`) to assert the verbatim report text and keep the APPROVE WITH FIXES case.

### Task 13: Goal loop and status (criterion 2)

- `DefaultGoalRunnerQaGate`: compile-follow only. QA still never blocks, and `qa:off` still skips without a device probe.
- `GoalRunnerBranchPlan.findingsPullRequestText()`: the format is unchanged (`fixed: <ids>` and `not fixed, review them yourself: <id> "<title>"`), and titles come from the stored uncapped title.
- `GoalRunnerStatusProjectionAssembler` (~633–658) and `GoalRunnerStatusProjectionModels`: drop the criteria projection.
- In `runtime-cli/.../goal/status/GoalCliStatusTextLines.kt`, keep line 35 (`qa_findings: fixed=<n> not_fixed=<m>` plus the open ids) and delete the line-41 `qa_criteria:` output. Update `GoalCliStatusFormatting.kt` to match.
- Tests:
  - Update `GoalCliStatusQaFindingsTest`: the `qa_criteria:` line is absent.
  - Rewrite `GoalQaRepairLoopTest`'s two named tests in place as the goal-loop obligation describes. The `Fixture` launcher (~243–257) writes prose `report.md` text through `InMemoryQaWorkspaces` in place of `qaTestReceipt` and `qaTestJson`.
  - Keep the relaunch case, adjusted to prose.

### Task 14: Schema, infra, and error deletions (criterion 4)

- Delete:
  - `orchestration/contracts/qa-receipt-schema.yaml`
  - `runtime-ports/.../qa/QaReceiptSchemaPort.kt`
  - `runtime-infra/contracts/.../qa/QaReceiptSchemaValidator.kt`
  - `QaReceiptSchemaPaths` in `FeatureTaskRuntimeSchemaPaths.kt` (~205–212)
  - the `qa-receipt-schema.yaml` copy in `runtime-infra/contracts/build.gradle.kts` (~116–118)
  - `RuntimeQaProvides.qaReceiptSchemaPort()`
  - `QA_RECEIPT_CONTRACT_VERSION`
- Before deleting the DI provider, count the generated kotlin-inject child-component readers of that provider.
- In `QaRuntimeErrors.kt`, delete `invalidQaReceipt`, `qaReceiptIdentityMismatch`, `qaReceiptUnsupportedClaim`, and `qaReceiptMissing` once they have no remaining callers. Remove their `FeatureTaskRuntimeFailureCode` entries and `QA_FAILURE_CODES` membership, except the codes Task 0 found read outside QA.
- `QaSchemaContractVersionTest`: keep only the repair-state and lease assertions, and delete the file if none remain.
- Test-only follow-ups:
  - `QaTestReceipts.kt` becomes prose helpers (`qaTestReport(verdict, findings)` returning markdown). `QA_TEST_HEAD` and `QA_TEST_TREE` stay where still used.
  - Compile-follow: `QaExecutionLifecycleFixture.kt`, the QA parts of `FeatureTaskRuntimeRunnerTestSupport.kt`, `TestPhaseStrategies.kt`, `PhaseStrategyCompositionTest.kt`, `PhaseGoalQaRunTest`, and `DefaultGoalRunnerQaGateTest` (keep the `qa:off` no-probe case).
  - Mocks use `relaxUnitFun = true`, never `relaxed = true`.

### Task 15: Guidance and docs (criterion 4)

- `platform-packs/kmp/qa/android-qa.md` and `platform-packs/ios/qa/ios-qa.md`:
  - Replace every receipt field (`device_fingerprint`, `selection`, `fallbacks`, `cleanup.outcome`, `target.kind`, `artifact.path_ref`) and the Verdict section (`incomplete_reason`, `skip_reason`) with the prose `report.md` shape. Name the device and any fallback in prose.
  - Leave the Selection and AVD lines that mention `ambiguous_target` as present. Subtask 2 owns them.
- `docs/runtime-command-guidance.md` (~128, ~155, ~173): replace the receipt-path, schema-validation, `Criteria:` block, downgrade, and invalid-receipt sentences with the `report.md` description, lenient verdict extraction, verbatim printing, and the id-based closure rule.
- Update receipt descriptions in `docs/observability-policy.md` and the receipt schema port line in `runtime-kotlin/ARCHITECTURE.md`.
- Update every hit on the Task 0 skill-content worklist. `runtime-kotlin/agent/history.md` and `agent/decisions.md` stay as written.

### Task 16: Desktop tray (criterion 5)

These edits are under `desktop-tray/`, a separate Gradle build, and ship in this commit.

- `core/domain/.../model/WorkModels.kt`:
  - `GoalQaResult` drops `criteria` and gains `reportText: String?`.
  - Delete `QaRunReport`, `QaCriterionResult`, `QaCheckResult`, and `QaCriterionFinding`.
  - The standalone run's `qaReport` becomes `qaReportText: String?`.
  - `GoalQaFinding(id, title, evidencePath, fixed)` stays.
- `core/data/.../runtime/RuntimeWorkMapper.kt`: delete `StandaloneQaReport.toDomain()` (37–43), map `reportText` and findings with `fixed` at ~170–178, and map `qaReportText` at ~189.
- `feature/detail`:
  - Replace `QaCriteria.kt`'s `QaCriteriaSection` with a report-text panel (`QaReportSection(text)`) showing the text verbatim in a selectable container.
  - `QaFindings.kt` stays.
  - `DetailContent.kt` lines 342 and 353 render `QaFindingsSection(detail.goalQa)` and `QaReportSection` for the goal report text and the standalone `qaReportText`.
  - `PhaseResultsPreview.kt` fixtures use prose text.
- Constraint: tray code maps persisted data only and never reaches installed-CLI or PATH execution.
- Tests: update `DetailQaFindingsTest`. Replace `DetailQaCriteriaTest` with `DetailQaReportTest`, which asserts the report text renders.

### Task 17: Residue census (criteria 4, 5)

Grep `runtime-kotlin/`, `desktop-tray/`, `orchestration/`, `platform-packs/`, `docs/`, and `skills/` for `qa-receipt-schema`, `QaReceiptSchema`, `QaReceiptValidator`, `normalizeQaReceiptClaims`, `StandaloneQaCriterion`, `QaCriterionResult`, `qa_criteria:`, `receipt.json`, and `MAX_RECEIPT_BYTES`.

The only allowed hits are:
- legacy read constants (`LEGACY_*`, `QA_RECEIPT` artifact fallback, the `receipt.json` legacy-reference check)
- persisted key strings
- historical migrations
- `agent/history.md` and `agent/decisions.md`

Record the result in the census.

### Constraints carried from the Decisions and the digest

- Do not touch: review, plan, and monitor contracts; the device lease code and `qa-device-lease-schema.yaml`; the repair-state schema; receipts already on disk; or the device-selection and `ambiguous_target` lines.
- Expected outcomes are results, not exceptions. The parser and settlement never throw on report content.
- After merge, rollout is a runtime install from main followed by a desktop rebuild for the `DesktopReadSchema` bump. That is outside this subtask and is never run from a linked worktree.

## Validation Strategy

The validate phase runs `./gradlew check`, which includes agent-config validation, plus the `desktop-tray/` build and tests. Then it reads the tree against criteria 1–5. Implement and audit run no builds or tests. No install, uninstall, or install-sync step belongs to this subtask.

## Next Path

```bash
skill-bill goal SKILL-438
```
