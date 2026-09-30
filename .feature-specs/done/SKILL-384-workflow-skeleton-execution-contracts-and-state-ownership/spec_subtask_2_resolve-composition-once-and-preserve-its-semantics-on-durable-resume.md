# SKILL-384 Subtask 2 - Resolve composition once and preserve its semantics on durable resume

Parent spec: [.feature-specs/SKILL-384-workflow-skeleton-execution-contracts-and-state-ownership/spec.md](spec.md)
Issue key: SKILL-384

## Scope

Implement findings 5 and 6 together. Own runtime-engine featuretask slot/PhaseStrategyRegistry.kt, PhaseStrategySelection.kt, PhaseStrategyLookup.kt, skeleton bindings and traversal consumers, phase-run admission, lifecycle/continuation, run-state reconstruction, and goal-child creation. Own runtime-domain skeleton and execution-plan models, runtime-application workflow creation inputs, runtime-ports persistence contracts, runtime-infra/contracts validators, runtime-infra/sqlite workflow transactions, and existing runtime-core composition wiring.

Create one immutable resolved execution plan. It contains the selected definition and semantic revision, ordered slot and strategy identities with semantic revisions, selected steps and entries, resolved traversal, and identities for behavior-affecting policies. Relevant policies include gate command family and effective command selection, receipt interpretation, retry and resume budgets, audit behavior, review invalidation, checkpoint ownership, and finalization. Store bounded policy identifiers or canonical digests of relevant inputs, not payload bodies, prompt text, or whole manifests. Runtime-owned review mode and quality-gate selection become typed dimensions. Manifest-owned names and paths remain open strings with shape validation.

Choose one authoritative persistence location: a governed execution-plan artifact in the existing workflow record's database artifacts. Leave FeatureTaskExecutionIdentity responsible for repository/spec lookup. Do not create another table, copy the descriptor into phase records, or store a competing graph in the identity row. The live immutable plan is the resolved representation of this descriptor. New-run resolution derives it once from definitions and registry. Durable resume validates the stored descriptor against supported implementations and uses that accepted plan. Registry lookups attach implementations without silently recomputing a different traversal.

Persist the descriptor atomically with new durable workflow creation, including goal children and imported preparation state, before launch or side effects. Thread resolved data through existing creation inputs using the composition root. Do not move registry dependencies into inward persistence models. Before resume changes execution state, validate identity ahead of continuation claim, mutable reconstruction, completion invalidation, and agent launch. Recheck authoritative state within the transaction that admits continuation. Preserve current leases and fencing.

Compatibility policy: schema compatibility and semantic compatibility are separate. Semantic revisions change when output interpretation, transitions, retry accounting, gate policy, or side-effect ownership changes incompatibly. Cosmetic edits do not change them. Initial support accepts matching semantic descriptors. A compatible implementation may retain the same revision only when its contract is unchanged. Cross-revision mappings require a finite explicit compatibility entry and a boundary regression test; no mapping for identity-less legacy runs is shipped in this subtask.

Recovery: missing, malformed, unsupported-version, and incompatible identities produce distinct typed reasons. Quarantine invalid artifact evidence through existing mechanisms without deleting the workflow's side-effect records. Do not stamp current bindings onto old runs. Existing route-identity repair does not repair semantic identity. Operator guidance preserves the original workflow and directs inspection of existing status and diagnostics, followed by use of a compatible runtime or a separately reviewed recovery mapping. Do not reset attempts, reopen terminal runs, or replay commit/push. In-memory phase runs hold the same plan ephemerally and gain no durable row or resume feature.

Follow architecture, wire ownership, schema versioning, typed failure, and observability requirements. Set explicit byte and collection limits in the new contract and enforce them on production and parse boundaries.

## Acceptance Criteria

1. S2-AC1. Registry validation rejects empty or duplicate step lists, entries outside their strategy steps, duplicate registrations, and steps outside their slot with typed composition failures. Selection rejects missing bindings and ambiguous matching facts independently of set iteration order, including multiple facts that happen to select the same strategy.
2. S2-AC2. The resolver validates selected ownership and traversal before execution. Selected entries are reachable, entry gates and remediation edges reference valid selected steps, and no selected step disappears silently during definition filtering. It accepts intentional optional steps, short definitions, loop-only remediation, and supported build/validate alternatives.
3. S2-AC3. A boundary test enumerates shipped definitions and supported typed selections through the production resolver. Synthetic rejection cases cover malformed registry entries, missing or ambiguous bindings, unreachable entries, and incoherent traversal. Acceptance cases protect short definitions, optional steps, and loop-only remediation without duplicating the resolver algorithm in tests.
4. S2-AC4. Execution lookup requires membership in the selected resolved plan and rejects unselected steps. Historical-record interpretation uses a separate non-executing path under a known semantic policy. Recognizing a historical step cannot authorize its runner, launch, or side effects.
5. S2-AC5. One immutable resolved plan supplies selected strategy identities, traversal, step policy identity, dispatch ownership, and resume interpretation. Execution consumers no longer independently resolve facts or rebuild traversal. Runtime-owned selection dimensions are typed while manifest-owned extension vocabulary remains open and shape-validated.
6. S2-AC6. Canonical YAML defines a bounded execution-plan artifact with explicit byte, collection, identifier, and revision constraints. The contract has an owning Kotlin version and wire-key vocabulary, typed parse failure, parity tests, coherence validation, and an explicit legacy policy. Its semantic descriptor includes the definition, selected strategies, traversal, and relevant execution policies without cosmetic source hashes.
7. S2-AC7. New durable creation paths persist exactly one authoritative semantic descriptor in the workflow database artifact within the workflow-creation transaction. Standalone durable workflows and goal children use the same ownership rule. A failed write rolls back creation. No descriptor is duplicated into each phase record or into FeatureTaskExecutionIdentity.
8. S2-AC8. Durable resume checks schema and semantic compatibility before continuation claim, execution-state mutation, mutable reconstruction, or launch. Admission rechecks authoritative state under the existing transaction and lease/fencing rules. Compatible reconstruction preserves completed outputs, attribution, checkpoints, and existing attempt-budget semantics, including any reset explicitly allowed by the recorded resume policy.
9. S2-AC9. The compatibility policy accepts matching semantics, distinguishes explicit supported mappings from schema validity, and does not invalidate cosmetic implementation changes. Initial legacy adoption has no identity-less mapping. Missing, corrupt, unsupported, and incompatible descriptors receive distinct typed diagnostics and cannot acquire current bindings automatically.
10. S2-AC10. Recovery paths preserve completed and uncertain commit/push evidence and terminal history. Receipt regeneration from subtask 1 requires accepted semantic identity and a proven safe gate boundary. Neither identity recovery nor receipt quarantine resets attempts, discards finalization records, launches work for a terminal run, or replays irreversible side effects.
11. S2-AC11. Durable reopen tests assert compatible preservation and incompatible rejection before execution-state changes or launches. Adapter-boundary tests assert transaction rollback, stale-owner fencing, and conflict rejection. In-memory tests assert ephemeral plan use without a workflow row, identity persistence, or new phase-resume behavior.

## Non-Goals

- Do not retain arbitrary historical executable implementations.
- Do not add a user-facing strategy selector or phase-run resume command.
- Do not create competing graph authorities or duplicate semantic descriptors.
- Do not infer legacy semantics from the current binding table or repository identity alone.
- Do not narrow every strategy capability here or change existing retry policy as a side effect.

## Dependency Notes

Depends on: 1
Depends on subtask 1 so the resolver records the final pack-validation/build alternatives, evidence policy, and required persistence behavior. Composition validation and durable identity are one commit because shipping them separately would leave resumed runs governed by a different authority. Subtask 3 depends on the resolved plan and admission operations established here. Preserve the existing FeatureTaskExecutionLookupStore immutable route identity and transaction ownership; the new artifact adds semantic authority without changing its lookup purpose.

## Validation Strategy

Author focused tests during implementation and execute them in validate. Composition tests catch malformed strategies reaching launch, conflicting facts choosing by set order, and execution lookup admitting an unselected historical step. One supported-selection matrix covers shipped definitions; a separate malformed-composition matrix exercises distinct rejection rules. Durable reopen tests catch an upgrade reinterpreting completed evidence, resetting budgets, or replaying finalization. Compare stored records, attempts, checkpoints, and side-effect evidence, not merely callback order. Cover compatible identity, semantically changed gate or retry policy, missing identity, malformed identity, unsupported version, and a cosmetic implementation change with unchanged semantics. Test continuation claim rejection before state mutation, creation rollback when identity persistence fails, stale fencing ownership, and immutable artifact conflicts at real persistence boundaries. Test that receipt recovery cannot proceed with incompatible identity and cannot issue commit/push. Retain schema parity and bounded-input rejection coverage. Validate runs relevant engine, domain, contract, SQLite, and architecture suites under the pack full gate. Build remains compile-only and runs only when selected by the runtime. No tests or builds run during this planning phase.

## Next Path

skill-bill goal SKILL-384

## Spec Path

.feature-specs/SKILL-384-workflow-skeleton-execution-contracts-and-state-ownership/spec_subtask_2_resolve-composition-once-and-preserve-its-semantics-on-durable-resume.md
