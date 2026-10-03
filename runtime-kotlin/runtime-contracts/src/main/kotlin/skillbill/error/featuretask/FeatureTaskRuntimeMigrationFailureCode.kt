package skillbill.error.featuretask

import skillbill.error.core.RuntimeFailureCode

enum class FeatureTaskRuntimeMigrationFailureCode : RuntimeFailureCode {
  SOURCE_UNSUPPORTED,
  SOURCE_CORRUPT,
  TARGET_NON_CONVERTIBLE,
  INVALID_TARGET,
  STALE_SOURCE,
  UNSAFE_IMPORT,
  WRITE_FAILURE,
}
