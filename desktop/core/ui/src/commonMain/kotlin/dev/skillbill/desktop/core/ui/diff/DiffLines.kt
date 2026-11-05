package dev.skillbill.desktop.core.ui.diff

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.hoverSpec
import dev.skillbill.desktop.core.domain.model.DiffHunk
import dev.skillbill.desktop.core.domain.model.DiffLine
import dev.skillbill.desktop.core.domain.model.DiffLineKind
import dev.skillbill.desktop.core.domain.model.FileDiff
import dev.skillbill.desktop.core.domain.model.FileDiffState
import dev.skillbill.desktop.core.ui.changes.changesMono
import dev.skillbill.desktop.core.ui.feedback.readProblemText

private val GUTTER_WIDTH = 52.dp
private val LINE_HEIGHT = 20.dp
private val TEXT_START = 12.dp
private const val FONT_SIZE = 12f
private const val LINE_HEIGHT_SP = 20f
private const val TAB = "        "
private const val HUNK_LABEL_GAP_CHARS = 2

/** A longer line, such as a minified bundle, is cut: layout cannot measure a row millions of pixels wide. */
private const val MAX_LINE_CHARS = 5_000
private const val CUT_MARKER_CHARS = 32

/**
 * Gives [content] a width that fits [longestChars] characters of diff text, or the viewport when that is wider, and scrolls
 * sideways when it does not fit: diff rows do not wrap.
 */
@Composable
fun DiffHorizontalScroll(longestChars: Int, modifier: Modifier = Modifier, scrollState: ScrollState = rememberScrollState(), content: @Composable () -> Unit) {
  val measurer = rememberTextMeasurer()
  val density = LocalDensity.current
  val mono = changesMono(FONT_SIZE, FontWeight.Normal, SkillBillTheme.colors.fg2, LINE_HEIGHT_SP)
  val charWidth = remember(measurer, mono, density) { with(density) { measurer.measure("0", mono).size.width.toDp() } }
  BoxWithConstraints(modifier.fillMaxSize()) {
    val width = maxOf(maxWidth, GUTTER_WIDTH * 2 + TEXT_START * 2 + charWidth * longestChars)
    Box(Modifier.horizontalScroll(scrollState).width(width).fillMaxHeight()) { content() }
  }
}

/** One row per hunk header and line of [diff], keyed under [key]. */
data class DiffLineAddress(val hunk: Int, val line: Int)

class DiffLineSlots(
  val keys: Map<DiffLineAddress, List<String>>,
  val content: @Composable LazyItemScope.(String) -> Unit,
) {
  companion object {
    val None = DiffLineSlots(emptyMap()) { }
  }
}

class DiffLineAffordance(val onAdd: (DiffLine) -> Unit, val onHover: (DiffLine, Boolean) -> Unit)

fun LazyListScope.diffRows(diff: FileDiff, key: String, slots: DiffLineSlots = DiffLineSlots.None, affordance: DiffLineAffordance? = null) {
  diff.hunks.forEachIndexed { h, hunk ->
    item(key = "$key:h$h", contentType = "hunk") { HunkHeaderRow(hunk) }
    hunk.lines.forEachIndexed { i, line ->
      item(key = "$key:l$h:$i", contentType = "line") { DiffLineRow(line, affordance) }
      val threadIds = slots.keys[DiffLineAddress(h, i)].orEmpty()
      items(threadIds, key = { "thread:$it" }, contentType = { "thread" }, itemContent = slots.content)
    }
  }
}

/** How many rows [diffRows] adds for [diff]. */
fun diffRowCount(diff: FileDiff, slots: DiffLineSlots = DiffLineSlots.None): Int = diff.hunks.indices.sumOf { h ->
  val hunk = diff.hunks[h]
  hunk.lines.size + 1 + hunk.lines.indices.sumOf { slots.keys[DiffLineAddress(h, it)]?.size ?: 0 }
}

/** The widest row of [diff], in characters of diff text. */
fun diffLongestChars(diff: FileDiff): Int = diff.hunks.maxOfOrNull(::hunkChars) ?: 0

fun fileDiffNotice(state: FileDiffState): String = when (state) {
  is FileDiffState.Shown, FileDiffState.Binary -> "Binary file or no textual changes."
  is FileDiffState.TooLarge -> "Diff is larger than ${state.limitBytes / 1024} KB"
  is FileDiffState.BaseUnavailable -> state.reason
  is FileDiffState.Unavailable -> readProblemText(state.problem)
}

private fun hunkChars(hunk: DiffHunk): Int {
  val header = hunk.header.length + (hunk.source?.let { it.length + HUNK_LABEL_GAP_CHARS } ?: 0)
  return maxOf(header, hunk.lines.maxOfOrNull { shownChars(it) + 1 } ?: 0)
}

/** The length of [displayText] without building it. */
private fun shownChars(line: DiffLine): Int {
  val expanded = line.text.length + line.text.count { it == '\t' } * (TAB.length - 1)
  return if (expanded <= MAX_LINE_CHARS) expanded else MAX_LINE_CHARS + CUT_MARKER_CHARS
}

internal fun displayText(line: DiffLine): String {
  val text = line.text.replace("\t", TAB)
  return if (text.length <= MAX_LINE_CHARS) text else "${text.take(MAX_LINE_CHARS)} … ${text.length - MAX_LINE_CHARS} more characters"
}

@Composable
private fun Modifier.gutterRules(): Modifier {
  val color = SkillBillTheme.colors.border
  return drawBehind {
    val stroke = 1.dp.toPx()
    val gutter = GUTTER_WIDTH.toPx()
    listOf(gutter, gutter * 2).forEach { edge -> drawLine(color, Offset(edge - stroke / 2, 0f), Offset(edge - stroke / 2, size.height), stroke) }
  }
}

@Composable
private fun HunkHeaderRow(hunk: DiffHunk) {
  val colors = SkillBillTheme.colors
  Row(Modifier.fillMaxWidth().background(colors.diffHunkBg).gutterRules()) {
    Row(Modifier.padding(start = GUTTER_WIDTH * 2 + TEXT_START, top = 6.dp, bottom = 6.dp, end = TEXT_START), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      BasicText(hunk.header, style = changesMono(FONT_SIZE, FontWeight.Normal, colors.stPlanning, LINE_HEIGHT_SP), maxLines = 1, softWrap = false)
      hunk.source?.let { BasicText(it, style = changesMono(FONT_SIZE, FontWeight.Normal, colors.fgFaint, LINE_HEIGHT_SP), maxLines = 1, softWrap = false) }
    }
  }
}

@Composable
private fun DiffLineRow(line: DiffLine, affordance: DiffLineAffordance?) {
  val colors = SkillBillTheme.colors
  val background = when (line.kind) {
    DiffLineKind.Added -> Modifier.background(colors.diffAddedBg)
    DiffLineKind.Removed -> Modifier.background(colors.diffRemovedBg)
    DiffLineKind.Context, DiffLineKind.NoNewlineMarker -> Modifier
  }
  val prefix = when (line.kind) {
    DiffLineKind.Added -> "+"
    DiffLineKind.Removed -> "−"
    DiffLineKind.Context -> " "
    DiffLineKind.NoNewlineMarker -> ""
  }
  val textColor = when (line.kind) {
    DiffLineKind.Added, DiffLineKind.Removed -> colors.fg
    DiffLineKind.Context -> colors.fg2
    DiffLineKind.NoNewlineMarker -> colors.fgMuted
  }
  val gutter = changesMono(FONT_SIZE, FontWeight.Normal, colors.fgFaint, LINE_HEIGHT_SP).copy(textAlign = TextAlign.End)
  val interaction = remember(line) { MutableInteractionSource() }
  val hovered by interaction.collectIsHoveredAsState()
  val focused by interaction.collectIsFocusedAsState()
  val visibility by animateFloatAsState(if ((hovered || focused) && affordance != null) 1f else 0f, hoverSpec(), label = "diff-comment-affordance")
  val currentAffordance by rememberUpdatedState(affordance)
  LaunchedEffect(hovered, line, affordance != null) { currentAffordance?.onHover(line, hovered) }
  DisposableEffect(line, affordance != null) {
    val onHover = currentAffordance?.onHover
    onDispose { onHover?.invoke(line, false) }
  }
  val density = LocalDensity.current
  Box(Modifier.fillMaxWidth().height(LINE_HEIGHT).then(background).gutterRules().then(if (affordance != null) Modifier.hoverable(interaction) else Modifier)) {
    androidx.compose.foundation.layout.Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
    GutterCell(line.oldNumber, GUTTER_WIDTH, gutter)
    GutterCell(line.newNumber, GUTTER_WIDTH, gutter)
    BasicText(
      prefix + remember(line) { displayText(line) },
      Modifier.padding(start = TEXT_START),
      style = changesMono(FONT_SIZE, FontWeight.Normal, textColor, LINE_HEIGHT_SP),
      maxLines = 1,
      softWrap = false,
    )
    }
    if (affordance != null && line.kind != DiffLineKind.NoNewlineMarker) {
      Box(
        Modifier.offset { IntOffset(with(density) { (GUTTER_WIDTH * 2 - 10.dp).roundToPx() }, 0) }
          .align(Alignment.CenterStart).size(18.dp).alpha(visibility).scale(.85f + visibility * .15f)
          .clip(RoundedCornerShape(5.dp)).background(SkillBillTheme.colors.accent)
          .clickable(interactionSource = interaction, indication = null, role = Role.Button) { affordance.onAdd(line) }
          .pointerHoverIcon(PointerIcon.Hand)
          .semantics { contentDescription = "Add comment" },
        contentAlignment = Alignment.Center,
      ) { BasicText("+", style = changesMono(13f, FontWeight.SemiBold, SkillBillTheme.colors.accentFg)) }
    }
  }
}

@Composable
private fun GutterCell(number: Int?, width: Dp, style: androidx.compose.ui.text.TextStyle) {
  BasicText(number?.toString().orEmpty(), Modifier.width(width).padding(end = 10.dp), style = style, maxLines = 1)
}
