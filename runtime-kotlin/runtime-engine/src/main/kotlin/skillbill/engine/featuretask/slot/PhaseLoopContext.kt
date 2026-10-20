package skillbill.engine.featuretask.slot

import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunFacts
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.workflow.taskruntime.model.audit.FeatureTaskRuntimeNoChangePause
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord

internal data class PhaseLoopContext(
  val request: FeatureTaskRuntimeRunFacts,
  val gitOperations: PhaseRepositoryObservations,
  val diagnostics: RuntimeDiagnostics,
  val loopIteration: (String) -> Int,
  val noChangePause: () -> FeatureTaskRuntimeNoChangePause? = { null },
  val phaseRecord: (String) -> FeatureTaskRuntimePhaseRecord? = { null },
)
