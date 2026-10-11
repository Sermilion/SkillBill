package skillbill.engine.work.pagination

import skillbill.error.core.RuntimeFailureCode
import skillbill.error.core.SkillBillRuntimeException
import java.time.Instant
import java.util.Base64

private const val MAX_CURSOR_LENGTH = 16384
private const val BASE64_GROUP_SIZE = 4
private const val CURSOR_FIELD_COUNT = 4
private const val CURSOR_SELECTOR_INDEX = 1
private const val CURSOR_TIME_INDEX = 2
private const val CURSOR_POSITION_INDEX = 3

internal enum class WorkPaginationFailureCode : RuntimeFailureCode {
  INVALID_WORK_PAGINATION_REQUEST,
}

internal data class WorkPageCursor(val observedAt: Instant, val position: String)

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
  val fields = decoded.split('\n', limit = CURSOR_FIELD_COUNT)
  if (fields.size != CURSOR_FIELD_COUNT || fields[0] != "1" || fields[CURSOR_SELECTOR_INDEX] != selector) {
    invalidWorkPaginationRequest("Continuation cursor does not match the requested list.")
  }
  if (fields[CURSOR_POSITION_INDEX].isBlank()) invalidWorkPaginationRequest("Empty continuation position.")
  val anchor =
    fields[CURSOR_TIME_INDEX].toLongOrNull()
      ?: invalidWorkPaginationRequest("Invalid list observation time.")
  return WorkPageCursor(Instant.ofEpochMilli(anchor), fields[CURSOR_POSITION_INDEX])
}

internal fun encodeWorkPageCursor(
  selector: String,
  observedAt: Instant,
  position: String,
): String =
  Base64.getUrlEncoder().withoutPadding().encodeToString(
    listOf("1", selector, observedAt.toEpochMilli().toString(), position)
      .joinToString("\n").toByteArray(Charsets.UTF_8),
  )

internal fun invalidWorkPaginationRequest(message: String): Nothing =
  throw SkillBillRuntimeException(WorkPaginationFailureCode.INVALID_WORK_PAGINATION_REQUEST, message)
