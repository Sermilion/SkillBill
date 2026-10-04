package skillbill.workflow

import skillbill.error.featuretask.InvalidFeatureTaskRuntimeHandoffProjectionContext
import skillbill.ports.taskruntime.FeatureTaskRuntimeWireArtifactValidator
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWireArtifactKind
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap

object NoopGoalPlanningPreparationEnvelopeValidator : FeatureTaskRuntimeWireArtifactValidator {
  override fun handoffEnvelopeRejection(
    payload: FeatureTaskRuntimeWorkflowArtifactMap,
    sourceLabel: String,
  ): InvalidFeatureTaskRuntimeHandoffProjectionContext? = null

  override fun validate(
    kind: FeatureTaskRuntimeWireArtifactKind,
    payload: FeatureTaskRuntimeWorkflowArtifactMap,
    sourceLabel: String,
  ) = Unit

  override fun violation(
    kind: FeatureTaskRuntimeWireArtifactKind,
    payload: FeatureTaskRuntimeWorkflowArtifactMap,
    sourceLabel: String,
  ): String? = null
}
