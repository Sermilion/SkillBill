package skillbill.infrastructure.sqlite.core.ops

import skillbill.error.shellcontent.invalidWorkflowStateSchemaError
import java.time.Instant
import java.time.format.DateTimeParseException

internal fun parseWorktreeEditJournalInstant(raw: String?): Instant {
  if (raw.isNullOrBlank()) {
    throw invalidWorkflowStateSchemaError("Worktree edit journal recorded_at must be a persisted instant.")
  }
  return try {
    Instant.parse(raw)
  } catch (failure: DateTimeParseException) {
    throw invalidWorkflowStateSchemaError(
      "Worktree edit journal recorded_at '$raw' must be a persisted instant.",
      failure,
    )
  }
}
