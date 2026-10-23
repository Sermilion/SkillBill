package skillbill.error.blockincident

import skillbill.error.core.RuntimeFailureCode

enum class BlockIncidentFailureCode : RuntimeFailureCode {
  INVALID_SCHEMA,
  INVALID_VERSION,
  IMMUTABLE_CONFLICT,
  PERSISTENCE,
  QUARANTINE,
  FALLBACK_SPOOL,
}
