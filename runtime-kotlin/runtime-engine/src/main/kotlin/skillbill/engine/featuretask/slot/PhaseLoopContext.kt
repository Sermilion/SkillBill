package skillbill.engine.featuretask.slot

import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunFacts
import skillbill.engine.featuretask.slot.PhaseRepositoryObservations
import skillbill.ports.taskruntime.FeatureTaskRuntimePhaseOutputValidator

internal data class PhaseLoopContext(
  val request: FeatureTaskRuntimeRunFacts,
  val outputValidator: FeatureTaskRuntimePhaseOutputValidator,
  val gitOperations: PhaseRepositoryObservations,
)
