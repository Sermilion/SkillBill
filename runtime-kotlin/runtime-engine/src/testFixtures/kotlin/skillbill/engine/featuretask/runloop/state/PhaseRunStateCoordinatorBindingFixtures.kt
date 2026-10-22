package skillbill.engine.featuretask.runloop.state

import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.slot.state.PhaseAcceptedStepExecution
import skillbill.engine.featuretask.slot.state.PhaseRunState
import skillbill.engine.featuretask.slot.state.step

internal fun PhaseRunState.bindStepForCoordinator(run: PhaseRun): PhaseAcceptedStepExecution {
  stepBinding.authorizeCoordinatorDispatch(run)
  return step(run)
}

internal fun PhaseRunState.releaseCoordinatorStepBinding(run: PhaseRun) {
  stepBinding.releaseCoordinatorDispatch()
}
