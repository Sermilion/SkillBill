package skillbill.engine.featuretask.slot.audit

import skillbill.engine.featuretask.phase.core.FeatureTaskRuntimeCurrentPhaseExecutionContext
import skillbill.engine.work.model.IdeStatusCurrentPhaseExecution
import skillbill.engine.work.model.IdeStatusCurrentPhaseExecutionKind

internal fun auditCurrentExecution(
  stepId: String,
  context: FeatureTaskRuntimeCurrentPhaseExecutionContext,
): IdeStatusCurrentPhaseExecution? {
  val attempts = context.phases.firstOrNull { it.phaseId == stepId }?.attemptCount ?: 0
  return if (attempts >= 1 || context.records[stepId] != null) {
    IdeStatusCurrentPhaseExecution(
      phaseId = stepId,
      kind = IdeStatusCurrentPhaseExecutionKind.PASS,
      count = 1,
    )
  } else {
    null
  }
}
