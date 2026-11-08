package dev.skillbill.desktop.core.ui.changes

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.PopupProperties
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.SbElevation
import dev.skillbill.desktop.core.designsystem.component.sbOverlaySurface
import kotlin.math.roundToInt

internal val STRIP_HEIGHT = 46.dp
internal val MIN_BAR_HEIGHT = 2.dp
internal const val MIN_FRACTION = 0.06f
internal const val SELECTED_ALPHA = 0.10f
private const val TIP_OFFSET_X = 12
private const val TIP_OFFSET_Y = 14

internal data class StripMetrics(
  val cards: Boolean,
  val pitch: Dp,
  val column: Dp,
  val scrolls: Boolean,
  val labelAll: Boolean,
)

private val COLUMN_GAP = 2.dp
private val MIN_COLUMN = 4.dp
private val LABEL_EVERY_COLUMN = 22.dp
internal val CARD_MIN_PITCH = 140.dp
internal val CARD_CHANGED_BADGE_MIN_CONTENT_WIDTH = 180.dp
private val CARD_MAX_WIDTH = 320.dp

internal fun stripMetrics(maxWidth: Dp, count: Int): StripMetrics {
  val natural = (maxWidth + COLUMN_GAP) / count
  val cards = natural >= CARD_MIN_PITCH
  val pitch = if (cards) minOf(natural, CARD_MAX_WIDTH + COLUMN_GAP) else maxOf(MIN_COLUMN + COLUMN_GAP, natural)
  return StripMetrics(cards, pitch, pitch - COLUMN_GAP, pitch * count - COLUMN_GAP > maxWidth, maxWidth / count >= LABEL_EVERY_COLUMN)
}

internal fun subtaskLineFraction(lines: Int, maxLines: Int): Float =
  maxOf(MIN_FRACTION, (kotlin.math.sqrt(lines.toDouble() / maxLines.coerceAtLeast(1)) * 100).roundToInt() / 100f)

@Composable
internal fun stripCardSurface(selected: Boolean, hovered: Boolean, base: Color): Modifier {
  val colors = SkillBillTheme.colors
  val shape = RoundedCornerShape(7.dp)
  return Modifier
    .background(when { selected -> colors.accent.copy(alpha = SELECTED_ALPHA); hovered -> colors.bgOverlay; else -> base }, shape)
    .then(if (selected) Modifier.border(1.dp, colors.accent.copy(alpha = 0.4f), shape) else Modifier)
}

@Composable
internal fun StripTipSurface(position: androidx.compose.ui.geometry.Offset, content: @Composable () -> Unit) {
  val provider = remember(position) {
    object : PopupPositionProvider {
      override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val x = anchorBounds.left + position.x.roundToInt() + TIP_OFFSET_X
        val y = anchorBounds.top + position.y.roundToInt() + TIP_OFFSET_Y
        return IntOffset(x.coerceAtMost((windowSize.width - popupContentSize.width).coerceAtLeast(0)), y.coerceAtMost((windowSize.height - popupContentSize.height).coerceAtLeast(0)))
      }
    }
  }
  Popup(popupPositionProvider = provider, properties = PopupProperties(focusable = false)) {
    Column(Modifier.widthIn(max = 320.dp).sbOverlaySurface(SbElevation.Popover).padding(horizontal = 9.dp, vertical = 7.dp)) { content() }
  }
}
