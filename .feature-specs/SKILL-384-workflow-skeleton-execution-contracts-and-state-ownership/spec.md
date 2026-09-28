# SKILL-384 - workflow-skeleton-execution-contracts-and-state-ownership

## Mode

decomposed

## Intended Outcome

# SKILL-384 Workflow skeleton execution contracts and state ownership

## Intended outcome

Make gate evidence truthful, require persistence before execution, reject invalid strategy composition, preserve durable execution semantics across upgrades, and restrict strategies to their owned operations. Preserve the existing slots, strategies, shared run loop, composition root, dynamic platform discovery, CLI forms, and compatible durable step IDs.

The investigation used static source inspection. It did not reproduce production incidents or execute tests. Findings about broad capabilities, composition validation, and upgrade compatibility are design gaps.

## Scope and decisions

Subtask 1 owns findings 1 through 3. Add explicit pack-validation composition for SkeletonDefinition.VALIDATION. Attribute it to PHASE_VALIDATE and the existing runtime-owned validation-result and validation-evidence family. Retain PHASE_BUILD and build_receipt for goal-child compile-only build. Required gate absence blocks. Successful evidence requires a real successful command record, including when the command uses cached work. Required start and briefing persistence must succeed before execution.

Subtask 2 owns findings 5 and 6. Resolve composition once into an immutable plan. Persist its bounded semantic descriptor as one governed artifact in the existing authoritative workflow database record. FeatureTaskExecutionIdentity remains the repository, issue, spec, mode, and route lookup identity. Do not duplicate semantic identity there or in each phase record. New workflow creation persists the descriptor atomically with the workflow before any execution. Resume validates it before claiming continuation, reconstructing mutable run state, applying recovery, or launching.

Subtask 3 owns finding 4. Give coupled run transitions one owner and pass strategies only immutable observations and operations scoped to their current step. Review authority remains local to review consumers. A before-and-after census includes transitive helper access, not just constructor signatures.

## Acceptance Criteria

1. Subtask 1 provides explicit standalone full-validation composition with manifest-selected collect-all commands, compile-only goal-child build, truthful step attribution, existing repair and wrapper behavior, and regression assertions for exact argv, telemetry, no workflow row, and no commit.
2. Subtask 1 provides typed required-gate rejection, command-backed success evidence, cache-hit semantics, governed receipt coherence, and documented recovery that preserves finalization evidence and cannot replay commit or push.
3. Subtask 1 enforces required start and briefing writes at the shared attempt boundary, preserves cancellation and primary failures, and contains failure-injection tests asserting zero execution after rejection and accurate terminal attribution.
4. Subtask 2 validates shipped and synthetic compositions, separates historical interpretation from execution authorization, and provides one resolved plan consumed by traversal, dispatch, status attribution, and resume policy selection.
5. Subtask 2 persists a bounded run-level semantic descriptor and contains compatibility, transaction, and fencing coverage for admission and resume. Missing or incompatible identities cannot silently acquire current semantics or erase completed work.
6. Subtask 3 includes an operation-level capability census, removes transitive broad state access from strategies, and contains boundary and behavioral coverage for shared transitions, review, goal fan-out, checkpoints, and durable versus in-memory execution.
7. Each changed or new runtime contract has canonical YAML, an owning version and wire-key vocabulary, typed parse failures, parity coverage, bounded validation, and an explicit legacy policy. Recovery diagnostics use the existing observability mechanism without payload bodies.

## Dependencies and rollout

Execute subtasks 1, 2, and 3 in that order. Each commit contains its implementation, contract changes, documentation, and meaningful tests. No feature flag or permissive fallback is planned. Gate and persistence rejection land before capability narrowing. Composition and durable semantic identity land in the same commit.

The future feature branch starts from base/SKILL-380-phase-slot-strategies. Do not create it during this preparation. Preserve existing unrelated changes in AgentPlanStrategy, PlanDecompositionStop, planning tests, and the planning prompt fixture.

## Migration and recovery

Absent required gates never count as success. Unsupported or empty successful receipts enter existing quarantine and recovery handling. Preserve original evidence and finalization records. Evidence regeneration is permitted only at an existing safe gate boundary with compatible semantic identity and proven absence of downstream side effects. Before semantic identity support lands, ambiguous historical recovery blocks.

Initial semantic compatibility accepts matching persisted descriptors and explicitly compatible current implementations. There is no automatic adoption mapping for identity-less legacy runs. Missing, malformed, unsupported, and semantically incompatible descriptors receive distinct typed recovery reasons. Preserve completed and uncertain commit/push evidence. Operators inspect the existing workflow status and diagnostics, retain the original run, and use a compatible runtime or a separately reviewed recovery mapping. Repository identity repair does not authorize semantic identity repair. Never advise resetting the run or deleting records to bypass compatibility.

## Non-goals

No implementation during preparation, workflow launch, branch creation, commit, push, or PR. No new workflow framework, strategy-selection CLI, standalone phase resume feature, optional gate-skip policy, historical executable-code archive, unrelated operation redesign, or broad cleanup. No source-skill or renderer changes are required. If later implementation proves such a change necessary, its owner must read docs/skill-source-generation.md and include the required installation work.

## Validation ownership

Implementation authors tests and implementation evidence. Audit checks criteria by reading the tree. Only validate executes tests and the selected pack's full validation gate. Only build executes the selected pack build commands for compile proof. No command result, receipt from a later phase, commit SHA, PR artifact, or runtime-written history entry is an acceptance criterion.

## Next path

skill-bill goal SKILL-384

## Overview

# SKILL-384 Workflow skeleton execution contracts and state ownership

## Intended outcome

Make gate evidence truthful, require persistence before execution, reject invalid strategy composition, preserve durable execution semantics across upgrades, and restrict strategies to their owned operations. Preserve the existing slots, strategies, shared run loop, composition root, dynamic platform discovery, CLI forms, and compatible durable step IDs.

The investigation used static source inspection. It did not reproduce production incidents or execute tests. Findings about broad capabilities, composition validation, and upgrade compatibility are design gaps.

## Scope and decisions

Subtask 1 owns findings 1 through 3. Add explicit pack-validation composition for SkeletonDefinition.VALIDATION. Attribute it to PHASE_VALIDATE and the existing runtime-owned validation-result and validation-evidence family. Retain PHASE_BUILD and build_receipt for goal-child compile-only build. Required gate absence blocks. Successful evidence requires a real successful command record, including when the command uses cached work. Required start and briefing persistence must succeed before execution.

Subtask 2 owns findings 5 and 6. Resolve composition once into an immutable plan. Persist its bounded semantic descriptor as one governed artifact in the existing authoritative workflow database record. FeatureTaskExecutionIdentity remains the repository, issue, spec, mode, and route lookup identity. Do not duplicate semantic identity there or in each phase record. New workflow creation persists the descriptor atomically with the workflow before any execution. Resume validates it before claiming continuation, reconstructing mutable run state, applying recovery, or launching.

Subtask 3 owns finding 4. Give coupled run transitions one owner and pass strategies only immutable observations and operations scoped to their current step. Review authority remains local to review consumers. A before-and-after census includes transitive helper access, not just constructor signatures.

## Acceptance Criteria

1. Subtask 1 provides explicit standalone full-validation composition with manifest-selected collect-all commands, compile-only goal-child build, truthful step attribution, existing repair and wrapper behavior, and regression assertions for exact argv, telemetry, no workflow row, and no commit.
2. Subtask 1 provides typed required-gate rejection, command-backed success evidence, cache-hit semantics, governed receipt coherence, and documented recovery that preserves finalization evidence and cannot replay commit or push.
3. Subtask 1 enforces required start and briefing writes at the shared attempt boundary, preserves cancellation and primary failures, and contains failure-injection tests asserting zero execution after rejection and accurate terminal attribution.
4. Subtask 2 validates shipped and synthetic compositions, separates historical interpretation from execution authorization, and provides one resolved plan consumed by traversal, dispatch, status attribution, and resume policy selection.
5. Subtask 2 persists a bounded run-level semantic descriptor and contains compatibility, transaction, and fencing coverage for admission and resume. Missing or incompatible identities cannot silently acquire current semantics or erase completed work.
6. Subtask 3 includes an operation-level capability census, removes transitive broad state access from strategies, and contains boundary and behavioral coverage for shared transitions, review, goal fan-out, checkpoints, and durable versus in-memory execution.
7. Each changed or new runtime contract has canonical YAML, an owning version and wire-key vocabulary, typed parse failures, parity coverage, bounded validation, and an explicit legacy policy. Recovery diagnostics use the existing observability mechanism without payload bodies.

## Dependencies and rollout

Execute subtasks 1, 2, and 3 in that order. Each commit contains its implementation, contract changes, documentation, and meaningful tests. No feature flag or permissive fallback is planned. Gate and persistence rejection land before capability narrowing. Composition and durable semantic identity land in the same commit.

The future feature branch starts from base/SKILL-380-phase-slot-strategies. Do not create it during this preparation. Preserve existing unrelated changes in AgentPlanStrategy, PlanDecompositionStop, planning tests, and the planning prompt fixture.

## Migration and recovery

Absent required gates never count as success. Unsupported or empty successful receipts enter existing quarantine and recovery handling. Preserve original evidence and finalization records. Evidence regeneration is permitted only at an existing safe gate boundary with compatible semantic identity and proven absence of downstream side effects. Before semantic identity support lands, ambiguous historical recovery blocks.

Initial semantic compatibility accepts matching persisted descriptors and explicitly compatible current implementations. There is no automatic adoption mapping for identity-less legacy runs. Missing, malformed, unsupported, and semantically incompatible descriptors receive distinct typed recovery reasons. Preserve completed and uncertain commit/push evidence. Operators inspect the existing workflow status and diagnostics, retain the original run, and use a compatible runtime or a separately reviewed recovery mapping. Repository identity repair does not authorize semantic identity repair. Never advise resetting the run or deleting records to bypass compatibility.

## Non-goals

No implementation during preparation, workflow launch, branch creation, commit, push, or PR. No new workflow framework, strategy-selection CLI, standalone phase resume feature, optional gate-skip policy, historical executable-code archive, unrelated operation redesign, or broad cleanup. No source-skill or renderer changes are required. If later implementation proves such a change necessary, its owner must read docs/skill-source-generation.md and include the required installation work.

## Validation ownership

Implementation authors tests and implementation evidence. Audit checks criteria by reading the tree. Only validate executes tests and the selected pack's full validation gate. Only build executes the selected pack build commands for compile proof. No command result, receipt from a later phase, commit SHA, PR artifact, or runtime-written history entry is an acceptance criterion.

## Next path

skill-bill goal SKILL-384

## Acceptance Criteria

1. SKILL-384 # Workflow skeleton execution contracts and state ownership

Create a governed spec bundle only. The user requested a spec for the six findings from the preceding architecture investigation. Keep the existing slot and strategy architecture and the shared run loop. Do not implement the changes, open a feature workflow, create a branch, commit, push, or open a PR.

## Intended outcome

Make gate outcomes truthful, stop agent execution when required phase persistence fails, restrict each strategy to the state operations it owns, reject invalid strategy composition before execution, and preserve execution semantics when durable workflows resume after a runtime upgrade.

## Evidence and required behavior

1. Standalone validation currently binds SkeletonDefinition.VALIDATION to PackBuildStrategy in runtime-kotlin/runtime-engine/src/main/kotlin/skillbill/engine/featuretask/slot/skeleton/SkeletonStrategyBindings.kt. FeatureTaskRuntimeBuildGatePolicy.kt selects the build command pair. The Kotlin pack distinguishes compileKotlin from its full check commands. The archived SKILL-380 subtask 8 scope explicitly requires phase validation to execute the collect-all validation gate and cache-bypassing collect-all verification. PhaseValidationRunTest currently asserts PHASE_BUILD and does not prove the command distinction. Require standalone validation to execute the manifest-declared full validation command family, retain its repair behavior and telemetry, and keep goal-child build compile-only. Resolve the strategy composition explicitly without a definition-specific branch in PhaseRunEntry or a second run loop. Test exact dispatched argv with distinct build and validation fixture commands and the existing wrapper override behavior. Preserve standalone no-workflow-row and no-commit behavior.

2. PackBuildGateCycle.settle handles AbsentFallback by producing runtimeOwnedBuildOutput with no measurements. PhaseQualityGateReporting.qualityGateAbsent is a no-op for durable runs. FeatureTaskRuntimeValidationGateExecutionEvidence.fromGateMeasurements defaults to passed, and orchestration/contracts/feature-task-runtime-build-receipt.yaml allows zero gate runs. Require a missing required gate to block with a typed failure and a diagnostic. Never synthesize passed build evidence without a successful command execution. Define receipt semantics for cache hits without requiring executed work units to be positive. Preserve evidence from a real successful cached command. Tighten the canonical receipt contract and coherence validation as needed; follow schema versioning and quarantine rules. Name the recovery behavior for existing empty successful receipts without replaying already completed commit or push side effects. An optional explicit skip policy would require a separate approved product decision and is outside this spec.

3. FeatureTaskRuntimeRunLoopOutputPersistence.persistPhase ignores recordPhaseState's Boolean result. PhaseAttemptOnce proceeds toward launch. PhaseLaunchPreparation ignores recordPhaseBriefing's Boolean result. The current durable recorder returns false when the workflow row is missing. Build and commit paths already check the start write. Require a successful required start write and required briefing write before launching any child or executing the phase side effect. Preserve exceptions and cancellation. Make rejection impossible to discard at the shared attempt boundary, using an owning typed result or typed failure rather than another forwarding abstraction. Preserve intentional in-memory behavior and the audit rule that launch briefings are not durably recorded. Add failure-injection coverage proving zero launches after either required write rejects and proving the recorded terminal reason is accurate when storage remains available.

4. PhaseStepState inherits PhaseRunState and every review-specific state interface. PhaseRunState exposes mutable FeatureTaskRuntimeRunState, mutable session transitions, records, goal state, settlements, checkpoints, attempt machinery, and gates. PhaseAttemptEnvironment forwards this entire capability set. The existing port provides storage substitution but does not restrict strategy authority. Require a before-and-after capability census, one owner of coupled run transitions, and narrow step capabilities. Keep review capabilities local to review consumers. Prevent strategies and their helpers from regaining access through a renamed context or getter bag. Keep the existing shared run loop, durable/in-memory implementations, goal planning fan-out, lease fencing, and checkpoint semantics. Introduce only interfaces justified by a module boundary, real test substitute, or existing separate consumers. Use meaningful boundary and behavioral tests rather than source-name checks alone.

5. PhaseStrategyRegistry only rejects duplicate registrations and out-of-slot steps. It accepts empty or duplicate step lists and does not validate entryStep membership. PhaseStrategyBinding.ByFact resolves the first matching value from Set<Enum<*>>, allowing conflicting facts to choose by iteration order. PhaseStrategyLookup.strategyFor identifies the selected slot strategy without verifying that it owns the requested step. Require composition validation before launch: nonempty unique steps, entry membership, coherent selected traversal and reachable entries, one unambiguous matching binding, and rejection of execution lookup for unselected steps. Preserve deliberate optional steps, short definitions, loop-only remediation, and valid build/validate alternatives. Distinguish execution lookup from historical-record interpretation so old unselected steps cannot become runnable accidentally. Validate every shipped definition and supported selection and reject malformed synthetic compositions with typed errors. Decide which facts should be typed without closing manifest-owned extension vocabularies.

6. Durable strategy selection currently recomputes from the installed binding table. FeatureTaskRuntimePhaseRecord records step and agent identity but no execution-definition revision or selected strategy identity. Schema compatibility alone does not guarantee that updated strategy semantics interpret old output correctly. Require a bounded durable execution identity with an explicit semantic compatibility policy, including the selected definition, selected strategies, traversal and relevant policy identity. Determine the smallest authoritative record and avoid storing duplicate identities in every phase record. Establish it before the first launch and validate it before resume mutates state or launches. Compatible resumes must preserve completed work and attempt budgets. Incompatible or missing legacy identities must follow a documented typed recovery path, with diagnostics, without silently reinterpreting evidence or replaying commit/push side effects. Preserve lease and transaction ownership. In-memory phase runs remain ephemeral. Do not require retaining arbitrary historical executable code or invalidate runs on cosmetic source changes.

## Scope and constraints

Use the current repository, not archived specs, as implementation authority. Read runtime-kotlin/ARCHITECTURE.md design principles and docs/code-principles.md. The earlier investigation was static source inspection, not executed tests or reproduced production incidents. Treat findings 4 through 6 as design gaps and avoid claiming observed production failures.

Keep dynamic manifest-based platform discovery, existing CLI forms, the sole listed skill, durable step IDs where compatible, and the existing composition root. No generic workflow framework, new user-facing strategy-selection options, new phase-run resume feature, unrelated operation redesign, or broad source cleanup. Do not invoke retired skills. Source skills remain content.md; if source skill or rendering changes are genuinely necessary, read docs/skill-source-generation.md and include the required install step in implementation ownership.

Every new or changed runtime YAML contract starts in orchestration/contracts, has an owning Kotlin version and wire-key vocabulary, typed parse failure, parity coverage, and an explicit legacy-record policy. Do not normalize corrupt records into success. Record fallback and recovery decisions through the existing observability mechanism without recording payload bodies.

## Planning output

Produce a parent spec, focused executable subtask specs, and a governed decomposition manifest. Define acceptance criteria with observable behavior and name the concrete regression each proposed test catches. Order gate correctness and persistence rejection before capability narrowing. Coordinate validated composition and execution identity so there is one resolved execution plan per run and no duplicate graph authority. Include scope, affected files or owning packages, dependencies, migration/recovery behavior, non-goals, and validation ownership in each subtask. Keep build-only verification distinct from full validation. Include tests to run during implementation; do not run builds or tests as part of this spec-only request.

## Constraints

- Runtime decompose planning stop.

## Non-Goals

- None

## Validation Strategy

This preparation executes no builds, tests, generators, or validation gates. Later implementation authors the boundary tests named in each subtask. Validate executes the relevant focused suites and the dominant pack's manifest-declared collect_all_full_gate_command, followed by cache_bypassing_collect_all_full_gate_command under its repair policy. For the current Kotlin pack these are ./gradlew check --continue --parallel -q --warning-mode none and ./gradlew check --continue --no-build-cache --parallel -q --warning-mode none. Build, when selected by the runtime, uses only build_command and cache_bypassing_build_command, currently ./gradlew compileKotlin and ./gradlew compileKotlin --no-build-cache. Build does not substitute for full validation. Preserve governed parity tests and regression coverage. Use one parameterized boundary test per distinct rule or failure branch where possible. Every proposed test below names the wrong behavior it catches; no test is justified solely by a helper, symbol, or prose change. Apply existing architecture guards during validate, including wire vocabulary, typed parse boundaries, dependency direction, comments, imports, package clustering, and file-size rules. No source-skill installation is planned.
