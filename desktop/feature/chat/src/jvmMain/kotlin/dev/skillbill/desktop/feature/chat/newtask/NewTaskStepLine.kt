package dev.skillbill.desktop.feature.chat.newtask

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.sp
import dev.skillbill.desktop.core.designsystem.SkillBillMotion
import dev.skillbill.desktop.core.designsystem.SkillBillTheme

internal val NEW_TASK_STEPS = listOf("plan", "implement", "audit", "review", "validate", "qa", "pr")

private const val CYCLE_MS = 4200
private const val FADE_IN_UNTIL = .15f
private const val GROWN_AT = .85f
private const val GLOW_ALPHA = .16f
private const val GOAL_LINE_ALPHA = .5f
private val DOT_DIAMETER = 5.dp
private val LIT_DOT_DIAMETER = 7.dp
private val GLOW_WIDTH = 3.dp
private val DOT_ROW_HEIGHT = LIT_DOT_DIAMETER + GLOW_WIDTH * 2
private val MIN_LINE_WIDTH = 460.dp

/** [reach] is the share of the line from the first dot to the last that the sweep covers; both values run from 0 to 1. */
internal data class SweepFrame(val reach: Float, val alpha: Float)

/** The sweep grows to the last dot over 85% of the cycle, fading in over the first 15% and out over the last 15%. */
internal fun sweepFrame(progress: Float): SweepFrame {
  val ease = SkillBillMotion.easing
  val reach = ease.transform((progress / GROWN_AT).coerceIn(0f, 1f))
  val alpha = when {
    progress < FADE_IN_UNTIL -> ease.transform(progress / FADE_IN_UNTIL)
    progress <= GROWN_AT -> 1f
    else -> 1f - ease.transform(((progress - GROWN_AT) / (1f - GROWN_AT)).coerceAtMost(1f))
  }
  return SweepFrame(reach, alpha)
}

@Composable
internal fun rememberIdleProgress(): State<Float> =
  rememberInfiniteTransition(label = "new-task-idle").animateFloat(
    initialValue = 0f,
    targetValue = 1f,
    animationSpec = infiniteRepeatable(tween(CYCLE_MS, easing = LinearEasing), RepeatMode.Restart),
    label = "new-task-idle-progress",
  )

/** Reads [progress] only while drawing, so a frame redraws the line and never recomposes the screen. */
@Composable
internal fun StepLine(progress: State<Float>?, preview: NewTaskRoutePreview?) {
  val colors = SkillBillTheme.colors
  val track = if (preview == NewTaskRoutePreview.Goal) colors.accent.copy(alpha = GOAL_LINE_ALPHA) else colors.border
  Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
      Row(
        Modifier.horizontalScroll(rememberScrollState()).width(max(MIN_LINE_WIDTH, maxWidth)).drawBehind {
          val y = DOT_ROW_HEIGHT.toPx() / 2
          val first = size.width / NEW_TASK_STEPS.size / 2
          val last = size.width - first
          val stroke = 1.dp.toPx()
          drawLine(track, Offset(first, y), Offset(last, y), stroke)
          val sweep = progress?.value?.let(::sweepFrame) ?: return@drawBehind
          val head = first + (last - first) * sweep.reach
          if (head > first && sweep.alpha > 0f) {
            val brush = Brush.horizontalGradient(listOf(Color.Transparent, colors.accent), startX = first, endX = head)
            drawLine(brush, Offset(first, y), Offset(head, y), stroke, alpha = sweep.alpha)
          }
        },
      ) {
        NEW_TASK_STEPS.forEach { label ->
          val lit = preview?.lights(label) == true
          Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            StepDot(lit)
            BasicText(label, style = SkillBillTheme.type.monoSm.copy(fontSize = 11.5.sp, color = if (lit) colors.accentHover else colors.fgMuted))
          }
        }
      }
    }
    routeCaption(preview)?.let { caption ->
      BasicText(
        caption,
        Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        style = SkillBillTheme.type.tSm.copy(color = colors.accentHover),
      )
    }
  }
}

@Composable
private fun StepDot(lit: Boolean) {
  val colors = SkillBillTheme.colors
  Canvas(Modifier.fillMaxWidth().height(DOT_ROW_HEIGHT)) {
    if (lit) {
      val radius = LIT_DOT_DIAMETER.toPx() / 2
      drawCircle(colors.accent.copy(alpha = GLOW_ALPHA), radius + GLOW_WIDTH.toPx())
      drawCircle(colors.accent, radius)
    } else {
      drawCircle(colors.borderStrong, DOT_DIAMETER.toPx() / 2)
    }
  }
}
