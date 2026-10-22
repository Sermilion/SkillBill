package skillbill.workflow.taskruntime.model.audit

import skillbill.workflow.model.validation.FeatureTaskRuntimeVerdict

const val REPAIR_ADMISSION_CLARIFICATION_PREFIX: String = "Read-only repair-report admission."

val AUDIT_CONCLUSION_VERDICTS: Set<String> =
  setOf(
    FeatureTaskRuntimeVerdict.SATISFIED.wireValue,
    FeatureTaskRuntimeVerdict.NO_CHANGE_CONFIRMED.wireValue,
    FeatureTaskRuntimeVerdict.NO_CHANGE_REJECTED.wireValue,
    FeatureTaskRuntimeVerdict.ADVANCE.wireValue,
  )

fun auditNeedsClarification(conclusion: String?): Boolean = conclusion !in AUDIT_CONCLUSION_VERDICTS

fun AcceptanceCriterionRepairState.awaitsAuditClarification(): Boolean =
  pendingClarificationQuestion?.startsWith(REPAIR_ADMISSION_CLARIFICATION_PREFIX) == false
