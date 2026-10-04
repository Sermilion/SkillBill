package skillbill.error.core

enum class RejectedOutputDiagnosticFailureCode : RuntimeFailureCode {
  PERSISTENCE,
  PERMISSION,
  CORRUPT,
  RETRIEVAL,
  INVALID_CONFIGURATION,
  INVALID_REQUEST,
  CONFLICT,
}

fun rejectedOutputDiagnosticAbsentMessage(identity: String): String =
  "Rejected output diagnostic '$identity' is absent."

fun rejectedOutputDiagnosticExpiredMessage(identity: String): String =
  "Rejected output diagnostic '$identity' has expired."

fun rejectedOutputDiagnosticOversizedMessage(identity: String): String =
  "Rejected output diagnostic '$identity' is oversized."

fun rejectedOutputDiagnosticCorruptMessage(identity: String): String =
  "Rejected output diagnostic '$identity' is corrupt."

fun rejectedOutputDiagnosticPersistenceMessage(operation: String): String =
  "Rejected output diagnostic persistence operation '$operation' failed."

fun rejectedOutputDiagnosticConflictMessage(identity: String): String =
  "Rejected output diagnostic '$identity' conflicts with immutable evidence."

fun rejectedOutputDiagnosticInvalidRequestMessage(reason: String): String =
  "Rejected output diagnostic request is invalid: $reason"

fun rejectedOutputDiagnosticInvalidConfigurationMessage(reason: String): String =
  "Rejected output diagnostic configuration is invalid: $reason"
