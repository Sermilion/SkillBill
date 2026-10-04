package skillbill.error.core

enum class FeatureSpecPreparationFailureCode : RuntimeFailureCode {
  INVALID_REQUEST,
}

fun invalidFeatureSpecPreparationRequest(
  fieldPath: String,
  reason: String,
  cause: Throwable? = null,
): SkillBillRuntimeException =
  SkillBillRuntimeException(
    FeatureSpecPreparationFailureCode.INVALID_REQUEST,
    "Feature-spec preparation request is invalid at '${fieldPath.ifBlank { "<root>" }}': $reason",
    cause,
  )
