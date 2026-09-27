# SKILL-380 Subtask 9 - The plan, implement, and pr definitions

Parent spec: [spec.md](spec.md)
Issue key: SKILL-380

## Scope

Adds the three remaining short skeleton definitions (runtime-domain) and their
selection entries. Each runs through `PhaseRunEntry` and the one run loop over the
in-memory state (subtask 8); none adds step, launch, or entry code.

- `plan`: `preplan`, `plan` → `agent-preplan`, `agent-plan`
- `implement`: `implementation` → `implement-then-simplify`
- `pr`: `pull_request` → `pr-description`

**`phase plan`.** The `plan` definition runs preplan then plan. If preplan fails, plan
does not launch, because the loop stops on a failed slot as it does in a full run. Writes a governed spec bundle (parent spec,
subtask specs, decomposition manifest) through the runtime path the skeleton's
decomposition stop already uses: `FeatureTaskRuntimeDecompositionPlanner` rendering
through `FeatureSpecPreparationWriter`, with manifest schema validation before the
first write. Today's `bill-feature-spec` has the agent write the bundle in-session; that
in-session writing is not ported. Keep `bill-feature-spec`'s intake rules (issue key
required, spec type from `skill-bill config resolve-spec-type`, Linear rehydrate) as
definition inputs. Intake required (spec path, issue key, or description). No
`feature_task_workflows` row.

**`phase:implement`.** `implement-then-simplify`. Requires an existing spec. Mutates the
current worktree. Does not commit. Refuses without a spec. No workflow row.

**`phase:pr`.** The push and refusal rules live in the `pr-description` strategy and
follow from facts, not from which composition runs it:

- Uncommitted work is not committed.
- If the current local branch has commits that are not on the remote, push that
  branch, then open the PR. Already-pushed branches skip the push. In a skeleton run
  commit_push has already pushed, so the strategy finds nothing to push.
- Protected or default-branch checkouts refuse with a typed error. A skeleton run is
  always on its feature branch, so the check passes there.
- PR identity (URL, number, created or reused) is measured by the runtime, as subtask 5
  made it for the skeleton.
- Intake optional. Until subtask 11 lands, the PR body follows today's pr directive.

Instructions apply to every step in a definition unless scoped. No resume. No listed-skill
changes.

Capture all three definitions' output and telemetry fixtures under
`featuretask/slotbaseline/phase/`.

## Verification ownership

Implement and audit can't run builds, tests, generators, or the fixture capture
(added 2026-09-27). Audit checks each behavioural criterion against the code and the
tests that assert it, and does not list a criterion as remaining just because nothing
has run.
- Validate runs the capture command
  `cd runtime-kotlin && SKILL_BILL_SLOTBASELINE_CAPTURE=1 ./gradlew :runtime-engine:test --tests skillbill.engine.featuretask.slotbaseline.SlotBaselineCaptureTest`,
  which first captures the three new phase-run fixtures. It confirms every existing
  fixture is unchanged, then runs `./gradlew check`.

## Acceptance Criteria

1. `skill-bill phase plan <intake>` writes a schema-valid spec bundle through `FeatureSpecPreparationWriter` (a test asserts `skill-bill goal preflight` accepts it), does not launch plan when preplan fails, and inserts no feature-task workflow or session row. Missing intake is a usage error.
2. `skill-bill phase implement` refuses without a spec. With a spec it mutates the worktree, creates no commit, and writes no workflow row.
3. `skill-bill phase pr` does not commit uncommitted work. On a local branch whose commits are not on the remote it pushes that branch and then opens the PR. Protected or default-branch checkouts fail with a typed error. No workflow or session row.
4. None of the three definitions adds step, launch, or entry code; each is a definition plus selection entries.
5. `SlotBaselineCaptureTest` captures the plan, implement, and pr phase-run outputs, and this subtask's diff changes no existing fixture file.

## Non-goals

- PR template rules (subtask 11). The dispatcher (subtask 12). Operations (SKILL-382).
- A preplan-only definition. A `commit_push` definition.

## Dependency notes

- Depends on subtask 8. Recheck `PhaseRunEntry`, CLI parser, git, and `gh` call
  sites at start.
- If a strategy needs a fact the skeleton state provides implicitly (for example a plan
  output path or the pushed branch), add it as a per-call fact both compositions pass.
  Do not branch on the composition.

## Validation strategy

Catch: plan after failed preplan; plan without intake; implement without spec;
implement committing; pr committing dirty files; pr skipping the push when ahead of the
remote; a workflow row. Cover with CLI usage-error tests, a dirty-tree non-commit test,
an ahead-of-remote push test against a local bare remote, and SQLite assertions. Run
`cd runtime-kotlin && ./gradlew check` plus engine, CLI, infra-sqlite.
`bill-unit-test-value-check` on changed tests.

## Next path

Continue to `spec_subtask_10_goal-planning-definition.md`.

## Spec Path

.feature-specs/SKILL-380-phase-slot-strategies/spec_subtask_9_phase-plan-implement-pr.md
