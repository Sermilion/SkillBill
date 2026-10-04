## [2026-10-04] Carry the original manifest failure through validation rejection
Context: SKILL-399 subtask 8 removes caught-exception reason and failureCode reads from schema validation while preserving the messages emitted by requireAccepted.
Decision: Rejected carries an optional SkillBillRuntimeException. Schema validation stores the original failure and its full message; requireAccepted rethrows it when present and constructs a coded failure otherwise.
Reason: Rebuilding from the full message would wrap the schema-validation prefix again and lose the original cause. The settled plan confirms that discovery, both file-write paths and purge use the same validation and acceptance label, so rethrowing preserves their message text.
Alternatives considered: Recover the raw reason from exception properties or parse the message. The plan removes those property reads and preserves the original failure instead.

## [2026-09-24] Validator contracts stay outside domain while artifact families stay typed (SKILL-372)
Context: Domain decoders need to reject malformed durable artifacts without depending on schema-validator ports.
Decision: Validator interfaces and wire carriers live in `runtime-ports`; runtime-domain owns pure artifact decoding and typed accessors, and adapters validate encoded payloads at every durable read and write seam.
Reason: This keeps domain rules dependency-free while preserving loud schema rejection and one owner for each artifact family.
Alternatives considered: Keeping validators in domain (rejected: it couples pure rules to adapter contracts); validating only service-mediated writes (rejected: direct engine and SQLite saves would bypass the boundary).
Revisit when: A validator contract no longer crosses a module boundary or an artifact family becomes an open extension surface.

## [2026-09-17] Remaining multi-file families after merge-count shrink (SKILL-351 subtask 3)

Context: Subtask 3 merges count-driven split files where ceilings allow and documents responsibility-based splits that remain.

Decision: `FeatureTaskRuntimeProjectionCanonicalizer` is one implementation file plus `FeatureTaskRuntimeProjectionCanonicalizationTypes.kt` for shared types and key sets. `GoalObservabilityParsing` is a single file. `FeatureTaskRuntimeHandoffProjection*` stays split by lifecycle: core projection model (`model/FeatureTaskRuntimeHandoffProjection*.kt`), envelope wire (`FeatureTaskRuntimeHandoffProjectionEnvelopeWire.kt`), validator and field resolution (`FeatureTaskRuntimeHandoffProjectionValidator.kt`, `FeatureTaskRuntimeHandoffProjectionFieldResolver.kt`, `FeatureTaskRuntimeHandoffProjectionValueBuilder.kt`, `FeatureTaskRuntimeHandoffProjectionFinalization.kt`, `FeatureTaskRuntimeHandoffProjectionDeclarationChecks.kt`, `FeatureTaskRuntimeHandoffProjectionSourceFields.kt`) because validator plus builder paths exceed a single file without spillover suffixes. `WorkflowEngine*` splits snapshot codec (`WorkflowEngineSnapshotCodec.kt`, `WorkflowEngineSnapshotCodecDurable.kt`), continuation assembly (`WorkflowEngineContinuationAssembly.kt`, `WorkflowEngineContinuationCompact.kt`, `WorkflowEngineContinuationPrompts.kt`), validation (`WorkflowEngineValidation.kt`), and numeric coercion (`WorkflowEngineNumericCoercion.kt`) around distinct persistence and continuation responsibilities. `FeatureTaskRuntimePhaseWorkflow*` keeps `FeatureTaskRuntimePhaseWorkflowDefinition.kt` as the graph owner with `FeatureTaskRuntimePhaseWorkflowGraph.kt`, `FeatureTaskRuntimePhaseWorkflowTransitions.kt`, `FeatureTaskRuntimePhaseWorkflowQueries.kt`, and `FeatureTaskRuntimePhaseWorkflowProjectionDeclarations.kt` as named graph, transition, query, and projection-declaration units.

Evidence: `ProductionFileLineCeilingArchitectureTest`, merged canonicalizer and goal observability units in this subtask.

Revisit when: Any family grows past ceilings without a clearer responsibility boundary.

## [2026-09-16] Validator port home, wire artifact collapse, version ownership, wrapper policy (SKILL-351 subtask 2)

Context: Subtask 2 restores ownership and typed boundaries across validator ports, learnings session wiring, workflow continuation typing, and wire-key governance.

Decision: (a) Feature-task runtime JSON-schema validator ports live in `runtime-domain` under `skillbill.workflow.taskruntime` and `skillbill.workflow.decomposition`; concrete Draft 2020-12 validators stay in `runtime-infra/fs`. (b) The identical-shape task-runtime and goal wire validators use `FeatureTaskRuntimeWireArtifactValidator` keyed by `FeatureTaskRuntimeWireArtifactKind`; infra dispatches through `FeatureTaskRuntimeWireArtifactValidatorAdapter`. (c) `SkillBillVersion` and `skillbill/version.properties` live in `runtime-core`; the packaged resource read remains the single ambient seam documented in `ARCHITECTURE.md`. (d) Typed boundary wrappers move out of the monolithic `WorkflowBoundaryCollections.kt` into owning area `model` packages (`workflow/decomposition`, `telemetry`, `review/context`, `workflow/engine`); delete the aggregate file rather than retaining a re-export hub. (e) `WorkflowDefinition.usesFeatureTaskRuntimeContinuation` replaces engine imports of `workflow.taskruntime` for continuation branching. (f) Governed goal-continuation artifact keys declare once in `FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys`; `WireVocabularyGovernedSeamInventory` scans the encode/decode pair.

Evidence: `../../../.feature-specs/done/SKILL-351-runtime-domain-boundaries-and-simplicity`, `FeatureTaskRuntimeWireArtifactValidatorAdapter`, `LearningSessionWire.kt`, `WorkflowEngineBoundaryMaps.kt`, `WireVocabularyArchitectureTest`.

Revisit when: Subtask 3 shrinks remaining surface and merge-count split units land.

## [2026-09-16] Decomposition manifest validator port (SKILL-52.3)

Context: Decomposition manifest schema validation previously lived only in infra; application reached it through ad hoc imports.

Decision: `DecompositionManifestValidator` in `runtime-domain` is the domain-owned port; `DecompositionManifestValidatorAdapter` in `runtime-infra/fs` runs JSON Schema plus coherence checks and throws `InvalidDecompositionManifestSchemaError` on violation.

Evidence: `DecompositionManifestValidatorAdapter`, decomposition manifest rejection tests in `runtime-infra/fs`.

Revisit when: Manifest schema or repair orchestration changes ownership again.
Superseded by: Validator contracts stay outside domain while artifact families stay typed (2026-09-24)

## [2026-09-16] Unified durable artifact map reader and lenient workflow-step integers (SKILL-351 subtask 1)

Context: Durable artifact decoding duplicated nine map-field accessor families and fourteen integer coercions with divergent semantics. Workflow snapshot step decoding intentionally keeps a lenient integer coercion for legacy rows.

Decision: (c) Retain `AttemptLedgerWorkflowDecoding.asLenientIntOrNull` as the sole lenient integer coercion (Int, Number→toInt, String→toIntOrNull). All other durable artifact seams use `DurableArtifactMapReader` with `BigDecimal.longValueExact` / exact integral narrowing via `asExactIntOrNull` and `asExactLongOrNull` in `FeatureTaskRuntimePersistenceMapFields.kt`. Review-state and goal-observability field helpers delegate to the exact coercion helpers rather than maintaining parallel parsers.

Evidence: `FeatureTaskRuntimePersistenceMapFieldsTest`, `ReviewRunLaneSegmentAccountingJsonTest`, `TypedParseBoundaryArchitectureTest`.

Revisit when: SKILL-352 replaces any remaining engine-local readers or workflow status moves to closed enums at the engine boundary.
