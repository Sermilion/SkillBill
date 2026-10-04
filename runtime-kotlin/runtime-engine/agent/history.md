## [2026-10-03] SKILL-390 subtask 3: engine test packages mirror main
Areas: runtime-engine featuretask lifecycle, phaserun, prepare, review, runloop, runner, slot and persistence; goalrunner execution, planning, status, repair and persistence; operation tests and engine testFixtures; runtime-core architecture inventory, test composition and build configuration; runtime-cli test inputs
- Moved 91 engine test files into their production owners' packages. The tree retains 252 Kotlin files across 58 packages, with no forbidden declarations or path/package mismatches.
- Updated both PrincipleEnforcementInventory pins to the runner test-support path without changing exemption meaning.
- Repaired shared harness construction, imports and stale calls across existing engine tests and fixtures. Existing assertions remain; repeated recovery-case setup uses a helper.
- reusable: RuntimeQualityGateCycles owns pack-gate execution, agent validation and gate resolution with four private dependencies. Resolution uses admitted effective inputs when present; pack execution retains receipt validation.
- reusable: GateCapturedEvidence groups captured settlement facts, and GoalPlanningSpecAdmission groups launched-spec and sibling facts. Neither value carries services.
- Run-loop context takes request and run state; PhaseRunState retains strategy ownership. GoalRunnerStatusService uses eight private dependencies, with operation owners wired directly.
- Core registry tests use an internal test component and kspTest generation. CLI tests use statusProjectionPhaseStrategies for execution-plan access instead of exposed runner collaborators.
- Limits: no intended persisted-byte, CLI or MCP behavior change. Slotbaseline resource contents and lookup paths remain unchanged; no tests or orphan-package guard were added, and guards and baselines remain intact.
Feature flag: N/A
Acceptance criteria: 4/4 implemented

## [2026-10-03] SKILL-390 subtask 2: feature-task-run collaborators and engine inject guard
Areas: runtime-engine featuretask runner, lifecycle, phaserun, prepare, review, runloop and slot attempt/state; goalrunner planning and persistence; runtime-core architecture guards; engine test support

- Deleted the phase-gate locator, both gate-boundary bags and the probe-writer bag. State, attempt and run-loop consumers now name the collaborator they use.
- Runner execution, prepared execution, launch outcomes and durable launch use private injected owners. Durable run state no longer takes the runner; run state and session remain per-run.
- Deleted runner receiver functions, both attempt aliases, the host-returning attemptRunHost accessor and the run-loop binding forwarders. Named injected constructors keep their collaborators private.
- Goal outcome persistence takes one wire-artifact validator; its test fixture selects validation by artifact kind. Unread validation-runner wiring is gone.
- reusable: The existing inject-constructor property scanner now covers runtime-engine through PrincipleEnforcementInventory.RUNTIME_ENGINE_MAIN with an empty baseline.
- Existing runner, phase-run, persistence, gate and slot-baseline factories use the new constructors. No new module, architecture-test class, suppression or baseline row was added.
- Limits: test-package moves remain subtask 3. This refactor introduces no intended persisted-byte, CLI or MCP contract change.

Feature flag: N/A
Acceptance criteria: 8/8 implemented in current source; execution proof remains owned by build and validate.

## [2026-10-02] SKILL-390 subtask 1: goal-runner collaborators and engine repairs
Areas: runtime-engine goalrunner execution, launch, planning, status, reset, repair and recovery; featuretask branch policy and persistence; work status; runtime-domain workflow identity; runtime-core architecture guards; engine test factories

- Goal execution, launch preparation, finalization and status projection now keep direct private collaborators; iteration and selected-subtask owners receive the run's pending state through calls.
- Planning production, settlement and attempt control own their operations in existing packages. The execution and planning boundary bags and status data-source bags are deleted.
- reusable: `FeatureTaskExecutionIdentityPolicy.canonicalIssueKey` supplies non-validating canonicalization to engine callers; validated normalization delegates to the same function.
- Planning duration and the 200 ms tick-progress cache use the injected Clock. Rollback refreshes the cache; a new regression asserts 137 ms in empty-provider-turn rejection evidence.
- Recovery consumers call the recovery owner directly. Featuretask owns protected-branch policy and child-repair evidence vocabulary; reverse imports are removed and the engine cycle baseline is empty.
- Removed the inert producer-side visibility check and its fixtures; consumer-side inbound API pins and live run-loop guards remain.
- Limitation: AC-001's remaining featuretask boundary bags and the engine inject-property guard belong to subtask 2; test-package moves belong to subtask 3. This refactor introduces no intended wire or recovery-text change.
Feature flag: N/A
Acceptance criteria: 10/11 implemented; AC-001 remains partial until subtask 2 removes the featuretask bags.

## [2026-10-01] SKILL-392 subtask 2 — Engine owns the feature-task run entry
Areas: runtime-kotlin/runtime-engine/skillbill/engine/featuretask/{runner,lifecycle/execution,model/core}, runtime-kotlin/runtime-cli/{featuretask,goal/core,model}, runtime-kotlin/runtime-ports/system, runtime-kotlin/runtime-infra/host, runtime-kotlin/runtime-core/repoTest/architecture
- New engine `FeatureTaskRuntimeRunEntry` opens the workflow, resolves inputs, derives the execution identity and runs inside the worker coordinator's lease wrapper; the CLI now only builds a `FeatureTaskRuntimeRunInput` and calls it.
- Identity and governed-spec-path derivation live once in the execution entry as internal extensions on the repository-root port; admission reuses them.
- The CLI run-override test seam is retyped to take the run input, so tests capture the input instead of CLI-built types.
- `HostPlatformPort` gained `javaCommand`, so runtime-cli main no longer touches `ProcessHandle`. Handwritten port stubs must override it. reusable
- Engine inbound-API pins gained the entry and input types and lost 15 zero-reference pins; one runtime-cli test moved to runtime-core.
- Validate fixed subtask 1 leftovers: two scaffold files renamed to match their single declaration, an import-order fix, and a moved-file path in the install-policy guard.
- Limitation: a malformed issue key on the explicit-workflow path now fails at key normalization, and an invalid spec path reports before an unknown-workflow error. agnix was not run headless.
Feature flag: N/A
Acceptance criteria: implemented per spec; validate passed

## [2026-10-01] SKILL-393 subtask 1 — Engine-owned contracts move from ports to engine
Areas: runtime-kotlin/runtime-engine/skillbill/engine/{goalrunner/{manifest,persist,repair,model,planning},work/model}, runtime-kotlin/runtime-ports/{goalrunner,idestatus}, runtime-kotlin/runtime-core/{di/goal,repoTest/architecture}, runtime-kotlin/runtime-cli
- Goal-runner manifest, outcome and repair store interfaces, their request/result models and the child-planning hydrator port now live in the engine. Ports no longer holds `goalrunner/persistence`, the three runner store files or the reset-subtask snapshot.
- IDE-status request, result, candidate, selection tier and repository resolution moved to the engine `work/model`; the 19 persistence aliases and the duplicate pause-label constant are gone.
- The manifest-defaults and no-op ledger test fixtures moved to engine testFixtures; runtime-cli tests now depend on them.
- Guards: the engine inbound-API pinned list gained four moved types and lost two IDE-status entries; the `featuretask|work` cycle-baseline row was removed.
- Pattern: moves are byte-for-byte body copies, with imports rewritten across 128 files. reusable
- Limitation: the persist package must stay at the 12-file sibling ceiling; compile, kotlin-inject wiring and tests were not proven before validate.
Feature flag: N/A
Acceptance criteria: implemented per spec; build and validate pending

## [2026-09-27] SKILL-380 subtask 11 — pr-description and boundary-history own their rules
Areas: runtime-kotlin/runtime-engine/skillbill/engine/featuretask/{slot/{pullrequest,writehistory,attempt},lifecycle/core,phase/prompt/compose}, runtime-kotlin/runtime-core/di/{core,featuretask}, runtime-kotlin/runtime-ports/{goalrunner/runner,workflow/gitops}, runtime-kotlin/runtime-infra/workflow/git/{goal,standard}, docs, runtime-kotlin/ARCHITECTURE.md
- The pr and write_history prompts no longer tell the agent to invoke a skill. Their rules come from the runtime-owned `PrDescriptionPromptRules` and `BoundaryMemoryPromptRules`. `BoundaryMemoryRulesParityTest` holds the history write/skip rules equal to the skill's.
- `PullRequestTemplateSearch` (slot/pullrequest) resolves the PR template: the first single-file match wins, a single directory template is used, several with no default block the run as ambiguous and name each path, and no template falls back to the coded default. Checklists are stripped. reusable
- Template IO goes through the new `PullRequestTemplateFiles` port with the `FileSystemPullRequestTemplateFiles` adapter, because engine main bans java.nio.file. `repoRoot` joined `FeatureTaskRuntimePhasePromptComposeInputs`.
- A completed pr step emits `pr_description_generated` from both the skeleton run and `phase pr`, via `FeatureTaskRuntimeLifecycleTelemetry.prDescriptionGenerated`. It measures commit_count (new `WorkflowGitCommitHistoryOperations.commitCountAhead`), files_changed_count and pr_title (new `PullRequestIdentity.Found.title`). If any value can't be measured, the event is skipped with a warning. reusable
- Limitation: the slotbaseline pr and write_history prompt fixtures are re-baselined in validate. The standalone pr.txt fixture was already stale from earlier subtasks.
Feature flag: N/A
Acceptance criteria: 4/5 implemented (AC-4 fixture re-baseline runs in validate)

## [2026-09-27] SKILL-380 subtask 5 — Remaining slots and the no-phase-id guard
Areas: runtime-kotlin/runtime-engine/skillbill/engine/featuretask/{slot/{audit,pullrequest,writehistory,commitpush,runner,qualitygate,codereview,plan,preplan,implementation},phase,lifecycle,runloop}, runtime-kotlin/runtime-core/{di/featuretask,repoTest/architecture}, runtime-kotlin/runtime-{domain,infra/contracts,infra/sqlite,infra/workflow,mcp,ports}, orchestration/contracts
- Every slot's behaviour now lives in its strategy package: acceptance-audit owns gaps_found rejection, remaining-criteria retry, the retry prompt and the unchanged-remainder block; the phase/prompt audit-retry directives file is gone.
- `PullRequestReadinessGate` (slot/pullrequest) replaces the coordinator's `verifyPrEntryIdentity`; PR identity is looked up through the new `PullRequestIdentityLookup` port (gh adapter in runtime-infra/workflow). reusable
- write_history and pr settle with the uniform output; changed paths, history/decision changes and PR identity are runtime-measured into owned measured-fact keys, and no production code decodes `history_result` or `pr_result`.
- `DefaultPhaseRunner` prefers the MCP-settled envelope and otherwise reads the minimal final object (status, value, verdict, failure_disposition) from stdout for any step name.
- Pattern: runtime-owned turns are exempted from settlement by the strategy fact `settles = false`, not a step id (commit_push, build non-repair turns).
- Guard: the step-identity rule scans an explicit list of step-owned packages with no exempt list, in four forms (constant, literal, `stepIds` element, alias object); phase/ and lifecycle/ are at zero.
- Phase-output contract bumped to 0.6; one test pins every schema copy to the Kotlin constant.
- Limitation: the step-identity remainder in runloop, runner, review, validation and persist, plus the PHASE_AUDIT gate in PhaseLaunchPreparation and AuditRetry plumbing, wait for subtask 7 (needs the PhaseRunState port); per-file counts are in `census_subtask_5.md`.
Feature flag: N/A
Acceptance criteria: 11/11 implemented

## [2026-09-26] SKILL-380 subtask 1 — Pre-change behaviour fixtures
Areas: runtime-kotlin/runtime-engine/src/test/{kotlin/skillbill/engine/{featuretask/slotbaseline,goalrunner/planning/sweep},resources/featuretask/slotbaseline}
- Added a pre-refactor baseline for the phase-slot-strategy work: committed fixtures for the standalone run, goal-child build and validate runs, goal planning, parallel code review (INLINE and DELEGATED), and MCP lifecycle telemetry. The fixtures cover phase records, handoff projections, ledger entries, run invariants, workflow snapshots, and per-phase prompts.
- `SlotBaselineFixtureTest` compares a live capture against every committed fixture. `SlotBaselineCaptureTest` checks that two consecutive captures are byte-identical. It also holds the writer, which runs only when `SKILL_BILL_SLOTBASELINE_CAPTURE=1`. reusable
- To regenerate fixtures after an intended behaviour change, rerun the gated writer and review the diff. The README lists every normaliser token and the parent spec's fixture ledger.
- Pattern: each capture runs against a temp repo root and home, real SQLite, and the real validator. `SlotBaselineNormalizer` replaces paths, ids, and timestamps with fixed tokens so the output is deterministic.
- The goal-planning sweep test doubles (launcher, manifest store, invariants source, context discovery) moved to the shared `GoalPlanningSweepTestFixtures.kt`, which the capture harness reuses. Test behaviour is unchanged. reusable
- Limitation: runtime-engine tests can't reach the runtime-cli renderers, so goal-planning ships `planning-log.json` rather than rendered text, and the code-review outputs are result JSON, not CLI text.
- Limitation: the prompt sets follow what the live loop launches. None of the captured runs includes a commit_push or implement_fix prompt, and goal children have no pr prompt. No `src/main` file changed.
Feature flag: N/A
Acceptance criteria: 5/5 implemented

## [2026-09-26] SKILL-378 subtask 3 — Goal-runner sequences, reads, and silent reads
Areas: runtime-kotlin/runtime-engine/skillbill/engine/{goalrunner/{execution/core,findings,persist,planning/{recovery,remedies},repair,status},featuretask/{phase/record,persist,runner,lifecycle/continuation}}, runtime-kotlin/runtime-domain/skillbill/{goalrunner,workflow/model/goalreview}, runtime-kotlin/runtime-contracts/skillbill/error/shellcontent
- Observability sequence numbers are allocated only by the durable in-transaction issue-wide max+1; no engine class keeps a counter, a `var sequence`, or a per-workflow sequence map.
- `patchForEvent` now takes a `(Int) -> GoalObservabilityEvent` builder instead of a pre-built event, so the allocated number is the single source of `sequenceNumber` and no construction site can silently default it to 0. reusable
- New read-only `FeatureTaskRuntimePhaseQuery` (featuretask/phase/record) splits phase reads from the writing recorder; the four goal-runner readers (status projection assembler, stop reports, repair coordinator, child-aware refresh liveness) depend on the query, and only the rejection recorder still holds the writing facade. reusable
- Pattern: route best-effort seams through `GoalRunnerBestEffortEmission.runCancellable {}` + `rethrowIfCancellation` rather than hand-rolled `catch (e: Exception)` — it rethrows cancellation, restores the interrupt flag, and keeps warning wording consistent across seams (also what satisfies detekt `TooGenericExceptionCaught`).
- Four former silent reads (unaddressed-findings ledger schema, child refresh liveness, preplan prose read, rejection write failure) now fail typed or degrade to a recorded seam with a bounded warning, each covered by a malformed-input test.
- `InvalidUnaddressedFindingsLedgerSchemaError` gained an optional `cause`; `ShellContentContractException` already supported it, so no call sites changed.
- Limitation: the fifth silent read (`runCatching` over `goalObservabilityLatestEventFromArtifacts` in `WorkflowGoalRunnerProgressRecording.progress`) and the inject-property exposure of `GoalRunnerStatusProjectionDataSources` are left to the goal-runner follow-up.
- Limitation: `phaseQuery` is public, not `internal` — runtime-engine `testFixtures` is a separate compilation without friend access to main internals.
Feature flag: N/A
Acceptance criteria: 5/5 implemented

## [2026-09-25] SKILL-378 subtask 2 — Feature-task run-loop step classes
Areas: runtime-kotlin/runtime-engine/skillbill/engine/featuretask/runloop/{core,phase,output,settlement,checkpoint,state}, runtime-kotlin/runtime-core/repoTest/skillbill/architecture
- Broke the 20-node mutual-reference cycle across the feature-task run-loop step objects; the declaration graph now has 23 nodes and no strongly connected component larger than one.
- New step owners: `PhaseBlocking` (runloop/phase) holds block and phase-state-read primitives, `ValidationScope` (runloop/settlement) holds validation changed-paths and the single pack build command, `ReviewCompletion` (runloop/output) holds goal-review detection and review completion persisters.
- Added an architecture guard asserting run-loop step declarations reference each other acyclically, plus a synthetic two-node fixture proving the census names both members of a mutual pair.
- Guard reuses the existing `stronglyConnectedComponents` scan via `ArchitectureScanSupport.cyclicComponents` rather than a second scanner; `CommentStripper` moved private -> internal and gained a defaulted `blankStringLiterals` flag so the default path stays byte-identical. reusable
- Pattern: strip comments and string literals before building a declaration-reference graph — observability seam literals otherwise forge false edges and re-form the cycle.
- Legacy SQLite busy-reason text matching is now one named constant consumed at the two resume sites; it classifies persisted historical blocked reasons, not live failures (typed `DatabaseBusyError` remains the live disposition).
- Limitation: guard is host-classed in `RuntimeEnginePublicTopLevelDeclarationArchitectureTest`; the carrier class `FeatureTaskRuntimeRunLoopSession` stays in the node set as a zero-out-edge sink rather than being name-excluded.
Feature flag: N/A
Acceptance criteria: 7/7 implemented

## [2026-09-17] SKILL-352 subtask 3 — Record fallbacks, restore ownership, and shrink surface
Areas: runtime-kotlin/{runtime-engine,runtime-domain,runtime-ports,runtime-application,runtime-infra-sqlite,runtime-core}
- Goal-runner durable-read fallbacks now emit bounded diagnostics and project degraded status; lease timestamps and closed runtime vocabularies use typed domain values.
- Consolidated best-effort diagnostic emission, removed the engine JSON scanner, and moved engine-only declarations behind their producing boundary while narrowing public surface.
- Pattern: keep fallback recording, projection degradation, and ownership decisions at typed runtime seams. reusable
- Limitation: the full engine visibility census and test-class/prose-assertion split remain partial follow-up work.
Feature flag: N/A
Acceptance criteria: 6/8 implemented

## [2026-09-17] SKILL-352 subtask 2 — One persistence seam and typed durable failures
Areas: runtime-kotlin/{runtime-application,runtime-contracts,runtime-core,runtime-domain,runtime-engine,runtime-infra-fs,runtime-infra-sqlite}
- Consolidated feature-task workflow artifact reads and writes behind the runtime-engine persistence owner and removed recorder role-interface bundles.
- Routed goal-planning and review-policy artifact decoding through declared wire-key owners, typed durable errors, and non-null domain decoder overloads.
- Pattern: keep artifact encoding, decoding, and persistence patches at one typed runtime boundary. reusable
- Limitation: several shared-context integrity paths still use `require`/`error`; unsupported packet versions use the typed failure path.
Feature flag: N/A
Acceptance criteria: 7/7 implemented

## [2026-09-14] WE-4789 — Validation gate execution evidence
Areas: orchestration/contracts, runtime-kotlin/{runtime-contracts,runtime-domain,runtime-engine,runtime-infra-fs,runtime-infra-sqlite,runtime-cli,runtime-ports}
- Validation gate settlement now projects `executed_work_units`, `executed_checks`, and top-level `checks` from `ValidationGateRunResult` instead of discarding gate task identities at the coordinator seam.
- Gradle packs derive stable `module|task` identities from `> Task` stdout lines even when actionable-summary work units are zero; explicit empty `executed_checks` distinguishes truthful zero-work from legacy evidence loss.
- Goal status and workflow status read the same `validation_result` gate execution evidence as the settled validate artifact; issue 341 command/exit verdict rules are unchanged.
- Pattern: one typed `FeatureTaskRuntimeValidationGateExecutionEvidence` projection across runner, settlement, persistence, and status surfaces. reusable
Feature flag: N/A
Acceptance criteria: 8/8 implemented

## [2026-09-13] Issue 342 — Repair lease and pause clearance
Areas: runtime-kotlin/{runtime-engine/runtime-cli/runtime-ports}/goalrunner
- Added durable stale parent execution-lease, stale child worker-lease, and runner-interrupted pause wedge diagnosis and repair.
- Repair apply clears stale leases and pause residue only without a live unexpired owner, preserves operator_stop, and retains LIVE_LEASE_REFUSED safety semantics.
- CLI inspect and apply output names wedge classes and affected workflow ids without exposing storage tables.
- Pattern: keep parent and child wedge diagnosis/apply flows explicit and reuse the existing live-owner refusal boundary. reusable
- Regression coverage includes engine and CLI fixtures matching the issue 342 manual database workaround.
- Limitation: contract-version hard reset, review-base recovery, and existing child phase-output wedge behavior remain outside this change.
Feature flag: N/A
Acceptance criteria: 7/7 implemented

## [2026-09-13] Issue 341 — Validate evidence integrity
Areas: orchestration/contracts, runtime-kotlin/{runtime-domain,runtime-engine,runtime-infra-fs,runtime-infra-sqlite}
- Validation settlement now consumes schema-backed command and exit-code evidence, requires the required command to exit `0`, and keeps red or missing evidence incomplete across persistence and resume.
- The existing repair path receives remaining validation findings after a red result; repeated unchanged findings and exhausted repair budget remain durable blockers.
- Goal status projects valid command/exit evidence and reports an integrity problem when completed evidence is absent or invalid.
- Pattern: keep one minimal validation-evidence projection across contract, domain, settlement, persistence, and status seams; provider metadata remains opaque. reusable
- Limitation: platform validation routing and review, build, and planning evidence contracts are unchanged.
Feature flag: N/A
Acceptance criteria: 11/11 implemented

## [2026-09-11] SKILL-233 subtask 8 — Engine module and package roots
Areas: runtime-kotlin/{runtime-engine,runtime-application,runtime-infra-fs,runtime-infra-sqlite,runtime-contracts,runtime-core,runtime-cli,runtime-mcp,runtime-domain,runtime-ports,agent,ARCHITECTURE.md}
- Extracted feature-task, goal-runner, goal-planning, and planning-projection runtime behavior into the `runtime-engine` module under one `skillbill.engine` root, with a pinned inbound API and no reverse application dependency. reusable
- Moved infrastructure and application packages to their owning roots, using explicit schema-path constants at validator seams so resource lookup remains stable after package relocation. reusable
- Pattern: record module edges and package ownership in one catalog plus architecture tests, and keep empty per-module baselines as proof that new root violations are rejected rather than suppressed. reusable
- Limitation: the extraction and package moves preserve behavior and signatures; further engine subdivision remains outside this change.
Feature flag: N/A
Acceptance criteria: 12/12 implemented
