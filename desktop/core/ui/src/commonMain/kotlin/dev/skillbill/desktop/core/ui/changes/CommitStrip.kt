package dev.skillbill.desktop.core.ui.changes

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.domain.model.ScopeCommit
import dev.skillbill.desktop.core.domain.model.SubtaskStats

fun commitTag(commit: ScopeCommit): String = if (commit.operator) "operator" else commit.orderedOwnerIds.joinToString(" + ")

fun commitTooltip(commit: ScopeCommit, stats: SubtaskStats?): String = buildString {
  append(commit.sha.take(7)).append(" · ").append(commit.subject).append(" · ").append(commitTag(commit))
  if (stats == null) append(" · stats unavailable") else append(" · ").append(stats.files.size).append(" files · +").append(stats.insertions).append("/−").append(stats.deletions)
}

fun commitBarFraction(stats: SubtaskStats?, maxLines: Int): Float =
  if (stats == null) 0f else subtaskLineFraction(stats.insertions + stats.deletions, maxLines)

private data class CommitHover(val index: Int, val position: Offset)
private data class CommitLabel(val text: String, val width: Dp, val lane: Int)

private val COMMIT_LABEL_GAP = 4.dp
private val COMMIT_LABEL_TOP_GAP = 3.dp
private val COMMIT_LABELS_MAX_HEIGHT = 112.dp

@Composable
fun CommitStrip(
  commits: List<ScopeCommit>,
  stats: Map<String, SubtaskStats>,
  selected: Set<String>,
  onPick: (sha: String, extend: Boolean) -> Unit,
  onDrag: (fromSha: String, toSha: String) -> Unit,
  modifier: Modifier = Modifier,
) {
  if (commits.isEmpty()) return
  val colors = SkillBillTheme.colors
  val density = LocalDensity.current
  val textMeasurer = rememberTextMeasurer()
  val pick by rememberUpdatedState(onPick)
  val drag by rememberUpdatedState(onDrag)
  val maxLines = stats.values.maxOfOrNull { it.insertions + it.deletions }?.coerceAtLeast(1) ?: 1
  BoxWithConstraints(modifier.fillMaxWidth()) {
    val metrics = stripMetrics(maxWidth, commits.size)
    val pitch = metrics.pitch
    val column = metrics.column
    val barWidth = pitch * commits.size - 2.dp
    val tagStyle = changesMono(9f, FontWeight.Medium, colors.fgMuted)
    val labelLaneHeight = with(density) {
      textMeasurer.measure("operator", tagStyle, softWrap = false, maxLines = 1).size.height.toDp()
    } + COMMIT_LABEL_GAP
    val labels = if (metrics.cards) emptyList() else remember(commits, pitch, column, density, tagStyle) {
      val laneRightEdges = mutableListOf<Dp>()
      commits.mapIndexed { index, commit ->
        val tag = commitTag(commit)
        val width = with(density) { textMeasurer.measure(tag, tagStyle, softWrap = false, maxLines = 1).size.width.toDp() }
        val center = pitch * index + column / 2
        val left = center - width / 2
        val lane = laneRightEdges.indexOfFirst { right -> left >= right + COMMIT_LABEL_GAP }.let { if (it < 0) laneRightEdges.size else it }
        val right = center + width / 2
        if (lane == laneRightEdges.size) laneRightEdges.add(right) else laneRightEdges[lane] = right
        CommitLabel(tag, width, lane)
      }
    }
    val labelLanes = (labels.maxOfOrNull { it.lane } ?: -1) + 1
    val edgeSpace = labels.maxOfOrNull { it.width }?.div(2) ?: 0.dp
    val contentWidth = barWidth + edgeSpace * 2
    var hover by remember { mutableStateOf<CommitHover?>(null) }
    val scrolls = metrics.scrolls || contentWidth > maxWidth
    Column(if (scrolls) Modifier.horizontalScroll(rememberScrollState()) else Modifier) {
      Column(Modifier.width(contentWidth)) {
        Box(
          Modifier.offset(x = edgeSpace).width(barWidth)
            .pointerInput(commits, pitch) {
              val pitchPx = pitch.toPx()
              awaitPointerEventScope {
                while (true) {
                  val event = awaitPointerEvent(PointerEventPass.Initial)
                  val change = event.changes.firstOrNull() ?: continue
                  val index = (change.position.x / pitchPx).toInt()
                  val insideColumn = index in commits.indices &&
                    change.position.x >= index * pitchPx &&
                    change.position.x < index * pitchPx + column.toPx()
                  hover = if (event.type == PointerEventType.Exit || !insideColumn) null else CommitHover(index, change.position)
                }
              }
            }
            .pointerInput(commits, pitch) {
              val pitchPx = pitch.toPx()
              val columnPx = column.toPx()
              fun indexAt(x: Float, clamp: Boolean): Int? {
                val index = (x / pitchPx).toInt()
                return when {
                  index in commits.indices && x >= index * pitchPx && x < index * pitchPx + columnPx -> index
                  clamp && x < 0f -> 0
                  clamp && x >= commits.size * pitchPx - (pitchPx - columnPx) -> commits.lastIndex
                  else -> null
                }
              }
              awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val from = indexAt(down.position.x, false) ?: return@awaitEachGesture
                val extend = currentEvent.keyboardModifiers.isShiftPressed
                pick(commits[from].sha, extend)
                if (extend) return@awaitEachGesture
                var last = from
                while (true) {
                  val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                  if (!change.pressed) break
                  val next = indexAt(change.position.x, true) ?: continue
                  if (next != last) {
                    last = next
                    drag(commits[from].sha, commits[next].sha)
                  }
                }
              }
            },
        ) {
          Row(
            Modifier.width(barWidth).then(if (metrics.cards) Modifier.height(IntrinsicSize.Max) else Modifier),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
          ) {
            commits.forEachIndexed { index, commit ->
              val stat = stats[commit.sha]
              val description = commitTooltip(commit, stat)
              val isSelected = commit.sha in selected
              val hovered = hover?.index == index
              val surface = Modifier.width(column)
                .semantics {
                  role = Role.Button
                  this.selected = isSelected
                  contentDescription = description
                  onClick { pick(commit.sha, false); true }
                }
                .then(stripCardSurface(isSelected, hovered, colors.bgRaised))
              if (metrics.cards) {
                Column(
                  surface.fillMaxHeight().heightIn(min = 46.dp).padding(horizontal = 8.dp, vertical = 5.dp),
                  verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    BasicText(commit.sha.take(7), style = changesMono(10f, FontWeight.SemiBold, colors.accentText), maxLines = 1)
                    BasicText(commitTag(commit), Modifier.weight(1f), style = changesSans(10f, FontWeight.Medium, colors.fgMuted), softWrap = true)
                  }
                  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    BasicText(commit.subject, Modifier.weight(1f), style = changesSans(11f, FontWeight.Normal, colors.fg2), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    BasicText(
                      if (stat == null) "stats unavailable" else "${filesLabel(stat.files.size)} · +${stat.insertions}/−${stat.deletions}",
                      style = changesMono(9f, FontWeight.Normal, colors.fgFaint),
                      maxLines = 1,
                      softWrap = false,
                    )
                  }
                }
              } else {
                Column(Modifier.width(column), horizontalAlignment = Alignment.CenterHorizontally) {
                  Box(surface.height(STRIP_HEIGHT), contentAlignment = Alignment.BottomCenter) {
                    val fraction = commitBarFraction(stat, maxLines)
                    if (stat != null) Box(
                      Modifier.fillMaxWidth().fillMaxHeight(fraction).heightIn(min = MIN_BAR_HEIGHT)
                        .background(if (commit.operator) colors.stripIdle else colors.stripDone, RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp)),
                    )
                  }
                }
              }
            }
          }
          hover?.let { tip ->
            commits.getOrNull(tip.index)?.let { commit ->
              StripTipSurface(tip.position) {
                BasicText(
                  commitTooltip(commit, stats[commit.sha]),
                  style = changesMono(10.5f, FontWeight.Normal, colors.fg),
                )
              }
            }
          }
        }
        if (!metrics.cards && labels.isNotEmpty()) {
          Box(
            Modifier.offset(x = edgeSpace).padding(top = COMMIT_LABEL_TOP_GAP)
              .heightIn(max = COMMIT_LABELS_MAX_HEIGHT)
              .verticalScroll(rememberScrollState()),
          ) {
            Box(Modifier.width(barWidth).height(labelLaneHeight * labelLanes)) {
              Canvas(Modifier.fillMaxWidth().height(labelLaneHeight * labelLanes)) {
                labels.forEachIndexed { index, label ->
                  val centerX = (pitch * index + column / 2).toPx()
                  val labelTop = (labelLaneHeight * label.lane).toPx()
                  drawLine(
                    color = colors.borderStrong,
                    start = Offset(centerX, 0f),
                    end = Offset(centerX, labelTop),
                    strokeWidth = 1.dp.toPx(),
                    cap = StrokeCap.Round,
                  )
                }
              }
              labels.forEachIndexed { index, label ->
                val isSelected = commits[index].sha in selected
                BasicText(
                  label.text,
                  Modifier.offset(
                    x = pitch * index + column / 2 - label.width / 2,
                    y = labelLaneHeight * label.lane,
                  ).width(label.width),
                  style = changesMono(9f, FontWeight.Medium, if (isSelected) colors.accentText else colors.fgMuted),
                  maxLines = 1,
                  softWrap = false,
                )
              }
            }
          }
        }
      }
    }
  }
}
