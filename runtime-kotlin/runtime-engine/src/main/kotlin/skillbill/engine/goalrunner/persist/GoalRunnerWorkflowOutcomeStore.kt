package skillbill.engine.goalrunner.persist

import skillbill.engine.featuretask.model.execution.GoalChildHistoryDraftWriter
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
import skillbill.engine.goalrunner.model.GoalSubtaskUnknownScenarios
import skillbill.engine.goalrunner.persist.assessment.GoalAssessmentRecordRead
import skillbill.goalrunner.assessment.model.GoalAssessmentRecord
import skillbill.goalrunner.model.GoalRunnerAttemptLedgerSummary
import skillbill.goalrunner.model.GoalRunnerObservabilityRecordRequest
import skillbill.goalrunner.model.GoalRunnerStoredOutcome
import skillbill.goalrunner.model.GoalRunnerWirePayload
import skillbill.goalrunner.model.GoalRunnerWorkerSubtaskRequestOutcome
import skillbill.goalrunner.model.history.GoalChildHistoryDraft
import skillbill.goalrunner.model.history.GoalFinalRepairContext
import skillbill.goalrunner.model.history.GoalFinalRepairOutput
import skillbill.goalrunner.model.history.GoalHistoryCompletion
import skillbill.goalrunner.model.history.GoalHistoryWriterEntry
import skillbill.goalrunner.model.quality.GoalQualityExecutionInputs
import skillbill.goalrunner.model.quality.GoalQualityExecutionPlans
import skillbill.goalrunner.model.quality.GoalQualityExecutionRecord
import skillbill.goalrunner.model.quality.GoalQualityPhaseProgress
import skillbill.ports.featuretask.model.FeatureTaskQaOutcome
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.taskruntime.model.ValidatedFeatureTaskRuntimeExecutionPlan
import skillbill.telemetry.model.GoalSubtaskTraceProbeCounts
import skillbill.workflow.model.FeatureTaskExecutionIdentity
import skillbill.workflow.model.goalobservability.GoalProgressEvent
import skillbill.workflow.model.goalreview.GoalSubtaskReviewPassResult
import skillbill.workflow.model.goalreview.GoalSubtaskReviewState
import skillbill.workflow.taskruntime.model.skeleton.FeatureTaskRuntimeQaSelection
import skillbill.workflow.taskruntime.model.skeleton.FeatureTaskRuntimeQualityGateSelection
import java.nio.file.Path

public interface GoalRunnerTerminalOutcomeStore {
  public fun terminalOutcome(
    workflowId: String,
    issueKey: String,
    subtaskId: Int,
  ): GoalRunnerStoredOutcome?

  public fun terminalOutcome(
    unitOfWork: UnitOfWork,
    workflowId: String,
    issueKey: String,
    subtaskId: Int,
  ): GoalRunnerStoredOutcome? = terminalOutcome(workflowId, issueKey, subtaskId)

  public fun recoverAndPersistTerminalOutcome(
    workflowId: String,
    parentWorkflowId: String,
    issueKey: String,
    subtaskId: Int,
    repoRoot: Path,
  ): GoalRunnerStoredOutcome?

  public fun recoverMissingResultPrefixOutput(
    workflowId: String,
    parentWorkflowId: String,
    issueKey: String,
    subtaskId: Int,
    output: GoalRunnerWirePayload,
  ): GoalRunnerStoredOutcome?
}

public interface GoalRunnerReviewOutcomeStore {
  public fun goalSubtaskReviewState(workflowId: String): GoalSubtaskReviewState?

  public fun recordedQualityGateSelection(workflowId: String): FeatureTaskRuntimeQualityGateSelection?

  public fun recordedQaOutcome(workflowId: String): GoalSubtaskQaSummary = GoalSubtaskQaSummary.NotRecorded

  public fun recordedUnknownScenarios(workflowId: String): GoalSubtaskUnknownScenarios

  public fun recordedTraceProbeCounts(workflowId: String): GoalSubtaskTraceProbeCounts = GoalSubtaskTraceProbeCounts()

  /** The admitted plan's QA selection once the child has a stored plan, else [requested]; null keeps a pre-QA plan. */
  public fun childQaSelection(
    workflowId: String,
    requested: FeatureTaskRuntimeQaSelection?,
  ): FeatureTaskRuntimeQaSelection? = requested

  public fun unemittedGoalReviewPasses(workflowId: String): List<GoalSubtaskReviewPassResult>

  public fun acknowledgeGoalReviewPass(
    workflowId: String,
    passNumber: Int,
  ): Boolean
}

public interface GoalRunnerWorkflowOutcomeStore :
  GoalRunnerQualityExecutionStore,
  GoalRunnerTerminalOutcomeStore,
  GoalRunnerReviewOutcomeStore,
  GoalRunnerWorkflowProgressStore,
  GoalRunnerWorkflowLedgerWriteStore,
  GoalRunnerGoalChildHistoryDraftStore,
  GoalRunnerRestoredChildStore,
  GoalRunnerAssessmentRecordStore,
  GoalRunnerWorkflowOutcomeMutationStore {
  public fun hasQaComposedPlan(workflowId: String): Boolean

  public fun recordGoalFinalRepairOutput(
    parentWorkflowId: String,
    expectedInvocationId: String,
    output: GoalFinalRepairOutput,
  ): Boolean

  public fun recordGoalFinalRepairWarning(
    parentWorkflowId: String,
    expectedInvocationId: String,
    warning: String?,
  ): Boolean

  public fun goalFinalRepairContext(parentWorkflowId: String): GoalFinalRepairContext?

  public fun saveGoalHistoryCompletion(
    parentWorkflowId: String,
    expectedInvocationId: String,
    completion: GoalHistoryCompletion,
  ): Boolean

  public fun goalHistoryCompletion(parentWorkflowId: String): GoalHistoryCompletion?

  public fun goalHistoryWriterEntries(
    parentWorkflowId: String,
    expectedSelection: List<Pair<Int, String?>>,
  ): List<GoalHistoryWriterEntry>

  public fun recordedGoalQaOutcome(parentWorkflowId: String): FeatureTaskQaOutcome?

  public fun manifestReconciliationInputs(
    issueKey: String,
    parentWorkflowId: String,
  ): GoalRunnerManifestReconciliationInputs =
    GoalRunnerManifestReconciliationInputs(
      authoritativeOutcomes(issueKey, parentWorkflowId),
      restoredGoalChildReferences(parentWorkflowId),
    )
}

public interface GoalRunnerAssessmentRecordStore {
  public fun readGoalAssessmentRecord(parentWorkflowId: String): GoalAssessmentRecordRead

  public fun recordGoalAssessmentRecord(
    parentWorkflowId: String,
    record: GoalAssessmentRecord,
  ): Boolean
}

public interface GoalRunnerAttemptLedgerStore {
  public fun readAttemptLedgerSummary(issueKey: String): GoalRunnerAttemptLedgerSummary

  public fun readAttemptLedgerSummaryForSubtasks(
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

public interface GoalRunnerWorkflowOutcomeMutationStore {
  public fun authoritativeOutcomes(
    issueKey: String,
    parentWorkflowId: String,
  ): Map<Int, GoalRunnerStoredOutcome>

  public fun authoritativeOutcomesForSubtasks(
    issueKey: String,
    parentWorkflowId: String,
    subtaskIds: Set<Int>,
  ): Map<Int, GoalRunnerStoredOutcome> =
    authoritativeOutcomes(
      issueKey,
      parentWorkflowId,
    ).filterKeys(subtaskIds::contains)

  public fun authoritativeOutcomesForSubtasks(
    unitOfWork: UnitOfWork,
    issueKey: String,
    parentWorkflowId: String,
    subtaskIds: Set<Int>,
  ): Map<Int, GoalRunnerStoredOutcome> = authoritativeOutcomesForSubtasks(issueKey, parentWorkflowId, subtaskIds)

  public fun reconcileAuthoritativeOutcomes(
    issueKey: String,
    parentWorkflowId: String,
    activeWorkflowIds: Set<String> = emptySet(),
    gate: GoalRunnerReconcileGate = GoalRunnerReconcileGate(),
    repoRoot: Path? = null,
  ): Map<Int, GoalRunnerStoredOutcome>

  public fun markBlocked(request: GoalRunnerBlockRequest): GoalRunnerAcceptedBlock?

  public fun reopenBlockedPhaseForOperatorResume(
    workflowId: String,
    preferredPhaseId: String,
    reason: String,
    expectedIdentity: FeatureTaskExecutionIdentity,
    expectedExecutionPlan: ValidatedFeatureTaskRuntimeExecutionPlan,
  ): Boolean
}

public interface GoalRunnerRestoredChildStore {
  public fun restoredGoalChildReferences(parentWorkflowId: String): Map<Int, GoalRunnerRestoredChildReference>

  public fun restoredGoalChildWorkflowId(
    parentWorkflowId: String,
    subtaskId: Int,
  ): String?

  public fun restoredGoalChildWorkflowId(
    unitOfWork: UnitOfWork,
    parentWorkflowId: String,
    subtaskId: Int,
  ): String? = restoredGoalChildWorkflowId(parentWorkflowId, subtaskId)
}

public interface GoalRunnerGoalChildHistoryDraftStore : GoalChildHistoryDraftWriter {
  public fun goalChildHistoryDrafts(parentWorkflowId: String): List<GoalChildHistoryDraft>

  public fun hasRequiredGoalChildHistoryDraft(
    parentWorkflowId: String,
    subtaskId: Int,
    childWorkflowId: String,
  ): Boolean

  public fun hasRequiredGoalChildHistoryDraft(
    unitOfWork: UnitOfWork,
    parentWorkflowId: String,
    subtaskId: Int,
    childWorkflowId: String,
  ): Boolean = hasRequiredGoalChildHistoryDraft(parentWorkflowId, subtaskId, childWorkflowId)
}

public interface GoalRunnerWorkflowProgressStore {
  public fun progress(workflowId: String): GoalRunnerWorkflowProgress?

  public fun progress(
    unitOfWork: UnitOfWork,
    workflowId: String,
  ): GoalRunnerWorkflowProgress? = progress(workflowId)

  public fun recordObservabilityEvent(request: GoalRunnerObservabilityRecordRequest): Boolean

  public fun recordProgressEvent(request: GoalRunnerProgressEventRecordRequest): Boolean

  public fun progressEvents(workflowId: String): List<GoalProgressEvent>
}

public interface GoalRunnerWorkflowLedgerWriteStore {
  public fun recordAttemptLedgerEntry(request: GoalRunnerAttemptLedgerRecordRequest): Boolean

  public fun recordWorkerSubtaskRequestOutcomes(
    workflowId: String,
    outcomes: List<GoalRunnerWorkerSubtaskRequestOutcome>,
  ): Boolean

  public fun ledgerSequenceWatermarks(issueKey: String): GoalRunnerLedgerSequenceWatermarks

  public fun childWorkflowLoopIterations(workflowId: String): Map<String, Int>
}

public interface GoalRunnerQualityExecutionStore {
  public fun goalQualityExecution(parentWorkflowId: String): GoalQualityExecutionRecord?

  /**
   * Records the operator's choice to discard ambiguous historical quality state. The parent's
   * quality record becomes clean and unstarted, so the next goal run admits a fresh quality
   * execution with new cycles. Returns false when the parent holds no historical ambiguity.
   */
  public fun chooseFreshGoalQualityExecution(parentWorkflowId: String): Boolean

  public fun resolvedGoalQualityChildIds(
    parentWorkflowId: String,
    expectedSelection: List<Pair<Int, String?>>,
  ): List<String>

  public fun admitGoalQualityExecution(
    parentWorkflowId: String,
    baselineContentFingerprint: String,
    governingInputs: GoalQualityExecutionInputs,
    executionPlans: GoalQualityExecutionPlans,
  ): GoalQualityExecutionRecord

  public fun transitionGoalQualityExecution(
    parentWorkflowId: String,
    expectedInvocationId: String,
    expected: GoalQualityExecutionRecord,
    next: GoalQualityExecutionRecord,
  ): Boolean

  public fun goalQualityPhaseProgress(
    parentWorkflowId: String,
    invocationId: String,
    definitionId: String,
    acceptedPlan: String,
  ): GoalQualityPhaseProgress

  public fun persistGoalQualityPhaseProgress(
    parentWorkflowId: String,
    invocationId: String,
    definitionId: String,
    acceptedPlan: String,
    progress: GoalQualityPhaseProgress,
  ): Boolean
}
