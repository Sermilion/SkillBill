package skillbill.engine.featuretask.slot

import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunFacts
import skillbill.workflow.taskruntime.model.audit.FeatureTaskRuntimeNoChangePause
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord

internal data class PhaseLoopContext(
  val request: FeatureTaskRuntimeRunFacts,
  val gitOperations: PhaseRepositoryObservations,
  val noChangePause: () -> FeatureTaskRuntimeNoChangePause? = { null },
  val phaseRecord: (String) -> FeatureTaskRuntimePhaseRecord? = { null },
)
