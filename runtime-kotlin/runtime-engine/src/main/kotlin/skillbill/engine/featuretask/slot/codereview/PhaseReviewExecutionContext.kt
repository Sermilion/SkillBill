package skillbill.engine.featuretask.slot.codereview

import skillbill.engine.featuretask.slot.PhaseRepositoryObservations
import skillbill.ports.taskruntime.FeatureTaskRuntimePhaseOutputValidator
import java.time.Clock

internal data class PhaseReviewExecutionContext(
  val gitOperations: PhaseRepositoryObservations,
  val outputValidator: FeatureTaskRuntimePhaseOutputValidator,
  val clock: Clock,
)
