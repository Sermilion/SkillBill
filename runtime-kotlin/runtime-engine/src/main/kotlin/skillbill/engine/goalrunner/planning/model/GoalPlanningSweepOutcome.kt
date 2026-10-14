package skillbill.engine.goalrunner.planning.model

import skillbill.engine.goalrunner.model.GoalChildPlanningHydrationRequest
import skillbill.engine.goalrunner.model.GoalRunnerManifestState
import skillbill.goalrunner.model.GoalRunnerStopReason
import skillbill.ports.goalrunner.model.GoalPlanningContractProvenance
import skillbill.ports.goalrunner.model.GoalPlanningIdentity
import skillbill.ports.goalrunner.model.GovernedGoalSubtaskDescriptor

sealed interface GoalPlanningSweepOutcome {
  data class PreparedAll(
    val identity: GoalPlanningIdentity? = null,
    val provenance: GoalPlanningContractProvenance? = null,
    val descriptors: List<GovernedGoalSubtaskDescriptor> = emptyList(),
    val planWorkflowId: String? = null,
    val state: GoalRunnerManifestState? = null,
  ) : GoalPlanningSweepOutcome {
    fun hydrationFor(subtaskId: Int) =
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

  data class Stopped(
    val issueKey: String,
    val currentSubtaskId: Int,
    val reason: GoalRunnerStopReason,
    val blockedReason: String,
    val lastResumableStep: String,
  ) : GoalPlanningSweepOutcome
}
