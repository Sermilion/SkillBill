package skillbill.error.core

enum class ExternalPlatformPackFailureCode : RuntimeFailureCode {
  CONFIG,
  AMBIGUOUS,
  OVERLAY,
  PUBLISH,
}

fun externalPlatformPackConfig(
  message: String,
  cause: Throwable? = null,
): SkillBillRuntimeException = SkillBillRuntimeException(ExternalPlatformPackFailureCode.CONFIG, message, cause)

fun ambiguousExternalPlatformPack(
  message: String,
  cause: Throwable? = null,
): SkillBillRuntimeException = SkillBillRuntimeException(ExternalPlatformPackFailureCode.AMBIGUOUS, message, cause)

fun externalPlatformPackOverlay(
  message: String,
  cause: Throwable? = null,
): SkillBillRuntimeException = SkillBillRuntimeException(ExternalPlatformPackFailureCode.OVERLAY, message, cause)

fun externalPlatformPackPublish(
  message: String,
  cause: Throwable? = null,
): SkillBillRuntimeException = SkillBillRuntimeException(ExternalPlatformPackFailureCode.PUBLISH, message, cause)
