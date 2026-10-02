package skillbill.ports.taskruntime

import skillbill.ports.taskruntime.model.FeatureTaskRuntimeSharedEvidenceDerivation
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeRepositoryCheckpoint

fun interface FeatureTaskRuntimeSharedEvidenceDeriver {
  /** Returns null when the evidence cannot be derived; the resolver then persists nothing. */
  fun derive(checkpoint: FeatureTaskRuntimeRepositoryCheckpoint): FeatureTaskRuntimeSharedEvidenceDerivation?
}
