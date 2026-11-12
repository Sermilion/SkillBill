package skillbill.engine.featuretask.model.audit

import skillbill.workflow.taskruntime.model.audit.AcceptanceRepairStep

internal fun AcceptanceRepairStep.stepId(steps: AcceptanceCriterionRepairStepRoles): String? =
  when (this) {
    AcceptanceRepairStep.Discover, AcceptanceRepairStep.ReadOnlyAudit,
    is AcceptanceRepairStep.Verify, is AcceptanceRepairStep.Blocked,
    -> steps.audit
    is AcceptanceRepairStep.Plan -> steps.planning
    is AcceptanceRepairStep.Repair -> steps.repair
    AcceptanceRepairStep.Accepted -> null
  }
