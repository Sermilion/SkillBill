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
