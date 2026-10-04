package skillbill.engine.featuretask.slot.attempt

import skillbill.engine.featuretask.phase.prompt.compose.PhaseStepPromptSource
import skillbill.engine.featuretask.runloop.core.PhaseOutcome
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.slot.PhaseStepDescription
import skillbill.engine.featuretask.slot.PhaseStrategy
import skillbill.engine.featuretask.slot.state.PhaseAcceptedStepExecution
import skillbill.engine.featuretask.slot.state.PhaseAgentExecution
import skillbill.error.featuretask.UnknownPhaseStepError
import skillbill.workflow.taskruntime.model.skeleton.PhaseStepPolicy

internal fun PhaseStrategy.promptSource(stepId: String): PhaseStepPromptSource =
  PhaseStepPromptSource { inputs -> promptSections(stepId, inputs) }

internal fun PhaseStrategy.stepCall(
  run: PhaseRun,
  state: PhaseAcceptedStepExecution,
): PhaseStepCall =
  PhaseStepCall(
    PhaseStepDescription(run.phaseId, promptSource(run.phaseId), policyFor(run.phaseId)),
    state,
    run.request,
    strategyId,
  )

internal fun PhaseStrategy.runAgentStep(
  run: PhaseRun,
  state: PhaseAcceptedStepExecution,
): PhaseOutcome = (state as PhaseAgentExecution).runAcceptedAgentStep(run, stepCall(run, state))

internal fun Map<String, PhaseStepPolicy>.policyOf(stepId: String): PhaseStepPolicy =
  this[stepId] ?: throw UnknownPhaseStepError(stepId)
