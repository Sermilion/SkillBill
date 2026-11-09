package skillbill.engine.featuretask.slot.audit

import skillbill.engine.featuretask.phase.core.FeatureTaskRuntimeCurrentPhaseExecutionContext
import skillbill.ports.idestatus.model.IdeStatusCurrentPhaseExecution
import skillbill.ports.idestatus.model.IdeStatusCurrentPhaseExecutionKind
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionAccountingPolicy
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairMode
import skillbill.workflow.taskruntime.model.audit.terminal

internal fun auditCurrentExecution(
  stepId: String,
  context: FeatureTaskRuntimeCurrentPhaseExecutionContext,
): IdeStatusCurrentPhaseExecution? {
  val repair =
    context.acceptanceCriterionRepair
      ?.takeUnless { it.mode == AcceptanceCriterionRepairMode.INITIAL_DISCOVERY }
  repair?.currentCriterionRef?.let { criterion ->
    val completed =
      if (repair.accountingPolicy == AcceptanceCriterionAccountingPolicy.PRE_ADOPTION) {
        repair.outcomes.count { it.criterionRef == criterion } + 1
      } else {
        repair.completedMutations.count { it.criterionRef == criterion && it.terminal }
      }
    if (completed == 0) return null
    return IdeStatusCurrentPhaseExecution(
      phaseId = stepId,
      kind = IdeStatusCurrentPhaseExecutionKind.ATTEMPT,
      criterionRef = criterion,
      count = completed,
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
