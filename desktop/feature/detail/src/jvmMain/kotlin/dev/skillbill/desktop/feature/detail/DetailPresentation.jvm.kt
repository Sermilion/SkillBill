package dev.skillbill.desktop.feature.detail

import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.time.Instant
import kotlin.time.toJavaInstant

internal actual fun formatInstant(instant: Instant, pattern: String): String =
  DateTimeFormatter.ofPattern(pattern, Locale.getDefault()).format(instant.toJavaInstant().atZone(ZoneId.systemDefault()))

internal actual fun abbreviateHome(path: String): String {
  val home = System.getProperty("user.home").orEmpty()
  return if (home.isNotEmpty() && (path == home || path.startsWith("$home/"))) "~" + path.removePrefix(home) else path
}
