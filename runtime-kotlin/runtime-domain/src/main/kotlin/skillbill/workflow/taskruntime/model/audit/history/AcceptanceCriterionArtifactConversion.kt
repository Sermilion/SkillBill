package skillbill.workflow.taskruntime.model.audit.history

import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap

sealed interface AcceptanceCriterionArtifactConversion {
  data class Current(val payload: FeatureTaskRuntimeWorkflowArtifactMap) : AcceptanceCriterionArtifactConversion

  data class Converted(
    val payload: FeatureTaskRuntimeWorkflowArtifactMap,
    val steps: List<AcceptanceCriterionMigrationStep>,
    val contexts: List<AcceptanceCriterionMigrationContext>,
  ) : AcceptanceCriterionArtifactConversion

  data class Unsupported(
    val family: DurableWorkflowArtifactFamily,
    val version: String?,
  ) : AcceptanceCriterionArtifactConversion
}
