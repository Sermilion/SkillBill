package dev.skillbill.desktop.core.designsystem.status

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.LocalReducedMotion

/** Draws a filled or hollow status dot, with an optional pulse. The default size is 6 dp. */
@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier, hollow: Boolean = false, pulse: Boolean = false, size: Dp = 6.dp) {
  val alpha = if (pulse && !LocalReducedMotion.current) {
    rememberInfiniteTransition(label = "status-dot").animateFloat(
      initialValue = 1f,
      targetValue = 0.35f,
      animationSpec = infiniteRepeatable(tween(800, easing = FastOutSlowInEasing), RepeatMode.Reverse),
      label = "status-dot-alpha",
    ).value
  } else {
    1f
  }
  Box(modifier.size(size).alpha(alpha).then(if (hollow) Modifier.border(1.dp, color, CircleShape) else Modifier.background(color, CircleShape)))
}
