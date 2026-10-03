## [2026-10-03] Require a tracker issue key for new goal intake

Context: Goal intake minted `LOCAL-<hash>` and defaulted the spec folder to `KEY-intake` when the operator supplied raw requirements or a key without a description.

Decision: Refuse new work that has no tracker issue key or link, and refuse a new spec folder that has no short description from the URL slug, remaining title, or existing bundle name. Resume of an existing spec or persisted key still works without inventing either value.

Reason: Spec identity is `KEY-short-description`. A hashed local key and an `intake` fallback hide the missing tracker reference instead of asking for it.

Alternatives considered: Keeping LOCAL minting for CLI-only raw text still creates untracked folders and a second identity scheme. Fetching the tracker title during prepare would hide a missing description on key-only CLI launches.

## [2026-10-03] Require requirements text with the tracker key for new goal intake

Context: A bare tracker URL with a slug started a new goal whose spec held only the link, and a bare key printed launch output before preparation refused it.

Decision: New work needs both a tracker key or link and requirements text after it; a URL slug names the folder but is not requirements. `GoalRunner.admitIntake` checks this before the CLI prints launch output, unless a persisted goal or existing spec matches the key. A gap returns `GoalIntakeAdmission.NeedsInput`, not an exception, because missing input is an expected outcome; the CLI phrases it as a request ("To start new work on APP-123, add the requirements…"), writes no state, and exits 1. The dispatcher asks the operator for the missing key or requirements.

Reason: The tracker reference names the work and the requirements define it. Refusing before launch output keeps a missing part a question for the operator rather than something that looks like a started run.

Alternatives considered: Accepting the slug as requirements leaves planning with nothing to plan from. Checking only inside `prepare` keeps the misleading launch banner.

## [2026-10-03] Canonicalize governed spec paths before planning-import admission

Context: A new goal child's implement admission refused `unsafe_import` with `source_version=unknown` even though planning had just written 0.7 payloads. Launch stores a repository-relative identity path; decomposition can still carry an absolute `spec_path` for the same file.

Decision: Treat those strings as the same governed spec by canonicalizing against `repository_identity` in `FeatureTaskExecutionIdentityPolicy`, and use that comparison in planning topology and child-import admission.

Reason: String equality treated a fresh same-runtime import as a foreign source. The diagnostic then had no phase-output version facts, so it logged `unknown`. Converting the bytes was never the failure.

Alternatives considered: Forcing planners to write only relative spec paths still leaves already-persisted absolute manifests, and child identity is required to stay repository-relative.

## [2026-10-03] Preserve slotbaseline resource paths during test relocation
Context: SKILL-390 subtask 3 moves slotbaseline capture tests into featuretask.runner while requiring resource and persisted-byte preservation.
Decision: Keep the featuretask/slotbaseline resource tree, module-root resolution and absolute audit-resource lookups unchanged while relocating capture tests and repairing their setup.
Reason: Existing resource lookups are independent of the Kotlin package. The relocation does not require moving goldens or regenerating captures, and existing byte-comparison coverage must retain its original evidence.

## [2026-10-03] Keep composition access in test source sets
Context: SKILL-390's private collaborator ownership leaves existing engine, CLI and core tests with stale construction and strategy-access calls.
Decision: Repair engine test factories, use the CLI's status-projection strategy seam, and give core registry tests an internal generated test component with kspTest wiring.
Reason: Restoring production collaborator accessors would undo the ownership repair. Existing behavioral and registry assertions cover these changes, so the plan calls for setup repairs without constructor-shape or setup-mirroring tests.

## [2026-10-03] Keep gate rewiring behind accepted-step bindings
Context: SKILL-390 subtask 2 removes the phase-gate locator across durable, in-memory and goal-planning execution while preserving strategy authority.
Decision: Give runtime consumers the specific typed collaborator they use, keep gate cycles and finalization with their existing owners, and retain accepted-step bindings for strategies.
Reason: Unpacking the locator must not give ordinary strategies Git writers or unrelated mutation authority. Renaming a bag or forwarding its host would preserve indirect access; replacing the run-loop framework would exceed this change's scope.
Alternatives considered: Replacement dependency factories, broad context conversions and per-run DI subcomponents would retain indirect ownership or add another execution framework.

## [2026-10-03] Extend the existing inject guard with an empty engine baseline
Context: The injected-constructor property guard covered application and CLI but missed exposed engine collaborators.
Decision: Add the engine scan beside the existing methods, use the inventory-owned main-source root and an empty baseline, and retain the scanner's rejection fixture.
Reason: The same scanner can reject engine property exposure without another architecture-test class or an exemption. Forwarding getters and receiver locators still require source review because this guard does not detect them.

## [2026-10-02] Private behavior owners with call-scoped pending state
Context: SKILL-390 found goalrunner dependency bags and receiver helpers that read exposed collaborators. Unpacking the per-run assembler alone would exceed the constructor limit.
Decision: Inject private behavior owners in existing packages, move finalization and projection operations into their classes, and pass one run-owned pending state through execution calls.
Reason: Each owner takes the dependencies its operations read. Moving dependencies into another bag would preserve the locator problem; storing pending state in injected owners would lose its per-run lifetime.
Alternatives considered: Unpacking all dependencies into the planning sweep or adding a replacement collaborator factory would retain oversized or indirect ownership.

## [2026-10-02] Canonicalize issue keys without adding validation
Context: Engine issue-key sites repeated trimming and uppercasing, while domain normalization also validates the original input.
Decision: Use `FeatureTaskExecutionIdentityPolicy.canonicalIssueKey` for non-validating derivation and have validated normalization delegate after its existing checks.
Reason: Replacing derivation with validated normalization would reject inputs that existing callers accepted and could change nullable behavior. The contracts-level normalizer has a different rule and remains separate.

## [2026-10-02] Inject timing and refresh tick progress after rollback
Context: Planning duration and tick-progress memoization read ambient monotonic time. SKILL-390 requires both to use the existing injected Clock.
Decision: Measure planning launch between two reads of that Clock and refresh the 200 ms tick cache when its time moves backwards.
Reason: A start read after launch or a different clock records zero for the planned 137 ms rejection-evidence regression. Rollback must not keep a cached result indefinitely; cached absence still follows the same memo interval.

## [2026-10-02] Featuretask owns branch policy and child-repair vocabulary
Context: Featuretask imported goalrunner for protected-branch policy and the persisted child-repair evidence key, creating the remaining engine package cycle.
Decision: Put both declarations in existing featuretask owner files, remove unused reverse imports, and empty the engine cycle baseline.
Reason: Goalrunner already depends on featuretask execution. Keeping shared execution policy in goalrunner preserves the reverse edge; moving ownership breaks it without changing branch matching or persisted artifact bytes.

## [2026-10-02] Remove the inert producer-side visibility census
Context: The engine visibility rule scanned only explicit public declarations, while its fixtures exercised default-public scanning that production never enabled.
Decision: Delete that rule, its two fixtures and its helper. Retain consumer-side inbound API pins and the carrier class's live run-loop guards.
Reason: The existing rule could not catch default-public declarations. Enabling that scan conflicts with generated runtime-core injection code that must name engine classes and constructor types; the consumer-side guard already enforces inbound imports.
Alternatives considered: Flipping the producer scan to include default-public declarations was rejected in the SKILL-390 investigation. This replaces SKILL-378 AC-11's producer-side conclusion.

## [2026-09-29] Admission precedes parent mutation and preserves recovery evidence, SKILL-384 subtask 2

Parent lease acquisition rechecks the current child link and its execution descriptor inside the lease transaction. A stale owner, changed child link, or incompatible descriptor leaves parent controls and child records unchanged. Descriptor admission compares the complete canonical plan. Ports carry validated canonical bytes; maps stay in serialization code.

Resume matches installed pack declarations against the recorded policy digest, even when the checkout is clean or its changed paths now route elsewhere. It rejects changed commands, wrappers, and execution settings without replacing the recorded plan.

Execution admission belongs to lifecycle code. The accepted plan and its effective inputs live under the feature-task models, and strategy dispatch consumes that plan. Recognizing a historical step does not authorize execution.

Runtime-generated build and validation receipts enter the producer-evidence store before completion. Regeneration requires that retained payload and a proven safe boundary. It preserves attempts, attribution, checkpoints, and finalization history. Process failures stop the current invocation; earlier invocations do not exhaust a later invocation's process-failure allowance.

Verification: 93 focused engine tests, 853 domain tests, 97 contract tests, 375 SQLite tests, and 272 architecture tests passed. The aggregate Kotlin compilation also passed. The repository-wide check still has test and lint failures outside this focused verification; it has not passed.

## [2026-09-25] Durable sequence allocation, loop-count caches, and F-006 silent reads (SKILL-378 subtask 3)

Context: Engine classes previously held their own sequence counters and several durable reads swallowed decode failures, so corrupt rows degraded silently.

Decision: (1) Progress, attempt-ledger, and observability sequences are allocated as `max + 1` inside the transaction that writes the entry. Progress and the attempt ledger take the issue-wide max in `WorkflowGoalRunnerProgressRecording.appendSequencedHistoryArtifact`; observability takes the per-workflow history max inside `GoalObservabilityArtifacts.patchForEvent`, which covers both the goal-runner writer and child progress-derived entries. `DEFAULT_GOAL_OBSERVABILITY_SEQUENCE_START` (10_000) and `DEFAULT_GOAL_EVENT_SEQUENCE_START` (20_000) are gone; existing histories continue from their max, and child observability entries now carry allocated numbers. That is the only stored-number change. (2) `GoalRunnerLedgerRecorder.cumulativeBackwardEdgeCounts` is a per-(subtask, loop) loop-count cache seeded from `ledgerSequenceWatermarks`, not a sequence allocator, and stays in memory. (3) Malformed durable review state (`UnaddressedFindingsLedgerService.repairLedgersByWorkflow`) and malformed shared-preplan payloads (`preplanProseValue`, `preplanProsePrompt`) now fail typed instead of vanishing; child execution liveness and the planning rejection-diagnostic write stay best-effort but record one bounded warning naming the seam, the expected value, and the used value. Cancellation rethrows everywhere; `InterruptedException` restores the interrupt flag first. (4) The fifth silent read, the `runCatching` over `goalObservabilityLatestEventFromArtifacts` in `WorkflowGoalRunnerProgressRecording.progress`, is left for the goal-runner follow-up. (5) Goal-runner reads resolve through the read-only `FeatureTaskRuntimePhaseQuery`; only writers keep the `FeatureTaskRuntimePhaseRecorder` facade.

Reason: One allocator per durable stream removes the duplicate-number class of bug that per-instance counters allow across concurrent recorders, and a typed failure beats a silently missing workflow in goal repair and CLI goal output.

Evidence: `GoalRunnerDurableSequenceAllocationTest`, `GoalObservabilityModelsTest`, `UnaddressedFindingsLedgerServiceTest`, `GoalPlanningRefreshLivenessTest`, `GoalPlanningPreplanProseReadTest`, `GoalPlanningRejectionRecorderTest`.

Revisit when: Cross-process writers stop relying on SQLite writer serialization, or the remaining best-effort emitters need a durable dead-letter instead of a dropped event.
