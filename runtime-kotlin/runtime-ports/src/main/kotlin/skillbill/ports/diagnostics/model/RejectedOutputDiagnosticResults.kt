package skillbill.ports.diagnostics.model

sealed interface RejectedOutputDiagnosticRead {
  data class Found(val record: RejectedOutputDiagnosticRecord) : RejectedOutputDiagnosticRead

  data class Expired(val record: RejectedOutputDiagnosticRecord) : RejectedOutputDiagnosticRead

  data class Oversized(val record: RejectedOutputDiagnosticRecord) : RejectedOutputDiagnosticRead

  data class Absent(val identity: String) : RejectedOutputDiagnosticRead
}

sealed interface RejectedOutputDiagnosticInsert {
  data class Inserted(val record: RejectedOutputDiagnosticRecord) : RejectedOutputDiagnosticInsert

  data class Conflict(val identity: String) : RejectedOutputDiagnosticInsert
}
