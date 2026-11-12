package skillbill.error.apprun

import skillbill.contracts.apprun.APP_RUN_CONFIG_CONTRACT_VERSION
import skillbill.error.core.RuntimeFailureCode
import skillbill.error.core.SkillBillRuntimeException

enum class AppRunFailureCode : RuntimeFailureCode {
  INVALID_APP_RUN_CONFIG,
  UNSUPPORTED_APP_RUN_CONFIG_VERSION,
}

fun invalidAppRunConfigError(
  path: String,
  field: String,
  reason: String,
  cause: Throwable? = null,
): SkillBillRuntimeException =
  SkillBillRuntimeException(
    AppRunFailureCode.INVALID_APP_RUN_CONFIG,
    "App-run config '$path' is invalid at '${field.ifBlank { "<root>" }}': $reason",
    cause,
  )

fun unsupportedAppRunConfigVersionError(
  path: String,
  found: String,
): SkillBillRuntimeException =
  SkillBillRuntimeException(
    AppRunFailureCode.UNSUPPORTED_APP_RUN_CONFIG_VERSION,
    "App-run config '$path' has unsupported 'contract_version' '$found'; " +
      "expected '$APP_RUN_CONFIG_CONTRACT_VERSION'.",
  )
