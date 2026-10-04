package skillbill.error.shellcontent

import skillbill.error.core.RuntimeFailureCode
import skillbill.error.core.SkillBillRuntimeException

enum class ScaffoldFailureCode : RuntimeFailureCode {
  SCAFFOLD_FAILURE,
  PAYLOAD_VERSION_MISMATCH,
  INVALID_PAYLOAD,
  RETIRED_KIND,
  UNKNOWN_SKILL_KIND,
  UNKNOWN_PRE_SHELL_FAMILY,
  SKILL_ALREADY_EXISTS,
}

fun scaffoldFailure(
  message: String,
  cause: Throwable? = null,
): SkillBillRuntimeException = SkillBillRuntimeException(ScaffoldFailureCode.SCAFFOLD_FAILURE, message, cause)

fun scaffoldPayloadVersionMismatchError(
  message: String,
  cause: Throwable? = null,
): SkillBillRuntimeException = SkillBillRuntimeException(ScaffoldFailureCode.PAYLOAD_VERSION_MISMATCH, message, cause)

fun invalidScaffoldPayloadError(
  message: String,
  cause: Throwable? = null,
): SkillBillRuntimeException = SkillBillRuntimeException(ScaffoldFailureCode.INVALID_PAYLOAD, message, cause)

fun retiredScaffoldKindError(
  message: String,
  cause: Throwable? = null,
): SkillBillRuntimeException = SkillBillRuntimeException(ScaffoldFailureCode.RETIRED_KIND, message, cause)

fun unknownSkillKindError(
  message: String,
  cause: Throwable? = null,
): SkillBillRuntimeException = SkillBillRuntimeException(ScaffoldFailureCode.UNKNOWN_SKILL_KIND, message, cause)

fun unknownPreShellFamilyError(
  message: String,
  cause: Throwable? = null,
): SkillBillRuntimeException = SkillBillRuntimeException(ScaffoldFailureCode.UNKNOWN_PRE_SHELL_FAMILY, message, cause)

fun missingPlatformPackError(
  message: String,
  cause: Throwable? = null,
): SkillBillRuntimeException = SkillBillRuntimeException(ScaffoldFailureCode.SCAFFOLD_FAILURE, message, cause)

fun missingSupportingFileTargetError(
  message: String,
  cause: Throwable? = null,
): SkillBillRuntimeException = SkillBillRuntimeException(ScaffoldFailureCode.SCAFFOLD_FAILURE, message, cause)

fun skillAlreadyExistsError(
  message: String,
  cause: Throwable? = null,
): SkillBillRuntimeException = SkillBillRuntimeException(ScaffoldFailureCode.SKILL_ALREADY_EXISTS, message, cause)

fun scaffoldRollbackError(
  message: String,
  cause: Throwable? = null,
): SkillBillRuntimeException = SkillBillRuntimeException(ScaffoldFailureCode.SCAFFOLD_FAILURE, message, cause)
