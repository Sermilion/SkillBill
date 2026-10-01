package skillbill.engine.featuretask.slot.qualitygate

import skillbill.engine.featuretask.phase.core.FeatureTaskRuntimeCurrentPhaseExecutionContext
import skillbill.engine.featuretask.phase.core.attemptPhaseExecution
import skillbill.engine.work.model.IdeStatusCurrentPhaseExecution
import skillbill.engine.work.model.IdeStatusCurrentPhaseExecutionKind
import skillbill.workflow.taskruntime.model.skeleton.PhaseStepPolicy

internal val QUALITY_GATE_STEP_POLICY: PhaseStepPolicy =
  PhaseStepPolicy(
    mutating = false,
    singleAgentSession = false,
    readOnlyIdle = false,
    fileMutating = true,
    generationScoped = false,
  )

internal fun gateCurrentExecution(
  stepId: String,
  context: FeatureTaskRuntimeCurrentPhaseExecutionContext,
): IdeStatusCurrentPhaseExecution? =
  context.gateRunCount?.takeIf { it >= 1 }?.let { count ->
    IdeStatusCurrentPhaseExecution(
      phaseId = stepId,
      kind = IdeStatusCurrentPhaseExecutionKind.GATE_RUN,
      count = count,
    )
  } ?: attemptPhaseExecution(stepId, context)
