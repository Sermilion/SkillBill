package skillbill.error.core

enum class BlockIncidentFailureCode : RuntimeFailureCode {
  INVALID_SCHEMA,
  INVALID_VERSION,
  IMMUTABLE_CONFLICT,
  PERSISTENCE,
  QUARANTINE,
  FALLBACK_SPOOL,
}
