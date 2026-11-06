package dev.skillbill.desktop.core.designsystem.component

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.LocalReducedMotion
import dev.skillbill.desktop.core.designsystem.SkillBillTheme

@Composable
fun SbSpinner(
  size: Dp = 14.dp,
  color: Color = SkillBillTheme.colors.stActive,
  contentDescription: String? = null,
  modifier: Modifier = Modifier,
  durationMillis: Int = 1_000,
  trackAlpha: Float = 0f,
  arcSweep: Float = 270f,
) {
  val reducedMotion = LocalReducedMotion.current
  val rotation = if (reducedMotion) 0f else rememberInfiniteTransition().animateFloat(
    0f,
    360f,
    infiniteRepeatable(tween(durationMillis, easing = LinearEasing), RepeatMode.Restart),
  ).value
  val semantics = if (contentDescription == null) Modifier.clearAndSetSemantics {} else Modifier.semantics { this.contentDescription = contentDescription }
  Canvas(modifier.then(semantics).size(size).graphicsLayer { rotationZ = rotation }) {
    if (trackAlpha > 0f) drawArc(color.copy(alpha = trackAlpha), 0f, 360f, false, style = Stroke(this.size.width / 8f))
    drawArc(color, -45f, arcSweep, false, style = Stroke(this.size.width / 8f, cap = StrokeCap.Round))
  }
}

@Composable
fun Modifier.sbPulse(): Modifier {
  if (LocalReducedMotion.current) return this
  val animatedAlpha = rememberInfiniteTransition().animateFloat(
    1f,
    .45f,
    infiniteRepeatable(tween(800, easing = FastOutSlowInEasing), RepeatMode.Reverse),
  ).value
  return this.alpha(animatedAlpha)
}
