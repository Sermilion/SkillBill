package skillbill.engine.featuretask.slot.attempt

import skillbill.engine.featuretask.phase.prompt.compose.PhaseStepPromptSource
import skillbill.engine.featuretask.runloop.core.FeatureTaskRuntimeRunLoopContext
import skillbill.engine.featuretask.runloop.core.PhaseOutcome
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.slot.PhaseRunState
import skillbill.engine.featuretask.slot.PhaseStepDescription
import skillbill.engine.featuretask.slot.PhaseStrategy
import skillbill.engine.featuretask.slot.phaseEnvelopeDecoder
import skillbill.error.featuretask.UnknownPhaseStepError
import skillbill.workflow.taskruntime.model.core.PhaseStepPolicy

internal fun PhaseStrategy.promptSource(stepId: String): PhaseStepPromptSource =
  PhaseStepPromptSource { inputs -> promptSections(stepId, inputs) }

internal fun PhaseStrategy.stepCall(
  run: PhaseRun,
  state: PhaseRunState,
): PhaseStepCall =
  PhaseStepCall(
    PhaseStepDescription(run.phaseId, promptSource(run.phaseId), policyFor(run.phaseId), phaseEnvelopeDecoder),
    runner,
    state,
  )

internal fun PhaseStrategy.runAgentStep(
  run: PhaseRun,
  context: FeatureTaskRuntimeRunLoopContext,
  state: PhaseRunState,
): PhaseOutcome = with(PhaseAttemptLoop) { context.runPhaseAttempts(run, stepCall(run, state)) }

internal fun Map<String, PhaseStepPolicy>.policyOf(stepId: String): PhaseStepPolicy =
  this[stepId] ?: throw UnknownPhaseStepError(stepId)
