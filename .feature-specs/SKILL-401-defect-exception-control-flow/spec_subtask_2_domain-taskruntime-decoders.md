# SKILL-401 Subtask 2 - domain-taskruntime-decoders

Parent spec: [.feature-specs/SKILL-401-defect-exception-control-flow/spec.md](./spec.md)
Issue key: SKILL-401

## Scope

Remove the IAE/ISE control flow at the 16 runtime-domain sites under `workflow/taskruntime/model/` (kinds A and D).

**Method for each site.** Replace the `try`/`catch (IllegalArgumentException)` with explicit checks before construction:

1. Call `violation(...)` and the `…OrNull` helpers.
2. On failure, throw the schema failure the catch used to throw, with byte-identical text (ground rule 2).
3. Then construct the value. Its `require` is now an invariant that no decoded input can break.

Where the body builds a value only from runtime-produced data (a kind-D site), drop the catch and keep the `require`. For example, check the `fromMeasurements`-style factory at `FeatureTaskRuntimeValidationGateExecutionEvidence.kt:87`.

Sites, all under `runtime-domain/.../`:

- `workflow/taskruntime/model/phase/FeatureTaskRuntimePhaseLedgerPersistenceModels.kt:170`. Includes `parsePersistedInstant` at `:153` and `FeatureTaskRuntimePhaseExecutionOrigin.fromWireValue`.
- `workflow/taskruntime/model/phase/FeatureTaskRuntimePhaseRecord.kt:218`. Includes `parsePersistedInstant` at `:183` and `:184`. Failures still go to `incompatiblePhaseRecord()`.
- `workflow/taskruntime/model/core/FeatureTaskRuntimeResolvedBranch.kt:70`
- `workflow/taskruntime/model/handoff/task/FeatureTaskRuntimeHandoffEnvelope.kt:62`
- `workflow/taskruntime/model/audit/FeatureTaskRuntimeQuarantineModels.kt:130`
- `workflow/taskruntime/model/persistence/FeatureTaskRuntimeRunInvariantsPersistence.kt:48`, `:93` and `:145`. `:145` uses `fromWireOrNull`. Its message `"... must be one of auto, inline, delegated."` is unchanged.
- `workflow/taskruntime/model/persistence/FeatureTaskRuntimeCheckpointIdentityModels.kt:154`
- `workflow/taskruntime/model/persistence/FeatureTaskRuntimeGoalContinuationArtifact.kt:145`, via `ValidationDepth.fromWireOrNull`.
- `workflow/taskruntime/model/validation/FeatureTaskRuntimeValidationGateProgressModels.kt:173`, `:211`
- `workflow/taskruntime/model/validation/FeatureTaskRuntimeReadinessEvidence.kt:181`
- `workflow/taskruntime/model/validation/FeatureTaskRuntimeValidationGateExecutionEvidence.kt:87`, `:117`, `:137`
- `workflow/taskruntime/model/validation/FeatureTaskRuntimeValidationEvidence.kt:117`

## Acceptance Criteria

1. No main source under `runtime-domain/.../workflow/taskruntime/` catches or `is`-checks `IllegalArgumentException` or `IllegalStateException`.
2. Each decoder throws the same schema failure, with byte-identical text, from an explicit validator check; phase-record failures still go to `incompatiblePhaseRecord()`.
3. Kind-D arms are dropped and their `require`s kept as invariants.

## Non-Goals

The `goalrunner/`, `review/` and `workflow/model/` decoders (subtask 1).

## Test obligations

None beyond the shared test rules: add an invalid-input test only for a validator whose invalid branch no existing decoder test drives.

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

.feature-specs/SKILL-401-defect-exception-control-flow/spec_subtask_2_domain-taskruntime-decoders.md

## Implementation Details

This plan uses the upstream preplan digest as its only repository evidence. Historical line numbers in Scope are anchors, not instructions to rediscover the census. Paths below are relative to `runtime-kotlin/`. Domain production files live under `runtime-domain/src/main/kotlin/skillbill/`; domain tests live under `runtime-domain/src/test/kotlin/skillbill/`. The subtask has no dependencies and must remain independently implementable.

### Ordered implementation tasks

1. Establish shared validator ownership and preserve rejection order. Serves AC-001 and AC-002, and supports AC-003.

   In `agentaddon/model/AgentAddonModels.kt`, retain existing `PersistedAgentAddonSelectionEntry.violation(slug, sourceIdentity, contentSha256)` and `AgentAddonSelection.violation(entries)`, or add them if absent. Entry validation orders slug, content digest, then source identity; selection validation checks unique slugs. Both model invariants call the same helper. In `workflow/taskruntime/model/handoff/task/FeatureTaskRuntimeHandoffModels.kt`, expose the ordered constraints through `FeatureTaskRuntimeRunInvariants.violation`. For every model named in the tasks below, put a pure nullable violation helper beside its owner and have its existing `require` use that helper. Keep one source for every reason, including `Failed requirement.` for any unlabelled invariant. Read persisted fields into locals, validate in the former execution order, and construct only after acceptance. Do not move forbidden catches into helpers.

   Reuse the already-present `CodeReviewExecutionMode.fromWireOrNull`, `ValidationDepth.fromWireOrNull`, and `parsePersistedInstantOrNull`. Preserve retired `build_only` acceptance as `FULL`. Assumption for implement to confirm while editing: existing durable readers and numeric helpers can remain unchanged while locals and explicit checks replace the catches. Preserve exact numeric narrowing and existing reader failure precedence.

   Test obligations for later validation are covered by tasks 2 through 6. Add no helper-only tests that duplicate an existing decoder rejection case.

2. Convert phase ledger and phase-record reconstruction. Serves AC-001 and AC-002.

   Touch `workflow/taskruntime/model/phase/FeatureTaskRuntimePhaseLedgerPersistenceModels.kt` and `FeatureTaskRuntimePhaseRecord.kt`. Validate ledger sequence, phase id, attempt count, optional fix-loop iteration, and optional edge iteration before constructing `FeatureTaskRuntimePhaseLedgerEntry`. Parse timestamps through `parsePersistedInstantOrNull` and retain `Feature-task-runtime phase ledger entry is invalid.` `FeatureTaskRuntimePhaseExecutionOrigin.fromWireValue` already throws a coded workflow-state failure; preserve its classification and do not treat it as an IAE source.

   For phase records, retain `requireCompatibleShape` and route rejected nullable timestamps, launch pairs, review-only fields, attempt values, and duration values through `incompatiblePhaseRecord()`. An incompatible durable record must never become an absent record. Preserve timestamp spelling and optional-field behavior.

   Later validate reuses `workflow/taskruntime/model/persistence/FeatureTaskRuntimePersistenceModelsTest.kt`. The realistic bugs to cover, only if current tests do not already cover them, are a malformed ledger timestamp escaping as a defect instead of the existing schema failure, and an incoherent phase record being admitted or treated as absent. Assert the public failure classification and exact text through the decoder.

3. Convert resolved-branch and complete handoff-envelope decoding. Serves AC-001 and AC-002.

   Touch `workflow/taskruntime/model/core/FeatureTaskRuntimeResolvedBranch.kt`, `core/FeatureTaskRuntimeRepositoryCheckpoint.kt`, `handoff/task/FeatureTaskRuntimeHandoffEnvelope.kt`, and `handoff/task/FeatureTaskRuntimeHandoffProjectionValue.kt`. Expose branch, review-base SHA, owned-path lists, and history-path lists as ordered violations. Preserve `Feature-task-runtime resolved-branch artifact is invalid.`

   Validate nested checkpoints and projection fields before constructing them. Check bounded nonblank checkpoint fingerprints, nonblank owned paths, projection-name patterns, and forbidden raw-context names. Then validate the envelope's nonblank consumer and version and unique projection names. Keep `invalidFeatureTaskRuntimePhaseHandoffSchema("<wire>", reason, ...)` framing. Existing coded enum and source-reference failures retain their codes and handling; unrelated failures propagate.

   Later validate reuses domain `workflow/taskruntime/model/handoff/task/FeatureTaskRuntimeHandoffFoundationModelsTest.kt` and engine `featuretask/persist/FeatureTaskRuntimeHandoffEnvelopeArtifactDecodersTest.kt`. The realistic uncovered bug would be malformed nested handoff data bypassing contextual schema rejection or changing the winning reason. Extend an existing boundary test only for a branch not already driven by those suites; keep valid serialization and omission assertions.

4. Convert quarantine and checkpoint-identity durable entries. Serves AC-001 and AC-002.

   Touch `workflow/taskruntime/model/audit/FeatureTaskRuntimeQuarantineModels.kt` and `persistence/FeatureTaskRuntimeCheckpointIdentityModels.kt`. Validate quarantine constraints in their original order, including positive iterations, declared rejection class, diagnostic identity XOR degradation, nonnegative byte size, and digest shape. Preserve `Feature-task-runtime quarantine entry is malformed: <violation>`.

   Validate checkpoint identity in its original order, including the derived checkpoint ref, bounded ref, subtask identity, SHA and digest fields, and optional parent and loop identity. Preserve `Feature-task-runtime checkpoint-identity entry is malformed: <violation>` and the distinct unsupported-version code. Keep `isInvalidWorkflowStateFailure()` guards and propagate unrelated coded failures, as required by the checkpoint-family decisions in the digest.

   Later validate reuses `workflow/taskruntime/model/audit/FeatureTaskRuntimeQuarantineModelsTest.kt` and `workflow/taskruntime/model/persistence/FeatureTaskRuntimeCheckpointIdentityModelsTest.kt`. Add coverage only if missing for the realistic bugs of accepting a diagnostic/degradation conflict or misclassifying an unsupported checkpoint version as an ordinary malformed entry. Assert the decoder's existing code and exact message, not helper structure.

5. Convert run-invariants, add-on selections, and goal-continuation artifacts. Serves AC-001 and AC-002.

   Touch `workflow/taskruntime/model/persistence/FeatureTaskRuntimeRunInvariantsPersistence.kt`, `FeatureTaskRuntimeGoalContinuationArtifact.kt`, and the shared owners in task 1. Validate add-on entries before constructing them and duplicate slugs before constructing the selection wherever these decoding paths are caller-handled. Use the run-invariants violation helper and nullable execution-mode parser. Preserve the run-invariants and add-on-selection prefixes and the literal `must be one of auto, inline, delegated.` message.

   Decode optional continuation depth with `ValidationDepth.fromWireOrNull` and retain `Goal-continuation artifact validation_depth is invalid.` Preserve absence separately from an invalid supplied value, contract versions, map ordering, and optional omissions.

   Later validate reuses `workflow/taskruntime/model/persistence/FeatureTaskRuntimePersistenceModelsTest.kt` and handoff foundation coverage. A new test earns its cost only if the suites lack a decoder case rejecting duplicate persisted add-on slugs, invalid execution mode, or invalid supplied validation depth with unchanged framing. Keep existing compatibility coverage for retired depth values; do not add a sibling test with another literal for the same rejection branch.

6. Convert validation progress, readiness, and evidence reconstruction. Serves AC-001 and AC-002.

   Touch `workflow/taskruntime/model/validation/FeatureTaskRuntimeValidationGateProgressModels.kt`, `FeatureTaskRuntimeReadinessEvidence.kt`, `FeatureTaskRuntimeValidationEvidence.kt`, and `FeatureTaskRuntimeValidationGateExecutionEvidence.kt`.

   Validate every gate run record before construction, then validate aggregate progress. Preserve the outer incoherence prefix and each indexed `gate_runs[$index]` prefix. Retain explicit `executed_checks` presence, including an empty list, command/exit/checkpoint fields, outcome relationships, and aggregate accounting. Readiness decoding validates nested check results and final evidence, retaining count bounds, unique selected checks and results, and source-labelled failure messages. Validation evidence retains its existing explicit blank-command rejection and validates empty or oversized result lists before construction. Preserve `onInvalid: (String) -> SkillBillRuntimeException`.

   Execution-evidence artifact decoding uses nullable outcome and cache parsing, validates run records and aggregate checks, then validates the final evidence. Preserve `onInvalid: (String, SkillBillRuntimeException) -> SkillBillRuntimeException` and the code-checked workflow-state remapping around nested gate records. Do not broaden the handled runtime-failure set.

   The digest overrides the historical kind-D example for `fromGateMeasurements(measurements: List<FeatureTaskRuntimeValidationGateRunRecord>)`. Its build-output caller reaches it before checkpoint comparison, and invalid measurements currently receive a coded rejection. Validate those measurements explicitly and retain that rejection. Do not delete input rejection on the assumption that all runtime-produced measurements are valid.

   Later validate reuses domain `workflow/taskruntime/model/validation/FeatureTaskRuntimeValidationEvidenceTest.kt`, `FeatureTaskRuntimeValidationGateExecutionEvidenceTest.kt`, and engine `featuretask/validation/FeatureTaskRuntimeValidationEvidenceSettlementTest.kt`, together with persistence coverage. Existing missing-fact, invalid-outcome, negative-count, inconsistent-aggregate, and durable-resume cases are the primary evidence. Extend existing suites only for uncovered bugs such as omitted `executed_checks` being confused with an explicitly empty list, or invalid measurements escaping through the build-output factory. Preserve expected text and durable assertions.

7. Remove remaining defect-only arms and preserve architecture coverage. Serves AC-001, AC-002, and AC-003.

   Within the digest's 16 taskruntime sites, remove IAE/ISE catch and type-check control flow after input paths have explicit validation. Drop only genuinely defect-only arms and keep their `require` invariants. The digest does not identify another concrete kind-D site beyond the superseded measurement example. Implement must classify any remaining arm from its reachable sources while editing; this is not permission to remove required coded input rejection. For a validator changed here, convert any caller-handled input path reached in the touched functions to the same non-throwing helper. Do not start a repository-wide `runCatching` sweep or add a new `runCatching`.

   Keep codec signatures, function names registered as `ParseBoundarySite`, failure factories, wire keys, field order, exact numeric behavior, schema versions, and emitted bytes. Preserve cancellation and interruption propagation. If a registered function must move, update its inventory location without dropping the entry. Domain helpers remain pure and import neither ports nor `java.nio`. Add no throwable, typealias, result library, collaborator bag, runner branch, suppression, or widened architecture baseline. Keep model ownership and rules A1, A2, A4, A7, A10, A11, and G7. Follow existing wire-key owners, the 1,200-line ceiling, comment/KDoc rules, and detekt limits through small owner-local helpers.

   Kind-D removals need no new tests. Later audit inspects all three criteria and inventories retained regression evidence. Later validate runs the named behavioral suites, detekt, `TypedParseBoundaryArchitectureTest`, `FailureCodeTotalityArchitectureTest`, wire-vocabulary, comment/KDoc, file-size, and module/package guards as part of the repository's actual full gate, plus `scripts/validate_agent_configs` required by CI. No guard or parity coverage may be weakened.

### Phase ownership and settled assumptions

Plan changes only this sub-spec. Implement produces the repository end states and any justified missing regression tests; it runs no build or tests. Audit inspects those end states. Build proof belongs only to the build phase if the runtime schedules it. Validate owns test execution, detekt, formatting checks, repoTest, and the full pack gate, and may repair production wiring, test setup, formatting, or lint while preserving behavior and architecture rules. The exact gate argv is intentionally left to validate because the digest identifies the authoritative pack declaration without supplying its argv. Spotless validation retains the parent's plain-clone constraint. No validation claim is made by this plan.

No new decomposition, migration, feature flag, public command, skill-source change, install refresh, or dependency work is required. Branch preparation, history, review, commit/push, and PR work remain with the runtime and their owning phases. The assigned Scope, acceptance criteria, dependencies, Validation Strategy, and Next Path remain unchanged. The explicit treatment of `fromGateMeasurements` resolves the stale example in Scope using the upstream digest; it changes no acceptance criterion.

The digest supplies no exhaustive per-assertion messages or test-case inventory. Implement must preserve the text and order found in each owning declaration rather than invent new reasons, and add only a boundary regression test for a concrete uncovered rejection behavior named above. Existing tests are assumed to cover the cases the digest explicitly identifies. Confirm coverage during implementation without treating unexecuted tests as proof. There is no missing product decision or planning blocker.
