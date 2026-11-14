package dev.skillbill.desktop.app.onboarding

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

internal val FRAME_WIDTH = 1120.dp
internal val FRAME_HEIGHT = 680.dp
internal val TOP_BAR_HEIGHT = 52.dp
internal val COMPACT_BELOW = 600.dp
internal val FULL_CAPTION_RESERVE = 140.dp
internal val COMPACT_CAPTION_RESERVE = 82.dp

internal data class TourFrameLayout(val scale: Float, val compact: Boolean, val captionReserve: Dp)

internal fun tourFrameScale(availableWidth: Dp, availableHeight: Dp): Float = minOf(
  1f,
  0.94f * availableWidth.value / FRAME_WIDTH.value,
  (availableHeight.value / FRAME_HEIGHT.value).coerceAtLeast(0f),
)

internal fun tourFrameLayout(windowWidth: Dp, windowHeight: Dp): TourFrameLayout {
  val compact = windowHeight < COMPACT_BELOW
  val reserve = if (compact) COMPACT_CAPTION_RESERVE else FULL_CAPTION_RESERVE
  val scale = tourFrameScale(windowWidth, windowHeight - TOP_BAR_HEIGHT - reserve)
  return TourFrameLayout(scale, compact, reserve)
}

@Composable
internal fun ScaledTourFrame(scale: Float, content: @Composable BoxScope.() -> Unit) {
  Box(
    Modifier.layout { measurable, constraints ->
      val placeable = measurable.measure(
        Constraints.fixed(FRAME_WIDTH.roundToPx(), FRAME_HEIGHT.roundToPx()),
      )
      val width = (placeable.width * scale).roundToInt().coerceIn(0, constraints.maxWidth)
      val height = (placeable.height * scale).roundToInt().coerceIn(0, constraints.maxHeight)
      layout(width, height) {
        placeable.place(0, 0)
      }
    }.graphicsLayer {
      scaleX = scale
      scaleY = scale
      transformOrigin = TransformOrigin(0f, 0f)
    },
    content = content,
  )
}
