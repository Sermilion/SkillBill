package dev.skillbill.desktop.core.ui.diff

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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

/** One unwrapped, horizontally scrolling row per line under two line-number gutters. */
@Composable
fun DiffLines(diff: FileDiff, modifier: Modifier = Modifier, state: LazyListState = rememberLazyListState()) {
  val measurer = rememberTextMeasurer()
  val density = LocalDensity.current
  val mono = changesMono(FONT_SIZE, FontWeight.Normal, SkillBillTheme.colors.fg2, LINE_HEIGHT_SP)
  val charWidth = remember(measurer, mono, density) { with(density) { measurer.measure("0", mono).size.width.toDp() } }
  val longest = remember(diff) { diff.hunks.maxOfOrNull(::hunkChars) ?: 0 }
  BoxWithConstraints(modifier.fillMaxSize()) {
    val content = maxOf(maxWidth, GUTTER_WIDTH * 2 + TEXT_START * 2 + charWidth * longest)
    LazyColumn(Modifier.horizontalScroll(rememberScrollState()).width(content).fillMaxHeight(), state = state) {
      diff.hunks.forEachIndexed { h, hunk ->
        item(key = "h$h") { HunkHeaderRow(hunk) }
        items(hunk.lines.size, key = { "l$h:$it" }) { index -> DiffLineRow(hunk.lines[index]) }
      }
    }
  }
}

fun fileDiffNotice(state: FileDiffState): String = when (state) {
  is FileDiffState.Shown, FileDiffState.Binary -> "Binary file or no textual changes."
  is FileDiffState.TooLarge -> "Diff is larger than ${state.limitBytes / 1024} KB"
  is FileDiffState.BaseUnavailable -> state.reason
  is FileDiffState.Unavailable -> readProblemText(state.problem)
}

private fun hunkChars(hunk: DiffHunk): Int {
  val header = hunk.header.length + (hunk.source?.let { it.length + HUNK_LABEL_GAP_CHARS } ?: 0)
  return maxOf(header, hunk.lines.maxOfOrNull { displayText(it).length + 1 } ?: 0)
}

private fun displayText(line: DiffLine): String = line.text.replace("\t", TAB)

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
private fun DiffLineRow(line: DiffLine) {
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
  Row(Modifier.fillMaxWidth().height(LINE_HEIGHT).then(background).gutterRules(), verticalAlignment = Alignment.CenterVertically) {
    GutterCell(line.oldNumber, GUTTER_WIDTH, gutter)
    GutterCell(line.newNumber, GUTTER_WIDTH, gutter)
    BasicText(
      prefix + displayText(line),
      Modifier.padding(start = TEXT_START),
      style = changesMono(FONT_SIZE, FontWeight.Normal, textColor, LINE_HEIGHT_SP),
      maxLines = 1,
      softWrap = false,
    )
  }
}

@Composable
private fun GutterCell(number: Int?, width: Dp, style: androidx.compose.ui.text.TextStyle) {
  BasicText(number?.toString().orEmpty(), Modifier.width(width).padding(end = 10.dp), style = style, maxLines = 1)
}
