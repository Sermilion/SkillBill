package dev.skillbill.desktop.core.ui.changes

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.status.StatusDot
import dev.skillbill.desktop.core.domain.model.ScopeSubtask
import dev.skillbill.desktop.core.domain.model.ScopeSubtaskState
import dev.skillbill.desktop.core.domain.model.SubtaskStats
import dev.skillbill.desktop.core.domain.model.TimelineProblem
import dev.skillbill.desktop.core.domain.model.subtaskColumns
import dev.skillbill.desktop.core.ui.feedback.readProblemText

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
  val entries = remember(subtasks, stats) { stripEntries(subtasks, stats) }
  val colors = SkillBillTheme.colors
  val maxLines = stats.values.maxOfOrNull { it.insertions + it.deletions }?.coerceAtLeast(1) ?: 1
  val pick by rememberUpdatedState(onPick)
  val drag by rememberUpdatedState(onDrag)
  var hover by remember { mutableStateOf<StripHover?>(null) }
  BoxWithConstraints(modifier.fillMaxWidth()) {
    val metrics = stripMetrics(maxWidth, entries.size)
    val pitch = metrics.pitch
    val column = metrics.column
    val cards = metrics.cards
    val labelAll = metrics.labelAll
    val gap = 2.dp
    Column(if (metrics.scrolls) Modifier.horizontalScroll(rememberScrollState()) else Modifier) {
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
                    StripHover((change.position.x / pitchPx).toInt().coerceIn(0, entries.lastIndex), change.position)
                  }
                }
              }
            }
            .pointerInput(subtasks, pitch) {
              val pitchPx = pitch.toPx()
              fun indexAt(x: Float) = (x / pitchPx).toInt().coerceIn(0, entries.lastIndex)
              awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val pressIndex = indexAt(down.position.x)
                val press = entries[pressIndex].subtask
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
                    follow(press.id, entries[index].subtask.id)
                  }
                }
              }
            },
          horizontalArrangement = Arrangement.spacedBy(gap),
        ) {
          entries.forEachIndexed { index, entry ->
            key(entry.ids) {
              val inRange = selected != null && entry.ids.any { it in selected }
              val description = subtaskTooltip(entry.subtask, entry.stats)
              val hovered = hover?.index == index
              val columnModifier = Modifier.width(column).fillMaxHeight()
              if (cards) {
                StripCard(
                  entry.subtask,
                  entry.stats,
                  inRange,
                  hovered,
                  description,
                  { pick(entry.subtask.id, false) },
                  columnModifier,
                  entry.number,
                )
              } else {
                StripColumn(
                  entry.subtask,
                  inRange,
                  hovered,
                  subtaskBarFraction(entry.subtask, entry.stats, maxLines),
                  description,
                  { pick(entry.subtask.id, false) },
                  columnModifier,
                )
              }
            }
          }
        }
        hover?.let { tip ->
          entries.getOrNull(tip.index)?.let { entry -> StripTip(entry.subtask, entry.stats, tip.position) }
        }
      }
      if (!cards) Row(Modifier.padding(top = 4.dp).height(9.5.dp), horizontalArrangement = Arrangement.spacedBy(gap)) {
        entries.forEachIndexed { index, entry ->
          val labelled = index == 0 || entry.ids.any { id -> id % (if (labelAll) 1 else 5) == 0 }
          val color = if (selected != null && entry.ids.any { it in selected }) colors.accentText else colors.fgFaint
          Box(Modifier.width(column).fillMaxHeight(), contentAlignment = Alignment.Center) {
            BasicText(
              if (labelled) entry.number else "",
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
  number: String = "${subtask.id}",
) {
  val colors = SkillBillTheme.colors
  val shape = RoundedCornerShape(7.dp)
  val started = subtask.state != ScopeSubtaskState.NOT_STARTED
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
      .then(stripCardSurface(inRange, hovered && started, if (started) colors.bgRaised else Color.Transparent))
      .then(
        when {
          inRange -> Modifier
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
    BoxWithConstraints(Modifier.fillMaxWidth()) {
      val showChangedLabel = maxWidth >= CARD_CHANGED_BADGE_MIN_CONTENT_WIDTH
      Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
        ScopeStateDot(subtask.state)
        BasicText(
          number,
          Modifier.widthIn(max = 56.dp),
          style = changesMono(11f, FontWeight.SemiBold, if (running || inRange) colors.accentText else colors.fgFaint),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          softWrap = false,
        )
        BasicText(
          subtask.name,
          Modifier.weight(1f),
          style = changesSans(12f, FontWeight.Medium, if (started) (if (inRange) colors.fg else colors.fg2) else colors.fgFaint, lineHeight = 15f),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        if (subtask.changedAfterFinish) {
          if (showChangedLabel) {
            BasicText("changed after finish", style = changesSans(10f, FontWeight.Medium, colors.stFailed), maxLines = 1, softWrap = false)
          } else {
            BasicText("!", style = changesMono(11f, FontWeight.Bold, colors.stFailed), maxLines = 1, softWrap = false)
          }
        }
      }
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
      if (subtask.changedAfterFinish) {
        Box(Modifier.align(Alignment.TopCenter).size(5.dp).background(colors.stFailed, CircleShape))
      }
    }
  }
}

/** The tip that follows the pointer over a column; [position] is local to the strip's bar row. */
@Composable
private fun StripTip(subtask: ScopeSubtask, stats: SubtaskStats?, position: Offset) {
  val colors = SkillBillTheme.colors
  StripTipSurface(position) {
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
        append(" · ${subtask.commitShas.lastOrNull()?.take(7) ?: "no commit"}")
        if (subtask.changedAfterFinish) append(" · changed after finish")
      }
    }
    BasicText(detail, style = changesMono(11f, FontWeight.Normal, colors.fgMuted, lineHeight = 15.4f))
  }
}

/** A 6 dp dot in the subtask state's color: RUNNING a pulsing accent, DONE done, INTERRUPTED failed, NOT_STARTED a hollow faint ring. */
@Composable
fun ScopeStateDot(state: ScopeSubtaskState, modifier: Modifier = Modifier) {
  val colors = SkillBillTheme.colors
  when (state) {
    ScopeSubtaskState.RUNNING -> StatusDot(colors.accent, modifier, pulse = true)
    ScopeSubtaskState.DONE -> StatusDot(colors.stDone, modifier)
    ScopeSubtaskState.INTERRUPTED -> StatusDot(colors.stFailed, modifier)
    ScopeSubtaskState.NOT_STARTED -> StatusDot(colors.fgFaint, modifier, hollow = true)
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
  return subtaskLineFraction(lines, maxLines)
}

/** `<n> · <name>` over `not started`, or `[running · ]<F> files · +<A> −<D> · <sha>`. */
fun subtaskTooltip(subtask: ScopeSubtask, stats: SubtaskStats?): String {
  val head = "${subtask.id} · ${subtask.name}"
  if (subtask.state == ScopeSubtaskState.NOT_STARTED) {
    val changed = if (subtask.changedAfterFinish) " · changed after finish" else ""
    return "$head\nnot started$changed"
  }
  val running = if (subtask.state == ScopeSubtaskState.RUNNING) "running · " else ""
  val sha = subtask.commitShas.lastOrNull()?.take(7) ?: "no commit"
  val changed = if (subtask.changedAfterFinish) " · changed after finish" else ""
  return "$head\n$running${filesLabel(stats?.files?.size ?: 0)} · +${stats?.insertions ?: 0} −${stats?.deletions ?: 0} · $sha$changed"
}

fun timelineProblemText(problem: TimelineProblem): String = when (problem) {
  is TimelineProblem.OnOtherBranch ->
    "Checkout is on ${problem.currentBranch ?: "detached HEAD"}, not the goal branch ${problem.featureBranch}. Subtask scopes need the goal branch."
  is TimelineProblem.BaseUnavailable -> "Base commit unavailable: ${problem.reason}"
  is TimelineProblem.NotARepository -> "${problem.worktreeRoot} is not a git repository."
  is TimelineProblem.Unavailable -> readProblemText(problem.problem)
}

internal data class StripEntry(val ids: List<Int>, val subtask: ScopeSubtask, val stats: SubtaskStats?) {
  val number: String get() = ids.joinToString(" + ")
}

private val STRIP_STATE_ORDER = listOf(
  ScopeSubtaskState.RUNNING,
  ScopeSubtaskState.INTERRUPTED,
  ScopeSubtaskState.DONE,
  ScopeSubtaskState.NOT_STARTED,
)

internal fun stripEntries(subtasks: List<ScopeSubtask>, stats: Map<Int, SubtaskStats>): List<StripEntry> {
  val byId = subtasks.associateBy { it.id }
  return subtaskColumns(subtasks).map { column ->
    val members = column.ids.map { byId.getValue(it) }
    val representative = members.maxByOrNull { member -> stats[member.id]?.let { it.insertions + it.deletions } ?: -1 } ?: members.first()
    val subtask = if (members.size == 1) members.single() else ScopeSubtask(
      id = column.ids.first(),
      name = members.joinToString(" + ") { it.name },
      state = STRIP_STATE_ORDER.first { state -> members.any { it.state == state } },
      recordedSha = representative.recordedSha,
      commitShas = members.flatMap { it.commitShas }.distinct(),
      changedAfterFinish = members.any { it.changedAfterFinish },
      baselineUntrackedPaths = representative.baselineUntrackedPaths,
      reviewBaseSha = representative.reviewBaseSha,
      completedNoChange = representative.completedNoChange,
    )
    StripEntry(column.ids, subtask, stats[representative.id])
  }
}
