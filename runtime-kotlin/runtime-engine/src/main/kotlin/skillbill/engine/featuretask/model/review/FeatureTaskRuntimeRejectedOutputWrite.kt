package skillbill.engine.featuretask.model.review

import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeDiagnosticFailureClass

internal sealed class FeatureTaskRuntimeRejectedOutputWrite {
  data class Written(val identity: String) : FeatureTaskRuntimeRejectedOutputWrite()

  data class Degraded(
    val failureClass: FeatureTaskRuntimeDiagnosticFailureClass,
  ) : FeatureTaskRuntimeRejectedOutputWrite()

  data object OwnershipRejected : FeatureTaskRuntimeRejectedOutputWrite()
}
