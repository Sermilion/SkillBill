package dev.skillbill.desktop.core.designsystem.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.sbFocusRing
import kotlin.math.roundToInt

private val TRACK_HEIGHT = 4.dp
private val THUMB_RADIUS = 7.dp

/**
 * The design's `.sb-range`: a full-width range input tinted with the accent. [onValueChange] fires while
 * dragging or on arrow keys; [onValueChangeFinished] fires when the pointer lifts or after a key step.
 */
@Composable
fun SettingRange(
  value: Int,
  onValueChange: (Int) -> Unit,
  label: String,
  modifier: Modifier = Modifier,
  min: Int = 0,
  max: Int = 100,
  enabled: Boolean = true,
  onValueChangeFinished: () -> Unit = {},
) {
  val colors = SkillBillTheme.colors
  val interaction = remember { MutableInteractionSource() }
  val change by rememberUpdatedState(onValueChange)
  val finished by rememberUpdatedState(onValueChangeFinished)
  val span = (max - min).coerceAtLeast(1)
  val fraction = ((value - min).toFloat() / span).coerceIn(0f, 1f)
  Canvas(
    modifier.height(20.dp).alpha(if (enabled) 1f else .45f)
      .semantics {
        contentDescription = label
        stateDescription = "$value"
        if (!enabled) disabled()
      }
      .focusable(enabled, interaction)
      .sbFocusRing(interaction, androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
      .onKeyEvent { event ->
        if (!enabled || event.type != KeyEventType.KeyDown) return@onKeyEvent false
        val step = when (event.key) {
          Key.DirectionRight, Key.DirectionUp -> 1
          Key.DirectionLeft, Key.DirectionDown -> -1
          else -> return@onKeyEvent false
        }
        val next = (value + step).coerceIn(min, max)
        if (next != value) {
          change(next)
          finished()
        }
        true
      }
      .pointerInput(enabled, min, max) {
        if (!enabled) return@pointerInput
        val thumb = THUMB_RADIUS.toPx()
        fun valueAt(x: Float): Int {
          val usable = (size.width - thumb * 2).coerceAtLeast(1f)
          return (min + ((x - thumb) / usable).coerceIn(0f, 1f) * span).roundToInt()
        }
        awaitEachGesture {
          val down = awaitFirstDown()
          change(valueAt(down.position.x))
          drag(down.id) { moved ->
            change(valueAt(moved.position.x))
            moved.consume()
          }
          finished()
        }
      },
  ) {
    val thumb = THUMB_RADIUS.toPx()
    val track = TRACK_HEIGHT.toPx()
    val usable = size.width - thumb * 2
    val y = size.height / 2
    val x = thumb + usable * fraction
    val radius = CornerRadius(track / 2, track / 2)
    drawRoundRect(colors.borderStrong, Offset(thumb, y - track / 2), Size(usable, track), radius)
    drawRoundRect(colors.accent, Offset(thumb, y - track / 2), Size(x - thumb, track), radius)
    drawCircle(colors.accent, thumb, Offset(x, y))
  }
}
