package dev.skillbill.desktop.core.ui.diff

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.domain.model.DiffLine
import dev.skillbill.desktop.core.domain.model.DiffLineKind
import dev.skillbill.desktop.core.domain.model.FileDiff
import dev.skillbill.desktop.core.domain.model.FileDiffState
import dev.skillbill.desktop.core.ui.feedback.readProblemText

private val GUTTER_WIDTH = 44.dp

@Composable
fun DiffLines(diff: FileDiff, modifier: Modifier = Modifier, state: LazyListState = rememberLazyListState()) {
  val colors = SkillBillTheme.colors
  val type = SkillBillTheme.type
  LazyColumn(modifier.fillMaxSize(), state = state) {
    diff.hunks.forEachIndexed { h, hunk ->
      item(key = "h$h") {
        Row(Modifier.fillMaxWidth().background(colors.diffHunkBg).padding(horizontal = 12.dp, vertical = 4.dp)) {
          BasicText(hunk.header, Modifier.weight(1f), style = type.monoSm.copy(color = colors.fgMuted))
          hunk.source?.let { BasicText(it, style = type.monoSm.copy(color = colors.fgMuted), maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
      }
      items(hunk.lines.size, key = { "l$h:$it" }) { index -> DiffLineRow(hunk.lines[index]) }
    }
  }
}

fun fileDiffNotice(state: FileDiffState): String = when (state) {
  is FileDiffState.Shown -> "No content changes (rename or mode change)"
  FileDiffState.Binary -> "Binary file, no text diff"
  is FileDiffState.TooLarge -> "Diff is larger than ${state.limitBytes / 1024} KB"
  is FileDiffState.BaseUnavailable -> state.reason
  is FileDiffState.Unavailable -> readProblemText(state.problem)
}

@Composable
private fun DiffLineRow(line: DiffLine) {
  val colors = SkillBillTheme.colors
  val type = SkillBillTheme.type
  val background = when (line.kind) {
    DiffLineKind.Added -> Modifier.background(colors.diffAddedBg)
    DiffLineKind.Removed -> Modifier.background(colors.diffRemovedBg)
    DiffLineKind.Context, DiffLineKind.NoNewlineMarker -> Modifier
  }
  val prefix = when (line.kind) {
    DiffLineKind.Added -> "+"
    DiffLineKind.Removed -> "-"
    DiffLineKind.Context -> " "
    DiffLineKind.NoNewlineMarker -> ""
  }
  val gutter = type.monoSm.copy(color = colors.fgFaint, textAlign = TextAlign.End)
  val textColor = if (line.kind == DiffLineKind.NoNewlineMarker) colors.fgMuted else colors.fg2
  Row(Modifier.fillMaxWidth().then(background).padding(end = 12.dp)) {
    BasicText(line.oldNumber?.toString().orEmpty(), Modifier.width(GUTTER_WIDTH).padding(end = 6.dp), style = gutter)
    BasicText(line.newNumber?.toString().orEmpty(), Modifier.width(GUTTER_WIDTH).padding(end = 6.dp), style = gutter)
    BasicText(prefix, Modifier.width(14.dp), style = type.mono.copy(color = textColor))
    BasicText(line.text, Modifier.weight(1f), style = type.mono.copy(color = textColor))
  }
}
