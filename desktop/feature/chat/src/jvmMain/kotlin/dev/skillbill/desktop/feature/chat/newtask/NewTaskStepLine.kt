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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.sp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme

internal val NEW_TASK_STEPS = listOf("plan", "implement", "audit", "review", "validate", "qa", "pr")

private const val AMBER_UNTIL = 0.10f
private const val GREY_FROM = 0.16f
private const val CYCLE_MS = 6300
private const val GLOW_ALPHA = .16f
private const val GOAL_LINE_ALPHA = .5f
private val DOT_DIAMETER = 7.dp
private val GLOW_WIDTH = 3.dp
private val MIN_LINE_WIDTH = 460.dp

/** Both values run from 0 to 1. */
internal data class DotFrame(val amber: Float, val glow: Float)

/** The 0.9s stagger is exactly 1/7 of the 6.3s cycle, so each step's phase is [progress] shifted by [step] sevenths. */
internal fun idleDotFrame(step: Int, progress: Float): DotFrame {
  val phase = (progress - step / 7f).mod(1f)
  val amber = when {
    phase <= AMBER_UNTIL -> 1f
    phase < GREY_FROM -> (GREY_FROM - phase) / (GREY_FROM - AMBER_UNTIL)
    else -> 0f
  }
  return DotFrame(amber, amber)
}

internal fun stepDotFrame(step: Int, progress: Float?, reducedMotion: Boolean): DotFrame = when {
  reducedMotion -> DotFrame(if (step == 0) 1f else 0f, 0f)
  progress != null -> idleDotFrame(step, progress)
  else -> DotFrame(0f, 0f)
}

@Composable
internal fun rememberIdleProgress(): State<Float> =
  rememberInfiniteTransition(label = "new-task-idle").animateFloat(
    initialValue = 0f,
    targetValue = 1f,
    animationSpec = infiniteRepeatable(tween(CYCLE_MS, easing = LinearEasing), RepeatMode.Restart),
    label = "new-task-idle-progress",
  )

/** Reads [progress] only while drawing, so a frame redraws the dots and never recomposes the screen. */
@Composable
internal fun StepLine(progress: State<Float>?, reducedMotion: Boolean, preview: NewTaskRoutePreview?) {
  val colors = SkillBillTheme.colors
  Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
      Row(Modifier.horizontalScroll(rememberScrollState()).width(max(MIN_LINE_WIDTH, maxWidth))) {
        NEW_TASK_STEPS.forEachIndexed { step, label ->
          val lit = preview?.lights(label) == true
          Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            StepDot(step, progress, reducedMotion, preview, lit)
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
private fun StepDot(step: Int, progress: State<Float>?, reducedMotion: Boolean, preview: NewTaskRoutePreview?, lit: Boolean) {
  val colors = SkillBillTheme.colors
  Canvas(Modifier.fillMaxWidth().height(DOT_DIAMETER + GLOW_WIDTH * 2)) {
    val frame = when {
      preview == null -> stepDotFrame(step, progress?.value, reducedMotion)
      lit -> DotFrame(1f, 1f)
      else -> DotFrame(0f, 0f)
    }
    val lineStart = if (step == 0) center.x else 0f
    val lineEnd = if (step == NEW_TASK_STEPS.lastIndex) center.x else size.width
    val line = if (preview == NewTaskRoutePreview.Goal) colors.accent.copy(alpha = GOAL_LINE_ALPHA) else colors.border
    drawLine(line, Offset(lineStart, center.y), Offset(lineEnd, center.y), 1.dp.toPx())
    val radius = DOT_DIAMETER.toPx() / 2
    if (frame.glow > 0f) drawCircle(colors.accent.copy(alpha = GLOW_ALPHA * frame.glow), radius + GLOW_WIDTH.toPx())
    drawCircle(lerp(colors.stripIdle, colors.accent, frame.amber), radius)
  }
}
