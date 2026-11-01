package dev.skillbill.desktop.core.ui.changes

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.SbTooltip
import dev.skillbill.desktop.core.domain.model.ScopeSubtask
import dev.skillbill.desktop.core.domain.model.ScopeSubtaskState
import dev.skillbill.desktop.core.domain.model.SubtaskStats
import kotlin.math.roundToInt
import kotlin.math.sqrt

private val MIN_COLUMN = 2.dp
private val LABEL_EVERY_COLUMN = 22.dp
private val BAR_HEIGHT = 36.dp
private const val MIN_FRACTION = 0.06f

/**
 * One column per subtask in id order. With [onDrag], a press on a selectable column calls [onPick] at once (extend while Shift is
 * held) and a drag from that column reports the press id and the id under the pointer. Without [onDrag] the strip is click-only:
 * [onPick] fires on release of a press that stayed within touch slop on its column, and drags pick nothing. Presses on
 * non-selectable columns do nothing.
 */
@Composable
fun SubtaskStrip(
  subtasks: List<ScopeSubtask>,
  stats: Map<Int, SubtaskStats>,
  selected: IntRange?,
  onPick: (id: Int, extend: Boolean) -> Unit,
  modifier: Modifier = Modifier,
  onDrag: ((fromId: Int, toId: Int) -> Unit)? = null,
) {
  if (subtasks.isEmpty()) return
  val colors = SkillBillTheme.colors
  val maxLines = stats.values.maxOfOrNull { it.insertions + it.deletions }?.coerceAtLeast(1) ?: 1
  val pick by rememberUpdatedState(onPick)
  val drag by rememberUpdatedState(onDrag)
  BoxWithConstraints(modifier.fillMaxWidth()) {
    val pitch = maxOf(MIN_COLUMN, maxWidth / subtasks.size)
    val scrolls = MIN_COLUMN * subtasks.size > maxWidth
    val labelAll = pitch >= LABEL_EVERY_COLUMN
    Column(if (scrolls) Modifier.horizontalScroll(rememberScrollState()) else Modifier) {
      Row(
        Modifier.height(BAR_HEIGHT).pointerInput(subtasks, pitch) {
          val pitchPx = pitch.toPx()
          fun indexAt(x: Float) = (x / pitchPx).toInt().coerceIn(0, subtasks.lastIndex)
          awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val pressIndex = indexAt(down.position.x)
            val press = subtasks[pressIndex]
            if (!press.selectable) return@awaitEachGesture
            val extend = currentEvent.keyboardModifiers.isShiftPressed
            val follow = drag
            if (follow == null) {
              // Click-only: pick on release when the press did not move past touch slop and stayed on its column.
              var dragged = false
              while (true) {
                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                if (change.isConsumed || (change.position - down.position).getDistance() > viewConfiguration.touchSlop) dragged = true
                if (!change.pressed) {
                  if (!dragged && indexAt(change.position.x) == pressIndex) {
                    change.consume()
                    pick(press.id, extend)
                  }
                  return@awaitEachGesture
                }
              }
            }
            down.consume()
            pick(press.id, extend)
            if (extend) return@awaitEachGesture
            var last = pressIndex
            while (true) {
              val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
              if (!change.pressed) break
              change.consume()
              val index = indexAt(change.position.x)
              if (index != last) {
                last = index
                follow(press.id, subtasks[index].id)
              }
            }
          }
        },
      ) {
        subtasks.forEach { subtask ->
          key(subtask.id) {
            val tooltip = subtaskTooltip(subtask, stats[subtask.id])
            SbTooltip(tooltip) {
              val fraction = subtaskBarFraction(subtask, stats[subtask.id], maxLines)
              val inRange = selected != null && subtask.id in selected
              Box(
                Modifier.width(pitch).fillMaxHeight()
                  .semantics {
                    contentDescription = tooltip
                    role = Role.Button
                    this.selected = inRange
                    if (subtask.selectable) onClick { pick(subtask.id, false); true } else disabled()
                  }
                  .background(if (inRange) colors.accentTint else Color.Transparent)
                  .padding(horizontal = if (pitch >= 4.dp) 1.dp else 0.dp),
                contentAlignment = Alignment.BottomCenter,
              ) {
                if (subtask.state == ScopeSubtaskState.NOT_STARTED) {
                  val border = colors.border
                  Box(
                    Modifier.fillMaxWidth().fillMaxHeight().drawBehind {
                      val stroke = 1.dp.toPx()
                      drawRect(
                        border,
                        topLeft = Offset(stroke / 2, stroke / 2),
                        size = Size(size.width - stroke, size.height - stroke),
                        style = Stroke(stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 2.dp.toPx()))),
                      )
                    },
                  )
                } else {
                  val bar = if (subtask.state == ScopeSubtaskState.RUNNING) colors.accent else colors.fgMuted
                  Box(Modifier.fillMaxWidth().fillMaxHeight(fraction).background(bar))
                }
              }
            }
          }
        }
      }
      Row {
        subtasks.forEachIndexed { index, subtask ->
          val labelled = index == 0 || labelAll || subtask.id % 5 == 0
          Box(Modifier.width(pitch)) {
            // A label wider than a narrow column overflows to the right, like the design's number row.
            if (labelled) BasicText("${subtask.id}", Modifier.width(maxOf(pitch, LABEL_EVERY_COLUMN)), style = SkillBillTheme.type.monoSm.copy(color = colors.fgMuted, textAlign = TextAlign.Start), maxLines = 1)
          }
        }
      }
    }
  }
}

/** A small dot in the subtask state's color: RUNNING accent, DONE done, INTERRUPTED failed, NOT_STARTED faint. */
@Composable
fun ScopeStateDot(state: ScopeSubtaskState, modifier: Modifier = Modifier) {
  val colors = SkillBillTheme.colors
  val color = when (state) {
    ScopeSubtaskState.RUNNING -> colors.accent
    ScopeSubtaskState.DONE -> colors.stDone
    ScopeSubtaskState.INTERRUPTED -> colors.stFailed
    ScopeSubtaskState.NOT_STARTED -> colors.fgFaint
  }
  Box(modifier.size(7.dp).background(color, CircleShape))
}

/**
 * The bar's share of the strip height: 0 for NOT_STARTED, 6% before stats arrive, else the square root of the subtask's lines over
 * [maxLines], rounded to whole percent and never below 6% so a tiny subtask stays visible and clickable.
 */
fun subtaskBarFraction(subtask: ScopeSubtask, stats: SubtaskStats?, maxLines: Int): Float {
  if (subtask.state == ScopeSubtaskState.NOT_STARTED) return 0f
  if (stats == null) return MIN_FRACTION
  val lines = stats.insertions + stats.deletions
  val percent = (sqrt(lines.toDouble() / maxLines.coerceAtLeast(1)) * 100).roundToInt()
  return maxOf(MIN_FRACTION, percent / 100f)
}

/** `<n> · <name>` over `not started`, or `[running · ]<F> files · +<A> −<D> · <sha>`. */
fun subtaskTooltip(subtask: ScopeSubtask, stats: SubtaskStats?): String {
  val head = "${subtask.id} · ${subtask.name}"
  if (subtask.state == ScopeSubtaskState.NOT_STARTED) return "$head\nnot started"
  val running = if (subtask.state == ScopeSubtaskState.RUNNING) "running · " else ""
  val sha = subtask.endSha?.take(7) ?: "no commit"
  return "$head\n$running${stats?.files?.size ?: 0} files · +${stats?.insertions ?: 0} −${stats?.deletions ?: 0} · $sha"
}
