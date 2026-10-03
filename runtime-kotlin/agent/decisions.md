# runtime-kotlin/ boundary decisions

## [2026-10-03] Reject mixed runtime packages before install promotion
Context: A packaged producer and its bundled schema disagreed on version pins. Checksums alone did not detect the mismatch that prompted LOCAL-274870733146662.
Decision: Check both staged CLI and MCP images against their own current and historical resource pins through database-free runtime-core composition before promoting either image.
Reason: A mixed package is an installation failure, so durable-state migration cannot repair it. Checking both candidates before promotion preserves the installed pair and avoids opening durable databases during package inspection.
Alternatives considered: Reuse doctor without proving database independence, or accept checksum-valid artifacts without schema parity. Neither establishes the required package contract.

## [2026-10-03] Publish planning migration with coupled child imports
Context: Shared preparation, subtask plans, and imported child outputs carry matching bytes, hashes, and provenance. Partial publication or a stale source would make resumed planning inconsistent.
Decision: Engine admission validates and converts the complete coupled set inside the existing immediate transaction, then publishes through guarded SQLite writes. Independent feature-task conversion joins execution admission and respects parent import ownership.
Reason: One transaction exposes either unchanged source or the complete target after interruption. Keeping conversion at readiness/resume preserves completed work and ownership without rehydrating children, appending completion events, or replaying finalization.
Alternatives considered: Provenance-only restamping, nested replacement transactions, and conversion during hydration, status reads, or installation do not preserve this publication boundary.

## [2026-10-03] Migrate only evidence-complete phase-output 0.6 records
Context: Exact version equality forced hard reset after schema upgrades even when retained planning remained usable. Historical readers and structural database migrations did not establish conversion safety.
Decision: Support preparation 0.2 with planning provenance 0.2 and phase-output 0.6-to-0.7, plus independent feature-task 0.6 outputs. Validate immutable source and target schemas separately, preserve supplied semantics, and refuse unsupported, corrupt, or source-valid non-convertible records.
Reason: Explicit support lets operators resume without discarding completed subtasks or commits. Missing target prose or failure evidence cannot be inferred from summaries or decoder defaults. This transition reuses planning without refresh and replaces blanket historical-version hard-reset guidance.
Alternatives considered: Version-token-only acceptance, automatic hard reset, speculative planning refresh, and a framework for unrelated schema families were excluded by the spec. Fresh agent prose retains its existing settlement behavior.
Revisit when: Another concrete transition has an evidenced conversion or requires scoped refresh of unfinished planning.

## [2026-10-02] Refuse incomplete build gates at admission and retire the active blocker on resume

A saved SKILL-390 child selected the generic review fallback before implementation.
Fresh routing selects Kotlin, but resume reconstructs the saved gate policy digest.
The repository fallback routing fix already protects fresh selection. Admission now
also refuses a required build gate without both command roles, before creating a
child or executing more phases on resume. Standalone validation keeps its existing
behavior. Crash recovery can still reconstruct missing gate inputs to release dead
workers without admitting them for execution.

The saved descriptor remains immutable. Replacing its commands changes execution
semantics, so recovery needs a reviewed mapping rather than automatic rerouting.
This change adds no mapping and rewrites no stored descriptor.

Operator resume now clears the active blocked reason in the same transaction that
reopens the phase. Retry evidence retains the previous reason, including supervisor
reasons absent from the phase record. Otherwise a later build block can lose its
current continuation outcome during reconciliation because an old timeout still
occupies the active blocked reason.

## [2026-10-02] SKILL-398 subtask 5: persistence failures as codes, and the split into SKILL-400
Context: The first implement attempt for subtask 5 blocked because 75 classes across six modules were too much for one phase that cannot compile. This subtask converts only the persistence and transport failures (database, telemetry HTTP, goal telemetry row, runtime-owned persistence boundary) and adds the shared pieces; the other areas moved to the unlaunched SKILL-400 bundle.
Decision: (1) Handled-set rule: `DatabaseAccessError` and `DatabaseBusyError` were plain `RuntimeException`s, so no `catch (SkillBillRuntimeException)` ever absorbed them. Unguarded catches a database failure can reach call `SkillBillRuntimeException.rethrowIfDatabaseFailure()` first. Guarded: `PlanDecompositionStop` (both catches, `persistDecomposeTerminal` is mandatory), `InstallCliCommands` replay (persisted selection read), `NativeScaffoldPayloadRun` scaffold invocation and `completeAuthoring`. Left unchanged after reachability checks: `FeatureTaskRuntimeRejectedOutputRecorder` (already rethrows non-diagnostic codes), `InstallStaging`, `AuthoringDiscovery`, `AuthoringMutation` (filesystem, rethrow), `ScaffoldWizardRun`, the other `NativeScaffoldPayloadRun` catches (payload read, encode, render), `SkillRemove` (filesystem port), `CliRuntime`. (2) `McpToolDispatcher.uncapturedAtMcp()` holds today's no-capture condition; a later conversion adds a code only when its former class was uncaptured. All nine classes here were captured or already shell-content failures, so it gains nothing. (3) Accepted framing change: former `RuntimeException` database failures that reach `CliRuntime` now print through the `SkillBillRuntimeException` arm without the `ClassName: ` prefix or the diagnostics record (known case: non-monitor `goal status`, still covered by `CliGoalStatusDatabaseFailureTest`). (4) `goalReviewPreparationFailure` takes its location from the first `skillbill.` stack frame, which is now the factory for factory-built failures. (5) `RuntimeOwnedFactUnavailable` becomes two entries, not one: `FACT_UNAVAILABLE` (engine boundary) and `REVIEW_FACT_UNAVAILABLE` (application boundary, thrown by the parallel review runner), because `CodeReviewStep.launchFailure` discriminated only the engine class and the application failure took the generic RETRYABLE arm. Readers match `FACT_UNAVAILABLE` only. (6) Both `invokeOrHandle` functions keep `runCatching` with the existing cooperative handling: detekt `TooGenericExceptionCaught` is active, `@Suppress` is banned, and no main site catches `Exception` by name. (7) `UnresolvedRemoteTransportPortError` became `error()`: it is reachable only by calling the component with an unresolved context, a composition defect. (8) `fetchProxyCapabilities` reads the response status as a value (execute, then check 404/405) instead of catching `TelemetryProxyRequestFailureError.statusCode`. (9) Test edits beyond type-to-code: assertions on removed properties (`operation`, `dbPath`, `condition`, `statusCode`, `detail`) assert the same fact from the message or `databaseAccessCondition(...)`. No expected CLI, MCP, wire or payload value changed. (10) The transition stays open: SKILL-399 and SKILL-400 classes still extend the legacy bases.
Reason: Codes keep the failure set and every message while removing nine classes; guards keep catches from widening onto failures that never reached them before.
Alternatives considered: keep `DatabaseAccessError` outside `SkillBillRuntimeException` (blocks the baseline reduction); one persistence code (changes the review-launch disposition); catch `Exception` in the boundaries (detekt violation).
Revisit when: SKILL-400 finishes and the legacy bases retire, at which point `rethrowIfDatabaseFailure` can be reviewed against the remaining catches.

## [2026-10-02] SKILL-398 subtask 4: Shell-content collapse starts with AgentAddon and GovernedReview, coded failures render their code label
Context: The first implement attempt for subtask 4 blocked because all 97 shell-content classes were too much for one phase. The conversion is split into the shared transition pieces plus the two smallest areas here, and the other seven areas in SKILL-399.
Decision: (1) Coded failures render `<CodeEnum>.<ENTRY>` through `Throwable.failureCodeLabel()` wherever a caught throwable's class name was rendered; uncoded throwables render as before. Pure null-message fallbacks (`message ?: x::class.simpleName`) are left alone, because a coded failure always carries a message and the label could never render there. (2) `isShellContentContractFailure()` is a transitional classification: each area conversion adds its code enum, and it is removed when `ShellContentContractException` retires. `ScaffoldFailureCode` is never added. (3) The shell-content conversion is split between SKILL-398 subtask 4 (AgentAddon, GovernedReview, edge sites) and SKILL-399 (the other seven areas).
Reason: Catch sites that name `ShellContentContractException` would miss a plain coded `SkillBillRuntimeException`, so the predicate keeps them handling the same failures until the class retires.
Revisit when: `ShellContentContractException` retires, or `LegacyFailureCode` is removed.

## [2026-10-02] SKILL-398: Failure model: results for expected outcomes, one runtime failure type with owner codes, defects via require/check
Context: The census in .feature-specs/SKILL-398-runtime-exception-reduction/investigation.md found 231 custom throwables in main, and 62% are never discriminated by type.
Decision: Three tiers. Defects use require/check/error() and are never caught for control flow. Expected outcomes are returned as results. Failures that end the run throw SkillBillRuntimeException with an owner-declared RuntimeFailureCode. A new custom Throwable earns its place only for a failure that crosses a boundary the runtime does not own and cannot be tier 3, with its reason recorded here. LegacyFailureCode.UNCLASSIFIED backs a transitional (message, cause) constructor while subclasses exist. custom-throwable-baseline.txt is a two-sided guard in FailureCodeTotalityArchitectureTest.
Reason: 62% of types are never discriminated, and both the CLI and MCP edges discard the type and print the message.
Supersedes: The "typed errors" retention of SKILL-349, SKILL-374 and SKILL-391, and the class-per-failure form of the SKILL-351/352/353 typed durable failures. Their ownership placement stands.
Alternatives considered: IllegalStateException with a code (the edges would catch real bugs as user errors); guard only (it freezes 231 classes); results for everything (it pushes unrecoverable failures through every signature). All three rejected; see investigation.md.
Revisit when: a boundary needs callers to discriminate a failure that a code on SkillBillRuntimeException cannot express.

## [2026-10-02] Recover expired worker leases on blocked goal children

The goal supervisor can kill a timed-out child before its worker cleanup runs, then
mark the workflow blocked. The old crash scan selected only running workflows.
Operator resume rejected the leftover worker lease as an incompatible execution
plan even when the stored descriptor matched the installed runtime.

Goal run preparation now reconciles the selected child's expired lease after
validating its execution plan and before child resume. Recovery requires an active,
expired lease and a confirmed dead process, then rechecks ownership, generation,
workflow status, immutable identity, and descriptor inside the write transaction.
The startup scan also includes blocked workflows. SQLite preserves their blocked
status, phase records, artifacts, and execution identity while releasing the lease
and recording the interruption reason. Only operator resume reopens a blocked phase.
Running crash candidates still transition to pending. Live workers, reserved
ownership, and uncertain process evidence remain untouched.

## [2026-10-01] Goal-review accounting types stay in workflow.model.goalreview
Context: SKILL-397 subtask 3 planned to move review accounting helpers into review.context.model.accounting to break a package cycle.
Decision: GoalSubtaskCommitFocusedAccounting and ReviewAccountingBoundedJson stay in skillbill.workflow.model.goalreview.
Reason: The first uses goalreview-internal decoders, so moving it creates an accounting-goalreview cycle. The second is JSON projection, which the typed-domain guards reject in a review domain package that the CLI imports.
Revisit when: the decoders become shareable without importing goalreview internals.

## 2026-09-29: Retain complete agent process output

Agent stdout and stderr have no byte retention limit. The previous 1 MiB
limit discarded output and blocked a completed review on SKILL-384. The
launcher now retains and forwards every byte through process completion.
Capture still reports incomplete drains and preserves cancellation and cleanup
failures. The existing truncation result field remains for adapter compatibility
and historical evidence. The JVM launcher no longer sets it.

A process regression emits more than 1 MiB on each stream and checks the
complete capture and live sink, including multibyte UTF-8 text and the final
completion marker.

This file records architectural and implementation decisions that span the
`runtime-kotlin/` boundary. Each entry is dated and explains the trade-off,
not the implementation detail.

## [2026-10-01] SKILL-395: McpToolPayloadKeys is owned by runtime-mcp; supersedes the 2026-09-24 runtime-contracts clause
Context: "[2026-09-24] runtime-contracts is a shared kernel: single-owner declarations move to their owner (SKILL-374 subtask 2)" kept `McpToolPayloadKeys` in `runtime-contracts` because `runtime-infra/sqlite` `ReviewRowMappers` read `McpToolPayloadKeys.REVIEW_SESSION_ID`. That read was a SQL column label, `review_session_id`, already owned by `ReviewFinishedTelemetryPayloadKeys.REVIEW_SESSION_ID`. The object also restated 46 of its 65 values from key objects in `runtime-contracts`.
Decision: `ReviewRowMappers` reads the column through `ReviewFinishedTelemetryPayloadKeys.REVIEW_SESSION_ID`. `McpToolPayloadKeys` moves to `runtime-mcp` `skillbill.mcp.shared` as an `internal object` holding only the 19 MCP-only values (tool, content, text, type, isError, envelope, payload, orchestrated, repo, dry_run, repository_identity, governed_spec_path, artifacts_patch, step_updates, and the five tool names). The `skillbill.contracts.mcp` package is gone. The 46 restated values are deleted and call sites reference the shared owners. `reason` uses `UpdateCheckPayloadKeys.REASON` and `kind` uses `ReviewAccountingPayloadKeys.KIND`, because they are the only public key-object owners of those values and minting an owner would collide with the runtime-contracts work. `WireVocabularyArchitectureTest` gains `mcp tool payload keys restate no shared payload key value`, which text-scans the object (runtime-core cannot reflect an internal runtime-mcp type) and asserts zero overlap with ten shared key objects.
Reason: no non-MCP module reads the object once the column label points at its owner, so the shared kernel carried an adapter-only vocabulary.
Scope: supersedes only the clause keeping `McpToolPayloadKeys` in `runtime-contracts`. The rest of the 2026-09-24 entry stands. The wire values, key order and error text are unchanged.
Revisit when: a second adapter module needs an MCP-only key, or a runtime-contracts key object gains one of the 19 values.

## [2026-10-01] SKILL-393: runtime-ports declares no repository-driving function; supersedes 2026-09-06 (b) for two manifest DTOs
Context: Decision (b) of "[2026-09-06] SKILL-233 subtask 2 audit round 3" kept `LoadedDecompositionManifest` and `ValidatedDecompositionManifestYaml` in `runtime-ports` because the SQLite adapter was expected to read them. SKILL-233's ports cleanup then regressed within 18 days: SKILL-372 moved repository-driving functions (manifest discovery, parent discovery, projection-failure persistence) and `GoalParentProjectionWriter` back into ports behind file exemptions in `PortsDeclarationArchitectureTest` and `RuntimeLayerBoundaryArchitectureTest`.
Decision: ports declares no top-level function with a `*Repository` receiver, and none with a `UnitOfWork`, `GoalRunnerPersistenceSession`, `DatabaseSessionFactory`, `WorkflowEngine`, `*Repository`, or `*Store` parameter. Derived extensions on a `*Store` receiver with plain parameters stay allowed. `PortsDeclarationArchitectureTest` enforces it with synthetic fixtures and has no file exemption. The behaviour moved to its one consumer: manifest discovery, parent discovery, and projection-failure persistence to `runtime-application`; `GoalParentProjectionWriter` to `runtime-engine`, `internal`.
Reason: no `runtime-infra/sqlite` file reads `LoadedDecompositionManifest` or `ValidatedDecompositionManifestYaml`. Their readers are application main and tests, engine main (`FeatureSpecPreparationWriter`, `DecompositionManifestEngineEncoding`), and core and engine tests. They now live in `skillbill.application.decomposition.model`, with the other decomposition write models. File exemptions let the regression land without a failing guard; the function rule removes the exemptions.
What else moved: the `DecompositionManifestProjectionWriter` interface had one implementation and no boundary, so it is deleted and consumers take `DecompositionManifestWriter`. The wire-artifact validator forwarders in `runtime-ports` are deleted; callers name the closed `FeatureTaskRuntimeWireArtifactKind`. `ReviewAttributionPort.composedLaunchPlan` is abstract. `REVIEW_EVIDENCE_BATCH_SIZE` has one owner in the launcher, and `INSTALLER_PROCESS_OUTPUT_CAP_BYTES` and `INSTALLER_OUTPUT_TRUNCATION_SENTINEL` are `internal` to `runtime-infra/host`.
Scope: this supersedes 2026-09-06 (b) only for `LoadedDecompositionManifest` and `ValidatedDecompositionManifestYaml`. The `WorkflowRecordMapping` half of (b) stands.
Revisit when: an adapter module needs to read those two DTOs, at which point only the DTO, not discovery or persistence behaviour, belongs back in ports.

## [2026-10-01] SKILL-387: use one prose content boundary with runtime-owned decisions
Context: Agent phases had competing response envelopes, strict schema gates, and formatting relaunches even though the domain already provided PhaseOutput with value and optional prompt.
Decision: Route agent content through PhaseOutput; keep process capture, terminal disposition, durable metadata, and semantic ledgers in their existing runtime owners. Interpret prose only in the phase owners that already decide review, finding, audit, or validation outcomes.
Reason: One content path accepts ordinary prose without weakening accepted-step authority or terminal precedence, and removes a duplicate response contract without inventing a new DTO or completion interface.
Alternatives considered: Retain strict response schemas or add a replacement completion contract (rejected: both preserve the wrapper failure that discarded usable work and create another authority).
Revisit when: A future phase needs authoritative content that cannot be carried by PhaseOutput.value and its existing semantic owner.

## [2026-10-01] SKILL-387: make the governed spec artifact the planning handoff
Context: Plan responses duplicated executable details, while goal planning could checkpoint a subtask hash before the persisted spec had been revalidated after authorship.
Decision: Standalone plan authors only its authorized local bundle and returns the canonical parent-spec path; goal planning writes its assigned subtask spec, validates readiness, recomputes its post-write hash, and checkpoints that artifact before hydration. Implementation rereads the selected spec.
Reason: The existing artifact writer, readiness checks, and checkpoint become one authority, preventing wrong, partial, escaped, or stale-hash plans without a second planning tree or parent replacement.
Alternatives considered: Carry executable plan text in the response or overwrite the parent spec (rejected: either creates competing authority or destroys sibling/subtask provenance).
Revisit when: Governed planning artifacts or their checkpoint owner change.

## [2026-10-01] SKILL-387: bound legacy extraction to persistence and recovery
Context: Fresh prose must bypass legacy response validation while durable historical envelopes and pending plans still need compatibility handling.
Decision: Extract supported historical content only at persistence/recovery boundaries; preserve accepted identity, attempts, completed effects, and terminal precedence, while routing unsupported records and unready legacy plans through existing typed recovery without conversion or replay.
Reason: This keeps compatibility observable and bounded without making old wire shapes gate fresh content or manufacturing new artifact authority from response text.
Alternatives considered: Blanket version bumps or automatic JSON-to-spec conversion (rejected: they either strand compatible history or silently create authority and replay risk).
Revisit when: Historical rows are migrated to a new authoritative format under an explicit schema and compatibility policy.

## [2026-09-29] Store execution semantics in the workflow creation transaction, SKILL-384
Context: New standalone workflows and goal children need the same semantic authority before launch, including children with imported planning state.
Decision: Store exactly one governed execution-plan descriptor in the workflow's database artifacts within its creation transaction. Pass validated resolved data through creation inputs, keep registry dependencies in the engine, and retain FeatureTaskExecutionIdentity for repository and spec lookup. Reject later descriptor replacement or removal.
Reason: Atomic creation prevents a durable run from existing without its execution semantics. One artifact avoids competing graphs in identity rows or phase records, and passing resolved data preserves inward dependency direction.
Alternatives considered: A separate table, per-phase descriptor copies, and a graph in the route identity would create additional authorities, so the spec excludes them.

## [2026-09-29] Retain complete agent process output
Context: The previous 1 MiB retention limit discarded output and blocked a completed review on SKILL-384.
Decision: Retain and forward complete agent stdout and stderr through process completion. Preserve incomplete-drain, cancellation, and cleanup failures. Keep the truncation result field for adapter compatibility and historical evidence, but the JVM launcher no longer sets it.
Reason: Discarding output can remove the completion marker and turn completed work into a blocked review. Complete capture preserves that evidence, including multibyte UTF-8 text.

## [2026-09-28] SKILL-383: skill-bill is the only listed skill; retired names stay on the wire
Context: After SKILL-380 and SKILL-382, every capability of the other listed skills already existed as a phase, a strategy, an operation, or the `/skill-bill` dispatcher. The other listed skills duplicated those routes, and pack specialists installed as sidecars of `bill-code-review`.
Decision: (1) `skill-bill` is the only listed skill. The `bill-feature`, `bill-feature-spec`, `bill-code-review`, `bill-code-review-inline`, `bill-code-check`, `bill-pr-description`, `bill-boundary-history`, `bill-boundary-decisions`, `bill-pr-review-fix`, `bill-unit-test-value-check`, `bill-update-check`, `bill-release`, `bill-feature-verify`, `bill-feature-guard`, `bill-feature-guard-cleanup`, and `bill-monitor` trees are deleted. Phases are reached as `phase:<name>` and operations as `operation:<name>` through `/skill-bill`. (2) Each deleted `content.md` body moved verbatim into `skills/skill-bill/content.md` or a directive resource its owner loads; Kotlin constants keep only output-contract text. (3) `bill-monitor` moved nowhere; `skill-bill goal status` stays CLI-only. (4) The `ParallelCodeReviewRunner` inline lane shape and `PARALLEL_REVIEW_INLINE_NATIVE_WORKER` are deleted: the SKILL-380 subtask 8 census found no production caller, and `DelegatedReviewStrategy` is the only request builder. An inline request now fails with `InlineParallelReviewUnsupportedError`. `attributeInlineFindings` runs on every lane, so it is renamed `attributeLaneFindings`, not deleted. (5) Pack specialists install as `internal-for: skill-bill` sidecars. Every deleted name is in `InstallLegacySkillNames`, so an install over an old home removes the stale links and copies. (6) These wire labels keep their retired values: telemetry `skill` values (`LifecycleTelemetryPayloads`, `TelemetrySettingsLoading`, `ReviewStatsContractWorkflowPayloadMappers`, `McpAdapterContracts`), the feature-verify `workflow_name` default `bill-feature-verify` and its migration, the workflow skill label `WorkflowStateWrites` writes, and the quality-check `routed_skill` `bill-code-check`.
Reason: one entry point matches the product surface and removes duplicate routes that could drift from the runtime. The labels in (6) stay because remote telemetry aggregates and stored workflow rows key on them; renaming them would split history and orphan stored rows.
Alternatives considered: keep the old skills as thin aliases of the dispatcher (rejected: two names per route, and aliases still name retired skills in prompts); rename the wire labels with a migration (rejected: remote telemetry cannot be migrated, and the rename buys nothing an operator sees); keep `bill-code-review-inline` as an unlisted sidecar (rejected: it had no caller).
Consequence: prompts and docs name `skill-bill phase review` or `skill-bill phase validation` instead of retired skills; a docs grep in the install-catalog test rejects `/bill-monitor` and `/bill-feature` as a listed skill. Telemetry and stored rows still show retired skill names, by design.
Revisit when: remote telemetry gains a label alias or re-key path, or a capability needs a listed entry the dispatcher cannot route.

## [2026-09-27] SKILL-380 subtask 6: delegated review returns with bounded lanes
Context: d6651d94b removed delegated review because a delegated lane could block forever. A lane launch had no idle bound, its progress probe was `NONE`, Claude ran non-streaming JSON (no output until exit), and the pass timeout was null, so no clock ever stopped a live, silent lane.
Decision: (1) Every review lane launch in the application (specialist parent, inline coverage continuation, claim verification, integration pass, spec adjudication) goes through `withBoundedLaneProgress` in `runtime-ports`: a progress idle bound (30 minutes by default, `ParallelCodeReviewRequest.laneProgressIdleTimeout`) under `DB_PROGRESS_ONLY`. (2) On a governed lane, evidence reads count as progress: the endpoint's read callback advances a counter and the lane's probe token is that counter. Ungoverned lanes use a probe that never advances, so the bound works as a no-progress cap. (3) `readOnlyPhase` stays false on lanes on purpose, because `HEARTBEAT_EXTENDED` treats a live process as progress and never stops a silent lane. (4) `DelegatedReviewStrategy` (id `delegated`) shares `CodeReviewSlot` with `inline` and runs the review step through `ParallelCodeReviewRunner` inside its `PhaseRunner` session. It is registered but unselected until subtask 8.
Reason: the hang came from the missing bound, not from delegation. Bounding the lane in one ports helper keeps each launch site to one call, and counting evidence reads keeps a lane that is still working inside the bound.
Alternatives considered: a wall-clock pass timeout (rejected: it stops slow lanes that are still working); `readOnlyPhase = true` (rejected: the heartbeat extends the lane forever); streaming output for liveness (rejected: it changes the provider transport for every review lane).
Consequence: a silent governed lane that reads no evidence for 30 minutes times out with "agent timed out" and fails its lane, where it used to hang. `ReviewLaneSilentUntilExitTest` pins both halves at the launcher.
Revisit when: a provider streams review output, or a lane needs a bound other than the read-only phase default.

## [2026-09-26] SKILL-380: a fixed slot skeleton with in-process strategies
Context: The run loop chose how to launch each phase by branching on phase ids, and launch policy lived in phase-id sets on the workflow definition and runner policies (`MUTATING_PHASES`, `OUTPUT_RETRY_PHASES`, `singleAgentSessionOnly`, `GENERATION_SCOPED_PHASE_IDS`, `NON_FILE_MUTATING_PHASES`). Every new way to run a stage meant another branch and another set.
Decision: (1) The runtime runs a fixed skeleton of slots (`PhaseSlot`), and each slot is run by one in-process `PhaseStrategy`. The graph, ledger, and durable phase ids do not change with the strategy. (2) There is one generic `PhaseRunner` implementation, `DefaultPhaseRunner`, and each strategy gets its own instance from an unscoped provider. There is one `PhaseRunState` port, and every composition (full run, phase run, operation) supplies its own implementation of it. (3) Every step crosses the AI boundary with the same input shape (`PhaseStepInput`) and the same output shape (`PhaseStepOutput`). (4) Selection is a code binding (`PhaseStrategySelection`, fixed or keyed by code review mode or quality gate), not operator config. (5) Strategies are registered explicitly in `RuntimeFeatureTaskSlotProvides`. There is no classpath scan and no multibinding.
Reason: one lookup replaces the phase-id branches in `runPreparedPhaseReady` and `FeatureTaskRuntimeCurrentPhaseExecutionDeriver`, and a strategy owns the policy of the steps it runs, so adding a strategy touches one class and one provider line. A single runner keeps launch, capture, token accounting, and settlement reads in one place. Separate runners per step type would each re-implement them.
Alternatives considered: a runner per strategy type (rejected: duplicates the launch path the single runner already owns); operator-configurable strategy selection (rejected: no second strategy exists for most slots, and a config surface would be a speculative feature); annotation- or classpath-based registration (rejected: an explicit list is reviewable, and a missing binding fails at composition with a typed error).
Consequence: `GoalRunnerSubtaskLauncher` is reached only through `DefaultPhaseRunner`. The last-commit review driver runs through the same runner as an untracked launch (no attempt, so no git capture, token record, or settlement read), and its unsupported-agent failure reason stays the launcher's own reason (`PhaseLaunchFailure.cause`), without the runner's phase prefix. Slot-baseline fixtures from subtask 1 are unchanged.
Revisit when: a slot needs two strategies that differ in which steps they run, or a strategy needs a run-state capability the shared port cannot express.

## [2026-09-25] SKILL-377: one flat git port, and no constant-result interface defaults in runtime-ports
Context: `WorkflowGitOperations` reached its capability groups through sub-port getters, and several ports shipped default bodies that returned a bare constant (`true`, `null`, `emptyList()`, `Unit`, `WorkflowGitOperationResult.Ok`). Both shapes let an implementation skip a capability without saying so: a getter can hand back a substitute, and a constant default answers on behalf of an implementation that was never written.
Decision: (1) `WorkflowGitOperations` is one flat interface that inherits the twelve capability interfaces and declares no sub-port getter, so every implementation supplies every member and the composition root binds a single object. (2) In `runtime-ports/src/main`, outside a `fun interface`, an interface default body that is a bare constant result is banned. `PortsDeclarationArchitectureTest` fails on `true`, `false`, `null`, `Unit`, an empty collection constructor, and `WorkflowGitOperationResult.Ok`/`Failed`, beside the existing `error`/`throw` rule; it reads interface bodies with the brace matcher so a nested declaration no longer truncates the scan. Derived defaults stay legal, and `fun interface` defaults are exempt because a SAM type has exactly one contract member.
Reason: a constant default is indistinguishable at the call site from a real answer. `ExperimentPairOwnerPort.acquireLease` returned `true` by default, so an implementation that never leased anything reported that it held the lease; `loadReport`/`listReports` reported "no report" for the same reason. Making those members abstract moves the omission to compile time, where the adapter author sees it.
Alternatives considered: keep sub-port getters with null-object substitutes (rejected: that is the silent-skip shape this entry removes); ban every interface default (rejected: derived defaults such as `GoalSubtaskPlanRepository.preparedPlanCount` and `boundedStatus` compute from parameters or other members and are genuinely shared by all implementations).
Consequence: `ExperimentPairOwnerPort`, `ExperimentPairRepository`, and `ExperimentDeferredPublicationPort` declare their reporting and lease members abstract. The SQLite adapters already implemented all of them, so no production behaviour changed; test doubles compose the runtime-ports testFixtures substitutes (`UnavailableExperimentPairOwner`, `UnavailableExperimentPairRepository`) with `by` and override only the members under test. `PortNullObjectCensus` also flags `class` declarations with the substitute name prefixes, not only `object` ones.
Revisit when: a port needs a shared default whose result is a constant by nature (a protocol version, say), or a capability group grows large enough that one flat git contract stops being reviewable.

## [2026-09-25] SKILL-377: WorkflowStateRepository is family-keyed; supersedes 2026-09-06 (c)
Context: Decision (c) of "[2026-09-06] SKILL-233 subtask 2 audit round 3" moved `save` / `saveRecord` / `get` / `getAll` / `list` / `latest` / `sessionSummary` off the `WorkflowFamily` enum and made them `WorkflowFamily`-receiver extensions in the file declaring `WorkflowStateRepository`, "beside the port they drive"; SKILL-358 retained that. It weighed keeping the members on the enum and moving the enum next to the port, but not family-keyed repository members. SKILL-377 investigation F-005 supplies evidence it did not have.
Decision: `WorkflowStateRepository` declares the seven members directly, each taking the family as its first parameter: `save(family, snapshot)`, `saveRecord(family, record)`, `get(family, workflowId)`, `getAll(family, workflowIds)`, `list(family, limit)`, `latest(family)`, `sessionSummary(family, sessionId)`. The SQLite adapter owns table selection (VERIFY reads the feature-verify table; TASK_RUNTIME reads the feature-task table as `FeatureTaskWorkflowMode.RUNTIME`) and owns chunking `getAll` at the 900 bound-parameter limit with a constant private to `runtime-infra/sqlite`. No top-level `WorkflowFamily`-receiver function and no batch-size constant remain in `runtime-ports` main. The feature-implement and runtime-alias repositories (`FeatureImplementWorkflowStateRepository`, `FeatureTaskRuntimeWorkflowStateRepository`), `FeatureImplementSessionSummary`, both `toContract` session-summary mappers, and the two session-summary contracts are deleted.
Reason (F-005 evidence): the ports-side extensions encoded storage-table selection and a SQLite bound-parameter limit (`WORKFLOW_SNAPSHOT_BATCH_SIZE = 900`) — adapter knowledge sitting inside the port. The SQLite adapter routed back out through the port-side helpers into its own methods, so the dispatch was a loop through the boundary rather than across it. The feature-implement and runtime-alias families existed only to be dispatched to: neither had a caller in application, engine, CLI, or MCP. Inherited members are not counted by detekt `TooManyFunctions`, and the flat aggregate stays under the threshold of 11.
Alternatives considered: keep the extensions and move only the batch constant to the adapter (rejected: table selection is the larger leak and would stay). Give each family its own repository interface (rejected: that is the alias shape this entry removes; two of the four families had no consumer).
Consequence: about 160 call sites across roughly 47 main files moved from `family.op(repository, ...)` to `repository.op(family, ...)`. Persisted row bytes, column values, and CLI/MCP workflow get/list/latest/continue output are unchanged. `FeatureTaskWorkflowStateRepository` (with `terminalizeLegacyProseFeatureTaskWorkflow` and mode-agnostic `getFeatureTaskWorkflow`), `GoalChildWorkflowStateRepository`, and `FeatureTaskRuntimeWorkerRepository` are unchanged.
Scope: this supersedes 2026-09-06 (c) only for the `WorkflowStateRepository` members. The rest of that entry, and the other SKILL-358 retention decisions (`GoalRunnerManifestStore` role split, `Path`, testFixtures defaults, `ReviewFinishedTelemetryPayload`, `NONE` on real default strategies), stand.
Revisit when: a family needs storage behaviour the adapter cannot express behind one keyed member, or a fifth family arrives whose table layout differs enough that the keyed `when` stops being the smallest dispatch.

## [2026-09-25] Prose phases settle through the MCP tools again; a printed value envelope is synthesized, not rejected
Context: SKILL-377 subtask 3's implement phase finished its work, then printed an envelope with `produced_outputs.value` but no root `status` or `summary`. The schema gate rejected it and, at the one-attempt output-gate budget, blocked the subtask. Both safeguards from 2026-09-05 (`6abebf225`) were gone: SKILL-233 (`4196e3f19`) deleted the settlement briefing when the run loop moved packages, and restored the `ProsePhaseOutputSynthesizer` refusal to recover an envelope that already carries `value`.
Decision: Preplan, plan, and implement briefings again pin workflow_id and attempt and tell the child to finish through `feature_task_phase_complete` or `feature_task_phase_block`; the printed envelope is demoted to a fallback. The attempt travels from the phase attempt loop to the prompt so the pinned value matches what the gate reads back. The synthesizer recovers a printed envelope whose `value` is present, defaulting absent status to completed as it already did for legacy shapes. `FeatureTaskRuntimePhasePromptComposerSettlementTest`, `ProsePhaseOutputSynthesizerTest`, and `FeatureTaskRuntimePhaseOutputWireSchemaEnvelopeTest` pin both halves.
Reason: The runtime needs a branch signal, not a hand-authored envelope, and the one-attempt budget (2026-08-20) assumes programmatic repair catches serialisation slips. A missing root field beside a real `value` loses no producer content.
Alternatives considered: Raising the output-gate budget (rejected 2026-09-02: a relaunch repeats the whole phase); inferring status only in the envelope walker (rejected: the synthesizer already owns prose-phase recovery).
Revisit when: settlement tools become unavailable to child agents, or a later phase gate needs structure that `value` cannot carry.
Superseded by: SKILL-387: use one prose content boundary with runtime-owned decisions (2026-10-01)

## [2026-09-25] Boundary memory entries are capped at 4096 bytes; verification truncates oversized bodies
Context: A SKILL-374 goal child crashed in verify_findings because one selected decisions.md entry exceeded the verification `max_body_bytes` (4096). The per-body cap loud-failed with `GoalVerificationBoundaryCapExceededError`, which the resolved-bodies prompt path did not catch, so the child exited 1 without a durable block. The repo also held 15 entries above the cap, and 38 legacy `## <date> — <title>` headings that the parser folded into the preceding dated entry (one "entry" measured 68 KB).
Decision: (1) Every `agent/history.md` and `agent/decisions.md` entry body is at most the verification `max_body_bytes`, measured the way `BoundaryMemoryHeadingParser` splits entries; `BoundaryMemoryEntrySizeRepoTest` in `:runtime-infra:workflow:repoTest` enforces it, and both boundary skills state the limit. (2) Under verification caps an oversized body is truncated at `max_body_bytes`, marks the resolution `truncated`, and the prompt says so; it no longer throws. This supersedes the SKILL-202 subtask 3 rule "loud-fail rather than truncate" for the per-body cap only; `max_selected_bodies` and `max_total_body_bytes` still loud-fail. (3) Legacy headings became `## [<date>] <title>` so each is its own addressable entry, and oversized entries were condensed without changing their headings.
Reason: The guard stops writers from producing entries the finding-verification step cannot read in full, and truncation keeps one old or hand-written entry from crashing a goal. A truncated body is still useful evidence, and the prompt flags the cut, so the verifier is never misled.
Alternatives considered: Raising the cap (moves the cliff, and the 32 KB verification total budget still binds); catching the error in the prompt path only (drops the evidence and keeps the crash risk in other callers).
Revisit when: verification needs whole entries it cannot fit, or the caps change.

## [2026-09-24] runtime-contracts is a shared kernel: single-owner declarations move to their owner (SKILL-374 subtask 2)

`runtime-contracts` had become the default home for anything more than one file
touched, so adapter DTOs, module-local `*Keys` objects, and schema locators sat in
the leaf every module compiles against: an MCP response or SQLite column change
recompiled the whole runtime.

**Decision.** A declaration stays in `runtime-contracts` only when two or more
production modules read or write it, or a `runtime-ports` signature exposes it.
Everything else moves to its one owner: the MCP skipped-result DTOs to
`runtime-mcp`, the telemetry-proxy request DTOs to `runtime-infra/http`, the
governed-review wire payloads to `runtime-infra/launcher`, the SQLite
materialization and review key objects to `runtime-infra/sqlite`, the goal
purge/reset key objects to `runtime-cli`, and so on. Every `*SchemaPaths`
locator — plus `logSchemaLoadFailure` — moves to
`skillbill.infrastructure.contracts.locator` in `runtime-infra/contracts`, the
module that stages the canonical YAML; `RuntimeArchitectureTest` now fails any
locator declared outside an `skillbill.infrastructure.*` package. Where an inner
layer only read a locator's `EXPECTED_SCHEMA_ID`, it reads a top-level
`*_SCHEMA_ID` constant in `runtime-contracts` instead, so ports, engine, and
sqlite no longer depend on the locator at all.

The move is package-and-module only: class names, member names, wire values, and
`toPayload()` output are byte-identical, and no Gradle module edge was added.

**Trade-off.** A declaration that gains a second production reader must move
back, paid when sharing appears rather than up front. In exchange the kernel's
contents are evidence of sharing, and adapter vocabulary recompiles only the
adapter.

**Corollary (F-003).** The two SQLite key objects restated dozens of values that
`SharedPayloadKeys`, `LifecycleTelemetryPayloadKeys`, `GoalTelemetryPayloadKeys`,
`ReviewFindingPayloadKeys`, `ReviewFinishedTelemetryPayloadKeys`, and
`ReviewVerificationSignalKeys` already owned. Those members are gone and the
adapters reference the shared owner; `WireVocabularyArchitectureTest` asserts the
zero overlap. `event_name` was restated in three objects with no shared owner at
all, so it is now `LifecycleTelemetryPayloadKeys.EVENT_NAME` — genuinely shared
between `runtime-mcp` and `runtime-infra/sqlite`.

**Supersedes.** This entry supersedes the 2026-05-28 clause "The pure
`*SchemaPaths` and `*_CONTRACT_VERSION` constants stay in `runtime-contracts`"
for the `*SchemaPaths` locators only. The clause still holds for the
`*_CONTRACT_VERSION` constants and for the two record-identity schema IDs
(`GOAL_PLANNING_PREPARATION_SCHEMA_ID`, `FEATURE_TASK_RUNTIME_PHASE_OUTPUT_SCHEMA_ID`),
which ports, engine, and sqlite all read.

**What stays, and why.** `JsonPayloadContract` stays in `runtime-contracts`
unchanged. It is the typed carrier that keeps raw `Map<String, Any?>` out of
inner-layer signatures. `runtime-ports` signatures expose it
(`IdeStatusValidator.toWirePayload`, `IdeStatusProblemDetails`,
`ReviewFinishedTelemetryPayload`), and application mappers, infra:contracts, and
infra:launcher use it too, so the placement rule keeps it shared. The learning,
review, lifecycle, doctor, and install-plan DTOs with a public
`toPayload(): Map<String, Any?>` also stay. Application or domain code builds
them and CLI or MCP calls `toPayload()`, so each has two production readers.
They can't move inward either: `RuntimeRawMapArchitectureTest` forbids a public
raw-map member in application, domain, and ports. `McpToolPayloadKeys` stays
because `runtime-infra/sqlite` reads `REVIEW_SESSION_ID` from it, which makes
two production readers.

**Exception under the no-new-edge rule.** `JvmSystemClock` has one production
reader (`runtime-core`), but tests in four other modules use it; moving it would
add test edges to the composition root, so it stays as the ambient clock seam.

---

## [2026-09-24] The architecture suite is a repository-contract suite with declared inputs

The architecture suite reads governed sources across the whole repository, not
runtime-core's own classes. It moved to `runtime-core/src/repoTest` so it runs in
`:runtime-core:repoTest` next to the other repository-contract suites instead of
riding `runtime-core`'s unit-test task. The move alone would have made the suite
worse: `repoTest`'s shared `governedRepositorySources` covers `skills`,
`platform-packs`, `orchestration`, `docs`, and the installer scripts, none of
which the scanners read, so Gradle would have reported UP-TO-DATE after a Kotlin
edit that broke a rule. `:runtime-core:repoTest` therefore declares its own input
set: every module's `src/**`, every `*.gradle.kts` (which covers
`settings.gradle.kts`), `ARCHITECTURE.md`, `agent/**`,
`build-logic/convention/src/**`, `config/**`, and `.editorconfig`. Installer and
launcher shell tests moved to `:runtime-cli:repoTest`, where the shared governed
inputs already cover `install.sh` and `uninstall.sh`.

Test placement follows one rule: the subject's module owns its test. runtime-core
keeps only composition and multi-adapter integration tests, all under
`skillbill.di.*`. Single-subject tests that happened to sit in runtime-core moved
to the module that owns the subject. Three borderline files stay in `skillbill.di.*`
because their subject is runtime-core composition or they need an infrastructure
adapter that `runtime-application` may not import: `SkillBillVersionTest`,
`RuntimeExperimentProvidesTest`, and `TelemetryLevelMutationServiceTest`.

## [2026-09-24] runtime-core is the only composition root

`RuntimeComponent`'s abstract accessors are the export list for the generated
Kotlin-Inject child components (`InjectCliComponent`, `InjectMcpComponent`). An
accessor with no generated or handwritten reader is dead export surface, so
`goalPlanningPreparationCheckpoint`, `uninstallPathsPort`,
`installedWorkspaceBaselineStatusPort`, and `featureTaskRuntimePhaseRecorder`
were removed while their bindings stayed. `PrincipleEnforcementInventory`
.`runtimeComponentInboundApi` is the pinned list.

`@JvmSynthetic` is gone from runtime-core, along with the
`runtimeComponentInternalProviderJvmLeaks` rule that required it. The annotation
hid provider signatures from Java callers that do not exist: runtime-cli and
runtime-mcp are Kotlin, and the generated components address providers through
Kotlin metadata. The SKILL-350 public-callable classifier stays; it is the rule
that actually keeps adapter types off runtime-core's inbound API.

The scaffold standalone harness (`ScaffoldStandaloneEntrypoint.kt`) moved from
runtime-infra/skills `src/main` to `src/test`. It constructed component-bound
adapters directly, which made it a second composition root that the guard had
to exempt. Only the scaffold test and repoTest suites ever called it, and
repoTest already carries the test output on its classpath, so the move costs
nothing and deletes the exemption list.

Composition inputs (`RuntimeContext`, `TransportContext`, `WorkflowOpsContext`,
`OptionalCallbacks`) now live in `skillbill.di.core`, because only the
composition root constructs or reads them. `EnvironmentContext` stays in
runtime-ports: adapters bind it. Keeping the four in `skillbill.di.core` means
no other `skillbill.di.<area>` package may import `skillbill.di.core` without
re-creating a package cycle, so the providers that read `OptionalCallbacks` or
`RuntimeContext` are collected in `RuntimeOptionalCallbackProvides` and
`RuntimeComponent` rather than left in `di.goal`, `di.install`, `di.review`, and
`di.experiment`. `runtime-core-package-cycle-baseline.txt` is now empty.

The packaged version is the typed `skillbill.model.RuntimeVersion`, not a
`String` binding: a graph keyed on `String` collides with any other string a
future provider needs. `SkillBillVersion.VALUE` remains the single read of the
packaged resource, keeping its missing-resource substitution record.

`WorkflowGoalRunnerOutcomeStoreDependencies` stays as an infra-owned `@Inject`
class. It is already constructor-injected, and flattening it is SKILL-376's to
own.

The experiment goal-runner factory is composition-owned: it creates a second
`RuntimeComponent`, which only the composition root may do.
`ExperimentGoalRunnerPort` stays in runtime-engine, where
`ExperimentPairCoordinator` reads it.
## [2026-09-24] Domain-owned durable artifact maps supersede duplicated cluster ownership (SKILL-372)
Context: Durable workflow artifacts are consumed by multiple adapters, while their keys and decoders were duplicated across engine, application, and SQLite.
Decision: Each artifact family owns typed reads and writes beside its domain model; adapters validate wire payloads and persist the resulting aggregate without indexing raw maps with domain keys.
Reason: One domain owner preserves byte-compatible encoding and makes malformed durable state fail at the read or write seam instead of becoming absence in one adapter.
Alternatives considered: Keeping per-adapter readers (rejected: divergent null and coercion behavior); moving storage coordination into domain (rejected: domain must remain free of filesystem, transaction, and validator concerns).
Revisit when: A family gains a genuinely open wire contract or its authoritative persistence owner changes.

## [2026-09-22] Build-logic declares one plugin classpath; modules keep ksp

`build-logic:convention` declares the Kotlin, Spotless, Detekt, and Badass
Runtime Gradle plugins as `implementation` rather than splitting them between
`compileOnly` in build-logic and `apply false` aliases in
`runtime-kotlin/build.gradle.kts`. Two half-strategies meant the version catalog
was read from two places and a convention plugin could compile against a plugin
the root build never put on the classpath. One `implementation` classpath makes
the convention plugins self-contained, so build-logic's own tests can apply them
through `ProjectBuilder`.

`ksp` is the deliberate exception: no convention plugin applies or configures it,
so an `implementation` entry would be an unused dependency. `runtime-core`,
`runtime-cli`, and `runtime-mcp` keep `alias(libs.plugins.ksp)` in their own
`plugins` blocks, and the version catalog stays the single version source either
way.

## [2026-09-22] Spotless has no ratchet; .editorconfig is the only formatter source

`ratchetFrom("origin/main")` is removed. The ratchet made formatting depend on a
ref that is absent in fresh CI checkouts and unresolvable from linked
`git worktree` clones (jgit does not follow gitdir files), so it cost a CI fetch
step and broke worktree-based work for a guarantee the tree did not need. Spotless
now formats every file it targets.

The ktlint `editorConfigOverride` maps are removed with it. `runtime-kotlin/.editorconfig`
is `root = true` and the only `.editorconfig` in the repository, so ktlint, Detekt
`MaxLineLength`, and the IDE all read the same 120-column limit and the same
trailing-comma settings from one file instead of two Kotlin maps that had to be
kept identical by hand.

## [2026-09-22] Convention-plugin tests live in build-logic, not runtime-core

Behavior owned by a convention plugin is asserted in
`build-logic/convention/src/test` with `ProjectBuilder`, against the observable
task graph and task properties. `runtime-core`'s architecture suite cannot apply
build-logic plugins, so its only alternative was reading plugin source text and
asserting on substrings — a test that passes when the wiring is broken and fails
when the source is merely reformatted. `RuntimeGradleModuleLayeringTest` keeps the
assertions it can make from the repository layout (declared modules, nested
directories, dependency direction) and no longer reads plugin sources.

`ProjectBuilder` is the default, so these tests stay cheap enough to run under
every `check`. Gradle TestKit is reserved for behavior that only exists once a
task executes; see the governed-resource entry below.

## [2026-09-22] runtime-infra/host owns the Java guard; build tooling declares it as an input

`skill-bill-java-guard.sh` is authored once, at
`runtime-infra/host/src/main/resources/skillbill/infrastructure/host/jvm/`, and
reaches `GateJvmResolver` as an ordinary module resource. It previously lived in
`build-logic/convention/src/main/resources` and was copied into the host module by
a `copyJavaGuard` governed entry, which inverted ownership: a build-tooling
directory shipped a runtime asset, and three consumers reached into that tree by
path. The guard is runtime behavior, so the module that ships it owns it.

Build tooling now consumes it rather than owning it. `StartScriptJavaGuard` takes
a `RegularFileProperty` that `RuntimeImageConventionPlugin` defaults to that path
under the root project, declared as an input of every `CreateStartScripts` task
and read inside the task action. Reading the guard at configuration time — as the
plugin-classpath resource lookup did — hid guard edits from up-to-date checks and
blocked configuration caching. `install.sh` and `uninstall.sh` source the same
file, so build-time and run-time JVM selection still follow one rule.

## [2026-09-22] Governed-resource behavior is tested against a synthetic project

`GovernedResourceCopyParityTest` copied `runtime-kotlin/` and `orchestration/`
into a temp directory, fabricated an `origin/main` ref, shelled out to `./gradlew`
for every assertion, and compared SHA-256 hashes of real schemas against a golden
manifest. It was the slowest suite in `runtime-infra/contracts` and it failed
whenever a schema's content changed for unrelated reasons. It is replaced by
build-logic tests that apply `skillbill.governed-resources` to a one-file project
in a temp directory and assert the four behaviors that can actually regress:
a missing source fails its copy task naming the owner and the absolute path and
writes nothing, a declared source lands under the generated root, `processResources`
runs the copy, and an unchanged source reports up to date.

These are the build-logic exception to the `ProjectBuilder`-only rule above:
task outcome, failure text, and up-to-date state do not exist until a task runs,
so they need Gradle TestKit. The start-script guard is tested through a pure
`startScriptWithJavaGuard` transformation instead, because the bug worth catching
is double insertion or a lost anchor, not the `doLast` wiring.

`GovernedResourceCopy` declares an `@OutputFile` per entry rather than an
`@OutputDirectory`. Thirty-five of the thirty-six `runtime-infra/contracts`
entries share one destination directory (`skillbill/infrastructure/contracts`;
`copyReviewContextSchema` is the lone exception at `skillbill/contracts`), so a
directory-typed output would overlap across tasks, disabling build caching and
weakening up-to-date checks for all of them.

`includeInTestProcessResources` is dropped with no replacement. The generated root
was only ever added to the `main` resources source set, so the `processTestResources`
wiring placed nothing on the test resource path, and no consumer outside the deleted
parity test read the distinction.

## [2026-09-19] Runtime package sibling ceilings

Production packages use a 12-sibling ceiling for non-model noun families and a
20-sibling ceiling for area-owned model packages. Packages with multiple noun
families nest those families below the area package instead of keeping mixed
files together. The model exception gives public inputs and results one
cohesive boundary while the lower non-model ceiling keeps services,
orchestration, and adapters small enough to locate and own.

## [2026-09-18] SKILL-359 subtask 2: TELEMETRY_PROXY_CONTRACT_VERSION stays in runtime-domain

`TELEMETRY_PROXY_CONTRACT_VERSION` remains in `runtime-domain` `TelemetryConstants.kt` with value `2` because it is the protocol version constant the HTTP client uses when the proxy omits `contract_version`. `SharedPayloadKeys.CONTRACT_VERSION` remains the map key on proxy payloads; wire keys for proxy fields live in `TelemetryProxyPayloadKeys` without moving the version constant beside them.

## [2026-09-18] SKILL-359: shared HTTP transport does not follow redirects

The shared JDK transport keeps redirect handling at `NEVER`; the live HEAD of
`https://raw.githubusercontent.com/Sermilion/skill-bill/main/install.sh`
returned 200 with zero redirects. Callers must receive the peer response
instead of silently crossing an unapproved URL boundary.

## [2026-09-18] SKILL-359: bootstrap remains the only transport fallback owner

`TransportContext.requester` fallback resolution remains only in
`RuntimeBootstrapBindings`. Adapters receive the bootstrap-resolved
`RemoteTransportPort`, so they do not mint or resolve another transport.

## [2026-09-18] SKILL-359: malformed workflow entries are observable and dropped

Non-string `supported_workflows` entries are recorded through
`RuntimeDiagnostics` and dropped while valid strings continue into the typed
capabilities result. A malformed extension entry does not invalidate the
well-formed capability fields.

## [2026-09-18] SKILL-359: message helpers remain local where no shared owner exists

The message-or-class-name helper copies in `UpdateCheckService` and
`TelemetryOutboxDrain` remain local because no shared owner exists in
`runtime-contracts` or `runtime-domain`.

## [2026-09-18] SKILL-358 subtask 2: typed accounting, wire-key owners, ports raw-map scanner

`ReviewAccountingRecord` carries `ReviewAccountingSummary`; SQLite encode/decode lives in
`runtime-infra/sqlite/review`. Wire keys are owned in `runtime-contracts` (`ReviewAccountingPayloadKeys`,
`ReviewFinishedTelemetryPayloadKeys`, `GoalSubtaskReviewInputPayloadKeys`, extended
`GovernedReviewEvidencePayloadKeys`). Goal review input artifacts are projected in the engine via
`goalReviewInputArtifactMap`. IDE status validation accepts `IdeStatusSnapshot`; schema wire projection is
private to `IdeStatusSchemaValidator`. `isBoundaryCarrierRawMapDeclaration` does not exempt public
`*Map` shapes under `runtime-ports/src/main`.

## [2026-09-18] SKILL-358: runtime-ports hold interfaces; errors and test defaults leave main

Port interfaces no longer ship `error`/`throw` default bodies; `WorkflowStateRepositoryDefaults`,
`GoalPlanningPreparationRepositoryDefaults`, and `ReviewEvidenceBrokerDefaults` in
`runtime-ports` testFixtures reproduce former stub behavior. `RejectedOutputDiagnosticError`,
`FeatureTaskRuntimeSharedEvidenceFingerprintContradictionError`, and
`GoalRunnerLaunchAuthorizationDeniedException` live under `skillbill.error` (contracts or domain
where module edges require). Governed review wire codec objects are internal under
`runtime-infra/launcher/.../review/`; `DecompositionManifestStore.writeBundleAtomically` is abstract
on the port. Discovery-era review-evidence operations and `NativeReviewOperationProtocol` are removed;
`RuntimeContext` is a single constructor over `EnvironmentContext`, `TransportContext`,
`WorkflowOpsContext`, and `OptionalCallbacks`. `PortsDeclarationArchitectureTest` guards ports main
source beside `RuntimeContractModuleImportRulesTest`.

## [2026-09-18] SKILL-357: MCP input schemas project from telemetry YAML

`orchestration/contracts/telemetry-event-schema.yaml` is the single source for
MCP argument validation and `tools/list` advertisement. `McpInputSchemaProjection`
strips envelope keys and inlines local `$ref`s; the hand-written
`McpInputSchemas.kt` table and `validateStrictArguments` walker are removed so
networknt `additionalProperties: false` is the one unknown-argument seam.

## [2026-09-18] SKILL-355: observed worktree edits are runtime-owned

The worktree edit journal is written only from the wait-loop
`AgentRunWorktreeEditObserver` via git numstat (`worktreeNumstat`). No MCP tool and no
agent receipt may insert journal rows. Declared-progress hints, if added later, must not
write the journal or count as durable workflow progress.

`phase_id` for goal children may lag one activity tick behind a step change because the
goal launch path resolves it from the memoized tick-reader window
(`GoalRunnerTickProgressReader`), not from a live workflow snapshot at persist time.

The per-workflow cap is 2000 rows (`WorktreeEditJournalPayloadKeys.MAX_ROWS_PER_WORKFLOW`);
overflow drops whole oldest ticks (grouped by `recorded_at`) and emits
`seam=worktree_edit_journal_cap`.

`combinedDiffStat` keeps its existing two-diff git invocation; it shares only the numstat
parser (`parseNumstatEntries`) with `worktreeNumstat`, which measures staged+unstaged against
HEAD in one `git diff --numstat HEAD` plus untracked insertions.

## [2026-09-18] SKILL-354 subtask 2: split infrastructure by measured ownership
Context: This entry supersedes the 2026-06-12 decision to keep
`runtime-infra/fs` as one adapter module. The measured module already
simulated ten boundaries with source sets, `friendPaths`, ten compile tasks,
and `verifyInfraFsAreaCompile`, recompiling 40,688 production lines eleven
times.
Decision: Keep seven nested infrastructure modules: `host`, `contracts`,
`skills`, `launcher`, `workflow`, `http`, and `sqlite`. The measured
dependency graph is `skills -> contracts, host`, `launcher -> skills, host`,
and `workflow -> skills, contracts, host`; `host` and `contracts` are leaves.
The profiles justify the cut: 22 of 26 contract files import networknt, seven
skills files import SnakeYAML, host owns JDK and environment adapters, launcher
owns child-process lifetimes, and workflow owns the Git, review, feature-task,
goal-planning, and validation adapters. Runtime-core consumes 28 skills, 22
workflow, 17 contracts, 12 host, and 6 launcher imports.
Reason: Gradle project edges make the measured boundaries compile-time
boundaries and replace repeated source-set compilation. Names consumed only
inside a module become `internal`; cross-module consumers retain public
visibility.
Alternatives considered: Keep one adapter module (rejected: the simulated
boundaries already impose the cost without real Gradle ownership), or split
by every area (rejected: skills areas share the scaffold/install lifecycle and
the measured graph does not justify additional projects).

## [2026-09-17] SKILL-353 subtask 3: platform-pack substance report Gradle task removed
Context: SKILL-353 subtask 3 census. `platformPackSubstanceReport` and `PlatformPackSubstanceReportMain` were referenced only from `runtime-infra/fs/build.gradle.kts` and feature-spec investigation prose. No CI workflow, script, or operator doc invoked the task. `RepoValidationCollected` already calls `PlatformPackSubstanceAudit.audit` on the `skill-bill validate` path.
Decision: Delete the Gradle `platformPackSubstanceReport` task and `PlatformPackSubstanceReportMain.kt`. Relocate the audit implementation from `scaffold/substance/` to `scaffold/platformpack/substanceaudit/` with the same `PlatformPackSubstanceAudit` entry point. Do not add a CLI report command — no measured operator caller.
Reason: The report task duplicated audit logic without a consumer; validate already enforces substance for well-formed repos.
Alternatives considered: `skill-bill` CLI subcommand via `ScaffoldCatalogGateway` (rejected: census found no operator use). Delete `scaffold/substance/**` wholesale without relocating audit types (rejected: would break repo validation until types moved).

## [2026-09-17] Empty produced_outputs is valid envelope payload
Context: Runtime-owned validate finishes without agent JSON, and the envelope schema rejected `produced_outputs: {}` before the runtime could stamp gate evidence.
Decision: Keep `produced_outputs` required as an object. Drop envelope `minProperties: 1`. Empty `{}` is valid when a phase has no payload. Phase allOf branches still require their shapes.
Reason: The envelope should not invent a payload for phases that have none. Plan, implement, audit, and other shaped phases still fail without their keys. Review and build keep their Kotlin gates.
Alternatives considered: A dummy `value: ""` stub, or skipping schema only for validate.

## [2026-09-17] Runtime engine bundle census and parameter threshold
Context: The runtime-engine boundary cleanup needs a repeatable count while
fact-only argument bags and port-carrying collaborators are dissolved.
Decision: Keep `LongParameterList.functionThreshold` at `6`. After dissolving
the remaining single-use planning and validation bags, the runtime-engine
production census is 97 `Args`, 23
`Context`, 5 `Inputs`, 0 `Deps`, and 7 `Boundaries` declarations. This is the
current census, not an exemption for the remaining fact-only bags; the
dissolution must not add another bag or suppression.
Reason: Six parameters is the existing detekt limit and leaves collaborator
constructors explicit. Raising it would hide a dependency surface that the
boundary cleanup is intended to expose.

## [2026-09-17] ARCHITECTURE.md is a guideline, not a case log
Context: The runtime architecture document had grown into a ticket-by-ticket inventory of files, censuses, and spec cases, and documentation tests froze that prose.
Decision: Keep `runtime-kotlin/ARCHITECTURE.md` as general module, ownership, and boundary rules. Architecture tests own numeric censuses and file inventories. This decisions log owns why a threshold or exception exists.
Reason: A guideline that lists current files and issue keys goes stale the moment the tree moves, and it stops being usable as architecture.
Alternatives considered: Keep per-file tables in the document and pin them with phrase tests.

## [2026-09-16] SKILL-350 subtask 1: component invocation snapshot and launcher lookup wiring

**Context.** Generated CLI and MCP graphs call parent `runtimeContext()` and
scoped providers directly. Re-entering `RuntimeBootstrapBindings` on each call
let database paths and telemetry config diverge after ambient `user.home`
changed, and repeated transport resolution could mint extra HTTP clients. The
injected `FileSystemAgentRunLauncher` constructor ignored composition
`ExecutableLookup`, so host PATH could launch a fixture even when callbacks
supplied a refusing lookup.

**Decision.** Memoize one resolved `RuntimeContext` on each `RuntimeComponent`
instance (lazy first call to `RuntimeBootstrapBindings.runtimeContext`). Keep
`RuntimeBootstrapBindings` as the sole ambient seam; keep `@RuntimeSingleton`
on stateful collaborators; leave stateless use cases invocation-local. Pass
composition `ExecutableLookup` into the `@Inject` launcher constructor; keep
internal defaults and explicit `AgentRunLauncher` overrides.

**Alternatives considered.** Rely on `@RuntimeSingleton` alone for providers
the generated graph calls as methods (does not stabilize direct parent calls).
Process-global context cache (rejected: breaks separate-component isolation).

**Limits.** `ExecutableLookup` is availability policy, not sandboxing. Infra
adapters may still apply `withProcessDefaults` when input carries unspecified
placeholders; the snapshot must already carry resolved environment facts from
bootstrap.

## [2026-09-16] Dev snapshot from latest stable tag, not git describe ancestry
Context: After a release, source builds must become the next patch SNAPSHOT so they are ahead of that release. `git describe` only sees ancestor tags, so an off-main release tag left `main` on the matching SNAPSHOT and update-check treated it as behind.
Decision: List every local `v[0-9]*` tag, take the newest stable `X.Y.Z`, and set `X.Y.(Z+1)-SNAPSHOT`. Release builds still use `RELEASE_VERSION`. Do not bump minor.
Reason: Patch is the unit that follows a patch release. Ancestry hides tags that exist but were not merged as the tagged commit. Treating same-base SNAPSHOT as newer would hide that class of tag mistakes instead of advancing the version.
Alternatives considered: Auto-bump minor after every release; override SemVer so `X.Y.Z-SNAPSHOT` is newer than `X.Y.Z`.

## [2026-09-16] SKILL-347 subtask 3: review composition, stateless update check, adapter census

**Context.** Subtask 3 collapses review runner wiring, deletes production-unused
telemetry and review adapters, removes feature-preparation aliases, and makes
update-check parsing invocation-local.

**Before census (production main).** `ParallelCodeReviewRunner` copied every
field from `ParallelCodeReviewRunnerPlanningBoundaries` and
`ParallelCodeReviewRunnerLaneLaunchBoundaries`, binding `parentReviewLauncher`
and `sharedEvidenceLocatorReader` twice while reconstructing planning, lane
launch, result assembly, and verification collaborators in the runner
constructor. `TelemetryConfigMutationRuntime` and `ReviewCommitSequenceResolver`
had zero production callers. `TelemetryConfigRuntime` only forwarded domain
telemetry parsers for `TelemetrySettingsFromStore`. `FeatureSpecPreparationRuntime`
exposed `prepareForFeatureImplement` and `prepareForGoal` with test-only callers.
`UpdateCheckService` kept `lastUnknown`, `releasePayloadMalformed`, and
`releaseEntryMalformed` as mutable fields across overlapping `check` calls.

**After census.** One `ParallelCodeReviewRunnerBoundaries` bag feeds
`ParallelCodeReviewRunnerComposition`, which is the sole production site that
binds `parentReviewLauncher` and `sharedEvidenceLocatorReader` into executable
collaborators; the runner sequences `run` only. Production declarations removed:
`ReviewCommitSequenceResolver.kt`, `TelemetryConfigMutationRuntime.kt`,
`TelemetryConfigRuntime.kt`. `TelemetrySettingsFromStore` calls
`parseTelemetryLevelValue`, `parseTelemetryBoolValue`, and
`parsePositiveTelemetryInt` directly; `TelemetryLevelMutationService` still uses
`TelemetryConfigMutations`. `FeatureSpecPreparationRuntime` keeps
`prepareForFeatureSpec` as the injected seam. `UpdateCheckService` returns
failures through invocation-local `ReleaseFetchResult` / `ReleaseSelection`
values. **`InstallAgentService` stays** (SKILL-238 decision unchanged): CLI
adaptation is not a rename.

**Architecture enforcement.** `RuntimeLayerBoundaryArchitectureTest` asserts the
composition root owns lane launch wiring, retired adapter files stay absent from
production main, and update-check parser flags stay out of service fields.

## [2026-09-16] JVM interrupt restoration stays in outer infrastructure

**Context.** `InterruptSignalPort` is the inward contract for restoring the
thread interrupt flag after `InterruptedException`. A JVM implementation in
`runtime-ports` let application sync helpers default to concrete thread APIs
without passing through `runtime-core`.

**Decision.** Keep `InterruptSignalPort` in `runtime-ports`. Implement
`JvmInterruptSignalPort` in `runtime-infra/fs` and supply it only from
`RuntimeComponent`. Application telemetry sync/drain APIs require callers to pass
the port explicitly.

**Reason.** Mechanism belongs in outer adapters; application code keeps
cooperative failure propagation without selecting environment APIs.

## [2026-09-14] Validate is collect-all, fix, exit; runtime confirms

**Context.** Validate blocked on agent `validation-evidence` JSON (schema cap=1)
even when the agent had finished. Fallback (`agentRunValidateFallback`) still
required that envelope. Prompts also forbade the agent from running collect-all,
so the child never saw gate output before the runtime confirmation.

**Decision.** Validate agent output is a finished signal, never evidence. The
agent runs pack `collect_all_full_gate_command` once, fixes, and stops with no
phase JSON. The runtime then runs `cache_bypassing_collect_all_full_gate_command`.
Still red starts a fresh validate session. Validate has two block reasons: no
installed platform pack declares `validation_gate`, and findings remaining after
3 restarts. Schema, process death, and agent JSON are not validate block reasons.

**Reason.** Pass/fail is the runtime confirmation. Agent JSON is not a check.

**Supersedes.** 2026-08-29 validate repair edit-only for the first collect-all;
2026-08-20 agent-run confirmation. Agent collect-all stays; confirmation is
runtime-owned.

## [2026-09-14] SKILL-238 subtask 2: what survives the single-implementation collapse

**Context.** The YAGNI sweep removes same-module interfaces that name exactly one
data bag and application services that only rename a port. Two calls in that sweep
were not mechanical and should not be re-litigated from the diff alone.

**Decisions.**

1. **Retain `InstallAgentService`.** The parent AC and the sub-spec both condition
   removal on the methods still being pure forwarders. They are not. Each of the six
   methods constructs a distinct request DTO (`InstallAgentPathRequest`,
   `DetectInstallAgentTargetsRequest`, `ClaudeConfigRootsRequest`,
   `CodexConfigRootsRequest`, `InstallAgentDirectoryRequest`,
   `InstallAgentTargetCleanupRequest`), unwraps `.path` / `.targets` / `.roots` /
   `.cleanup` from the response, and supplies default `home` and `environment`
   arguments that roughly fifteen CLI call sites rely on. Deleting it would push DTO
   construction into the CLI, which is real adaptation, not a rename. Revisit when
   `InstallAgentTargetPort` exposes the unwrapped shapes directly.

2. **Nullability is declared by the interface, not the binding.** When collapsing the
   goal-runner boundary groups, `phaseRecorder` and `unaddressedFindingsLedgerService`
   keep the *interface's* nullable declared types on the surviving data class, even
   though the deleted `Default*` classes declared `phaseRecorder` non-null. Every
   consumer read through the interface, so the nullable type is the behaviour that was
   actually observed — `GoalRunnerPerRunLoopAssembler` forwards `phaseRecorder` into
   `GoalRunnerIterationOutcome`, which takes it nullable. Taking the binding's non-null
   type instead would silently change downstream null handling and warnings-as-errors
   behaviour. The same rule applies to any future collapse of an interface/binding pair
   whose declared types differ: the interface side wins.

3. **The survivor sheds port vocabulary.** `skillbill.ports.*` owns the `*Port`
   suffix, and `*RolePortBindings.kt` named a bindings half that no longer exists, so
   the collapsed data bags are `*Boundaries` in `FeatureTaskRuntimePhaseGateBoundaries.kt`,
   `ParallelCodeReviewRunnerBoundaries.kt`, `GoalPlanningSweepBoundaries.kt`, and
   `GoalRunnerBoundaries.kt`. Constructor parameters follow the type
   (`checkpointBoundaries`, `laneLaunchBoundaries`); parameters still named `*Port`
   inside those bags (`timingPort`, `fanOutPort`, `pullRequestPort`,
   `repositoryEnclosingRootPort`) hold real `skillbill.ports.*` types and keep the
   suffix.

Revisit when: a second implementation of one of these collapsed groups appears, at
which point reintroduce the interface rather than branching inside the data class.


## [2026-09-14] SKILL-52.5 subtask 7: zero-tolerance raw-map enforcement

**Context.** SKILL-52.1 introduced `@OpenBoundaryMap` plus a Kotlin FQN
allow-list with ARCHITECTURE.md / SKILL-52.2 inventory parity. Subtasks 2–6
typed the remaining inner-layer public seams; subtask 7 removes the escape
hatches.

**Decisions.**

1. **Retire allow-list governance.** Delete the Kotlin allow-list constant,
   the allow-list sync script, the SKILL-52.2 inventory resource, and all
   architecture-test branches that parsed or ratcheted them.
   `RuntimeRawMapArchitectureTest` hard-fails any new public raw-map
   declaration in `runtime-application`, `runtime-domain`, and `runtime-ports`
   with no FQN grandfather path.

2. **Delete `@OpenBoundaryMap` from production.** The annotation and
   `skillbill.boundary` package are removed; wire maps belong in private or
   internal adapter code or in typed DTOs at real boundaries.

3. **Supersede 2026-05-29 — SKILL-52.3 subtask 4 item 2 (lifecycle payloads
   as permanent annotated open boundaries).** Lifecycle telemetry and similar
   forward-compatible event bags remain raw-map at the adapter seam, but
   enforcement follows the typed doctor/version pattern (item 1 of that entry)
   and private serializers — not annotation plus three-place allow-list
   lockstep.

Revisit when: a new inner-layer public API genuinely requires an open schema
extension with no stable per-key contract; that case needs a typed envelope or
contract version bump, not a restored allow-list.


## [2026-09-06] SKILL-233 subtask 2 audit round 3: the manifest port owns its own capability split; duplicate ports/application declarations collapse to the ports copy

**(a) `GoalRunnerManifestStore` is composed of four port-owned capability interfaces, superseding decision (a) of the audit-round-2 entry below.**
The six seam names (`GoalRunnerManifestLookup`, `…PauseOps`, `…ExecutionLease`, `…ControlCommands`,
`…PersistenceCommands`, `…ReviewCommands`) are now declared only once, `internal` in
`runtime-infra/sqlite`, where the delegating store is assembled. `runtime-ports` declares the same
34 members — signatures and default values byte-identical — across
`GoalRunnerManifestQueries`, `GoalRunnerManifestExecutionCommands`, `GoalRunnerManifestControlWrites`
and `GoalRunnerManifestStateWrites`, split on the port's own read/lifecycle/control/state axis rather
than mirroring the adapter's five delegate classes. No consumer import, call site or fake changed.
Alternatives considered: flatten all 34 members onto `GoalRunnerManifestStore` (rejected: detekt
`TooManyFunctions` caps an interface at 11 and the largest interface anywhere in the tree is 10, so
the flat port only compiles behind a new `@Suppress` the gate forbids); keep the ports names and
delete the `runtime-infra/sqlite` internals (rejected: it puts the adapter's delegation seams back
on the public port surface, which is what the criterion removes).

**(b) A behaviourally identical ports/application pair collapses onto the ports copy.**
`WorkflowRecordMapping` (`toSnapshot`, `toRecord`, the two session-summary `toPayload` mappers) and
the `LoadedDecompositionManifest` / `ValidatedDecompositionManifestYaml` DTOs existed byte-identically
in `runtime-ports` and `runtime-application`. The ports copy survives in both cases because
`runtime-infra/sqlite` reads it and cannot see `runtime-application`. Duplicate main-source
basenames: 27 -> 24, none added.
Alternatives considered: keep both and document them as distinct (rejected: the bodies were
identical, so the pair was one type spelled twice, not two types).

**(c) Behaviour that is not a DTO extension leaves `runtime-ports` even when it is small.**
`WorkflowFamily` drove `WorkflowStateRepository` through `save` / `saveRecord` / `get` / `getAll` /
`list` / `latest` / `sessionSummary`. The enum now holds only its definition in
`skillbill.ports.workflow.model`, and those seven members are `WorkflowFamily`-receiver extension
functions in the file that declares `WorkflowStateRepository`, so the repository-driving behaviour
sits beside the port it drives. `FeatureTaskRuntimeWorkerRepository` split into its own file to keep
that file under detekt's 45-function threshold. The validation-gate failure-message helpers moved to
`runtime-domain` `skillbill.workflow.taskruntime`.
Reason: an enum that reaches into a repository is not a DTO, and
`RuntimeLayerBoundaryArchitectureTest` requires the type itself to live in a `model` package.
Splitting definition from behaviour satisfies both without a new module.
Alternatives considered: move the enum with its members into `skillbill.ports.workflow` beside the
port (rejected: `public model declarations live in model packages` fails on it). Move the telemetry
payload contract into `runtime-domain` `skillbill.review.model` beside the DTO it projects (rejected:
`RuntimeArchitectureTest.review and telemetry domain models do not own json payload contracts` forbids
`JsonPayloadContract` under domain `skillbill.review`; the mapper now lives beside `ReviewRepository`
in `skillbill.ports.review`).
Superseded by: SKILL-377, WorkflowStateRepository is family-keyed (2026-09-25) — for the seven
`WorkflowStateRepository` members only; the rest of (c) stands.

## [2026-09-06] SKILL-233 subtask 2: ports hold interfaces and DTOs; path values leave `java.nio` at the domain edge
Context: `runtime-ports` carried 24 non-interface behaviour files (1,541 lines) outside its `model` packages, and `runtime-domain` imported `java.nio.file` in 15 files. The prior SKILL-233 entry deferred both to this subtask.
Decision: `skillbill.model.FileLocation` (a `@JvmInline value class` over the path string) is the domain- and port-facing path type; `skillbill.model.toPath` and `skillbill.ports.repository.toFileLocation` in `runtime-ports` are the only bridge to `java.nio.file.Path`, and adapters own the conversion. Behaviour clusters left `runtime-ports` for `runtime-infra/sqlite` (`skillbill.db.goalrunner`, `skillbill.db.workflow`, `skillbill.db.decomposition`) rather than `runtime-application`, because the only consumer of each was `runtime-infra/sqlite` and the two are siblings. `JvmSystemClock` landed in `runtime-contracts` (`skillbill.contracts.time`), not `runtime-domain`, because domain effect purity forbids `System.currentTimeMillis`. Files that were pure extensions over a port type in the same package were folded into the file declaring that type (`WorkflowGitOperations`, `GoalRunnerControlRepository`, `FeatureTaskExecutionIdentity`) instead of moved.
Reason: A port file that declares no interface is behaviour the inside cannot substitute. Moving that behaviour to the one adapter that calls it keeps the port surface substitutable without inventing a new shared module; `FileLocation` removes the filesystem dependency that forced the behaviour into ports in the first place.
Alternatives considered: Move the clusters to `runtime-application` (rejected: `runtime-infra/sqlite` cannot see it). Introduce a shared module below both (rejected: no second consumer; a new module for one caller is not a boundary). Keep `java.nio.file.Path` in domain signatures and guard only the imports (rejected: the import is the symptom, the signature is the coupling).
Consequence: The `skillbill.db.decomposition` copy shrank from seven files to one — infra-sqlite reached only six of the thirty-one declarations the ports copy carried, so the rest were deleted rather than relocated. Six near-duplicate basename pairs remain between `runtime-application` and `runtime-infra/sqlite` (`GoalContinuationArtifactCodec`, `GoalParentProjectionWriter`, `GoalRunnerWorkflowFamilyLookup`, `LegacyGoalRunnerControlMigration`, `DecompositionWorkflowRuntimeLookup`, `DecompositionWorkflowRuntimeLookupParentDiscovery`). They are distinct types: the copies diverge (3–31 differing lines each), each is live in its own module, and no module below both can hold them now that `runtime-ports` is interface-and-DTO only. Two call sites lost implicit CWD resolution of a relative path: `SkillRemoveErrorSanitizer.parseRepoRoot` and `InstallPlanPolicyChecks.validatePath` now compare the given text rather than a working-directory-resolved absolute path.
Revisit when: a third module needs one of the six duplicated clusters — that is the second consumer that would justify a shared module — or `DecompositionManifestStore` stops taking `java.nio.file.Path`, which would let the decomposition pair collapse.
Superseded by: Domain-owned durable artifact maps supersede duplicated cluster ownership (2026-09-24). Also superseded in part by SKILL-377 (2026-09-25) for the path type: `java.nio.file.Path` is the port-facing path type, `FileLocation` is a domain value, and `toPath`/`toFileLocation` are the two bridges between them.

## [2026-09-04] Deletion-elision path for goal-subtask review retired, not bypassed
Context: SKILL-232 subtask 1 swept confirmed-unused `internal` declarations. `withinReviewInputBound` in `runtime-infra/fs` was unreferenced, and every other symbol in `GoalSubtaskReviewDeletionElision.kt` (`goalReviewDiffArguments`, `goalReviewNumstatArguments`, `ownedPathspecArguments`, `fitsReviewInputBound`, `deletionElidedDelta`, `deletionManifest`, `deletionManifestEntry`, `NUMSTAT_FIELD_COUNT`) was reachable only through it.
Decision: Delete the file whole rather than trimming the single unreferenced entry point. SKILL-224 (be9b56edb) replaced the materialized tracked delta in `GitGoalSubtaskReviewOperations.kt` with a scope-fingerprint string; `trackedDelta` is no longer a diff, so the byte-bound elision branch can never fire. Treat that redesign as retiring elision, not as a temporary bypass.
Reason: A file whose only entry point is dead because an upstream representation changed is obsolete design, not an accidentally orphaned call site. Keeping it would preserve a numstat-parsing and deletion-manifest path that no producer can reach and that a future reader would mistake for live review-input bounding.
Alternatives considered: Delete only `withinReviewInputBound` and leave the helpers (rejected: leaves eight symbols with no reachable caller, which the next sweep would delete anyway). Restore elision against the fingerprint representation (rejected: out of scope, and the fingerprint carries no byte size to bound against). Keep the file as documentation of the prior approach (rejected: git history already holds it).
Revisit when: goal-subtask review needs input-size bounding again — restore from be9b56edb's parent rather than reviving this file against the fingerprint, and re-decide how the bound reads a size the fingerprint does not carry.

## [2026-09-03] SKILL-231 subtask 3 tasks 9–11: port placement, repository root seam, intra-module role-port bindings
Context: SKILL-231 subtask 3 tasks 9–11 relocate misplaced port interfaces, empty ambient-clock and package-cycle baselines where reachable, route `toRealPath` through `RepositoryEnclosingRootPort`, and record binding policy.
Decision: `IdeStatusValidator` and `SkillRemoveFileSystem` live in `runtime-ports` (`skillbill.ports.idestatus`, `skillbill.ports.skillremove`). `SkillRemove` orchestration moved to `runtime-application` so `runtime-domain` does not depend on `runtime-ports`. `RepositoryEnclosingRootPort` owns `canonicalPath`, `optionalRealPath`, and `repositoryIdentity`; `CanonicalRepositoryRoot` is the sole production adapter. Intra-module role-port binding types (`*RolePorts`, `*RolePortBindings`, `Default*Port` adapters) stay in `runtime-application` — they are composition helpers, not cross-module ports.
Reason: Domain must not import ports; filesystem canonicalization belongs behind one repository-root seam; application-local injectable groupings are not misplaced port surfaces.
Alternatives considered: Keep `SkillRemoveFileSystem` in domain (rejected: domain→ports edge). Call `Path.toRealPath()` from application services (rejected: task 11). Move role-port bindings to `runtime-ports` (rejected: no second consumer; would blur application composition).
Revisit when: a second module needs the same role-port grouping, or repository-root policy splits across multiple adapters.

Context: SKILL-231 subtask 3 tasks 6–8 collapse composite ports, rename technology-leaking port names to capability names, and record genuinely open wire decoders.
Decision: Rename `UninstallFileSystemGateway` → `UninstallPathsPort`, `HttpRequester` → `RemoteTransportPort`, `HttpResponse` → `RemoteTransportResponse`, and the decomposition-manifest ports to `DecompositionManifestStore`, `DecompositionManifestPersistencePort`, and `DecompositionManifestDiscoveryPort`. Adapter class names (`FileSystemUninstallFileSystemGateway`, `FileSystemDecompositionManifestFileStore`, `JdkHttpRequester`) stay unchanged. The `*GitOperations` family is explicitly excluded from this rename set — git is the workflow capability, not an implementation leak.
Reason: Port names describe what the inside needs; adapter names describe how the outside provides it. Git operations are domain vocabulary for workflow finalisation, not HTTP/SQLite-style adapter leakage.
Alternatives considered: Rename `*GitOperations` for symmetry (rejected: spec non-goal; git is the capability). Rename adapters with ports (rejected: spec criterion 8 leaves adapter names alone).
Revisit when: a second transport implementation needs a distinct capability port, or git operations split across non-git adapters.

## [2026-09-03] Wire-string decoder fallbacks stay open
Context: SKILL-231 subtask 3 task 8. Several decoders accept legacy or unknown wire values from durable artifacts and operator-produced output.
Decision: Keep open `else` fallbacks in `GoalSubtaskReviewSummarySanitize.CompactFindingSeverity.from` (unknown severity → `OTHER`), `FeatureTaskRuntimeQualityGateSelection.fromWire` (unknown/null → `VALIDATE`), `classifyDurableChild` (unknown workflow status → `INCOMPATIBLE_TERMINAL`), and `goalContinuationTerminalStatus` (unknown status → `null`). These sets are genuinely open at the wire boundary.
Reason: Durable artifacts and agent output can carry values written before a schema bump or outside the closed enum; failing closed would wedge recovery and status reconciliation on historical rows.
Alternatives considered: Exhaustive decoders with typed errors (rejected: would block operator repair and self-heal paths on legacy wire values). Silent swallow without a recorded decision (rejected: SKILL-231 requires naming open sets).
Revisit when: a schema migration retires the legacy wire values and the decoders can fail loudly on unknown input.

## [2026-09-03] Load-bearing thin ports retained across SKILL-231 subtask 3 collapse
Context: SKILL-231 subtask 3 tasks 4–5 delete pass-through application services and collapse nine thin ports whose only caller was a CLI/MCP adapter. Four of the nine cross a module boundary the DI graph needs and have more than one consumer or a non-trivial composition role.
Decision: Keep `ExternalAddonOverlayPort`, `ExternalAddonSourceConfigPort`, `CheckedOutBranchSource`, and `GoalPlanningBoundaryBodyResolver` as named ports. Collapse the other five by deleting `RepoSourceDiscoveryGateway` (unused) and removing `ScaffoldService`, `ScaffoldCatalogService`, `UnsupportedScaffoldService`, `McpRegistrationService`, `NativeAgentInstallService`, and `RepoValidationService` so CLI/MCP resolve `ScaffoldGateway`, `ScaffoldCatalogGateway`, `UnsupportedScaffoldGateway`, `InstallMcpRegistrationPort`, `InstallNativeAgentLinkPort`, and `RepoValidationGateway` through `RuntimeComponent`.
Reason: A port whose sole justification was a one-line application wrapper is not a boundary; ports that separate install overlay config from overlay IO, inject git branch discovery for IDE status, or isolate goal-planning body resolution across application and infra-fs remain load-bearing.
Alternatives considered: Collapse all nine in one pass (rejected: would inline infra-fs adapters into application services or blur install/planning boundaries). Keep every gateway as an application service wrapper (rejected: the wrappers renamed nothing and added no policy).
Revisit when: a second consumer needs the retained ports through a different composition path, or overlay/branch/body resolution moves behind a broader capability port with multiple implementations.

## [2026-09-02] Empty architecture baselines are the permanent floor
Context: SKILL-227 subtask 3 emptied the logical-type line-ceiling and application-package-cycle baselines after god-object decomposition and cycle breaks. Subtask 1 had baselined seven cycle pairs as shrink-only.
Decision: Keep both baselines empty. Any new ceiling offender or application-package cycle fails the guard. Do not reintroduce *Helpers*/*Extras*/*Support* files solely to stay under the per-file line ceiling — fold into the owning type or a named domain collaborator instead.
Reason: Shrink-only measurement did its job; an empty floor is the enforceable target. Filename-suffix splits hide god objects from the logical-type ceiling without reducing complexity.
Alternatives considered: Keep a non-empty shrink-only residual (rejected: would allow the debt to persist indefinitely). Raise the ceiling or add exemptions (rejected: defeats the guard). Permit Helpers/Extras splits when a type is near 500 lines (rejected: that is how the prior evasion landed).
Revisit when: a measured logical type legitimately cannot be split without harming a boundary, and the alternative is an explicit named exemption with rationale — not a suffix file.

## [2026-09-03] Shrink-only infra ambient-environment baselines
Context: SKILL-231 subtask 1 records ambient-environment baselines for every module. The three `runtime-infra-*` modules legitimately read host environment variables and working-directory paths inside filesystem, HTTP, and SQLite adapters.
Decision: `runtime-infra/fs`, `runtime-infra/http`, and `runtime-infra/sqlite` ambient-environment baselines are shrink-only ceilings, not targets that must reach zero. Reading the host environment is what an adapter does; the boundary is that a policy decision may not depend on an ambient read.
Reason: Empty-by-rule baselines from the 2026-09-02 permanent-floor decision bind only the eight baselines that were already empty on main. Module baselines recorded in SKILL-231 are shrink-only ceilings that may only shrink.
Alternatives considered: Force infra baselines to zero in this subtask (rejected: would require rewriting every adapter before measurement lands). Treat infra reads as permanent baseline entries with no shrink path (rejected: adapter refactors should still be able to narrow ambient coupling over time).
Revisit when: a refactor removes the last ambient read from an infra module and the recorder empties that module's baseline.

## [2026-09-03] Single runtime-core composition ambient seam
Context: SKILL-231 widens ambient-environment measurement to every module. `runtime-core` composition reads ambient input at `RuntimeBootstrapBindings.runtimeContext`.
Decision: That seam is the single named composition entry point for ambient input. It is documented here rather than treated as a permanent baseline entry to shrink away.
Reason: Empty-by-rule baselines from the 2026-09-02 permanent-floor decision bind only the eight baselines that were already empty on main. Module baselines recorded in SKILL-231 are shrink-only ceilings; the composition seam is an intentional boundary, not debt to baseline away.
Alternatives considered: Add the seam to the ambient-environment baseline permanently (rejected: would block the one legitimate composition read). Exempt `runtime-core` from ambient-environment scanning (rejected: would leave the module unmeasured).
Revisit when: composition can receive ambient input only through an injected port with no direct `System.getenv` / `Path.of("")` call sites outside tests.

## [2026-09-03] CLI/MCP presentation-layer duplication is intentional at the boundary
Context: SKILL-231 subtask 2. `runtime-cli` and `runtime-mcp` each carry a parallel presentation stack shaping the same application services into different operator surfaces.
Decision: Keep fourteen mirrored pairs side by side. Format-specific pairs (two payload dialects a hexagonal boundary is expected to produce): `Component` (`CliComponent` / `McpComponent`), `Runtime` (`CliRuntime` / `McpRuntime`), `WorkflowContinueMaps` (`WorkflowContinueCliMaps` / `WorkflowContinueMcpMaps`), `WorkflowContinueBranchMapsCore`, `WorkflowContinueBranchMapsDecomposition`, `WorkflowGoalObservabilityMapping`, `WorkflowResultMappers`, `ReviewResultMappers`, `TelemetryResultMappers`, `LearningPayloads`, `Main`. Copy pairs (same shaping, no format reason — merge is a separate feature): `ScaffoldCommandRequestParser`, `ScaffoldCommandRequestParseHelpers`, `ScaffoldCommandRequestBaselineLayerParser`.
Reason: CLI argv/stdout and MCP JSON-RPC/maps are different presentation dialects; several continuation and result mappers exist only to translate application outcomes into those dialects. The scaffold parsers duplicate the same raw-map → `ScaffoldCommandRequest` decode at both entry adapters instead of sharing a module.
Alternatives considered: Extract a shared presentation module in this subtask (rejected: non-goal; SKILL-231 records the overlap only). Merge scaffold parsers without a shared module boundary (rejected: would blur entry-adapter ownership).
Revisit when: a follow-up feature extracts shared scaffold parsing or a third entry adapter needs the same decode.

## [2026-09-03] runtime-mcp process-boundary exemption for ambient environment reads
Context: SKILL-231 subtask 2. `runtime-mcp` must not read `System.getenv` or `System.getProperty` outside the process entry. `Main.kt` reads the environment once to choose bridge versus stdio server and passes that map downstream.
Decision: `runtime-kotlin/runtime-mcp/src/main/kotlin/skillbill/mcp/core/Main.kt` is the one place the MCP process may read its own environment. The exemption is a named entry on `PrincipleEnforcementInventory.ambientEnvironmentExemptions`, not a baseline row; the `runtime-mcp` ambient-environment baseline stays empty behind it. The repository-root seam in `RuntimeBootstrapBindings.runtimeContext` now canonicalizes explicitly supplied roots through `RepositoryEnclosingRootPort` as well as unspecified ones.
Reason: A process boundary read is intentional; recording it in the baseline would treat legitimate entry behavior as shrinkable debt. Empty baseline equality still bans every other ambient read in `runtime-mcp` main source.
Alternatives considered: Build `RuntimeComponent` in `Main` to resolve environment (rejected: pays component construction and filesystem walk in a worker subprocess that needs neither). Leave `GovernedReviewEvidenceBridge.run` with a `System.getenv()` default (rejected: re-reads behind the injected map).
Revisit when: MCP launch receives environment through an injected port with no `Main.kt` read, or a second legitimate process-boundary site appears.

## [2026-08-31] RuntimeSingleton lives in skillbill.application.runtime
Context: SKILL-227 subtask 1 needed a kotlin-inject scope for services that hold caches, connections, or leases. Placing `@RuntimeSingleton` under `skillbill.di` failed `ImplementationOwnershipArchitectureTest`.
Decision: Define `@RuntimeSingleton` in `skillbill.application.runtime` and apply `@Provides @RuntimeSingleton` on composition-root bindings unchanged.
Reason: Application-legal packages may own injectable annotations consumed by runtime-application; `skillbill.di` is composition-root wiring only and must not host types that application modules import as API.
Alternatives considered: Keep the annotation in `skillbill.di` and widen the ownership allow-list (rejected: would re-open application→di type ownership). Duplicate annotation per module (rejected: one scope must apply across the graph).

## [2026-08-31] Package-cycle baseline is shrink-only at seven pairs
Context: Preplan named four `skillbill.application.<area>` mutual-import cycles; the recorded scan found seven. Subtask 1 ships measurement before structural shrink.
Decision: Baseline all seven pairs and fail on any new cycle or growth; do not delete or rewrite production packages in this subtask to reach the preplan count.
Reason: Guards define the target later subtasks shrink; inventing a smaller baseline would hide existing cycles and defeat shrink-only policy.
Alternatives considered: Force the four-pair baseline by moving types now (rejected: non-goal for subtask 1). Drop unlisted pairs from the baseline (rejected: would allow growth of unmeasured cycles).
Revisit when: subtask 3 (or later) empties the baseline.
Superseded by: Empty architecture baselines are the permanent floor (2026-09-02)

## [2026-08-29] Capability vocabulary boundaries for fallback capabilities, entrypoints, and idle policy

Context: SKILL-220 subtask 3 (P-07). Platform manifests carry `fallback_capabilities` as open strings, native-agent add-on declarations name `entrypoint` paths as manifest strings, and `AgentRunIdlePolicy` is selected at compile time per agent command builder.
Decision: All three stay open at their current boundaries. `fallbackCapabilities` remains `Set<String>` validated for shape only in `ShellContentLoader.parseFallbackCapabilities`; the closed review-routing vocabulary (`CODE_REVIEW_CAPABILITY` / `CODE_REVIEW_FALLBACK_CAPABILITY`) converts to manifest strings at `PlatformReviewRouting` only. Native-agent `entrypoint` slots remain manifest-relative path strings validated by the platform-pack schema and `ShellContentLoader` add-on wiring — not a closed enum, because each pack names pack-owned files. `AgentRunIdlePolicy` stays a compile-time closed set (`HEARTBEAT_EXTENDED`, `DB_PROGRESS_ONLY`, `OUTPUT_EXTENDED`) chosen in each `*AgentRunCommandBuilder`; it is not wire-decoded from operator or agent output.
Reason: Fallback capabilities and entrypoints are pack-authored extension surfaces; closing them would reject valid pack content without a migration path. Idle policy is already closed where it matters — agent builders — and is never read from untrusted JSON.
Alternatives considered: Closed enum for `fallback_capabilities` with typed failure on unknown values (rejected: would break extensibility for future fallback lanes without a schema bump). Closed enum for entrypoint slots (rejected: filenames are pack-local). Wire-decoded idle policy (rejected: no untrusted input path exists).

## [2026-08-29] Production file line ceiling at 500 lines

Context: SKILL-220 subtask 4–6 split fifty-two production files that exceeded 500 lines; subtask 7 added a mechanical guard.
Decision: Production `src/main` Kotlin under the runtime modules, `intellij-plugin`, and `runtime-kotlin/build-logic` must stay at or below 500 lines. `PrincipleEnforcementInventory.productionLineCeiling` is `500`; `productionLineCeilingExemptions` is empty at program completion. Test `src/test` and `src/testFixtures` sources are out of scope for this ceiling.
Reason: Matches v2 SKILL-18 while acknowledging this tree's larger orchestration files were split rather than exempted. Test-only size is deferred to review and SKILL-221 complexity work.
Alternatives considered: Apply the ceiling to test sources (rejected: test fixtures and architecture scanners legitimately exceed 500 lines). Permanent exemptions for orchestration types (rejected: subtasks 4–6 already split them).

## [2026-08-29] Inline FQN scanner keep-list

Context: SKILL-220 subtask 2 swept ~1,100 inline FQNs; subtask 7 added `InlineFqnArchitectureTest` over `PrincipleEnforcementInventory.inlineFqnScanRoots`.
Decision: The scanner bans inline references for prefixes `java.`, `javax.`, `jakarta.`, `kotlin.`, `kotlinx.`, `org.`, `com.`, `dev.`, and `skillbill.` in production and test Kotlin. Keep-list: `package` and `import` lines; string literals (including architecture-test fixtures and `ARCHITECTURE.md` inventories); generated sources; compiler-required disambiguation after `import … as …` fails. KDoc and comment mentions are not scanned.
Reason: Expression-position FQNs defeat import hygiene and hide collisions; documentation and literal allow-lists must name types without forcing false positives.
Alternatives considered: Scan KDoc and comments (rejected: subtask 2 explicitly left them optional). Narrow prefixes to `java.` only (rejected: `skillbill.` inline references were the dominant defect).

## [2026-08-29] Review-only principles not mechanically enforced

Context: SKILL-220 subtask 7 added six architecture guards and recorded what resist deterministic scans.
Decision: Comment quality, naming taste beyond noun-family clustering, deeper noun-family relatedness inside a single area cluster, and open harness/capability vocabulary keys (see capability decision above) are review-only. They are listed in `PrincipleEnforcementInventory.reviewOnlyRules` and `docs/code-principles.md` but have no architecture-test guard.
Reason: Subjective editorial rules and intra-cluster vocabulary produce false positives; open pack extension surfaces were explicitly left open in subtask 3.
Alternatives considered: Detekt or comment-density rules (rejected: SKILL-221 owns complexity and suppression policy). Forcing closed enums for fallback capabilities (rejected: recorded in the capability vocabulary decision).

Context: SKILL-211 through SKILL-214 moved agent-to-agent phases onto `phase_prose`. Verify and implement_fix still decide from structure. An omitted id is silent loss.
Decision: Keep `finding_dispositions` and `repair_receipt` as an id-enum census. Gate coverage and routing on those enums. Ignore former fat fields instead of rejecting them. Drop the compact-symbol regex rather than salvaging near-misses.
Reason: The runtime already owns the finding set. Stuffing `value` would make Kotlin re-parse prose for ids it has. Grammar-cop near-misses were discarding tree-resident work.
Alternatives considered: Adopt `phase_prose` by momentum (rejected: next reader is the runtime). Keep fat fields and salvage compact-symbol near-misses (rejected: SKILL-210 showed salvage does not stop the grammar cop).
Revisit when: a remaining finalization receipt is proven to be grammar rather than measurement.

## [2026-08-27] Validate format repair is project-wide spotlessApply

Context: SKILL-211 blocked on validate after three repair turns with one Spotless
finding in `:runtime-domain`. The repair agent ran `:runtime-application:spotlessApply`,
reported clean, and left the domain violation open. The gate had surfaced the failure
as `unparseable_gate_failure` with a stdout dump naming the correct module.

Decision: validate Task and findings preamble require `./gradlew spotlessApply` once
at the Gradle project root for Spotless/format findings, and forbid `:module:spotlessApply`.
Targeted `test` / `compileKotlin` / `detekt` / `ktlintCheck` stay allowed.

Reason: Project-wide apply is fast and removes module-picking from an opaque gate dump.
Matching the repair Task and findings preamble keeps the authoritative and projected
text aligned (same pattern as the collect-all forbid).

Alternatives considered: Runtime-owned auto-apply inside the validation gate coordinator
(rejected for this hotfix: prompt contract is the cheaper fix and matches existing
repair ownership). Keep module-scoped apply and improve finding parsing (rejected: still
depends on the agent reading the right module from a noisy dump).

## [2026-08-26] Runtime git commit waits for the target repo's hooks

Context: Atlas `commit_push` died at the runtime's 30s git timeout while `.githooks/pre-commit` ran `./gradlew ktfmtFormat` against a cold Gradle daemon. The implementation work was already on disk.

Decision: `commit` and `push` wait 10 minutes. Other git calls stay at 30s. Hooks still run; the runtime does not pass `--no-verify`.

Reason: The target repo declared those hooks. Skipping them would land unformatted code. Raising every git call would hide hung `status`/`rev-parse`. Ten minutes covers a cold daemon plus format without matching the 120-minute validation-gate budget.

Alternatives considered: `--no-verify` on runtime commits (rejected: bypasses the repo's format gate). A single raised timeout for all git (rejected: plumbing hangs would sit for minutes). No timeout on commit (rejected: a stuck hook would wedge finalisation with no bound).

## [2026-08-25] A process failure stores the child's output instead of a bounded excerpt

Context: WE-4860 subtask 4 blocked four times on `agent exited with non-zero status 1`, each time carrying the child's own claim that the host had slept mid-response. The host's `pmset` log showed no sleep at any of those timestamps. Nothing durable existed to check the claim against: a process failure reaches no output gate, so `rejected_output_diagnostics` and `producer_output_evidence` both stayed empty, and the only surviving trace was the excerpt inlined in the block reason, capped at `STDERR_EXCERPT_MAX_CHARS`. `reconcileLaunch` read `AgentRunLaunchFacts`, which holds both streams in full, and dropped them.

Decision: `LaunchResult.InfraFailure` carries the child's stdout and stderr, and the block seam stores them as a rejected-output diagnostic under rule `process-failure`, retrievable by the existing `feature-task rejected-output --raw-output`. The body is framed: both streams labelled with their lengths, plus exit status, timeout, interruption and spawn flags. Absent output stores nothing rather than an empty row. The write is best-effort and precedes the block.

Reason: A failure whose only evidence is a 200-character excerpt cannot be diagnosed, and this one was actively misleading — the child named a cause the host's own log contradicted. Both streams are kept because a child's diagnosis arrives on whichever it happens to use, and attributing the wrong one is how a false cause gets believed. The write is best-effort because the block is what settles the phase: a lost artifact is a worse diagnosis, while a lost block is a wedged run.

Alternatives considered: Widen the inline excerpt (rejected: the block reason is read by operators and prompts, and a full transport dump does not belong in either). A new table for process failures (rejected: the rejected-output store already has payload retention, sha/size verification, lifecycle and a CLI; a second store would need all of it again and a second command to reach it). Store only stderr (rejected: this failure class printed its message on stdout, so stderr alone would have retained nothing).

## [2026-08-25] A misnamed key's prose is adopted, not discarded and then reported missing

Context: WE-4860 subtask 4 emitted `reconciliation_evidence` as `{"reconciled": true, "notes": "<the evidence>"}`. `notes` is not declared on that closed object, so canonicalization discarded it as an unknown key, and strict validation then rejected the receipt for `required property 'evidence' not found`. Correct, in-cap prose was deleted by the repair layer and then reported absent. The producer cannot learn from that rejection either: from its side it did supply the text.

Decision: before the unknown-key discard, a closed object whose prose field is absent adopts the value of a lone unknown key holding non-blank text, recorded as `misnamed_key_adopted`. Wired per call site — currently `reconciliation_evidence.evidence` — rather than as a general rule.

Reason: The two halves of the closed-object contract were defeating each other: the SKILL-152 class-1 repair manufactured its own class-2 failure. Adoption is information-preserving in the way the class-2 prohibition cares about — nothing is synthesized, the producer's own text is moved to the field it was written for.

Alternatives considered: A general "single unknown key fills the single missing required field" rule (rejected: `deviation` requires `ref` and `note`, so a general rule would file a sentence as an identifier — worse than rejecting; a test pins that adoption never reaches `deviations`). Adopt when several unknown keys are present (rejected: which one is the evidence is a guess, and the earlier `{reconciled, method, observations}` shape must keep rejecting). Truncate over-length adopted prose (rejected: SKILL-169 forbids it, and a length violation is the one class whose correction tells the producer to compress).

## [2026-08-25] Goal liveness falls back to the parent lease when the child lease is idle

Context: SKILL-208's goal runner was live with a fresh parent execution lease,
but status reported `execution_liveness: idle` because the child worker lease
was still the expired implement-phase row. The IDE maps idle to paused, so the
plugin showed paused while the validate agent was running.

Decision: child LIVE or UNKNOWN still wins. Child IDLE (missing or expired
worker lease, or a dead local owner) falls through to the parent goal
execution lease before status reports idle.

Reason: The parent lease is the authority that a goal runner still owns the
goal. Child leases are phase-scoped and routinely lag across resume, runtime-
owned gates, and agent turns. Treating an idle child as global idle hides a
live parent.

Alternatives considered: Require every phase to renew the child lease before
status can be live (rejected: races with runtime-owned work and resume). Change
only the IDE mapping so idle stays active (rejected: idle must still mean no
live runner when both leases are gone).

## [2026-08-25] Validate repair Task must not re-invoke collect-all

Context: SKILL-208 validate sat "live" for hours while the repair agent ran
`./gradlew check --continue | tail -15`. The Task line still ordered
bill-code-check collect-all even after the runtime had projected findings, so
the agent rediscovered the suite and buffered all output until EOF — no fixes.

Decision: when `validationGateFindings` is present, the validate Task is
`validateRepairPhaseTask`: fix the listed set; forbid bill-code-check, pack
collect-all, and `check --continue`; allow only targeted pack-checker tasks.
The findings preamble matches that forbid list. Absent-gate agent-run fallback
keeps the collect-all Task.

Reason: Runtime already owns discovery and post-repair verify. A second full
suite in the repair session duplicates wall clock and, with `tail`, hides
findings from the agent. Build repair already forbade collect-all; validate
must match.

Alternatives considered: Soften only the findings preamble (rejected: Task is
authoritative and still invited collect-all). Intercept shell gradle in the
runner (rejected: prompt contract is the cheaper fix and matches existing
build ownership).

## [2026-08-25] An over-bound review input elides deleted bodies rather than blocking

Context: WE-4860 subtask 3 retires a module. Its review input measured 1,716,726 bytes against the 1,000,000-byte bound, of which 1,114,385 — 65% — was the complete body of 170 deleted files. The bound blocked the subtask, so the 14 added, 238 modified, and 29 renamed files went unreviewed as well.

Decision: when the delta exceeds the bound, the review input keeps every added, modified, and renamed patch in full and replaces each deleted file's body with a manifest line naming the path and the lines it lost. Elision is conditional: a delta that already fits is unchanged, byte for byte. A delta still over the bound after elision keeps its full text so the failure reports the real size. Operator-chosen over raising the bound and over chunking the review.

Reason: A deleted file's body is the part of a retirement delta a reviewer can act on least, and the alternative was reviewing none of it. The reduction is consistent with how this review already works: lanes read bodies on demand through `read_evidence` and reach past their assigned hunks through `request_expansion`, so the manifest's paths are a locator rather than a dead end. Measured at 625,771 bytes on the real delta, 37% under the bound.

Alternatives considered: Raise `GOAL_SUBTASK_REVIEW_INPUT_MAX_BYTES` (rejected by the operator: it pushes 1.1MB of deleted source into the review context and moves the wall rather than removing it). Chunk an over-bound delta across passes and merge findings (rejected: most faithful, but it breaks the one-pass-per-delta assumption the findings ledger, coverage gate, and repair receipt are all built on). Elide unconditionally (rejected: an ordinary subtask's review input should not change, and a reviewer should never wonder which shape they are holding). Note the cost accepted here: a block of logic relocated out of a deleted file is easier to miss when only the deletion's path is inline.

## [2026-08-24] An absent summary is filled from the producer's prose, not rejected

Context: WE-4860 subtask 3's implement receipt narrated its work in prose, then emitted a fenced envelope carrying all thirteen closed tasks and no `summary`. The envelope walker requires every declared field before a span can be a candidate, so nothing matched and a 23KB receipt was discarded over the one root field no consumer branches on — `terminalBlockedReasonFrom` reads it as `.orEmpty()`, and the runtime already authors its own for gate-executed phases.

Decision: `PhaseOutputExpectedShape.withRecoveredSummary` fills an absent `summary` from the last paragraph of the producer's prose preceding the envelope, or from a marker naming the phase when there is no such prose. It fires only when `phase_id` matches and every other required field is present. `select` runs a scan without the fill first and only falls back to a scan with it when the text holds no complete envelope at all.

Reason: The prose is the producer's own account of the phase, misplaced rather than missing — the same judgement as the misplaced-key decision below, and the same recovery the review path already performs when it assembles an envelope from prose. The two-pass order is what makes it safe: a phase that emits a summary-less draft and then a corrected envelope must settle on the correction, and filling during the first scan would promote the draft to a competing candidate. `FeatureTaskRuntimePhaseOutputSchemaValidatorTest` proved that regression before it shipped.

Alternatives considered: Fill from `reconciliation_evidence.evidence` (rejected: that field is the tree-state evidence, not a phase summary; repurposing it would misreport what the phase did). Marker only, never prose (rejected: it discards a sentence the producer actually wrote and that is already on the wire). Relax `matches` to drop `summary` from the required set (rejected: the field stays contractually required, and the envelope written back carries a real value rather than a hole later readers must handle).

## [2026-08-24] A bare evidence string is promoted to reconciliation_evidence, not rejected

Context: WE-4860 subtask 2's implement receipt emitted `reconciliation_evidence` as the evidence string itself rather than `{ reconciled, evidence }`. The producer-projection gate rejected it and, at a one-attempt budget, blocked the run — discarding a 22KB receipt whose read-only sweep was already on the wire. `RealValidatorReceiptFixLoopConvergenceTest` pinned that rejection deliberately, as one of three SKILL-152 classes "canonicalization must never paper over".

Decision: `FeatureTaskRuntimeProjectionCanonicalizer` promotes a non-blank string at `reconciliation_evidence` to `{ reconciled: true, evidence: <trimmed> }`, recorded as a new `scalar_promoted_to_object` transform. A blank string is left alone. The SKILL-152 guard's line is restated as *whether the repair loses producer content or invents an assertion*: the missing-`evidence` and over-length-`evidence` classes still reject, and the type-mismatch class moves across.

Reason: `reconciled` is `const: true` on the receipt variant, so the promotion asserts nothing the contract had not already fixed, and the string it promotes is exactly the `evidence` the producer wrote — nothing is lost or invented. The original guard grouped this with two classes that genuinely do lose or fabricate content; the distinguishing test it names separates them once `reconciled` being a const is taken into account. Not blocking, and interpreting output that is not shaped exactly as expected, is the standing preference.

Alternatives considered: Keep the rejection and fix only the prose (rejected: the prose already showed the correct object shape, so it was not a briefing gap, and the gate would still discard a receipt already emitted). Promote a blank string too (rejected: `evidence` is `nonBlank`, so it trades a type error for a value error while manufacturing a `reconciled: true` claim the producer never made). Promote in the structural repair walker beside the root-key demotion (rejected: that layer is phase-shape-only and phase-agnostic; this is projection knowledge, and canonicalization already owns the field and runs immediately before validation).

## [2026-08-24] A finding verification refuted is not carried, so the repair receipt owes it no entry

Context: On wftr-20260824-125937-qn99 review reported three findings and `verify_findings` refuted `F-003`, a nit. The fix phase closed the two survivors and reported exactly that. The repair-receipt coverage gate then rejected the round, because it measures against `reviewState.passResults.last().findings` — the raw review output, which nothing subtracts the refuted findings from. The round blocked with both real findings already fixed on the tree. The phase prose carried the same contradiction: it opened with "Address every *verified* finding from verify_findings" and then declared every carried finding in scope, so the agent's reading was the defensible one.

Decision: `featureTaskRuntimeCarriedFindings` becomes the single definition of the carried set for both the coverage rejection and the omitted-findings retry reason, and drops every finding whose durable ledger row records `verification_disposition = rejected` for that pass. The refuted refs are read from the unaddressed-findings ledger scoped to the pass being repaired, never workflow-wide: each pass renumbers from `F-001`, so an unscoped read would let an earlier pass's refutation waive whichever finding inherited its ordinal. A ledger that cannot be read waives nothing. The parser's coverage path now also stabilizes refs, which it previously skipped — a review that omitted a ref failed coverage on an identity it never had.

Reason: Verification exists to drop findings that do not survive scrutiny. Requiring a `no_edit_required` entry for a claim the runtime itself refuted made the stage decorative and blocked a round on paperwork the runtime had already decided was unnecessary. Not blocking, and interpreting what the runtime already knows, is the standing preference — the same judgement as the misplaced-key decision below.

Alternatives considered: Synthesize the `no_edit_required` entry from the refutation reason (rejected: it writes a repair-ledger row for work no one did, and the ledger's value is that every row is a real decision). Fix only the prose (kept as well, but it cannot settle a receipt already emitted, and the gate would still block a correct one). Read refuted refs from `review_run_finding_verdicts` instead of the ledger (rejected: it needs the review run id re-derived from phase output at a seam that holds only the review state, and the ledger is the merged record the reducer already writes).

## [2026-08-24] A key placed beside produced_outputs is moved into it, not rejected

Context: An implement receipt on wftr-20260824-125937-qn99 carried `reconciled_state` at the envelope root instead of inside `produced_outputs`. The closed root rejected it as an unknown property, discarding 42KB of output describing 227 changed files that were already on disk. The contract calls the reconciliation report an *additional* report, which reads as a sibling of `produced_outputs` rather than a member of it.

Decision: `PhaseOutputExpectedShape.align` gains the mirror of its nested-required-field pass: a root key outside the envelope's declared root fields moves into `produced_outputs`. A member `produced_outputs` already states keeps its value; the stray root copy is dropped either way. `ENVELOPE_ROOT_FIELDS` is pinned to the schema's root properties by a parity test.

Reason: The envelope root is closed and `produced_outputs` is open, so an undeclared root key can only be a misplaced member — the shape already says where it belongs. Correcting placement in the capture we hold beats spending a session regenerating work the producer already did, which is the same judgement as the 2026-08-20 decisions to repair in place rather than relaunch.

Alternatives considered: Give envelope failures their own retry budget (rejected: this is the salvage relaunch the 2026-08-20 decision removed after observing zero recoveries, and a second process cannot see the first session's context). Fix only the prompt wording (kept as well, but it cannot recover a receipt already emitted). Demote by an explicit key allowlist (rejected: the closed root already identifies a stray key, and an allowlist would miss the next misplacement).

## [2026-08-24] Remove provider-reported review accounting
Context: Provider token fields use incompatible conventions and no review decision consumes the resulting aggregates.
Decision: Remove provider-token review models, thresholds, folding, projection, and enforcement while retaining runtime-owned byte and count accounting.
Reason: Repairing convention-specific accounting would add an unvalidated measurement without a caller; local byte-derived estimates remain meaningful and load-bearing.
Alternatives considered: Normalize provider values during decoding (rejected: it would preserve an unusable cross-provider metric and expand the transport contract).

## [2026-08-21] Soft-admit findings for verification; prose still settles

Context: Prose-only review emptied merge findings, so claim verification always no-oped even when the parent named concrete defects.

Decision: Soft-parse optional `[F-XXX]` lines from parent stdout into merge findings for claim verification and adjudication. Never fail the lane on register shape. Keep advance settlement on parent `verdict:`; attach soft findings to the feature-task envelope only after that reduction.

Reason: Verification needs structured F-ids; settlement must stay relaxed and not let Minor-only rows flip `changes_requested` to approved.

Alternatives considered: Restore hard register gates (rejected). Change `outcomeFor` ordering globally (deferred; local assemble order preserves prose-first without wider verdict churn).

## [2026-08-21] Review is single-agent prose; dual-agent lanes disconnected

Context: Parallel lane register parsing blocked runs on format drift while findings were meant for the same review owner to interpret.

Decision: Disconnect dual-agent `agent2` paths. Inline and delegated review use one parent agent; delegated specialists return raw text to that parent with no register verification. Remediation opens only from an explicit parent `verdict`.

Reason: Machine admission of register lines dropped usable findings and blocked on punctuation; prose plus verdict is enough for advance vs `implement_fix`.

Alternatives considered: Soften the parser only (rejected: still two agents and a hard merge gate). Keep dual lanes under one owner (deferred: disconnect first).

## [2026-08-26] SKILL-209 removes dual-agent parallel review

Context: Parallel second-parent lanes, merge commands, and config keys duplicated review orchestration without a distinct product surface after single-agent prose review landed.

Decision: Delete `bill-code-review-parallel`, `code-review-parallel`, `code-review-merge`, and `config resolve-parallel-agent`. Drop `--agent2`, `--model2`, `--parallel-review-agent`, and `code_review_parallel_agent` from new writes and CLI surfaces. Standalone review uses one parent lane with issue key `code-review`. Legacy continuation and review-policy artifacts may still carry `parallel_review_agent` on read; the value is ignored and is not written again. Non-`none` `code_review_parallel_agent` config loud-fails naming the removed capability; `none` is ignored as a legacy key.

Reason: One parent agent plus delegated specialists is the supported review model; dual-lane merge and parallel config added failure modes without separate operator value.

Alternatives considered: Keep config fallback mapping to a single lane (rejected: implies a second parent still exists). Migrate historical accounting rows keyed `code-review-parallel-*` (out of scope).

## [2026-08-20] Output-gate failures block on the first invalid envelope

Context: The one salvage agent launch after a schema-invalid audit did not recover. SKILL-202 burned both attempts on missing `verdict` then prose in `carried_gap_dispositions.evidence.observation`.

Decision: Cap the per-visit output-gate correction budget at one. Programmatic extract-and-shape-repair still runs on the existing capture. If that capture is still invalid, the run blocks. No second agent launch.

Reason: The salvage prompt did not convert contract misses into valid envelopes in practice; it doubled latency and still blocked.

Alternatives considered: Keep the salvage launch (rejected: observed zero recoveries on the SKILL-202 audit path).

## [2026-08-20] Extracted phase JSON is aligned to the expected shape; gate retries cap at two

Context: Audit kept relaunching because an extra `}` closed the envelope before `verdict`, or `verdict` sat under `produced_outputs`. Schema-invalid retries had no cap.

Decision: Walk the capture for JSON, keep the object that matches the phase's expected fields, and repair that object in place (drop extra closers, pull trailing or nested required fields onto the envelope). If programmatic salvage cannot accept it, one last agent launch receives the original capture plus the expected shape; that result is extracted and validated the same way, and a second failure blocks.

Reason: The agent already emitted the envelope. Regenerating it burns the session; one salvage pass is enough to catch a remaining contract miss, then the run must stop.

Alternatives considered: Keep syntax-only delimiter repair and uncapped schema retries (rejected: SKILL-201 spent 35 audit launches on the same extra `}`).

## [2026-08-20] Phase JSON repair keeps the existing envelope; the agent does not regenerate it

Context: A complete valid audit envelope was rejected because surrounding prose had a bare `}`, which exhausted the format-retry budget and relaunched the phase.

Decision: Structural repair is library-owned. Parse with Jackson, compare to the expected envelope shape, and repair the existing capture (drop extra closers, add a missing closer when bounded). Do not ask the agent to generate a new envelope. Reject only when there is no unique complete candidate.

Reason: Format retries repeat the whole phase. An extra bracket around an already-valid object is a syntax fix, not a new authoring turn.

Alternatives considered: Keep rejecting outside closers so agents learn to omit them (rejected: it burned the format cap on wrapping, not on the envelope).

## [2026-08-20] Validate uses only the pack-declared collect-all command

Context: The validate agent ran AGENTS.md extras (`npx agnix --strict`, `skill-bill validate`) after Gradle was already green, then blocked on those results.

Decision: Validate may run only the pack `validation_gate.collect_all_full_gate_command` for collect-all and confirmation. The prompt names that argv and forbids repo-root checklists. `npx agnix --strict` is no longer in AGENTS.md. Mid-repair targeted Gradle proof is forbidden; see 2026-08-29.

Reason: Agnix lints instruction files. It is not the Kotlin pack gate. Mixing the two made a green `./gradlew check` look blocked.

Alternatives considered: Keep agnix on the maintainer list and hope the phase prompt wins (rejected: AGENTS.md is always applied).

## [2026-08-29] Validate repair forbids mid-session proof commands

Context: Repair prompts required spotlessApply at turn start and a targeted detekt/compile/test proof after each checklist item. Agents thrashed on short Gradle loops instead of fixing the full finding set and letting the runtime confirm.

Decision: Validate repair is check → list → fix all → check again. Runtime-owned repair turns edit only; the runtime re-runs the pack gate after the agent stops. Agent-run fallback still runs collect-all once and one confirmation after the full set is fixed. No `spotlessApply`, `detekt`, `ktlintCheck`, `compileKotlin`, `test`, or other proof between findings.

Reason: Per-item proof recreates the collect-all gate as a slow chat loop and burns wall-clock without shrinking the open set.

Alternatives considered: Keep targeted proof as optional guidance (rejected: agents treated it as mandatory and never reached a clean confirm).

## [2026-08-20] Validate session owns collect-all and confirmation

Context: Runtime-owned collect-all parsed findings, deleted the log, and told the agent not to run the gate. "Do not rerun the full gate after every finding" became "this process cannot run check."

Decision: The validate agent runs the pack collect-all gate, reads that output, fixes the set, then runs one confirmation check. The runtime may still verify once after the session. Parsed findings are a hint.

Reason: The working loop is check, read the real output, fix, confirm. A finding list without the log is not that loop.

Alternatives considered: Keep runtime-owned collect-all and only allow targeted module tasks (rejected: the agent still never sees check output).

## [2026-08-20] Checkpoint-ref prune lifecycle supersedes amend-era ref-retention trigger (SKILL-190 subtask 6)

Context: Subtask 4's ref-based remediation reconciliation kept checkpoint refs for the life of a
subtask; subtask 6 adds a gated prune after push plus recorded `commit_sha`, reset-driven pruning,
and idempotent resume.

Decision: Prune `refs/skill-bill/checkpoints/<issue-key>/<subtask-id>/*` only after the subtask
commit is pushed and the decomposition manifest entry carries a non-blank `commit_sha`; hard reset
prunes without that gate; blocked or abandoned subtasks retain refs.

Reason: Refs remain the recovery surface until the deliverable commit is durable on the branch and
in the manifest; afterward they would only grow the namespace without adding reachability.

Revisit when: prune eligibility or the checkpoint namespace layout changes.

## [2026-08-19] Ref-based remediation reconciliation supersedes compensating soft-reset (SKILL-190 subtask 4)

Context: Subtask 3 introduced runtime-owned amend semantics; the SKILL-176 compensating soft-reset and
HEAD-rewrite reconciliation path contradicted amend-owned history and reintroduced SKILL-189 empty-review
diffs when checkpoint commits were orphaned.

Decision: `reconcileRemediationBaseCoherence` resolves the latest `review_fix` base through checkpoint
refs, not branch ancestry; unresolvable bases return a typed blocked outcome with `skill-bill goal repair`
guidance instead of rewriting to HEAD. `rollbackRemediationCheckpointCommit` restores the prior checkpoint
ref (or removes the first subtask commit) and is idempotent when HEAD already moved.

Reason: Refs preserve pre-amend commits the branch no longer names; soft-reset to `parentSha` fails once
amend dissolves the intermediate commit object the old rollback targeted.

Alternatives considered: Keeping HEAD rewrite for recorded-but-superseded — rejected; that was the
SKILL-189 failure door. Retaining parent-only soft-reset — rejected; amend orphans the parent link the
rollback relied on.

Superseded by: checkpoint-ref prune lifecycle (SKILL-190 subtask 6, 2026-08-20).

## [2026-08-17] Checkpoint-identity 0.2 keeps its parity test; quarantine enum widens without a bump (SKILL-190 subtask 2)

Context: Bumping the checkpoint-identity contract to 0.2 hit two governance collisions the parent
spec flagged. First, `CLAUDE.md` forbids new tests while the runtime-contract recipe requires a
parity test for every version bump. Second, recording the quarantine of a legacy checkpoint-identity
store needs a `rejection_class` the quarantine schema's enum does not carry.

Decision (AC-008 parity): The collision is abstract here — `FeatureTaskRuntimeCheckpointIdentitySchemaContractVersionTest`
already exists and is version-agnostic, asserting the YAML const against the Kotlin constant. Follow
the contract rule by retaining that test unchanged and adding no new test file. The subtask therefore
proceeds on a recorded decision, not an unrecorded default.
Alternative rejected: waiving the parity check on the no-new-tests rule — it would leave a
YAML-versus-Kotlin divergence with no gate, which is exactly the drift the recipe exists to catch.

Decision (quarantine enum): Add `checkpoint_identity_contract_version` to the quarantine schema's
`rejection_class` enum WITHOUT bumping the quarantine contract from 0.3. Enum widening is
read-compatible in the only direction that occurs: a store is written and read by the same installed
runtime, and every 0.3 record stays valid under the widened enum.
Alternative rejected: bumping the quarantine contract to 0.4 per the usual per-change precedent. The
quarantine store has no quarantine-of-quarantine recovery path, so a bump would loud-fail every
in-flight workflow that already holds evidence — trading the wedge this subtask removes for a worse
one.
Revisit when: the quarantine record gains or changes a field (not just an enum member), or a
downgrade path where an older runtime reads a newer store becomes real.

Known residual gap handed to subtask 4: `FeatureTaskRuntimeGoalContinuationRecorder.reconcileRemediationBaseCoherence`
decodes the checkpoint-identity store at run startup and rethrows anything that is not an
`InvalidGoalSubtaskReviewStateSchemaError`. That seam is correctly loud (AC-006) but runs BEFORE
`appendCheckpointIdentity`'s quarantine repair, so a goal-continuation run holding a 0.1 store can
still fail there first. Subtask 4 owns that consumer; this subtask audited it and left it unedited.

## [2026-08-15] FULL validate proves repairs by confirmation identity closure
Context: Last-subtask FULL collect-all discovery still treated a completed repair agent payload as proof, so omitted identities and leftover confirmation findings could look green.
Decision: Persist a covering repair plan and require a substantiation receipt per discovery identity before confirmation; green confirmation is identity closure on the confirmation finding set, not measured PASSED alone.
Reason: Suite proof stays one collect-all confirmation run; per-finding Gradle or filtered `--tests` launches recreate the SKILL-176 39-run failure. BUILD_ONLY stays compile/build-only without receipts or identity closure.
Alternatives considered: Agent-run full_gate_command or per-test substantiation — rejected. Bumping persistence contract versions for additive plan/receipt keys — rejected; absent keys decode empty.
Revisit when: confirmation closure needs a different identity key than exact module|ruleOrTestId|message|location.

## [2026-08-10] Review remediation gate is Blocker or Major (SKILL-178)

Context: Governed skill content and content-lock tests still stated the old
Blocker-only reopen rule after subtasks 1–3 widened runtime severity gates so
Blocker and Major both reopen `implement_fix` and hard-block advance.

Decision: Governed content, playbook/code-review remediation-delta prose, and
parity locks describe the Blocker-or-Major rule; Minor and Nit stay ledger-only
with retrieval only via `skill-bill goal findings --issue-key <KEY>`. Durable
wire key `blocker_dispositions` stays named as-is.

Reason: Content must agree with the runtime predicates already shipped; renaming
durable disposition keys is a separate migration.

Alternatives considered: Leaving Blocker-only prose until a later docs pass —
rejected; locks would keep encoding the wrong rule. Renaming `blocker_dispositions`
in this sweep — rejected; out of scope and breaks durable decode.

Revisit when: disposition obligations widen from prior-Blocker ids to every
addressed finding in the review-execution directive itself.

## [2026-08-10] Validate-phase build/test/gate execution is runtime-owned (SKILL-180)

Context: Validate previously told the agent to invoke `bill-code-check`, so
gate-run count, batching, and terminal cache-bypass evidence were claims rather
than measurements. Intermediate cache-served greens could also satisfy a
terminal outcome without executing work.

Decision: When the dominant platform pack declares `validation_gate`, the
runtime owns gate execution (pack-declared argv, including the cache-bypassing
terminal variant), measures each run, projects bounded findings to the validate
agent, and persists `gate_run_count` / `gate_runs`. The agent repairs findings
and must not invoke the gate or any quality-check skill. Absence of a
declaration falls back to agent-run validate with a surfaced degradation.
Audit and repair evidence remain read-only repository facts.

Alternatives considered: Agent-reported gate_run_count (rejected). Hardcoded
Gradle cache flags in the runtime (rejected; packs declare bypass argv).

## [2026-08-10] producer_output_evidence identity includes agent_id (SKILL-176)

Context: Re-entering a phase attempt under a different agent (SKILL-15
`review:0:2`) crashed retention: the four-part key already held another
producer's immutable bytes, so read-back Conflict aborted phase recording.

Decision: Widen identity to `(workflow_id, phase_id, generation, attempt,
agent_id)`. Same-agent divergent bytes still Conflict; cross-agent rows
coexist. Do not supersede or overwrite — AC-002 forbids in-place mutation,
and AC-003 requires both producers remain reconstructable.

Reason: `agent_id` is already durable on every row, so including it needs no
backfill; widening the key is the minimum change that keeps immutability and
lets a second producer land without terminating the run.

Alternatives considered: Last-writer-wins or supersede — rejected; violates
AC-002 and loses reconstructability. Advance attempt on agent switch —
rejected; hides the identity bug and is out of scope.

Revisit when: evidence must be shared across producers for one attempt without
agent scoping.

## [2026-08-10] remediation checkpoint sha and branch tip stay paired (SKILL-176)

Context: On SKILL-15, remediation checkpoint `73993c8` was recorded as
`remediation_base_sha`, then the branch tip moved to sibling `9d814e8` (same
parent `173fb03`) without a second checkpoint-identity record. The durable base
became unreachable. Candidate runtime producers of that sibling topology were
eliminated: index restore on a failed checkpoint never moves HEAD; a crash
between commit and `updateReviewState` leaves committed-but-unrecorded rather
than a recorded orphan sibling; resume Skip+re-record converges on HEAD; no
runtime reset/rollback API existed. The stranding requires a post-record history
rewrite that moves the tip off the recorded sha without a coupled base write.

Decision: (1) A remediation Stage commit and its `remediation_base_sha` write are
one unit — the commit sha is passed into `updateReviewState`, and a failed base
record soft-resets HEAD to the pre-commit parent so the ref and the durable row
both remain at the pre-commit state. (2) On goal-child resume, reconcile
committed-but-unrecorded and recorded-but-superseded bases to the branch tip (or
latest review_fix checkpoint still on the branch) before review preparation
consumes the base, emitting durable `goal_review_base_recoveries` evidence.
Subtask 2 recovery remains the degradation path for pre-existing orphans.

**Superseded 2026-08-19 (SKILL-190 subtask 4):** resume reconciliation and compensating rollback now use
checkpoint refs under amend semantics; the HEAD rewrite and parent-only soft-reset described above no
longer apply. See the 2026-08-19 entry in this file.

Reason: Git and SQLite cannot share one ACID transaction; compensating soft-reset
plus resume heal close both the crash window and the post-record rewrite window
without a second reconciliation pass on the healthy path.

Alternatives considered: Always reset on identity-write failure for every
checkpoint intent — rejected, only remediation bases are scope-critical at this
seam; migrate/backfill historical rows — rejected, heal at read/resume only.

Revisit when: a runtime-owned history rewrite (amend/rebase) is introduced, at
which point that path must call the same paired base update.

## [2026-08-09] runtime is the only feature engine; prose and OpenCode/zcode are removed from the product (SKILL-175)

Context: Runtime owns guarantees prose cannot deliver (DB-owned phase loop,
preplan hydration, projection budgets, worker leases, agent-independent resume).
Keeping prose meant a second, weaker product: dual skills, MCP tools, CLI,
telemetry, IDE enums, parity locks. OpenCode/zcode were runtime-refused with a
refusal pointing at prose. Supersedes the 2026-06-27 "opencode is prose-only"
entry in full.

Decision:
1. Runtime is the sole feature engine; no `mode:prose`/`mode:runtime` selector.
2. The prose surface is deleted, not renamed: prose runners,
   `feature_task_prose_*` / legacy `feature_implement_*` / `goal_prose_*` MCP
   tools, `skill-bill workflow` (`TASK_PROSE`), `implement-stats`,
   `FeatureImplement*`, `WorkflowFamily.IMPLEMENT`, IDE `feature-task-prose`.
3. OpenCode and zcode leave the product entirely, explicitly with no refuse tier
   or "unsupported agents" list. `RUNTIME_REFUSED_AGENTS`, its message,
   `isRuntimeRefusedAgent`, and `InstallAgent.OPENCODE`/`ZCODE` are deleted.
4. A future OpenCode return is a new integration from a working headless
   driver, never an un-delete, shim, or prose fallback.
5. Cutover is dependency-ordered because prose shares `feature_task_workflows`
   with runtime via the `mode` column. No parity tests survive.

In-flight prose rows (binding on subtask 6): quarantine + loud-fail resume,
never reinterpretation or a rewriting migration.
- `feature_task_workflows` `mode = 'prose'` rows stay readable; resume paths
  raise a typed error naming `skill-bill goal <KEY>`. Both `'prose'` CHECKs stay
  (`DatabaseSchema.kt`, avoiding a table rebuild; `DatabaseMigrations.kt` for
  `feature_task_execution_identities`). Writes refuse `'prose'`.
- `feature_implement_sessions`: read-only, no writer, stats removed,
  `StaleSessionReconciler` ignores it. `goal_run_sessions` keep recorded `mode`;
  prose sessions are not resumable.
- Decode paths stay as legacy read-only values, overriding SKILL-175 spec rows
  that said "Remove": `FeatureTaskWorkflowMode.PROSE`, `decodeIdentityMode`, the
  identity-schema `prose` enum, `WorkItemKind.FEATURE_TASK_PROSE` (states from a
  frozen literal set once `FeatureImplement*` is gone). Deleting them turns the
  refusal into an opaque schema error and makes
  `SQLiteWorkListRepository.list()` throw on one legacy row.
- Quarantine lives in read/resume code; appending to an applied migration is a
  no-op on existing DBs.
- Trap: the prose issue-key backfill in `recoverGoalContinuationWorkflowIssueKeys`
  is a read-side repair; keep it. The refusal goes in
  `FeatureTaskContinuationLookupService.lookup`, not in `WorkflowStateStore`
  candidate or delete/count queries.

Scope: only the product mode `prose` goes; English "prose" wording stays. The
`opencode`/`zcode` grep allowlist is the SKILL-175 spec folder and
`.feature-specs/done/**`. Subtask 3 precondition: re-home governed briefing text
into `FeatureTaskRuntimePhaseBriefingAssembler` before deleting the prose
agents holding its only copy.

Reason: two engines on one `mode` column double maintenance while only one
honours the durability guarantees. A refuse tier advertises nonexistent support.
Quarantine keeps history truthful and fails loud instead of half-running.

Alternatives considered: install-only OpenCode/zcode with a redirect (fake
tier); migrating prose rows (state not equivalent, cannot resume); renaming
prose to a runtime variant (dead code under a new name).

Non-goals: an OpenCode/zcode runtime; rewriting done specs or history; review
`mode:inline|delegated|auto`; cleaning user symlinks under `~/.config/opencode`
or `~/.zcode`.

Revisit when: a working headless OpenCode/zcode driver can sustain the runtime
child model, or prose-quarantine loud-fails keep firing long after the cut.

## [2026-08-08] link and compile toolchain pin is JDK 21 (SKILL-166)

Context: The Kotlin runtime compiled and linked against JDK 17 while the
intellij-plugin and host JDKs had already moved to 21.

Decision: Raise `JDK_VERSION`, `LINK_JDK_VERSION`, build-logic source/target,
and the four CI Temurin pins to 21 in one change; keep Badass Runtime and the
explicit additive `IMAGE_MODULES` strategy unchanged.

Reason: One pinned toolchain across compile, jlink, CI, and release docs
avoids mixed-JDK drift; membership of `IMAGE_MODULES` needed no add/remove on
the JDK 21 module graph.

## [2026-08-07] reject audit/review single-pass merge (SKILL-164)

Context: Checkpoint-keyed shared review evidence made it tempting to collapse
`audit` and `review` into one agent pass over the same derived artifact.

Decision: Keep audit and review as separate phases. The single-pass merge is
rejected and not to be re-litigated.

Reason: The phases read divergent evidence sets (audit still needs unchanged
files that produce no diff), they settle into divergent backward edges
(`audit_gap` → `implement` versus `review_fix` → `implement_fix` under
`MUST_MATCH`), audit-first ordering is itself the cost optimization that keeps
specialist fan-out off trees about to be rewritten, and one agent holding both
evidence bars degrades the audit.

Alternatives considered: Merge audit and review into a single pass over the
shared evidence — rejected for the four reasons above.

Revisit when: none; settled for this feature.

## [2026-08-11] Non-terminal-only plan cascade with provenance restamp (SKILL-181)

Context: SKILL-160 cascaded every sibling plan under `--include-shared-preplan`
because `recoveryProgress` re-validates all ordered plans against the governing
shared provenance with no status filter. Leaving a complete sibling mismatched
wedged resume. WE-4719 showed that wiping a complete+commit plan row destroys
useful planning provenance for no benefit.

Decision: Reverse the SKILL-160 cascade breadth. Cascade only plan rows whose
manifest subtask is **not** (`status == complete` AND non-blank `commit_sha`),
on both `--include-shared-preplan` and in-run heading-set refresh. When survivors
remain, soft-invalidate the shared preplan (keep the parent row so FK ON DELETE
CASCADE cannot wipe them) instead of deleting it. In the same transaction that
writes a replacement shared preplan (refresh replace, or relaunch regeneration
after invalidate), restamp retained plan rows' provenance to the new shared
provenance without changing plan payloads or runtime manifest fields.

Evidence that decided it:
- Complete-with-commit plans are still read via `findStoredSubtaskPlan` for hash
  recovery, but they are never re-hydrated into a fresh child; discarding them
  only loses history.
- Soft-invalidate avoids mid-transaction `PRAGMA foreign_keys` toggles (illegal
  inside an open SQLite transaction) while preserving survivors across discard.
- Restamp-at-write keeps `recoveryProgress` provenance equality strict for every
  remaining prepared plan; non-terminal leftovers with mismatched provenance
  still loud-fail.

Alternatives considered: (1) Permanently ignore terminal plan provenance in
recovery — rejected; weakens hydration checks for non-terminals if the filter
drifts. (2) Orphan plan rows by deleting the shared parent with FK off — rejected;
cannot toggle `foreign_keys` inside the replan transaction. (3) Keep
cascade-everything — rejected by WE-4719 cost.

Revisit when: none; settles the SKILL-160 revisit clause.

## [2026-08-05] `--include-shared-preplan` cascades every sibling plan row (SKILL-160)

Context: Discarding the goal-wide shared preplan while leaving sibling
`goal_subtask_plans` rows would provenance-mismatch those survivors against the
regenerated preplan. The subtask asked for either cascade of non-terminal
plans or an explicit reject naming blockers.

Decision: Cascade **every** stored sibling plan row for the goal (terminal and
non-terminal), while leaving runtime fields (`status`, `commit_sha`,
`workflow_id`, out-of-band acceptances) untouched. Non-terminal-only cascade
was rejected.

Evidence that decided it:
- `GoalPlanningPreparationCheckpoint.recoveryProgress` re-validates all ordered
  plans against `expectedProvenance` with no status filter; a leftover complete
  plan whose provenance no longer matches throws
  `IncompatibleGoalPlanningPreparationRecoveryError` and wedges resume.
- `GoalPlanningSweep.descriptor` still reads complete plans via
  `findStoredSubtaskPlan` (hash recovery for completed sub-specs), so terminal
  plans are not inert bytes after completion.
- `GoalPlanningPreparationStore.replaceSharedPreplan` already
  `DELETE FROM goal_subtask_plans` for the same reason — leaving survivors
  strands rows whose provenance can never match.
- A non-terminal-only cascade would leave complete siblings mismatched and
  wedge; a reject-when-complete-siblings-exist path would break the ST2 e2e
  (subtask 3 with 1–2 complete).

Alternatives considered: (1) Reject when any surviving plan would mismatch —
rejected because the operator path for goal-wide amendments is exactly the
mid-goal case with complete siblings. (2) Leave mismatch for runtime discovery —
forbidden by the subtask. (3) Non-terminal-only cascade — rejected by the
evidence above.

Revisit when: recovery or hydration gains a status filter that permanently
ignores terminal plan provenance, with tests proving complete plans are never
read after completion.

**Superseded by 2026-08-11 SKILL-181 decision** (non-terminal-only cascade +
restamp). Kept for history.

## [2026-07-04] internal skills are file-read sidecars; repo paths did not move (SKILL-102)

Context: The feature-execution dispatch targets needed to stop appearing in every
agent's skill list because they are selected by `bill-feature`, not user entry
points. The install pipeline derived listing from the same `content.md`
discovery that drives staging, so hiding a skill required a new internal-skill
classification.

Decision: An internal skill is declared by one optional frontmatter key
(`internal-for: <parent>`). Install renders its governed content as a
`<skill-name>.md` sidecar inside the parent's staged directory (no `skills_dir`
entry, no `SKILL.md` of its own), and the parent invokes it by reading that
sibling sidecar file and executing it in-session — never via the Skill tool.

Reason: The Skill tool on every supported agent resolves only listed skills;
there is no invocable-but-hidden state, so the invocation contract for internal
skills is necessarily a file read. The file-read pattern was already established
for other sibling sidecars (`shell-ceremony.md`, `compose-guidelines.md`) and is
more portable across agents than Skill-tool mechanics. Repo source directories
did not move or rename (PD3) because `WorkflowEngine.CONTINUATION_CONTENT_PATHS`
and `RepoValidationRuntime` content-marker checks bind to the existing repository
paths; moving them would have changed runtime path bindings, workflow identity,
the DB `workflow_name` CHECK constraint, and telemetry constants (PD4) for no
listing benefit.

Alternatives considered: (1) A separate `config.yaml` visibility switch per
skill — rejected as a per-skill preference system, the opposite of a repo-level
authored classification. (2) Moving internal skills' source directories under
the parent — rejected because it breaks runtime path bindings and identity
strings (PD3/PD4). (3) Trimming the
sidecar to a token-light format — rejected by PD6 (behavior parity over token
savings).

Revisit when: A supported agent gains a first-class invocable-but-hidden skill
state, or when internal skills need to be surfaced in a maintainer-only listing
view (the parent spec's deferred open question).

## [2026-06-27] opencode is prose-only: runtime mode refuses whenever the resolved agent is opencode

Context: A real run (NEWS-141, workflow `wftr-20260626-193556-a4lk`) proved the
runtime phase loop non-viable under opencode for two independent reasons: (A)
the Kotlin driver runs the whole loop in one foreground process, and opencode's
Bash tool hard-kills foreground commands at 120 000 ms, while one phase
(preplan) took ~241 s; (B) the nested `opencode run` emits valid contract JSON
the runtime cannot harvest (the builder used `usePtyStdio=true` and opencode's
TUI output buries the JSON in ANSI), so the phase stays `running` and the loop
wedges. opencode is the highest-churn, lowest-usage target; fixing either bug
is not worth it.

Decision: opencode is prose-only. Runtime mode refuses loudly at the boundary
whenever the resolved agent is opencode by any route (host detection,
`SKILL_BILL_AGENT`, `--agent`, `--phase-agent`, `--agent-override` on the
feature-task and goal CLIs), before opening a workflow, resolving a branch, or
spawning a phase. Single source of truth: `skillbill.install.model.RUNTIME_REFUSED_AGENTS`
(`{OPENCODE}`), with `isRuntimeRefusedAgent` and `OPENCODE_RUNTIME_REFUSAL_MESSAGE`
derived from it, so re-enabling is a one-line change. Two enforcement layers:
(L1) feature-task, goal, and `code-review` preflights funnel through
`skillbill.cli.core.refuseRuntimeRefusedAgents`, throwing a `UsageError` naming
the prose alternative; (L2) `OpencodeAgentRunCommandBuilder` is removed and
`headlessAgentRunAdapters` filters the set, so `FileSystemAgentRunLauncher`
yields `UnsupportedAgentRunLaunch` (like copilot) with the same message as an
unbypassable backstop. The message is a `runtime-domain` const (inert data,
allowed by the 2026-05-24 boundary decision), so every refusal is byte-identical.

Reason: prose runs the same governed loop in-session without the 120s-kill or
PTY-harvest problems, and a loud actionable refusal beats silent wedging.
Detection (`InvokingAgentContextResolver`) and the `InstallAgent` enum stay so
opencode is still detectable (to refuse) and installable; only the runtime
launch path is disabled. No app-layer guard in `AgentRunService`,
`FeatureTaskRuntimeRunner`, or `GoalRunner`, keeping the launcher the single
spawner chokepoint. Standalone `code-review` preflights the resolved parent
agent instead of degrading to a silent one-lane review.

Non-goals: no change to opencode install/scaffold/MCP, prose orchestration,
telemetry, or runtime support for claude, codex, junie; no migration or flag.

Revisit when: opencode gains a non-TTY harvestable headless mode and a
foreground budget over 120s.

Superseded by: 2026-08-09 "runtime is the only feature engine; prose and
OpenCode/zcode are removed from the product (SKILL-175)".

## [2026-06-26] SQLite runs in WAL with a busy_timeout for concurrent runs

Context: All runtimes share one global review metrics SQLite file in the user skill-bill state directory,
and nothing prevents concurrent runs (e.g. two goals for two projects at once).
`ensureDatabase` previously set only `PRAGMA foreign_keys = ON`, leaving SQLite on
its rollback-journal default with no busy timeout, so a write that collided with a
concurrent writer failed immediately with `SQLITE_BUSY` ("database is locked") and
aborted the operation — there is no DB-level retry/backoff anywhere in the adapter.

Decision: `ensureDatabase` now sets `PRAGMA busy_timeout = 5000` and
`PRAGMA journal_mode = WAL` (in that order) on every connection, alongside the
existing `foreign_keys` pragma. busy_timeout makes a blocked writer wait-and-retry
inside SQLite instead of erroring; WAL lets readers run concurrently with the single
writer. busy_timeout is set before the journal_mode switch so the WAL transition
itself tolerates a concurrent writer.

Reason: This is the standard low-risk hardening for a shared local SQLite file and
closes the only practical sharp edge of concurrent runs without introducing an
application-level lock or per-project DB files (both rejected: a lock would remove
the ability to run concurrent goals, per-project files would fork the cross-project
review-metrics aggregation the single DB enables). WAL is a persistent property of
the DB file and creates `-wal`/`-shm` sidecars next to the DB — acceptable for a
local user-state database. Re-applying the pragmas per connection is
idempotent.

Revisit when: the DB is moved off a local filesystem (WAL needs shared-memory
support), or measured contention shows 5s is the wrong timeout.

## [2026-09-19] Idle execution liveness is not operator pause (SKILL-362)

Context: IDE status treated expired parent execution leases and idle
`execution_liveness` as `lifecycle_state: paused` even when `GoalRunnerControlState.paused`
was false and the wire carried no `paused_at`. A live parent or child JVM could also
disappear from status when lease heartbeats failed under SQLite write contention.

Decision: `IdeStatusProjector` projects operator pause only from consumed pause control
state; idle liveness maps to `lifecycle_state: idle` (or active when process inspect
reports a live owner). `GoalRunnerStatusProjectionAssembler` consults process inspect
for parent and child worker ownership even when the stored lease is expired. Activity
stamp and worktree journal writers retry `selfManagedWrite` up to
`ReviewMetricsDatabasePolicy.SELF_MANAGED_WRITE_BUSY_ATTEMPTS` on `SQLITE_BUSY` before
emitting the existing bounded failure diagnostics.

Reason: Operator pause, heartbeat loss, and between-phase idle are distinct signals.
Inspect keeps a running JVM visible when durable lease rows lag; bounded in-process
retries complement the existing per-connection `busy_timeout` without forking the shared
review-metrics database.

Relative to 2026-06-26: `PRAGMA busy_timeout` remains
`ReviewMetricsDatabasePolicy.BUSY_TIMEOUT_MILLIS` (5000 ms). Application-level stamp and
journal persistence adds three total `selfManagedWrite` attempts on `SQLITE_BUSY`; the
SQLite pragma value itself is unchanged.

## [2026-06-12] Retain split `skillbill.contracts.*` package for validator moves

Context: SKILL-52.4 F16 leaves contract DTOs/constants/helpers in
`runtime-contracts` while concrete schema/coherence validators compile from
`runtime-infra/fs` under the existing `skillbill.contracts.*` packages.
Decision: Keep the split package and guard against adding new concrete
`*SchemaValidator` / `*CoherenceValidator` declarations to `runtime-contracts`
main source.
Reason: The package name preserves classpath resource paths and import
compatibility while keeping validator dependencies behind the infra/domain-port
ownership pattern.
Revisit when: Resource paths/import compatibility can be migrated cleanly, or
JPMS/module packaging becomes an active target.

## [2026-06-12] Keep `runtime-infra/fs` as one adapter module

Context: SKILL-52.4 F17 considered splitting `runtime-infra/fs` into smaller
Gradle modules after validator and filesystem/process ownership moved behind
ports.
Decision: Do not split `runtime-infra/fs` now; keep the filesystem, process,
schema-validation, rendering, git, and staging adapters in the current adapter
module.
Reason: The current module keeps cohesive adapter ownership without adding
premature Gradle/module overhead or new cross-module seams.
Revisit when: Infra-fs package ownership, file count, or build/runtime ownership
pressure makes module-level separation cheaper than the current single adapter
module.

## [2026-05-29] Ship desktop installers UNSIGNED for v1

**Context.** SKILL-55 subtask 2 produces native desktop installers (`.dmg`,
`.msi`, `.deb`, `.rpm`) via Compose's jpackage integration, each bundling its own
JRE. macOS Gatekeeper and Windows SmartScreen both warn on, or block, software
that is not signed with an Apple Developer ID (notarized) certificate or a
Windows Authenticode code-signing certificate respectively. We do not hold either
certificate for v1.

**Decision.** **SHIP UNSIGNED FOR V1.** We ship the installers unsigned and defer
code signing + Apple notarization to a later release. End users open the app
through the OS "open anyway" escape hatch; the exact steps below are recorded
verbatim so subtask 4 (post-install hint) and subtask 6 (launch FAQ) reuse the
same wording without re-deriving it.

**End-user open-anyway steps (verbatim, reuse these).**

- **macOS (Gatekeeper).** Right-click (or Control-click) the app in Finder ->
  **Open** -> **Open** in the confirmation dialog. Alternatively: **System
  Settings -> Privacy & Security -> Open Anyway**.
- **Windows (SmartScreen).** On the "Windows protected your PC" dialog, click
  **More info** -> **Run anyway**.

**Reason.** Acquiring and provisioning an Apple Developer ID certificate (+
notarization pipeline) and a Windows Authenticode certificate is cost and
process overhead not justified for a v1 launch. Unsigned distribution with
documented open-anyway steps unblocks shipping now; signing/notarization is a
tracked follow-up. The `.deb` / `.rpm` Linux packages have no equivalent
OS-level signing gate for local installs, so this trade-off is macOS/Windows
specific.

**Consumers.** Subtask 4 surfaces a post-install hint pointing at these steps;
subtask 6 embeds them in the launch FAQ. Keep the wording above as the single
source of truth.

## [2026-05-29] Artifact FILENAME, not embedded version, is the source of truth (macOS diverges)

**Context.** SKILL-55 subtask 2 derives the embedded jpackage `--app-version` from
`project.version` (`0.1.0-SNAPSHOT`). jpackage requires a strict numeric
`MAJOR.MINOR.PATCH`, and macOS jpackage + the Compose Dmg validator additionally
require `MAJOR >= 1`. So `toMacAppVersion` bumps a zero major (`0.1.0` -> `1.1.0`)
for the macOS `.dmg` embedded version ONLY; Linux `.deb`/`.rpm` and Windows `.msi`
keep the honest `toJpackageVersion` (`0.1.0`). The embedded version therefore
deliberately DIVERGES across operating systems for the same build. Separately, the
canonical artifact FILENAME (`SkillBill-<project.version>-<os>-<arch>.<ext>`) uses
the full, un-stripped `project.version` uniformly across all operating systems.

**Decision.** The artifact **FILENAME** is the single source of truth for an
installer's version and for artifact resolution in subtask 3/4. Verifiers and
release tooling MUST resolve on the filename, never on the embedded installer
metadata — the embedded `--app-version` deliberately diverges on macOS
(`1.1.0` vs the filename's `0.1.0-SNAPSHOT`) and must not be treated as
authoritative.

**Reason.** macOS's `MAJOR >= 1` constraint forces a per-OS embedded-version
bump that the honest project version cannot satisfy, so embedded metadata is not
a stable cross-OS key. The full `project.version` in the filename is identical
across operating systems and carries the un-stripped qualifier (`-SNAPSHOT`),
making it the only consistent, honest resolution key.

**Consumers.** Subtask 3/4 artifact resolution/verification keys on the filename
token (`SkillBill-<project.version>-<os>-<arch>.<ext>`); do NOT parse the embedded
installer version.

## [2026-05-29] Non-modular jlink images via Badass Runtime, not Badass JLink

**Context.** SKILL-55 subtask 1 needs self-contained, per-OS runtime images of
`runtime-cli` / `runtime-mcp` that run with no system JDK. The runtime modules are
plain non-modular Kotlin apps (no `module-info.java`), pulling in automatic
modules (kotlin-inject, kotlinx.serialization, jackson, networknt, sqlite-jdbc).

**Decision.** Use the Badass **Runtime** plugin (`org.beryx.runtime` 2.0.1), not
Badass **JLink** (`org.beryx.jlink`). Badass JLink requires a modular app and a
`module-info.java` — it has no non-modular path and loud-fails with "Cannot find
module-info.java". Badass Runtime is the Beryx plugin built for non-modular apps:
it links a trimmed JDK runtime with `jlink` and wraps the existing `application`
distribution, keeping the `bin/runtime-cli` / `bin/runtime-mcp` launchers. We pin
the link toolchain to Java 21 (matching `build-logic` `Jvm.kt` `JDK_VERSION`), set
an explicit `additive` module set (java.base/logging/management/naming/net.http/
sql/xml/desktop, jdk.crypto.ec, jdk.unsupported) instead of relying on jdeps (which
cannot resolve the automatic modules cleanly), and trim with `--strip-debug
--no-header-files --no-man-pages --compress 2`. `java.net.http` is required by the
telemetry HTTP client (`runtime-infra/http`), which the version/stdio smoke test
does not exercise. Image name/zip derive from `project.version` + a canonical
`<os>-<arch>` host token defined once, as a typed contract, in the
`skillbill.runtime-image` convention plugin
(`build-logic/convention/.../buildlogic/RuntimeTargets.kt`). The Badass Runtime tasks are not
configuration-cache compatible, so they opt out per-task via
`notCompatibleWithConfigurationCache`; the global config cache stays warm for
`check` / `installDist`.

**Reason.** GraalVM `native-image` was rejected: the reflection/serialization
surface of kotlin-inject + kotlinx.serialization + jackson + sqlite-jdbc would
require extensive reachability metadata and per-OS native toolchains for little
payoff over a trimmed jlink image. A hand-rolled `jlink`+`jpackage` script was
rejected to avoid re-implementing module resolution, launcher generation, and
per-OS zipping that Badass Runtime already provides. Badass JLink (the plan's
first choice) was rejected because it fundamentally cannot link a non-modular app.

## [2026-05-24] Runtime paths stay inert outside adapters and composition

**Context.** SKILL-52.1 tightened hexagonal boundaries while several public
application/domain/port models still need to carry `java.nio.file.Path` values
for caller-provided homes, repo roots, and generated plan locations.

**Decision.** Keep `Path` legal as inert data in application/domain/port public
models, but ban filesystem IO, home expansion, process environment reads, and
system-property reads outside adapters or composition.

**Reason.** Replacing every path with strings would make typed runtime contracts
weaker, while allowing `Path` operations that touch the host would leak adapter
responsibilities back into domain and port code.

## [2026-05-24] Preserve dual install-plan validation after policy extraction

**Context.** SKILL-52.1 moved install planning toward typed policy and
capability ports, but install-plan wire maps still cross two independent
emission seams: builder output and CLI JSON emission.

**Decision.** Keep the shared install-plan wire-snapshot validator at both the
builder seam and CLI emission seam after the refactor.

**Reason.** The builder proves the pure plan shape, while CLI emission can still
assemble or project a payload after planning; validating both seams preserves
the existing loud-fail contract instead of relying on one earlier check.

## [2026-05-24] Runtime-core retains only generated DI public ABI edges

**Context.** The runtime-core shrink makes the module a composition root rather
than an implementation umbrella, but Kotlin-Inject generated components expose
some application service and port types in the public `RuntimeComponent` ABI.

**Decision.** Retain only the generated Kotlin-Inject public ABI edges required
by `RuntimeComponent`: direct API edges to runtime-application services and
runtime-ports context/port types, with the documented transitive domain and
contracts closure, and no infrastructure or entrypoint API edges.

**Reason.** Hiding the generated DI ABI would fight the toolchain and break
callers, but documenting and testing the narrow edge prevents runtime-core from
growing back into a compatibility umbrella.

## [2026-05-18] Platform-pack manifest validation moves to a canonical JSON Schema

**Context.** Before SKILL-47 the rules describing
`platform-packs/<slug>/platform.yaml` lived only inside
`ShellContentLoader.buildPack` (Kotlin parser code), `ScaffoldSupport.kt`
(`SHELL_CONTRACT_VERSION`, `APPROVED_CODE_REVIEW_AREAS`, `CONTENT_BODY_FILENAME`),
and the in-memory `PlatformManifest` data class. No standalone document
described the manifest shape; new fields drifted across three files with no
mechanical link, and the desktop UI had nowhere to render a contract reference.

**Decision.** Adopt JSON Schema (Draft 2020-12) authored as YAML at
`orchestration/contracts/platform-pack-schema.yaml` as the source of truth for
the manifest shape. Validate manifests against the schema at runtime through
`com.networknt:json-schema-validator` (full Draft 2020-12 support, Apache-2.0)
bridged via Jackson `databind` (already required transitively by the validator).
The parser still produces the existing `PlatformManifest`; only the shape-rule
source moves. Cross-field coherence rules (`slug-parity`,
`areas-require-baseline`, `areas-equal-declared`,
`area-metadata-keys-subset-declared`, `pointers-unique-name-per-dir`) stay in
Kotlin because they are awkward to express in pure JSON Schema, but each is
named and documented in the schema file's `x-coherence-checks` block so the
schema document alone describes the full contract.

**Alternatives considered.**

- *Keep rules in Kotlin (status quo).* Rejected: drift across data model,
  parser, and `SHELL_CONTRACT_VERSION` is the problem this task solves.
- *Custom YAML-with-our-own-validator DSL.* Rejected: low leverage, every new
  rule needs custom validator code, no tooling ecosystem.
- *kaml + Kotlin data classes as schema.* Rejected: still couples schema to
  runtime code, no documentation surface, no UI viewer.

**Consequences.**

- Adds two runtime dependencies to `runtime-core`:
  `com.networknt:json-schema-validator` and Jackson `databind` /
  `dataformat-yaml`. Pure-JVM, no native bindings, no reflection magic.
- `SHELL_CONTRACT_VERSION` is pinned to the schema's `contract_version.const`
  via a parity test. Mismatch is a build break, not a runtime mystery.
- Desktop UI can surface the canonical schema file as a read-only viewer
  through the existing editor pane; no second copy of the schema lives in the
  UI module.
- Wrapping the validator behind `PlatformPackSchemaValidator` keeps the
  library choice local — swapping it later means rewriting one Kotlin file.

## [2026-05-19] Install-plan validates at BOTH builder and CLI seams (diverges from 2a)

**Context.** SKILL-48 subtask 2a (workflow-state) wired schema validation at a
single seam — the canonical `Canonical*` parse path — and relied on that one
choke-point to keep the wire honest. Subtask 2b (install-plan) explicitly
specifies dual-seam validation in AC4: both `buildInstallPlan` (in
`runtime-core`'s `InstallPlanBuilder`) and `installPlanPayload` (in
`runtime-cli`'s `InstallCliPayloads.kt`) must validate the install-plan-shaped
map against the canonical schema and loud-fail via
`InvalidInstallPlanSchemaError`.

**Decision.** Keep `InstallPlanSchemaValidator.validate(...)` calls at both
seams. The CLI seam is not a redundant safety net — it covers post-build
re-assembly that the builder cannot see (the CLI may stitch additional fields
in before emission), and AC4 of subtask 2b
(`.feature-specs/SKILL-48-runtime-contracts-expansion/spec_subtask_2b_install-plan.md`)
explicitly requires both seams to loud-fail. Diverging from the 2a single-seam
pattern is intentional for install-plan.

**Consequences.**

- The CLI-side `installPlanPayload` carries a code comment naming AC4 so
  future readers do not mistake the dual validation for accidental duplication.
- Tests under `runtime-domain` exercise the validator in isolation; the
  CLI-side coverage flows through existing CLI integration tests.
- Deferred decision: the install-plan validator currently ships as a Kotlin
  `object` singleton (`InstallPlanSchemaValidator`) rather than the 2a
  `interface + Canonical*` shape. This is acceptable while the validator has a
  single in-process consumer; revisit (lift to an interface + canonical impl)
  when a second consumer needs to substitute a fake.

**Superseded by 2026-05-28 (SKILL-52.3).** The dual-seam INTENT (validate at
both the builder seam and the CLI emission seam) still holds, but the mechanics
described above are stale: neither seam may import `InstallPlanSchemaValidator`
directly, the validator no longer lives in `runtime-core`/`runtime-domain`
(it moved to `runtime-infra/fs`), and both seams now validate through the
injected domain-owned `InstallPlanWireValidator` port (the CLI seam routes via
the thin application method `InstallService.validateInstallPlanWire`). See the
2026-05-28 entry for the relocation and the 2026-05-29 external-schema entry for
the source-of-truth and parity guarantee.

## [2026-05-28] Schema validators move from runtime-contracts to runtime-infra/fs, reached through domain ports

**Context.** SKILL-52.3 closes the runtime hexagon leak: the foundational
`runtime-contracts` leaf owned three networknt + Jackson + filesystem schema
validators (`InstallPlanSchemaValidator`, `WorkflowStateSchemaValidator` /
`CanonicalWorkflowStateSchemaValidator`, `DecompositionManifestSchemaValidator`)
plus the `DecompositionManifestCoherenceValidator`, and `runtime-domain` install
policy invoked the concrete install-plan validator at runtime. A contract leaf
and the domain should not own infrastructure-grade schema loading.

**Decision.** Move all three schema validators and the coherence validator into
`runtime-infra/fs` — the module that already owns `PlatformPackSchemaValidator`
and `NativeAgentCompositionSchemaValidator`. Reach them only through
domain-owned ports that generalize the existing `WorkflowSnapshotValidator`
pattern: `InstallPlanWireValidator` (runtime-domain `skillbill.install.model`)
and `DecompositionManifestValidator` (runtime-domain `skillbill.workflow`).
Wire each port to an infra-fs adapter through `RuntimeComponent` with
`@Provides @JvmSynthetic internal`, exactly like every other infra adapter.
The pure `*SchemaPaths` and `*_CONTRACT_VERSION` constants stay in
`runtime-contracts` (**superseded for `*SchemaPaths` by the 2026-09-24 SKILL-374
entry: the locators now live with the module that stages the resources**); the
networknt + Jackson dependencies and the three schema
`Copy` tasks move with the validators to `runtime-infra/fs`. The library choice
is unchanged.

**Reason.** Keeping `Path`-free constants in contracts preserves the single
source of truth for schema locations while removing infrastructure ownership
from the contract leaf and the domain. Routing every validator through a
domain-owned port keeps the three validators reached uniformly and lets the
composition root own the concrete wiring, so `runtime-domain`'s runtime closure
no longer pulls networknt/Jackson transitively.

**Supersedes.**

- 2026-05-24 "Preserve dual install-plan validation after policy extraction" —
  dual-seam coverage (builder + CLI emission) is preserved, but neither seam may
  live inside `runtime-domain`; both now validate through the injected
  `InstallPlanWireValidator` port.
- 2026-05-18 "Platform-pack manifest validation moves to a canonical JSON
  Schema" added the validator dependencies to `runtime-core`; they later moved
  to `runtime-contracts`. This subtask moves all schema validators to
  `runtime-infra/fs`, the module that already owns the platform-pack validator.

**Note.** The infra-side adapters live in `runtime-infra/fs`, not
`runtime-application`, because the application layer cannot depend on infra
without inverting the hexagon. The former `runtime-application`
`WorkflowSnapshotValidatorAdapter` is superseded by
`WorkflowSnapshotValidatorInfraAdapter`. Final source-of-truth wording for the
schema files themselves is recorded in the 2026-05-29 external-schema entry
below (subtask 5).

---

## [2026-05-29] External schemas are the source of truth, copied into the runtime at build time (SKILL-52.3 subtask 5)

Context: Each runtime contract schema (`install-plan`, `workflow-state`,
`decomposition-manifest`, `platform-pack`, `native-agent-composition`,
`telemetry-event`) is authored once as Draft 2020-12 YAML under
`../orchestration/contracts/`, OUTSIDE the Gradle project, and consumed at
runtime as a classpath resource by the JVM validators.

Decision: Keep `orchestration/contracts/*.yaml` as the single canonical source
of truth. `runtime-infra/fs` copies the five schema files
(`copyInstallPlanSchema`, `copyWorkflowStateSchema`,
`copyDecompositionManifestSchema`, `copyPlatformPackSchema`,
`copyNativeAgentCompositionSchema`) and `runtime-mcp` copies the sixth
(`copyTelemetryEventSchema`) into their generated resources at build time. Each
`Copy` task is config-cache-safe: the canonical source path is captured as a
plain `String` `val` at configuration time and fed to `from(...)` /
`inputs.file(...)` (no `Project`/`Task` reference is captured), while only the
`require(File(path).exists())` existence check runs inside a `doFirst {}`
guard, loud-failing with a named message if the canonical file is missing. Parity is mechanical: every
`*_CONTRACT_VERSION` constant in `runtime-contracts` (or the domain/mcp
equivalents) is pinned to its schema's `properties.contract_version.const` by a
dedicated `*SchemaContractVersionTest`, so bumping one without the other is a
build break.

Reason: The schemas are shared with the orchestration layer (CLI/MCP tooling),
so they cannot live inside one Gradle module without forking the contract.
Copying at build time keeps the runtime self-contained (validators load a
classpath resource, not a repo-relative path) while preserving the external
file as the one place a contract change is made. The loud-fail guard turns a
missing-schema misconfiguration into an immediate, named build failure instead
of a runtime `null` resource stream.

Revisit when: a schema needs to diverge between the runtime and the
orchestration tooling, or when the runtime is published as a standalone
artifact without access to `../orchestration/contracts/`.

## [2026-05-29] SKILL-52.3 subtask 4: application wire seam + open-boundary reconciliation

**Decisions.**

1. **Type `SystemService.doctor` / `version`.** Both now return
   `DoctorContract` / `VersionContract`; the CLI (`SystemCliCommands`) and MCP
   (`McpRuntime`) adapters own the `.toPayload()` call. Output stays
   byte-equivalent. The two FQNs were removed from the raw-map allow-list, the
   ARCHITECTURE.md open-boundary block, and the SKILL-52.2 `must_type_now`
   inventory group.

2. **Relabel lifecycle payloads + `LifecycleTelemetryService` as permanent open
   boundaries.** The 5 `LifecycleTelemetryPayloads` helpers and the 7
   `LifecycleTelemetryService` emit methods are forward-compatible MCP/CLI event
   bags with no stable per-key schema, so they are now annotated
   `@OpenBoundaryMap` and moved from the SKILL-52.2 `postponed_with_reason`
   group (gated, `[subtask 4]`) into `open_extension` (no subtask tag) rather
   than typed away. No event names, keys, shapes, or persisted payloads changed.
   All "will remove" / future-tense removal wording was deleted from
   ARCHITECTURE.md and `RuntimeArchitectureTest`.

**Encode-seam relocation rationale.** YAML serialization for the decomposition
manifest moved out of `runtime-application` (`DecompositionManifestFileWrites`)
behind a new `DecompositionManifestStore.encodeManifestYaml(wireMap)` port
method, implemented by the infra-fs `FileSystemDecompositionManifestFileStore`
with the same `YAMLMapper()` construction (byte-identical output). This mirrors
the subtask-1 decode seam (`DecompositionManifestValidator`): the application
layer keeps `encodeDecompositionManifestMap` (the validated-map builder) and
still calls `validator.validateYamlText` AFTER serialization, so the write path
keeps throwing `InvalidDecompositionManifestSchemaError` on invalid input.
`runtime-application` main no longer imports Jackson and its build no longer
carries the production `jackson.dataformat.yaml` dependency (relocated to
`testImplementation` for the pre-existing + new test doubles). The new port
method is `@OpenBoundaryMap`-annotated and documented in the allow-list +
`open_extension` inventory because the raw-map architecture scanner walks
`runtime-ports`.

## [2026-06-04] Goal telemetry: writes on LifecycleTelemetryRepository, goalStats() on WorkflowStatsRepository

**Context.** SKILL-66 Subtask 2 adds persistence for the goal telemetry event
family (`goal_started`, `goal_subtask_finished`, `goal_finished`). Acceptance
criterion 1 reads literally as "`LifecycleTelemetryRepository` gains methods for
the three goal events ... plus the read/aggregate queries needed for stats", which
could be read as putting the aggregate read on the same port. But every existing
lifecycle family keeps writes on `LifecycleTelemetryRepository` (write-only:
`featureImplementStarted`, `featureVerifyStarted`, `featureTaskRuntimeStarted`,
...) and puts the aggregate read on `WorkflowStatsRepository`
(`featureImplementStats()`, `featureVerifyStats()`, `featureTaskRuntimeStats()`).

**Decision.** Goal **writes** (`goalStarted`/`goalSubtaskFinished`/`goalFinished`)
go on `LifecycleTelemetryRepository`; the aggregate **read** `goalStats()` goes on
`WorkflowStatsRepository`. AC#1's own tiebreaker clause — "*following the interface
style of the existing event methods*" — selects parity placement over literal
single-port grouping. No existing family reads through
`LifecycleTelemetryRepository`, and breaking that would split the read surface
across two ports.

**Reason.** Parity keeps the stats surface single-sourced on
`WorkflowStatsRepository` (which Subtask 4's `goal_stats` tool reads), preserves
the established write/read seam separation, and avoids leaking a read method onto
the write-only telemetry port. The cost is that AC#1's literal "on
`LifecycleTelemetryRepository`" wording is satisfied for writes only; the
read lives one port over, exactly as `featureTaskRuntimeStats()` does.

**Consumers.** Subtask 3 calls the three write methods from `GoalRunner`;
Subtask 4 reads `goalStats()` for the `goal_stats` MCP tool and `goal-stats` CLI.

## [2026-06-05] Goal runtime telemetry: loud-fail, per-segment run-session id, and resume dedup (SKILL-66 Subtask 3)

Context: SKILL-66 Subtask 3 wires `goal_started`/`goal_subtask_finished`/
`goal_finished` into `GoalRunner`. Four questions: per-segment sessions vs
stable per-subtask children, resume double-counting, what `attempt_count`
means, and how a telemetry write failure relates to the best-effort
observability/ledger writes around it.

Decision:
1. Loud-fail, not best-effort. Emission goes through the seam
   `GoalLifecycleTelemetryEmitter`, implemented by `LifecycleTelemetryService`
   via `enabledStandaloneResult -> database.transaction`. When telemetry is
   enabled, a throwing write fails `GoalRunner.run`; it is deliberately not
   wrapped in `runCatching` like `GoalRunnerObservabilityEmitter` /
   `GoalRunnerLedgerRecorder`. Disabled is a silent no-op, and the default
   `GoalLifecycleTelemetryEmitter.NONE` keeps non-telemetry runs byte-equivalent.
2. (D1) `goal_started`/`goal_finished` carry
   `"<parentWorkflowId>:seg:<segmentStartedAt>"`, captured once at loop start
   from the injected clock: deterministic under a fake clock, unique per
   segment, never colliding with child `wfl-N` ids. This gives exactly one per
   run segment across resumes.
3. (D2) `goal_subtask_finished.workflow_id` is the stable child id (`wfl-N`), or
   `"<issueKey>:subtask:<id>"` for a never-launched terminal (projection skip).
   With the DB dedup key `(issue_key, subtask_id, workflow_id)` (`ON CONFLICT DO
   NOTHING`), a subtask emits at most one terminal event. The runner also
   snapshots `priorTerminal` at loop start and emits only for subtasks turning
   terminal within the current segment.
4. (D4) `attempt_count` is runtime-owned and per-segment: occurrences in the
   in-memory `attempted` list, at least 1 (1 today). Rejected: the child-progress
   `attemptCount` (counts step retries, nullable, extra read) and the ledger (no
   per-subtask count).
5. (D5) One `sweepTerminal` pass after each iteration and before `goal_finished`
   emits for every newly terminal subtask, covering `complete`, `blocked`, and
   `skipped` (set only by external manifest projection, so no per-site hook can
   catch it). `goal_finished` counts come from the final manifest.

Reason: silently dropped telemetry would make goal stats untrustworthy;
observability/ledger stay best-effort because they are diagnostic, not the
metric of record. Separating the segment session id from the child id makes
"one per segment" and "no double-count on resume" hold without a cross-segment
counter.

Consumers (Subtask 4 stats): dedupe `goal_subtask_finished` by `(issue_key,
subtask_id, child workflow_id)`; `goal_started`/`goal_finished` are per-segment
and grouped by `issue_key`; `attempt_count` is per-segment.

## [2026-07-05] pack skills internalize by flattening into one parent; baseline co-presence is loud-fail (SKILL-104)

Context: SKILL-102's internal-skill mechanism deliberately loud-failed `internal-for` on
platform-pack skills. The code-review family (34 stack skills across ios/kotlin/kmp/python) needs
the same hiding treatment, but pack skills are discovered, selected, staged, and hashed through a
selection-gated pipeline distinct from base skills.

Decision: Three Pinned Decisions shape the extension. **PD1** keeps the single shared evaluator
(`InternalSkillClassification.kt`) and relaxes ONLY the base-skill-only rule — every other rule
(blank value, self parent, unknown parent, parent must be a listed base skill, depth is 1) is
byte-for-byte unchanged; the `isBaseSkill` flag now feeds only the parent-side rule. **PD2**
flattens: all 34 sidecars are siblings inside `bill-code-review`'s staged directory (depth stays
1; nesting would require a sidecar-hosting-sidecar the staging model cannot express). **PD3**
makes sidecar discovery selection-aware: `discoverInternalSidecarTargets` accepts the plan's
selected pack skills and unions them with the skills-root scan, so an unselected pack contributes
no sidecar and no hash bytes (inertness — a repo with no opted-in pack skill stages
byte-identically). **PD8** adds a plan-time guard (`MissingBaselinePlatformSelectionError`) that
loud-fails when a selected pack declares a required `baseline_layers` entry in an unselected
pack; the shell never silently auto-includes a baseline.

Reason: A `platform.yaml`-level "internal" flag would fork a second classification source and
desynchronize the three seams (authoring, install-plan, validate); PD1's whole point is one
evaluator. Selection-aware staging is the only way to honor pack selection (hidden skills from
unselected packs must not ship) without breaking cache reuse — the parent's content hash folds
only the selected sidecars, so changing selection re-stages. The PD8 guard is pinned here, not
deferred, because today's behavior (selecting KMP alone silently installs a review whose baseline
is absent) becomes load-bearing once the baseline is a sidecar.

Trade-off: Pack sidecar discovery is source-aware (it consults `InstallPlanSkill.sourceDir` from
the plan, not an independent re-scan of `platform-packs/`), so the three staging seams (plan
builder, apply, link-skill fallback) each thread the selected pack skills. The link-skill flow
refuses internal skills upstream and never reaches the pack-sidecar path.

## [2026-08-10] Runtime-owned validate gate (SKILL-180)

**Decision.** Validate-phase build/test/gate execution moves to the runtime via pack-declared
`validation_gate` argv. The agent receives a bounded finding projection and must not invoke the
gate or quality-check skills. Terminal satisfaction requires a forced-full pack-declared run with
non-zero executed work. Missing gate declarations degrade to agent-run validate with a surfaced
observability record at `ValidationGateResolver.resolve`.

**Boundary.** Validation owns execution; audit and repair evidence remain read-only repository
facts agents read but do not produce by running builds or tests outside validate's runtime-owned
gate cycle.

## [2026-08-30] Compiler suppression allow-list lock

The allow-list rows live in `PrincipleEnforcementInventory.suppressionAllowList`, which the suppression
guard reads directly; complexity rule names are never allow-listed.


## [2026-09-04] Guard recalibration: line ceiling 1200, TooManyFunctions 40/45, constructor threshold 12, LargeClass 1200 (SKILL-233 subtask 1)
Context: SKILL-233 measured that the 500-line ceiling, `TooManyFunctions` at 11 in every scope, and `LongParameterList.constructorThreshold` at 7 produced 65 `FeatureTaskRuntimeRunLoop*` fragments, ten `FeatureTaskRuntimeRunState*` files, thirty `*Deps`/`*Dependencies`/`*Collaborators` bundles, and 13 forwarding `*Bindings` objects. The largest cohesive cluster SKILL-231 attributed measured 1069 lines.
Decision: This entry supersedes the 2026-08-29 500-line entry. `PrincipleEnforcementInventory.PRODUCTION_LINE_CEILING` is 1200; `productionLineCeilingExemptions` stays empty; the logical-type ceiling test keeps the same constant so extension-file splitting cannot hide size. Detekt `TooManyFunctions` is 40 for classes (`thresholdInClasses`) and objects (`thresholdInObjects`), 45 for files (`thresholdInFiles`); `thresholdInInterfaces` and `thresholdInEnums` stay 11 as the SKILL-231 port-width guard. Detekt `LongParameterList.constructorThreshold` is 12 with `functionThreshold` 6 unchanged. Detekt `LargeClass.threshold` is 1200, equal to the file ceiling, so a class cannot exceed its file and detekt still names an oversized class if the architecture test is skipped. `LongMethod` 60, `CyclomaticComplexMethod` 15, `NestedBlockDepth` 6, and `ComplexCondition` 4 are unchanged; they measure functions, and function size is the limit that still matters.
Reason: The re-merged run state carries 43 raw member functions (10 own plus 33 former extensions) and the checkpoint unit carries 44 raw functions before forwarder removal; both are single responsibilities that the subtask names as one unit, so 40 is the smallest class threshold that does not force a count split, and 45 for files leaves room for one file-scoped unit with a few top-level helpers. The spec expected roughly 25; that value was rejected because it would re-split the two measured units by count rather than by responsibility. Twelve injected collaborators is over-injection; when a constructor reaches 12 the split point is a real sub-collaborator with a name a reader can state, never a bundle that only one constructor consumes. The ceiling moves by this entry, never by baseline: guards ratchet by decision, and `productionLineCeilingExemptions` and every spillover baseline stay empty.
Spillover-scanner scope: the numbered-suffix rule (`Continued<N>`, `Helpers<N>`, `Fns<N>`, `Support<N>`, `[A-Z]<N>`, bare-digit siblings) keeps scanning every `src` root including tests; the new bare `Support`, `Helpers`, `Misc`, `Extras` file-and-type rule and the new identifier rule apply only to paths containing `/src/main/`, matching the 2026-08-29 decision that production rules scope to `src/main`, so the 42 `*TestSupport` helpers keep their names. Rename ownership: this subtask renames every suffixed file, type, and member in all ten modules (including `JsonSupport` in `runtime-contracts` and the `*Extras` types in `runtime-domain` and `runtime-ports`) as a rename-only final step because AC-002 requires an empty census across all ten modules; behaviour moves inside ports, domain, and contracts remain subtask 2 work. The two `@Inject` data classes in `runtime-ports` `WorkflowGoalRunnerStoreDepsModels.kt` are deleted here because AC-005 requires it and subtask 2 wants the same deletion; both overlaps are named in the subtask report.
Alternatives considered: Baseline entries for the oversized merged units (rejected: 2026-09-02 permanent-floor decision). `TooManyFunctions` 25 (rejected: forces a count split of two measured single-responsibility units). Leaving `LargeClass` at 600 (rejected: would contradict the 1200-line file ceiling and force a suppression on every merged unit). Keeping `thresholdInInterfaces` at 40 (rejected: port width is the SKILL-231 guard against composite ports).
Revisit when: a merged unit legitimately exceeds 1200 lines or 40 functions and the alternative is a named responsibility split, not a suffix file.

## [2026-09-06] Ports evacuation and inward-layer purity (SKILL-233 subtask 2)

**(a) Null-object substitutes leave production; reached absences become nullable ports.**
Every `Noop`/`Unavailable`/`Empty`/`Unconfigured` object moved out of `src/main` into the owning
module's `src/testFixtures` under its original package, so no consumer import changed and the
substitutes cannot be resolved from a published runtime. Where a production call site actually
reached a total refusal, the port became nullable and the site names its fallback:
`TransportContext.requester` (`?: JdkHttpRequester` in `RuntimeBootstrapBindings` and
`HttpTelemetryClient`), `WorkflowOpsContext.workflowGitOperations` (`?: git` in
`RuntimeWorkflowProvides`), and `DecompositionWorkflowContinuation.fileStore` (absent store returns
no disk manifest). `RecordingNullObjectDiagnostics` and its global bind are deleted: a substitute
that is unreachable from production has nothing to record.
Alternative rejected: keeping the classification census and its recording contract — it made a
production reachability problem look like a documentation problem.

**(b) `GoalRunnerManifestStore` is one flat interface with no default bodies.**
The six sub-interfaces (`GoalRunnerManifestLookup`, `…PauseOps`, `…ExecutionLease`,
`…ControlCommands`, `…PersistenceCommands`, `…ReviewCommands`) are now `internal` declarations in
`runtime-infra/sqlite`, where the delegating store is assembled. The port declares ~35 abstract
members. Test fakes that relied on the removed default bodies extend
`GoalRunnerManifestStoreDefaults` in `runtime-ports` testFixtures, which reproduces the former
defaults exactly.
Alternative rejected: expanding the defaults into all 13 fakes — ~450 lines of restated behaviour
with no assertion behind it.

**(c) `UnitOfWork` declares only abstract members.**
The three defaulted repositories and the two nullable diagnostics accessors are abstract; the
production `SQLiteRepositories` already supplied all five. The 14 anonymous test implementations
extend `UnitOfWorkDefaults` in `runtime-ports` testFixtures.

**(d) `runtime-ports` imports no adapter machinery.**
`AttemptLedgerWorkflowDecoding` was duplicated byte-for-byte in `runtime-ports` and
`runtime-application`. It decodes `WorkflowStepState`, a `runtime-domain` type, and its consumers
span `runtime-infra/fs`, `runtime-infra/sqlite`, and `runtime-application` — modules whose only
common visible ancestor is `runtime-domain`. Both copies are deleted and the single home is
`skillbill.workflow.engine`. That removes the last `kotlinx.serialization` import from
`runtime-ports` and one duplicate basename pair. `kotlin-inject` leaves the `runtime-ports` Gradle
edge with it.
Alternative rejected: keeping the ports copy and deleting the application one — it would leave
serialization in a module that must declare interfaces and DTOs only.

**(e) `runtime-contracts` stops exporting `kotlinx-serialization-json`.**
The edge narrows from `api` to `implementation` and every module that names a kotlinx type declares
the dependency itself. `JsonCodec` still exposes `JsonObject` and `JsonElement`, so the declaration
is not optional for its consumers; making that explicit is the point.
Superseded by: runtime-contracts exports kotlinx-serialization-json as `api` (2026-09-24)

## [2026-09-06] Audit-gap remediation: interface segregation restored, second ports wave, Path migration scoped out (SKILL-233 implement attempt 2)

(a) `GoalRunnerManifestStore` is again a composite of six segregated interfaces,
superseding decision (b) of the 2026-09-06 subtask-2 entry. The flattened port
had 34 functions against detekt's interface `TooManyFunctions` threshold of 11.
The six (`GoalRunnerManifestLookup`, `…PauseOps`, `…ExecutionLease`,
`…ControlCommands`, `…PersistenceCommands`, `…ReviewCommands`) live in
`runtime-ports`; consumers and fakes are unchanged and no default bodies returned.
Rejected: `@Suppress("TooManyFunctions")`; the audit forbids clearing a gap with
a suppression, exemption, or baseline, and the threshold flags real cohesion loss.

(b) Snapshot wire projection is adapter work; the domain port takes the typed
record. `WorkflowSnapshotValidator.validate` takes `WorkflowStateSnapshot`
instead of a map built in `runtime-domain`; the canonical wire shape is built by
`skillbill.infrastructure.fs.WorkflowStateSnapshotWireMapper`. The other wire
maps moved from `skillbill.workflow.engine.WorkflowEngineWireMaps` to
`skillbill.application.workflow.WorkflowWireProjections`; `artifactSummaryMap`
went private rather than earning another allow-list row.

(c) `AttemptLedgerWorkflowDecoding` declares a private lenient integer coercion
(Int, Number.toInt(), String.toIntOrNull(), else null) instead of reusing
`asExactIntOrNull`, which rejects lossy numbers by design; widening it would
turn one caller's leniency into the other's silent truncation.

(d) Duplicated ports/application basenames collapse into `runtime-domain` only
when the shared code is free of port types. This round moved 19 ports files to
`runtime-domain` and deleted 13 application duplicates (unresolved pairs 39 to
19). `ReviewRepository` and `UnitOfWork` parameters became the function type
`(String) -> List<ReviewFindingVerdict>`. Nine byte-identical pairs
(`AttemptLedgerDecoding`, `AttemptLedgerAccumulator`,
`AttemptLedgerProgressEvents`, `GoalTerminalOutcomeDerivation`,
`GoalObservabilityArtifacts`, and their DTOs) were reachable from
`runtime-infra/sqlite` via ports and from `runtime-core` via application, so
neither copy could go until their pure DTOs moved to `skillbill.goalrunner.model`
in `runtime-domain`. Each collapse also removed a duplicate `@OpenBoundaryMap`
allow-list row in `ARCHITECTURE.md`.

(e) The `java.nio.file` half of AC-009 is its own subtask; `kotlinx.serialization`
and `StandardCharsets` are closed and guarded. `RuntimeContractModuleImportRulesTest`
now bans `java.io.`, `java.nio.charset.`, `com.fasterxml.`,
`kotlinx.serialization.`, and `org.yaml.` in `runtime-domain`, with no baseline.
`java.nio.file` remains in 15 domain files declaring 85 Path-carrying types used
from 306 files across nine modules. Introducing `FileLocation` and pushing
`Paths.get`/`normalize`/`resolve` into `runtime-infra/fs` changes call sites,
not just imports, so it cannot land behind one green gate in a phase.
Consequence: the remaining 19 pairs stay. Twelve (`DecompositionManifest*`,
`DecompositionWorkflowRuntimeLookup*`) depend on `DecompositionManifestStore`,
whose seven members take `Path`; the rest carry `WorkflowStateRepository`,
`WorkflowStateRecord`, or `WorkflowFamily`. Both groups wait on `FileLocation`.

Revisit when: `FileLocation` lands; re-run the pair census and expect the twelve
decomposition pairs to collapse in one move.

## [2026-09-18] Ledger-stamped user_version and migrate-on-access retirement (SKILL-356 subtask 3)

Context: `PRAGMA user_version` was read for readiness but never written; `establishSchemaReadiness` also ran unconditional column probes and lazy legacy goal-runner and telemetry repairs on every open.
Decision: Stamp `user_version` to the highest applied migration version inside `DatabaseMigrations.apply`; move column ensure, heals, and legacy artifact repairs into append-only ledger migrations; delete lazy migration call sites; raise `DatabaseAccessError(READ)` from unreadable identity reads instead of silent null.
Reason: Readiness cache and routine writes must reflect ledger state; one-time repairs belong in the migration ledger with observability records, not in hot paths.
Alternatives considered: Stamping `user_version` outside the ledger transaction (rejected: diverges from applied migration set). Keeping lazy migrations for safety (rejected: violates routine-work boundary and observability policy).
Consequence: Fresh and upgraded databases converge through migrations 39–41; `reconcileStaleSessions` no longer restamps telemetry on every call.

## [2026-09-18] SQLite cross-module test fixtures (SKILL-356)

Context: Review and telemetry persistence tests in `runtime-cli`, `runtime-core`, `runtime-mcp`, and `runtime-engine` reached SQLite through `skillbill.infrastructure.sqlite.core` and internal store types, coupling consumer tests to adapter internals and blocking `internal` visibility on stores.
Decision: Sanction `:runtime-infra:sqlite` `testFixtures` as the only cross-module SQLite test entry: `establishTemporarySchemaReadiness`, `ensureTestDatabase`, `sqliteSessionFactoryForTests`, `withLifecycleTelemetryStore`, `withTelemetryOutboxStore`, and `TelemetryOutboxTestHandle` / `telemetryOutboxOnConnection` for outbox assertions that need `listPending`. Consumer modules add `testImplementation(testFixtures(project(":runtime-infra:sqlite")))`.
Reason: Keeps schema readiness and session-factory behaviour aligned with production while exposing only port types or fixture handles at module boundaries.
Alternatives considered: Keeping stores public for tests (rejected: widens the DI surface). Duplicating fixture helpers per consumer module (rejected: drift from production readiness).

## [2026-09-18] Omit unmeasured scaffold duration (SKILL-357)

Scaffold telemetry omits `duration_seconds` because this adapter does not measure
elapsed time. Reporting zero would present an unmeasured value as a measurement.

## [2026-09-19] Pack validation_gate owns quality-check argv (SKILL-360)

Context: Shipped platform packs carried a second `quality-check/` skill and `declared_quality_check_file`; `bill-code-check` routed to pack checker sidecars.
Decision: Quality-check collect-all and confirmation are the dominant pack `validation_gate` argv only. `routeQualityCheck` selects the pack slug and fixes `routed_skill` to `bill-code-check`; missing `validation_gate` on the winning pack raises `MissingValidationGateError`. Scaffold, install, and review-structure validation no longer emit or require pack checker skills. Optional `declared_quality_check_file` remains schema-parseable for leftover custom packs. Install still treats that path as a declared skill directory when present, and skips undeclared `quality-check/` `addon_usage` keys so a leftover checker registration cannot block reconcile.
Reason: One shell (`bill-code-check`), one gate surface per pack, no duplicate command-discovery sidecars. Reconcile enumerates the existing local pack copy with the new runtime before upstream can replace it.
Alternatives considered: Keep pack checker skills as documentation-only (rejected: install and routing still duplicated argv). Generic command-discovery fallback when gate is absent (rejected: silent wrong-suite risk). Loud-fail leftover `quality-check/` `addon_usage` (rejected: `install reconcile` cannot apply the cleaned upstream pack).

## [2026-09-22] Spotless ratchet removed; tree-wide ktlint adopted (SKILL-368)

Context: `ratchetFrom("origin/main")` scoped Spotless to files differing from that ref, so roughly 2,900 of 2,948 Kotlin files had never been linted. The ratchet also failed in linked worktrees (jgit does not resolve `gitdir:` files) and in clones lacking the ref, and forced `GovernedResourceCopyParityTest` to fabricate an `origin/main`.
Decision: Remove the ratchet and format the whole tree to ktlint's fixed point in one migration. Line length comes from `runtime-kotlin/.editorconfig` (120) with no `editorConfigOverride`. Raise detekt `LongMethod` from 60 to 70.
Reason: Tree-wide linting is the honest end state and makes worktrees and fresh clones work. The `LongMethod` bump absorbs line growth from the `ktlint_official` wrapping rules, which put assignment right-hand sides on their own line; the 62 functions that crossed the old limit measured 60-68 lines and gained height, not complexity. Extracting them would have been a large unrelated refactor driven by formatting.
Alternatives considered: Keep the ratchet (rejected: leaves the tree unlinted and keeps worktrees broken). Ratchet from a merge-base sha (rejected: avoids the jgit failure but still leaves the tree unlinted). Switch to `ktlint_code_style = intellij_idea` (rejected: same 2,400-file churn and stops enforcing the 120-column limit).
Consequence: One mechanical reformat commit touches about 2,400 files; `spotlessApply` converges and normal edits produce normal diffs from here.

## [2026-09-22] External platform pack copy allowlist (SKILL-369)

Context: Install and review-catalog staging copy declared pack files from registered external roots into managed output.
Decision: A resolved path may be read only inside the registered pack root for that slug or under the checkout `.bill-shared` directory referenced by governed pointers. Symlink or join escapes outside those roots fail before promotion.
Reason: Prevents external pack registration from becoming an arbitrary file read during install or catalog staging.
Alternatives considered: Trusting symlink targets under the author tree (rejected: escapes user home or VCS boundaries). Merging shadowed bundled files into external packs (rejected: whole-pack replacement only).

## [2026-09-22] Review-routing fallback is returned, not logged in the domain (SKILL-368 merge)

Context: `ReviewStackRouting` warned through `java.util.logging` when a routed pack's baseline platform was absent, which the runtime-domain effect-purity guard bans.
Decision: `route` returns `missingPackFallbacks` on `ReviewStackRoutingResult`, and `FileSystemDeclaredReviewSpecialists` emits the record with the same seam, pack, used, expected, and cause fields.
Reason: The observability policy still gets its fallback record, and the domain stays free of ambient effects.
Alternatives considered: Exempt the file from the purity guard (rejected: the guard is what keeps the domain testable without ambient wiring). Drop the warning (rejected: a silent routing fallback is the defect the policy names).

## [2026-09-24] Architecture guards iterate over every declared module; rule inventory names its proving test

Context: The ambient-clock, ambient-environment, and `@Inject`-defaults rules were hand-written per module, so a module with no hand-written case was never scanned. The engine ambient-clock baseline had drifted by line number without failing, which proved no engine case ran. Baselines keyed rows as `path:line:call`, so reformatting churned them.

Decision: Each of the three rules is one iterating test over `PrincipleEnforcementInventory.moduleArchitectureScanCases`, plus one rejection fixture that seeds a synthetic violation into a temporary `runtime-engine` tree. Baseline rows are grouped `path:call:count` with no line numbers. `PrincipleEnforcementInventory.enforceableRules` is a list of `EnforcedRule(rule, test)` pairs, so a rule cannot stay listed once its test is gone, and each rule text states current behaviour rather than migration history. The compiler-suppression allow-list moved from a markdown table in this file into `PrincipleEnforcementInventory.suppressionAllowList`; this file records only where it lives.

Reason: A rule counts as enforced only if it runs where it claims to run and names the test that proves it. Line-keyed baselines made a real drift look like noise and a reformat look like a violation.

Alternatives considered: Keep per-module test methods and add the missing ones (rejected: the next module added would silently go unscanned again). Keep the allow-list table here and parse the markdown (rejected: a test that reads prose fails for reasons unrelated to the rule).

## [2026-09-24] Architecture tests stop pinning history, counts, and source restatements

Context: The suite pinned retired type names, exact member counts, and verbatim current declarations, and several tests asserted only that prose in `ARCHITECTURE.md`, `agent/decisions.md`, or a historical evaluation document contained given phrases.

Decision: A test stays only if it asserts an invariant that neither the compiler nor the `RuntimeModuleCatalog` topology enforces. No architecture test reads `ARCHITECTURE.md` or any `agent/*.md`. Deleted: `RuntimeArchitectureDocumentationTest`, `PrincipleEnforcementInventoryTest`, `FeatureTaskRuntimeParameterBagArchitectureTest`, `FeatureTaskRuntimeBoundaryOwnershipArchitectureTest`, and `FeatureTaskRuntimeRunLoopContextExtensionCensusArchitectureTest`, plus the retired-shape and prose clauses inside surviving tests.

Reason: Pinning a deleted name or a rule count fails on the next honest rename and proves nothing about the boundary; documentation is kept true by review, not by a string search.

Alternatives considered: Keep the doc-phrase assertions as drift detection (rejected: they fail on wording changes and pass on wrong content).

## [2026-09-24] Experiment pair coordinators keep telemetry off through an explicit nullable binding

Context: Dropping the `@Inject` constructor defaults from `ExperimentPairCoordinator` and `ExperimentNavigationPairCoordinator` removed `telemetryRecorder: ExperimentTelemetryRecorder? = null`, so the nullable parameter now needs a binding. The outbox recorder is bound on the non-null `ExperimentTelemetryRecorder` type, which no constructor asks for.

Decision: `optionalExperimentTelemetryRecorder` returns `null`. The DI-built coordinators record no experiment telemetry, exactly as the removed default did.

Reason: The guard bundle removes defaults; it does not switch a delivery path on. Binding the outbox recorder here would start writing experiment pair events to the telemetry outbox as a side effect of a guard change, with no measurement of what that adds to the outbox.

Alternatives considered: Bind `ExperimentTelemetryOutboxRecorder` to the nullable parameter (rejected: behaviour change outside this bundle; it belongs to the change that wants experiment pair telemetry, which would also drop the then-redundant nullable binding). Make the parameter non-null (rejected: the coordinators treat a missing recorder as a supported state, and the tests construct them without one).

## [2026-09-24] One-shot process execution belongs to `runtime-infra/host`; `runtime-infra/launcher` owns long-running agents

Context: Process spawning was split by feature rather than by lifetime. `InstallerProcessAdapter` lived in `runtime-infra/launcher` beside the agent-run launcher, while git invocation built its own `ProcessBuilder` in `runtime-infra/workflow`. Three separate implementations of deadline, bounded capture, and owned-descendant teardown drifted apart, and `ARCHITECTURE.md` named all five infra modules as the owner of "filesystem/process mechanics", which is not an owner.

Decision: The split is by process lifetime. `runtime-infra/host` owns one-shot execution — a bounded command run to completion or killed, with captured output — through `BoundedExternalProcessRunner`; `InstallerProcessAdapter`, gate JVM resolution, git-tracked-file listing, and git invocation all go through it. `runtime-infra/launcher` owns long-running agent processes: progress probes, idle policy, heartbeat supervision, and streaming output. `runtime-infra/workflow` keeps git semantics (trimmed output, exit `-1` on timeout, `IOException` as `readFailure`) by mapping the host runner's result, not by spawning its own process.

Reason: Deadline handling, output bounding, and killing the owned descendant tree are the same problem for every one-shot command and a different problem for a supervised agent. One owner per lifetime means a teardown fix lands once. Feature-based placement gave `InstallerProcessAdapter` the launcher's dependencies without any of its supervision needs.

Alternatives considered: One process module owning both lifetimes (rejected: the agent launcher's probe, idle, and heartbeat surface has no one-shot caller, and pulling it into host would put it on every git call's classpath). Leaving `InstallerProcessAdapter` in launcher and sharing only a helper (rejected: the adapter's teardown is exactly the host runner's teardown; a shared helper reached across a module boundary is the split with extra indirection).

## [2026-09-25] Adapters hold no port-only coordination; goal-runner coordination moves to `runtime-engine`

Context: `skillbill.infrastructure.sqlite.goalrunner` held the goal-runner control coordinator, manifest store and projection persistence, outcome store, block writes, reconciliation, scoped replan, and child repair. None of it issued SQL. It composed `WorkflowEngine`, `WorkflowStateRepository`, `GoalRunnerControlRepository`, and the git, supervisor, validator, and manifest ports that the composition root already binds, and it took `WorkflowGitOperations`, `FeatureTaskRuntimeWorkerSupervisor`, `GoalRunnerChildRepairRunnerPort`, `DecompositionManifestStore`, `DecompositionManifestProjectionWriter`, and `GoalChildPlanningHydratorPort` as constructor parameters. Living in the adapter forced duplicate copies of twenty domain and application helpers — `workflowFamilyFor`, `pauseAtOperatorBoundary`, `withParentStatus`, `requireRuntimeModeForEngineWrite`, `generateWorkflowId`, the phase-artifact codecs, and the rest — because `runtime-infra/sqlite` cannot depend on `runtime-application` or `runtime-engine`.

Decision: A class belongs in an adapter only if it speaks that adapter's technology. Coordination that merely composes ports moves to `runtime-engine` — `status`, `manifest`, `persist`, `reset`, and `repair` under `skillbill.engine.goalrunner`. `runtime-infra/sqlite` production code now declares no public top-level function, and its public types are `SQLiteDatabaseSessionFactory`, `SqliteFeatureTaskPhaseSettlementRepository`, and `SqliteExperimentPairOwnerStore`. Each moved class calls the single application or domain owner of the helper it used to copy; the duplicates are deleted. `WorkflowGoalRunnerOutcomeStoreDependencies` is gone: the outcome store takes its eight collaborators directly, and the child-repair concern it also carried is its own `WorkflowGoalRunnerChildRepairStore` bound to `GoalRunnerChildRepairStore`, so no constructor approaches detekt's threshold and no bag stands in for a split.

Reason: The adapter boundary is about technology, not about which layer happens to own the database handle. Port-only coordination parked behind that boundary cannot reach the code that owns its vocabulary, so it grows a private copy of the domain — which is how twenty helpers came to have two definitions that could drift independently.

Alternatives considered: Give `runtime-infra/sqlite` a dependency on `runtime-application` so the copies could be deleted in place (rejected: the edge points the wrong way and would put every application use case on the adapter's classpath). Keep the dependency carrier and add a second one as the constructor grew (rejected: a carrier hides a missing split behind a parameter count that stops growing).

## [2026-09-25] Infra package-cycle scans use the module's own package root, and `nativeagent <-> scaffold` is the one recorded cycle

Context: `PrincipleEnforcementInventory.packagePrefixForModule` returned `"skillbill."` for every module it did not name, which is every `runtime-infra` module. With that prefix the first segment of an infra package is always `infrastructure`, so every infra file resolved to one node, no edge ever left it, and the seven infra package-cycle baselines were empty because the scan could not see an infra edge, not because there were none.

Decision: Unnamed modules take their prefix from `RuntimeModuleCatalog.moduleMainPackageRoots`, so `runtime-infra:skills` scans `skillbill.infrastructure.skills.` and its nodes are `agentaddon`, `install`, `nativeagent`, `scaffold`, `skillremove`, and the rest. The SQLite baseline stays empty and now means it. The skills baseline holds exactly one row, `nativeagent|scaffold`. Three `install` helpers that `scaffold` imported — `packRootsBySlug`, `selectedPlatformManifests`, and `sourceKind` — moved unchanged into `skillbill.infrastructure.skills.scaffold.platformpack`, and `FileSystemScaffoldGateway`, `FileSystemScaffoldOrchestrator`, and `FileSystemScaffoldInstallLink` moved into `skills.install.scaffold`, so no `skills.scaffold` file imports `skills.install`. The corrected scan also exposed two pairs held together only by dead imports, `externaladdon <-> scaffold` in skills and `phaseoutput <-> workflow` in contracts. Each importing file shadows the imported name with its own member or local, so deleting those imports closes both pairs with no behaviour change, and every infra baseline other than skills stays empty.

Reason: A guard that cannot observe the thing it guards is worse than no guard, because its empty baseline reads as proof. Recording the one real cycle rather than suppressing it keeps the baseline honest and makes the next new cycle fail the build.

Alternatives considered: Break `nativeagent <-> scaffold` in the same change (rejected: `nativeagent` rendering and `scaffold` platform-pack loading genuinely co-depend today, and untangling them is a design change, not a package move). Keep `"skillbill."` and add per-module exceptions (rejected: the catalog already records each module's root, so the exception list would be a second copy that drifts).

## [2026-09-25] One noun-family package per twelve files, and every test package names a production package

Context: `runtime-infra` had packages whose path repeated a segment (`skills.install.staging.staging`, `sqlite.workflow.workflow`, `contracts.workflow.workflow`), single-file packages nested four deep under a noun that no longer distinguished anything (`contracts.workflow.featuretask.phase.task.runtime.quarantine`), and test files in packages that had no production counterpart in their module — so a reader could not tell which production code a test covered, and the package tree encoded the order in which files were added rather than what they are.

Decision: A subtree uses the fewest noun-family packages that keep every package at twelve Kotlin files or fewer; a package name never repeats its parent's segment; and every `src/test` package under `runtime-infra` also exists as a production package in the same module, so a test sits with the code it exercises. `testsupport`/`testing` fixture packages and `src/repoTest` suites, which check repository content rather than a module's classes, are exempt.

Reason: Twelve files is the point at which a package stops being readable in one screen; below it, a child package adds a name to remember and buys nothing. A test package with no production twin is a claim about structure that the production tree does not make.

Alternatives considered: Add an architecture guard for stutter paths and orphan test packages (rejected: this bundle sets the layout; a guard that pins it is a separate decision about what is worth failing the build for). A flat package per module (rejected: several infra modules hold well over twelve files and would lose every meaningful grouping).

## [2026-09-24] runtime-contracts holds compile-time constants, not ambient YAML loaders

Context: `GoalVerificationBoundaryCaps`, `GoalPlanningDiscoveryExclusions`, `IssueKeyShape`, and `PackagedContractYamlNumbers` read checked-in YAML off the classpath from inside `runtime-contracts`, which forced SnakeYAML, `java.io`, and three `Copy` tasks staging canonical schemas into the module's own resources. The values were fixed at build time and never varied per run.

Decision: Each value is now a Kotlin `const val` owned by the layer that reads it: verification caps and reporting bounds live beside the planning group in `GoalPlanningContext` (runtime-ports), the discovery exclusion list is `GoalPlanningExcludedPaths` (runtime-domain), and issue-key shape is `skillbill.contracts.issuekey.IssueKeys`. `runtime-contracts/build.gradle.kts` declares only `kotlinx-serialization-json`; the `issue-key-schema.yaml` classpath resource moved to `runtime-infra/contracts` `governedResources`, which is where `IssueKeySchemaRefInlining` reads it. `IssueKeySchemaLengthRepoTest` in `runtime-infra/contracts/src/repoTest` pins the schema's `minLength` to 1 and `maxLength` to `MAX_ISSUE_KEY_LENGTH`.

Reason: A contracts module that loads files has an I/O dependency and a resource-staging build graph for data that a constant expresses exactly. The parity direction is preserved — a repo test still asserts the canonical YAML agrees with the Kotlin bound — but the runtime no longer pays for it.

Supersedes: the SKILL-174 pattern in `agent/history.md` 2026-08-09 ("follow this pattern for any future repo-owned contract the runtime must read"). Build-time constants are Kotlin constants, not packaged YAML read at runtime. It also reverses SKILL-349's retention decision (`runtime-contracts/agent/history.md`, SKILL-349 subtask 2), which kept the packaged-YAML loaders and rejected moving them behind a port as too large a dependency change. The evidence SKILL-349 did not weigh: the three loaders plus `PackagedContractYamlNumbers` were 468 lines, with 389 lines of tests, three `Copy` tasks, and a SnakeYAML dependency re-implementing JSON Schema rules (`type: integer`, `minimum`, `uniqueItems`) to deliver about 30 values that were already baked into the jar. The planning caps for the same seven fields were already `const val` in runtime-ports, and the caps and exclusions YAML had no reader besides the loaders. This decision needs neither a port nor a move.

Alternatives considered: Keep the loaders and cache the parse (rejected: the I/O dependency and the `Copy` tasks stay). Generate the constants from YAML at build time (rejected: a code generator for four fixed values, and the generated source still needs the parity test).

## [2026-09-24] runtime-contracts exports kotlinx-serialization-json as `api`

Context: Decision (e) of the 2026-09-06 "Ports evacuation and inward-layer purity" entry narrowed the `runtime-contracts` kotlinx edge to `implementation` and had every module that names a kotlinx type declare it. The edge is `api` again, and runtime-engine and runtime-infra/http still declared `implementation(libs.kotlinx.serialization.json)` without importing anything from `kotlinx.serialization`.

Decision: `runtime-contracts` keeps `api(libs.kotlinx.serialization.json)`, because `JsonCodec` exposes `JsonObject` and `JsonElement` in its public signatures and every consumer of those signatures needs the type on its compile classpath. The redundant declarations in runtime-engine and runtime-infra/http are removed. runtime-ports' declaration is left to SKILL-377. This supersedes 2026-09-06 (e).

Reason: A type in a public signature is part of the module's API. Declaring the library `implementation` and asking each consumer to re-declare it adds Gradle lines without isolating anything, and it leaves stale copies behind once a consumer stops using kotlinx directly.

Alternatives considered: Keep `implementation` and per-consumer declarations (rejected: the exported types make the edge transitive in practice). Hide kotlinx behind contract-owned JSON types (rejected: a wrapper layer over a stable library for no consumer benefit).

## [2026-09-24] The `skillbill.error` packages are acyclic; the base exception sits in `core`

Context: `skillbill.error.core` imported `ShellContentContractException` and `FeatureTaskRuntimePhaseOutputFailureKind` from `skillbill.error.shellcontent`, `shellcontent` imported `InvalidFeatureTaskRuntimeHandoffProjectionContext` from `skillbill.error.featuretask`, and `featuretask` imported back into `shellcontent`. Every error package depended on every other.

Decision: `ShellContentContractException` moved to `skillbill.error.core` next to `SkillBillRuntimeException`. The two coarse failure-kind enums and `coarseFailureKindForPhaseOutputWireCode` moved to `skillbill.error.featuretask`. The dependency direction is now `core <- featuretask <- shellcontent`, with `goalrunner` and `learning` depending only on `core`.

Reason: The base exception and the wire-code contract are the shared root of the hierarchy, so they belong in the root package. The failure-kind enums are feature-task vocabulary that the shell-content errors consume, not the reverse.

Alternatives considered: Leave the cycle and widen the baseline (rejected: the cycle was the reason the contracts module could not be scanned at exact-package granularity). Merge all error packages into one (rejected: loses the per-surface grouping that makes the hierarchy navigable).

## [2026-09-24] runtime-contracts is scanned for cycles at exact-package granularity and banned from I/O

Context: `runtime-contracts` was scanned with `FIRST_SEGMENT_MUTUAL_PAIR` under the `skillbill.contracts.` prefix, so the `skillbill.error.*` cycle was outside the scan entirely, and the purity lock banned only networknt, Jackson, and `java.nio.file.Files`.

Decision: `PrincipleEnforcementInventory.moduleArchitectureScanCase` gives `runtime-contracts` `EXACT_PACKAGE_SCC` under the default `skillbill.` prefix, with the baseline left empty. `contractsForbiddenImports` adds `org.yaml.` and `java.io.`; `contractsForbiddenSourceReferences` adds those plus `getResourceAsStream`, and the `ContractsLeak` synthetic fixture grew to exercise each new entry.

Reason: The guard has to cover the whole module and the whole class of ambient loading, not one library and one entry point. An empty baseline is the statement that the module is acyclic today and will fail loudly if it stops being.

Alternatives considered: Seed the baseline with the current state (rejected: nothing is left to baseline). Ban `java.` wholesale (rejected: `java.util` and friends are legitimate in a pure data module).
## [2026-09-23] Experiment pairing deleted; inner-layer gaps closed (SKILL-378)

Context: The SKILL-366 experiment surface shipped arm selection, pair leases, observations, and reports, but no experiment was ever defined and no caller consumed the results. It kept ports, stores, contracts, a CLI command group, config keys, and launcher capability plumbing alive across every layer.
Decision: Delete the whole experiment surface — engine services, ports, domain models, contract schemas and key objects, SQLite stores, host and launcher adapters, DI mixins, and the `skill-bill experiments` command group with the `--experiments` option on `goal` and `goal preflight`. The SKILL-366 migrations 42-44 stay recorded as applied history; migration 45 `skill-378-drop-experiment-tables` drops the five tables. Repo-local and machine configs tolerate a leftover `experiments` key rather than loud-failing on upgrade. The checkpoint ref namespace is now the `FEATURE_TASK_RUNTIME_CHECKPOINT_REF_NAMESPACE` constant at every call site instead of an experiment-aware resolver, which preserves existing ref names byte for byte. Removing the surface also closed the gaps it was hiding: `runtime-engine` main takes `Clock` and `Random` by construction instead of reading ambient time and randomness, the layer-boundary file-IO guard now scans `runtime-engine` main with a synthetic violation test, and six test-only null objects moved from interface companions into `testFixtures`.
Reason: Unreachable machinery with no consumer costs maintenance at every contract bump and blocks the boundary guards it sits behind. Deletion over addition.
Alternatives considered: Keep the surface behind a disabled flag (rejected: the flag is still a live seam across nine modules). Drop the tables without a migration (rejected: existing stores would keep orphan tables forever). Loud-fail a leftover `experiments` config key (rejected: breaks upgrades for repos that enabled it).
Revisit when: a concrete experiment with a named consumer of its outcome exists; re-add from the SKILL-366 history rather than reviving the generic framework speculatively.

## [2026-09-27] Phase runs share the run loop over an in-memory run state (SKILL-380)

Context: `skill-bill code-review` drove `ParallelCodeReviewRunner` directly, so a standalone review never verified or fixed findings, and no other phase could run outside a durable feature-task workflow.
Decision: `PhaseRunEntry` (`skillbill.engine.featuretask.phaserun`) drives an `IN_MEMORY` skeleton definition (`review`, `validation`) through the same `FeatureTaskRuntimeRunLoopEntry` as a full run, over `InMemoryPhaseRunState`. It keeps no workflow row, resolves no branch, and loud-fails a checkpoint commit with `InMemoryPhaseRunUnsupportedError`. `PhaseRunState` gains three default no-op capabilities (`recordReviewRun`, `qualityCheckStarted`, `qualityCheckFinished`) that only the in-memory state implements, so full-run records and telemetry are unchanged. `skill-bill phase` and `skill-bill code-review` both route through it; `RuntimeComponent.phaseRunEntry` replaces `parallelCodeReviewRunner`.
Reason: One loop and one strategy set means a phase run cannot drift from the full run. A second, review-only driver would duplicate the review, verify, and fix edges.
Alternatives considered: Run a phase over a throwaway durable workflow row (rejected: leaves rows and branches behind and needs cleanup). A dedicated review driver (rejected: duplicates the slot strategies).
Revisit when: a phase run needs resume, or phase-run lifecycle events are wanted (they wait for a telemetry contract-version bump, which would also carry a phase invocation id).

## [2026-09-27] Phase validation keeps pack-build's triage and repair cap (SKILL-380)

Context: `skill-bill phase validation` reports the same quality_check events as `bill-code-check`. The skill runs the pack gate and opens one repair session. The `pack-build` strategy triages each failing gate run and repairs for up to `MAX_REPAIR_TURNS` (3) turns.
Decision: The `VALIDATION` definition selects `pack-build` unchanged, so a phase run inherits its triage and repair-turn cap. quality_check_finished reports the last gate run's failure count and its distinct sorted rule or test ids. A phase run reports `scope_type` `working_tree`, since it validates the working tree rather than a branch diff.
Reason: One quality-gate strategy for full runs and phase runs. A phase-only single-repair variant would be a second strategy that drifts.
Alternatives considered: A phase-run strategy with one repair session, matching `bill-code-check` (rejected: duplicates `pack-build`). Report `branch_diff` like the skill (rejected: a phase run has no branch).
Revisit when: `bill-code-check` routes through `skill-bill phase validation`, so the two paths must agree on the repair budget.

## [2026-09-27] Census of the inline review lane shape for SKILL-383 (SKILL-380)

Context: Both review modes now run the whole `code_review` slot. Inline reviews through `InlineReviewStrategy`, so `ParallelCodeReviewRunner` no longer runs an inline lane for `skill-bill code-review`.
Decision: Record a grep census over production sources (`src/main`, `skills/`, `platform-packs/`) for SKILL-383. `ParallelCodeReviewRunner` has one production caller, `DelegatedReviewStrategy`, bound in `RuntimeFeatureTaskSlotProvides`, and it always passes `CodeReviewExecutionMode.DELEGATED`. The inline lane shape is still in runtime-application but no production request reaches it: `PARALLEL_REVIEW_INLINE_NATIVE_WORKER` (`ParallelCodeReviewRunner`, `ParallelCodeReviewRunnerLaneLaunch`, `ParallelCodeReviewInlineCoverageContinuation`), `PARALLEL_REVIEW_INLINE_DEPTH_DIRECTIVE` (`ParallelCodeReviewRunnerParentPrompt`), and `attributeInlineFindings` (`ParallelCodeReviewRunnerFailureAdmission`). `bill-code-review-inline` appears in `skills/bill-code-review-inline/` (its content and native agent) and in `skills/bill-code-review/content.md`.
Reason: SKILL-383 decides whether to delete the inline lane shape and the `bill-code-review-inline` agent. The census saves it from re-deriving the caller set.
Alternatives considered: Delete the inline lane shape in SKILL-380 (rejected: out of scope, and the listed skill stays installed).
Revisit when: SKILL-383 starts; re-run the census first, since callers may have changed.

## [2026-09-27] Phase plan, implement, and pr runs (SKILL-380)

Context: `skill-bill phase` ran only `review` and `validation`. Planning, implementing, and opening a pull request needed a durable feature-task workflow. `plan` needs an issue key to name a spec bundle, `implement` needs a governed spec, and the pull-request step assumed `commit_push` had run earlier in the same run.
Decision: Each `SkeletonDefinition` carries a `PhaseIntakeRequirement` (`optional`, `issue_key`, `spec`). One `PhaseRunIntakeResolver` turns any intake into the run's issue key and invariants by that requirement, and raises `PhaseIntakeRequiredError` or `PhaseSpecRequiredError` when the intake falls short. A run whose last slot is `plan` sets `specBundleRequired`. Its plan must return a decomposition package, which the planning stopper writes as a parent spec, subtask specs, and a manifest. A direct plan blocks. `PrDescriptionStrategy` refuses a detached, protected, or base branch, pushes a branch that has unpushed commits, and runs the readiness gate only when `commit_push` is one of the run's forward steps. The strategy reads the branch from `PhaseRunState` records. `InMemoryPhaseRunRecords` answers with the checkout's current branch, and durable records answer with the workflow's resolved branch.
Reason: Intake shape is definition data. A per-definition resolver or parser would fork as definitions are added. The readiness gate checks that `commit_push` settled the tree. Without `commit_push` in the run there is nothing for it to check, so a phase pr only needs a pushed branch. Reading the branch through the state port keeps one PR strategy for full runs and phase runs.
Alternatives considered: Parse the intake per definition in the CLI (rejected: the engine is the one caller that knows the requirement, and MCP would need the same logic). Let a direct plan complete without a spec (rejected: `skill-bill goal` cannot run it). Run the readiness gate unconditionally (rejected: it blocks every phase pr on a tree that no `commit_push` settled). Resolve the branch inside the PR strategy with git (rejected: the full run already records it, and two sources could disagree).
Revisit when: a definition needs an intake shape beyond an issue key or a spec, or a phase run must create its own branch.

## [2026-09-27] Runtime operations and the two-invocation confirmation gate (SKILL-382)

Context: `bill-release` and `bill-update-check` ran as prose skills. The release skill asked the operator one confirmation question inside a single agent turn, then tagged and pushed. A runtime command cannot hold an operator question open inside one process.
Decision: `Operation` (`skillbill.engine.operation.core`) runs pre, run, and post. Pre and post are in-process; run's agent steps go only through `OperationStepRunner`, which launches the generic `PhaseRunner` under an operation-local step name, settles through the final object, and fails a read-only step that changed the worktree. `OperationRegistry` is one explicit `@Provides` list in `RuntimeOperationProvides`. A duplicate id raises `DuplicateOperationIdError` and an unknown id raises `UnknownOperationIdError`. A `ConfirmableOperation` confirms in two invocations. The first stores the proposal step's prose in `operation_proposals` and exits `awaiting_confirmation` (exit code 4) with a `confirm:<token>` line. The second loads that stored proposal, refuses it if it is unknown, consumed, superseded, foreign, or its anchors moved (HEAD, branch, plus the operation's own anchors), consumes the token by compare-and-set, and only then executes. A new proposal for the same operation and repo root supersedes the prior open one. `operation:release` tags with `--cleanup=verbatim` so the stored changelog is the tag message byte for byte. No operation emits telemetry yet, so `update-check` keeps its default no-op `post`. Today's `update-check` CLI and MCP tool emit no event either, and an operation event needs a telemetry contract-version bump that carries `invocation_id`, the same wait as phase-run lifecycle events.
Reason: Agent-curated content, such as a changelog, cannot be recomputed identically, so confirm must execute the stored proposal instead of re-running the step. Consuming before executing means a crash after consume refuses a retry instead of tagging twice. Verify needs its own workflow family instead of this gate (SKILL-382 subtask 4), because it spans many agent steps and needs resume.
Alternatives considered: Recompute the proposal on confirm (rejected: a second changelog differs from the one the operator approved). Hold the process open for an interactive answer (rejected: agents relay one command at a time). Tag message `Release vX.Y.Z` as the skill did (rejected: the spec makes the stored changelog the tag message).
Revisit when: a proposal needs an expiry, an operation needs to run a skeleton definition as a step through `PhaseRunEntry`, or the telemetry contract gains an operation event keyed by `invocation_id`.

## [2026-09-27] Pre-consume admission and the review-thread port (SKILL-382 subtask 3)

Context: `pr-review-fix` confirms with a thread selection and can push. The gate consumed the token before `execute`, so a bad selection, the wrong checked-out branch, or `push:on` on a protected branch could only fail after the token was burned. The operation also needs GitHub review-thread state (resolved and outdated flags) that the flat PR comments list does not carry.
Decision: `ConfirmableOperation.admit(context, proposal)` is a gate hook with a default no-op. The gate runs the refusal checks, then `admit`, then the compare-and-set consume. An operation puts input and environment checks that should leave the token usable in `admit`. Review threads are read and replied to through `PullRequestReviewThreadOperations` in `skillbill.ports.review.pullrequest`, implemented by the GraphQL `GhPullRequestReviewThreads` adapter in runtime-infra/workflow `git/github`. The port does not go in `goalrunner.runner`, so that package's star-import launch ban still holds.
Reason: A refusal the operator can fix, such as a typo in `select:` or checking out the PR branch, should not force a fresh analysis. Consume-before-execute still holds for everything with side effects. Only GraphQL `reviewThreads` exposes `isResolved` and `isOutdated`.
Alternatives considered: Validate the selection inside `execute` (rejected: the token is already spent). Re-validate on the CLI side (rejected: the CLI has no proposal ordinals, and MCP would need the same logic). Read resolution state from REST comments (rejected: the fields are missing there).
Revisit when: an admission check needs a side effect, or thread listing needs to page comments past 100 per thread.

## [2026-09-28] operation:verify confirms on its own workflow row (SKILL-382 subtask 4)

Context: `bill-feature-verify` ran as a prose skill. It asked the operator to confirm the extracted criteria, reviewed, and then offered to fix issues or post a PR comment. Verify spans many agent steps and must resume, which is why the 2026-09-27 entry kept it out of the proposal gate.
Decision: `VerifyOperation` is a `SelfConfirmingOperation`. The executor hands `confirm:` to its `run` and the gate stores nothing. The first invocation opens a `bill-feature-verify` workflow, parks it at `extract_criteria`, and returns the workflow id as the token. No `operation_proposals` row is written. An adjusted re-invocation opens a new row and abandons the older parked rows for the same repo root, recording `superseded_by` in `session_notes`. Confirming a superseded row refuses and names the newer token. Verify is report-only: the verdict step has no fix or PR-comment offer, and no step edits.
Reason: The workflow row already holds the parked criteria and the resume point, so a second proposal store would be a second source for the same state. The skill's fix offer needs editing steps and a push policy that belong to `pr-review-fix`.
Alternatives considered: Store the criteria in `operation_proposals` and open the workflow on confirm (rejected: two stores, and the parked row could not resume). Keep the fix and PR-comment offer (rejected: it makes a read-only report an editing operation).
Revisit when: verify needs to post its report, or a second operation needs a self-owned workflow token.

## [2026-09-28] Remediation refusal preserves the original checkpoint record (SKILL-384)

Context: Remediation recovery removed an unsupported checkpoint artifact into quarantine and returned a coherent result. That result let execution proceed without an admitted semantic plan or evidence of a safe regeneration boundary.
Decision: Refuse unsupported checkpoint records without writing artifacts. Checkpoint append reads the existing history inside its write transaction and propagates version refusal without clearing that history. Refuse terminal workflows before reading recovery inputs or calling Git. Check remediation recovery before reopening stale settled review steps. Keep the original workflow, attempts, outputs, checkpoint records, and finalization evidence available for inspection with a compatible runtime or a separately reviewed semantic mapping.
Reason: An unreadable checkpoint cannot establish that irreversible work has not begun. Moving it out of the active artifact made the next read look like missing evidence and concealed that uncertainty.
Revisit when: Durable semantic admission supplies a recorded resume policy and proves a safe recovery boundary. These refusal checks do not implement that admission or authorize receipt regeneration.

## [2026-09-28] Execution-plan canonicalization and coherence, SKILL-384

Context: The execution-plan reader checked step coverage but allowed two strategies in one slot, entries owned by another strategy, reversed entry gates, and unreachable remediation cycles.
Decision: Check those relationships after schema validation. Canonicalize object keys, strategy declarations by slot and identity, per-step declarations by step, and the loop-only set by step. Preserve selected-step order and all ordered traversal arrays. Preserve absent, empty, and null values as distinct inputs.
Reason: Named collection order must not change descriptor equality. Traversal order can change execution, so sorting every array would hide a behavioral change.
Revisit when: The domain codec records the remaining effective policies. These checks do not establish semantic compatibility or authorize durable continuation.

## [2026-09-28] Worker takeover and terminal recovery fences, SKILL-384

Context: A takeover contender could terminate an exact live worker before reserving its lease. Crash repair could remove a lease after another transaction made the workflow terminal or reserved takeover.
Decision: Reserve takeover with the recorded token and generation before terminating the worker. Reservation and transfer require a nonterminal runtime workflow. Crash candidate discovery and repair require an active lease and a running workflow. A failed or interrupted takeover retains its reservation as evidence. Direct runner entry refuses terminal workflows before crash reconciliation, preparation, or reconstruction.
Reason: Process identity proves which process would be stopped. The database reservation proves which contender owns the takeover. A terminal transition or reserved takeover invalidates a prior crash candidate, so repair must recheck those facts before deleting its lease.
Revisit when: Durable semantic admission can share these transactions. The ownership fences and terminal refusal do not establish descriptor compatibility or a safe receipt regeneration boundary.

## [2026-09-28] Resolved-plan encoding and composition compatibility, SKILL-384

Context: The descriptor validator accepted maps, but execution had no codec for restoring an immutable resolved plan. The previous boundary test hand-built a descriptor and never reconstructed the plan.

Decision: Keep the codec and composition compatibility checks in the engine. The codec delegates wire validation to the validator port and restores domain data without runners. Policy digests cover the JSON string encoding of each recorded identity. Compatibility compares the recorded selections and policy references with supported implementations without substituting a newly selected plan. A supported composition alone cannot authorize durable execution.

Reason: Artifact shape, supported composition, and transactional admission have different owners. The reader must report unsupported versions and policy mismatches without leaking descriptor bodies or modifying their evidence.

Revisit when: Effective execution policies and durable creation, claim, and recovery callers use this boundary. Until then, codec round-trip tests and composition refusal tests do not prove durable admission or safe receipt regeneration.

## [2026-09-28] Quarantined producer invalidation retains irreversible evidence, SKILL-384

Context: The producer invalidation transaction could clear a completed output after commit or PR work had started. It also treated an absent producer record as permission to continue regeneration.
Decision: Refuse invalidation inside the transaction for terminal workflows, gate producers whose recorded semantics remain unproven, missing producer records, and any commit or PR phase record or ledger entry. Pending finalization records still count as evidence because their attempts have already started. The backward-edge caller waits for durable invalidation before changing its in-memory completion state and refuses a missing workflow. Recovery guidance directs operators to retain evidence and use a compatible runtime or a separately reviewed semantic mapping.
Reason: A missing receipt does not prove that an external side effect never happened. Keeping the check beside the destructive write also protects direct recorder callers.
Revisit when: Admission supplies a recorded plan and proves a safe boundary before checkpoints or other recovery work. These refusal checks do not grant semantic admission or permit gate receipt regeneration. QuarantinedProducerRecoveryRefusalTest covers retained SQLite rows, attempts, outputs, ledger entries, and checkpoints. This audit authored the test without executing it.

## [2026-09-28] Effective execution policy descriptors, SKILL-384

Context: Composition identity did not cover resolved gate commands or the shared rules that interpret receipts, budgets, checkpoints, and finalization.

Decision: Add immutable effective-policy descriptors with named identities, semantic revisions, and bounded input digests. Hash command argv after the existing wrapper resolver. Include command family, discovery or verification role, cache mode, findings settings, pack identity, validation depth, and timeout. Keep argv order and absent values. Compare the complete supported policy set separately from composition. Durable compatibility accepts only the definition's exact traversal until an explicit mapping has behavioral coverage.

Reason: An unchanged strategy id cannot prove that its command or recovery behavior is unchanged. Source hashes would also refuse harmless edits. Semantic revisions name supported behavior, while effective-input digests detect changed execution settings.

Revisit when: Creation and admission resolve these inputs and pass the accepted plan to execution. The codec and effective-policy comparison still have no durable callers. Their tests cover encoding and compatibility, not transaction rollback, lease fencing, or recovery authorization.

## [2026-09-28] Continuation claim admits the persisted descriptor, SKILL-384

Context: Continuation claim checked only the lookup timestamp. A descriptor could change after lookup without changing that timestamp, and a successful claim returned no execution plan.

Decision: Re-read the workflow, route identity, ownership, and descriptor inside the existing claim transaction. Compare the descriptor with supported composition and caller-supplied effective inputs before the status write. Return the recorded immutable plan only when the timestamp claim succeeds. Refuse changed ownership, reserved takeover, terminal status, and a definition that conflicts with the route scope. Record bounded semantic refusal diagnostics outside the rolled-back transaction and preserve the primary exception if diagnostics fail. The execution encoder also rejects traversal overrides that have no supported mapping.

Reason: Lookup is advisory. Claim must check the authoritative descriptor under the same transaction as its mutation, and its consumer needs the plan that passed that check. FeatureTaskContinuationAdmissionTest exercises SQLite preservation, distinct descriptor refusals, same-timestamp descriptor changes, stale ownership, cosmetic ordering, and diagnostic failure without launching phases.

Revisit when: Production launchers resolve effective inputs and consume the returned plan. Worker acquisition, creation, runner preparation, and recovery remain separate admission gaps. This change does not authorize those boundaries or gate receipt regeneration.

## [2026-10-01] Audit reports open criteria only and completes on one fixed line

Context: SKILL-387 accepted ordinary prose for the audit outcome and asked for a satisfied rationale on success. Every rationale line that began with a criterion ID then read as a remaining finding. SKILL-393 subtask 1 blocked on a fully satisfied audit whose rationale carried path bullets and a "the production part is satisfied" line.

Decision: The audit prompt asks for the single line `No production criteria remain.` on success and, otherwise, one line per open criterion starting with its ID. The parser counts only ID-leading lines and skips ones whose first sentence declares the criterion met. It ignores explanation lines and bullets, and completes on a sentence ending in the completion phrase. Narrative prose without ID-leading lines or the completion line is unusable. A prior audit that parsed as complete gives no shrink baseline, so the comparison advances instead of casting.

Reason: A fixed success line and an open-only list give the runtime a branch signal it can read without guessing at prose. Skipping satisfied notes and ignoring bullets keeps older-style reports from blocking a completed phase.

Revisit when: A model regularly lists open criteria without leading IDs, or the completion phrase appears beside open criteria in real reports.

## [2026-10-02] Plan works only from the preplan digest, delivered as fenced markdown

Context: On ENG-1251 (capmo-android) the plan session re-read 17 of the files preplan had just mapped and spent about 40 seconds copying the manifest format from older bundles. Preplan had handed three open questions forward without the evidence to settle them. Its 100-line digest reached plan as one line with 99 escaped `\n`.

Decision: Preplan is the feature's only discovery. It settles every question the repository can answer and carries the paths, symbols, hierarchies and test helpers that each subtask spec cites. Plan, bundle authoring and the goal fan-out planner treat the digest as their only repository knowledge (`PREPLAN_DIGEST_AUTHORITY`). They do not read, grep or re-verify, and they record an assumption when a fact is missing. The bundle directive carries a schema-checked manifest template. A multi-line projection text value is delivered inside a fence one backtick longer than its longest backtick run. Single-line values keep `name: value`.

Reason: Discovery done once is the point of preplan, and a plan that re-verifies pays for it twice, once per subtask in a fan-out. The fence keeps the digest readable while a heading inside it still cannot pass for a briefing section, which the escaping used to guarantee.

Revisit when: Plans regularly record assumptions that implement finds wrong, which would mean preplan digests are too thin rather than plan too strict.

## 2026-10-03: Resume revision-one audit workflows through a checked mapping

Context: Adding `audit_plan_fix` bumped acceptance-audit to revision 2. Existing
revision-one workflows refused admission even though their production edits and
planning evidence remained usable.

Decision: Map the exact previous composition at read time. Preserve the original
descriptor and every persisted phase, ledger entry, and checkpoint. Check the old
retry/resume digests and recompute only those affected by the new step. Keep full
admission checks for the other effective policies. Emit a mapping diagnostic.
Route resumed loop-only successors through an unfinished predecessor so repair
plans run before old blocked repairs continue. Do not delete child workflows or
reset their budgets to make them compatible. This keeps ownership in execution
admission and the run loop, as required by A1, A2, A7, and A10.

## 2026-10-03: Keep audit repair plans as prose

Context: A repair plan blocked because the completion hook parsed headings and
required four field labels. The operator requested prose planning without content
verification.

Decision: Delete the parser and completion hook. Keep planning instructions for
each gap's production changes and execution order. Persist the prose through the
ordinary phase output and deliver it to repair. Bump acceptance-audit to revision
3 and extend the checked mapping to the exact revision 1 and 2 compositions. Keep
the original descriptors and evidence. This removes the restrictive parser rather
than moving it, as required by A5. No architecture guard changes.
