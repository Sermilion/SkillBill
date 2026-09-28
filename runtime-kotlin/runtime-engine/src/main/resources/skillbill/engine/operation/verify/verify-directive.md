# Feature Verify Content

This file is the author-owned execution body for `bill-feature-verify`. It carries the workflow-state contract, continuation contract, stable step ids, stable artifact names, telemetry ownership rules, and the per-step orchestration prose.

`bill-feature-verify` runs entirely in-session as an orchestrator (Skill-tool-driven code review, unit-test checks, completeness audit) with no foreground `skill-bill` runtime driver and no per-phase agent subprocess.

## Step 1: Collect Inputs

Step id: `collect_inputs`

Primary artifact: `input_context`

Ask the user for the task spec and PR inputs.

Accept the task spec as pasted text, a file path, a directory, or another readable spec source. Accept the PR as a number, branch, or commit range. Accept PDFs if the spec lives in one; read them in page ranges when they are longer than 10 pages. If the total text exceeds roughly 8,000 words, ask which sections matter most before continuing.

The success artifact for this step is `input_context`: the normalized spec source, the verify target, and any user clarifications that later steps need.

## Step 1b: Rehydrate a Missing Linear-Mode Spec (before any spec read)

Determine the verify target's spec source from durable artifacts, never config.
When `decomposition-manifest.yaml` exists, read its `spec_source` field and
default an omitted field to `local`.
A bare `spec.md` is intake rather than prepared source authority.

For `spec_source: local`, feature-spec files are not required in the PR tree. A human
operator may have committed them; do not fail solely because they are absent, and do
not treat their presence as a defect. Read the task spec from the user-supplied input
or an available local checkout; do not make a Linear MCP call.

For a terminal Linear target, the committed tree carries no spec or manifest
because scratch cleanup deleted both. Treat the simultaneous absence of both
artifacts as requiring durable source resolution: derive the normalized
`issue_key` from the branch/PR and fetch that parent issue from Linear. A
successful exact-key lookup is the durable Linear source signal; failure to
resolve it loud-fails instead of defaulting the missing target to local. Enumerate
the parent's sub-issues from Linear, fetch each sub-issue by its
`linear_issue_id`, rewrite the parent spec + subtask specs locally, then extract
acceptance criteria as usual. Rehydrate is agent-side MCP only.

## Step 2: Extract Acceptance Criteria

Step id: `extract_criteria`

Primary artifact: `criteria_summary`

After reading the spec, produce in one pass:

1. **Acceptance criteria** — numbered list
2. **Non-goals** — things explicitly out of scope
3. **Rollout expectation** — does the spec require guarded rollout?
4. **Key technical constraints** — specific patterns, APIs, or architectural requirements

Then ask: **Confirm or adjust the criteria before I review the PR.**

The success artifact for this step is `criteria_summary`: acceptance criteria, non-goals, rollout expectation, and technical constraints.

Persist it with the exact wire fields `acceptance_criteria`, `non_goals`,
`rollout_expectation`, and `technical_constraints`.

After Step 2 is confirmed, call `feature_verify_started` and save the returned `session_id`. When the input context has an authoritative normalized issue key, retain it and pass `issue_key: <normalized issue key>` to `feature_verify_workflow_open`; otherwise omit the field rather than deriving one from presentation data, workflow ids, or free text. Immediately call `feature_verify_workflow_update` to mark `collect_inputs` and `extract_criteria` completed, set `gather_diff` to running, and persist `input_context` plus `criteria_summary`.

## Step 3: Gather PR Diff

Step id: `gather_diff`

Primary artifact: `diff_projection`

Based on user input, gather changes via `gh pr diff`, `git diff`, or `git log`. Resolve the immutable repository checkpoint and bounded comparison scope, then persist `diff_projection`, `feature_flag_policy`, `review_rubric`, `unit_test_value_rubric`, and `completeness_rubric` before advancing.

The success artifact is a typed `diff_projection` containing its checkpoint identity and bounded changed-file evidence. It must not contain the complete diff, prompts, logs, source bodies, telemetry, or progress payloads.

Persist `diff_projection` with the exact wire fields `checkpoint`,
`comparison_scope`, and `changed_files`. Persist each evaluator policy or rubric
with exactly `contract_version` and `rules`.

## Step 4: Feature Flag Audit (conditional)

Step id: `feature_flag_audit`

Primary artifact: `feature_flag_audit_receipt`

Use the Feature Flag Audit rubric below for the full rubric and output format.

The audit is legally skippable when the spec and diff do not require it. Persist a compact typed `feature_flag_audit_receipt` either way. Update workflow state so `feature_flag_audit` is completed or skipped and `code_review` is running.

Every evaluator receipt, including a skipped receipt, has the exact bounded
wire fields `contract_version`, `verdict`, and `findings`. Full evaluator
narratives and telemetry are private.

## Step 5: Code Review

Step id: `code_review`

Primary artifact: `code_review_receipt`

Run `skill-bill phase review` against the PR diff. Follow the full skill instructions including any matching `.agents/skill-overrides.md` section.

When this skill runs `skill-bill phase review`, this skill is itself a parent. Pass `orchestrated=true` to `import_review` and `triage_findings`. Store the returned `telemetry_payload` only in the dedicated telemetry store; persist a compact typed code-review receipt in workflow domain state.

Persist `code_review_receipt` after review finishes. Update workflow state so `code_review` is completed and `unit_test_value_check` is running.

Persist only the evaluator receipt wire shape defined in Step 4; raw review
output remains outside workflow domain state.

## Step 6: Unit Test Value Check

Step id: `unit_test_value_check`

Primary artifact: `unit_test_value_receipt`

Run `operation:unit-test-value-check` independently against acceptance criteria, its declared rubric, and the authoritative checkpoint-scoped diff projection. It must not receive code-review, feature-flag, or completeness output. If the diff contains no unit tests, persist a compact skipped receipt. Otherwise persist only its typed bounded receipt; keep the full report private.

Persist `unit_test_value_receipt` after the check finishes. Update workflow state so `unit_test_value_check` is completed or skipped and `completeness_audit` is running.

## Step 7: Completeness Audit

Step id: `completeness_audit`

Primary artifact: `completeness_audit_receipt`

Use the Completeness Audit rubric below for the audit format and rules.

Run completeness independently against acceptance criteria, its declared rubric, and the authoritative checkpoint-scoped diff projection; do not provide sibling evaluator output. Persist only `completeness_audit_receipt`. When the verify target changes materially during the same session, invalidate stale projections and receipts, refresh the checkpoint-scoped diff, and increment the next step's `attempt_count`. Otherwise, update workflow state so `completeness_audit` is completed and `verdict` is running.

## Step 8: Consolidated Verdict

Step id: `verdict`

Primary artifact: `verdict_result`

Use the Consolidated Verdict rubric below for the verdict format and PR comment instructions.

Persist `verdict_result` after the verdict is delivered. Update workflow state so `verdict` is completed and `finish` is running.

## Audit Rubrics

Use these inline rubrics for the feature-flag audit, completeness audit, and final verdict phases.

## Feature Flag Audit

**Skip if:** the spec does not require feature-flagged rollout, no feature flag appears in the diff, and repo policy does not require one.

**Run if:** the spec requires a feature flag, a feature flag appears in the diff, or the repo has explicit feature-flag policy for this change.

Verify against the repo's rollout requirements. If the repo does not define its own rollout rubric, use `operation:feature-guard` as a narrow checklist rather than assuming every repo follows it by default:

1. **Flag exists** — is the flag defined in the codebase?
2. **Rollback safety** — when flag is OFF, behavior is identical to before the PR
3. **Minimal checks** — feature flag checks are at the highest practical level (not scattered)
4. **Legacy preserved** — if Legacy pattern used, legacy code is untouched
5. **No hybrid states** — no mixing of old/new behavior paths
6. **Default value** — if a new flag is introduced, it defaults to `false` (disabled)

Output:

```
FEATURE FLAG AUDIT
Flag name: <name>
Pattern: Legacy / DI Switch / Simple Conditional / N/A

[ PASS | FAIL ] Flag defined in codebase
[ PASS | FAIL ] Rollback safe (flag OFF = identical old behavior)
[ PASS | FAIL ] Minimal flag checks (not scattered)
[ PASS | FAIL ] Legacy code untouched (if applicable)
[ PASS | FAIL ] No hybrid states
[ PASS | FAIL ] Default value is false

Issues: <list, or "None">
```

## Completeness Audit

For each numbered acceptance criterion, search the actual code changes to verify implementation:

```
COMPLETENESS AUDIT

Acceptance criteria: <total>
Implemented:         <count>
Missing:             <count>
Partial:             <count>

---

[PASS] #1: <criterion text>
  Evidence: FileA.kt:42, FileB.kt:88

[FAIL] #6: <criterion text>
  Not found — <reason>

[PARTIAL] #8: <criterion text>
  Missing — <what's missing>
```

**Rules:**
- Every criterion must have concrete file:line evidence or be marked FAIL
- "Partial" means some but not all aspects of the criterion are covered
- Check both positive (feature works) and negative (edge cases, error states) aspects
- If the spec mentions tests, verify test coverage exists for the criterion

## Consolidated Verdict

Merge all findings into a single report:

```
FEATURE VERIFY: <feature name>

--- ACCEPTANCE CRITERIA ---
<completeness audit>

--- FEATURE FLAG ---
<audit, or "N/A — no flag required">

--- CODE REVIEW ---
<risk register and action items>

--- UNIT TEST VALUE ---
<unit test value result, or "N/A — no unit tests changed">

--- VERDICT ---
<one of:>
  APPROVE — all criteria met, no blockers
  APPROVE WITH FIXES — all criteria met, but code issues need fixing [list P0/P1]
  REQUEST CHANGES — missing criteria or blockers [list what's missing/blocking]
```

After presenting the verdict, ask:
> **Would you like me to leave this as a PR comment, or fix any of the issues?**

If the user wants a PR comment:
- Format the verdict as a GitHub PR review comment using `gh pr review <number>`
- Use `--comment` for APPROVE WITH FIXES, `--approve` for APPROVE, `--request-changes` for REQUEST CHANGES

## Verification Input Boundary

Each verifier receives only its declared criteria and authoritative bounded
repository projection. Private workflow evidence, unrelated evaluator outputs,
telemetry, and complete upstream artifact maps are not prompt inputs. The
consolidated verdict consumes compact typed evaluator receipts, while repository
checkpoint state remains authoritative over receipt claims.

Durable least-context records are versioned boundaries. A legacy workflow,
briefing, handoff, private-evidence, or delivered-projection record must fail
through the typed workflow-contract hierarchy; it is never defaulted or decoded
as the current shape. The actionable operator guidance is to restart the active
run or use the documented out-of-band migration procedure. Error and
continuation surfaces identify the incompatible record and consumer projection
without copying private content.
