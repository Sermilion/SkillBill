# SKILL-442 - cross-criterion-audit-test-value-probes-and-goal-assessment

## Mode

decomposed

## Intended Outcome

SKILL-442 Cross-criterion audit, test-value probes, and goal assessment.

Requirements source: docs/plans/cross-criterion-audit-and-test-value.md. Its acceptance criteria, constraints, non-goals, sizing note, and open questions are preserved below.

## Problem

Goal runs close every acceptance criterion and still ship defects that live between criteria. An outside review of GV-1 (Galaxy Valkyries save, settlement, and checkpoint remediation, subtasks 1-5) found one Blocker and three Majors after audit, review, and validation had all passed. Three of the four have the same shape. Each criterion holds on its own, and the defect appears only on a user flow that crosses two of them.

GV-1 is a Godot project that ran on the generic pack. Godot is incidental. The defect class depends on how criteria are checked, not on the stack, and it appears anywhere one criterion constrains a state that another criterion's flow depends on. A web service can reject an invalid config at load while a second criterion requires a successful persist before redirecting, so a bad config file makes every form submission hang. A CLI can lock a cache on corruption while a second criterion requires a cache write before exit, so the command never exits. A mobile app can drop an unsupported token while a second criterion requires a valid token before showing the logout screen, so the user can't log out. The GV-1 cases below are evidence. They are not the design target.

- GV-1 subtask 2. AC "retain invalid originals, offer fresh defaults" closed when the implementation blocked every write after a failed load. AC "callers do not claim durable success" closed when hub navigation and quit started requiring a committed save. Together they soft-lock the end screen for any player with a corrupt save, the exact case the subtask exists for.
- GV-1 subtasks 1 and 2. Subtask 1 rejects any model whose lifetime damage exceeds 2^31. Subtask 2 validates every commit's readback with the same validator and gates quit on commit. Gameplay never caps the counter, so a long-lived save reaches the subtask 2 soft-lock through a subtask 1 rule.
- GV-1 subtask 3. A boss reward reservation is persisted before the final commit, but only that commit or a full reset removes it. The criterion for atomic settlement passed. The flow "final commit fails, player quits, relaunches" was never traced.

The pipeline today makes these structurally invisible:

- `AcceptanceAuditPromptSections.DIRECTIVE` tells the auditor to inspect one criterion at a time, says "Do not open a different criterion to chase unrelated work", and says "Criteria accepted by an earlier audit stay accepted and are not re-checked."
- `acceptance-criterion-audit-result-schema.yaml` already carries `regressed_refs` and criterion `dependencies`. `AcceptanceCriterionRepairAuditAdmission.noGraphChanges` rejects any targeted audit that reports a regression, so the runtime cannot reopen an accepted criterion.
- Audit excludes tests by design. Review leaves criterion gaps to audit. Validation runs tests but never asks whether they can fail. GV-1 contains tests that pass with the production check deleted: snapshot rows that pass because every snapshot was rejected, a round-trip assertion through a pure function that returns its input, and a fixture freed before the assertion runs.
- Goal QA runs only for packs that declare QA support. Nothing traces a parent-spec flow across subtasks before the PR.

## Goal

Audit judges how criteria behave together on real entry points, not just whether each one holds. Validation proves that the tests it relies on can fail. When every subtask is done, an independent goal assessment reviews the whole goal at the branch head, fixes confirmed gaps, and reassesses until it converges. None of these checks blocks a goal because the check itself could not run.

## Non-goals

- Generic QA for stacks without a QA-capable pack. That is a separate spec.
- Checking that implementation structure matches ownership directives in spec prose, such as "RunController owns settlement".
- Review learnings and triage.
- Changing review depth, review areas, or the severity vocabulary.
- Mutation-testing frameworks. The probe in criterion 4 is agent-applied and bounded, with no new tool dependency.


## Acceptance Criteria

1. Planning authors interaction scenarios alongside acceptance criteria.
   `plan/feature-spec-directive.md` requires a `## Interaction Scenarios` section in every subtask spec that has two or more criteria, and in every authored parent spec whose subtasks share state, files, or user-facing entry points.
   Each scenario has an `IS-NNN` identifier, the two or more criterion identifiers it crosses, the entry point a user or caller actually invokes (a command, screen action, API route, or public function by name), its preconditions including any failure state, its steps, and the observable outcome that must hold.
   For every criterion whose text describes a failure, rejection, retry, or recovery behavior, at least one scenario must start in that failure state and continue through the next user action that depends on it. Examples: "the stored config is invalid, then submit the form, then the redirect happens", or "the cache is corrupt, then run the command, then the process exits".
   A subtask spec holds at most six scenarios. The parent spec holds at most six cross-subtask scenarios, and each names the subtasks it spans.
   The runtime parses scenarios into a catalog next to the criterion catalog, keyed by spec digest, and loud-fails a malformed section through the existing producer-side planning fix loop. A spec with fewer than two criteria and no parent-level sharing needs no section, and its absence is not an error.
   The directive also adds a `## Transitional Regressions` section to the parent spec. It lists every shipped behavior that a subtask intentionally disables until a later subtask restores it, together with the disabling and restoring subtask IDs. Examples: a subtask that rejects an old file format before a later subtask adds its migration, or one that removes an endpoint before a later subtask adds its replacement.
   This criterion does not cover tracing. Criteria 2 and 5 own it.
   Audit confirms this criterion by reading the directive text, the catalog parser and its contract, and the fix-loop rejection path.

2. Audit traces interaction scenarios after every criterion is accepted and can reopen criteria.
   When the subtask's acceptance-criterion repair state reaches `accepted` and the catalog has scenarios, the runtime runs one read-only audit invocation in a new mode, `interaction_trace`, before review.
   The invocation receives the scenario catalog and the accepted criteria with their `closure_evidence_ref` values. It also receives a runtime-computed list of criterion pairs whose closure evidence cites the same production file, and the auditor must consider those pairs even when no scenario names them.
   For each scenario, the auditor traces production code from the named entry point to the stated outcome, cites `path:line` for each step, and returns `holds`, `broken`, or `unknown` with the cited trace.
   A `broken` scenario names the criterion that owns the fix. The runtime records it in `regressed_refs`, moves that criterion to `source_unresolved` with the scenario trace as its gap, and routes it through the existing serial repair loop under that criterion's existing three-outcome budget. After a successful repair, the targeted audit re-verifies the criterion and re-traces only the broken scenario.
   `AcceptanceCriterionRepairAuditAdmission.noGraphChanges` admits `regressed_refs` for `interaction_trace` output and keeps rejecting them for `current_criterion_targeted` output.
   `unknown` never blocks. The runtime records it as a diagnostic, passes it to review as context, and lists it in the PR description.
   `AcceptanceAuditPromptSections.DIRECTIVE` keeps per-criterion focus for targeted audits. It replaces "Criteria accepted by an earlier audit stay accepted and are not re-checked" with a sentence saying accepted criteria are re-checked only when the runtime lists them in criterion 2 or criterion 3 scope.
   The audit result contract adds `interaction_trace` to `mode` and an `interactions` array of `{scenario_ref, verdict, owner_ref, trace_ref}`. The change follows the AGENTS.md contract sequence: schema, Kotlin contract version, parity test, and a `RuntimeFailureCode` entry for malformed interaction output.
   Telemetry records scenarios traced, broken, repaired, and unknown per subtask, and `goal-stats` reports the totals.
   Audit confirms this criterion by reading the new mode's strategy wiring, the admission change, the prompt text, the contract, and the telemetry fields.

3. Later repairs re-check accepted criteria whose evidence they touch.
   During the audit repair loop, whenever a repair commit changes a file that an already-accepted criterion's `closure_evidence_ref` cites, the runtime adds that criterion to a re-check set. The runtime extracts `path` tokens from `path:line` citations in the evidence ref. When it cannot extract any path from an accepted criterion's evidence, it records a `RuntimeDiagnosticsBestEffortWarning` and includes that criterion in the set whenever a repair changes any file.
   Before audit settles, the runtime runs one batched read-only re-check of the set in the same `interaction_trace` invocation as criterion 2, or in its own invocation when the catalog has no scenarios.
   A criterion that no longer holds enters `regressed_refs` and the repair loop as in criterion 2. A criterion that holds keeps its acceptance and gets a new `closure_evidence_ref`.
   An empty re-check set launches no agent.
   This criterion does not re-check criteria whose cited files no repair touched.
   Audit confirms this criterion by reading the path extraction, the set computation over repair commit diffs, and the batching into the interaction invocation.

4. Validation proves that the tests it relies on can fail.
   After every required check passes in a goal-subtask validate step, the validator probes tests that the subtask added or changed.
   It selects at most six tests. Priority goes first to tests covering a scenario that criterion 2 marked broken and then repaired, then to one test per criterion whose `validation_obligation` is `required`, then to the remaining changed tests in diff order.
   For each selected test, the validator names the production line or branch the test guards. In the validator's isolated copy only, never in the workflow checkout, it applies the smallest change that removes that behavior, runs only that test, and restores the copy.
   Each probe records `killed` when the test failed, `survived` when it passed, or `not_run` with a reason.
   A `survived` probe means the test cannot catch the regression it claims to catch. The validator strengthens the test inside its existing repair loop, for example by asserting the specific outcome rather than only success, by driving the named entry point instead of an internal helper, or by keeping its setup in place through the assertion. It then re-probes once. It never changes production code to make a probe pass and never weakens an assertion.
   For every scenario that criterion 2 marked broken and then repaired, at least one probed test must drive that scenario's entry point. When no such test exists, the validator writes one.
   When no isolated copy or targeted test command is available, every probe records `not_run`, the validator emits a warning, and validation completes on its normal checks.
   `qualitygate/opus-5-5-agent-validate.md` and `QualityGatePromptDirectives` carry the probe instructions. The validate phase output adds a `test_probes` list of `{test_ref, guarded_ref, result, reason}`, with a contract version bump and parity test. Telemetry records killed, survived, repaired, and not_run counts.
   Standalone `phase:validation` does not run probes.
   Audit confirms this criterion by reading the prompt text, the output contract, the isolated-copy requirement, and the telemetry fields.

5. A goal assessment phase reviews the whole goal after every subtask is done and loops assess, fix, reassess until it converges.
   The goal runner adds a `goal_assessment` stage after the last subtask commits and before goal QA. It runs once per goal, in a multi-subtask or single-subtask goal alike, against the feature branch head.
   Assessment is independent of the pipeline's own verdicts. Assessors receive the parent spec, every subtask spec, the `## Interaction Scenarios` and `## Transitional Regressions` sections, and each subtask's commit range. They do not receive audit, review, or validation outputs, receipts, or acceptance states, so earlier passes cannot anchor them.
   The runtime launches one read-only assessor per subtask, and they may run in parallel. Each judges its subtask's criteria against its spec at the branch head rather than at the subtask commit, so a defect a later subtask fixed is not reported and a defect a later subtask introduced is. Each assessor reports confirmed defects with `path:line`, a concrete failure scenario, and a severity. It also reports tests that cannot fail with their guarded behavior. One more read-only assessor traces the parent's cross-subtask scenarios and transitional regressions, marking each regression `restored` or `not_restored`, and looks for defects that chain across subtasks, such as a validation rule in one subtask that feeds a gate in another.
   Before any fix, a separate read-only verifier re-reads each Blocker and Major at the branch head and marks it `confirmed` or `refuted` with evidence. Only confirmed findings and `not_restored` regressions enter the fix step. Minors and test-value findings go to the fix step only when they share a file with a confirmed finding. Every other finding goes to the PR description.
   The fix step uses the implementation tier, works on the feature branch, fixes all confirmed findings in one pass, and is followed by the full project validation that already runs after goal QA changes code. The runtime commits the result as one `goal_assessment` commit per round.
   The next round reassesses only the files the fix changed, plus every scenario and regression that touches them, and verifies any new Blockers and Majors the same way.
   The loop ends at the first of these: a round with no confirmed Blocker or Major, three completed rounds, or a confirmed finding with the same `path` and failure scenario surviving two fix rounds. Every finding still open at the end is listed in the PR description and the desktop goal activity view with its evidence, and the goal continues to QA and the PR. The phase never blocks the goal. An assessor or verifier that fails or returns malformed output records a diagnostic, and the round continues with the outputs it has.
   The execution matrix gets a `goal_assessment` phase in the reasoning tier and a `goal_assessment_fix` phase in the implementation tier. `.skill-bill/phases/goal_assessment.md` adds project instructions like any other phase.
   The desktop goal view shows the stage, its round, and its findings with confirmed or refuted state. Telemetry records findings by severity, confirmed and refuted counts, rounds, the exit reason, and findings left open, and `goal-stats` reports them.
   A goal whose parent spec has no interaction or regression sections still runs the per-subtask assessors and the verifier.
   This criterion does not add goal-level QA for packs without QA support and does not replace per-subtask audit or review.
   Audit confirms this criterion by reading the goal runner stage order, the assessor input assembly and its exclusion of pipeline verdicts, the verifier gate, the loop exit rules, the commit and validation reuse, and the PR, desktop, and telemetry fields.

## Constraints

- Stack-agnostic. Every new prompt, contract, and runtime rule works on any stack, including stacks with no platform pack. Nothing names a language, framework, engine, or test runner. Agents discover entry points, test commands, and how to remove the behavior a test guards from the repository, the same way validation discovers checks today. A platform pack may add optional guidance through its existing add-on or `.skill-bill/phases/` surfaces, but no criterion depends on one. Prompt examples come from at least two different stacks, or are abstract.
- Every new check warns and continues when it cannot run. Only a substantive `broken` or `regressed` verdict enters a repair loop, and every repair loop uses an existing budget. No new budget and no new blocking reason.
- All contract changes follow the AGENTS.md sequence: Draft 2020-12 schema, Kotlin `*_CONTRACT_VERSION`, parity test, owner `RuntimeFailureCode` entry, loud failure at the parse seam. Wire keys go in the owning `*PayloadKeys` objects.
- Every fallback and skip emits a record, per `docs/observability-policy.md`.
- Prompt text stays short. Each new directive should be a few sentences added to the owning prompt section, not a new document.
- Tests: one regression test per criterion, naming the realistic bug it would catch. Fixtures are synthetic and stack-neutral. Suggested anchors follow.
  - Criterion 2: a two-criterion fixture where each criterion passes alone and their interaction scenario is broken. The trace must reopen the owner criterion.
  - Criterion 3: a repair that edits a file cited by an accepted criterion must put that criterion in the re-check set.
  - Criterion 4: a test that passes with its guarded line removed must be recorded as `survived`.
  - Criterion 5: assessor input must not contain audit or review output. A refuted Major must not reach the fix step. A finding that survives two fix rounds must end the loop and appear in the PR description.

## Measuring it

The feature earns its place if broken scenarios found by `interaction_trace` and probes found `survived` are non-zero on real goals, and if outside reviews like the GV-1 one stop finding cross-criterion defects. Compare goal-stats totals for the first ten goals after rollout with the GV-1 baseline: four post-audit Major or Blocker defects across five subtasks, three of them cross-criterion.

## Sizing note

Criteria 2 and 3 share the new audit mode and admission change, so they belong in one subtask. Criterion 4 is its own subtask. Criterion 5 is the largest and probably splits into the stage with its assessors and verifier, then the fix loop with exit rules, then the desktop and telemetry surfaces. Criterion 1 lands first, because 2 and 5 consume its catalog. Criterion 5 works without 1 through 4, so if it ships early it gives the earliest return: GV-1's defects were found by exactly this procedure.

## Why both per-subtask audit and goal assessment

Per-subtask audit is cheap, focused, and repairs while context is fresh. Goal assessment is expensive but sees what audit cannot: the final tree, every subtask together, and fresh eyes that never read the pipeline's own verdicts. Criteria 1 through 4 shrink what goal assessment finds. Criterion 5 catches what remains. If goal assessment keeps finding cross-criterion defects after 1 through 3 ship, that is the signal those criteria need work.

## Open questions

- Should criterion 2's runtime-computed same-file pairs also be offered to review as hunks to read together, or only to audit?
- Is six the right probe cap for large subtasks, or should it scale with the number of required criteria?
- Should a scenario that stays `unknown` across two goals in the same repository become a planning hint for the next goal's spec?
- Should goal assessment use a different agent than the one that implemented the goal, when more than one is configured, for extra independence?
- Is three rounds right, or should the cap depend on how many confirmed findings the first round produces?

## Planning Decisions

These resolve the preplan's open decisions. Every subtask spec restates the decisions it depends on.

- Structured verdicts travel as parsed prose lines inside the existing `feature_task_phase_complete` value. No MCP settlement argument is added. Interaction traces, re-checks, test probes, assessor findings, and verifier verdicts each get a fixed line grammar, defined in the owning subtask.
- Malformed structured lines get one re-ask inside the existing attempt budget. A second malformed output records a `RuntimeDiagnosticsBestEffortWarning` and the affected verdicts degrade to their non-blocking value (`unknown` for traces, `not_run` for probes, a dropped assessor output for assessment). No new block reason and no new budget.
- The test-probe list gets its own new contract, `feature-task-runtime-validation-test-probes-schema.yaml`, at version `0.1`, with its Kotlin constant and parity test. The phase-output envelope stays at `0.7`. Its `phaseProseProducedOutputs` description gains a description-only note, the same pattern `no_change` uses. This new governed version is the contract version event AC-004 asks for. A full envelope bump to `0.8` was rejected because it forces a readable-version migration for every stored phase output and buys nothing the new contract does not.
- The interaction-trace pending and done marker lives in the acceptance-criterion repair state, which moves from contract `0.1` to `0.2` once, in subtask 2, together with the re-check set fields subtask 3 writes. One bump instead of two.
- An operator-authored (protected) parent spec never has to carry `## Interaction Scenarios` or `## Transitional Regressions`. Their absence, or an unparseable section in such a parent, records a diagnostic and the parent is treated as having none.
- Goal assessors run sequentially in the first cut. The criterion allows parallel runs but does not require them.
- `interaction_trace` is a runtime-scoped invocation of the existing `audit` step, not a new phase id.
- Telemetry and `goal-stats` for AC-002, AC-004, and AC-005 land together in subtask 8, after every producer of the counted facts exists.
- The open questions above stay open and unclaimed. No subtask implements an answer to them.

## Subtasks

1. Interaction scenario authoring and catalog (AC-001).
2. Interaction trace audit mode (AC-002 core: contracts, domain transition, admission, prompt, wiring).
3. Re-check of accepted criteria and unknown-scenario surfacing (AC-003, and the AC-002 `unknown` handling).
4. Validate test probes (AC-004 except telemetry).
5. Goal assessment stage, assessors, and verifier (AC-005 part 1).
6. Goal assessment fix loop and exit rules (AC-005 part 2).
7. Goal assessment PR, status, and desktop surfaces (AC-005 part 3).
8. Telemetry and goal-stats for traces, probes, and assessment (telemetry parts of AC-002, AC-004, AC-005).

## Interaction Scenarios

1. IS-001. Criteria: AC-001, AC-002. Subtasks: 1, 2.
   Entry point: `skill-bill goal SKILL-442` style goal run, at the plan step's `feature_task_phase_complete` settlement.
   Preconditions: the plan authors a subtask spec with three criteria whose `## Interaction Scenarios` item names only one criterion.
   Steps: the plan settles; the runtime rejects the bundle with field path `subtasks[0].interaction_scenarios`; the plan relaunches and fixes the item; the subtask reaches audit and every criterion is accepted.
   Outcome: the `interaction_trace` invocation receives exactly the scenario ids the corrected spec lists, bound to the same spec digest as the criterion catalog.
2. IS-002. Criteria: AC-002, AC-004. Subtasks: 2, 4.
   Entry point: the goal-subtask `validate` step.
   Preconditions: `interaction_trace` marked IS-001 `broken`, its owner criterion was repaired, and the re-trace marked IS-001 `holds`.
   Steps: validate runs every required check, they pass, and the validator selects probes.
   Outcome: the validate prompt lists IS-001 as broken-then-repaired, the first selected probe is a test that drives IS-001's entry point, and when no such test exists the validator writes one before probing.
3. IS-003. Criteria: AC-002, AC-003. Subtasks: 2, 3.
   Entry point: the audit repair loop after an `audit_implement_fix` round.
   Preconditions: AC-001 is accepted with closure evidence citing `src/a.ext:10`; AC-002 is under repair, and its repair edits `src/a.ext`.
   Steps: AC-002's targeted audit passes; every criterion is accepted; the runtime launches the trace invocation.
   Outcome: one `interaction_trace` invocation carries both the pending scenarios and AC-001 in re-check scope. If AC-001 no longer holds, it reopens under its own existing three-outcome budget.
4. IS-004. Criteria: AC-002, AC-005. Subtasks: 3, 6, 7.
   Entry point: goal finalization opening the pull request.
   Preconditions: subtask 1's trace left IS-002 `unknown`; goal assessment exited `round_cap` with one confirmed Major still open.
   Steps: assessment ends, goal QA runs or is skipped, the runtime builds the PR body.
   Outcome: the PR body lists IS-002 under subtask 1 with its reason and lists the open Major with `path:line`, failure scenario, and verification evidence. The goal is not blocked.
5. IS-005. Criteria: AC-005. Subtasks: 5, 6.
   Entry point: goal finalization's `goal_assessment` stage.
   Preconditions: round 1's verifier returns output with no parseable verdict lines, twice.
   Steps: the runtime records the diagnostic, keeps round 1's assessor findings, and decides the fix set.
   Outcome: no unverified Blocker or Major enters the fix step, they are listed in the PR body as unverified, the round ends with exit reason `converged`, and the goal continues to goal QA.
6. IS-006. Criteria: AC-005. Subtasks: 5, 6, 7.
   Entry point: `skill-bill goal SKILL-442` resume after the process was killed.
   Preconditions: round 2's fix commit exists on the feature branch; round 3 has not started.
   Steps: the operator resumes the goal.
   Outcome: assessment resumes at round 3, scoped to the files round 2's fix changed, without rerunning rounds 1 and 2. `skill-bill goal status` and the desktop goal view show round 3.

## Transitional Regressions

None.

## Validation Strategy

The validate phase of each subtask runs the repository's full required checks, including the runtime-kotlin Gradle `check` with the repoTest architecture guards, and for subtask 7 the desktop-tray build and tests. Each subtask lists its own test obligations. No planning or implementation step runs these commands.

## Next Path

```bash
skill-bill goal SKILL-442
```
