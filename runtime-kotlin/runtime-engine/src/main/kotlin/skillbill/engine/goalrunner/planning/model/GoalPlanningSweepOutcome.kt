package skillbill.engine.goalrunner.planning.model

import skillbill.engine.goalrunner.model.GoalChildPlanningHydrationRequest
import skillbill.engine.goalrunner.model.GoalRunnerManifestState
import skillbill.goalrunner.model.GoalRunnerStopReason
import skillbill.ports.goalrunner.model.GoalPlanningContractProvenance
import skillbill.ports.goalrunner.model.GoalPlanningIdentity
import skillbill.ports.goalrunner.model.GovernedGoalSubtaskDescriptor

public sealed interface GoalPlanningSweepOutcome {
  public data class PreparedAll(
    public val identity: GoalPlanningIdentity? = null,
    public val provenance: GoalPlanningContractProvenance? = null,
    public val descriptors: List<GovernedGoalSubtaskDescriptor> = emptyList(),
    public val planWorkflowId: String? = null,
    public val state: GoalRunnerManifestState? = null,
  ) : GoalPlanningSweepOutcome {
    internal fun hydrationFor(subtaskId: Int) =
      identity?.let { expectedIdentity ->
        val expectedProvenance = requireNotNull(provenance)
        val descriptor = descriptors.singleOrNull { it.subtaskId == subtaskId } ?: return@let null
        GoalChildPlanningHydrationRequest(
          expectedIdentity,
          expectedProvenance,
          descriptor,
          planWorkflowId,
        )
      }
  }

  public data class Stopped(
    public val issueKey: String,
    public val currentSubtaskId: Int,
    public val reason: GoalRunnerStopReason,
    public val blockedReason: String,
    public val lastResumableStep: String,
  ) : GoalPlanningSweepOutcome
}
