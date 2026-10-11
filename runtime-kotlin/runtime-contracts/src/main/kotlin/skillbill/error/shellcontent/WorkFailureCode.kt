package skillbill.error.shellcontent

import skillbill.error.core.RuntimeFailureCode
import skillbill.error.core.SkillBillRuntimeException

enum class WorkFailureCode : RuntimeFailureCode {
  INVALID_WORK_BOARD_SCHEMA,
  INVALID_WORK_DETAIL_SCHEMA,
}

fun invalidWorkBoardSchemaError(
  sourceLabel: String,
  fieldPath: String,
  reason: String,
  cause: Throwable? = null,
): SkillBillRuntimeException =
  SkillBillRuntimeException(
    WorkFailureCode.INVALID_WORK_BOARD_SCHEMA,
    "Work board '${sourceLabel.ifBlank { "<unknown>" }}' fails schema validation at " +
      "'${fieldPath.ifBlank { "<root>" }}': $reason",
    cause,
  )

fun invalidWorkDetailSchemaError(
  sourceLabel: String,
  fieldPath: String,
  reason: String,
  cause: Throwable? = null,
): SkillBillRuntimeException =
  SkillBillRuntimeException(
    WorkFailureCode.INVALID_WORK_DETAIL_SCHEMA,
    "Work detail '${sourceLabel.ifBlank { "<unknown>" }}' fails schema validation at " +
      "'${fieldPath.ifBlank { "<root>" }}': $reason",
    cause,
  )
