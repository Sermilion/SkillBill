# Authoring model-specific phase strategies

Model-specific strategies adapt a slot's agent instructions to a resolved model.
They keep the slot's execution contract, including its phase ids, output validation,
authority, retries, and checkpoints.

The Opus 5.5 selection behavior belongs to
[SKILL-403](../.feature-specs/SKILL-403-opus-55-slot-strategies/spec.md). It is planned work, not an
installed extension point. This guide records the requirements for that work and
future model strategies. Implementation must update this guide with the actual
registration and configuration examples before describing them as available.

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

`commit_push` remains a runtime operation. Its model policy selects the existing
runtime commit strategy. Do not create an agent session or a forwarding strategy
just to give this slot a model-specific name.

## Resolve model identity before selection

Use one authoritative effective model resolution path for both selection and
launch. Preserve per-phase overrides, execution-matrix precedence, and explicit
effort. Provider adapters own provider defaults and remapping. Engine code consumes
resolved facts through the existing boundaries instead of reading provider
environment variables itself.

Use documented version identities. An agent name, an unversioned alias, or a
substring match does not prove a model version. Recognize an alias only when an
authoritative source resolves it to that version. Preserve an explicit unknown
outcome when version evidence is unavailable.

Resolve participating steps separately. SKILL-403 proposes selecting an
Opus-capable slot strategy when any participating agent step resolves to Opus 5.5.
Only those steps receive Opus directives. Other steps keep their existing
directives. Optional steps and delegated children need explicit inheritance rules.
Future models must define equivalent mixed-model behavior before adding selection.

## Add behavior that earns a strategy

Record the model guidance source and the concrete instruction changes it supports.
Separate provider recommendations from project decisions. Each agent slot needs
an observable behavior change, not a class that only forwards to another strategy.
Reuse existing execution mechanics without copying validators or transition rules.

Keep model-specific decisions at selection and directive ownership boundaries.
Do not add model-name branches throughout the process runner or shared run loop.
Register implementations in the existing composition root, and compose model
selection with definition, review mode, and gate facts.

Keep task instructions within the accepted step's authority. A model's ability to
delegate does not authorize build subagents or wider repository writes. Agent
checklists, if used, are runtime-private projections. They do not replace durable
workflow state or become committed task files by default.

## Preserve execution identity

Version semantic changes to strategy behavior. Include every model/profile fact
that changes selection in cache identity and the admitted execution plan. Resolve
the selected composition before launch, using the same effective facts at launch.

Resume the recorded composition. A changed default, alias, provider mapping, or
model configuration must not silently select a new strategy for an existing run.
Incompatible changes use the existing typed refusal and recovery contract before
workflow or lease mutation. Do not reinterpret old plans through today's defaults.

Distinguish requested model identity from provider-reported execution identity.
When a provider reports a change, record bounded evidence and retain the accepted
attempt's strategy. State the evidence limit when the provider does not report
actual identity. Do not infer it by scraping interactive notices.

## Prove behavior at the boundaries

Before writing a test, name the regression it catches. Extend existing slot,
execution-plan, and launcher tests where possible. Exercise selection through
accepted execution, not just registration lists or exact prompt snapshots.

Cover exact versions, unresolved aliases, provider remapping, precedence,
mixed-model slots, definition variants, cache isolation, and immutable resume.
Prove that directives reach the selected model's step and that rejected output
still fails its existing contract. Keep standalone review read-only and
`commit_push` agent-free. Include admission and cancellation failure paths when
changing those boundaries.

Apply schema-first contract evolution and version parity when adding durable
fields. Run required project validation, including architecture guards. Baselines
and exemptions must not grow to accommodate a model strategy.

## Keep the guide usable

For each landed model, add its supported identifiers, alias policy, selection
precedence, mixed-model behavior, registration example, and compatibility rules.
Name the tests that prove them and the files that own the behavior. Mark unsupported
provider evidence explicitly. Update this guide in the same change as the model.

The first model-specific reference is
[Getting the most out of Opus 5.5](https://claude.dev/blog/getting-the-most-out-of-opus-5-5/).
It informs directive design. Repository contracts govern execution and authority.
