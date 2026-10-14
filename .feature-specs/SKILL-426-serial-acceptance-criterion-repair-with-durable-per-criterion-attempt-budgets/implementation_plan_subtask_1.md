# Subtask 1 execution checklist and test obligations

## Boundary ownership

The executable spec is spec_subtask_1_durable-serial-repair.md. This companion is a checklist, not an additional consumer projection. The parent spec and manifest assign three subtasks and preserve all parent acceptance criteria. This checklist covers callable domain/persistence operations; production workflow activation belongs to subtask 2.

Use the following digest-backed owners; confirm proposed filenames during implement rather than treating them as existing source.

| Work | Existing owners and proposed additions |
| --- | --- |
| Authored identity/admission | runtime-domain workflow/taskruntime/model/audit/FeatureTaskRuntimeAcceptanceCriteria.kt; runtime-engine featuretask/slot/audit/AcceptanceAuditCatalog.kt; proposed domain AcceptanceCriterionRepairState.kt and admission/topological owner |
| Wire model | runtime-domain workflow/taskruntime/artifact/AcceptanceCriterionRepairStateWire.kt; runtime-contracts contracts/workflow/featuretask/AcceptanceCriterionRepairPayloadKeys.kt and owning versions |
| Artifact/schema admission | DurableWorkflowArtifactKeys/Family; FeatureTaskRuntimeWireArtifactKind; runtime-infra/contracts FeatureTaskRuntimeWireArtifactValidator, locator/FeatureTaskRuntimeSchemaPaths and build.gradle.kts governedResources |
| Transactions | runtime-engine featuretask/phase/record/FeatureTaskRuntimePhaseStateRecorder.kt; model/phase/FeatureTaskRuntimePhaseRecordRequest.kt; persist/FeatureTaskRuntimeWorkflowPersistence.kt and RuntimeOwnedPersistenceBoundary.kt; proposed adjacent AcceptanceCriterionRepairPersistence |
| Ownership | featuretask/lifecycle/core/FeatureTaskRuntimeWorkerCoordinator.kt; model/execution/AdmittedFeatureTaskRuntimeExecution.kt; lifecycle/execution/AdmittedExecutionRecheck.kt; runtime-ports workflow worker/state repositories through UnitOfWork |
| Adoption | featuretask/lifecycle/execution/FeatureTaskRuntimeExecutionAdmission.kt; slot/audit/planning/AuditPlanningExecutionPlanMapping.kt; raw records before AcceptanceAuditResumeRules normalization |

Path rows abbreviate the full runtime-kotlin module source roots established by the digest. They do not replace repository architecture ownership.

## Ordered checklist

1. Resolve branch/worktree policy through the first mutating runtime owner, preserve unrelated/incident evidence, and read required architecture/code/observability guidance. The manifest's main base is an assumption, not a new git observation.
2. Define typed catalog, graph, source/validation axes, queue/current position, revision, admission/tree identities, reservation/outcome and final proof. Own wire tokens/keys once. Keep original composite AC text and identity.
3. Implement pure admission, stable topology and serial transition policy including monotonic failure counters and final-only reopening. Bound display collections without deriving authority from pruned history.
4. Author four strict 0.1 Draft 2020-12 schemas first; register resources, kinds, families, validators, coherent typed rejection and version parity. Keep existing outer envelopes unless their strict shape actually changes.
5. Add narrow acquired-owner transaction requests/operations in existing engine persistence. Fence token/generation/lease and expected serial revision/AC/reservation/catalog/tree within the transaction, couple phase/ledger/workflow changes and return committed accepted facts only afterward. Subtask 2 connects those results to run-loop acceptance and success publication.
6. Make outcome replay once-only and projection recovery projection-only. Supply typed hydration that distinguishes present state, proven fresh discovery and adoption-required legacy absence. Preserve critical accepted identities, totals and closure/reopen provenance. Refuse ambiguity or capacity before effects, never truncate authoritative evidence to display bounds. Automatic startup/recovery callers belong to subtask 2.
7. Implement the explicit stopped-pristine adoption allowlist and provenance without activation. Preserve original descriptors/evidence and require fresh structured discovery before first reservation. Do not broaden semantic mapping based on topology.
8. Author the obligations below with existing domain/contract tests and production isolated SQLite/public-port fixtures. Deliver domain/persistence operations together, not a contract-only stub. Leave strategy activation to subtask 2.

## Legacy allowlist decision

The allowlist contains standalone revision 1, standalone revision 2, standalone revision 3, goal-child-build revision 1, goal-child-build revision 2, goal-child-build revision 3, goal-child-validate revision 1, goal-child-validate revision 2 and goal-child-validate revision 3 archived audit compositions. Each entry must match exact archived descriptor, historical effective-policy/cap digests, skeleton and expected source/catalog/checkpoint identity. Only current phase-output 0.7 or the existing explicit 0.6 migration is admissible. Existing compatible handoff/persistence versions are admitted through their exact validators, not coerced to new shapes.

The digest supplies modes/revisions and fixture owners, not literal historical digest values. Implement must bind each entry to the raw historical producer captures and immutable source identities recorded in serial-adoption/provenance.json. ExecutionPlanAdmissionFixture runs in each historical checkout; the current restoreDigests helper is not authority. If an entry cannot be proven, refuse that entry with typed provenance diagnostics; do not invent a digest or admit a wildcard. Physical stop, no prior source repair, no ambiguous aggregate repair history and no in-flight/no-change operator retry contradiction are mandatory. Revision 4 activation belongs to subtask 2.

## test_obligations

Each row names the realistic bug before any test is authored. Reuse existing tests when they already assert the outcome. Parameterize distinct refusal branches within one admission boundary suite, rather than duplicate literals or pin internal mapping structure.

| Named realistic bug | Observable assertion and fixture | Criteria |
| --- | --- | --- |
| Initial discovery omits one AC or splits a composite into independent budgets | Complete-catalog admission rejects omission/duplicate/unknown identity and retains one composite identity/original text; pure domain admission boundary | AC-001, AC-006; S1-001 |
| A source repair follows an invalid or validation-unavailable prerequisite | Graph admission rejects unknown/self/cycle/unavailable-validation edges and returns stable ordinal ordering for independent ties with admitted source-satisfied facts | AC-001; S1-002 |
| Success or reopening clears prior failures or advances two criteria | Pure transition outcomes retain totals/history and advance only current AC; empty queue/final-only reopen/exhausted refusal are observable results | AC-003, AC-005; S1-003 |
| A malformed or wrong-version artifact bypasses its canonical validator | Actual packaged validators reject unknown/missing keys, invalid tokens/versions and semantic coverage/reservation/graph incoherence; parity pins each schema ID/version | AC-008; S1-006 |
| A restarted database grants a fresh budget or repeats an outcome | Production SQLite reopen preserves queue/reservation/totals and identical outcome replay leaves revision/closure/count unchanged; conflicting digest refuses | AC-004; S1-004/S1-005 |
| A stale owner or stale AC/revision/reservation commits after takeover | Public-port production SQLite transactions reject stale token/generation/lease and each expected-state mismatch, leaving workflow/phase/ledger/serial evidence unchanged | AC-004; S1-004 |
| A late coupled write failure leaves a successful closure or phase completion published | Inject failure at the existing required persistence seam against production SQLite; observe rollback of serial/phase/ledger/workflow and no success publication | AC-004; S1-004/S1-005 |
| Projection regeneration repeats committed failure or closure | Commit succeeds then projection write fails; regeneration from committed revision restores projection without new reservation/outcome/counter/workflow mutation | AC-004; S1-005 |
| Unsafe legacy broad repair is adopted as a pristine zero-budget run | Exact stopped-pristine tuple succeeds with separate provenance/original evidence; prior repair/in-flight/contradictory/policy-digest mismatch or unstopped ownership refuses without evidence loss | AC-008; S1-007 |
| Required persistence swallows cancellation or returns an empty state after refusal | Existing required-write cancellation/refusal regressions retain propagation and bounded attributable failure facts through the new serial seam | AC-004, AC-010; S1-008 |

SQLite fixtures are SqliteTestDatabaseFixtures.establishTemporarySchemaReadiness, ensureTestDatabase and sqliteSessionFactoryForTests with explicit disposable home/database/environment and injected Clock. Consumer tests use public ports, not sqlite.core. Existing WorkflowPersistenceSeamTest, ExecutionPlanWriteGuardTest, AuditPlanningExecutionPlanMappingTest and its admission fixture are starting points, not a mandate to duplicate their tests.

## Coverage and handoff

S1-001/S1-002 cover the catalog/dependency admission operations of AC-001. S1-003 defines accounting/final/source-validation operations for AC-003/AC-005/AC-006. S1-004/S1-005 cover AC-004's authoritative transaction, hydration, replay and projection-recovery operations. S1-006/S1-007 cover artifact admission and legacy-adoption operations of AC-008. S1-008 carries their boundary AC-009 tests and operation-level AC-010 architecture/diagnostics. Subtask 2 supplies production execution callers, handoff delivery, commit-gated run-loop publication and completion gates. These parent references describe contributions, not complete parent acceptance in this subtask. Review approval, command execution evidence, commits and history belong to their runtime phases, not this checklist.

## Validation ownership

No execution here. tests_executed is empty. Implement authors tests; audit reads source. Only build owns the pack build command. Validate discovers and runs required full repository/pack checks, packaged schema/parity, architecture/repo and isolated SQLite regressions, then repairs failures. Required architecture guards and real past regressions cannot be dropped for test minimalism. Runtime owns later exact-tree review/history/commit/PR/monitor.


## Direct repair evidence, 2026-10-14

The earlier claim that S1-001 through S1-008 were all satisfied is withdrawn. The former allowlist reconstruction did not prove the exact historical standalone shape. Subtask acceptance requires a fresh runtime audit against the corrected ownership boundary. Strategy revision 4 and executor activation remain in subtask 2; reporting remains in subtask 3.

The nine descriptors under `runtime-engine/src/test/resources/featuretask/serial-adoption/` now come from the unnormalized production producers at the immutable commits in `provenance.json`. The checked-in `capture.py` rebuilt all three commits in disposable checkouts and matched every descriptor's SHA-256 identity. It uses neither normalized archives nor current-policy reconstruction. Original workflow history is separate evidence and remains mandatory at adoption.

Direct repair also addresses prior-owner stop proof, explicit 0.6 output admission with original-version preservation, projection locking, stale/conflicting projection refusal and corrupt-cache recovery. Focused adoption, persistence and projection tests passed. Full repository checks belong to the direct repair's validation evidence; their success does not mark any criterion accepted or reset runtime budgets. The runtime owns the next audit, durable subtask completion and commits.
