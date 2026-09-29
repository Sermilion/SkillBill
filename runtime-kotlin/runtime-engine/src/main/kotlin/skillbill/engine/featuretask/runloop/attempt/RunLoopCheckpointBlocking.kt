package skillbill.engine.featuretask.runloop.attempt

import skillbill.engine.featuretask.runloop.core.FeatureTaskRuntimeRunLoopContext
import skillbill.engine.featuretask.runloop.core.PhaseBlockRequest
import skillbill.engine.featuretask.runloop.core.PhaseOutcome
import skillbill.engine.featuretask.runloop.phase.FeatureTaskRuntimeRunLoopPhaseBlocking
import skillbill.engine.featuretask.slot.attempt.PhaseCheckpointRemediationContext

internal fun PhaseCheckpointRemediationContext.blockStepInPhase(block: PhaseBlockRequest): PhaseOutcome {
  val coupling = remediationCoupling()
  return FeatureTaskRuntimeRunLoopPhaseBlocking.blockInPhase(
    request,
    coupling.progress,
    coupling.transitions,
    recorder,
    observability,
    block,
  )
}
