package skillbill.engine.featuretask.slot.audit

import skillbill.engine.featuretask.slot.PhaseAcceptanceRepairHooks
import skillbill.engine.featuretask.slot.PhaseStepHooks
import skillbill.engine.featuretask.slot.state.PhaseAcceptedStepExecution

internal object AuditImplementFixStep :
  PhaseStepHooks<PhaseAcceptedStepExecution>,
  PhaseAcceptanceRepairHooks {
  override val serialAcceptanceRepairStep: Boolean = true
}
