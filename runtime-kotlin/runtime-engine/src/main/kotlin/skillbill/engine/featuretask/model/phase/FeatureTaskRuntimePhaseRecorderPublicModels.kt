package skillbill.engine.featuretask.model.phase

import skillbill.ports.diagnostics.model.ProducerOutputEvidence
import skillbill.workflow.model.goalreview.GoalSubtaskBlockerDisposition
import skillbill.workflow.model.goalreview.GoalSubtaskCommitFocusedAccounting
import skillbill.workflow.model.goalreview.GoalSubtaskReviewCompactFinding
import skillbill.workflow.model.validation.FeatureTaskRuntimeVerdict
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeDiagnosticFailureClass
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeProducerIteration
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeProjectionFailureClassification

data class GoalReviewPhaseCompletionRequest(
  val phaseState: FeatureTaskRuntimePhaseStateRequest,
  val verdict: FeatureTaskRuntimeVerdict,
  val unresolvedFindingCount: Int,
  val findings: List<GoalSubtaskReviewCompactFinding>,
  val rawReviewResult: String,
  val blockerDispositions: List<GoalSubtaskBlockerDisposition> = emptyList(),
  val commitFocusedAccounting: GoalSubtaskCommitFocusedAccounting? = null,
)

data class FeatureTaskRuntimeProjectionRejection(
  val workflowId: String,
  val consumerPhaseId: String,
  val projectionContractId: String,
  val producerIteration: FeatureTaskRuntimeProducerIteration,
  val repositoryCheckpointFingerprint: String?,
  val failureClassification: FeatureTaskRuntimeProjectionFailureClassification,
  val sourceLabel: String,
)

sealed class FeatureTaskRuntimeProducerOutputRead {
  data class Found(val evidence: ProducerOutputEvidence) : FeatureTaskRuntimeProducerOutputRead()

  data object Absent : FeatureTaskRuntimeProducerOutputRead()

  data class Unreadable(
    val failureClass: FeatureTaskRuntimeDiagnosticFailureClass,
  ) : FeatureTaskRuntimeProducerOutputRead()
}

data class ProducerOutputQueryArgs(
  val workflowId: String,
  val phaseId: String,
  val attempt: Int,
  val agentId: String,
  val generation: Int,
)

data class AppendCheckpointIdentityArgs(
  val workflowId: String,
  val issueKey: String,
  val subtaskId: String,
  val branch: String,
  val phaseId: String,
  val attemptCount: Int,
  val loopId: String?,
  val generation: Int,
  val parentSha: String?,
  val ownedPaths: List<String>,
  val commitSha: String,
)
