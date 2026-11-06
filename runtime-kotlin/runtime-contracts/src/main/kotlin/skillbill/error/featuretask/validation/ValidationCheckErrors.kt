package skillbill.error.featuretask.validation

import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.FeatureTaskRuntimeFailureCode

private const val VALIDATION_CHECK_REJECTED_PREFIX = "Validation check rejected: "

enum class ValidationCheckRejectionReason(val wireValue: String) {
  NO_RUN_IDENTITY("no_run_identity"),
  RUN_NOT_FOUND("run_not_found"),
  NOT_STANDALONE_VALIDATION("not_standalone_validation"),
  RUN_NOT_ACTIVE("run_not_active"),
  INVALID_EVENT("invalid_event"),
  CHECK_ALREADY_RUNNING("check_already_running"),
  CHECK_NOT_STARTED("check_not_started"),
  EVENT_LIMIT_REACHED("event_limit_reached"),
  ;

  companion object {
    fun fromWire(value: String?): ValidationCheckRejectionReason? = entries.firstOrNull { it.wireValue == value }
  }
}

val SkillBillRuntimeException.isValidationCheckFailure: Boolean
  get() = code == FeatureTaskRuntimeFailureCode.VALIDATION_CHECK_FAILED

val SkillBillRuntimeException.validationCheckRejectionReason: ValidationCheckRejectionReason?
  get() = ValidationCheckRejectionReason.fromWire(message.orEmpty().removePrefix(VALIDATION_CHECK_REJECTED_PREFIX))

fun validationCheckRejected(reason: ValidationCheckRejectionReason): SkillBillRuntimeException =
  SkillBillRuntimeException(
    FeatureTaskRuntimeFailureCode.VALIDATION_CHECK_FAILED,
    "$VALIDATION_CHECK_REJECTED_PREFIX${reason.wireValue}",
  )
