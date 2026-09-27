# SKILL-382 - Runtime operations

## Mode

decomposed

## Start gate

SKILL-380 (phase slot strategies) is merged to main. This bundle builds on its parts:
the one `PhaseRunner`, the phase input and output shape, skeleton definitions run
through `PhaseRunEntry` (including `review` and `validation`), and the `/skill-bill`
dispatcher.
Read [SKILL-380's spec](../done/SKILL-380-phase-slot-strategies/spec.md) and its
[investigation](../done/SKILL-380-phase-slot-strategies/investigation.md) first.

## Intended outcome

Standalone jobs that are not skeleton slots become runtime-owned operations, invoked
as `skill-bill operation <name>` or `/skill-bill operation:<name>`.

- **`Operation`** = runtime-owned pre and post around a sequence of steps. An agent
  step runs through `PhaseRunner` with the SKILL-380 phase input and output shape, using
  an operation-local step name that never enters the skeleton's domain graph. An
  operation may also run a short skeleton definition (`review`, `validation`) as a
  step. Operations never open a feature-task workflow and never launch an agent any
  other way.
- **Confirmation gate.** An operation that mutates, posts, pushes, or tags on operator
  consent runs in two invocations (see below).
- Closed operation ids: `update-check`, `release`, `unit-test-value-check`,
  `feature-guard`, `feature-guard-cleanup`, `pr-review-fix`, `verify`.
- The old listed skills these replace (`bill-update-check`, `bill-release`,
  `bill-unit-test-value-check`, `bill-feature-guard`, `bill-feature-guard-cleanup`,
  `bill-pr-review-fix`, `bill-feature-verify`) keep working beside the operations.
  SKILL-383 deletes them.

Main stays usable after this bundle's PR: every operation works from the CLI and the
dispatcher, and every old skill still works.

## Operation confirmation gate

Headless agent steps cannot talk to the operator, so consent crosses two
invocations.

1. `skill-bill operation <name> …` runs pre and a read-only proposal step. It prints
   the proposal and a token, exits with the documented `awaiting_confirmation` exit
   code, and changes nothing.
2. The caller (the `/skill-bill` dispatcher, or a person at the CLI) shows the
   proposal and asks once.
3. `skill-bill operation <name> confirm:<token> [select:…]` executes exactly the stored
   proposal. Asking for changes produces a new proposal and token; the old token is
   superseded.

Proposals live in an `operation_proposals` table (subtask 1) with the operation id,
repo root, runtime-measured anchors, the proposal step's prose value, and consumed
time. `operation:verify` uses the same outcome type and `confirm:` token, but its
proposal state is its feature-verify workflow, not an `operation_proposals` row.

## Acceptance Criteria

1. `Operation` and `OperationRegistry` exist in runtime-engine. The registry is one explicit `@Provides` list; duplicate ids and unknown lookups fail with a typed error.
2. Every agent step of every operation runs through `PhaseRunner` with the SKILL-380 phase input and output shape. SKILL-380's launch-port architecture rule covers the operation packages and fails on a synthetic violation.
3. `skill-bill operation <name>` runs every closed operation id through pinned engine inbound API types. An unknown id is a usage error. `/skill-bill` refuses a request carrying both `phase:` and `operation:`.
4. No operation writes a `feature_task_workflows` or `feature_task_runtime_sessions` row. Operation telemetry uses `invocation_id`.
5. An operation that mutates, posts, pushes, or tags on consent returns `awaiting_confirmation` with a token on its first invocation and changes nothing. `confirm:<token>` executes the stored proposal and refuses a token that is unknown, consumed, superseded, for another operation or repo, or whose anchors moved.
6. `/skill-bill operation:<name>` reaches every operation, and the dispatcher relays `awaiting_confirmation` with one question and never sends `confirm:` without an operator answer.
7. Every old listed skill this bundle replaces still works. No `skills/` tree is deleted.
8. `runtime-kotlin/ARCHITECTURE.md` and `AGENTS.md` describe operations, the confirmation gate, and how to add an operation. `runtime-kotlin/agent/decisions.md` records the gate.

## Executable scope

Four subtasks, one commit each. Each subtask adds its operations' routes to the
`/skill-bill` dispatcher, so every commit is usable from the slash command.

1. **Operation contract, confirmation gate, `update-check`, and `release`**
   (`spec_subtask_1_operation-contract-and-release.md`).
   - Split condition: the contract and the gate need real users to prove them;
     `update-check` proves the no-consent path and `release` the consent path.
2. **Checklist operations** (`spec_subtask_2_checklist-operations.md`).
   - `unit-test-value-check`, `feature-guard`, `feature-guard-cleanup`.
3. **`operation:pr-review-fix`** (`spec_subtask_3_pr-review-fix-operation.md`).
   - Split condition: large gated GitHub loop.
4. **`operation:verify`** (`spec_subtask_4_verify-operation.md`).
   - Split condition: different durable contract (feature-verify workflow family).

Dependencies: 2→1, 3→1, 4→1, 4→2 (verify runs the unit-test value check as a step).

## Verify stays report-only (decided 2026-09-25)

`operation:verify` never edits the PR it checks; the operator decides afterwards what to
do with the findings. So verify does not run the `review` definition, which finds and
fixes (SKILL-380). Its `code_review` step is an operation step that reuses the review
building blocks and runs through `PhaseRunner`:

- default: one read-only review agent step with a findings-only directive, reusing the
  inline review's target and diff composition and its F-XXX register decoder
- `mode:delegated`: the multi-agent review step `DelegatedReviewStrategy` composes,
  which is already read-only
- neither runs verify_findings or implement_fix, and neither edits a file

## Constraints

- SKILL-380's constraints stay in force: Kotlin style, SKILL-378.2's rules, one
  execution path, AI-facing I/O, loud failure, no install inside the goal, local clone
  for Spotless.
- Operations are standalone jobs, not skeleton slots. The runtime owns pre, run, and
  post. They are not listed skills.
- Nothing selects the `delegated` review strategy unless the operator passed
  `mode:delegated`.
- `operation:verify` is report-only; it never fixes code.
- Proposal payloads are the proposal step's prose value plus runtime-measured anchors.
  No new required structure in agent-written output.
- Telemetry `skill` labels of the old skills stay (for example `bill-feature-verify`).
- Recheck every file anchor at the start of each subtask against the current tree.

## Non-goals

- Deleting any `skills/bill-*` tree, re-parenting sidecars, or the single catalog
  (SKILL-383).
- `bill-monitor` (SKILL-383 deletes it; it is not an operation).
- Rewriting verify into a feature-task skeleton.
- Proposal expiry by time. Anchors decide staleness.
- Changing CI that reacts to tags, or the GitHub reply and learnings product.

## Validation strategy

- **Per subtask:** `cd runtime-kotlin && ./gradlew check`, plus the CLI, engine, MCP,
  and infra-sqlite suites.
- **Guards.** The launch-port rule extension gets a synthetic violation and asserts it
  read at least one file.
- **Contract tests.** Duplicate and unknown operation ids, `phase:` plus `operation:`,
  a stale, consumed, superseded, or foreign confirmation token, and the migration over
  a pre-change database.
- **Fixtures.** Each subtask captures its operations' output and telemetry fixtures.
  SKILL-380's full-run and phase-run fixtures still match.
- **Test review.** Changed tests go through `bill-unit-test-value-check`.
- No tests ran during preparation.

## Next path

```bash
skill-bill goal SKILL-382
```

Then SKILL-383 (single skill catalog).
