package skillbill.engine.featuretask.model.subtask

import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.workflow.model.goalreview.GoalSubtaskReviewState
import skillbill.workflow.taskruntime.model.persistence.FeatureTaskRuntimeCheckpointIdentity
import skillbill.workflow.taskruntime.model.persistence.FeatureTaskRuntimeGoalContinuationArtifact
import java.nio.file.Path

data class RemediationReconciliationApplyRequest(
  val reconciliation: RemediationReconciliationDecision,
  val snapshot: RemediationReconcileSnapshot,
  val workflowId: String,
  val gitOperations: WorkflowGitOperations,
  val repoRoot: Path,
  val latestRemediationResolved: ResolvedReviewFixCheckpoint?,
)

data class RemediationBaseHealRequest(
  val target: String,
  val stored: String?,
  val storedResolves: Boolean,
  val state: GoalSubtaskReviewState,
  val continuation: FeatureTaskRuntimeGoalContinuationArtifact,
  val workflowId: String,
  val gitOperations: WorkflowGitOperations,
  val repoRoot: Path,
  val latestRemediationResolved: ResolvedReviewFixCheckpoint?,
)

data class PersistHealedRemediationBaseRequest(
  val workflowId: String,
  val target: String,
  val stored: String?,
  val reason: String,
  val continuation: FeatureTaskRuntimeGoalContinuationArtifact,
  val gitOperations: WorkflowGitOperations,
  val repoRoot: Path,
)

data class RemediationReconcileSnapshot(
  val state: GoalSubtaskReviewState,
  val continuation: FeatureTaskRuntimeGoalContinuationArtifact,
  val checkpoints: List<FeatureTaskRuntimeCheckpointIdentity>,
)

sealed interface RemediationReconciliationDecision

data object RemediationReconciliationCoherent : RemediationReconciliationDecision

data object RemediationReconciliationBlocked : RemediationReconciliationDecision

data class RemediationReconciliationHeal(val sha: String) : RemediationReconciliationDecision

data class ResolvedReviewFixCheckpoint(
  val identity: FeatureTaskRuntimeCheckpointIdentity,
  val sha: String,
)
