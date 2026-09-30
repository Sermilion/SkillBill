# SKILL-384 Subtask 3 - Restrict strategy capabilities and centralize coupled transitions

Parent spec: [.feature-specs/SKILL-384-workflow-skeleton-execution-contracts-and-state-ownership/spec.md](spec.md)
Issue key: SKILL-384

## Scope

Implement finding 4 after the execution contracts settle. Own runtime-engine featuretask slot/state/PhaseRunState.kt and PhaseStepState.kt, slot/attempt/PhaseAttemptEnvironment.kt and its consumers, durable and in-memory state implementations, shared run-loop transition ownership, review-local state, quality-gate helpers, runtime-owned finalization helpers, and goal planning fan-out integration. Update the owning architecture documentation and justified guards.

Before changing access, write .feature-specs/SKILL-384-workflow-skeleton-execution-contracts-and-state-ownership/capability-census.md. Record each strategy and helper consumer, its direct and transitive reachable mutable capabilities, the operations it actually uses, its launch or side-effect path, and the transition owner. Retain this baseline and add the final census in the same file. Include a disposition for each former broad capability and a justification for each new interface.

Keep the existing shared loop as execution coordinator. Give one owner responsibility for coupled progress, session, attempt accounting, completion, re-entry, evidence invalidation, and checkpoint transitions. Strategies receive immutable observations and named operations scoped to the active step and accepted resolved plan. Strategy requests cannot select arbitrary phase IDs to bypass plan membership or persistence prerequisites. Keep mutable recorder, session, goal, gate, checkpoint, and attempt machinery behind their owners. Do not pass them back through renamed contexts, getter collections, extension receivers, casts, or helper constructors.

Keep review reservations, generations, finding verification, review checkpoints, and settlement local to review consumers. Planning receives only its fan-out operations; quality-gate consumers receive only their gate operations; finalization retains its current runtime owner. Narrow interfaces only where a module boundary, real test substitute, or existing separate consumers justify them. Prefer removing forwarding layers to creating a role interface per helper.

Migration and recovery: this subtask introduces no new durable wire format or semantic reset. Preserve subtask 2's resolved execution identity and existing serialized evidence. Keep durable and ephemeral implementations behaviorally equivalent where their storage policies agree. Preserve audit's in-memory briefing rule, goal planning fan-out and imported preparation, lease fencing, checkpoint ownership, review remediation, cancellation, and retry accounting. If a necessary change alters durable semantics, update its owned contract and compatibility policy explicitly rather than presenting it as a refactor.

Follow runtime-kotlin/ARCHITECTURE.md and docs/code-principles.md. Add no authored Kotlin line or block comments, keep KDoc only on interfaces and their members, preserve dependency direction, and use the existing composition root. No source-skill or rendering work is planned.

## Acceptance Criteria

1. S3-AC1. capability-census.md contains preserved before and after operation-level inventories for strategies and their transitive helpers. Each entry identifies reachable authority, actual use, launch or side-effect path, and final owner. Every removed or retained broad capability and every introduced interface has a concrete disposition.
2. S3-AC2. One run-transition owner implements coupled progress, session, attempt accounting, completion, re-entry, and evidence/checkpoint transitions through named operations. Strategies cannot independently mutate the fields that form those transitions. Read observations do not expose mutable aliases.
3. S3-AC3. Strategy entry points and helpers receive only the capabilities required for their selected step. PhaseStepState no longer inherits the whole run state and all review interfaces. PhaseAttemptEnvironment and replacement helpers do not expose an equivalent all-access object, getter bag, cast path, or arbitrary attempt executor.
4. S3-AC4. Review-only operations are reachable only by review consumers. Planning fan-out, gate execution, and finalization expose scoped operations through their existing owners. Step-scoped operations verify the accepted plan and cannot bypass required persistence by requesting another step.
5. S3-AC5. Existing durable and in-memory implementations retain their intended storage distinction while sharing transition semantics. Source paths preserve audit briefing ephemerality, goal fan-out, imported preparation, lease fencing, review invalidation, checkpoint ownership, cancellation, and existing retry budgets.
6. S3-AC6. A capability-boundary guard checks reachable types and operations, including transitive helper and extension access, and includes synthetic violating and allowed cases. It detects a non-review consumer obtaining review mutation or raw run-state authority. It does not rely solely on prohibited source names.
7. S3-AC7. Behavioral regression coverage exercises named transitions through the real shared loop or owning boundary. It asserts durable/in-memory outcomes, review remediation and checkpoint preservation, fan-out isolation, retry accounting, cancellation, and persistence rejection. Tests assert observable records and outcomes rather than forwarding calls.
8. S3-AC8. Architecture documentation matches the final census and ownership. Any mechanically enforced new rule is registered with its proving test in the existing enforcement inventory. No new port exists solely to rename or forward a same-module dependency set, and no semantic identity or durable format changes occur without their governed compatibility policy.

## Non-Goals

- Do not replace the slot architecture or shared run loop.
- Do not introduce a generic workflow framework, second composition root, or role interface for every helper.
- Do not change phase ordering, review policy, retry budgets, checkpoint semantics, or durable recovery behavior.
- Do not treat file counts, constructor counts, or renamed types as proof that authority narrowed.
- Do not perform unrelated cleanup or modify source skills.

## Dependency Notes

Depends on: 1, 2
Depends on subtasks 1 and 2. Narrow access around the established gate, persistence, resolved-plan, and admission operations. This ordering prevents the capability change from choosing a second execution plan or hiding unchecked persistence in a new context. The baseline census is a repository artifact written by this subtask's implementation, not a runtime-written history entry. Preserve existing unrelated planning changes and adapt to the current tree.

## Validation Strategy

Author tests during implementation; execute them in validate. The boundary guard catches the concrete design regression where a non-review strategy obtains mutable review or run state through a helper, extension receiver, or widened capability type. Exercise the guard with a synthetic violation and a permitted narrow consumer through its real entry point. Pair it with behavioral tests because type-name checks cannot prove transition ownership. Reuse existing shared-loop scenarios where possible: a review repair must invalidate and advance the correct generation without losing its checkpoint; a rejected write must still prevent execution; a fan-out unit must not mutate a sibling's attempts; cancellation must retain its signal and ownership; a resumed transition must retain the recorded budget semantics. Compare durable and in-memory outcomes only where policy is shared, with an explicit audit-briefing exemption assertion. Avoid adding a sibling test for each forwarding helper. Validate executes the affected engine and architecture suites through the manifest full gate. Only a runtime-selected build phase runs compile commands. No command success is an acceptance criterion.

## Next Path

skill-bill goal SKILL-384

## Spec Path

.feature-specs/SKILL-384-workflow-skeleton-execution-contracts-and-state-ownership/spec_subtask_3_restrict-strategy-capabilities-and-centralize-coupled-transitions.md
