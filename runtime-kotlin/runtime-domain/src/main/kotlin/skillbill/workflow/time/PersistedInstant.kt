package skillbill.workflow.time

import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

fun parsePersistedInstant(value: String): Instant =
  parsePersistedInstantOrNull(value)
    ?: throw IllegalArgumentException("Timestamp is not a supported persisted instant.")

fun parsePersistedInstantOrNull(value: String): Instant? =
  try {
    Instant.parse(value)
  } catch (_: DateTimeParseException) {
    try {
      OffsetDateTime.parse(value).toInstant()
    } catch (_: DateTimeParseException) {
      try {
        LocalDateTime.parse(value.replace(' ', 'T'), DateTimeFormatter.ISO_LOCAL_DATE_TIME).toInstant(ZoneOffset.UTC)
      } catch (_: DateTimeParseException) {
        null
      }
    }
  }
