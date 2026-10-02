package skillbill.error.featuretask

import skillbill.error.core.RuntimeFailureCode

enum class RuntimeOwnedPersistenceFailureCode : RuntimeFailureCode {
  FACT_UNAVAILABLE,
  REVIEW_FACT_UNAVAILABLE,
}
