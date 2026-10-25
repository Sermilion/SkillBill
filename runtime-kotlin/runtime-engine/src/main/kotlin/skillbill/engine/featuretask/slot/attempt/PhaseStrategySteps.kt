package skillbill.engine.featuretask.slot.attempt

import skillbill.engine.featuretask.phase.prompt.compose.PhaseStepPromptSource
import skillbill.engine.featuretask.runloop.core.PhaseOutcome
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.slot.ModelDirectiveSupplements
import skillbill.engine.featuretask.slot.PhaseStepDescription
import skillbill.engine.featuretask.slot.PhaseStrategy
import skillbill.engine.featuretask.slot.state.PhaseAcceptedStepExecution
import skillbill.engine.featuretask.slot.state.PhaseAgentExecution
import skillbill.error.featuretask.UnknownPhaseStepError
import skillbill.workflow.taskruntime.model.skeleton.PhaseStepPolicy

internal fun PhaseStrategy.promptSource(
  stepId: String,
  supplements: ModelDirectiveSupplements,
): PhaseStepPromptSource =
  PhaseStepPromptSource { inputs ->
    supplements.supplement(
      promptSections(stepId, inputs),
      this,
      stepId,
      inputs.stepProfile,
      probes = { inputs.validateProbesActive },
    )
  }

internal fun stepCall(
  run: PhaseRun,
  state: PhaseAcceptedStepExecution,
): PhaseStepCall {
  val owner = state.acceptedOwner
  return PhaseStepCall(
    PhaseStepDescription(
      run.phaseId,
      owner.promptSource(run.phaseId, state.modelDirectiveSupplements),
      owner.policyFor(run.phaseId),
    ),
    state,
    run.request,
    owner.strategyId,
  )
}

internal fun <S> runAgentStep(
  run: PhaseRun,
  state: S,
): PhaseOutcome where S : PhaseAcceptedStepExecution, S : PhaseAgentExecution =
  state.runAcceptedAgentStep(run, stepCall(run, state))

internal fun Map<String, PhaseStepPolicy>.policyOf(stepId: String): PhaseStepPolicy =
  this[stepId] ?: throw UnknownPhaseStepError(stepId)
