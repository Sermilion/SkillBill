package skillbill.engine.work.pagination

import skillbill.error.core.RuntimeFailureCode
import skillbill.error.core.SkillBillRuntimeException
import skillbill.ports.work.model.WorkStoreObservation
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.Base64

private const val MAX_CURSOR_LENGTH = 16384
private const val BASE64_GROUP_SIZE = 4
private const val OBSERVED_CURSOR_FIELD_COUNT = 5
private const val OBSERVED_CURSOR_POSITION_INDEX = 4
private const val CURSOR_FIELD_COUNT = 4
private const val CURSOR_SELECTOR_INDEX = 1
private const val CURSOR_TIME_INDEX = 2
private const val CURSOR_POSITION_INDEX = 3

internal enum class WorkPaginationFailureCode : RuntimeFailureCode {
  INVALID_WORK_PAGINATION_REQUEST,
}

internal data class WorkPageCursor(
  val observedAt: Instant,
  val position: String,
  val observation: WorkStoreObservation? = null,
)

internal fun decodeWorkPageCursor(
  cursor: String,
  selector: String,
): WorkPageCursor {
  if (cursor.length !in 1..MAX_CURSOR_LENGTH || cursor.length % BASE64_GROUP_SIZE == 1 ||
    !cursor.matches(Regex("[A-Za-z0-9_-]+"))
  ) {
    invalidWorkPaginationRequest("Invalid board continuation cursor.")
  }
  val decoded = Base64.getUrlDecoder().decode(cursor).toString(Charsets.UTF_8)
  val version = decoded.substringBefore('\n')
  val observed = version == "2"
  val fieldCount = if (observed) OBSERVED_CURSOR_FIELD_COUNT else CURSOR_FIELD_COUNT
  val positionIndex = if (observed) OBSERVED_CURSOR_POSITION_INDEX else CURSOR_POSITION_INDEX
  val fields = decoded.split('\n', limit = fieldCount)
  if (fields.size != fieldCount || version !in setOf("1", "2") || fields[CURSOR_SELECTOR_INDEX] != selector) {
    invalidWorkPaginationRequest("Continuation cursor does not match the requested list.")
  }
  if (fields[positionIndex].isBlank()) invalidWorkPaginationRequest("Empty continuation position.")
  val anchor = cursorTime(fields[CURSOR_TIME_INDEX], observed)
  val observation = if (observed) cursorObservation(fields[CURSOR_POSITION_INDEX]) else null
  return WorkPageCursor(anchor, fields[positionIndex], observation)
}

private fun cursorTime(
  value: String,
  observed: Boolean,
): Instant =
  if (!observed) {
    value.toLongOrNull()?.let(Instant::ofEpochMilli)
      ?: invalidWorkPaginationRequest("Invalid list observation time.")
  } else {
    try {
      Instant.parse(value)
    } catch (_: DateTimeParseException) {
      invalidWorkPaginationRequest("Invalid list observation time.")
    }
  }

private fun cursorObservation(value: String): WorkStoreObservation {
  val token = value.split(':', limit = 2)
  val revision =
    token.getOrNull(1)?.toLongOrNull()
      ?: invalidWorkPaginationRequest("Invalid store observation.")
  if (token.first().isBlank() || revision < 0) invalidWorkPaginationRequest("Invalid store observation.")
  return WorkStoreObservation(token.first(), revision)
}

internal fun encodeWorkPageCursor(
  selector: String,
  observedAt: Instant,
  position: String,
  observation: WorkStoreObservation? = null,
): String =
  Base64.getUrlEncoder().withoutPadding().encodeToString(
    (
      if (observation == null) {
        listOf("1", selector, observedAt.toEpochMilli().toString(), position)
      } else {
        listOf("2", selector, observedAt.toString(), "${observation.storeId}:${observation.revision}", position)
      }
    )
      .joinToString("\n").toByteArray(Charsets.UTF_8),
  )

internal fun invalidWorkPaginationRequest(message: String): Nothing =
  throw SkillBillRuntimeException(WorkPaginationFailureCode.INVALID_WORK_PAGINATION_REQUEST, message)
