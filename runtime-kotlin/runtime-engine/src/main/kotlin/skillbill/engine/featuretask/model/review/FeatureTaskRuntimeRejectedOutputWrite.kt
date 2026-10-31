package skillbill.engine.featuretask.model.review

import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeDiagnosticFailureClass

public sealed class FeatureTaskRuntimeRejectedOutputWrite {
  public data class Written(val identity: String) : FeatureTaskRuntimeRejectedOutputWrite()

  public data class Degraded(
    val failureClass: FeatureTaskRuntimeDiagnosticFailureClass,
  ) : FeatureTaskRuntimeRejectedOutputWrite()

  public data object OwnershipRejected : FeatureTaskRuntimeRejectedOutputWrite()
}
