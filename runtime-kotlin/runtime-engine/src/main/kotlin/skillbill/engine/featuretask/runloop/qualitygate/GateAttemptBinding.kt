package skillbill.engine.featuretask.runloop.qualitygate

import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.slot.attempt.PhaseQualityGateCycleContext
import skillbill.engine.featuretask.slot.attempt.PhaseStepCall
import skillbill.engine.featuretask.slot.state.PhaseAcceptedStepExecution

internal fun PhaseQualityGateCycleContext.gateAttemptCall(
  call: PhaseStepCall,
  acceptedRun: PhaseRun,
  attemptRun: PhaseRun,
): PhaseStepCall {
  call.requireAcceptedAttempt(acceptedRun, call)
  check(attemptRun.request === acceptedRun.request && attemptRun.phaseId == acceptedRun.phaseId)
  check(attemptRun.policy == acceptedRun.policy)
  val binding = GateAttemptBinding(call.acceptedExecution, acceptedRun, attemptRun)
  return call.copy(acceptedStep = binding)
}

private class GateAttemptBinding(
  private val parent: PhaseAcceptedStepExecution,
  private val acceptedRun: PhaseRun,
  private val attemptRun: PhaseRun,
) : PhaseAcceptedStepExecution by parent {
  override fun requireAcceptedAttempt(
    run: PhaseRun,
    call: PhaseStepCall,
  ) {
    check(run === attemptRun) { "Gate attempt is not the runtime-issued repair run." }
    parent.requireAcceptedAttempt(acceptedRun, call)
  }

  override fun requireAcceptedStep(
    run: PhaseRun,
    strategyId: String,
  ) {
    check(run === attemptRun)
    parent.requireAcceptedStep(acceptedRun, strategyId)
  }
}
