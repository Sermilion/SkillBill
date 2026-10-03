package skillbill.infrastructure.contracts.workflow.goal

import me.tatarka.inject.annotations.Inject
import skillbill.ports.goalrunner.GoalPlanningPreparationSourceValidator
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap

@Inject
class ContractGoalPlanningPreparationSourceValidator : GoalPlanningPreparationSourceValidator {
  override fun validateHistoricalPhaseOutput06(
    envelope: FeatureTaskRuntimeWorkflowArtifactMap,
    sourceLabel: String,
  ) {
    GoalPlanningPreparationSchemaValidator.validateHistoricalPhaseOutput06(envelope, sourceLabel)
  }
}
