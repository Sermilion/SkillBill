package skillbill.engine.goalrunner.persist

import skillbill.engine.goalrunner.model.GoalRunnerAttemptLedgerRecordRequest
import skillbill.engine.goalrunner.model.GoalRunnerLedgerSequenceWatermarks
import skillbill.engine.goalrunner.model.GoalRunnerProgressEventRecordRequest
import skillbill.engine.goalrunner.model.GoalRunnerReconcileGate
import skillbill.engine.goalrunner.model.GoalRunnerWorkflowProgress
import skillbill.goalrunner.model.GoalRunnerAttemptLedgerSummary
import skillbill.goalrunner.model.GoalRunnerObservabilityRecordRequest
import skillbill.goalrunner.model.GoalRunnerStoredOutcome
import skillbill.goalrunner.model.GoalRunnerSupervisionEvent
import skillbill.goalrunner.model.GoalRunnerWirePayload
import skillbill.goalrunner.model.GoalRunnerWorkerSubtaskRequestOutcome
import skillbill.ports.taskruntime.model.ValidatedFeatureTaskRuntimeExecutionPlan
import skillbill.workflow.model.FeatureTaskExecutionIdentity
import skillbill.workflow.model.goalobservability.GoalProgressEvent
import skillbill.workflow.model.goalreview.GoalSubtaskReviewPassResult
import skillbill.workflow.model.goalreview.GoalSubtaskReviewState
import java.nio.file.Path

interface GoalRunnerTerminalOutcomeStore {
  fun terminalOutcome(
    workflowId: String,
    issueKey: String,
    subtaskId: Int,
  ): GoalRunnerStoredOutcome?

  fun recoverAndPersistTerminalOutcome(
    workflowId: String,
    issueKey: String,
    subtaskId: Int,
    repoRoot: Path,
  ): GoalRunnerStoredOutcome?

  fun recoverMissingResultPrefixOutput(
    workflowId: String,
    issueKey: String,
    subtaskId: Int,
    output: GoalRunnerWirePayload,
  ): GoalRunnerStoredOutcome?
}

interface GoalRunnerReviewOutcomeStore {
  fun goalSubtaskReviewState(workflowId: String): GoalSubtaskReviewState?

  fun unemittedGoalReviewPasses(workflowId: String): List<GoalSubtaskReviewPassResult>

  fun acknowledgeGoalReviewPass(
    workflowId: String,
    passNumber: Int,
  ): Boolean
}

interface GoalRunnerWorkflowOutcomeStore :
  GoalRunnerTerminalOutcomeStore,
  GoalRunnerReviewOutcomeStore,
  GoalRunnerWorkflowProgressStore,
  GoalRunnerWorkflowLedgerWriteStore,
  GoalRunnerWorkflowOutcomeMutationStore

interface GoalRunnerAttemptLedgerStore {
  fun readAttemptLedgerSummary(issueKey: String): GoalRunnerAttemptLedgerSummary
}

interface GoalRunnerWorkflowOutcomeMutationStore {
  fun authoritativeOutcomes(issueKey: String): Map<Int, GoalRunnerStoredOutcome>

  fun reconcileAuthoritativeOutcomes(
    issueKey: String,
    activeWorkflowIds: Set<String> = emptySet(),
    gate: GoalRunnerReconcileGate = GoalRunnerReconcileGate(),
    repoRoot: Path? = null,
  ): Map<Int, GoalRunnerStoredOutcome>

  fun markBlocked(
    workflowId: String,
    blockedReason: String,
    lastResumableStep: String,
    supervisionEvent: GoalRunnerSupervisionEvent? = null,
  ): String?

  fun reopenBlockedPhaseForOperatorResume(
    workflowId: String,
    preferredPhaseId: String,
    reason: String,
    expectedIdentity: FeatureTaskExecutionIdentity,
    expectedExecutionPlan: ValidatedFeatureTaskRuntimeExecutionPlan,
  ): Boolean
}

interface GoalRunnerWorkflowProgressStore {
  fun progress(workflowId: String): GoalRunnerWorkflowProgress?

  fun recordObservabilityEvent(request: GoalRunnerObservabilityRecordRequest): Boolean

  fun recordProgressEvent(request: GoalRunnerProgressEventRecordRequest): Boolean

  fun progressEvents(workflowId: String): List<GoalProgressEvent>
}

interface GoalRunnerWorkflowLedgerWriteStore {
  fun recordAttemptLedgerEntry(request: GoalRunnerAttemptLedgerRecordRequest): Boolean

  fun recordWorkerSubtaskRequestOutcomes(
    workflowId: String,
    outcomes: List<GoalRunnerWorkerSubtaskRequestOutcome>,
  ): Boolean

  fun ledgerSequenceWatermarks(issueKey: String): GoalRunnerLedgerSequenceWatermarks

  fun childWorkflowLoopIterations(workflowId: String): Map<String, Int>
}
