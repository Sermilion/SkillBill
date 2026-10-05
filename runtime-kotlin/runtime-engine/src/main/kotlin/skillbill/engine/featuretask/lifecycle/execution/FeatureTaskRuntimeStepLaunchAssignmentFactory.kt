package skillbill.engine.featuretask.lifecycle.execution

import skillbill.engine.featuretask.lifecycle.core.FeatureTaskRuntimeAgentResolver
import skillbill.engine.featuretask.lifecycle.core.FeatureTaskRuntimeModelResolver
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeAgentAssignment
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeModelAssignment
import skillbill.engine.featuretask.slot.PhaseStrategyLookup
import skillbill.engine.featuretask.slot.PhaseStrategySelectionFacts
import skillbill.ports.agentrun.AgentRunLauncher
import skillbill.ports.agentrun.model.AgentRunLaunchModelRequest
import skillbill.workflow.taskruntime.model.skeleton.LaunchEnvironmentKind
import skillbill.workflow.taskruntime.model.skeleton.PhaseSlot
import skillbill.workflow.taskruntime.model.skeleton.StepLaunchAssignment

data class StepLaunchAssignmentInputs(
  val invokedAgentId: String,
  val agentAssignment: FeatureTaskRuntimeAgentAssignment,
  val modelAssignment: FeatureTaskRuntimeModelAssignment,
  val environmentKind: LaunchEnvironmentKind = LaunchEnvironmentKind.INHERITED,
)

object FeatureTaskRuntimeStepLaunchAssignmentFactory {
  fun resolve(
    launcher: AgentRunLauncher,
    lookup: PhaseStrategyLookup,
    facts: PhaseStrategySelectionFacts,
    inputs: StepLaunchAssignmentInputs,
  ): Map<String, StepLaunchAssignment> {
    if (inputs.invokedAgentId.isBlank()) return emptyMap()
    val canonical = lookup.executionPlan(facts.copy(stepAssignments = emptyMap()))
    val assignments = linkedMapOf<String, StepLaunchAssignment>()
    canonical.selectedStepIds.forEach { stepId ->
      val strategy = lookup.strategyFor(stepId, canonical)
      if (strategy.slot == PhaseSlot.COMMIT_PUSH) return@forEach
      val agent = FeatureTaskRuntimeAgentResolver.resolve(stepId, inputs.agentAssignment, inputs.invokedAgentId)
      val requested = FeatureTaskRuntimeModelResolver.resolve(stepId, agent.resolvedAgentId, inputs.modelAssignment)
      val launch =
        launcher.resolveLaunchModel(
          AgentRunLaunchModelRequest(
            agentId = agent.resolvedAgentId,
            requestedModel = requested?.model,
            requestedEffort = requested?.effort,
            environmentKind = inputs.environmentKind,
          ),
        )
      assignments[stepId] = StepLaunchAssignment(stepId, agent.resolvedAgentId, launch)
    }
    return assignments
  }
}
