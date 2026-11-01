package dev.skillbill.desktop.core.ui.changes

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import dev.skillbill.desktop.core.designsystem.LocalReducedMotion
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.layeredShadow
import dev.skillbill.desktop.core.domain.model.ScopeSubtask
import dev.skillbill.desktop.core.domain.model.ScopeSubtaskState
import dev.skillbill.desktop.core.domain.model.SubtaskStats
import kotlin.math.roundToInt
import kotlin.math.sqrt

private val COLUMN_GAP = 2.dp
private val MIN_COLUMN = 4.dp
private val LABEL_EVERY_COLUMN = 22.dp
private val STRIP_HEIGHT = 46.dp
private val MIN_BAR_HEIGHT = 2.dp
private const val MIN_FRACTION = 0.06f
private const val SELECTED_ALPHA = 0.10f
private const val TIP_OFFSET_X = 12
private const val TIP_OFFSET_Y = 14

/** Below this per-subtask width bars read better; at or above it each subtask gets a labelled card. */
private val CARD_MIN_PITCH = 140.dp
private val CARD_MAX_WIDTH = 320.dp

private data class StripHover(val index: Int, val position: Offset)

/**
 * One column per subtask in id order: a labelled card when the width gives each subtask at least [CARD_MIN_PITCH], else a bar. With [onDrag], a press on a selectable column calls [onPick] at once (extend while Shift is
 * held) and a drag from that column reports the press id and the id under the pointer. Without [onDrag] the strip is click-only:
 * [onPick] fires on release of a press that stayed within touch slop on its column, and drags pick nothing. Presses on
 * non-selectable columns do nothing. [barsFooter] shows under the bars only, since a card already names its subtask.
 */
@Composable
fun SubtaskStrip(
  subtasks: List<ScopeSubtask>,
  stats: Map<Int, SubtaskStats>,
  selected: IntRange?,
  onPick: (id: Int, extend: Boolean) -> Unit,
  modifier: Modifier = Modifier,
  onDrag: ((fromId: Int, toId: Int) -> Unit)? = null,
  barsFooter: (@Composable () -> Unit)? = null,
) {
  if (subtasks.isEmpty()) return
  val colors = SkillBillTheme.colors
  val maxLines = stats.values.maxOfOrNull { it.insertions + it.deletions }?.coerceAtLeast(1) ?: 1
  val pick by rememberUpdatedState(onPick)
  val drag by rememberUpdatedState(onDrag)
  var hover by remember { mutableStateOf<StripHover?>(null) }
  BoxWithConstraints(modifier.fillMaxWidth()) {
    val natural = (maxWidth + COLUMN_GAP) / subtasks.size
    val cards = natural >= CARD_MIN_PITCH
    val pitch = if (cards) minOf(natural, CARD_MAX_WIDTH + COLUMN_GAP) else maxOf(MIN_COLUMN + COLUMN_GAP, natural)
    val column = pitch - COLUMN_GAP
    val scrolls = pitch * subtasks.size - COLUMN_GAP > maxWidth
    val labelAll = maxWidth / subtasks.size >= LABEL_EVERY_COLUMN
    Column(if (scrolls) Modifier.horizontalScroll(rememberScrollState()) else Modifier) {
      Box {
        Row(
          Modifier.height(STRIP_HEIGHT)
            .pointerInput(subtasks, pitch) {
              val pitchPx = pitch.toPx()
              awaitPointerEventScope {
                while (true) {
                  val event = awaitPointerEvent(PointerEventPass.Initial)
                  val change = event.changes.firstOrNull() ?: continue
                  hover = if (event.type == PointerEventType.Exit) null else {
                    StripHover((change.position.x / pitchPx).toInt().coerceIn(0, subtasks.lastIndex), change.position)
                  }
                }
              }
            }
            .pointerInput(subtasks, pitch) {
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
          horizontalArrangement = Arrangement.spacedBy(COLUMN_GAP),
        ) {
          subtasks.forEachIndexed { index, subtask ->
            key(subtask.id) {
              val inRange = selected != null && subtask.id in selected
              val description = subtaskTooltip(subtask, stats[subtask.id])
              val hovered = hover?.index == index
              val columnModifier = Modifier.width(column).fillMaxHeight()
              if (cards) {
                StripCard(subtask, stats[subtask.id], inRange, hovered, description, { pick(subtask.id, false) }, columnModifier)
              } else {
                StripColumn(subtask, inRange, hovered, subtaskBarFraction(subtask, stats[subtask.id], maxLines), description, { pick(subtask.id, false) }, columnModifier)
              }
            }
          }
        }
        hover?.let { StripTip(subtasks[it.index], stats[subtasks[it.index].id], it.position) }
      }
      if (!cards) Row(Modifier.padding(top = 4.dp).height(9.5.dp), horizontalArrangement = Arrangement.spacedBy(COLUMN_GAP)) {
        subtasks.forEachIndexed { index, subtask ->
          val labelled = index == 0 || subtask.id % (if (labelAll) 1 else 5) == 0
          val color = if (selected != null && subtask.id in selected) colors.accentText else colors.fgFaint
          Box(Modifier.width(column).fillMaxHeight(), contentAlignment = Alignment.Center) {
            // A label wider than a narrow column overflows it on both sides.
            BasicText(
              if (labelled) "${subtask.id}" else "",
              Modifier.wrapContentSize(unbounded = true),
              style = changesMono(9.5f, FontWeight.Medium, color),
              maxLines = 1,
              softWrap = false,
            )
          }
        }
      }
      if (!cards) barsFooter?.invoke()
    }
  }
}

/** A subtask with room for its name: number, state and name over its counts, dashed and inert until it starts. */
@Composable
private fun StripCard(
  subtask: ScopeSubtask,
  stats: SubtaskStats?,
  inRange: Boolean,
  hovered: Boolean,
  description: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val colors = SkillBillTheme.colors
  val shape = RoundedCornerShape(7.dp)
  val started = subtask.state != ScopeSubtaskState.NOT_STARTED
  val background = when {
    inRange -> colors.accent.copy(alpha = SELECTED_ALPHA)
    !started -> Color.Transparent
    hovered -> colors.bgOverlay
    else -> colors.bgRaised
  }
  val dash = colors.borderStrong
  val running = subtask.state == ScopeSubtaskState.RUNNING
  Column(
    modifier
      .then(if (subtask.selectable) Modifier.pointerHoverIcon(PointerIcon.Hand) else Modifier)
      .semantics {
        contentDescription = description
        role = Role.Button
        this.selected = inRange
        if (subtask.selectable) onClick { onClick(); true } else disabled()
      }
      .background(background, shape)
      .then(
        when {
          inRange -> Modifier.border(1.dp, colors.accent.copy(alpha = 0.4f), shape)
          started -> Modifier
          else -> Modifier.drawBehind {
            val stroke = 1.dp.toPx()
            drawRoundRect(
              dash,
              Offset(stroke / 2, stroke / 2),
              androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke),
              androidx.compose.ui.geometry.CornerRadius(7.dp.toPx()),
              style = androidx.compose.ui.graphics.drawscope.Stroke(stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx()))),
            )
          }
        },
      )
      .padding(horizontal = 10.dp),
    verticalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterVertically),
  ) {
    Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
      ScopeStateDot(subtask.state)
      BasicText(
        "${subtask.id}",
        style = changesMono(11f, FontWeight.SemiBold, if (running || inRange) colors.accentText else colors.fgFaint),
        maxLines = 1,
        softWrap = false,
      )
      BasicText(
        subtask.name,
        style = changesSans(12f, FontWeight.Medium, if (started) (if (inRange) colors.fg else colors.fg2) else colors.fgFaint, lineHeight = 15f),
        maxLines = 1,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
      )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
      if (started) {
        BasicText(filesLabel(stats?.files?.size ?: 0), style = changesMono(11f, FontWeight.Normal, colors.fgMuted), maxLines = 1, softWrap = false)
        AddCount(stats?.insertions ?: 0)
        DelCount(stats?.deletions ?: 0)
      } else {
        BasicText("not started", style = changesMono(11f, FontWeight.Normal, colors.fgFaint), maxLines = 1, softWrap = false)
      }
    }
  }
}

@Composable
private fun StripColumn(
  subtask: ScopeSubtask,
  inRange: Boolean,
  hovered: Boolean,
  fraction: Float,
  description: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val colors = SkillBillTheme.colors
  val background = when {
    inRange -> colors.accent.copy(alpha = SELECTED_ALPHA)
    hovered -> colors.bgRaised
    else -> Color.Transparent
  }
  Box(
    modifier
      .then(if (subtask.selectable) Modifier.pointerHoverIcon(PointerIcon.Hand) else Modifier)
      .semantics {
        contentDescription = description
        role = Role.Button
        this.selected = inRange
        if (subtask.selectable) onClick { onClick(); true } else disabled()
      }
      .background(background, RoundedCornerShape(3.dp)),
    contentAlignment = Alignment.BottomCenter,
  ) {
    if (subtask.state == ScopeSubtaskState.NOT_STARTED) {
      val dash = colors.borderStrong
      Box(
        Modifier.fillMaxWidth().height(MIN_BAR_HEIGHT).drawBehind {
          val stroke = 1.dp.toPx()
          drawLine(
            dash,
            Offset(0f, stroke / 2),
            Offset(size.width, stroke / 2),
            stroke,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx())),
          )
        },
      )
    } else {
      val bar = when {
        subtask.state == ScopeSubtaskState.RUNNING -> colors.accent
        inRange -> colors.accentText
        subtask.state == ScopeSubtaskState.DONE -> colors.stripDone
        else -> colors.stripIdle
      }
      Box(
        Modifier.fillMaxWidth().fillMaxHeight(fraction).heightIn(min = MIN_BAR_HEIGHT)
          .background(bar, RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp)),
      )
    }
  }
}

/** The tip that follows the pointer over a column; [position] is local to the strip's bar row. */
@Composable
private fun StripTip(subtask: ScopeSubtask, stats: SubtaskStats?, position: Offset) {
  val colors = SkillBillTheme.colors
  val shape = RoundedCornerShape(7.dp)
  val provider = remember(position) {
    object : PopupPositionProvider {
      override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val x = anchorBounds.left + position.x.roundToInt() + TIP_OFFSET_X
        val y = anchorBounds.top + position.y.roundToInt() + TIP_OFFSET_Y
        return IntOffset(
          x.coerceAtMost((windowSize.width - popupContentSize.width).coerceAtLeast(0)),
          y.coerceAtMost((windowSize.height - popupContentSize.height).coerceAtLeast(0)),
        )
      }
    }
  }
  Popup(popupPositionProvider = provider) {
    Column(
      Modifier.widthIn(max = 320.dp)
        .layeredShadow(SkillBillTheme.shadows.popover, shape)
        .clip(shape)
        .background(colors.bgOverlay, shape)
        .border(1.dp, colors.borderStrong, shape)
        .padding(horizontal = 9.dp, vertical = 7.dp),
    ) {
      BasicText("${subtask.id} · ${subtask.name}", style = changesSans(11.5f, FontWeight.SemiBold, colors.fg, lineHeight = 16.1f))
      val detail = buildAnnotatedString {
        if (subtask.state == ScopeSubtaskState.NOT_STARTED) {
          append("not started")
        } else {
          if (subtask.state == ScopeSubtaskState.RUNNING) append("running · ")
          append("${filesLabel(stats?.files?.size ?: 0)} · ")
          withStyle(changesMono(11f, FontWeight.Medium, colors.stDone).toSpanStyle()) { append("+${stats?.insertions ?: 0}") }
          append(" ")
          withStyle(changesMono(11f, FontWeight.Medium, colors.stFailed).toSpanStyle()) { append("−${stats?.deletions ?: 0}") }
          append(" · ${subtask.endSha?.take(7) ?: "no commit"}")
        }
      }
      BasicText(detail, style = changesMono(11f, FontWeight.Normal, colors.fgMuted, lineHeight = 15.4f))
    }
  }
}

/** A 7 dp dot in the subtask state's color: RUNNING a pulsing accent, DONE done, INTERRUPTED failed, NOT_STARTED a hollow faint ring. */
@Composable
fun ScopeStateDot(state: ScopeSubtaskState, modifier: Modifier = Modifier) {
  val colors = SkillBillTheme.colors
  when (state) {
    ScopeSubtaskState.RUNNING -> {
      val alpha = if (LocalReducedMotion.current) {
        1f
      } else {
        val pulse by rememberInfiniteTransition(label = "running-dot").animateFloat(
          initialValue = 1f,
          targetValue = 0.35f,
          animationSpec = infiniteRepeatable(tween(800, easing = FastOutSlowInEasing), RepeatMode.Reverse),
          label = "running-dot-alpha",
        )
        pulse
      }
      Box(modifier.size(7.dp).alpha(alpha).background(colors.accent, CircleShape))
    }
    ScopeSubtaskState.DONE -> Box(modifier.size(7.dp).background(colors.stDone, CircleShape))
    ScopeSubtaskState.INTERRUPTED -> Box(modifier.size(7.dp).background(colors.stFailed, CircleShape))
    ScopeSubtaskState.NOT_STARTED -> Box(modifier.size(7.dp).border(1.dp, colors.fgFaint, CircleShape))
  }
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
  return "$head\n$running${filesLabel(stats?.files?.size ?: 0)} · +${stats?.insertions ?: 0} −${stats?.deletions ?: 0} · $sha"
}
