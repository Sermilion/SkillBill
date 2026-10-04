package skillbill.error.featuretask

import skillbill.error.core.RuntimeFailureCode
import skillbill.error.core.SkillBillRuntimeException

enum class FeatureTaskRuntimeExecutionPlanAdmissionCode(val wireValue: String) : RuntimeFailureCode {
  MISSING_DESCRIPTOR("missing_descriptor"),
  CORRUPT_DESCRIPTOR("corrupt_descriptor"),
  UNSUPPORTED_DESCRIPTOR("unsupported_descriptor"),
  INCOMPATIBLE_DESCRIPTOR("incompatible_descriptor"),
}

fun executionPlanRefused(code: FeatureTaskRuntimeExecutionPlanAdmissionCode): SkillBillRuntimeException =
  SkillBillRuntimeException(
    code,
    "Durable execution plan refused: ${code.wireValue}. Retain the original workflow and its evidence. " +
      "Inspect status and use a compatible runtime or a separately reviewed semantic mapping.",
  )
