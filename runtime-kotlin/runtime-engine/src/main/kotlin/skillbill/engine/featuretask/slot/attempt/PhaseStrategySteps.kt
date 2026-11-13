package skillbill.engine.featuretask.slot.attempt

import skillbill.engine.featuretask.phase.prompt.compose.PhaseStepPromptSource
import skillbill.engine.featuretask.runloop.core.PhaseOutcome
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.slot.PhaseStepDescription
import skillbill.engine.featuretask.slot.PhaseStrategy
import skillbill.engine.featuretask.slot.state.PhaseAcceptedStepExecution
import skillbill.engine.featuretask.slot.state.PhaseAgentExecution
import skillbill.error.featuretask.UnknownPhaseStepError
import skillbill.ports.agentrun.model.AgentRunOutputSink
import skillbill.workflow.taskruntime.model.skeleton.PhaseStepPolicy

internal fun PhaseStrategy.promptSource(stepId: String): PhaseStepPromptSource =
  PhaseStepPromptSource { inputs -> promptSections(stepId, inputs) }

internal fun stepCall(
  run: PhaseRun,
  state: PhaseAcceptedStepExecution,
): PhaseStepCall {
  val owner = state.acceptedOwner
  return PhaseStepCall(
    PhaseStepDescription(
      run.phaseId,
      owner.promptSource(run.phaseId),
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
  outputSink: AgentRunOutputSink = AgentRunOutputSink.NONE,
  captureStreamOutput: Boolean = false,
): PhaseOutcome where S : PhaseAcceptedStepExecution, S : PhaseAgentExecution =
  state.runAcceptedAgentStep(run, stepCall(run, state), outputSink, captureStreamOutput)

internal fun Map<String, PhaseStepPolicy>.policyOf(stepId: String): PhaseStepPolicy =
  this[stepId] ?: throw UnknownPhaseStepError(stepId)
