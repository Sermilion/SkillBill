# SKILL-397 Subtask 1 - domain-wire-boundary-honesty

Parent spec: [.feature-specs/SKILL-397-runtime-domain-boundary-integrity/spec.md](spec.md)
Issue key: SKILL-397

## Scope

Remove the three guard evasions on the domain wire boundary. (F-004) Change the 33 public top-level domain functions whose declared return type is `Any`. They are in taskruntime/artifact FeatureTaskRuntimeWorkflowArtifactWire.kt, FeatureTaskRuntimeWorkflowArtifactWireMappings.kt and FeatureTaskRuntimeWorkflowArtifactWirePhaseMappings.kt; goalrunner GoalWorkerSubtaskRequestArtifactCodec.kt; goalrunner/model GoalRunnerStatusProjectionModels.kt, FeatureTaskRuntimeGoalContinuationOutcome.kt and GoalRunnerAccountingModels.kt; and workflow/model/goalreview GoalSubtaskCommitFocusedAccounting.kt, GoalSubtaskReviewState.kt and GoalObservabilityModels.kt. Each returns FeatureTaskRuntimeWorkflowArtifactMap or a concrete type, or is deleted where a typed wire map already exists (for example presentationWireMap). Extend the existing inner-layer raw-map scan in runtime-core repoTest (findRawMapViolations, used by RuntimeRawMapArchitectureTest) to reject public declarations typed exactly `Any` in runtime-application, runtime-domain and runtime-ports main. Add a synthetic-fixture test beside the existing application fixture test. (F-005) Domain stops accepting validators. Delete the FeatureTaskRuntimeWireArtifactValidation fun interface and the validator parameters or field at PhaseHandoffProjectionDeclaration.fromArtifactMap, FeatureTaskRuntimeHandoffProjectionInputs, the phase-mapping wrapper, GoalObservabilityArtifacts (3 sites), InstallPlanWireMap and InstallPlanPolicy. Each engine or application caller validates with its ports validator before calling the domain decoder. Delete the domain testFixtures file AcceptingFeatureTaskRuntimeWireArtifactValidator, the java-test-fixtures plugin in runtime-domain/build.gradle.kts, and the testFixtures(project(:runtime-domain)) dependency lines in application, engine and core. The three tests that use the fixture switch to the engine fixture or to direct calls. (F-006) Make decodeStrictKeyedArtifactMap, FeatureTaskRuntimeGoalContinuationArtifact.toWorkflowArtifactPatch, goalParentArtifactProjection, missingResultPrefixTerminalOutcomeArtifact, goalReviewArtifacts and validatedGoalReviewPasses internal, or type them with the existing DurableWorkflowArtifacts or WorkflowArtifactPatch carriers. Delete their rawMapBoundaryAccessors entries in RuntimeArchitectureTestSupport.kt. Amend ARCHITECTURE.md Boundary Rule 11 to name the four DurableWorkflowArtifactFamily members as the only allow-listed raw-map members, and give the reason.

## Acceptance Criteria

1. No public declaration in runtime-domain, runtime-application or runtime-ports main declares a return or property type of exactly `Any`. Each of the 33 former wrappers returns FeatureTaskRuntimeWorkflowArtifactMap or a concrete type, or no longer exists.
2. The inner-layer raw-map scan used by RuntimeRawMapArchitectureTest reports a public declaration typed exactly `Any` in those three modules, and a synthetic-fixture test in RuntimeRawMapArchitectureTest asserts that rejection.
3. runtime-domain main declares no FeatureTaskRuntimeWireArtifactValidation, and GoalObservabilityArtifacts, InstallPlanWireMap, InstallPlanPolicy, PhaseHandoffProjectionDeclaration, FeatureTaskRuntimeHandoffProjectionInputs and the phase-mapping wrappers have no validator parameter or property.
4. runtime-domain has no src/testFixtures directory, its build file applies no java-test-fixtures plugin, and no build file references testFixtures(project(:runtime-domain)).
5. rawMapBoundaryAccessors contains no skillbill.workflow.* or skillbill.goalrunner.* entry other than DurableWorkflowArtifactFamily.contains, value, putInto and removeFrom. The six former entries are internal or expose no Map<String, Any?> in a public signature.
6. ARCHITECTURE.md Boundary Rule 11 names the four DurableWorkflowArtifactFamily members as the only allow-listed raw-map members and states why.
7. No baseline file gains a row, no exemption or architecture-test class is added, and no existing wire-fixture or expected-payload assertion is edited.

## Non-Goals

- Unifying the domain and engine add-on selection decoders (F-008).
- Changing the DurableWorkflowArtifactFamily accessor signatures.
- Deleting the ports validateX forwarders (SKILL-393 owns them).
- Changing the ports FeatureTaskRuntimeWireArtifactValidator interface or its adapter.

## Dependency Notes

Depends on: none
Coordinates with SKILL-393, which deletes the ports allow-list entry and the 12 ports forwarders, and with SKILL-389, which edits other regions of ArchitectureScanSupport and RuntimeRawMapArchitectureTest. Whichever lands second keeps both edits and leaves only the four family entries in the allow-list.

## Validation Strategy

Goal gates: build, unit tests and the runtime-core repoTest architecture suite. Test obligation: the synthetic-fixture test for the `Any`-typed public declaration rule. Existing projection, handoff, install-plan and observability tests must pass unchanged, which shows that validation order and wire output are unchanged.

## Implementation Details

Scope of this run: this subtask's seven criteria only. Subtasks 2 and 3 are separate runs. The counts below were taken on `c038e02e5` (after SKILL-387, 388, 389, 391, 392, 393, 395 and 396 landed). Where they differ from the Scope text, the recount wins.

### Census deviations from the Scope text

- **`Any`-typed declarations.** There are 43 public functions returning exactly `Any`, plus 2 public properties, not 33. All are in runtime-domain; runtime-application and runtime-ports have none.
  - `taskruntime/artifact/FeatureTaskRuntimeWorkflowArtifactWire.kt`: 14, including `FeatureTaskRuntimeOperatorBlockRetry.asWorkflowArtifactEntry(previousBlockedReason, reopenedPhaseIds)`.
  - `...WireMappings.kt`: 9, including `implementationAttemptRecordWorkflowArtifact`.
  - `...WirePhaseMappings.kt`: 9, including 4 `asTelemetryPayload`.
  - `goalrunner/GoalWorkerSubtaskRequestArtifactCodec.kt`: 2.
  - `goalrunner/GoalObservabilityArtifacts.patchForRuntimeEvent`: 1. `patchForProgressEvent` returns `Any?`; it is retyped here too.
  - `goalrunner/model` (`GoalRunnerStatusProjectionModels.toStatusWire`, `FeatureTaskRuntimeGoalContinuationOutcome.toPersistenceWire`, `GoalRunnerAccountingModels.toPersistenceWire`): 3.
  - `workflow/model/goalreview` (`GoalSubtaskCommitFocusedAccounting`, `GoalSubtaskReviewState`, `GoalObservabilityModels` ×2): 4.
  - `taskruntime/model/handoff/task/FeatureTaskRuntimeHandoffModels.kt:145`, `NormalizedFeatureTaskRuntimePhaseOutput.envelopePayload()`: 1.
  - The properties: `val artifacts: Any` on `GoalObservabilityProgressInput` and on `GoalObservabilityRuntimeEventInput` (`goalrunner/model/GoalRunnerObservabilityModels.kt:83,91`).
  - `WorkflowInputProjectionSelector.kt:142` is private and out of scope.
  - Parameters typed `Any` (for example `artifacts: Any`, `output: Any`) are legal. The rule covers only return and property types.
- **Underlying serializers.** Every `toArtifactMap`, `toEnvelopeMap`, `toTelemetryMap`, `*RecordToWire` and `*ToArtifact` behind a wrapper returns `Map<String, Any?>`. So every wrapper, including the two list-receiver ones, can return `FeatureTaskRuntimeWorkflowArtifactMap`.
- **Already done.** `FeatureTaskRuntimeHandoffProjectionInputs` has no validator field.
- **Dead code.** `decodePhaseHandoffProjectionDeclarationFromArtifact` (internal) has no callers. The only caller of `PhaseHandoffProjectionDeclaration.fromArtifactMap` is the domain test `model/handoff/task/FeatureTaskRuntimeHandoffFoundationModelsTest.kt:49`.
- **Domain test fixture.** `skillbill.workflow.taskruntime.noop.AcceptingFeatureTaskRuntimeWireArtifactValidator` has no importers. Every test uses the engine fixture of the same name, so no test needs to switch.
- **`testFixtures(project(":runtime-domain"))` references.** There are five lines, not three:
  - `runtime-application/build.gradle.kts:20` and `:25`;
  - `runtime-engine/build.gradle.kts:20` and `:22`;
  - `runtime-core/build.gradle.kts:57`.
- **Allow-list.** `rawMapBoundaryAccessors` (`runtime-core/src/repoTest/kotlin/skillbill/architecture/RuntimeArchitectureTestSupport.kt:454-467`) holds exactly the 6 entries to delete plus the 4 `DurableWorkflowArtifactFamily` entries. The ports entry is already gone (SKILL-393).

### Cycle-safety rule for new imports (AC-7)

- `skillbill.workflow.taskruntime.model.core`, the home of `FeatureTaskRuntimeWorkflowArtifactMap`, imports only `workflow.model.persistence.artifact`. That package imports only `agent.model`, which is a leaf.
- So any domain package may import `FeatureTaskRuntimeWorkflowArtifactMap` without creating or growing a package SCC. Use it as the default carrier.
- Use `DurableWorkflowArtifacts` or `WorkflowArtifactPatch` (`skillbill.workflow.engine.model`, which is in SCC row 1 of `runtime-domain-package-cycle-baseline.txt`) only from packages that are already in that row:
  - `skillbill.goalrunner`;
  - `workflow.decomposition.runtime`;
  - `workflow.engine`;
  - `persistence.task.runtime.goal`.
  A new edge inside an existing SCC cannot change the baseline. Never add a `workflow.engine.model` import to `goalrunner.model`, `workflow.model.goalreview`, `taskruntime.artifact` or `taskruntime.phaseartifacts`.

### Tasks (in order)

1. **Give the carrier Map-contract equality (prerequisite for AC-1 and AC-7).**
   - In `runtime-domain/.../workflow/taskruntime/model/core/FeatureTaskRuntimeWorkflowArtifactMap.kt`, add these overrides:
     - `override fun equals(other: Any?): Boolean = delegate == other`
     - `override fun hashCode(): Int = delegate.hashCode()`
     - `override fun toString(): String = delegate.toString()`
   - Why: Kotlin `by` delegation does not forward these three. Once the wrappers return the carrier, `carrier == map`, data-class equality over nested carriers, set membership and string interpolation would otherwise change behaviour.
   - Wire bytes are unaffected: `JsonCodec.mapJsonElement` serializes any `Map` by its entries.
   - No new type is added.
   - Tests: none new. Existing equality-based tests cover it.

2. **Type every `Any` wrapper (AC-1).** Change each `: Any = x` listed above to `: FeatureTaskRuntimeWorkflowArtifactMap = FeatureTaskRuntimeWorkflowArtifactMap.from(x)`. Specific cases:
   - `FeatureTaskRuntimeOperatorBlockRetry.asWorkflowArtifactEntry(...)`: wrap the inline `mapOf`. `JsonCodec.anyToStringAnyMap` keeps insertion order.
   - Delete `FeatureTaskRuntimeValidationGateRunRecord.asWorkflowArtifactEntry()`. Its only caller is `presentationWireMap()`, which becomes `FeatureTaskRuntimeWorkflowArtifactMap.from(toArtifactMap())`. The runtime-cli caller (`cli/featuretask/FeatureTaskRuntimeStatusPresentation.kt:30`) is unchanged.
   - Make `NormalizedFeatureTaskRuntimePhaseOutput.envelopePayload()` `internal`, keeping its body. `envelopeWireMap()` stays the public typed accessor. Repoint the engine main callers of `envelopePayload()` to `envelopeWireMap()`:
     - `goalrunner/persist/GoalContinuationArtifactCodec.kt:17`;
     - `featuretask/phase/core/FeatureTaskPhaseSettlementService.kt:36,61` (drop the trailing `.toWorkflowArtifactMap()`);
     - `runloop/state/FeatureTaskRuntimeRunProgressObservations.kt:107` (drop `as Map<*, *>`);
     - `runloop/state/FeatureTaskRuntimeRunState.kt:512,515`;
     - `review/goal/FeatureTaskRuntimeGoalReviewCompletionRecorder.kt:126,233`;
     - `validation/RuntimeGateRecordIntegrity.kt:20`.
   - Add `import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap` in `goalrunner`, `goalrunner.model` and `workflow.model.goalreview` files as needed. Keep `package` and `import` lines at column 0.
   - **Overload watch.** Inside runtime-domain, several decoders have a public `(raw: Any?)` overload and an internal `(raw: Map<String, Any?>)` overload, for example `decodeHandoffEnvelopeFromArtifact`. A domain call that passes a wrapper result now resolves to the internal non-null overload. Behaviour is the same for maps. Remove any safe call or elvis the compiler reports as redundant; do not suppress the warning.
   - Consumers in engine, cli, mcp, infra and sqlite keep compiling, because the carrier is a `Map<String, Any?>`. There are no `as MutableMap` or `as LinkedHashMap` casts in these modules (checked). `.toMutableMap()` call sites use the stdlib copy.
   - Tests: none new. The existing wire and expected-payload tests prove byte identity and stay unedited.

3. **Remove validator injection from domain (AC-3).**
   - Delete `runtime-domain/.../taskruntime/model/core/FeatureTaskRuntimeWireArtifactValidation.kt`.
   - `PhaseHandoffProjectionDeclaration.fromArtifactMap(raw)`:
     - drop the `foundationValidator` parameter and the call to it;
     - delete the dead `decodePhaseHandoffProjectionDeclarationFromArtifact` and its import in `...WirePhaseMappings.kt`;
     - update the test call site `FeatureTaskRuntimeHandoffFoundationModelsTest.kt:49` to `fromArtifactMap(wire)`. This is a call-site change; the expected value is not edited.
   - `GoalObservabilityArtifacts`:
     - remove the `validator` parameter from `patchForProgressEvent`, `patchForRuntimeEvent` and the private `patchForEvent`;
     - make `patchForEvent` return `FeatureTaskRuntimeWorkflowArtifactMap`, `patchForRuntimeEvent` return `FeatureTaskRuntimeWorkflowArtifactMap`, and `patchForProgressEvent` return `FeatureTaskRuntimeWorkflowArtifactMap?`;
     - retype both `val artifacts: Any` properties in `GoalRunnerObservabilityModels.kt` to `FeatureTaskRuntimeWorkflowArtifactMap`.
   - **Validate-before-persist at the two callers.**
     - Callers: `application/workflow/persist/WorkflowServiceInputMapping.kt:195` and `engine/goalrunner/persist/WorkflowGoalRunnerProgressRecording.kt:121`.
     - Each wraps its input with `FeatureTaskRuntimeWorkflowArtifactMap.from(...)`, then calls the domain patch builder.
     - Before building the `WorkflowArtifactPatch`, each runs a file-private `validateGoalObservabilityPatch(patch)` with its existing ports `FeatureTaskRuntimeWireArtifactValidator` and `FeatureTaskRuntimeWireArtifactKind.GOAL_OBSERVABILITY_EVENT`. In order, that helper:
       1. validates `DurableWorkflowArtifactFamily.GOAL_OBSERVABILITY_LATEST_EVENT.value(patch)` with source label `GOAL_OBSERVABILITY_LATEST_EVENT.label()`;
       2. validates each entry of `GOAL_OBSERVABILITY_RUN_HISTORY.value(patch) as List<*>` with label `"${GOAL_OBSERVABILITY_RUN_HISTORY.label()}[$index]"`.
     - That keeps the old order and labels: latest event first, then history. The patch build is pure, so nothing is persisted before validation. `DurableWorkflowArtifactFamily.label()` returns the private key unchanged (`DurableWorkflowArtifactFamily.kt:115`), so the source labels stay byte-identical to the old `GOAL_OBSERVABILITY_*_ARTIFACT_KEY` strings.
     - Both callers already pass real maps (`mergedArtifacts` is a `LinkedHashMap`, and `record.artifacts` is a map). `FeatureTaskRuntimeWorkflowArtifactMap.from` turns a non-map into an empty carrier, so never pass it anything except a map here.
     - Use the family accessors, never key literals, because `RuntimeRawMapArchitectureTest` forbids consumers from reading domain artifact keys.
     - The engine line `JsonCodec.anyToStringAnyMap(observabilityPatch)?.let(WorkflowArtifactPatch::from)` may become `WorkflowArtifactPatch.from(observabilityPatch)`.
   - Test call sites (call-site changes only): `domain/src/test/.../workflow/goal/GoalObservabilityModelsTest.kt:87,107,135` and `engine/src/test/.../goalrunner/GoalRunnerTest.kt:4202-4208`. Drop the validator argument and wrap the `artifacts =` maps with `FeatureTaskRuntimeWorkflowArtifactMap.from(...)`. Do not touch the expected-bytes assertion at GoalRunnerTest `:4214`.
   - **Install plan.**
     - Delete `validateInstallPlanWireSnapshot` (`install/model/InstallPlanWireMap.kt:78-83`) and `InstallPlanPolicy.validateInstallPlanSnapshot` (`install/policy/InstallPlanPolicy.kt:72-78`), plus their now-unused imports. `InstallPolicyValidationResult` stays, because `validateRequest` uses it.
     - Each caller calls its port directly with `validator.validate(buildInstallPlanWireMap(plan))`:
       - `runtime-application/.../install/InstallPlanningPolicyMappers.kt:33`;
       - `runtime-application/.../install/InstallService.kt:154`;
       - `runtime-infra/skills/.../install/plan/InstallPlanBuilder.kt:38`, as `wireValidator.validate(buildInstallPlanWireMap(plan))`, with the import updated.
   - **`InstallPolicyOwnershipArchitectureTest`** (runtime-core repoTest) pins the deleted names. Edit it in place; do not add a class:
     - In `install policy delegates schema validation to the injected wire validator port`, drop the two assertions that require the callback text (`validateInstallPlanWireSnapshot(plan, validate)` and `validate: (InstallPlanWireMap) -> Unit`). Keep the assertion that `InstallPlanPolicy.kt` does not reference `InstallPlanSchemaValidator`. Rename the test to say domain install policy takes no validator.
     - Repoint the InstallPlanBuilder entry in `approvedValidationSeams` to the token `wireValidator.validate(buildInstallPlanWireMap(plan))`.
     - Repoint `installWireSnapshotValidationReferencePattern` from `validateInstallPlanWireSnapshot` to `buildInstallPlanWireMap`, so adapter calls outside the approved seams are still caught. Runtime-cli uses `toInstallPlanContract()`, not `buildInstallPlanWireMap`, so it stays clean.
     - Update the scanner's known-bad samples and the approved-builder sample to the new symbol, so the guard can still fail.
   - **Install test relocation.** Domain test `install/policy/InstallPlanPolicyTest.kt:325-363` (`validate install plan snapshot delegates to the injected wire validator port`) tests a deleted function. Move its two assertions verbatim into `runtime-application/src/test/.../InstallServiceTest.kt`:
     - the recording validator sees status `"planned"`;
     - a loud-fail `InvalidInstallPlanSchemaError` propagates.
     They go through `InstallService.validateInstallPlanWire(plan)` with a recording or throwing `InstallPlanWireValidator`. The realistic bug it guards: the application install path stops validating the plan before apply.

4. **Delete the domain test fixtures (AC-4).**
   - Delete `runtime-domain/src/testFixtures/` (its one file).
   - Remove `` `java-test-fixtures` `` from `runtime-domain/build.gradle.kts`.
   - Remove the five `testFixtures(project(":runtime-domain"))` lines listed above. Application and engine keep domain through `api(project(":runtime-domain"))`; core keeps it through `implementation`; engine keeps `testFixturesImplementation(project(":runtime-domain"))`.
   - In `runtime-core/.../architecture/RuntimeModuleCatalog.kt:167-174`, remove `"runtime-domain"` from the `testFixturesProjectDependenciesByModule` set for `runtime-application`. Otherwise `RuntimeAdapterDependencyAllowlistTest` reports it as Missing. This deletes an allow-list entry and adds none.
   - Leave `PortNullObjectClassification` alone. Its keys name the engine fixture, which still exists.

5. **Trim the raw-map allow-list (AC-5).** Delete the six non-family entries from `rawMapBoundaryAccessors` and fix each declaration:
   - **`decodeStrictKeyedArtifactMap`** (`taskruntime/phaseartifacts/FeatureTaskRuntimePhaseArtifactDecoders.kt:20`).
     - Change `artifacts` to `FeatureTaskRuntimeWorkflowArtifactMap` and `decodeEntry` to `(String, FeatureTaskRuntimeWorkflowArtifactMap) -> T`. Build each entry with `FeatureTaskRuntimeWorkflowArtifactMap.from(value)` and keep the existing schemaError when `!isObject`.
     - Callers wrap with `FeatureTaskRuntimeWorkflowArtifactMap.from(artifacts)`: domain-internal `phaseRecordsFrom`, and engine `featuretask/persist/FeatureTaskRuntimeHandoffEnvelopeArtifactDecoders.kt:18,51`. The lambdas compile unchanged, because the carrier is a Map.
     - It cannot be internal: engine calls it.
   - **`FeatureTaskRuntimeGoalContinuationArtifact.toWorkflowArtifactPatch()`.** Return `FeatureTaskRuntimeWorkflowArtifactMap`. Its caller, `engine/goalrunner/reset/WorkflowGoalRunnerChildWorkflowPersistence.kt:300`, does `putAll`, which is unchanged.
   - **`goalParentArtifactProjection`** (`workflow/decomposition/runtime`, SCC row 1).
     - New signature: `(existing: DurableWorkflowArtifacts, encodedManifest: FeatureTaskRuntimeWorkflowArtifactMap): WorkflowArtifactPatch`.
     - Store the manifest as `LinkedHashMap(encodedManifest)`, so the persisted value stays a plain map.
     - Return `WorkflowArtifactPatch.from(...) ?: WorkflowArtifactPatch.EMPTY` or an equivalent non-null form.
     - Callers:
       - `application/workflow/decomposition/DecompositionWorkflowResumeAlignment.kt:200` drops its `WorkflowArtifactPatch.from(...)` wrapper;
       - `engine/goalrunner/manifest/GoalParentProjectionWriter.kt:26` wraps its inputs;
       - domain test `FeatureTaskRuntimePersistenceModelsTest.kt:278` gets a call-site wrap only.
   - **`missingResultPrefixTerminalOutcomeArtifact`.** Return `FeatureTaskRuntimeWorkflowArtifactMap?`, wrapping the built `linkedMapOf`. Caller: `engine/goalrunner/persist/WorkflowGoalRunnerOutcomeTerminalPersistence.kt:183`.
   - **`goalReviewArtifacts`.** Delete it, and make the existing `DurableWorkflowArtifacts.goalSubtaskReviewArtifacts()` extension public in `goalrunner/GoalReviewArtifactValidation.kt`. Engine `WorkflowGoalRunnerOutcomeStore.kt:274,282,298` calls `DurableWorkflowArtifacts.fromAny(record.artifacts).goalSubtaskReviewArtifacts()`; `fromAny` returns the receiver when it is already a `DurableWorkflowArtifacts`.
   - **`validatedGoalReviewPasses`.** Retype `emissionEnvelope` to `(String) -> FeatureTaskRuntimeWorkflowArtifactMap`. Engine `:283,300` passes `{ FeatureTaskRuntimeWorkflowArtifactMap.from(goalReviewEmissionEnvelope(it)) }`. The reducers take `output: Any` and read it as a map, so they are unchanged.
   - Keep the four `DurableWorkflowArtifactFamily` entries and their signatures (non-goal).

6. **Add the exact-`Any` rule and its fixture (AC-2).**
   - In `RuntimeArchitectureTestSupport.rawMapViolationForLine`, run the new check after `fqn` is computed and before the banned-shape early return. Reuse `rawMapDeclarationModifiers` and `tracker.insideNonPublicScope` for the exemption: `private`, `protected` and `internal` declarations and non-public scopes are exempt. Do not apply the named-carrier exemption or the allow-list.
   - Matching: a fun matches when the collected signature contains `\)\s*:\s*Any(?![\w?<.])`. A val or var matches when the trimmed line matches `(?:val|var)\s+\w+\s*:\s*Any(?![\w?<.])`. `Any?`, `Any` parameters and generic `Any` arguments therefore stay legal.
   - Message: `"${file.relativePath}:${index + 1} public `$declName` declares type exactly Any (fqn=$fqn)"`. It keeps the "public `name`" shape that the existing name-parsing tests use. `rawMapViolationFixtureSource()` has no exact-`Any` declarations, so `expectedRawMapViolationFixtureNames()` stays unchanged.
   - Extend the failure message of `runtime architecture forbids public raw map shapes in inner layers` to mention exact `Any`.
   - **Test obligation.** Add one test in `RuntimeRawMapArchitectureTest`, next to `inner-layer raw-map scanner rejects synthetic public map in application main source`, named `inner-layer raw-map scanner rejects synthetic public Any-typed declarations in domain main source`.
     - It writes a temp `runtime-domain/src/main/kotlin/skillbill/workflow/fixture/SyntheticAnyLeak.kt` containing a class with:
       - `val artifacts: Any`;
       - `private val hidden: Any`;
       - `fun wire(): Any = Unit`;
       - `internal fun internalWire(): Any = Unit`;
       - `fun nullable(): Any? = null`;
       - `fun decode(raw: Any): Int = 0`.
     - It asserts that `rawMapViolationsUnder(sourceRoot)` reports exactly the names `artifacts` and `wire`, from that file.
     - Bug it catches: the rule is missing, the private/internal exemption is lost, or `Any?` and `Any` parameters are flagged. It fails on the first and passes only with the exemptions intact.

7. **Rewrite Boundary Rule 11 (AC-6).**
   - In `runtime-kotlin/ARCHITECTURE.md:982-998`, replace "There is no curated FQN allow-list" with text that:
     - names `DurableWorkflowArtifactFamily.contains`, `value`, `putInto` and `removeFrom` as the only allow-listed raw-map members;
     - gives the reason: `key` is private, and these four accessors are the single typed gate through which every adapter reads or writes a durable artifact family, so they must accept the store's `Map<String, Any?>`;
     - adds that public inner-layer declarations must not be typed exactly `Any`.
   - Make the matching edit to the "zero-tolerance: no allow-list" bullet at `ARCHITECTURE.md:2117-2120`.
   - Docs only; no test.

### Acceptance-criteria coverage

- AC-1: tasks 1 and 2, plus the observability retyping in task 3 and the `goalParentArtifactProjection` and `missingResultPrefixTerminalOutcomeArtifact` retyping in task 5.
- AC-2: task 6.
- AC-3: task 3.
- AC-4: task 4.
- AC-5: task 5.
- AC-6: task 7.
- AC-7: the cycle-safety rule above, plus the Constraints below. The review phase checks it by diffing the test tree for edited `assertEquals` expected values.

### Test obligations

1. The new synthetic fixture test in `RuntimeRawMapArchitectureTest` (task 6, AC-2).
2. The install validator assertions moved verbatim into `InstallServiceTest` (task 3), so the coverage behind the deleted domain function survives.

No other new tests. The existing wire, projection, handoff, install-plan and observability tests carry the byte-identity proof, and they stay unedited apart from call-site signature changes.

### Constraints

- **AC-7.**
  - No baseline file gains a row. Use the cycle-safety rule above.
  - No architecture-test class, exemption or typealias is added.
  - Do not edit any wire fixture or expected-payload assertion. Test edits are limited to call-site signature changes, the in-place `InstallPolicyOwnershipArchitectureTest` edits that follow the deleted names, the relocated install validator test, and the new fixture test.
- **Non-goals.** Leave alone:
  - the ports `FeatureTaskRuntimeWireArtifactValidator` and its adapter;
  - the ports `validateX` forwarders;
  - the `DurableWorkflowArtifactFamily` signatures;
  - the add-on decoders.
- **Decisions to follow.**
  - Decision `runtime-domain/agent/decisions.md#a1612cb77454`: domain decodes, and adapters validate at each durable seam.
  - Decision `#e89f5bccae22`: reuse the existing carriers and add no new ones.
- **Phase ownership.** No build, tests or `check` in implementation phases. The build and validate phases own those gates. Run Spotless in a plain clone, not a linked worktree, and use `--no-configuration-cache` if it reports a stale cache.

## Next Path

skill-bill goal SKILL-397

## Spec Path

.feature-specs/SKILL-397-runtime-domain-boundary-integrity/spec_subtask_1_domain-wire-boundary-honesty.md
