package skillbill.engine.goalrunner.manifest

import skillbill.engine.goalrunner.manifest
import skillbill.engine.goalrunner.model.GoalRunnerManifestState
import skillbill.goalrunner.model.GoalRunnerExecutionLease
import java.nio.file.Path

internal object TestNoopGoalPlanningManifestStore : GoalRunnerManifestStoreDefaults() {
  override fun loadByIssueKey(
    issueKey: String,
    repoRoot: Path?,
  ): GoalRunnerManifestState? = null

  override fun save(state: GoalRunnerManifestState): GoalRunnerManifestState = state

  override fun acquireExecutionLease(
    parentWorkflowId: String,
    lease: GoalRunnerExecutionLease,
    expectedOwnerToken: String?,
  ): Boolean = true

  override fun heartbeatExecutionLease(
    parentWorkflowId: String,
    lease: GoalRunnerExecutionLease,
  ): Boolean = true

  override fun releaseExecutionLease(
    parentWorkflowId: String,
    ownerToken: String,
    generation: Long,
  ): Boolean = true
}
