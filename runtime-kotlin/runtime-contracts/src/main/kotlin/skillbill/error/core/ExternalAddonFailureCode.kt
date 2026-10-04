package skillbill.error.core

enum class ExternalAddonFailureCode : RuntimeFailureCode {
  CONFIG,
  OVERLAY,
}

fun externalAddonConfig(
  message: String,
  cause: Throwable? = null,
): SkillBillRuntimeException = SkillBillRuntimeException(ExternalAddonFailureCode.CONFIG, message, cause)

fun externalAddonOverlay(
  message: String,
  cause: Throwable? = null,
): SkillBillRuntimeException = SkillBillRuntimeException(ExternalAddonFailureCode.OVERLAY, message, cause)
