# Authoring model-specific phase strategies

A model adapts a slot's agent instructions through a directive supplement. The
slot keeps one canonical strategy, and the supplement appends model-specific
text to the step prompt. The slot's execution contract, including its phase ids,
output validation, authority, retries, and checkpoints, never changes with the model.

Opus 5.5 supplements are installed and on by default. Existing model configuration
selects the model. One switch turns every supplement off. This guide records the
landed path and the steps a future model must follow.

## Start with the slot contract

Read [runtime command guidance](runtime-command-guidance.md), the
[slot architecture](../runtime-kotlin/ARCHITECTURE.md#phase-slots-and-strategies),
and [architecture guidelines](architecture-guidelines.md) before changing a strategy.
Inspect the current declarations rather than copying an old model's implementation.

`PhaseSlot` owns step membership. `SkeletonDefinition` owns the selected slots and
their traversal. `PhaseStrategy` declares strategy identity, revision, steps,
policies, directives, and per-step execution behavior. `PhaseStrategyRegistration`
pairs a strategy with its runner. The accepted attempt host resolves that runner
after required persistence. Strategies must use their bound role operations.

A model never selects a different strategy. `SkeletonStrategyBindings` picks the
canonical strategy from the definition and selection facts alone. A planning
strategy keeps goal-planning fan-out, a gate strategy keeps build versus
validation selection and pack commands, and review keeps the difference between
full-run repair and report-only standalone review.

`commit_push` remains a runtime operation. `RuntimeCommitStrategy` launches no
agent and has no supplement. The `monitor` and `standalone_monitor` strategies
have no supplement resource either.

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

Each participating step records its own profile, and only a step whose profile
qualifies is supplemented. Unselected gate members and absent optional steps cannot
select a profile. Explicit child overrides win; unsupported inheritance stays
unknown.

## Supplement a canonical strategy

`ModelDirectiveSupplements` is the single supplement owner. It maps each canonical
`(slot, strategy id)` pair to one directive resource for the profile and loads
that resource once. Resources live under
`runtime-kotlin/runtime-engine/src/main/resources/skillbill/engine/featuretask/slot/<slot>/opus-5-5-<family>.md`.
Write and review every directive resource against the
[Opus 5.5 directive guidelines](opus-5-5-directive-guidelines.md).

Composition reads the recorded `FeatureTaskRuntimePhasePromptComposeInputs.stepProfile`
of the step. For a qualifying step it appends the resource to the task directive,
to the retry focus when one is set, and to the output contract when one exists.
Directive placement and text stay as the canonical strategy composed them. A step
with the `canonical` profile, a step the owner has no mapping for, and a strategy
without a resource stay unsupplemented. Alias identity alone never enables a
supplement; the recorded profile does.

Two mappings need care:

- `plan` and `plan_subtasks` share the `goal-plan-fan-out` strategy but take
  different resources: `plan_subtasks` maps to the fan-out resource and `plan`
  maps to the plan resource.
- Inline and delegated standalone review launch through their own owners. Each
  applies the supplement to its own prompt, so both `REVIEW` modes are covered.
  Full-run `CODE_REVIEW` stays inline for every current mode.

Switch: `SKILL_BILL_MODEL_DIRECTIVE_SUPPLEMENTS` is on when omitted, `true` keeps
it on, and `false` turns every supplement off. Any other value fails with a typed
error. [`RuntimeFeatureTaskSlotProvides.kt`](../runtime-kotlin/runtime-core/src/main/kotlin/skillbill/di/featuretask/RuntimeFeatureTaskSlotProvides.kt)
resolves it once from the injected immutable environment settings and hands the
result to `PhaseStrategyLookup`. Durable runs, in-memory phase runs, and direct
review launches all read that one value through their step binding. Slot and
composer code read no ambient environment, and the switch is not persisted, so
changing it affects only prompts of attempts launched afterwards.

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

Plans recorded before supplements carry model-specific strategy ids such as
`inline-opus-5-5`. The registry keeps those ids as immutable historical aliases
that resolve to the canonical strategy for lookup and runner dispatch. Revision
and policy checks still run against the recorded identity, so a tampered or stale
plan is refused. Current configuration never reselects a recorded plan, and no
workflow row is rewritten. Aliases are fixed history, not an extension point: a
new model adds none.

Launch evidence keeps requested identity, effective prelaunch identity, and
bounded provider-reported identity separate. Claude `modelUsage` object keys
supply reported evidence. Codex, Cursor, and Junie report `unsupported_agent`. Malformed
fields degrade evidence and still admit ordinary output.

## Add a model

1. Confirm the wire identity against the model's documentation. Add its
   `PhaseModelProfile` value and one case to `PhaseModelProfileClassifier`.
   Aliases stay unknown unless an inherited environment pins them to that exact id.
2. Add directive resources next to the canonical strategies they supplement, and
   register each canonical strategy, accepted step, and resource with
   `ModelDirectiveSupplements`. Its resource table currently serves only
   `opus-5-5`, so key the table by profile when the second model lands. Cover
   `plan_subtasks` and both standalone review owners where the model needs them.

That is the whole recipe. No strategy class, registration, selection binding,
strategy id, or plan field changes, because the profile is already recorded per
step. Reuse existing validators, pack commands, standalone report-only rules, and
build's single-session policy; a supplement changes prompt text only. Diagnostics
for unknown resolution, resume drift, malformed reported identity, and checklist
failure use `seam=… value_expected=… value_used=…`.

Production owners: `PhaseModelProfileClassifier`, `AgentRunLauncher`,
`resolveAgentRunLaunchModel`, `ModelDirectiveSupplements`,
`FeatureTaskRuntimeExecutionPlanResolver`,
`FeatureTaskRuntimeStepLaunchAssignmentFactory`.

Test owners: `PhaseModelProfileSelectionTest`, `AgentRunCommandBuildersTest`,
`ModelDirectiveSupplementsTest`, `PhaseStrategyHistoricalAliasTest`,
`RuntimeFeatureTaskSlotProvidesTest`, `FeatureTaskRuntimeExecutionPlanCoherenceTest`,
`FileSystemFeatureTaskImplementationChecklistStoreTest`,
`FeatureTaskRuntimeCheckpointScopeTest`. Extend them; do not add a parallel harness.

## Prove behavior at the boundaries

Before writing a test, name the regression it catches. Extend existing slot,
execution-plan, and launcher tests where possible. Exercise selection through
accepted execution, not just registration lists or exact prompt snapshots.

Cover exact versions, unresolved aliases, provider remapping, precedence,
mixed-model slots, the switch, historical aliases, cache isolation, and immutable
resume. Prove that directives reach the selected model's step, that canonical
steps stay unsupplemented, and that rejected output still fails its existing
contract. Keep standalone review read-only and `commit_push` agent-free.

Apply schema-first contract evolution and version parity if a change ever adds a
durable field; a supplement does not. Architecture guards stay in force.
Baselines and exemptions must not grow to accommodate a model.

The first model-specific reference is
[Getting the most out of Opus 5.5](https://claude.dev/blog/getting-the-most-out-of-opus-5-5/).
[Opus 5.5 directive guidelines](opus-5-5-directive-guidelines.md) records how it
applies to directive design. Repository contracts govern execution and authority.
A later model whose vendor publishes prompting guidance gets its own guidelines
file next to this one.
