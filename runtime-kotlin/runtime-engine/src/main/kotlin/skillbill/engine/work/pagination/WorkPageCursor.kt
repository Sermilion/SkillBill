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
private const val SCOPED_CURSOR_FIELD_COUNT = 5
private const val CURSOR_SELECTOR_INDEX = 1
private const val CURSOR_TIME_INDEX = 2
private const val CURSOR_POSITION_INDEX = 3
private const val CURSOR_VERSION_LEGACY = "1"
private const val CURSOR_VERSION_OBSERVED = "2"
private const val CURSOR_VERSION_SCOPED = "3"

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
  val fields = decoded.split('\n', limit = SCOPED_CURSOR_FIELD_COUNT)
  val scoped = version == CURSOR_VERSION_SCOPED
  val observed = version == CURSOR_VERSION_OBSERVED || scoped
  val fieldCount = cursorFieldCount(scoped, observed, fields.size)
  val positionIndex =
    if (fieldCount == OBSERVED_CURSOR_FIELD_COUNT) OBSERVED_CURSOR_POSITION_INDEX else CURSOR_POSITION_INDEX
  val decodedSelector =
    if (scoped) decodeScopedField(fields.getOrNull(CURSOR_SELECTOR_INDEX)) else fields.getOrNull(CURSOR_SELECTOR_INDEX)
  if (!validCursorFields(fields.size, fieldCount, version, decodedSelector, selector)) {
    invalidWorkPaginationRequest("Continuation cursor does not match the requested list.")
  }
  if (fields[positionIndex].isBlank()) invalidWorkPaginationRequest("Empty continuation position.")
  val anchor = cursorTime(fields[CURSOR_TIME_INDEX], observed)
  val position =
    (if (scoped) decodeScopedField(fields[positionIndex]) else fields[positionIndex])
      ?: invalidWorkPaginationRequest("Invalid continuation position.")
  val observation = decodedObservation(version, scoped, fieldCount, fields)
  return WorkPageCursor(anchor, position, observation)
}

private fun decodedObservation(
  version: String,
  scoped: Boolean,
  fieldCount: Int,
  fields: List<String>,
): WorkStoreObservation? =
  when {
    version == CURSOR_VERSION_OBSERVED -> cursorObservation(fields[CURSOR_POSITION_INDEX])
    scoped && fieldCount == OBSERVED_CURSOR_FIELD_COUNT -> cursorObservation(fields[CURSOR_POSITION_INDEX])
    else -> null
  }

private fun cursorFieldCount(
  scoped: Boolean,
  observed: Boolean,
  actual: Int,
): Int =
  when {
    scoped -> actual
    observed -> OBSERVED_CURSOR_FIELD_COUNT
    else -> CURSOR_FIELD_COUNT
  }

private fun validCursorFields(
  size: Int,
  count: Int,
  version: String,
  decodedSelector: String?,
  selector: String,
): Boolean =
  validCursorCount(size, count) &&
    version in setOf(CURSOR_VERSION_LEGACY, CURSOR_VERSION_OBSERVED, CURSOR_VERSION_SCOPED) &&
    decodedSelector == selector

private fun validCursorCount(
  size: Int,
  count: Int,
): Boolean = size == count && count in setOf(CURSOR_FIELD_COUNT, OBSERVED_CURSOR_FIELD_COUNT)

private fun decodeScopedField(value: String?): String? =
  try {
    value?.takeIf { it.length % BASE64_GROUP_SIZE != 1 && it.matches(Regex("[A-Za-z0-9_-]*")) }
      ?.let { Base64.getUrlDecoder().decode(it).toString(Charsets.UTF_8) }
  } catch (_: IllegalArgumentException) {
    null
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
      if ('\n' in selector) {
        val encodedSelector =
          Base64.getUrlEncoder().withoutPadding().encodeToString(selector.toByteArray(Charsets.UTF_8))
        val encodedPosition =
          Base64.getUrlEncoder().withoutPadding().encodeToString(position.toByteArray(Charsets.UTF_8))
        if (observation == null) {
          listOf("3", encodedSelector, observedAt.toString(), encodedPosition)
        } else {
          listOf(
            "3",
            encodedSelector,
            observedAt.toString(),
            "${observation.storeId}:${observation.revision}",
            encodedPosition,
          )
        }
      } else if (observation == null) {
        listOf("1", selector, observedAt.toEpochMilli().toString(), position)
      } else {
        listOf("2", selector, observedAt.toString(), "${observation.storeId}:${observation.revision}", position)
      }
    )
      .joinToString("\n").toByteArray(Charsets.UTF_8),
  )

internal fun invalidWorkPaginationRequest(message: String): Nothing =
  throw SkillBillRuntimeException(WorkPaginationFailureCode.INVALID_WORK_PAGINATION_REQUEST, message)
