# SKILL-442 subtask 4 - validate test probes

## Scope

Parent criterion AC-004, except its telemetry counts (subtask 8). The validator in a goal-subtask validate step probes the tests the subtask added or changed, strengthens tests that cannot fail, and reports a governed `test_probes` list.

Shared decisions restated: probe results travel as parsed prose lines in the existing `feature_task_phase_complete` value. The list gets a new contract at `0.1`; the phase-output envelope stays at `0.7`, which the parent's Planning Decisions record as the contract version event for AC-004. A malformed probe output gets one re-ask, then a `RuntimeDiagnosticsBestEffortWarning`, and every probe records `not_run`. No new budget or block reason; strengthening happens inside validate's existing repair session.

Probe line grammar: `Test probe <test_ref>: <killed|survived|not_run> guards <guarded_ref> repaired <yes|no> reason <text>`. `<test_ref>` names the test by file and test name. `<guarded_ref>` is the `path:line` or branch the test guards. `repaired yes` means the first probe survived, the validator strengthened the test, and the single re-probe killed it.

## Acceptance Criteria

1. The validate prompt carries the probe instructions, in a few sentences.
   `runtime-kotlin/runtime-engine/src/main/resources/skillbill/engine/featuretask/slot/qualitygate/opus-5-5-agent-validate.md` and the matching directive in `QualityGatePromptDirectives` (`runtimeOwnedValidateAgentPhaseTask` and `VALIDATE_VALUE_CONTENT`) say: after every required check passes, probe at most six tests the subtask added or changed. Priority goes first to tests covering a listed broken-then-repaired scenario, then one test per listed required criterion, then the remaining changed tests in diff order.
   For each test, name the production line or branch it guards. Copy the current checkout, uncommitted changes included, to a temporary directory outside the workflow checkout. Apply there the smallest change that removes that behavior, run only that test, then delete the copy. Never mutate the workflow checkout. Record `killed`, `survived`, or `not_run` with a reason.
   A `survived` test is strengthened inside the existing repair session, for example by asserting the specific outcome instead of only success, driving the named entry point instead of an internal helper, or keeping setup alive through the assertion, then re-probed once. Never change production code to make a probe pass, and never weaken an assertion. Every listed repaired scenario needs one probed test that drives its entry point; write one when none exists. When no copy can be made or no targeted test command exists, record every probe `not_run` with the reason and finish on the normal checks. End with one Scope-grammar line per probe.
   No text names a language, framework, or test runner. Audit confirms it by reading both prompt surfaces.
2. Probes run only in goal-subtask validate, and the prompt gets their inputs.
   `FeatureTaskRuntimePhasePromptComposeInputs` gains an optional probe scope set only when the run is a goal child (`SkeletonDefinition.GOAL_CHILD`), not keyed on the phase id. `AgentValidateStrategy.directiveFor` and `promptSections` include the criterion 1 instructions only when that scope is set. Standalone `phase:validation`, the post-QA `SkeletonDefinition.VALIDATION` run in `DefaultGoalRunnerQaGate.settleAfterQa`, and gate triage (`validateGateTriagePhaseTask`) never include them.
   The scope lists the broken-then-repaired scenarios from the subtask's repair state (`interaction_trace.repaired_scenario_refs` with each scenario's entry point and outcome from the catalog) and the criteria whose `validation_obligation` is `required`, by canonical ref and text. Empty lists are rendered as "none". Audit confirms it by reading the compose input, its population in launch preparation, and the strategy's conditional.
3. A governed `test_probes` list is parsed and stored with the validate output.
   A Draft 2020-12 contract `orchestration/contracts/feature-task-runtime-validation-test-probes-schema.yaml` requires `contract_version` (const `"0.1"`) and `test_probes`, an array of at most six `{test_ref, guarded_ref, result, reason, repaired}` items. `test_ref` and `guarded_ref` are non-blank, `result` is `killed`, `survived`, or `not_run`, `reason` is a string that must be non-blank when `result` is `not_run`, and `repaired` is a boolean that may be true only when `result` is `killed`.
   `runtime-contracts` gains `FEATURE_TASK_RUNTIME_VALIDATION_TEST_PROBES_CONTRACT_VERSION = "0.1"` and a payload-keys object for these keys. `FeatureTaskRuntimeFailureCode` gains `INVALID_VALIDATION_TEST_PROBES`, registered in its classification list and `BlockIncidentClassificationRule`. The envelope schema's `phaseProseProducedOutputs` description gains a description-only note naming `test_probes` and the new contract.
   A runtime-domain parser reads the Scope line grammar into that model and a codec encodes it to the contract map, loud-failing with `INVALID_VALIDATION_TEST_PROBES` on a schema violation. It reports a malformed result for a line that does not match the grammar, a duplicate `test_ref`, more than six lines, or `repaired yes` on a non-`killed` result. This criterion does not store anything (criterion 4). Audit confirms it by reading the schema, constant, keys, failure code, parser, and codec.
4. The validate settlement stores the probe list and degrades instead of blocking.
   On a goal-child validate settlement whose prompt had a probe scope, the agent-validate settlement path (`slot/qualitygate/agentvalidate/`, through `AgentValidateStepHooks` or the strategy's completion check) runs the criterion 3 parser on the prose value and stores the encoded list under `produced_outputs.test_probes` in the validate phase output. Prose with no probe lines while a probe scope was set counts as malformed.
   A malformed first output is re-asked once through validate's existing completion rejection, naming `INVALID_VALIDATION_TEST_PROBES`. A second malformed output records a `RuntimeDiagnosticsBestEffortWarning` and stores an empty `test_probes` list, and validation settles on its normal checks. Settlements without a probe scope store no `test_probes` key.
   A stored list never changes the validate verdict: `survived` results are reported, not blocking. Audit confirms it by reading the settlement seam, the re-ask, and the fallback warning.

## Criterion Sizing

1. About 30 production lines, 2 files, 1 area (`runtime-engine` slot/qualitygate). Fits.
2. About 110 production lines, 5 files, 2 areas (`runtime-engine` slot/qualitygate and prompt/compose, `runtime-engine` slot/attempt launch preparation). Fits.
3. About 190 production lines, 7 files, 3 areas (`orchestration/contracts`, `runtime-contracts`, `runtime-domain`). Fits.
4. About 70 production lines, 2 files, 1 area (`runtime-engine` slot/qualitygate/agentvalidate). Fits.

## Interaction Scenarios

1. IS-001. Criteria: AC-001, AC-002, AC-004.
   Entry point: the goal-subtask `validate` step.
   Preconditions: the repair state lists IS-001 as repaired; the subtask changed four tests; none drives IS-001's entry point.
   Steps: the required checks pass; the validator probes.
   Outcome: the validator writes a test driving IS-001's entry point, probes it first, and the stored `test_probes` has that test first.
2. IS-002. Criteria: AC-001, AC-004.
   Entry point: the same step.
   Preconditions: a changed test still passes with its guarded line removed.
   Steps: the validator probes it, strengthens it, and re-probes.
   Outcome: the stored item has `result: killed` and `repaired: true`. If the re-probe still passes, the item has `result: survived` and `repaired: false`. Production code is unchanged by the probe work.
3. IS-003. Criteria: AC-002, AC-004.
   Entry point: `skill-bill phase validation` (standalone), and the post-QA validation run.
   Preconditions: none.
   Steps: validation runs.
   Outcome: the prompt contains no probe instructions and the output has no `test_probes`.
4. IS-004. Criteria: AC-001, AC-004.
   Entry point: the goal-subtask `validate` step.
   Preconditions: the repository has no command that runs a single test.
   Steps: required checks pass; the validator reports.
   Outcome: every probe is `not_run` with a reason, a warning is recorded, and validation completes on its normal checks.
5. IS-005. Criteria: AC-003, AC-004.
   Entry point: the goal-subtask `validate` settlement.
   Preconditions: the validator's prose ends without probe lines, twice.
   Steps: the runtime re-asks once, then settles.
   Outcome: one warning naming `INVALID_VALIDATION_TEST_PROBES`, an empty stored `test_probes`, and validate completes on its normal checks.

## Non-Goals

- Telemetry counts and `goal-stats` (subtask 8).
- A mutation-testing tool dependency.
- Scaling the cap of six (parent open question, left open).

## Dependency Notes

Depends on subtask 2 for `repaired_scenario_refs` and the catalog on the repair binding.

## Test Obligations

- Contract parity test for the test-probes schema against its constant (governed parity).
- Regression test from the parent constraints: a probe line reporting a test that passes with its guarded line removed is stored as `result: survived`, `repaired: false`. Bug caught: the parser maps any reported probe to `killed`, hiding tests that cannot fail.
- Gating test: the standalone validation prompt has no probe instructions while a goal-child validate prompt does. Bug caught: probes leak into standalone or post-QA validation and mutate repositories outside a goal.

## Validation Strategy

The validate phase runs the runtime-kotlin Gradle `check` with repoTest guards. Nothing runs here.

## Implementation Details

The AC references below are this sub-spec's four criteria. The supplied preplan digest is the starting evidence. Subtask 2 supplies the repair binding's scenario catalog and `interaction_trace.repaired_scenario_refs`; this subtask consumes that declared dependency without changing its contract. All behavior is local to the runtime and agent instructions. No external mutation-testing capability is required or assumed.

### Evidence and implementation boundaries

Targeted source inspection resolved the settlement and gating questions left by preplan:

- `runtime-kotlin/runtime-engine/src/main/kotlin/skillbill/engine/featuretask/slot/qualitygate/agentvalidate/AgentValidateStrategy.kt` currently returns an unconditional base directive from `directiveFor`, while `promptSections` receives compose inputs and selects the triage directive. `directiveFor` has no run inputs. Keep its reusable base free of probe instructions and add the conditional instructions through `promptSections`; do not introduce ambient run state or widen the strategy API just to inspect goal identity.
- `runtime-engine/.../slot/attempt/PhaseLaunchPreparation.kt`, in `promptInputs` around lines 435-474, constructs the compose inputs and already carries validation triage and repair facts. Populate the optional probe scope there from the admitted skeleton and current durable repair state. Use the same resolved scope at settlement, rather than inferring eligibility from the phase name or reparsing prompt text.
- `runtime-engine/.../slot/PhaseStepHooks.kt` exposes `completionRejection`, `acceptedOutput`, and `interpretedOutput`. `runtime-engine/.../slot/attempt/PhaseOutputGate.kt:560-670` calls rejection before output interpretation and accepted-output persistence. `AgentValidateStepHooks` currently only enables immediate-consumer projection checks. These are existing local extension points for the single correction and normalized probe list.
- `runtime-engine/.../runloop/qualitygate/AgentValidateGateCycle.kt` carries the completed agent output into `RuntimeOwnedGateSettlement`; `FeatureTaskRuntimeValidationGateCoordinator.execute` launches one agent session and returns its output. Preserve the augmented payload across that second settlement. The prompt, rather than a new runtime mutation runner, owns running normal checks before probes and rerunning affected normal checks after strengthening a test.
- `runtime-kotlin/runtime-ports/src/main/kotlin/skillbill/ports/taskruntime/FeatureTaskRuntimeWireArtifactValidator.kt` provides typed artifact-kind validation. Register the new artifact through this existing port. Keep schema loading and JSON Schema enforcement in `runtime-infra/contracts`, never in the domain parser.

Resolve the Scope shorthand about malformed reports using AC-004's explicit settlement rule. A valid unavailable-capability report retains individual `not_run` items and reasons. Twice-malformed prose stores an empty list and a diagnostic because the runtime cannot invent test identities or guarded behavior from unusable output. Do not synthesize a successful probe. A scoped report with no probe lines follows the same one-correction fallback, including a subtask with no eligible changed tests. The six-test cap remains absolute; prioritize unique repaired-scenario entry-point coverage before the other selection tiers.

### Ordered tasks

1. Define and register the governed probe artifact. Serves AC-003 and supplies the prerequisite for AC-004.

   Author `orchestration/contracts/feature-task-runtime-validation-test-probes-schema.yaml` first, with Draft 2020-12, version `0.1`, the exact item fields and result constraints in AC-003, uniqueness enforcement for test refs at the parse/coherence seam, and the six-item ceiling. Then add the version constant and owning probe payload keys in `runtime-contracts`, the parity test in `runtime-infra/contracts`, and `INVALID_VALIDATION_TEST_PROBES` in `FeatureTaskRuntimeFailureCode` and its classification registrations, including `BlockIncidentClassificationRule`. Extend `FeatureTaskRuntimeWireArtifactKind` and the infrastructure validator's dispatch/resource registration so the engine validates actual encoded artifacts against the schema. Add only the requested description note to `phaseProseProducedOutputs` in `feature-task-runtime-phase-output-schema.yaml`; retain envelope version `0.7`.

   Keep the domain result typed and pure. Put the prose parser and probe result enum beside the validation models in `runtime-domain`; encode through the established artifact representation at the artifact seam. The codec packages `contract_version` and `test_probes` for validation, while settlement stores the array under `produced_outputs.test_probes`. Shared version and keys have production consumers in domain encoding, engine settlement, and infrastructure validation. Use `wireValue` for enum tokens and existing shared keys for envelope fields.

   Test obligations: governed schema/constant parity, plus schema acceptance and rejection cases protecting the cap, required `not_run` reason, and `repaired` implication. These catch accepting an invalid wire artifact that downstream readers would otherwise trust. Keep the schema resource in the packaged contract path through existing build resource registration.

2. Parse the agreed line grammar without converting missing evidence into success. Serves AC-003 and prepares AC-004.

   Implement a pure typed parse result for `Test probe <test_ref>: <result> guards <guarded_ref> repaired <yes|no> reason <text>`. Preserve report order and non-blank test and guarded references. Allow ordinary surrounding prose; treat every line beginning with the probe prefix as a candidate that must parse in full. Reject malformed candidate lines, duplicate test refs, more than six items, and `repaired yes` with any result except `killed`. Treat no lines as malformed when called with an active scope. Allow an empty reason for `killed` or `survived`, as the schema permits. Return expected grammar failures as data; convert an invalid governed artifact to the named typed failure at the validation boundary. Do not catch invariant exceptions or cancellation to manufacture a result.

   Test obligations: the mandated regression reports `survived` with `repaired no` and observes the stored result and boolean unchanged. It catches a parser or codec that rewrites a surviving mutation to a kill. Add one focused rejection table through the real parser for distinct grammar/coherence branches, without separate literal-variant tests or assertions about parser internals.

3. Populate goal-child scope and compose the conditional instructions. Serves AC-001 and AC-002; depends on task 1's typed vocabulary and subtask 2's durable inputs.

   Add the optional typed probe scope to `FeatureTaskRuntimePhasePromptComposeInputs` and construct it in `PhaseLaunchPreparation` only for admitted `SkeletonDefinition.GOAL_CHILD` validation, excluding triage. Resolve repaired scenario refs against the binding catalog to include entry point and outcome, and select required criteria by canonical ref and text. Read current repair state at launch so completed audit repairs reach validate. Preserve canonical order and render empty input lists as `none`. A dangling repaired-scenario reference is a broken prerequisite binding, not permission to invent an entry point; use the existing typed binding failure path.

   Update `qualitygate/opus-5-5-agent-validate.md` and `QualityGatePromptDirectives.runtimeOwnedValidateAgentPhaseTask` / `VALIDATE_VALUE_CONTENT` with short, stack-neutral conditional probe text. Keep the common directive used by `directiveFor` probe-free. `AgentValidateStrategy.promptSections` adds the goal-child instructions, scope, and output grammar only when the optional scope is present and triage is false. Standalone and post-QA `VALIDATION` skeletons remain outside that scope.

   Cover selection order, six-test cap, isolation outside the checkout including dirty/untracked source needed by the tests, smallest behavioral mutation, targeted execution, restoration between probes, and cleanup on every exit. Strengthen only tests in the workflow checkout, never production code, then copy the strengthened state for the single re-probe. Rerun normal checks affected by test repairs before reporting completion. With no isolated copy or targeted command, report each selected probe as `not_run` and emit the bounded warning through settlement. These are instructions for the future validate agent; this subtask adds no mutation-testing dependency or process orchestration.

   Test obligations: extend the existing engine prompt-composer/strategy test boundary with goal-child, standalone, post-QA, and triage cases. Assert presence or absence of the probe scope/instructions and correct repaired-scenario input, not exact prose. The realistic bugs are leakage into standalone validation and losing repaired-scenario priority by omitting its entry point. No separate test is needed for trivial directive forwarding.

4. Settle probes with one correction and observable degradation. Serves AC-004 and integrates AC-001 to AC-003.

   Extend `AgentValidateStepHooks` using a small validation-owned settlement helper. Before accepting a scoped validate output, parse its prose and validate the encoded artifact through the registered validator. On the first malformed report return the existing completion rejection naming `INVALID_VALIDATION_TEST_PROBES`. Track whether that specific correction was already requested with workflow/phase/attempt identity in the existing attempt correction owner; expose only the detached fact needed by the hook. Reconstruct it from retained correction evidence on resume. Do not keep mutable counters on the singleton hook, use unrelated gate failures as the counter, add a new retry budget, or reset the existing session.

   On the second malformed report emit `RuntimeDiagnosticsBestEffortWarning` with the seam, expected valid artifact, empty-list fallback, and bounded cause, then attach an empty list. For valid reports retain all items and order, including `survived`; emit an unavailable-probe warning for `not_run` reports. Use output interpretation before persistence to merge the array into produced outputs while retaining the prose, verdict, summary, and existing validation evidence. Without a scope, add no probe key. Ensure `AgentValidateGateCycle` and final runtime-owned settlement preserve this normalized list rather than reconstructing an envelope that drops it. Probe outcomes alone never alter the verdict or suppress a normal-check failure.

   Test obligations: an engine settlement regression submits two malformed scoped completions and observes exactly one correction, the warning, an empty durable list, and unchanged normal validation outcome. Include resume between the correction and second report to catch accidental retry-budget reset. Extend that boundary coverage to observe a valid `survived` item surviving final gate settlement and an unscoped completion omitting the key. These protect durable behavior rather than hook invocation order.

5. Complete the evidence review and hand off verification. Serves AC-001 through AC-004 and IS-001 through IS-005.

   Inspect the finished production paths against the trace above. IS-001 depends on task 3's scenario entry-point input and selection instructions plus task 4's ordered storage. IS-002 depends on task 2 preserving survived/repaired semantics and task 3's single re-probe. IS-003 depends on task 3's skeleton gating and task 4's omitted key. IS-004 depends on isolated-copy/targeted-command fallback instructions and task 4's warning and retained reasons. IS-005 depends on task 4's correction accounting and empty-list fallback.

   Implementation authors the focused tests above. The later validate phase runs them and the assigned Validation Strategy's full runtime-kotlin checks with repoTest guards; this planning phase runs no tests, build, or checks. Contract parity and existing architecture regressions remain mandatory. Review applies the architecture-guidelines section 5 checklist, especially A1/A2 ownership, A6 shared vocabulary, A7 typed degradation and cancellation, A10 test placement, A11 visibility, and A12 typed inner APIs. Preserve file-size/package limits and all guard baselines under G7. Authored Kotlin has no forbidden comments or inline fully qualified references. Keep telemetry and goal-stats with subtask 8. No commit, history, publication, or other phase work is part of implementation here.

## Next Path

```bash
skill-bill goal SKILL-442
```
