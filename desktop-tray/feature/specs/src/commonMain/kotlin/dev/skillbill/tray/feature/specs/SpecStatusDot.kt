package dev.skillbill.tray.feature.specs

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.skillbill.tray.core.designsystem.amber
import dev.skillbill.tray.core.designsystem.dim
import dev.skillbill.tray.core.designsystem.green
import dev.skillbill.tray.core.designsystem.orange
import dev.skillbill.tray.core.domain.model.SpecStatus

private const val PULSE_MILLIS = 900
private const val PULSE_LOW_ALPHA = 0.35f
private const val HALF_SWEEP = 180f
private const val BOTTOM_ANGLE = 90f

/** The status colour shared by the dot, the progress text and the folder's segmented bar. */
internal fun statusColor(status: SpecStatus): Color = when (status) {
  SpecStatus.COMPLETE -> green
  SpecStatus.IN_PROGRESS, SpecStatus.RUNNING, SpecStatus.PAUSING, SpecStatus.PAUSED -> amber
  SpecStatus.PENDING, SpecStatus.CANCELED -> dim
  SpecStatus.BLOCKED -> orange
}

internal fun statusWords(status: SpecStatus): String = when (status) {
  SpecStatus.COMPLETE -> "Complete"
  SpecStatus.IN_PROGRESS -> "In progress"
  SpecStatus.RUNNING -> "Running"
  SpecStatus.PAUSING -> "Pausing"
  SpecStatus.PAUSED -> "Paused"
  SpecStatus.PENDING -> "Pending"
  SpecStatus.BLOCKED -> "Blocked"
  SpecStatus.CANCELED -> "Canceled"
}

/**
 * One status as a ~10dp mark: a filled dot when complete, a half-filled amber dot while in progress or paused (pulsing while the
 * run is live), an empty ring when pending, a thick orange ring when blocked and a short dash when canceled.
 */
@Composable
fun SpecStatusDot(status: SpecStatus, modifier: Modifier = Modifier) {
  val pulsing = status == SpecStatus.RUNNING || status == SpecStatus.PAUSING
  val pulse = if (pulsing) pulseAlpha() else null
  val color = statusColor(status)
  Canvas(modifier.size(10.dp).semantics { contentDescription = statusWords(status) }) {
    val alpha = pulse?.value ?: 1f
    when (status) {
      SpecStatus.COMPLETE -> drawCircle(color)
      SpecStatus.IN_PROGRESS, SpecStatus.PAUSED, SpecStatus.RUNNING, SpecStatus.PAUSING -> {
        val stroke = 1.5.dp.toPx()
        drawCircle(color.copy(alpha = alpha), radius = (size.minDimension - stroke) / 2, style = Stroke(stroke))
        drawArc(color.copy(alpha = alpha), BOTTOM_ANGLE, HALF_SWEEP, useCenter = true)
      }
      SpecStatus.PENDING -> {
        val stroke = 1.5.dp.toPx()
        drawCircle(color, radius = (size.minDimension - stroke) / 2, style = Stroke(stroke))
      }
      SpecStatus.BLOCKED -> {
        val stroke = 2.5.dp.toPx()
        drawCircle(color, radius = (size.minDimension - stroke) / 2, style = Stroke(stroke))
      }
      SpecStatus.CANCELED -> {
        val inset = size.width * 0.2f
        drawLine(color, Offset(inset, size.height / 2), Offset(size.width - inset, size.height / 2), strokeWidth = 1.5.dp.toPx(), cap = StrokeCap.Round)
      }
    }
  }
}

/** The pulse is a Compose infinite transition: it runs only while the dot is composed and needs no timer. */
@Composable
private fun pulseAlpha(): State<Float> {
  val transition = rememberInfiniteTransition(label = "spec-status-pulse")
  return transition.animateFloat(
    initialValue = PULSE_LOW_ALPHA,
    targetValue = 1f,
    animationSpec = infiniteRepeatable(tween(PULSE_MILLIS), RepeatMode.Reverse),
    label = "spec-status-alpha",
  )
}
