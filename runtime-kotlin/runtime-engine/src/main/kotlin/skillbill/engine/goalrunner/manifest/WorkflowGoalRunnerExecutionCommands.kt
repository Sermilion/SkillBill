package skillbill.engine.goalrunner.manifest

import skillbill.engine.featuretask.lifecycle.execution.FeatureTaskRuntimeExecutionAdmission
import skillbill.engine.goalrunner.model.GoalRunnerChildExecutionPlanAdmission
import skillbill.engine.goalrunner.model.GoalRunnerManifestState
import skillbill.engine.goalrunner.model.GoalRunnerPausePersistenceResult
import skillbill.engine.goalrunner.status.GoalRunnerControlCoordinator
import skillbill.engine.goalrunner.status.requestPause
import skillbill.engine.goalrunner.status.requestPauseByIssueKey
import skillbill.engine.goalrunner.status.resume
import skillbill.goalrunner.model.GoalRunnerControlState
import skillbill.goalrunner.model.GoalRunnerExecutionLease
import java.nio.file.Path

internal class WorkflowGoalRunnerExecutionCommands(
  private val controls: GoalRunnerControlCoordinator,
  private val executionAdmission: FeatureTaskRuntimeExecutionAdmission,
) : GoalRunnerManifestExecutionCommands {
  override fun requestPause(parentWorkflowId: String): GoalRunnerControlState? =
    controls.requestPause(
      parentWorkflowId,
    )

  override fun pauseNow(
    parentWorkflowId: String,
    reason: String,
    pausedAt: String,
    overwriteExistingReason: Boolean,
  ): GoalRunnerControlState? = controls.pauseNow(parentWorkflowId, reason, pausedAt, overwriteExistingReason)

  override fun requestPauseByIssueKey(
    issueKey: String,
    repoRoot: Path?,
  ): GoalRunnerPausePersistenceResult? = controls.requestPauseByIssueKey(issueKey, repoRoot)

  override fun resume(parentWorkflowId: String): GoalRunnerManifestState? = controls.resume(parentWorkflowId)

  override fun pauseAtBoundary(state: GoalRunnerManifestState): GoalRunnerManifestState =
    controls.pauseAtBoundary(state)

  override fun acquireExecutionLease(
    parentWorkflowId: String,
    lease: GoalRunnerExecutionLease,
    expectedOwnerToken: String?,
  ): Boolean =
    controls.acquireExecutionLease(parentWorkflowId, lease, expectedOwnerToken, null) { unitOfWork ->
      requireCompatibleChildPlan(unitOfWork, parentWorkflowId, null, executionAdmission)
    }

  override fun acquireExecutionLeaseFenced(
    parentWorkflowId: String,
    lease: GoalRunnerExecutionLease,
    expectedOwner: GoalRunnerExecutionLease?,
  ): Boolean =
    controls.acquireExecutionLease(
      parentWorkflowId,
      lease,
      expectedOwner?.ownerToken,
      expectedOwner?.generation,
    ) { unitOfWork -> requireCompatibleChildPlan(unitOfWork, parentWorkflowId, null, executionAdmission) }

  override fun acquireExecutionLeaseWithChildAdmission(
    parentWorkflowId: String,
    lease: GoalRunnerExecutionLease,
    expectedOwnerToken: String?,
    childAdmission: GoalRunnerChildExecutionPlanAdmission,
  ): Boolean =
    controls.acquireExecutionLease(parentWorkflowId, lease, expectedOwnerToken, null) { unitOfWork ->
      requireCompatibleChildPlan(unitOfWork, parentWorkflowId, childAdmission, executionAdmission)
    }

  override fun acquireExecutionLeaseWithChildAdmissionFenced(
    parentWorkflowId: String,
    lease: GoalRunnerExecutionLease,
    expectedOwner: GoalRunnerExecutionLease?,
    childAdmission: GoalRunnerChildExecutionPlanAdmission,
  ): Boolean =
    controls.acquireExecutionLease(
      parentWorkflowId,
      lease,
      expectedOwner?.ownerToken,
      expectedOwner?.generation,
    ) { unitOfWork ->
      requireCompatibleChildPlan(unitOfWork, parentWorkflowId, childAdmission, executionAdmission)
    }

  override fun heartbeatExecutionLease(
    parentWorkflowId: String,
    lease: GoalRunnerExecutionLease,
  ): Boolean = controls.heartbeatExecutionLease(parentWorkflowId, lease)

  override fun releaseExecutionLease(
    parentWorkflowId: String,
    ownerToken: String,
    generation: Long,
  ): Boolean = controls.releaseExecutionLease(parentWorkflowId, ownerToken, generation)

  override fun releaseExecutionLeaseIfExpired(
    parentWorkflowId: String,
    ownerToken: String,
    generation: Long,
    nowInstant: String,
  ): Boolean =
    controls.releaseExecutionLeaseIfExpired(
      parentWorkflowId,
      ownerToken,
      generation,
      nowInstant,
    )
}
