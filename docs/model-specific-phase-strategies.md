# Authoring model-specific phase strategies

Model-specific strategies adapt a slot's agent instructions to a resolved model.
They keep the slot's execution contract, including its phase ids, output validation,
authority, retries, and checkpoints.

Opus 5.5 automatic selection is installed. There is no operator strategy toggle.
Existing model configuration is the only input. This guide records the landed
registration and configuration path and the same steps a future model must follow.

## Start with the slot contract

Read [runtime command guidance](runtime-command-guidance.md), the
[slot architecture](../runtime-kotlin/ARCHITECTURE.md#phase-slots-and-strategies),
and [architecture guidelines](architecture-guidelines.md) before changing a strategy.
Inspect the current declarations rather than copying an old model's implementation.

`PhaseSlot` owns step membership. `SkeletonDefinition` owns the selected slots and
their traversal. `PhaseStrategy` declares strategy identity, revision, steps,
policies, directives, and execution behavior. `PhaseStrategyRegistration` pairs a
strategy with its runner. The accepted attempt host resolves that runner after
required persistence. Strategies must use their bound role operations.

List the existing variants that the model must support. A planning strategy must
preserve goal-planning fan-out. A gate strategy must preserve build versus
validation selection and pack commands. A review strategy must preserve the
difference between full-run repair and report-only standalone review.

`commit_push` remains a runtime operation. `RuntimeCommitStrategy` stays
canonical-only. Do not create an agent session or a forwarding strategy just to
give this slot a model-specific name.

## Resolve model identity before selection

`AgentRunLauncher.resolveLaunchModel` is the prelaunch operation. Adapter
`resolveAgentRunLaunchModel` owns remapping and pinned-alias resolution.
`PhaseModelProfileClassifier.classify` is the only exact-identity owner. The
launcher consumes the same `EffectiveLaunchModel` that selection uses.

Exact Opus 5.5 identities:

- `anthropic_api`, `google`, and `claude_platform_aws` accept `claude-opus-5-5`
- `bedrock` accepts `anthropic.claude-opus-5-5`

Older, unknown, unrelated, unversioned, arbitrary deployment, and substring
forms stay `canonical`. Alias `opus` qualifies only for inherited process
environments when `ANTHROPIC_DEFAULT_OPUS_MODEL` is one of those exact ids
(`alias_pinned_by_environment`). Governed-child launches resolve that alias as
unknown (`environment_not_inherited`). Flag-free launches stay unknown
(`flag_free_default`) even if `ANTHROPIC_MODEL` is set. A requested Opus remapped
to `deepseek-v4-flash` stays canonical.

A participating Opus step selects a capable slot variant. Only that step's
directives specialize. Unselected gate members and absent optional steps cannot
select a profile. Explicit child overrides win; unsupported inheritance stays
unknown.

## Add behavior that earns a strategy

Twelve Opus variants compose a canonical instance.
`InlineStandaloneReviewOpus55Strategy` and `DelegatedStandaloneReviewOpus55Strategy`
extend the canonical classes and override `promptSections`. Every variant uses id
`<canonical-id>-opus-5-5` and appends a directive resource only when the step
profile is `opus-5-5`. Resources live under
`runtime-kotlin/runtime-engine/src/main/resources/skillbill/engine/featuretask/slot/<slot>/opus-5-5-<family>.md`.

Register variants in
[`RuntimeFeatureTaskSlotProvides.kt`](../runtime-kotlin/runtime-core/src/main/kotlin/skillbill/di/featuretask/RuntimeFeatureTaskSlotProvides.kt)
and bind them through
[`SkeletonStrategyBindings.kt`](../runtime-kotlin/runtime-engine/src/main/kotlin/skillbill/engine/featuretask/slot/skeleton/SkeletonStrategyBindings.kt).
Full-run `CODE_REVIEW` stays inline for every current mode. `REVIEW` standalone
selects delegated only for explicit delegated mode.

Implementation and simplify prompts name a runtime-private checklist at
`.skill-bill/feature-task-tracking/<workflow-path-segment>/checklist.md`.
Implement and simplify launch preparation calls
`FileSystemFeatureTaskImplementationChecklistStore.prepare`. A missing file is
seeded. Existing ticks for remaining keys are kept. A corrupt or unwritable file
degrades and does not mark workflow tasks complete.

## Preserve execution identity

Execution-plan contract `0.2` records `step_launch_assignments` and selected
strategy identities. Version `0.1` reads as canonical with no assignments.
Resume launches the recorded assignment. Current config that would resolve
differently does not reselect an accepted attempt. Incompatible or unregistered
recorded identities refuse before workflow, parent/child, or lease mutation.

Launch evidence keeps requested identity, effective prelaunch identity, and
bounded provider-reported identity separate. Claude `modelUsage` object keys
supply reported evidence. Codex, Cursor, and Junie report `unsupported_agent`. Malformed
fields degrade evidence and still admit ordinary output.

## Opus 5.5 registration example

1. Confirm the wire identity against Claude model documentation. Add one
   classifier case in `PhaseModelProfileClassifier`.
2. Add a variant class next to the canonical strategy, for example
   `AcceptanceAuditOpus55Strategy`, composing the canonical owner and appending
   `opus-5-5-acceptance-audit.md` through `appendWhenOpus`.
3. Register the variant and bind it with `PhaseStrategyBinding.withOpus`.
4. Extend `PhaseStrategySelectionFacts.stepAssignments`. Plan cache identity
   copies those assignments.
5. Cover exact accept/reject, remapping, mixed audit/repair, cache isolation,
   historical `0.1`, and decoder reported identity.

Production owners: `PhaseModelProfileClassifier`, `AgentRunLauncher`,
`resolveAgentRunLaunchModel`, `SkeletonStrategyBindings`,
`FeatureTaskRuntimeExecutionPlanResolver`,
`FeatureTaskRuntimeStepLaunchAssignmentFactory`.

Test owners: `PhaseModelProfileSelectionTest`, `AgentRunCommandBuildersTest`,
`PhaseStrategyCompositionTest`, `RuntimeFeatureTaskSlotProvidesTest`,
`FeatureTaskRuntimeExecutionPlanCoherenceTest`,
`FileSystemFeatureTaskImplementationChecklistStoreTest`,
`FeatureTaskRuntimeCheckpointScopeTest`.

## Future-model example

A later model repeats the same seams without a strategy flag:

1. Detection: one classifier case for documented exact identities. Aliases stay
   unknown unless an inherited environment pins them to that exact id.
2. Slot variants: compose each agent-capable canonical strategy except
   `InlineStandaloneReviewOpus55Strategy` and `DelegatedStandaloneReviewOpus55Strategy`,
   which subclass the canonical classes and override `promptSections`; keep
   `RuntimeCommitStrategy` canonical-only.
3. Mixed-model: specialize a slot when any participating step qualifies; append
   directives only for qualifying steps.
4. Authority: reuse existing validators, pack commands, standalone report-only
   rules, and build's single-session policy.
5. Immutable admission: add bounded fields to the next execution-plan contract
   version and keep a historical reader for the previous version.
6. Diagnostics: unknown resolution, resume drift, malformed reported identity,
   and checklist failure use `seam=… value_expected=… value_used=…`.
7. Tests: extend the owners named above; do not add a parallel harness.

## Prove behavior at the boundaries

Before writing a test, name the regression it catches. Extend existing slot,
execution-plan, and launcher tests where possible. Exercise selection through
accepted execution, not just registration lists or exact prompt snapshots.

Cover exact versions, unresolved aliases, provider remapping, precedence,
mixed-model slots, definition variants, cache isolation, and immutable resume.
Prove that directives reach the selected model's step and that rejected output
still fails its existing contract. Keep standalone review read-only and
`commit_push` agent-free.

Apply schema-first contract evolution and version parity when adding durable
fields. Architecture guards stay in force. Baselines and exemptions must not
grow to accommodate a model strategy.

The first model-specific reference is
[Getting the most out of Opus 5.5](https://claude.dev/blog/getting-the-most-out-of-opus-5-5/).
It informs directive design. Repository contracts govern execution and authority.
