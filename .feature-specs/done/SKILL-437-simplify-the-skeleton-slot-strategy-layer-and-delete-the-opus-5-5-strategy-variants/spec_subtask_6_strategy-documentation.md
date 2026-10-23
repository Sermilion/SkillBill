# Subtask 6 Document model supplements and the simplified strategy architecture

## Scope

Implement R8 in docs/model-specific-phase-strategies.md, the Phase slots and strategies section of runtime-kotlin/ARCHITECTURE.md and runtime-kotlin/agent/decisions.md. Update docs/runtime-command-guidance.md only where it names removed types. Keep the R5 decision from subtask 3 and add the supersession of SKILL-403 without inventing existing heading identifiers.

## Acceptance Criteria

1. docs/model-specific-phase-strategies.md teaches adding a model through one classifier case and directive resources registered with the supplement owner, documents the default-on switch and immutable historical aliases, and names no wrapper class.
2. runtime-kotlin/ARCHITECTURE.md describes canonical strategy selection, recorded-profile supplements, declared typed binding families, optional capability interfaces and unchanged immutable execution/admission ownership in its Phase slots and strategies section and any directly affected authority description.
3. runtime-kotlin/agent/decisions.md records the SKILL-403 supersession and retained decision against delegated full-run review, distinguishing live delegated standalone review; runtime-command-guidance.md contains no stale references to types removed by this feature.

## Non-goals

Do not rewrite unrelated runtime command guidance or change public commands, skill sources, directive content or tracker data. Do not add production comments as a substitute for the decision record.

## Dependency notes

Depends on subtask 5 and describes the final production shape from subtasks 1 through 5. Read actual documentation during authorized implementation to match existing structure and vocabulary. The preplan supplied no boundary heading catalog, so this spec does not assert existing headings in history or decisions. The decisions file is an authored production artifact; later runtime-authored history or publication artifacts are not acceptance criteria.

## Test Obligations

No new test is justified for reversible prose edits. The realistic risk is stale type/recipe documentation, checked by reading the affected documents against the landed production interfaces. Retain any existing governed documentation or contract parity checks in later validate.

## Next path

Return control to the parent runtime for its remaining owned phases. The prepared goal reference is `skill-bill goal SKILL-437`; this subtask does not launch it.

## Shared constraints

The parent spec's constraints, non-goals, decisions and phase ownership apply. Paths describe affected owners without preventing necessary review or validation repairs. Do not weaken behavior, assertions, architecture guards or durable identity checks. Keep existing unrelated workspace edits. No directive text, classifier identity or telemetry-column changes are allowed.

## Validation Strategy

Implement authors production and test changes; audit inspects repository facts. Neither executes builds, tests or generators. The later build phase owns compile/buildability proof. The later validate phase runs the required focused obligations below and the full project validation strategy, including repoTest guards. Preserve all existing relevant regression coverage and allow repairs required by those checks. Review, history, commit, publication and workflow advancement remain runtime-owned.

## Implementation Details

Complete these tasks in order after subtask 5. This plan uses only the upstream preplan digest. Implementation must confirm final symbol names and configuration spelling against the landed work from subtasks 1 through 5 when reading the affected documentation. No further discovery or verification occurs in this planning phase.

1. Rewrite the model extension recipe in `../../../docs/model-specific-phase-strategies.md`. This serves acceptance criterion 1. Replace wrapper composition, subclassing, variant registration, `withOpus`, specialized strategy identities and extra plan-field instructions with two steps: add one `PhaseModelProfileClassifier` case, then add directive resources and register their canonical strategy and accepted-step mapping with the single supplement owner. Use `ModelDirectiveSupplements` as the planned owner name, subject to confirmation against subtask 2. Explain that composition uses the recorded `FeatureTaskRuntimePhasePromptComposeInputs.stepProfile`, preserves directive placement and text, and leaves canonical-profile and unmapped steps unsupplemented. Include the `plan` versus `plan_subtasks` mapping and direct inline and delegated standalone review launch paths so the recipe covers actual launches. Retain all thirteen existing resources, including the unreachable delegated full-run resource, without editing their bytes or changing classifier identities. Document `SKILL_BILL_MODEL_DIRECTIVE_SUPPLEMENTS` as enabled when omitted and disabled by `false`. This is the chosen spelling and false value from the digest's recommendation; implementation must confirm that subtask 2 implements them and document the landed setting if its spelling differs. Explain that configuration enters through injected immutable settings for durable and in-memory launches, without ambient environment reads in slot or composer code and without persisting the switch.

2. Update the Phase slots and strategies section and directly affected authority descriptions in `../../../runtime-kotlin/ARCHITECTURE.md`. This serves acceptance criterion 2. Describe canonical selection through `SkeletonStrategyBindings`, model supplements independent of identity, `PhaseStrategy<B>`, strategy-declared per-step `PhaseBindingFamily<B>` and runtime-owned binding construction and generic dispatch. Explain separate review, finding-verification and repair authority, typed hooks with unchanged timing, and the quality-gate family payload. Describe the twelve-member core and optional loop, resume, verdict, status, repair-role, fan-out, prepared-run, recorded-plan-mapping and hook capabilities using their landed interface names. Identity computations remain final semantic helpers rather than extra open core members. Preserve the documented ownership of immutable plans, recorded launch assignments, codec, digests, execution admission and accepted-step authority. Explain explicit registry aliases for historical strategy, policy and resume identities, with revision and tampering checks retained, no current-configuration reselection and no workflow-row rewrite. Contract 0.2 remains unchanged because this design adds no durable field. Do not introduce a new migration recipe or claim that alias identity alone enables a supplement.

3. Update `../../../runtime-kotlin/agent/decisions.md` and narrowly repair `docs/runtime-command-guidance.md`. This serves acceptance criterion 3. Preserve the subtask 3 decision that delegated full-run review is not planned, distinguish supported delegated standalone review, and add the supersession of SKILL-403's model-specific strategy shape. Record why canonical strategies plus prompt supplements replace forwarding wrappers and why historical aliases preserve recorded execution. Keep the also-unreachable `AgentPlanStrategy` registration removed while retaining its used planning helper class. Resolve the intake's registration-count conflict in favor of the digest's seventeen reachable canonical registrations, preserving live standalone and QA definitions; do not restate twelve as the registration total. Explain that twelve is the core-member limit. Confirm the landed reachability-derived count during implementation before naming it in documentation. Use existing document structure and vocabulary, invent no heading identifiers, and avoid duplicating or overwriting the subtask 3 decision. In runtime command guidance, replace only references to removed types with the landed ownership descriptions; leave public commands and unrelated guidance unchanged.

4. Check the edited prose against the landed interfaces and inherited test obligations. This serves acceptance criteria 1 through 3. During authorized implementation, read the affected documents to confirm that the recipe names no wrapper class, removed-type references no longer teach the old design, the switch covers direct review and in-memory launches, and the decisions preserve delegated standalone behavior and immutable aliases. This is a documentation consistency review, not a new test or a substitute for later validation. `test_obligations` is empty for this subtask because a prose-only regression test would mirror wording rather than protect runtime behavior. The later validate phase retains existing governed documentation and contract parity checks and the parent feature's repoTest architecture guards without adding baselines or exemptions. Implementation and audit run no builds, tests, generators or full suites for this task.

Keep edits limited to the documentation owners in scope during implementation, subject to the shared allowance for necessary review and validation repairs. Do not change production interfaces to make the documentation easier to write. Preserve directive text, classifier inputs, telemetry columns, existing unrelated edits and all stated non-goals. The earlier monitor regression, archived fixture capture, alias admission coverage, supplement launch coverage, registration reachability and typed-authority tests remain obligations of their preceding subtasks. This subtask neither duplicates them nor claims they have run. History, commits, publication and workflow advancement remain with their owning runtime phases.
