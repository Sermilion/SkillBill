# SKILL-382 Subtask 4 - operation:verify

Parent spec: [spec.md](spec.md)
Issue key: SKILL-382

## Scope

Migrates `bill-feature-verify` (320 lines) to `operation:verify`.

The operation keeps the **feature-verify** durable workflow family
(`feature_verify_workflow_*` tools and tables). It must not write
`feature_task_workflows` rows. Step ids, artifact names, and telemetry events stay as
in today's `../../../skills/bill-feature-verify/content.md`.

**Step ownership.** Today one agent session runs all nine steps. After this subtask
every agent step runs through `PhaseRunner`, and review is the `review`
definition run as a step:

| Step id | Owner | Artifact |
| --- | --- | --- |
| `collect_inputs` | runtime pre (spec source, verify target, Linear rehydrate as today) | `input_context` |
| `extract_criteria` | agent step (read-only), then the confirmation gate | `criteria_summary` |
| `gather_diff` | runtime, in-process | `diff_projection` |
| `feature_flag_audit` | agent step (read-only), conditional as today | `feature_flag_audit_receipt` |
| `code_review` | read-only review operation step through `PhaseRunner` (parent "Verify stays report-only"): findings-only single-agent review by default, the multi-agent review step with `mode:delegated`; no verify_findings or implement_fix | `code_review_receipt` |
| `unit_test_value_check` | the `operation:unit-test-value-check` step (subtask 2) against the diff projection only | `unit_test_value_receipt` |
| `completeness_audit` | agent step (read-only), isolated from the other receipts as today | `completeness_audit_receipt` |
| `verdict` | agent step over the receipts, using today's consolidated-verdict rubric | `verdict_result` |
| `finish` | runtime post: `feature_verify_finished`, final workflow update | — |

Each artifact holds its step's prose value. The `code_review` step keeps today's review
telemetry: it imports the review step's decoded findings through `import_review` and
`triage_findings` with `orchestrated=true`, as today's skill does after
`bill-code-review`. Rubrics (feature-flag audit, completeness
audit, consolidated verdict, verification input boundary) move into runtime-owned prompt
fragments. If artifact content changes shape, bump the feature-verify contract version
and keep older workflows readable.

**Report-only review.** The `code_review` step never edits. It reuses the inline
review's target and diff composition and F-XXX register decoder with a findings-only
directive, or, with `mode:delegated`, the multi-agent review step, which is already
read-only. It does not run the `review` definition. Record in `../../../agent/decisions.md` that
verify reports and the operator decides what to fix.

**Confirmation.** Today's "Confirm or adjust the criteria before I review the PR" uses
the subtask 1 outcome type and `confirm:<token>` CLI grammar. The token is the
feature-verify `workflow_id`, and the pending proposal is the workflow parked after
`extract_criteria`. There is no `operation_proposals` row. `feature_verify_started`
fires after confirmation, as today. Adjusting criteria re-runs `extract_criteria` with
the operator's instructions and parks again.

**Continuation.** `feature_verify_workflow_continue` / `resume` keep working. A
continued workflow resumes at `continue_step_id` under the table above.

Telemetry labels stay `bill-feature-verify`. Add the route to the `/skill-bill`
dispatcher, including `mode:` forwarding.

## Acceptance Criteria

1. `skill-bill operation verify` opens and updates feature-verify workflow state, not `feature_task_workflows`.
2. The first invocation parks after `extract_criteria` with `awaiting_confirmation` and the workflow id as token, and `feature_verify_started` has not fired. `confirm:<workflow_id>` runs the remaining steps.
3. The nine step ids and eight artifact names are unchanged, each step is owned as in the table, and unknown verify contract versions still loud-fail.
4. `code_review` is a read-only review step (single-agent by default, multi-agent with `mode:delegated`) that changes no file and runs no verify_findings or implement_fix, `unit_test_value_check` runs the subtask 2 operation step, neither receives another step's receipt, and no verify step launches an agent outside `PhaseRunner`.
5. A verify run leaves the worktree unchanged (git status and HEAD identical before and after), and `../../../agent/decisions.md` records that verify is report-only.
6. `/skill-bill operation:verify` routes to the CLI. Subtasks 1–3 operations still register, SKILL-380 fixtures still match, and `../../../skills/bill-feature-verify` still works.

## Non-goals

- Rewriting verify into a feature-task skeleton. Deleting `../../../skills/bill-feature-verify`
  (SKILL-383). Renaming the `bill-feature-verify` telemetry label or `workflow_name`
  default.

## Dependency notes

- Depends on subtask 1 (gate) and subtask 2 (unit-test value operation). Independent of
  subtask 3. Recheck verify MCP/workflow anchors at start.

## Validation strategy

Catch: verify editing a file or running implement_fix; a feature-task workflow row; a dropped verify step id; review or unit-test check
receiving sibling receipts; `feature_verify_started` firing before confirmation; an
older verify workflow failing to resume. Cover with a worktree-unchanged assertion per mode, SQLite family assertions, a
park-and-confirm test, an input-isolation test, a contract-version rejection test, and a
resume over a pre-change workflow. Run `cd runtime-kotlin && ./gradlew check` plus CLI,
MCP, infra-sqlite. `bill-unit-test-value-check` on changed tests.

## Next path

Last subtask. `skill-bill goal SKILL-382` opens the PR. Then SKILL-383.

## Spec Path

.feature-specs/SKILL-382-runtime-operations/spec_subtask_4_verify-operation.md
