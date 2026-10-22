package skillbill.engine.featuretask.slot.audit

import skillbill.engine.featuretask.phase.core.FeatureTaskRuntimeCurrentPhaseExecutionContext
import skillbill.ports.idestatus.model.IdeStatusCurrentPhaseExecution
import skillbill.ports.idestatus.model.IdeStatusCurrentPhaseExecutionKind
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairMode
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairState

internal fun auditCurrentExecution(
  stepId: String,
  context: FeatureTaskRuntimeCurrentPhaseExecutionContext,
): IdeStatusCurrentPhaseExecution? {
  val repair =
    context.acceptanceCriterionRepair
      ?.takeUnless { it.mode == AcceptanceCriterionRepairMode.INITIAL_DISCOVERY }
  repair?.currentCriterionRef?.let { criterion ->
    return IdeStatusCurrentPhaseExecution(
      phaseId = stepId,
      kind = IdeStatusCurrentPhaseExecutionKind.ATTEMPT,
      count =
        (repair.unsuccessfulTotals.getOrDefault(criterion, 0) + 1)
          .coerceAtMost(AcceptanceCriterionRepairState.MAX_UNSUCCESSFUL_REPAIR_OUTCOMES),
    )
  }
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
