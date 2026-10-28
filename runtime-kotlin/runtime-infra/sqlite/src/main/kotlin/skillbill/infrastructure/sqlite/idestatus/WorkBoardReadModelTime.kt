package skillbill.infrastructure.sqlite.idestatus

import java.time.DateTimeException
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

internal object WorkBoardReadModelTime {
  private val formatter = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSSSSS'Z'").withZone(ZoneOffset.UTC)

  fun format(value: Instant): String = formatter.format(value)

  fun parse(value: String): Instant = Instant.from(formatter.parse(value))

  fun parseOrNull(value: String): Instant? =
    try {
      parse(value)
    } catch (_: DateTimeException) {
      null
    }
}
