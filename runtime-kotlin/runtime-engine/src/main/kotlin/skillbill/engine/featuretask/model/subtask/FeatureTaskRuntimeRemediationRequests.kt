package skillbill.engine.featuretask.model.subtask

import skillbill.engine.featuretask.model.execution.AdmittedFeatureTaskRuntimeExecution
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.workflow.model.goalreview.GoalSubtaskReviewState
import skillbill.workflow.taskruntime.model.persistence.FeatureTaskRuntimeCheckpointIdentity
import skillbill.workflow.taskruntime.model.persistence.FeatureTaskRuntimeGoalContinuationArtifact
import java.nio.file.Path

internal data class RemediationReconciliationApplyRequest(
  public val reconciliation: RemediationReconciliationDecision,
  public val snapshot: RemediationReconcileSnapshot,
  public val workflowId: String,
  public val gitOperations: WorkflowGitOperations,
  public val repoRoot: Path,
  internal val latestRemediationResolved: ResolvedReviewFixCheckpoint?,
  public val admitted: AdmittedFeatureTaskRuntimeExecution? = null,
)

internal data class RemediationBaseHealRequest(
  public val target: String,
  public val stored: String?,
  internal val storedResolves: Boolean,
  public val state: GoalSubtaskReviewState,
  public val continuation: FeatureTaskRuntimeGoalContinuationArtifact,
  public val workflowId: String,
  public val gitOperations: WorkflowGitOperations,
  public val repoRoot: Path,
  internal val latestRemediationResolved: ResolvedReviewFixCheckpoint?,
  public val admitted: AdmittedFeatureTaskRuntimeExecution? = null,
)

internal data class PersistHealedRemediationBaseRequest(
  public val workflowId: String,
  public val target: String,
  public val stored: String?,
  public val reason: String,
  public val continuation: FeatureTaskRuntimeGoalContinuationArtifact,
  public val gitOperations: WorkflowGitOperations,
  public val repoRoot: Path,
  public val admitted: AdmittedFeatureTaskRuntimeExecution? = null,
)

internal data class RemediationReconcileSnapshot(
  public val state: GoalSubtaskReviewState,
  public val continuation: FeatureTaskRuntimeGoalContinuationArtifact,
  public val checkpoints: List<FeatureTaskRuntimeCheckpointIdentity>,
)

internal sealed interface RemediationReconciliationDecision

internal data object RemediationReconciliationCoherent : RemediationReconciliationDecision

internal data object RemediationReconciliationBlocked : RemediationReconciliationDecision

internal data class RemediationReconciliationHeal(val sha: String) : RemediationReconciliationDecision

internal data class ResolvedReviewFixCheckpoint(
  public val identity: FeatureTaskRuntimeCheckpointIdentity,
  public val sha: String,
)
