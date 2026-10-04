package skillbill.ports.goalrunner

import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap

interface GoalPlanningPreparationSourceValidator {
  fun validateHistoricalPhaseOutput06(
    envelope: FeatureTaskRuntimeWorkflowArtifactMap,
    sourceLabel: String,
  )
}
