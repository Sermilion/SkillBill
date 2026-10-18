# SKILL-419 subtask 3: Verify `qa:on|off`

Parent spec: `.feature-specs/SKILL-419-mobile-qa-phase/spec.md`

## Scope

Add QA as an opt-in verify evaluator after `completeness_audit` and before `verdict`, reusing the subtask 1 capability resolver, device lease, strategy guidance, and receipt validator.

- `OperationArguments` gains `qa: String?`. `OperationCommand` parses a `qa:` token, rejects it outside verify the way `spec:`, `target:`, and `mode:` are rejected, and raises actionable usage errors for duplicate, conflicting, or unknown values. Verify `pre()` validates `on | off`; omission means `off`. MCP verify tools accept the same option at parity with the CLI.
- `FeatureVerifyWorkflowDefinition` and `VerifyWorkflow` gain a `qa` step between `completeness_audit` and `verdict`, a `qa_receipt` artifact, a `QA_SELECTION` input_context key, a step label, resume action, continuation directive, and a heading in `verify-directive.md`.
- Selection persistence: `openAndExtract` stores `QA_SELECTION` in input_context, the proposal text names the selected option next to the criteria, and `confirm` refuses a qa value that differs from the stored one (unlike `mode`, which confirm may override). A parked verify workflow created before this change has no stored selection and resumes as `off`.
- `qa_receipt` is always written: with `off` it is a `skipped` receipt with `disabled_policy`, settled as a skipped step with no device probing, no capability resolution, and no agent launch. The verdict's required artifacts include `qa_receipt` only for workflows whose stored selection exists, so pre-QA workflows keep their recorded requirements.
- Target binding (AC-007): with `on`, the QA step creates a temporary detached worktree at the resolved `target.headRevision` under `.git/skill-bill/verify-qa/<workflow>/`, builds and installs from it, and requires the receipt's tree identity to equal that head. Disposable runtime state lives there too. Cleanup is bounded on success, skip, failure, cancellation, and resume; a cleanup failure emits a record with the retained worktree path and guidance. The existing operator worktree baseline check stays in force and fails the run if the operator's source or HEAD changes.
- Verdict (AC-008): the verdict directive and its parse treat `findings_open` QA findings as consolidated findings that block an unqualified approval, and list skipped or incomplete QA as untested coverage with its reason. Optional unavailability never becomes a product defect.
- Put QA logic in new files (for example `VerifyQaStep.kt`) so `VerifyOperation.kt` and `VerifyStepSequence.kt` stay under the production line ceilings.
- Verify state, CLI reports, and `VerifyTelemetry` plus `telemetry-event-schema.yaml` carry a QA outcome that distinguishes not selected, attempted, passed, findings, skipped, and incomplete, without credentials or device log bodies.
- `skills/skill-bill/content.md` adds `[qa:on|off]` to the verify routing row, the forwarded-token paragraph, and the operation form text, and `docs/runtime-command-guidance.md` adds it to the verify syntax.

## Acceptance Criteria

1. A verify workflow with no `qa:` token stores `off`, keeps the existing proposal text apart from naming the selection, runs the existing evaluator sequence, writes a `disabled_policy` skip receipt without probing devices or launching an agent, and reaches its verdict with unchanged rules.
2. `qa:on` inserts the `qa` step after `completeness_audit` and before `verdict`, the selection is shown in the proposal, persisted in input_context, retained through confirmation and resume, and a confirm carrying a different value is refused.
3. `qa:` outside verify, duplicates, conflicts, and unknown values raise actionable usage errors in the CLI, and the MCP verify tools accept the same option.
4. An opted-in QA step runs against a temporary detached worktree at the resolved target head, rejects a receipt whose tree identity differs from that head, cleans up the worktree on every exit path with a recorded failure when cleanup fails, and leaves the operator's source, HEAD, and review target unchanged.
5. The verdict directive and parser include QA findings in consolidated findings so an unqualified approval cannot ignore them, and present skipped or incomplete QA as untested coverage with its reason.
6. Verify state, CLI reports, and verify telemetry with its schema distinguish not selected, attempted, passed, findings, skipped, and incomplete QA without credentials or device log bodies.
7. `skills/skill-bill/content.md` and `docs/runtime-command-guidance.md` document `operation:verify ... [qa:on|off]`.

## Non-goals

- Repairing application code during verify.
- Changing the existing verify evaluators, criteria extraction, or `mode` semantics.
- Running QA against the operator's working tree.

## Dependency Notes

Depends on subtask 1 for the receipt contract, shared validator, capability resolver, device lease, and pack guidance. It does not depend on subtask 2, but runs after it in the shared branch.

Assumption for implement to confirm: the verify verdict's required-artifact check can be made conditional on the stored selection. If the definition only supports static requirements, implement writes the `qa_receipt` for every new workflow and treats a missing selection on a pre-QA workflow as "not composed".

## Test Obligations

- Verify with `qa:off` writes a skip receipt and makes no capability or device-lease call (AC-006).
- `qa:on` survives park, confirm, and resume; confirm with `qa:off` against a stored `on` is refused (AC-006).
- QA step builds from the target head while the operator tree is dirty, and the operator tree and HEAD are unchanged afterwards; a receipt bound to another tree is rejected (AC-007).
- A findings receipt prevents an unqualified approval verdict; a skipped receipt appears as untested coverage, not a finding (AC-008).
- `qa:` passed to another operation is a usage error.

## Validation Strategy

Validate runs the repository's required maintainer checks, including the production file line ceiling and wire vocabulary architecture tests. Implement and audit run nothing.

## Next Path

```bash
skill-bill goal SKILL-419
```

## Implementation Details

All paths are under `runtime-kotlin/` unless they start with `orchestration/`, `skills/`, or `docs/`. Engine paths abbreviate `runtime-engine/src/main/kotlin/skillbill/engine/`. Names this plan takes from subtask 1 (`QaCapabilityResolver`, the shared QA receipt validator, the device-lease port, `QaReceiptPayloadKeys`, the `qa-receipt-schema.yaml` verdict and skip-reason enums, and the QA failure codes) are assumptions. Implement confirms the names subtask 1 landed and uses them; it never adds a second resolver, validator, or lease.

### Decisions settled here

- D-V1. The full-run vocabulary is `auto|off`. Verify's is `on|off`, and omission is `off`. The two value sets stay separate, and verify rejects `auto` as an unknown value.
- D-V2. `confirm` with a `qa` value that differs from the stored one fails with an actionable error naming both values. A confirm that repeats the stored value, or omits `qa`, proceeds. `mode` keeps its override behavior.
- D-V3. A parked workflow with no stored `QA_SELECTION` is pre-QA. It resumes with selection `off`, keeps its recorded verdict requirements, and gets no `qa` step history entry. The step sequence reports its QA outcome as `not_selected`.
- D-V4. The verdict requirement is conditional. `requiredArtifactsByStep[verdict]` keeps today's static list, and the run adds `qa_receipt` to the required set only when `QA_SELECTION` is present in input_context. Assumption for implement to confirm: if the workflow definition only supports static requirements, implement uses the spec's fallback from Dependency Notes and records the deviation in its output.
- D-V5. The temporary worktree reuses the standalone-monitor owned-worktree helper when its create/remove API is reachable from the verify operation. Otherwise implement adds the smallest create-detached and remove methods to the existing git port and adapter, with no new composition root. Implement confirms which.
- D-V6. The receipt's tree identity must equal the tree of `target.headRevision`. Bind to the tree rather than the commit sha so a message-only amend of the same tree matches, which is the same rule subtask 2 uses for invalidation.
- D-V7. The QA outcome vocabulary for verify is `not_selected`, `attempted`, `passed`, `findings`, `skipped`, and `incomplete`. `attempted` appears only while the step is running or when the run stops before the receipt validates. The other values come from the validated receipt verdict, mapping `findings_open` to `findings`.

### Ordered tasks

1. Operation argument and CLI token parsing. Serves AC-3.
   - `runtime-engine/.../operation/core/Operation.kt`: add `qa: String?` to `OperationArguments` and update every constructor call site that the compiler flags.
   - `runtime-cli/src/main/kotlin/skillbill/cli/operation/OperationCommand.kt`: parse `qa:<value>` into `OperationArguments.qa`. Reject it for every operation other than verify through the same path that rejects `spec:`, `target:`, and `mode:`. A repeated `qa:` with the same value and a repeated `qa:` with different values both raise a usage error that names the token. Unknown values raise a usage error listing `on|off`.
   - MCP verify tools (`feature_verify_workflow_open`, `_continue`, `_resume`, `_update`, in `runtime-mcp`): accept an optional `qa` string and pass it through to the same `OperationArguments.qa`, so validation lives in one place (verify `pre()`). Implement locates the MCP argument mapping.
   - Constraint: no `//` comments, and wire keys go into the existing `*Keys` objects (WireVocabularyArchitectureTest).

2. Verify workflow vocabulary. Serves AC-1 and AC-2.
   - `runtime-domain/.../workflow/verify/FeatureVerifyWorkflowDefinition.kt`: add step `qa` between `completeness_audit` and `verdict`, label "Step 8: Mobile QA", and relabel verdict to "Step 9: Consolidated Verdict" and finish to the following step number. Add `requiredArtifactsByStep[qa] = [diff_projection]`, a resume action ("re-create the temporary target worktree and rerun QA, never reuse a receipt from another tree"), a continuation directive, a `continuationReferenceSections` entry pointing at the new heading, the `qa_receipt` artifact in `continuationArtifactOrder` after the completeness receipt, and an `inputProjectionsByStep` entry for verdict that includes `qa_receipt` with the receipt fields `contract_version`, `verdict`, `findings`, plus the skip or incomplete reason.
   - `runtime-engine/.../operation/verify/VerifyArtifacts.kt`: add `VerifyWorkflow.QA`, `QA_RECEIPT`, and the input_context key `QA_SELECTION`. Insert `QA` into `CONFIRMED_STEPS` between `COMPLETENESS_AUDIT` and `VERDICT`.
   - `runtime-engine/src/main/resources/skillbill/engine/operation/verify/verify-directive.md`: add a QA heading. It tells the agent to work only inside the temporary worktree, follow the pack QA guidance resolved for the target, write the receipt to the stated path, and never edit, commit, or push.
   - Constraint: renumbering labels must not change any existing step id or artifact key.

3. Selection persistence and confirm refusal. Serves AC-1 and AC-2.
   - New file `runtime-engine/.../operation/verify/VerifyQaSelection.kt`: an enum `VerifyQaSelection(ON, OFF)` with `wireValue` and `fromWire`, plus helpers that read the stored selection from input_context (absent returns null, meaning pre-QA) and resolve the effective selection (`stored ?: OFF`).
   - `VerifyOperation.pre()`: validate `arguments.qa` with `VerifyQaSelection.fromWire`, failing through `invalidArgument("qa", value, "on|off")`.
   - `VerifyOperation.openAndExtract`: write `QA_SELECTION` with the requested value or `off`.
   - `extractAndPark`: add one line naming the selection, for example "QA: on" or "QA: off", directly after the criteria block. The rest of the proposal text stays as it is.
   - `confirmTarget`: read the stored selection. If `context.arguments.qa` is present and differs, fail per D-V2. Never write a new value over the stored one.
   - `VerifyStepSequence.VerifyRun`: add `qa: VerifyQaSelection?` (null for pre-QA) populated from input_context on confirm and resume.
   - Constraint: `VerifyOperation.kt` (541 lines) and `VerifyStepSequence.kt` (447 lines) may only gain call sites. New logic goes into the new files.

4. QA step execution. Serves AC-1 and AC-4.
   - New file `runtime-engine/.../operation/verify/VerifyQaStep.kt`, called from the `execute` `when` branch for `VerifyWorkflow.QA`.
   - `off`, including the pre-QA effective `off` on a workflow whose stored selection exists: write a `skipped` receipt with skip reason `disabled_policy` through the shared receipt validator, and settle the step as `WorkflowStepStatus.SKIPPED` with the existing `settled(...)` pattern. Make no capability resolution, no device-lease call, no worktree creation, and no agent launch. A pre-QA workflow (stored selection null) skips the step without writing a receipt, per D-V3.
   - `on`:
     1. Resolve the capability with `QaCapabilityResolver` on the target's changed paths. `Absent` writes a `skipped` receipt with `unsupported_target`. A malformed declaration fails loudly with the subtask 1 failure code and does not become a skip.
     2. Create a detached worktree at `target.headRevision` under `.git/skill-bill/verify-qa/<workflowId>/` per D-V5. Disposable runtime state and generated scripts go under that directory, never into tracked source.
     3. Acquire the device lease for this workflow's invocation. Contention writes a `skipped` receipt with `device_contended` and leaves the other owner's lease untouched.
     4. Launch the QA agent step with the working directory set to the temporary worktree, the resolved pack guidance, the confirmed criteria, and the receipt output path.
     5. Validate the receipt with the shared validator. A malformed or missing receipt fails the step with the typed QA receipt failure code. A receipt whose tree identity differs from the tree of `target.headRevision` (D-V6) is rejected with a typed failure, never accepted as passed.
     6. Store the validated receipt as `qa_receipt` and settle the step.
     7. In a `finally`-style path, release the lease and remove the worktree on success, skip, failure, cancellation, and resume re-entry. Resume first removes any stale worktree left at that path for the same workflow. A removal failure emits an observability record holding the retained path and manual cleanup guidance, and does not mask the original outcome. Cancellation is rethrown after cleanup and is never reported as a skip.
   - The existing `worktreeBaseline` before/after check in `VerifyOperation.run` keeps guarding the operator tree and HEAD. The QA worktree under `.git/` is outside it.
   - Constraint: no device serials, credentials, or device log bodies in step output, records, or compact receipts. Evidence stays as bounded private-artifact references, following the subtask 1 receipt contract.

5. Verdict directive and parse. Serves AC-5.
   - `runtime-engine/.../operation/verify/VerifyPromptSections.kt` `verdictDirective()`: when `qa_receipt` is present, include it. `findings_open` QA findings join the consolidated findings with their affected criteria, steps, expected, observed, and evidence references. `skipped` or `incomplete` shows up in an "Untested coverage" list with its reason and is never listed as a product defect.
   - Verdict parse (in `VerifyStepSequence.verdict()`, with the new rule placed in `VerifyQaStep.kt` or a small `VerifyQaVerdictRule.kt`): if the stored receipt is `findings_open` and the parsed verdict is the unqualified approval value, reject the report as invalid and ask for a corrected verdict through the existing malformed-verdict path. Implement confirms the exact value names in `VERDICTS`.
   - Constraint: existing verdict rules for workflows with no QA receipt are unchanged (AC-1).

6. QA outcome in verify state, CLI report, and telemetry. Serves AC-6.
   - Project the D-V7 outcome from the run into the verify workflow state returned by `feature_verify_workflow_get` and `_latest`, and into the CLI report. Implement locates the state projection and report writer.
   - `runtime-engine/.../operation/verify/VerifyTelemetry.kt`: add a `qa_outcome` field to `FeatureVerifyFinishedRequest`, plus an optional skip or incomplete reason drawn from the schema enums.
   - `orchestration/contracts/telemetry-event-schema.yaml`: add `qa_outcome` (enum of the D-V7 values) and the optional reason to the `feature_verify_finished` event. The field is optional so earlier events stay valid. Bump the contract version and its Kotlin `*_CONTRACT_VERSION` constant if the schema pins one, and keep the existing parity test passing.
   - Constraint: telemetry carries the enum and reason only, never device ids, logs, or evidence bodies.

7. Docs. Serves AC-7.
   - `skills/skill-bill/content.md`: add `[qa:on|off]` to the verify row in "Forms and Routing", add `qa:` to the forwarded-operation-token paragraph with its rejection outside verify, and name the QA selection in the verify proposal text under "Operation Forms".
   - `docs/runtime-command-guidance.md`: add `[qa:on|off]` to the verify syntax under "Operations".
   - Edit `content.md` only, never generated `SKILL.md`.

### Tests to add (implement writes them, validate runs them)

Each test maps to a Test Obligation above and the bug it catches.

- `qa:off` and omitted `qa`: the run writes a `disabled_policy` skip receipt, and a fake capability resolver and device-lease port record zero calls. Catches a regression that probes devices or launches an agent when QA is off.
- Park with `qa:on`, then confirm, then resume: `QA_SELECTION` stays `on` and the `qa` step runs. Confirm with `qa:off` against a stored `on` fails. Catches confirm silently overriding the selection the way `mode` does.
- A pre-QA parked workflow (no `QA_SELECTION`) resumes, reaches verdict without a `qa_receipt` requirement, and reports `not_selected`. Catches the conditional requirement breaking in-flight workflows.
- With a dirty operator tree, the QA step's agent working directory is the temporary worktree at the target head. Afterwards the operator tree and HEAD are unchanged and the worktree directory is removed. A receipt carrying another tree identity is rejected. Use a real temporary git repository, because the bug is in git interaction.
- A forced worktree-removal failure emits a cleanup record with the retained path, and the step's own outcome is preserved.
- A `findings_open` receipt plus an unqualified approval report is rejected. A `skipped` receipt shows up as untested coverage and not as a finding.
- `qa:` on another operation, a duplicate `qa:`, conflicting values, and `qa:auto` each raise a usage error. Keep this to one parameterized CLI parser test.
- Run the existing telemetry schema parity test with the new field, adding a case only for the new enum's accept and reject.

No test for the label renumbering, the doc edits, or the MCP pass-through beyond one call proving `qa` reaches `OperationArguments`.

### Constraints

- Report-only. Verify never repairs application code, commits, pushes, or edits the operator's source, HEAD, or review target.
- The existing evaluators, criteria extraction, and `mode` semantics stay as they are.
- Production file line ceilings and wire-vocabulary rules apply. New QA logic lives in `VerifyQaSelection.kt`, `VerifyQaStep.kt`, and an optional `VerifyQaVerdictRule.kt`.
- Every fallback, skip, rejection, and cleanup failure emits an observability record per `docs/observability-policy.md`.
- Implement and audit run no build, tests, or checks. Validate owns them.
