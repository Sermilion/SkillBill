package skillbill.engine.featuretask.slot.state

import skillbill.engine.featuretask.runloop.core.PhaseOutcome
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.slot.PhaseStepCapture
import skillbill.engine.featuretask.slot.PhaseStepDescriptor
import skillbill.engine.featuretask.slot.PhaseStrategy

internal fun PhaseRunState.step(run: PhaseRun): PhaseAcceptedStepExecution =
  strategyFor(run.phaseId).stepDescriptor(run.phaseId).bind(bindings(run))

internal fun PhaseStrategy.runStep(
  run: PhaseRun,
  runState: PhaseRunState,
  inspect: (PhaseAcceptedStepExecution) -> Unit = {},
): PhaseOutcome =
  stepDescriptor(run.phaseId).capture(
    object : PhaseStepCapture<PhaseOutcome> {
      override fun <B : PhaseAcceptedStepExecution> capture(descriptor: PhaseStepDescriptor<B>): PhaseOutcome {
        val binding = descriptor.bind(runState.bindings(run))
        inspect(binding)
        return descriptor.execute(run, binding)
      }
    },
  )
