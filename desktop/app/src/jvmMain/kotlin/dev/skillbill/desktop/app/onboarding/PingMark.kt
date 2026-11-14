package dev.skillbill.desktop.app.onboarding

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.LocalReducedMotion
import dev.skillbill.desktop.core.designsystem.SkillBillTheme

@Composable
internal fun PingMark(bandsShown: Int, ripples: Boolean, modifier: Modifier = Modifier) {
  val reduced = LocalReducedMotion.current
  val colors = SkillBillTheme.colors
  val ring = if (ripples && !reduced) {
    rememberInfiniteTransition().animateFloat(
      initialValue = 0f,
      targetValue = 1f,
      animationSpec = infiniteRepeatable(tween(3200, easing = CubicBezierEasing(.2f, .6f, .3f, 1f))),
      label = "ping-ripple",
    ).value
  } else 0f
  val bandProgress = (1..3).map { index ->
    val progress by animateFloatAsState(
      if (reduced || bandsShown >= index) 1f else 0f,
      animationSpec = if (reduced) androidx.compose.animation.core.snap() else tween(900, easing = CubicBezierEasing(.2f, .9f, .25f, 1.12f)),
      label = "ping-band-$index",
    )
    progress
  }
  Canvas(modifier.size(104.dp)) {
    val unit = size.minDimension / 100f
    val bandCenter = Offset(18.9f * unit, 81.1f * unit)
    clipPath(androidx.compose.ui.graphics.Path().apply {
      addOval(androidx.compose.ui.geometry.Rect(6f * unit, 6f * unit, 94f * unit, 94f * unit))
    }) {
      listOf(13f to 26f, 47f to 22f, 80f to 22f).forEachIndexed { index, (radius, width) ->
        val progress = bandProgress[index]
        if (progress > 0f) {
          val scale = .15f + .85f * progress
          drawCircle(
            color = colors.accent.copy(alpha = progress),
            radius = radius * unit * scale,
            center = bandCenter,
            style = Stroke(width * unit * scale),
          )
        }
      }
    }
    if (ripples && !reduced) repeat(2) { index ->
      val phase = (ring + index * .34375f) % 1f
      drawCircle(
        color = colors.accent.copy(alpha = .45f * (1f - phase)),
        radius = size.minDimension * .5f * (.85f + 1.45f * phase),
        center = Offset(size.width / 2f, size.height / 2f),
        style = Stroke(1.5f * unit),
      )
    }
  }
}
