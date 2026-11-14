package dev.skillbill.desktop.app.onboarding

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateOffsetAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.LocalReducedMotion
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.layeredShadow
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/** The demo cursor: a pointer that travels to its target and plays a press ripple on each `presses` increment. */
@Composable
internal fun DemoCursorLayer(cursor: DemoCursor?, modifier: Modifier = Modifier) {
  val reduced = LocalReducedMotion.current
  if (reduced) return
  val colors = SkillBillTheme.colors
  val targets = LocalTourTargets.current
  val density = LocalDensity.current
  val rect = cursor?.let { targets?.rects?.get(it.target) }
  val point = rect?.let {
    Offset(it.center.x - with(density) { 4.dp.toPx() }, it.center.y - with(density) { 2.dp.toPx() })
  }

  var lastPoint by remember { mutableStateOf(Offset(900f, 560f)) }
  val pos by animateOffsetAsState(point ?: lastPoint, tween(950, easing = TOUR_MOVE_EASING), label = "cursor-pos")
  LaunchedEffect(point) { if (point != null) lastPoint = point }
  val alpha by animateFloatAsState(if (point != null) 1f else 0f, tween(350), label = "cursor-alpha")

  val pressScale = remember { Animatable(1f) }
  val ripple = remember { Animatable(1f) }
  LaunchedEffect(cursor?.presses) {
    if ((cursor?.presses ?: 0) <= 0) return@LaunchedEffect
    launch { pressScale.animateTo(.82f, tween(60)); pressScale.animateTo(1f, tween(60)) }
    launch {
      ripple.snapTo(0f)
      ripple.animateTo(1f, tween(550, easing = CubicBezierEasing(0f, 0f, .2f, 1f)))
    }
  }

  Canvas(modifier.fillMaxSize()) {
    if (alpha <= 0f) return@Canvas
    val unit = 1.dp.toPx()
    val path = Path().apply {
      moveTo(4f * unit, 2.5f * unit)
      lineTo(18f * unit, 10.5f * unit)
      lineTo(11.6f * unit, 12.2f * unit)
      lineTo(8.6f * unit, 19f * unit)
      close()
    }
    withTransform({ translate(pos.x + 1.5f * unit, pos.y + 3f * unit) }) {
      drawPath(path, Color.Black.copy(alpha = .5f * alpha))
    }
    withTransform({
      translate(pos.x, pos.y)
      scale(pressScale.value, pressScale.value, pivot = Offset(11f * unit, 11f * unit))
    }) {
      drawPath(path, colors.fg.copy(alpha = alpha))
      drawPath(path, colors.bgFrame.copy(alpha = alpha), style = Stroke(1.4f * unit))
    }
    if (ripple.value < 1f) {
      drawCircle(
        colors.accent.copy(alpha = .9f * (1f - ripple.value) * alpha),
        radius = 15f * unit * (.3f + 1.2f * ripple.value),
        center = pos + Offset(3f * unit, 2f * unit),
        style = Stroke(2f * unit),
      )
    }
  }
}

/**
 * A snapshot flying from one target's rect to another's, through a compact midpoint, over 950 ms.
 * A new [CardFlight.id] restarts the flight; reduced motion draws nothing.
 */
@Composable
internal fun CardFlightLayer(flight: CardFlight?, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
  val reduced = LocalReducedMotion.current
  if (reduced || flight == null) return
  val colors = SkillBillTheme.colors
  val targets = LocalTourTargets.current
  val density = LocalDensity.current
  val from = targets?.rects?.get(flight.from) ?: return
  val to = targets?.rects?.get(flight.to) ?: return

  val progress = remember(flight.id) { Animatable(0f) }
  LaunchedEffect(flight.id) { progress.animateTo(1f, tween(950, easing = TOUR_MOVE_EASING)) }
  val p = progress.value
  val midTop = minOf(from.top, to.top) - with(density) { 40.dp.toPx() }
  val midLeft = (from.left + to.left) / 2f
  val mid = Rect(midLeft, midTop, midLeft + with(density) { 260.dp.toPx() }, midTop + with(density) { 36.dp.toPx() })
  val (rect, alpha) = if (p <= .55f) {
    lerpRect(from, mid, p / .55f) to 1f
  } else {
    val t = (p - .55f) / .45f
    lerpRect(mid, to, t) to (1f - t)
  }

  val shape = RoundedCornerShape(10.dp)
  Box(modifier.fillMaxSize()) {
    Box(
      Modifier.offset { IntOffset(rect.left.roundToInt(), rect.top.roundToInt()) }
        .requiredSize(with(density) { rect.width.toDp() }, with(density) { rect.height.toDp() })
        .graphicsLayer { this.alpha = alpha }
        .layeredShadow(SkillBillTheme.shadows.popover, shape)
        .clip(shape)
        .background(colors.bgOverlay)
        .border(1.dp, colors.accentRing, shape)
        .padding(horizontal = 12.dp),
      contentAlignment = Alignment.CenterStart,
    ) {
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { content() }
    }
  }
}

private fun lerpRect(a: Rect, b: Rect, t: Float): Rect = Rect(
  a.left + (b.left - a.left) * t,
  a.top + (b.top - a.top) * t,
  a.right + (b.right - a.right) * t,
  a.bottom + (b.bottom - a.bottom) * t,
)

/** One large key and its label pinned to the frame's bottom center; `presses` increments play a press. */
@Composable
internal fun KeycapLayer(keycap: Keycap?, modifier: Modifier = Modifier) {
  if (keycap == null) return
  val colors = SkillBillTheme.colors
  val type = SkillBillTheme.type
  val reduced = LocalReducedMotion.current
  val keyShape = RoundedCornerShape(12.dp)
  val press = remember(keycap.key, keycap.label) { Animatable(1f) }
  LaunchedEffect(keycap.presses) {
    if (reduced || keycap.presses <= 0) return@LaunchedEffect
    press.animateTo(.88f, tween(80))
    press.animateTo(1f, tween(160))
  }
  var entered by remember(keycap.key, keycap.label) { mutableStateOf(reduced) }
  LaunchedEffect(keycap.key, keycap.label) { entered = true }
  val alpha by animateFloatAsState(if (entered) 1f else 0f, if (reduced) snap() else tween(400), label = "kc-alpha")
  val rise by animateDpAsState(
    if (entered) 0.dp else 12.dp,
    if (reduced) snap() else tween(550, easing = CubicBezierEasing(.34f, 1.56f, .64f, 1f)),
    label = "kc-rise",
  )

  Box(
    modifier.fillMaxSize().graphicsLayer { this.alpha = alpha; translationY = rise.toPx() },
    contentAlignment = Alignment.BottomCenter,
  ) {
    Column(
      Modifier.padding(bottom = 24.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Box(
        Modifier.defaultMinSize(minWidth = 58.dp, minHeight = 58.dp)
          .graphicsLayer { scaleX = press.value; scaleY = press.value }
          .layeredShadow(SkillBillTheme.shadows.popover, keyShape)
          .background(colors.bgOverlay, keyShape)
          .border(1.dp, colors.borderStrong, keyShape)
          .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
      ) {
        BasicText(keycap.key, style = type.t2xl.copy(color = colors.fg))
      }
      BasicText(
        keycap.label,
        Modifier.background(colors.bgOverlay, RoundedCornerShape(6.dp))
          .border(1.dp, colors.border, RoundedCornerShape(6.dp))
          .padding(horizontal = 8.dp, vertical = 3.dp),
        style = type.tXs.copy(color = colors.fgMuted),
      )
    }
  }
}
