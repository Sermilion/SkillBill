package skillbill.engine.featuretask.lifecycle.core

import skillbill.config.model.PhaseModelDirective
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeModelAssignment
import skillbill.workflow.taskruntime.model.skeleton.StepLaunchAssignment

public object FeatureTaskRuntimeResumeLaunchAssignment {
  public fun modelDirective(
    recorded: StepLaunchAssignment?,
    resolvedAgentId: String,
    phaseId: String,
    assignment: FeatureTaskRuntimeModelAssignment,
  ): PhaseModelDirective? {
    if (recorded != null) {
      val recordedModel = recorded.launch.effectiveModel ?: return null
      return PhaseModelDirective(recordedModel, recorded.launch.requestedEffort)
    }
    return FeatureTaskRuntimeModelResolver.resolve(phaseId, resolvedAgentId, assignment)
  }

  public fun usedAssignments(
    recorded: Map<String, StepLaunchAssignment>,
    current: Map<String, StepLaunchAssignment>,
  ): Map<String, StepLaunchAssignment> =
    buildMap {
      recorded.forEach { (stepId, assignment) ->
        put(stepId, assignment)
      }
      current.forEach { (stepId, assignment) -> if (stepId !in recorded) put(stepId, assignment) }
    }
}
