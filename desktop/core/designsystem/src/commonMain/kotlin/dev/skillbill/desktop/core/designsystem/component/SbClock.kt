package dev.skillbill.desktop.core.designsystem.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.text.TextStyle
import dev.skillbill.desktop.core.designsystem.LocalContentColor
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.tabular
import kotlin.time.Instant

@OptIn(kotlin.time.ExperimentalTime::class)
internal fun formatActiveDuration(ms: Long?, showSeconds: Boolean): String {
  if (ms == null) return "—"
  val seconds = (ms / 1_000).coerceAtLeast(0)
  val hours = seconds / 3_600
  val minutes = (seconds % 3_600) / 60
  val remainder = seconds % 60
  return when {
    hours > 0 -> "${hours}h ${minutes.toString().padStart(2, '0')}m"
    minutes > 0 && showSeconds -> "${minutes}m ${remainder.toString().padStart(2, '0')}s"
    minutes > 0 -> "${minutes}m"
    else -> "${seconds}s"
  }
}

@Composable
@OptIn(kotlin.time.ExperimentalTime::class)
fun SbClock(activeDurationMs: Long?, activeDurationAsOf: Instant?, live: Boolean, showSeconds: Boolean = false, style: TextStyle = SkillBillTheme.type.tSm) {
  val ticker = LocalUiTicker.current
  val ticking = live && activeDurationAsOf != null
  if (ticking) DisposableEffect(ticker) { ticker.retain(); onDispose { ticker.release() } }
  val elapsed = if (ticking) activeDurationMs?.plus((ticker.now.value - activeDurationAsOf!!).inWholeMilliseconds) else activeDurationMs
  BasicText(formatActiveDuration(elapsed, showSeconds), style = style.tabular().copy(color = LocalContentColor.current), maxLines = 1)
}
