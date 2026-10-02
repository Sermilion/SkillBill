package skillbill.application.diagnostics.model

import skillbill.ports.diagnostics.model.RejectedOutputDiagnostic

sealed interface RejectedOutputDiagnosticRecording {
  data class Recorded(val metadata: RejectedOutputDiagnostic) : RejectedOutputDiagnosticRecording

  data class Conflict(val identity: String) : RejectedOutputDiagnosticRecording

  data class InvalidRequest(val reason: String) : RejectedOutputDiagnosticRecording
}

sealed interface RejectedOutputDiagnosticSelection {
  data class Selected(val diagnostics: List<RejectedOutputDiagnostic>) : RejectedOutputDiagnosticSelection

  data class InvalidRequest(val reason: String) : RejectedOutputDiagnosticSelection
}

sealed interface RejectedOutputDiagnosticDeletion {
  data class Deleted(val count: Int) : RejectedOutputDiagnosticDeletion

  data class InvalidRequest(val reason: String) : RejectedOutputDiagnosticDeletion
}

sealed interface RejectedOutputDiagnosticRawRead {
  class Payload(val bytes: ByteArray) : RejectedOutputDiagnosticRawRead

  data class Absent(val identity: String) : RejectedOutputDiagnosticRawRead

  data class Expired(val identity: String) : RejectedOutputDiagnosticRawRead

  data class Oversized(val identity: String) : RejectedOutputDiagnosticRawRead
}
