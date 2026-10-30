package dev.skillbill.desktop.core.designsystem.status

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillColors
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import kotlin.math.PI

@Composable
fun StatusIcon(kind: StatusKind, progress: Float?, large: Boolean = false, modifier: Modifier = Modifier) {
  val colors = SkillBillTheme.colors
  // The SVG re-renders with the new dash length; `.sb-status` declares no transition.
  val fraction = (progress ?: 0.5f).coerceIn(0.12f, 1f)
  Canvas(modifier.size(if (large) 16.dp else 14.dp).semantics { contentDescription = kind.label }) {
    scale(size.width / 14f, pivot = Offset.Zero) {
      val center = Offset(7f, 7f)
      val color = kind.color(colors)
      val ring = Stroke(width = 1.5f)
      when (kind) {
        StatusKind.Active -> {
          drawCircle(color, 5.5f, center, style = ring)
          val sweep = 360f * (fraction * 11f / (2f * PI.toFloat() * 1.75f)).coerceIn(0f, 1f)
          drawArc(color, -90f, sweep, false, Offset(5.25f, 5.25f), Size(3.5f, 3.5f), style = Stroke(3.5f))
        }
        StatusKind.Planning -> drawCircle(color, 5.5f, center, style = Stroke(1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.4f, 1.9f))))
        StatusKind.Stalled -> drawCircle(
          color,
          5.5f,
          center,
          style = Stroke(1.5f, cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(0.6f, 2.6f))),
        )
        StatusKind.Paused -> {
          drawCircle(color, 5.5f, center, style = ring)
          drawRoundRect(color, Offset(4.9f, 4.6f), Size(1.4f, 4.8f), CornerRadius(0.5f))
          drawRoundRect(color, Offset(7.7f, 4.6f), Size(1.4f, 4.8f), CornerRadius(0.5f))
        }
        StatusKind.Blocked -> {
          drawCircle(color, 6.25f, center)
          drawRoundRect(colors.bgPanel, Offset(6.3f, 3.4f), Size(1.4f, 4.4f), CornerRadius(0.7f))
          drawCircle(colors.bgPanel, 0.85f, Offset(7f, 9.9f))
        }
        StatusKind.Failed -> {
          drawCircle(color, 6.25f, center)
          drawLine(colors.bgPanel, Offset(4.9f, 4.9f), Offset(9.1f, 9.1f), 1.5f, cap = StrokeCap.Round)
          drawLine(colors.bgPanel, Offset(9.1f, 4.9f), Offset(4.9f, 9.1f), 1.5f, cap = StrokeCap.Round)
        }
        StatusKind.Canceled -> {
          drawCircle(color, 5.5f, center, style = ring)
          drawLine(color, Offset(4.9f, 4.9f), Offset(9.1f, 9.1f), 1.5f, cap = StrokeCap.Round)
          drawLine(color, Offset(9.1f, 4.9f), Offset(4.9f, 9.1f), 1.5f, cap = StrokeCap.Round)
        }
        StatusKind.Completed -> {
          drawCircle(color, 6.25f, center)
          drawLine(colors.bgPanel, Offset(4.3f, 7.2f), Offset(6.1f, 9f), 1.5f, cap = StrokeCap.Round)
          drawLine(colors.bgPanel, Offset(6.1f, 9f), Offset(9.7f, 5.2f), 1.5f, cap = StrokeCap.Round)
        }
        StatusKind.CompletedNoChange -> {
          drawCircle(color, 5.5f, center, style = ring)
          drawLine(color, Offset(4.6f, 7.2f), Offset(6.2f, 8.8f), 1.4f, cap = StrokeCap.Round)
          drawLine(color, Offset(6.2f, 8.8f), Offset(9.4f, 5.4f), 1.4f, cap = StrokeCap.Round)
        }
        StatusKind.Pending -> drawCircle(color, 5.5f, center, style = ring)
        StatusKind.Skipped -> {
          drawCircle(color, 5.5f, center, style = Stroke(1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.4f, 1.9f))))
          drawLine(color, Offset(4.6f, 9.4f), Offset(9.4f, 4.6f), 1.4f, cap = StrokeCap.Round)
        }
      }
    }
  }
}

/** The state color [StatusIcon] draws [kind] in, for a state word next to the icon. */
@Composable
fun statusColor(kind: StatusKind): Color = kind.color(SkillBillTheme.colors)

private fun StatusKind.color(colors: SkillBillColors): Color = when (this) {
  StatusKind.Active -> colors.stActive
  StatusKind.Planning -> colors.stPlanning
  StatusKind.Stalled, StatusKind.Pending, StatusKind.Skipped, StatusKind.Canceled -> colors.stPending
  StatusKind.Paused -> colors.stPaused
  StatusKind.Blocked -> colors.stBlocked
  StatusKind.Failed -> colors.stFailed
  StatusKind.Completed, StatusKind.CompletedNoChange -> colors.stDone
}
