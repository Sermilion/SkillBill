package dev.skillbill.desktop.feature.tray

import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.time.Instant
import kotlin.time.toJavaInstant

private val clockTime = DateTimeFormatter.ofPattern("HH:mm")
private val dayAndTime = DateTimeFormatter.ofPattern("MMM d HH:mm", Locale.getDefault())

internal actual fun trayFinishedTimeText(finishedAt: Instant, now: Instant): String {
  val zone = ZoneId.systemDefault()
  val finished = finishedAt.toJavaInstant().atZone(zone)
  val today = now.toJavaInstant().atZone(zone).toLocalDate()
  return when (finished.toLocalDate()) {
    today -> finished.format(clockTime)
    today.minusDays(1) -> "Yesterday ${finished.format(clockTime)}"
    else -> finished.format(dayAndTime)
  }
}
