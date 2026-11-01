package dev.skillbill.desktop.feature.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.Hint
import dev.skillbill.desktop.core.designsystem.component.SbIconButton
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.domain.model.DiffLine
import dev.skillbill.desktop.core.domain.model.DiffLineKind
import dev.skillbill.desktop.core.domain.model.FileDiff
import dev.skillbill.desktop.core.domain.model.FileDiffState
import dev.skillbill.desktop.core.ui.feedback.readProblemText
import dev.skillbill.desktop.core.ui.input.ShellEdit

private val GUTTER_WIDTH = 44.dp

/** A read-only unified diff of one changed file, shown in place of the detail document. Escape or the back button closes it. */
@Composable
internal fun DiffView(diff: DiffUi, onAction: (DetailAction) -> Unit, modifier: Modifier = Modifier) {
  ShellEdit(onCancel = { onAction(DetailAction.CloseDiff) }, onSave = {})
  val shown = ((diff.content as? DiffContent.Read)?.state as? FileDiffState.Shown)?.diff
  Column(modifier) {
    DiffHeader(diff, shown, onAction)
    Box(Modifier.weight(1f).fillMaxWidth()) {
      when {
        shown != null && shown.hunks.isNotEmpty() -> DiffLines(shown)
        else -> Box(Modifier.padding(16.dp)) { Hint(diffNotice(diff.content)) }
      }
    }
  }
}

private fun diffNotice(content: DiffContent): String = when (content) {
  DiffContent.Loading -> "Loading diff"
  DiffContent.NoLongerChanged -> "This file no longer has changes"
  is DiffContent.Read -> when (val state = content.state) {
    is FileDiffState.Shown -> "No content changes (rename or mode change)"
    FileDiffState.Binary -> "Binary file, no text diff"
    is FileDiffState.TooLarge -> "Diff is larger than ${state.limitBytes / 1024} KB"
    is FileDiffState.BaseUnavailable -> state.reason
    is FileDiffState.Unavailable -> readProblemText(state.problem)
  }
}

@Composable
private fun DiffHeader(diff: DiffUi, shown: FileDiff?, onAction: (DetailAction) -> Unit) {
  val colors = SkillBillTheme.colors
  val type = SkillBillTheme.type
  Row(
    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    SbIconButton(LucideIcons.arrowLeft, "Back to detail", { onAction(DetailAction.CloseDiff) }, shortcut = "Esc")
    BasicText(diff.file.label(), Modifier.weight(1f), style = type.monoSm.copy(color = colors.fg), maxLines = 1, overflow = TextOverflow.Ellipsis)
    if (shown != null) {
      BasicText("+${shown.insertions}", style = type.monoSm.copy(color = colors.stDone))
      BasicText("-${shown.deletions}", style = type.monoSm.copy(color = colors.stFailed))
    }
  }
}

@Composable
private fun DiffLines(diff: FileDiff) {
  val colors = SkillBillTheme.colors
  val type = SkillBillTheme.type
  LazyColumn(Modifier.fillMaxSize()) {
    diff.hunks.forEachIndexed { h, hunk ->
      item(key = "h$h") {
        BasicText(
          hunk.header,
          Modifier.fillMaxWidth().background(colors.diffHunkBg).padding(horizontal = 12.dp, vertical = 4.dp),
          style = type.monoSm.copy(color = colors.fgMuted),
        )
      }
      items(hunk.lines.size, key = { "l$h:$it" }) { index -> DiffLineRow(hunk.lines[index]) }
    }
  }
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
  // Long lines wrap inside the text cell, so the gutters stay aligned without a horizontal scroll.
  Row(Modifier.fillMaxWidth().then(background).padding(end = 12.dp)) {
    BasicText(line.oldNumber?.toString().orEmpty(), Modifier.width(GUTTER_WIDTH).padding(end = 6.dp), style = gutter)
    BasicText(line.newNumber?.toString().orEmpty(), Modifier.width(GUTTER_WIDTH).padding(end = 6.dp), style = gutter)
    BasicText(prefix, Modifier.width(14.dp), style = type.mono.copy(color = textColor))
    BasicText(line.text, Modifier.weight(1f), style = type.mono.copy(color = textColor))
  }
}
