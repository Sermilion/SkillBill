package skillbill.infrastructure.contracts

import skillbill.error.core.RuntimeFailureCode
import skillbill.error.core.SkillBillRuntimeException

internal enum class PackagedContractResourceFailure : RuntimeFailureCode {
  SCHEMA_UNAVAILABLE,
}

internal fun packagedContractResourceFailure(
  resource: String,
  cause: Throwable? = null,
): SkillBillRuntimeException =
  SkillBillRuntimeException(
    PackagedContractResourceFailure.SCHEMA_UNAVAILABLE,
    "Packaged contract resource '$resource' is missing or invalid. Repair the runtime package before retrying.",
    cause,
  )
