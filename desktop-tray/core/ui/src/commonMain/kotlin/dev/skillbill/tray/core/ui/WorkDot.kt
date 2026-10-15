package dev.skillbill.tray.core.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import dev.skillbill.tray.core.domain.model.Lifecycle
import kotlinx.coroutines.delay
import kotlin.time.Clock
import kotlin.time.Instant

@Composable
fun WorkDot(lifecycle: Lifecycle, modifier: Modifier = Modifier) {
  Canvas(modifier.size(if (lifecycle == Lifecycle.BLOCKED) 8.dp else 7.dp)) {
    val color = lifecycleColor(lifecycle)
    if (lifecycle == Lifecycle.PAUSED || lifecycle == Lifecycle.BLOCKED || lifecycle == Lifecycle.IDLE) {
      val stroke = (if (lifecycle == Lifecycle.BLOCKED) 2.dp else 1.5.dp).toPx()
      drawCircle(color, radius = (size.minDimension - stroke) / 2, style = Stroke(stroke))
    } else drawCircle(color)
  }
}

/** The current time, advanced once a second, for clocks that tick while their work is live. */
@Composable
fun tickingNow(clock: Clock = Clock.System): State<Instant> {
  val now = remember(clock) { mutableStateOf(clock.now()) }
  LaunchedEffect(clock) {
    while (true) {
      delay(1000)
      now.value = clock.now()
    }
  }
  return now
}
