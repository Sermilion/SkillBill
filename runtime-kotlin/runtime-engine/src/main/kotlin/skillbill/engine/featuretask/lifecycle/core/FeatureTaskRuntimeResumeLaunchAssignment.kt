package skillbill.engine.featuretask.lifecycle.core

import skillbill.config.model.PhaseModelDirective
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeModelAssignment
import skillbill.workflow.taskruntime.model.skeleton.StepLaunchAssignment

internal object FeatureTaskRuntimeResumeLaunchAssignment {
  fun recordedForAgent(
    recorded: StepLaunchAssignment?,
    resolvedAgentId: String,
  ): StepLaunchAssignment? = recorded?.takeIf { it.agentId == resolvedAgentId }

  fun modelDirective(
    recorded: StepLaunchAssignment?,
    resolvedAgentId: String,
    phaseId: String,
    assignment: FeatureTaskRuntimeModelAssignment,
  ): PhaseModelDirective? {
    val matching = recordedForAgent(recorded, resolvedAgentId)
    val recordedModel = matching?.launch?.effectiveModel
    if (recordedModel != null) return PhaseModelDirective(recordedModel, matching.launch.requestedEffort)
    if (matching != null) return null
    return FeatureTaskRuntimeModelResolver.resolve(phaseId, resolvedAgentId, assignment)
  }

  fun usedAssignments(
    recorded: Map<String, StepLaunchAssignment>,
    current: Map<String, StepLaunchAssignment>,
    resolvedAgentIdForStep: (String) -> String,
  ): Map<String, StepLaunchAssignment> =
    buildMap {
      recorded.forEach { (stepId, assignment) ->
        val matching = recordedForAgent(assignment, resolvedAgentIdForStep(stepId))
        val chosen = matching ?: current[stepId]
        if (chosen != null) put(stepId, chosen)
      }
    }
}
