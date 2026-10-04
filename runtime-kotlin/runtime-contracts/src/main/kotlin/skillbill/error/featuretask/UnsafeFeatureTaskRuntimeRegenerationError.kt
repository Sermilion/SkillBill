package skillbill.error.featuretask

import skillbill.error.core.RuntimeFailureCode
import skillbill.error.core.SkillBillRuntimeException

enum class FeatureTaskRuntimeRegenerationRefusal(val wireValue: String) : RuntimeFailureCode {
  MISSING_WORKFLOW("missing_workflow"),
  TERMINAL_WORKFLOW("terminal_workflow"),
  UNPROVEN_GATE_SEMANTICS("unproven_gate_semantics"),
  IRREVERSIBLE_WORK_RECORDED("irreversible_work_recorded"),
  MISSING_PRODUCER_EVIDENCE("missing_producer_evidence"),
}

fun regenerationRefused(refusal: FeatureTaskRuntimeRegenerationRefusal): SkillBillRuntimeException =
  SkillBillRuntimeException(
    refusal,
    "Receipt regeneration refused: ${refusal.wireValue}. " +
      "Retain the workflow and inspect its evidence with a compatible runtime.",
  )
