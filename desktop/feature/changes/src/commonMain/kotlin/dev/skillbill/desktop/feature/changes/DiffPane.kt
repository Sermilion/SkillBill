package dev.skillbill.desktop.feature.changes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.FeedbackTone
import dev.skillbill.desktop.core.designsystem.component.Hint
import dev.skillbill.desktop.core.designsystem.component.SbButton
import dev.skillbill.desktop.core.designsystem.component.SbButtonVariant
import dev.skillbill.desktop.core.designsystem.component.SbControlSize
import dev.skillbill.desktop.core.designsystem.component.SbTooltip
import dev.skillbill.desktop.core.domain.model.ChangeStatus
import dev.skillbill.desktop.core.domain.model.ChangedFile
import dev.skillbill.desktop.core.domain.model.FileDiffState
import dev.skillbill.desktop.core.ui.diff.DiffLines
import dev.skillbill.desktop.core.ui.diff.fileDiffNotice

@Composable
internal fun DiffPane(state: ChangesWindowUiState, onAction: (ChangesWindowAction) -> Unit, modifier: Modifier = Modifier) {
  Column(modifier.padding(12.dp)) {
    when (val diff = state.diff) {
      DiffPaneUi.NoSelection -> Hint("Select a file")
      is DiffPaneUi.Loading -> Hint("Loading diff")
      is DiffPaneUi.Shown -> {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          BasicText(diff.file.status.letter(), style = SkillBillTheme.type.monoSm.copy(color = SkillBillTheme.colors.fgMuted))
          diff.file.directory()?.let {
            BasicText(it, style = SkillBillTheme.type.monoSm.copy(color = SkillBillTheme.colors.fgMuted), maxLines = 1, overflow = TextOverflow.Ellipsis)
          }
          BasicText(diff.file.name(), Modifier.weight(1f), style = SkillBillTheme.type.monoSm.copy(color = SkillBillTheme.colors.fg), maxLines = 1, overflow = TextOverflow.Ellipsis)
          if (state.list !is ScopeListUi.Listed) Unit else if (diff.file.binary) BasicText("binary", style = SkillBillTheme.type.monoSm.copy(color = SkillBillTheme.colors.fgMuted)) else {
            BasicText("+${diff.file.insertions ?: 0}", style = SkillBillTheme.type.monoSm.copy(color = SkillBillTheme.colors.stDone))
            BasicText("-${diff.file.deletions ?: 0}", style = SkillBillTheme.type.monoSm.copy(color = SkillBillTheme.colors.stFailed))
          }
          // A deleted file has nothing on disk to open.
          SbButton(
            "Open",
            { onAction(ChangesWindowAction.OpenSelectedFile) },
            enabled = state.selectedFile?.let { it.status != ChangeStatus.Deleted } == true,
            size = SbControlSize.Small,
            shortcut = "O",
          )
        }
        state.openProblem?.let { Hint(it, tone = FeedbackTone.Error) }
        if (diff.chips.isNotEmpty()) Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
          BasicText(if (diff.chips.size == 1) "Changed by subtask" else "Changed by subtasks")
          diff.chips.forEach { chip -> SbTooltip(chip.name) {
            SbButton(
              "${chip.id}",
              { onAction(ChangesWindowAction.NarrowToSubtask(chip.id)) },
              enabled = chip.selectable,
              variant = if (chip.inScope) SbButtonVariant.Primary else SbButtonVariant.Ghost,
              size = SbControlSize.Small,
            )
          } }
        }
        when (val content = diff.state) {
          is FileDiffState.Shown -> if (content.diff.hunks.isEmpty()) {
            Hint(fileDiffNotice(content))
          } else {
            key(diff.file.path) { DiffLines(content.diff, Modifier.fillMaxSize()) }
          }
          else -> Hint(fileDiffNotice(content))
        }
      }
    }
  }
}

private fun ChangedFile.directory(): String? = path.substringBeforeLast('/', "").ifEmpty { null }

private fun ChangedFile.name(): String = path.substringAfterLast('/')
