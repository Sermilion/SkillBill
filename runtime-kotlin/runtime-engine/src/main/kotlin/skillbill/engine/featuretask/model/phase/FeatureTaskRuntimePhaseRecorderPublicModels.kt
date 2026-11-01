package skillbill.engine.featuretask.model.phase

import skillbill.ports.diagnostics.model.ProducerOutputEvidence
import skillbill.workflow.model.goalreview.GoalSubtaskBlockerDisposition
import skillbill.workflow.model.goalreview.GoalSubtaskCommitFocusedAccounting
import skillbill.workflow.model.goalreview.GoalSubtaskReviewCompactFinding
import skillbill.workflow.model.validation.FeatureTaskRuntimeVerdict
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeDiagnosticFailureClass
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeProducerIteration
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeProjectionFailureClassification

public data class GoalReviewPhaseCompletionRequest(
  val phaseState: FeatureTaskRuntimePhaseStateRequest,
  val verdict: FeatureTaskRuntimeVerdict,
  val unresolvedFindingCount: Int,
  val findings: List<GoalSubtaskReviewCompactFinding>,
  val rawReviewResult: String,
  val blockerDispositions: List<GoalSubtaskBlockerDisposition> = emptyList(),
  val commitFocusedAccounting: GoalSubtaskCommitFocusedAccounting? = null,
)

public data class FeatureTaskRuntimeProjectionRejection(
  val workflowId: String,
  val consumerPhaseId: String,
  val projectionContractId: String,
  val producerIteration: FeatureTaskRuntimeProducerIteration,
  val repositoryCheckpointFingerprint: String?,
  val failureClassification: FeatureTaskRuntimeProjectionFailureClassification,
  val sourceLabel: String,
)

public sealed class FeatureTaskRuntimeProducerOutputRead {
  public data class Found(val evidence: ProducerOutputEvidence) : FeatureTaskRuntimeProducerOutputRead()

  public data object Absent : FeatureTaskRuntimeProducerOutputRead()

  public data class Unreadable(
    public val failureClass: FeatureTaskRuntimeDiagnosticFailureClass,
  ) : FeatureTaskRuntimeProducerOutputRead()
}

public data class ProducerOutputQueryArgs(
  public val workflowId: String,
  public val phaseId: String,
  public val attempt: Int,
  public val agentId: String,
  public val generation: Int,
)

public data class AppendCheckpointIdentityArgs(
  public val workflowId: String,
  public val issueKey: String,
  public val subtaskId: String,
  public val branch: String,
  public val phaseId: String,
  public val attemptCount: Int,
  public val loopId: String?,
  public val generation: Int,
  public val parentSha: String?,
  public val ownedPaths: List<String>,
  public val commitSha: String,
)
