package skillbill.engine.featuretask.phaserun

import skillbill.engine.featuretask.lifecycle.continuation.GoalContinuationStateRecordRequest
import skillbill.engine.featuretask.lifecycle.continuation.GoalReviewPassCompletionRequest
import skillbill.engine.featuretask.lifecycle.remediation.RemediationDegradationSignal
import skillbill.engine.featuretask.lifecycle.subtask.SubtaskCommitPreservationRequest
import skillbill.engine.featuretask.model.phase.FeatureTaskPhaseSettlementEnvelope
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseLaunchBriefing
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimeProjectionRejection
import skillbill.engine.featuretask.model.review.GoalSubtaskReviewInputPreparation
import skillbill.engine.featuretask.model.review.GoalSubtaskReviewPassReservation
import skillbill.engine.featuretask.slot.state.PhaseLaunchRecords
import skillbill.engine.featuretask.slot.state.PhaseRunCheckpoints
import skillbill.engine.featuretask.slot.state.PhaseRunGoal
import skillbill.engine.featuretask.slot.state.PhaseRunSettlements
import skillbill.engine.featuretask.slot.state.RequiredPhaseWrite
import skillbill.error.featuretask.InMemoryPhaseRunUnsupportedError
import skillbill.error.featuretask.InvalidFeatureTaskRuntimeHandoffProjectionContext
import skillbill.goalrunner.model.history.GoalChildHistoryDraft
import skillbill.goalrunner.model.history.GoalChildHistoryDraftSaveResult
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.workflow.model.goalreview.GoalSubtaskReviewState
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.handoff.PhaseHandoffProjectionDeclaration
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeSharedEvidenceMeasurement
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeDeliveredProjectionRecord
import java.nio.file.Path

internal object InMemoryPhaseRunGoal : PhaseRunGoal {
  override fun saveGoalChildHistoryDraft(draft: GoalChildHistoryDraft): GoalChildHistoryDraftSaveResult =
    error("In-memory phases cannot persist goal-child history drafts.")

  override fun recordGoalContinuationState(request: GoalContinuationStateRecordRequest): Boolean = false

  override fun reserveGoalReviewPass(workflowId: String): GoalSubtaskReviewPassReservation =
    GoalSubtaskReviewPassReservation.MissingState

  override fun updateReviewState(
    workflowId: String,
    transform: (GoalSubtaskReviewState) -> GoalSubtaskReviewState,
  ): GoalSubtaskReviewState? = null

  override fun completeGoalReviewPass(request: GoalReviewPassCompletionRequest): GoalSubtaskReviewState? = null

  override fun buildGoalReviewInput(
    workflowId: String,
    gitOperations: WorkflowGitOperations,
    repoRoot: Path,
    scopedUntrackedExclusions: List<String>?,
    ownedPathspec: List<String>,
  ): GoalSubtaskReviewInputPreparation = GoalSubtaskReviewInputPreparation.MissingState

  override fun reviewState(workflowId: String): GoalSubtaskReviewState? = null

  override fun lastGoalReviewResult(workflowId: String): String? = null

  override fun appendRemediationRollbackDegradationEvidence(
    workflowId: String,
    signal: RemediationDegradationSignal,
  ) = Unit
}

internal object InMemoryPhaseRunSettlements : PhaseRunSettlements {
  override fun findEnvelope(
    workflowId: String,
    phaseId: String,
    attempt: Int,
  ): FeatureTaskPhaseSettlementEnvelope? = null

  override fun clear(
    workflowId: String,
    phaseId: String,
    attempt: Int,
  ): Boolean = false

  override fun persistAcceptedOutput(
    workflowId: String,
    phaseId: String,
    attempt: Int,
    envelope: FeatureTaskRuntimeWorkflowArtifactMap,
  ): Boolean = true
}

internal class GoalQualityPhaseRunSettlements(
  private val records: InMemoryPhaseRunRecords,
) : PhaseRunSettlements {
  override fun findEnvelope(
    workflowId: String,
    phaseId: String,
    attempt: Int,
  ): FeatureTaskPhaseSettlementEnvelope? =
    records.loadAcceptedEnvelope(phaseId, attempt)?.let(::FeatureTaskPhaseSettlementEnvelope)

  override fun clear(
    workflowId: String,
    phaseId: String,
    attempt: Int,
  ): Boolean = records.clearAcceptedEnvelope(phaseId, attempt)

  override fun persistAcceptedOutput(
    workflowId: String,
    phaseId: String,
    attempt: Int,
    envelope: FeatureTaskRuntimeWorkflowArtifactMap,
  ): Boolean = records.recordAcceptedEnvelope(phaseId, attempt, envelope)
}

internal object InMemoryPhaseRunCheckpoints : PhaseRunCheckpoints {
  override fun commitSubtask(request: SubtaskCommitPreservationRequest): WorkflowGitOperationResult =
    throw InMemoryPhaseRunUnsupportedError("write a subtask checkpoint commit")
}

internal class InMemoryPhaseLaunchRecords : PhaseLaunchRecords {
  override fun recordPhaseBriefing(
    workflowId: String,
    briefing: FeatureTaskRuntimePhaseLaunchBriefing,
    sharedEvidenceMeasurement: FeatureTaskRuntimeSharedEvidenceMeasurement?,
    attempt: Int,
  ): RequiredPhaseWrite = RequiredPhaseWrite.Acknowledged

  override fun recordProjectionRejection(
    context: InvalidFeatureTaskRuntimeHandoffProjectionContext,
    repositoryCheckpointFingerprint: String?,
  ): Boolean = true

  override fun recordProjectionRejection(rejection: FeatureTaskRuntimeProjectionRejection): Boolean = true

  override fun validateHandoffDeclarations(declarations: List<PhaseHandoffProjectionDeclaration>) = Unit

  override fun loadDeliveredProjections(workflowId: String): Map<String, FeatureTaskRuntimeDeliveredProjectionRecord> =
    emptyMap()
}
