package dev.skillbill.desktop.feature.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.Hint
import dev.skillbill.desktop.core.designsystem.component.SbIconButton
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.domain.model.FileDiff
import dev.skillbill.desktop.core.domain.model.FileDiffState
import dev.skillbill.desktop.core.ui.diff.DiffLines
import dev.skillbill.desktop.core.ui.diff.fileDiffNotice
import dev.skillbill.desktop.core.ui.input.ShellEdit

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
  is DiffContent.Read -> fileDiffNotice(content.state)
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
