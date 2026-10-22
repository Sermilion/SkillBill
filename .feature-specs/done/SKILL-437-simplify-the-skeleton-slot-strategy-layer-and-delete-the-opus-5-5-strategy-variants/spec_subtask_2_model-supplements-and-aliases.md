# Subtask 2 Replace model strategy variants with supplements and aliases

## Scope

Implement R1/R2/R3 as one indivisible change. Engine owns model directive resource loading, prompt composition and registry aliases; domain retains pure launch/profile models; core owns configuration injection. Remove all fifteen Opus55 strategy files enumerated in preplan and their registrations, bindings and subclass references. Remove OPUS_55_STRATEGY_ID_SUFFIX, ByProfile, withOpus, specialize and specializedStrategyIdFor. Fixed and fact-based selection, including ambiguous-fact rejection, remain. Remove the temporary forwarding test from subtask 1 after no forwarder remains.

## Acceptance Criteria

1. No slot strategy class is named Opus55, and profile-specific strategy selection constructs and registrations are absent; new plans select canonical strategies while retaining recorded launch assignments and profile-sensitive cache isolation.
2. A single ModelDirectiveSupplements owner applies the original resource to every previously supplemented accepted step only for its recorded opus-5-5 profile, preserving section placement, whitespace and all other prompt fields across ordinary and direct-review launches.
3. Core DI supplies the default-on SKILL_BILL_MODEL_DIRECTIVE_SUPPLEMENTS setting to durable and in-memory launches, and false disables every supplement without changing recorded identities, profile classification or model launch evidence.
4. Explicit slot-scoped historical aliases resolve canonical owners and runners through registry lookup, compatibility, selection matching and accepted dispatch, preserving recorded revision, policy, resume and digest validation without rewriting descriptors or workflow rows.

## Resource contract

Retain these thirteen resources unchanged under runtime-engine/src/main/resources/skillbill/engine/featuretask/slot:

- preplan/opus-5-5-agent-preplan.md
- plan/opus-5-5-agent-plan.md
- plan/opus-5-5-goal-plan-fan-out.md
- implementation/opus-5-5-implement-then-simplify.md
- audit/opus-5-5-acceptance-audit.md
- codereview/opus-5-5-inline.md
- codereview/opus-5-5-delegated.md
- standalonereview/opus-5-5-inline.md
- standalonereview/opus-5-5-delegated.md
- qualitygate/opus-5-5-pack-build.md
- qualitygate/opus-5-5-agent-validate.md
- writehistory/opus-5-5-boundary-history.md
- pullrequest/opus-5-5-pr-description.md

Preserve old mapping on every participating step, including simplify and repair steps where previously supplied. plan uses agent-plan; plan_subtasks uses goal-plan-fan-out. Monitor has no resource; commit stays agent-free. Retain the unreachable delegated full-run resource bytes even when its strategy is later deleted. Retaining a resource does not require making unreachable behavior selectable.

Normal composition consumes FeatureTaskRuntimePhasePromptComposeInputs.stepProfile from the recorded assignment. InlineReviewPass.review, standaloneDirective, runPreparedReview, direct launch and delegated standalone directiveSuffix must use the same supplement owner. Remove duplicate profile branches only after routing these launches through that owner. Apply a supplement once per launched directive. Preserve standalone report-only completion, pinned targets, review persistence, model override and changes_requested behavior.

## Compatibility contract

PhaseStrategyRegistry aliases are a separate explicit table, excluded from actual registration counts. strategy, contains and runner consult it. requireSupportedStrategies, matchesRecordedSelection and selectedOwnerOf use the same alias semantics. Do not accept arbitrary suffix-bearing strings. Evaluate policy semantic identity and resume interpretation identity under the recorded alias ID and unchanged semantic revision. A canonical-profile assignment receives no supplement even when the descriptor contains a historical suffixed ID.

Delete resolveUncached's specialization pass while retaining participating-step selection and copied-fact/assignment caching. Admission remains FeatureTaskRuntimeExecutionAdmission, including immutable identity checks and independently necessary phase-output migration. Keep execution-plan contract 0.2 because no field changes are planned. Canonical selection must not replace a recorded plan.

## Non-goals

Do not delete live standalone review, alter resource text, classify new model identities, persist the supplement setting, normalize archival bytes or loosen revision/policy/digest refusals.

## Dependency notes

Depends on subtask 1, including its preserved pre-change byte fixtures. Removal and alias support land together; no intermediate state may strand archived plans. General dead-registration cleanup follows in subtask 3. The parent records off-switch parsing and test-owner decisions. Inject the switch at DI rather than relying on the empty in-memory run environment.

## Test Obligations

- Bug caught, accepted execution loses or duplicates a model directive on a non-entry/direct launch. Extend PhaseModelProfileSelectionTest through accepted launches, using one mapping matrix for reachable resource/step combinations, mixed profiles, plan versus plan_subtasks, simplify, inline review, standalone report-only and delegated lane suffixes. Assert unchanged expected directive text, canonical absence and disabled absence. Preserve all thirteen resource bytes, including the resource with no reachable full-run selection. Do not manufacture a live binding to exercise that dead resource.
- Bug caught, the off switch works for durable runs but leaks into standalone in-memory review. Exercise both factories, including delegated directiveSuffix. Assert setting propagation and unchanged launch profile/identity evidence; invalid switch values use typed configuration refusal with diagnostics.
- Bug caught, registry alias lookup succeeds but admission or resume rejects historical policy/resume identities. Use subtask 1's frozen bytes for the three named aliases, decode/coherence in FeatureTaskRuntimeExecutionPlanCoherenceTest in infra, and admission plus accepted resume in engine coverage using ExecutionPlanAdmissionFixture and a launching resume harness. Assert unchanged stored descriptors and admission itself performs no launch or reselection. The fixture whose launcher fails if called proves admission only, never resume.
- Bug caught, alias support admits tampered policy/revision/digest values or arbitrary suffix IDs. Keep focused rejection coverage alongside the successful historical path, including canonical profile with a historical ID receiving no supplement and the same accepted owner/runner resolving through contains, strategy and runner.
- Retain subtask 1's monitor publication regression. Delete only its now-obsolete forwarding test.

## Next path

Subtask 3, consolidate live registrations and share the runner.

## Shared constraints

The parent spec's constraints, non-goals, decisions and phase ownership apply. Paths describe affected owners without preventing necessary review or validation repairs. Do not weaken behavior, assertions, architecture guards or durable identity checks. Keep existing unrelated workspace edits. No directive text, classifier identity or telemetry-column changes are allowed.

## Validation Strategy

Implement authors production and test changes; audit inspects repository facts. Neither executes builds, tests or generators. The later build phase owns compile/buildability proof. The later validate phase runs the required focused obligations below and the full project validation strategy, including repoTest guards. Preserve all existing relevant regression coverage and allow repairs required by those checks. Review, history, commit, publication and workflow advancement remain runtime-owned.

## Implementation Details

Execute these tasks in order within the single R1/R2/R3 removal bundle. In the paths below, E is runtime-kotlin/runtime-engine/src/main/kotlin/skillbill/engine/featuretask, ET is runtime-kotlin/runtime-engine/src/test/kotlin/skillbill/engine/featuretask, and C is runtime-kotlin/runtime-core/src/main/kotlin/skillbill/di/featuretask. These tasks use the upstream preplan digest as their repository evidence. Implement confirms assumptions against the checkout while preserving unrelated edits, including the existing change to E/runloop/state/FeatureTaskRuntimeRunLoopStepBindings.kt.

1. Establish the historical identity boundary before removing variants. Serves AC4 and protects AC1. Consume subtask 1's frozen contract-0.2 descriptor bytes for inline-opus-5-5, acceptance-audit-opus-5-5 and monitor-opus-5-5. Add an explicit slot-scoped alias table in E/slot/PhaseStrategyRegistry.kt, separate from registrations, and route contains, strategy and runner through it. Enumerate supported historical registered pairs from the removed registrations during implementation; never infer support from a string suffix. Keep canonical IDs on returned owners and leave recorded descriptors untouched. Provide one registry-owned identity interpretation for consumers to compare canonical selection with a supported recorded alias. Unknown slots, IDs and alias targets retain typed refusal. Extend existing registry coverage only for the observable alias and refusal boundaries in Test Obligations, rather than mirroring the table in independent tests.

2. Complete alias-aware durable and accepted execution. Serves AC4. Update E/slot/PhaseStrategyLookup.kt, especially selectedOwnerOf, matchesRecordedSelection, runnerFor and executionPlanMapping, plus E/lifecycle/execution/FeatureTaskRuntimeExecutionPlanCompatibility.kt and requireSupportedStrategies. Compare revision and policy/resume semantics using the recorded alias identity and the canonical implementation's unchanged behavior. Preserve the existing audit historical mapping. Adjust accepted identity checks in E/slot/attempt/PhaseAttemptRunHost.kt and E/runloop/state/FeatureTaskRuntimeRunLoopStepBindings.kt only as necessary to accept registry-proven historical aliases under the admitted plan. Keep acceptsAttemptStrategy until its removal in subtask 5; do not turn it into suffix-based permission. Admission, codec, digests and phase-output migration retain their current responsibilities. Use frozen bytes for infra decode/coherence coverage and engine admission/resume coverage as specified under Test Obligations. The infra test must not gain an engine production dependency. No durable field change is planned, so retain contract 0.2 and historical readers. If implementation discovers a required field change, apply schema-first evolution and historical-reader coverage before consuming it rather than silently changing 0.2.

3. Introduce the supplement owner and immutable injected setting. Serves AC2 and AC3. Add E/slot/ModelDirectiveSupplements.kt to select a resource by canonical strategy ID, accepted step ID and recorded PhaseModelProfile, with the injected enabled flag. Reuse E/slot/PhaseRunFacts.kt resource loading and append semantics where appropriate. Preserve resource bytes, task-directive placement, trailing-whitespace behavior and every other PhaseStepPromptSections field. Return no addition for canonical profiles, disabled settings or unmapped steps. Keep historical aliases independent from profile selection. Preserve the resource mappings in this sub-spec, including the plan versus plan_subtasks split and implementation simplify/repair coverage. Retain the unreachable delegated full-run resource without creating new selection paths.

   Resolve SKILL_BILL_MODEL_DIRECTIVE_SUPPLEMENTS through core-owned environment/configuration injection, starting with C/RuntimeFeatureTaskSlotProvides.kt and the existing composition wiring. Omission enables supplements and the literal false disables them. The digest does not establish the repository's boolean parser or typed configuration-error owner. Implement confirms those owners and reuses their conventions; the chosen default for any other unsupported value is typed refusal with a diagnostic, never silent fallback. Pass one immutable setting to both durable and in-memory launch composition. Do not read System.getenv in slot/composer code, consult only run.request.environment, persist the setting, or change classifier inputs. Cover both launch factories with the existing switch-propagation obligation.

4. Route every actual launch through the owner once. Serves AC2 and AC3. Integrate normal composition at E/slot/attempt/PhaseStrategySteps.kt and PhaseLaunchPreparation.kt with E/phase/prompt/compose/FeatureTaskRuntimePhasePromptComposer.kt and its inputs/source boundary. Keep composeLaunchPromptInputs.stepProfile sourced from run.launchAssignment, with its existing canonical default. Keep launchPromptSupplement hook ordering unchanged. Update InlineReviewPass.review in E/slot/codereview/InlineReviewStrategy.kt to supplement its actual InlineReviewDirective through the same owner. Update E/slot/standalonereview/StandaloneReviewStrategies.kt at standaloneDirective, runPreparedReview, direct launch and launchDelegated's directiveSuffix. Ensure prepared briefings and actual launched directives receive one addition each where previously required, without double-appending when paths converge. Preserve report-only completion, target pinning, persistence, model overrides and changes_requested outcomes. Extend ET/slot/PhaseModelProfileSelectionTest.kt through accepted launches, grouping mapping, mixed-profile, direct-review and disabled cases around the concrete lost/duplicated-directive bug. A historical alias with a canonical-profile assignment must still receive no supplement.

5. Delete strategy variants and profile-based selection together with the completed alias/supplement wiring. Serves AC1 through AC4. Remove these files under E/slot: preplan/AgentPreplanOpus55Strategy.kt, plan/AgentPlanOpus55Strategy.kt, plan/PlanFanOutOpus55Strategy.kt, implementation/ImplementThenSimplifyOpus55Strategy.kt, audit/opus/AcceptanceAuditOpus55Strategy.kt, codereview/opus/InlineReviewOpus55Strategy.kt, codereview/opus/DelegatedReviewOpus55Strategy.kt, standalonereview/InlineStandaloneReviewOpus55Strategy.kt, standalonereview/DelegatedStandaloneReviewOpus55Strategy.kt, qualitygate/packbuild/PackBuildOpus55Strategy.kt, qualitygate/agentvalidate/AgentValidateOpus55Strategy.kt, writehistory/BoundaryHistoryOpus55Strategy.kt, pullrequest/PrDescriptionOpus55Strategy.kt, monitor/MonitorOpus55Strategy.kt and monitor/standalone/StandaloneMonitorOpus55Strategy.kt.

   Remove their references from C/RuntimeFeatureTaskSlotProvides.kt, C/PullRequestPhaseStrategies.kt and InjectedPhaseStrategies. In E/slot/PhaseStrategySelection.kt and E/slot/skeleton/SkeletonStrategyBindings.kt remove ByProfile, withOpus, specialization and specializedStrategyIdFor while retaining Fixed, fact selection and ambiguous-fact rejection. Delete OPUS_55_STRATEGY_ID_SUFFIX from runtime-kotlin/runtime-domain/src/main/kotlin/skillbill/workflow/taskruntime/model/skeleton/PhaseLaunchModel.kt, retaining profile models and classifier behavior. Delete resolveUncached's specialization pass in PhaseStrategyLookup while preserving participating steps and copied assignment-sensitive cache keys. New plans contain canonical identities and original launch assignments. Update affected existing resolver/selection assertions and fixture references rather than adding structure-only tests. Remove duplicate direct-review profile branches and old subclass constants only after task 4 supplies their launched directives. Remove only the temporary forwarding proof from ET/slot/PhaseStrategyCompositionTest.kt; retain the real monitor publication regression. General dead-registration deletion, runner consolidation, typed families and capability splitting remain in their later subtasks. The runtime must land deletion, supplements and aliases in one commit, never publish an intermediate deletion.

6. Prepare boundary evidence for later verification. Serves AC1 through AC4. Finish the Test Obligations in their existing owners: ET/slot/PhaseModelProfileSelectionTest.kt and PhaseStrategyRegistryTest.kt; ET/lifecycle/execution admission/resume coverage using ExecutionPlanAdmissionFixture; runtime-kotlin/runtime-infra/contracts/src/test/kotlin/skillbill/infrastructure/contracts/workflow/featuretask/FeatureTaskRuntimeExecutionPlanCoherenceTest.kt; and the retained ET/phaserun/PhaseMonitorRunTest.kt regression. Reuse subtask 1's archival bytes without rebuilding their identities or digests from the new registry. Assert descriptor preservation and zero admission launches separately from successful accepted resume. Preserve rejection of revision, policy, resume and digest tampering, unknown suffix IDs, ambiguous facts and profile-cache leakage. Keep existing repoTest architecture guards and baselines unchanged. The later build phase supplies buildability evidence; validate runs the focused tests and required full repository validation. This planning phase and implement execute no tests or builds.

Implementation must preserve the digest's A1/A2 ownership boundaries, A3 composition ownership, A5 deletion requirement, A6 wire-key ownership, A7 typed durable refusals and diagnostics, and A8 injected environment rule. Add no Kotlin line/block comments or unauthorized KDoc. Do not change directive text, classifier identities, telemetry columns, immutable admission semantics or supported standalone behavior. The precise existing configuration wiring and alias-table enumeration are implementation confirmations, not reasons to repeat discovery during planning. Documentation and the final decision record remain with subtask 6, which must document the chosen switch semantics. No additional tests are planned beyond the named behavioral obligations and retained governed coverage.
