package skillbill.infrastructure.launcher.review

import skillbill.error.core.CursorReviewStreamFailureCode
import skillbill.error.core.SkillBillRuntimeException

internal fun malformedCursorReviewStream(
  message: String,
  cause: Throwable? = null,
): SkillBillRuntimeException = SkillBillRuntimeException(CursorReviewStreamFailureCode.MALFORMED, message, cause)

internal fun forbiddenCursorReviewOperation(message: String): SkillBillRuntimeException =
  SkillBillRuntimeException(CursorReviewStreamFailureCode.FORBIDDEN_OPERATION, message)

internal fun cursorReviewProviderFailure(
  message: String,
  cause: Throwable? = null,
): SkillBillRuntimeException = SkillBillRuntimeException(CursorReviewStreamFailureCode.PROVIDER_FAILURE, message, cause)

internal fun cursorReviewTermination(message: String): SkillBillRuntimeException =
  SkillBillRuntimeException(CursorReviewStreamFailureCode.TERMINATION, message)

internal fun cursorReviewUnknown(message: String): SkillBillRuntimeException =
  SkillBillRuntimeException(CursorReviewStreamFailureCode.UNKNOWN, message)
