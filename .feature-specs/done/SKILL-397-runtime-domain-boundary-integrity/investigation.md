# SKILL-397 runtime-domain architecture investigation

## Judgment

The domain's core is sound:
- no ambient effects;
- no DI annotations;
- no java.nio or ports imports;
- the WorkflowEngine is constructor-free and always validates;
- the snapshot is typed.

The regression is guard integrity. SKILL-372 (#403, 3973aa265) closed its criteria by moving the problem instead of fixing it:
- it baselined 4 package SCCs where its subtask 3 AC2 required an empty baseline;
- it hardcoded two package-specific sibling ceilings equal to the current file counts;
- it added a curated FQN raw-map allow-list, which ARCHITECTURE.md Boundary Rule 11 forbids;
- it replaced validator ports in domain with a fun interface and function types;
- it renamed a Noop fixture to Accepting*.

An older evasion predates SKILL-372: 33 public wrappers erase raw maps to `Any`, so the raw-map scanner cannot see them.

Separately, the DecompositionManifest aggregate's pure transitions are spread across engine and application, where they restate status and action wire literals.

The fixes remove layers. No module, framework, dependency bag or architecture-test class is added, and no baseline grows.

## Method and baseline

- Baseline: ae23f4f28 (HEAD unchanged at final recheck, 2026-09-30).
- Context read: AGENTS.md; ARCHITECTURE.md (Design Principles, Gradle Modules, Package Ownership, Guardrails); docs/code-principles.md; runtime-domain agent/decisions.md and history.md.
- Prior investigation: SKILL-372 (investigation, spec, subtask 3), read in full.
- Sibling bundles: SKILL-386, 387, 388, 389, 392, 393 and 395.
- Census tools: grep, cut, sort and uniq only. The sandbox blocked python, so there was no scripted Tarjan run (see Limits).
- No delegated review.

## Census

| Metric | Value |
|---|---|
| main | 369 files, 31,489 lines |
| test | 128 files, 20,188 lines |
| testFixtures | 1 file, 12 lines |
| Packages (main) | 86; about 20 single-file |
| Largest packages | workflow.model.goalreview 18, review.model 17, goalrunner 15, goalrunner.model 12, taskruntime.model.core 11 |
| Top-level declarations (first token) | about 988 public, 324 internal, 297 private |
| Typealiases | 0 |
| Interfaces | 13 sealed result sums; 2 fun interfaces (RequiredArtifactPresenceResolver with 2 impls; FeatureTaskRuntimeWireArtifactValidation, a validator seam) |
| Ambient effects (Instant.now, Clock.system, System.*, UUID, Thread, @Inject) | 0 |
| runCatching | 8 (4 exact-numeric in DurableArtifactMapReader.kt:157-160, 2 `fromWire` swallows in ReviewSpecAdjudicationAdmission.kt:51,82, 2 timestamp/date parses) |
| Public top-level `): Any` functions | domain 33, application 0, ports 0 |
| Internal *_ARTIFACT_KEY constants | 40; consumed only in domain main plus 6 domain test files |
| Cycle baseline | baselines/runtime-domain-package-cycle-baseline.txt: 4 SCC rows (5, 2, 17 and 3 packages); empty before 3973aa265 |

Build edges:
- `api(:runtime-domain)` from application, ports and engine (RuntimeModuleCatalog :33, :59, :157). SKILL-388 confirmed these are required.
- `implementation` from the other consumers.
- `testFixtures(project(:runtime-domain))` consumed by application, engine and core.
- sqlite and skills use `testFixturesImplementation(project(:runtime-domain))`, which points at main.

## SKILL-372 landing check

| Fix | State |
|---|---|
| F-001 typed snapshot | Landed |
| F-003 always-validate engine | Landed |
| F-002 lenient decoders | Landed |
| F-008 coercions | Landed |
| F-010 SkillKind, F-011 aliases, F-014 resource | Landed |
| F-004 cycles | Not landed: 4 SCCs baselined (violates subtask 3 AC2 and the shrink-only baseline rule) |
| F-007 validators | Alias-evaded: a fun interface, 5 function-typed validators, and the renamed Noop fixture (AC9) |
| F-012 packages | Not landed: `taskruntime.model.persistence.task.runtime.{checkpoint, goal, implementation, prior, run, store}` holds 11 files in 6 leaves under empty levels; `handoff.envelope` has 1 file; `repair.task` has 4 |
| Sibling guard | Evaded: hardcoded ceilings at ArchitectureScanSupport.kt:403-404 and :435-436 |
| F-013 visibility | Not verified; counts are by first token, not by FQN (see Limits) |

## Principle table

| Principle | State |
|---|---|
| Dependency direction | Clean at the import level. Cycles inside the module (F-001). |
| Ports and adapters | Violated: a validator seam inside domain (F-005). |
| Rich domain | Violated: aggregate transitions live in engine and application (F-007). |
| Wire vocabulary | Violated: restated status and action literals (F-007); `Any` erasure (F-004). |
| Guards honest | Violated: ceilings (F-002), FQN allow-list (F-006), baseline rows (F-001). |
| YAGNI | One-file testFixtures set, three sha256 helpers, empty package levels, two identical reset bodies. |

## Checklist

1. **Dependency direction.** No java.nio, ports, io, jackson, kotlinx or yaml imports. Package SCCs are listed in F-001.
2. **Inbound adapters.** Not applicable to domain.
3. **Outbound ports.** Domain holds no port. One validator seam survives as a fun interface (F-005).
4. **Domain richness.** Manifest and goal-runner transitions sit outside domain (F-007). The add-on selection decoder is duplicated with divergent contracts (F-008).
5. **Composition.** Clean: no @Inject, no second roots. RequiredArtifactPresenceResolver is a real 2-implementation strategy.
6. **Entry-point leakage.** Clean.
7. **Ambient effects.** Clean: 0 hits. The ambient-clock, ambient-environment and inject-defaults baselines are empty.
8. **State.** Immutable data copies. The transition logic is misplaced (F-007).
9. **Error model.** `require` in RuntimeOwnedReviewMode.parse (a SKILL-392 non-goal) and in the FeatureTaskRuntimeGoalContinuationArtifact init (61-69). Two `runCatching{fromWire}.getOrNull()` calls swallow unknown values (ReviewSpecAdjudicationAdmission.kt:51,82). Recorded, not fixed.
10. **Cohesion.** Artifact-key constants are scattered across 12 packages but registered centrally, which drives the cycles (F-001a). There are three sha256 helpers (F-001d).
11. **YAGNI.** Listed in the over-engineering register.
12. **Naming and packages.** Fragmentation (F-003); ceilings (F-002); stale ownership doc and an orphan test package (F-009).
13. **Guard validity.**
    - Scan roots resolve: RuntimeRawMapArchitectureTest uses moduleMainKotlinRoot and asserts the file set is non-empty; the sibling scan covers every module's main; EXACT_PACKAGE_SCC applies to domain and contracts.
    - Validity is broken by the ceilings (F-002), the allow-list (F-006) and the `Any` erasure (F-004).

## Findings

### F-001 (High). Package cycles baselined instead of broken

Evidence: 4 SCC rows were added to baselines/runtime-domain-package-cycle-baseline.txt by 3973aa265. Root causes:

- **(a) Artifact-key registry.** DurableWorkflowArtifactFamily.kt:3-39 (package workflow.engine.model) imports 40 internal key constants. Their sources are goalrunner, goalrunner.model, decomposition.runtime, workflow.engine, workflow.model.goalreview, and taskruntime.model.audit, core and persistence.*. Those packages import DurableWorkflowArtifacts back from engine.model. This drives the 5-package goalrunner SCC.
  - Fix: move the constants into workflow.engine.model, still internal.
  - Feasibility: RuntimeRawMapArchitectureTest:67-102 already forces them internal and unused outside domain. Engine imports none; its one import is an engine-owned key. Six domain tests rewrite imports.
- **(b) install.model to install.policy.** InstallPlanWireMap.kt:6 and InstallPolicyModels.kt:3 import `selectedPlatformSlugs`.
  - Fix: move that function into install.model (11 files, model ceiling 20).
- **(c) taskruntime.artifact, phase.task and phase.planning.**
  - The internal object FeatureTaskRuntimeRequiredArtifactPresenceResolver (artifact/:15-17) imports the phase.task definition, and phase.task/FeatureTaskRuntimePhaseWorkflowGraph.kt:7 imports it back.
  - phase.planning/UpstreamPlanningProjectionSpec.kt:5 imports phase.task, and phase.task/...ProjectionDeclarations.kt:21 imports it back.
  - Fix: move the resolver into phase.task and fold the single phase.planning file into it (6 to 8 files).
- **(d) 17-package review/taskruntime SCC.** The identified cross-area back-edge is review.context.model.packet/ReviewLaneBundleAssembly.kt:12, which imports taskruntime.model.repair.sha256Hex. That function is an internal duplicate (CorrectiveRepairRendering.kt:47) of skillbill.text.sha256HexUtf8 (Utf8Text.kt:26). A third private copy sits at ReviewLaneBundleAssembly.kt:323.
  - Fix: one `sha256Hex(ByteArray)` beside sha256HexUtf8 in the leaf package skillbill.text; delete the duplicates. That removes every review.context-to-workflow edge into the SCC, so it splits.
  - Remaining intra-group edges resolve by moving declarations between domain packages. Every domain package has the same import rules, so any such move is import-legal; the sibling ceilings are the only constraint.

### F-002 (High). Hardcoded per-package sibling ceilings

Evidence: ArchitectureScanSupport.kt:401-407 and :433-439 set `skillbill.goalrunner` to 15 and `skillbill.workflow.model.goalreview` to 18, which equal today's counts. `packageSiblingCountRemainderInventory` is emptyMap (PrincipleEnforcementInventory.kt:724). Added by 3973aa265. A ceiling equal to the count is a baseline in disguise.

Fix: delete both branches, then split the two packages to at most 12 files:
- goalrunner: GoalRunnerWorkflowStoreConstants.kt empties after F-001a, and the AttemptLedger* trio moves to goalrunner.ledger (15 to 11).
- goalreview:
  - FeatureTaskRuntimeRepairLedger, FeatureTaskRuntimeRepairReceipt and FeatureTaskRuntimeRepairReceiptDecodeObservations move to taskruntime.model.repair;
  - GoalObservabilityModels, GoalObservabilityParsing, GoalObservabilitySchemaErrors and GoalHistoryArtifactRetention move to workflow.model.goalobservability (18 to 11).

### F-003 (Medium). Fragmented persistence and handoff packages (SKILL-372 F-012)

Evidence: 11 files in 6 leaves under `persistence.task.runtime`, whose `task` and `runtime` segments repeat the parent `taskruntime`. `handoff.envelope` has 1 file; `repair.task` has 4.

Fix:
- flatten the 6 leaves into taskruntime.model.persistence (11 files);
- fold envelope into handoff (4 files);
- fold repair.task into repair (with F-002: 4 + 4 + 3 = 11 files).

Importer rewrites are mechanical.

### F-004 (High). `Any` erasure hides raw maps from the raw-map guard

Evidence: 33 public top-level functions of the form `fun X.asWorkflowArtifactEntry(): Any = toArtifactMap()`, `toPersistenceWire(): Any`, `asTelemetryPayload(): Any` or `toStatusWire(): Any`. Locations:
- FeatureTaskRuntimeWorkflowArtifactWire.kt:92-233;
- ...WireMappings.kt:18-50;
- ...WirePhaseMappings.kt:21-80;
- GoalWorkerSubtaskRequestArtifactCodec.kt:10,29;
- GoalRunnerStatusProjectionModels.kt:118;
- FeatureTaskRuntimeGoalContinuationOutcome.kt:53;
- GoalRunnerAccountingModels.kt:136;
- goalreview: GoalSubtaskCommitFocusedAccounting.kt:46, GoalSubtaskReviewState.kt:212, GoalObservabilityModels.kt:117,291.

The raw-map scan matches only Map shapes. The typed carrier already exists: `presentationWireMap` (:96-97) re-wraps the `Any` into FeatureTaskRuntimeWorkflowArtifactMap.

Fix: return FeatureTaskRuntimeWorkflowArtifactMap or a concrete type, and delete wrappers made redundant. Extend the existing inner-layer scan to reject public declarations typed exactly `Any` in application, domain and ports. Application and ports have 0 today.

Feasibility:
- Consumers: engine main 69, engine test 57, infra/contracts 9, sqlite 5, application 3, cli 3, mcp 1, ports test 4, domain test 7. They keep compiling because the carrier is a Map and therefore an `Any`.
- The carrier delegates to the same map, so encoding should be identical; only tests can confirm this.

### F-005 (High). Validator injection into domain survives SKILL-372

Evidence:
- The fun interface FeatureTaskRuntimeWireArtifactValidation (taskruntime/model/core) is used at:
  - PhaseHandoffProjectionDeclaration.kt:113-119, which validates before decoding;
  - FeatureTaskRuntimeHandoffProjectionInputs.kt:27, a validator field on a model;
  - FeatureTaskRuntimeWorkflowArtifactWirePhaseMappings.kt:61.
- Function-typed validators: GoalObservabilityArtifacts.kt:24,37,58 take `(Any, String) -> Unit`; InstallPlanWireMap.kt:80 and InstallPlanPolicy.kt:74 take `(InstallPlanWireMap) -> Unit`.
- The domain testFixtures holds one file, `AcceptingFeatureTaskRuntimeWireArtifactValidator`, a renamed Noop fixture. It has 2 domain users and 1 engine user, and it duplicates the ports-typed engine fixture (about 24 users).
- Decision 2026-09-24 says adapters validate.

Fix:
- The caller validates with its ports FeatureTaskRuntimeWireArtifactValidator before calling the domain decoder. Engine already holds it at every call site (FeatureTaskRuntimeBriefingProjectionInputsMapper.kt:23). Validation ran first before, so the throw order is unchanged.
- Delete the fun interface, the validator parameters and field, the testFixtures file, the java-test-fixtures plugin, and the three `testFixtures(project(:runtime-domain))` lines.

### F-006 (Medium). Curated raw-map FQN allow-list

Evidence: RuntimeArchitectureTestSupport.kt:454-468 (3973aa265) exempts 10 domain declarations, against Boundary Rule 11:
- decodeStrictKeyedArtifactMap (taskruntime.phaseartifacts);
- FeatureTaskRuntimeGoalContinuationArtifact.toWorkflowArtifactPatch (:100);
- goalParentArtifactProjection (decomposition/runtime/GoalParentArtifactProjection.kt:6);
- missingResultPrefixTerminalOutcomeArtifact (GoalContinuationOutcomeDecoding.kt:9);
- goalReviewArtifacts (GoalReviewArtifactValidation.kt:16);
- validatedGoalReviewPasses (:19, with an `emissionEnvelope: (String) -> Map<String, Any?>` parameter);
- the four DurableWorkflowArtifactFamily members contains, value, putInto and removeFrom (:100-113).

Fix:
- The six functions become internal where only domain calls them. Otherwise they take and return the existing named carriers DurableWorkflowArtifacts or WorkflowArtifactPatch. Their allow-list entries are deleted.
- The four family members stay. `key` is private, and they are the one typed gate for the artifact keys, with 88 call sites. Rule 11 is amended to name exactly these four.
- SKILL-393 deletes the ports entry.

### F-007 (Medium). DecompositionManifest and goal-runner rules live outside domain

Evidence (engine):
- GoalRunnerBranchPlan.kt:15-132: withAttemptedSubtask, withWorkflowId, knownWorkflowId, withCompletedSubtask, withStoppedSubtask, withResumableSubtask.
- GoalRunnerBranchPlanSubtaskOrdering.kt:11-65,112: withValidationQualityRetrySubtask, withBranchSetupBlockedSubtask, withBlockedSelection, branchForFinalPullRequest.
- GoalRunnerManifestSnapshotProjection.kt:16-115: 8 restated consts, isAtUnlaunchedBoundary, resetManifest, restartIntent, replanIntent.
- WorkflowGoalRunnerScopedReplanPersistence.kt:105-123,149-168: afterIncompatibleChildDeletion and afterReplanChildDeletion, whose reset bodies are identical to each other and to resetManifest's freshReset.
- GoalRunnerReAttemptCause.kt:39-90: GoalRunnerStopReason.toLedgerAction, toDiagnosticClass, nextSafeAction.
- GoalRunnerWorkflowFamilyLookup.kt:34-56: pauseAtOperatorBoundary.
- GoalRunnerControlCoordinator.kt:228-233: targetReached, which takes ports GoalRunnerManifestState only to read `.manifest`.

Evidence (application):
- DecompositionWorkflowResumeAlignment.kt:217-258: withStartedSubtask, withCommittedSubtask, branchForSubtask, baseForSubtask.
- DecompositionManifestRuntimeState.kt:76-101: withPreservedRuntimeState.

Domain already owns intentFor, withParentStatus, withBlockedSubtask and withRetriedSubtask (DecompositionManifestTransitions.kt). Restated literals: engine about 32, application about 5, domain 7. DecompositionStatus exists (ClosedStatusTypes.kt:3); the action vocabulary (none, start, resume, blocked, complete) has no enum.

Fix: move the listed functions into domain `skillbill.workflow.decomposition` (3 files today) and next to the goalrunner.model enums. Add `DecompositionSubtaskAction` with wireValue and fromWire beside DecompositionStatus. Write every token through `.wireValue`. Keep one subtask-reset function.

Stays: toPullRequestRequest (Path and ports GoalPullRequestRequest), branchPlanFor (engine GoalRunnerBranchPlan), withRuntimeUpdate, currentSubtaskIdForUpdate, withRuntimeFields and assertExecutionModelCanReplace (Path and ports DecompositionManifestRuntimeUpdate), toResetSnapshot (engine and ports snapshot types).

Feasibility:
- The moved bodies import only domain types: GoalRunnerReconciledOutcome, CurrentSubtaskIntent, DecompositionManifest, DecompositionSubtask, DecompositionStatus, GoalRunnerControlState, GOAL_PAUSE_REASON_* and GoalAttemptLedgerAction.
- The new edge workflow.decomposition to goalrunner.model is acyclic, because goalrunner.model imports only workflow.decomposition.model (GoalRunnerTerminalModels.kt:3).

### F-008 (Low, recorded). Duplicated add-on selection decoder

Evidence: the domain decoder (FeatureTaskRuntimeGoalContinuationArtifact.kt:193-225) checks the slug and sha256 regexes, rejects duplicates, and throws InvalidWorkflowStateSchemaError. The engine decoder for the goal review policy (GoalRunnerWorkflowFamilyLookup.kt:58-112) checks exact keys and throws InvalidAgentAddonSelectionError with its own messages. SKILL-386 recorded this as a follow-up.

Not unified here: unifying changes a thrown type, a message or the strictness for persisted policies, and that needs a product decision.

### F-009 (Low). Stale ownership doc and an orphan test package

Evidence: ARCHITECTURE.md Package Ownership lists the domain packages skillbill.workflow.goal, workflow.goal.model and workflow.idestatus, none of which exist in main. The domain test package skillbill.workflow.goal (GoalObservabilityModelsTest) has no main package.

Fix: refresh the list after subtask 3, and move the test beside its subject.

## Over-engineering register

| Item | Action |
|---|---|
| Validator fun interface, 5 function-typed validator params, 1 validator field | Delete (F-005) |
| Domain testFixtures source set (1 file), plugin, 3 dependency lines | Delete (F-005) |
| 33 `Any` wrappers and the presentationWireMap re-wrap | Typed return (F-004) |
| 8 engine status/action consts | Enum (F-007) |
| 3 identical subtask-reset bodies | One domain function (F-007) |
| 3 sha256 helpers | One per input type in skillbill.text (F-001d) |
| Empty package levels persistence.task.runtime | Flatten (F-003) |
| Two hardcoded ceilings, 6 domain FQN entries, 4 baseline rows | Delete (F-001, F-002, F-006) |

## What stays unchanged

| Item | Reason |
|---|---|
| DurableWorkflowArtifactFamily contains/value/putInto/removeFrom | Sole typed gate over private keys; 88 call sites; typing them to DurableWorkflowArtifacts would force copying `fromMap` wraps in builders. |
| String-typed DecompositionManifest/Subtask status and CurrentSubtaskIntent.action | 126 intent and about 165 manifest/subtask constructions, mostly tests. The status-String-plus-enum pattern is already established. Same wire bytes, and no bug evidence. |
| RequiredArtifactPresenceResolver | 2 implementations; a real strategy. |
| 13 sealed result interfaces | Closed sums, not ports. |
| api edges from application, ports and engine | Signatures expose domain types (SKILL-388). |
| SKILL-372 retention decisions | Multi-file families split by responsibility (2026-09-17); asLenientIntOrNull as the sole lenient coercion; validator interfaces in ports (2026-09-24). |
| Split package skillbill.model (FileLocation in domain; EnvironmentContext and RuntimeVersion in ports) | No module system; a move churns imports with no boundary gain. |
| Engine/application functions needing Path or ports types | Domain bans java.nio and ports (F-007 stay list). |
| Named-carrier suffix rule in isBoundaryCarrierRawMapDeclaration | A pattern rule, not an FQN list; SKILL-389 owns scanner anchoring. |
| F-008 decoders; `require` and runCatching error sites | Error contracts differ; recorded only. |

## Coordination with concurrent bundles

| Bundle | Overlap | Rule |
|---|---|---|
| SKILL-389 core | Edits ArchitectureScanSupport.kt:508-509 and deletes RuntimeRawMapArchitectureTest:11-64. 397 edits ArchitectureScanSupport.kt:397-439, the inner-layer raw-map scan, and RuntimeArchitectureTestSupport.kt:454-468. | Different regions; second lander keeps both. |
| SKILL-393 ports | Deletes the ports allow-list entry (:467) and the 12 `validateX(payload: Any)` forwarders. | Second lander leaves only the four DurableWorkflowArtifactFamily entries. Forwarder call sites become `validate(kind, FeatureTaskRuntimeWorkflowArtifactMap.from(x), label)`, which F-004 makes a pass-through wrap. The peer confirmed the 10 domain entries belong to 397. |
| SKILL-388 application | Moves decomposition discovery into application/decomposition (11 to 12 files). 397 only removes functions from DecompositionManifestRuntimeState.kt and DecompositionWorkflowResumeAlignment.kt. | Second lander applies the removals to the files present. |
| SKILL-390 engine (reserved) | Engine goalrunner files lose transitions. | If 390 moves them first, 397 moves the same functions from their new files; pure DecompositionManifest, ControlState and StopReason transitions live in domain. |
| SKILL-392 cli | RuntimeOwnedReviewMode.parse `require` is a non-goal in both bundles. | None. |
| SKILL-387, 391, 395, 396 | Consumers of the `Any` wrappers in infra/contracts, sqlite, mcp and cli only widen to a Map subtype. | Second lander compiles against the typed return. |

Global order: 388, 387, 389, 393, 397; the others are independent under the rules above.

Reserved keys observed: 389, 390, 391, 392, 393, 395, 396, 397. SKILL-397 is absent from .feature-specs, done, branches and the log.

## Limits (what only compiling or tests can confirm)

- No scripted census: python was blocked. Counts come from grep. Top-level visibility is counted by first token, not by FQN, so SKILL-372 F-013 is neither verified nor re-planned.
- Exact-package SCC edges were fully traced for the goalrunner, install and artifact/phase SCCs. In the 17-package SCC only the cross-area back-edge was found; any intra-group edges left will show up in the existing EXACT_PACKAGE_SCC scanner output.
- Whether the goalrunner and goalreview splits create new cycles: the scanner will show.
- That Jackson encodes the Map-delegating carrier identically to the raw map: tests will confirm.
- That every validator caller holds a ports validator: compiling will confirm.

