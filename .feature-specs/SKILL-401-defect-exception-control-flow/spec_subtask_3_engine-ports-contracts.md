# SKILL-401 Subtask 3 - engine-ports-contracts

Parent spec: [.feature-specs/SKILL-401-defect-exception-control-flow/spec.md](./spec.md)
Issue key: SKILL-401

## Scope

Remove the IAE/ISE control flow at the 6 runtime-engine sites, the runtime-ports site and the runtime-contracts site, including the `FeatureTaskRuntimeRunInvariantsSource` port result.

The engine sites are in `runtime-engine/.../engine/featuretask/`.

- **`lifecycle/execution/FeatureTaskRuntimeExecutionPlanDecode.kt:72`**: use `ValidationDepth.fromWireOrNull` and the other sources the census finds. Keep `"execution plan settings are invalid: <message>"`.
- **`definition.traversal(...)` callers.** Add a non-throwing form next to the existing extension, either `traversalOrViolation` or a nullable result plus its message. Callers:
  - `lifecycle/execution/FeatureTaskRuntimeExecutionPlanCompatibility.kt:85` goes to `incompatible()`.
  - `slot/PhaseStrategyLookup.kt:144` goes to `invalidComposition("definition … has incoherent traversal: <message>")`.
  - `lifecycle/execution/FeatureTaskRuntimeExecutionPlanCodec.kt:30` keeps its own handling.
  - The throwing `traversal` stays for callers handled only at the edge.
  - It is a shared skeleton helper with one generic path; no phase-specific branch (`runtime-kotlin/agent/decisions.md#01a41a7ed8b3`).
- **`lifecycle/execution/FeatureTaskRuntimeExecutionPlanCodec.kt:114`**: make the IAE sources of `decodeExecutionPlan` non-throwing, and throw `"execution plan cannot be reconstructed"` from the result.
- **`review/core/FeatureTaskRuntimeSharedReviewEvidenceResolver.kt:84`**
  - Add a non-throwing `ReviewDiffEvidence` parse in `runtime-application/.../application/reviewevidence/`, for example `parseOrRejection(diff)` returning the evidence or the `require` message, such as `"The authoritative review diff contains no attributable diff records."`.
  - `recordParseDegradation` must emit the same record text as before. If it took a `Throwable`, give it a message overload.
- **`phaserun/PhaseRunIntakeResolver.kt:79` and the `FeatureTaskRuntimeRunInvariantsSource` port**
  - Change `FeatureTaskRuntimeRunInvariantsSource.read(specPath)` (`runtime-ports/.../ports/taskruntime/`) to return a sealed `FeatureTaskRuntimeRunInvariantsRead { Read(invariants); Rejected(reason) }` in `ports/taskruntime/model`.
  - In `FileSystemFeatureTaskRuntimeRunInvariantsSource` (`runtime-infra/workflow`), its three path `require`s return `Rejected` with the same text.
  - `PhaseRunIntakeResolver` maps `Rejected` to `null`.
  - `goalrunner/planning/outcome/GoalPlanningSubtaskPlanProduction.kt:39` and `goalrunner/planning/context/GoalPlanningSharedPreplanProduction.kt:52` map `Rejected(reason)` to the stop reason they produce today. `invariantReadReason` takes the message, so the output is still `"…run-invariants could not be read: <reason>"`.
  - Their touched `runCatching` keeps catch-all behaviour for I/O through the cooperative rethrow (ground rule 7).
  - Update the test fakes `FakeInvariantsSource` and the `GoalRunnerTestFactory` source.
- **`runtime-ports/.../ports/workflow/model/WorkflowArtifactTimestampMapping.kt:68`**: use `parsePersistedInstantOrNull`, and keep `"Workflow artifact contains an invalid timestamp."`.
- **`runtime-contracts/.../contracts/JsonCodec.kt:111`**: drop the IAE arm. `parseToJsonElement` reports malformed text as `SerializationException`, which is already caught (kind C).

## Acceptance Criteria

1. No main source in runtime-engine, runtime-ports or runtime-contracts catches or `is`-checks `IllegalArgumentException` or `IllegalStateException`.
2. `FeatureTaskRuntimeRunInvariantsSource.read` returns `FeatureTaskRuntimeRunInvariantsRead`; `PhaseRunIntakeResolver` and both goal-planning producers branch on it with today's stop reasons; the test fakes are updated.
3. Execution-plan decode, compatibility and codec messages are unchanged.

## Non-Goals

runtime-application and runtime-infra sites other than `FileSystemFeatureTaskRuntimeRunInvariantsSource`.

## Test obligations

- `FeatureTaskRuntimeRunInvariantsRead.Rejected`: `PhaseRunIntakeResolver` returns null for an unreadable spec token.

## Shared Rules

Apply `spec.md` "Shared validators", "Ground rules", "Site classification", "Test rules" and "Execution Rule". If a shared validator this subtask needs is missing, add it as written there.

## Common Acceptance Criteria

- Every user-visible message and every persisted byte is unchanged. Existing tests pass with only exception-type assertion edits where a validator now returns a value.
- The exception type that reaches `CliRuntime.run` or `McpToolDispatcher.dispatch` for a given input is unchanged, or is a code on the MCP no-capture list.
- `TypedParseBoundaryArchitectureTest` and detekt pass. No `ParseBoundarySite` entry is dropped.
- Sites outside this subtask's scope are unchanged, except for callers of a port or validator this subtask changed.

## Validation Strategy

Goal gates: build, unit tests, detekt and the runtime-core repoTest suite. Add only the tests listed under Test obligations, and only where no existing test already drives the branch. Run nothing in implement; build, tests, detekt and repoTest belong to the build and validate phases.

## Next Path

skill-bill goal SKILL-401

## Spec Path

.feature-specs/SKILL-401-defect-exception-control-flow/spec_subtask_3_engine-ports-contracts.md

## Implementation Details

This plan uses only the upstream preplan digest for repository facts. AC-001 through AC-003 refer to the numbered Acceptance Criteria above. All tasks also preserve the Common Acceptance Criteria. Paths below are relative to `runtime-kotlin/`; production paths use the module's `src/main/kotlin/skillbill/` root and test paths use its `src/test/kotlin/skillbill/` root.

The digest supersedes historical line numbers and settles the reachable input sources. The existing Non-Goals section remains unchanged. Its application and infrastructure exclusion is subject to the existing Common Acceptance Criteria exception for changed validators and their callers. The complete review-diff parser conversion below is necessary to remove the engine catch without moving exception-driven control flow into another wrapper. Domain model validators and skeleton traversal are likewise bounded dependencies of execution-plan decoding. No sibling subtask work or new decomposition is required.

1. Expose ordered execution-plan model validation and non-throwing traversal. This serves AC-001 and AC-003. Touch domain `workflow/taskruntime/model/skeleton/ResolvedPhaseExecutionPlan.kt`, `ResolvedExecutionPolicy.kt`, `ResolvedFeatureTaskRuntimeExecutionSettings.kt`, and `workflow/taskruntime/phase/task/SkeletonDefinitionTransitions.kt`, plus the owners of the backward-edge, entry-gate and `FeatureTaskRuntimeTransitionDeclaration` models reached by traversal decoding. Add or retain companion `violation` helpers for strategy identity, dispatch, execution plan, policy id, revision, digest, timeout and traversal constraints. Each constructor invariant uses the same reason helper; ordered validation runs before input-driven construction. Preserve `Failed requirement.` for unlabelled assertions. Add a non-throwing traversal result carrying either the canonical declaration or its existing reason. Keep the throwing `SkeletonDefinition.traversal` for edge-only consumers. Both forms share one generic derivation and preserve selected steps, edges, gates and loop-only successor filtering. Assumption for implement to confirm: the digest does not name every traversal model's owner file, so place its validator beside the existing model rather than inventing a new ownership layer. Reuse `ResolvedPhaseExecutionPlanImmutabilityTest` and `PhaseStrategyTraversalTest` during validate; this task adds no separate test obligation.

2. Convert execution-plan decoding, compatibility and strategy lookup to explicit rejection. This serves AC-001 and AC-003. Touch engine `featuretask/lifecycle/execution/FeatureTaskRuntimeExecutionPlanDecode.kt`, `FeatureTaskRuntimeExecutionPlanTraversalCodec.kt`, `FeatureTaskRuntimeExecutionPlanCodec.kt`, `FeatureTaskRuntimeExecutionPlanCompatibility.kt`, and `featuretask/slot/PhaseStrategyLookup.kt`. Use `ValidationDepth.fromWireOrNull`, preserving retired `build_only` as `FULL`. Replace cap-behavior and cap-scope `valueOf` calls with nullable enum lookup. Decode fields into locals, apply task 1's ordered validators, then construct settings, strategies, edges, gates and the final plan. Route settings rejection through the exact `execution plan settings are invalid: <message>` framing and codec rejection through `execution plan cannot be reconstructed`. Compatibility still calls `incompatible()`; strategy lookup retains `definition … has incoherent traversal: <message>`. Remove the former IAE/ISE arms after all reachable input sources return values or existing owner-coded failures. Keep wire keys, field ordering, numeric compatibility, optional omissions and contract versions unchanged. Reuse `FeatureTaskRuntimeExecutionPlanResolverTest`, `FeatureTaskExecutionPlanCreationTest` and the traversal tests during validate. Existing behavioral coverage is the initial evidence for this conversion, with no duplicate helper-by-helper tests planned.

3. Land the run-invariants port result with its filesystem implementation, all producers and all fakes. This serves AC-001 and AC-002. Change ports `taskruntime/FeatureTaskRuntimeRunInvariantsSource.kt` so `read(specPath: Path)` returns a sealed interface `FeatureTaskRuntimeRunInvariantsRead` in `ports/taskruntime/model`, with nested `Read(invariants)` and `Rejected(reason)` data variants. Keep ports declarative, without a default implementation or top-level singleton. In infra workflow `infrastructure/workflow/featuretask/FileSystemFeatureTaskRuntimeRunInvariantsSource.kt`, return `Rejected` with today's reasons for the three path-authorization failures, the owned manifest-schema rejection in `requireSelectedBundleEntry`, and invalid run-invariants discovered after acceptance-list collection. Reuse or add the shared run-invariants violation helper beside domain `workflow/taskruntime/model/handoff/task/FeatureTaskRuntimeHandoffModels.kt`. Preserve symlink containment and selected-bundle authorization. Narrow path parsing to `InvalidPathException`; propagate I/O and unrelated coded failures. Any coded catch must match only the existing owned code or family predicate.

   Update engine `featuretask/phaserun/PhaseRunIntakeResolver.kt` to map `Rejected` to null. Update `goalrunner/planning/outcome/GoalPlanningSubtaskPlanProduction.kt` and `goalrunner/planning/context/GoalPlanningSharedPreplanProduction.kt` to branch on the result and retain their existing stop reasons, including `run-invariants could not be read: <reason>`. Adapt shared-preplan production's existing whole-production `Result` boundary. Retain cooperative cancellation and interruption rethrow in touched broad wrappers; do not add `runCatching`. Update engine test implementations in `featuretask/phaserun/PhaseRunTestSupport.kt`, `featuretask/runner/FeatureTaskRuntimeRunnerTestSupport.kt`, `goalrunner/execution/core/GoalRunnerTestFactory.kt`, and `goalrunner/planning/sweep/GoalPlanningSweepTestFixtures.kt`, plus infra workflow `featuretask/FileSystemFeatureTaskRuntimeRunInvariantsSourceTest.kt`. Land every signature consumer together. Preserve existing rejection messages and change assertions only where the return contract requires it.

   The one new test obligation is an unreadable-spec rejection case in `PhaseRunIntakeResolverTest`. The realistic bug is that a source returns `Rejected` and intake proceeds or throws instead of returning null. Assert the intake outcome at that boundary. Reuse existing filesystem authorization and unusable-artifact cases, and existing goal-planning fixtures and producer coverage, during validate. New engine tests follow current production packages and relocated helpers.

4. Convert the complete review-diff parser and its dependent callers. This serves AC-001 and the Common Acceptance Criteria for unchanged messages and bytes. Touch application `reviewevidence/model/ReviewDiffEvidenceModels.kt`, `reviewevidence/ReviewDiffEvidenceParsing.kt`, `ReviewDiffEvidencePathParsing.kt`, and `ReviewDiffEvidenceGitPathDecoding.kt`. Use a small accepted/rejected parse value carrying the existing reason, because the engine degradation record needs it. Cover empty attributable records, malformed quotes and escapes, invalid UTF-8, missing prefixes, disagreeing path sources, ambiguous headers, non-repository paths, oversized hunk coordinates and hunk construction. Use nullable integer parsing, the existing repository-path violation helper and ordered hunk validation. Catch only `CharacterCodingException` at UTF-8 decoding. The throwing public parser delegates to the same non-throwing path for edge-only callers, preserving its message and edge exception classification.

   Update engine `featuretask/review/core/FeatureTaskRuntimeSharedReviewEvidenceResolver.kt` to branch on rejection and record the same `seam=shared_review_evidence_parse` text and reason. Pass the reason explicitly to degradation recording rather than manufacturing an exception. Convert dependent `runCatching` uses in `parseAttributableReviewDiffEvidence`, `SharedReviewEvidenceResolution.derivationOf`, and application `review/packet/ReviewHunkStoreIndexing.kt` functions `rawRecord` and `hunkBodyIn`. Preserve their absence and unreadable policies. Reuse application `review/snapshot/ReviewDiffEvidenceTest.kt` for malformed and conflicting Git path cases during validate. Assumption for implement to confirm: the digest establishes existing malformed-path coverage but does not enumerate every parser test; retain that suite as the evidence and follow the existing test obligations rather than creating duplicate invalid-input cases.

5. Remove the remaining timestamp and JSON defect handling. This serves AC-001 and the Common Acceptance Criteria for timestamp spelling and rejection text. In ports `workflow/model/WorkflowArtifactTimestampMapping.kt`, make `preserveTimestampText` parse both values through `parsePersistedInstantOrNull`. Retain the original source spelling when both parse to equal instants; otherwise retain `Workflow artifact contains an invalid timestamp.` In contracts `JsonCodec.kt`, remove only the redundant IAE arm in `parseJsonElementStrict` and retain `SerializationException` handling. Do not widen its handled failure set. Add no test for library catch removal or trivial glue. Validate with retained timestamp and codec coverage from the full gate; their exact test names are not supplied by the digest and implement must confirm the existing coverage without treating that omission as a planning blocker.

6. Complete the scoped implementation and hand off evidence to the owning phases. This serves all three ACs and the Common Acceptance Criteria. Implement removes the forbidden catches and type checks in engine, ports and contracts, including any touched input wrappers, while retaining true defect invariants and unrelated failure propagation. Audit inspects each criterion and verifies message preservation against the changed paths. Preserve every named `ParseBoundarySite` in runtime-core `src/repoTest/kotlin/skillbill/architecture/PrincipleEnforcementInventory.kt`; keep the function name or update its location without dropping coverage. No architecture baseline, suppression or exemption may widen. Apply A1, A2, A4, A7, A10 and A11 to helper placement and dependency direction, and G7 to guards. Keep domain free of `java.nio` and ports imports, files below 1,200 lines, existing wire-key ownership, and authored Kotlin free of forbidden comments. Use small helpers within the existing detekt limits rather than suppressions. Remove a custom throwable and shrink its baseline only if it loses its final production reader.

   Buildability proof belongs to build. Validate owns execution of the behavioral suites above, the pack's declared collect-all full gate and cache-bypassing counterpart when needed, detekt, runtime-core repoTest and `scripts/validate_agent_configs`. Retain `TypedParseBoundaryArchitectureTest`, `FailureCodeTotalityArchitectureTest`, `PortsDeclarationArchitectureTest`, `WireVocabularyArchitectureTest`, comment/KDoc, file-size and module/package guards. Spotless validation uses a plain clone under the supplied parent constraint. No command syntax is inferred from historical line numbers. Validation must use the actual declared gate and preserve its required checks. Review and validation may repair production wiring, test setup, formatting and lint while preserving behavior, assertions and architecture rules.

Planning runs no build, test or validation command. Implement also runs none under the existing Validation Strategy. No migration, feature flag, public workflow command, skill-source change or install refresh is required. Runtime preparation owns branch selection; this phase neither verifies nor changes the digest's checkout state. History, commit/push and PR work remain with their owning phases. There are no unresolved product decisions or dependency prerequisites.
