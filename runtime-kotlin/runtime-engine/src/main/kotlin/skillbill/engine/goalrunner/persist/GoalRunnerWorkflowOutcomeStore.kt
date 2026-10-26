package skillbill.engine.goalrunner.persist

import skillbill.engine.featuretask.model.execution.AdmittedFeatureTaskRuntimeExecution
import skillbill.engine.goalrunner.model.GoalRunnerAcceptedBlock
import skillbill.engine.goalrunner.model.GoalRunnerAttemptLedgerRecordRequest
import skillbill.engine.goalrunner.model.GoalRunnerBlockRequest
import skillbill.engine.goalrunner.model.GoalRunnerLedgerSequenceWatermarks
import skillbill.engine.goalrunner.model.GoalRunnerManifestReconciliationInputs
import skillbill.engine.goalrunner.model.GoalRunnerProgressEventRecordRequest
import skillbill.engine.goalrunner.model.GoalRunnerReconcileGate
import skillbill.engine.goalrunner.model.GoalRunnerRestoredChildReference
import skillbill.engine.goalrunner.model.GoalRunnerWorkflowProgress
import skillbill.engine.goalrunner.model.GoalSubtaskQaSummary
import skillbill.goalrunner.model.GoalChildHistoryDraft
import skillbill.goalrunner.model.GoalChildHistoryDraftSaveResult
import skillbill.goalrunner.model.GoalRunnerAttemptLedgerSummary
import skillbill.goalrunner.model.GoalRunnerObservabilityRecordRequest
import skillbill.goalrunner.model.GoalRunnerStoredOutcome
import skillbill.goalrunner.model.GoalRunnerWirePayload
import skillbill.goalrunner.model.GoalRunnerWorkerSubtaskRequestOutcome
import skillbill.ports.featuretask.model.FeatureTaskQaOutcome
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.taskruntime.model.ValidatedFeatureTaskRuntimeExecutionPlan
import skillbill.workflow.model.FeatureTaskExecutionIdentity
import skillbill.workflow.model.goalobservability.GoalProgressEvent
import skillbill.workflow.model.goalreview.GoalSubtaskReviewPassResult
import skillbill.workflow.model.goalreview.GoalSubtaskReviewState
import skillbill.workflow.taskruntime.model.skeleton.FeatureTaskRuntimeQaSelection
import skillbill.workflow.taskruntime.model.skeleton.FeatureTaskRuntimeQualityGateSelection
import java.nio.file.Path

interface GoalRunnerTerminalOutcomeStore {
  fun terminalOutcome(
    workflowId: String,
    issueKey: String,
    subtaskId: Int,
  ): GoalRunnerStoredOutcome?

  fun terminalOutcome(
    unitOfWork: UnitOfWork,
    workflowId: String,
    issueKey: String,
    subtaskId: Int,
  ): GoalRunnerStoredOutcome? = terminalOutcome(workflowId, issueKey, subtaskId)

  fun recoverAndPersistTerminalOutcome(
    workflowId: String,
    parentWorkflowId: String,
    issueKey: String,
    subtaskId: Int,
    repoRoot: Path,
  ): GoalRunnerStoredOutcome?

  fun recoverMissingResultPrefixOutput(
    workflowId: String,
    parentWorkflowId: String,
    issueKey: String,
    subtaskId: Int,
    output: GoalRunnerWirePayload,
  ): GoalRunnerStoredOutcome?
}

interface GoalRunnerReviewOutcomeStore {
  fun goalSubtaskReviewState(workflowId: String): GoalSubtaskReviewState?

  fun recordedQualityGateSelection(workflowId: String): FeatureTaskRuntimeQualityGateSelection?

  fun recordedQaOutcome(workflowId: String): GoalSubtaskQaSummary = GoalSubtaskQaSummary.NotRecorded

  /** The admitted plan's QA selection once the child has a stored plan,
   else [requested]; null keeps a pre-QA plan. */
  fun childQaSelection(
    workflowId: String,
    requested: FeatureTaskRuntimeQaSelection?,
  ): FeatureTaskRuntimeQaSelection? = requested

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
  GoalRunnerGoalChildHistoryDraftStore,
  GoalRunnerWorkflowOutcomeMutationStore {
  fun hasQaComposedPlan(workflowId: String): Boolean

  fun recordedGoalQaOutcome(parentWorkflowId: String): FeatureTaskQaOutcome?

  fun manifestReconciliationInputs(
    issueKey: String,
    parentWorkflowId: String,
  ): GoalRunnerManifestReconciliationInputs =
    GoalRunnerManifestReconciliationInputs(
      authoritativeOutcomes(issueKey, parentWorkflowId),
      restoredGoalChildReferences(parentWorkflowId),
    )
}

interface GoalRunnerAttemptLedgerStore {
  fun readAttemptLedgerSummary(issueKey: String): GoalRunnerAttemptLedgerSummary

  fun readAttemptLedgerSummaryForSubtasks(
    issueKey: String,
    subtaskIds: Set<Int>,
  ): GoalRunnerAttemptLedgerSummary =
    readAttemptLedgerSummary(issueKey).let { summary ->
      summary.copy(
        cumulativeFixIterations =
          summary.cumulativeFixIterations.filterKeys {
              key ->
            key.substringBefore(':').toIntOrNull() in subtaskIds
          },
      )
    }
}

interface GoalRunnerWorkflowOutcomeMutationStore {
  fun authoritativeOutcomes(
    issueKey: String,
    parentWorkflowId: String,
  ): Map<Int, GoalRunnerStoredOutcome>

  fun authoritativeOutcomesForSubtasks(
    issueKey: String,
    parentWorkflowId: String,
    subtaskIds: Set<Int>,
  ): Map<Int, GoalRunnerStoredOutcome> =
    authoritativeOutcomes(
      issueKey,
      parentWorkflowId,
    ).filterKeys(subtaskIds::contains)

  fun authoritativeOutcomesForSubtasks(
    unitOfWork: UnitOfWork,
    issueKey: String,
    parentWorkflowId: String,
    subtaskIds: Set<Int>,
  ): Map<Int, GoalRunnerStoredOutcome> = authoritativeOutcomesForSubtasks(issueKey, parentWorkflowId, subtaskIds)

  fun reconcileAuthoritativeOutcomes(
    issueKey: String,
    parentWorkflowId: String,
    activeWorkflowIds: Set<String> = emptySet(),
    gate: GoalRunnerReconcileGate = GoalRunnerReconcileGate(),
    repoRoot: Path? = null,
  ): Map<Int, GoalRunnerStoredOutcome>

  fun markBlocked(request: GoalRunnerBlockRequest): GoalRunnerAcceptedBlock?

  fun reopenBlockedPhaseForOperatorResume(
    workflowId: String,
    preferredPhaseId: String,
    reason: String,
    expectedIdentity: FeatureTaskExecutionIdentity,
    expectedExecutionPlan: ValidatedFeatureTaskRuntimeExecutionPlan,
  ): Boolean
}

interface GoalRunnerGoalChildHistoryDraftStore {
  fun restoredGoalChildReferences(parentWorkflowId: String): Map<Int, GoalRunnerRestoredChildReference> = emptyMap()

  fun restoredGoalChildWorkflowId(
    parentWorkflowId: String,
    subtaskId: Int,
  ): String? = null

  fun restoredGoalChildWorkflowId(
    unitOfWork: UnitOfWork,
    parentWorkflowId: String,
    subtaskId: Int,
  ): String? = restoredGoalChildWorkflowId(parentWorkflowId, subtaskId)

  fun saveGoalChildHistoryDraft(
    draft: GoalChildHistoryDraft,
    admitted: AdmittedFeatureTaskRuntimeExecution? = null,
  ): GoalChildHistoryDraftSaveResult

  fun goalChildHistoryDrafts(parentWorkflowId: String): List<GoalChildHistoryDraft>

  fun hasRequiredGoalChildHistoryDraft(
    parentWorkflowId: String,
    subtaskId: Int,
    childWorkflowId: String,
  ): Boolean

  fun hasRequiredGoalChildHistoryDraft(
    unitOfWork: UnitOfWork,
    parentWorkflowId: String,
    subtaskId: Int,
    childWorkflowId: String,
  ): Boolean = hasRequiredGoalChildHistoryDraft(parentWorkflowId, subtaskId, childWorkflowId)
}

interface GoalRunnerWorkflowProgressStore {
  fun progress(workflowId: String): GoalRunnerWorkflowProgress?

  fun progress(
    unitOfWork: UnitOfWork,
    workflowId: String,
  ): GoalRunnerWorkflowProgress? = progress(workflowId)

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
