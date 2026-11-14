package dev.skillbill.desktop.app.onboarding

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.LocalReducedMotion
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.Kbd
import dev.skillbill.desktop.core.designsystem.layeredShadow
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

internal val TOUR_MOVE_EASING = CubicBezierEasing(.65f, 0f, .25f, 1f)
private val TIP_GAP = 14.dp
private val TIP_MAX_WIDTH = 250.dp

/** The scrim with a rounded cut-out over the spotlight's target, plus its accent ring and breathing glow. */
@Composable
internal fun SpotlightLayer(spotlight: Spotlight?, modifier: Modifier = Modifier) {
  val colors = SkillBillTheme.colors
  val reduced = LocalReducedMotion.current
  val targets = LocalTourTargets.current
  val rect = spotlight?.let { targets?.rects?.get(it.target) }

  var lastRect by remember { mutableStateOf(Rect(560f, 340f, 560f, 340f)) }
  var lastSpot by remember { mutableStateOf<Spotlight?>(null) }
  SideEffect {
    if (rect != null) lastRect = rect
    if (spotlight != null) lastSpot = spotlight
  }
  val spec: FiniteAnimationSpec<Float> = if (reduced) snap() else tween(600, easing = TOUR_MOVE_EASING)
  val left by animateFloatAsState(lastRect.left, spec, label = "spot-left")
  val top by animateFloatAsState(lastRect.top, spec, label = "spot-top")
  val right by animateFloatAsState(lastRect.right, spec, label = "spot-right")
  val bottom by animateFloatAsState(lastRect.bottom, spec, label = "spot-bottom")
  val alpha by animateFloatAsState(if (rect != null) 1f else 0f, if (reduced) snap() else tween(500), label = "spot-alpha")
  val glow = if (reduced) {
    0f
  } else {
    rememberInfiniteTransition(label = "spot-glow").animateFloat(
      .08f, .22f, infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "spot-glow-pulse",
    ).value
  }
  Canvas(modifier.fillMaxSize()) {
    if (alpha <= 0f) return@Canvas
    val pad = (lastSpot?.pad ?: 0.dp).toPx()
    val hole = Rect(left, top, right, bottom).inflate(pad)
    if (hole.width <= 0f || hole.height <= 0f) {
      drawRect(colors.scrim.copy(alpha = colors.scrim.alpha * alpha))
      return@Canvas
    }
    val corner = CornerRadius(
      (lastSpot?.radius ?: 0.dp).toPx().coerceIn(0f, min(hole.width, hole.height) / 2f),
    )
    val path = Path().apply { addRoundRect(RoundRect(hole, corner)) }
    clipPath(path, ClipOp.Difference) { drawRect(colors.scrim.copy(alpha = colors.scrim.alpha * alpha)) }
    drawRoundRect(
      colors.accentRing.copy(alpha = alpha), hole.topLeft, hole.size, corner,
      style = Stroke(1.5.dp.toPx()),
    )
    if (glow > 0f) {
      drawRoundRect(
        colors.accent.copy(alpha = glow * alpha), hole.topLeft, hole.size, corner,
        style = Stroke(5.dp.toPx()),
      )
    }
  }
}

/**
 * The tip bubble anchored to the spotlight's target rect. It appears 300 ms after the first spotlight
 * of a scene, 520 ms after a move; under reduced motion it snaps with no delay.
 */
@Composable
internal fun TipBubble(spotlight: Spotlight?, modifier: Modifier = Modifier) {
  val tip = spotlight?.tip ?: return
  val targets = LocalTourTargets.current
  val rect = targets?.rects?.get(spotlight.target) ?: return
  val colors = SkillBillTheme.colors
  val type = SkillBillTheme.type
  val reduced = LocalReducedMotion.current

  var shown by remember { mutableStateOf(false) }
  var hadSpot by remember { mutableStateOf(false) }
  LaunchedEffect(spotlight) {
    shown = false
    val delayMs = if (reduced) 0 else if (hadSpot) 520 else 300
    if (delayMs > 0) delay(delayMs.toLong())
    shown = true
    hadSpot = true
  }
  val alpha by animateFloatAsState(if (shown) 1f else 0f, if (reduced) snap() else tween(300), label = "tip-alpha")
  val rise by animateDpAsState(
    if (shown) 0.dp else 4.dp,
    if (reduced) snap() else tween(450, easing = CubicBezierEasing(.16f, 1f, .3f, 1f)),
    label = "tip-rise",
  )

  val shape = RoundedCornerShape(9.dp)
  Layout(
    content = {
      Column(
        Modifier.widthIn(max = TIP_MAX_WIDTH)
          .layeredShadow(SkillBillTheme.shadows.popover, shape)
          .background(colors.bgOverlay, shape)
          .border(1.dp, colors.borderStrong, shape)
          .padding(horizontal = 11.dp, vertical = 9.dp),
      ) {
        BasicText(tip.title, style = type.tSmMedium.copy(color = colors.fg))
        BasicText(tip.body, Modifier.padding(top = 2.dp), style = type.tSm.copy(color = colors.fg2))
        if (tip.keys.isNotEmpty()) {
          Row(Modifier.padding(top = 7.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            tip.keys.forEach { Kbd(it) }
          }
        }
      }
    },
    modifier = modifier.graphicsLayer { this.alpha = alpha; translationY = rise.toPx() },
  ) { measurables, constraints ->
    val maxWidth = min(TIP_MAX_WIDTH.roundToPx(), constraints.maxWidth)
    val placeable = measurables[0].measure(Constraints(maxWidth = maxWidth, maxHeight = constraints.maxHeight))
    val gap = TIP_GAP.roundToPx()
    val pad = spotlight.pad.roundToPx()
    val w = placeable.width
    val h = placeable.height
    val x = when (spotlight.side) {
      TipSide.Right -> rect.right + pad + gap
      TipSide.Left -> rect.left - pad - gap - w
      TipSide.BottomLeft -> rect.left + 24.dp.toPx()
      TipSide.Top, TipSide.Bottom -> rect.left + rect.width / 2f - w / 2f
    }.coerceIn(10f, (constraints.maxWidth - w - 10f).coerceAtLeast(10f))
    val y = when (spotlight.side) {
      TipSide.Right, TipSide.Left -> rect.top + rect.height / 2f - h / 2f
      TipSide.Top -> rect.top - pad - gap - h
      TipSide.Bottom, TipSide.BottomLeft -> rect.bottom + pad + gap
    }.coerceIn(10f, (constraints.maxHeight - h - 10f).coerceAtLeast(10f))
    layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(x.roundToInt(), y.roundToInt()) }
  }
}
