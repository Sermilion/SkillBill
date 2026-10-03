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
