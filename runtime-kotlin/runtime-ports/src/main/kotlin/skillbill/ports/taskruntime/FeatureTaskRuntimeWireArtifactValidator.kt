package skillbill.ports.taskruntime

import skillbill.error.featuretask.InvalidFeatureTaskRuntimeHandoffProjectionContext
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWireArtifactKind
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap

interface FeatureTaskRuntimeWireArtifactValidator {
  fun handoffEnvelopeRejection(
    payload: FeatureTaskRuntimeWorkflowArtifactMap,
    sourceLabel: String,
  ): InvalidFeatureTaskRuntimeHandoffProjectionContext?

  fun validate(
    kind: FeatureTaskRuntimeWireArtifactKind,
    payload: FeatureTaskRuntimeWorkflowArtifactMap,
    sourceLabel: String,
  )
}
