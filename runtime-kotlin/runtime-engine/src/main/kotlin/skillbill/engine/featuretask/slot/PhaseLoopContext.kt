package skillbill.engine.featuretask.slot

import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunFacts

internal data class PhaseLoopContext(
  val request: FeatureTaskRuntimeRunFacts,
  val gitOperations: PhaseRepositoryObservations,
)
