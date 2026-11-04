package dev.skillbill.desktop.feature.changes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.FeedbackTone
import dev.skillbill.desktop.core.designsystem.component.Hint
import dev.skillbill.desktop.core.designsystem.component.SbTooltip
import dev.skillbill.desktop.core.domain.model.ChangeStatus
import dev.skillbill.desktop.core.domain.model.ChangedFile
import dev.skillbill.desktop.core.domain.model.DiffCommentsState
import dev.skillbill.desktop.core.ui.changes.AddCount
import dev.skillbill.desktop.core.ui.changes.ChangesButton
import dev.skillbill.desktop.core.ui.changes.DelCount
import dev.skillbill.desktop.core.ui.changes.changesMono
import dev.skillbill.desktop.core.ui.changes.changesSans
import dev.skillbill.desktop.core.ui.changes.hairlineBottom

@Composable
internal fun DiffPane(state: ChangesWindowUiState, onAction: (ChangesWindowAction) -> Unit, modifier: Modifier = Modifier, onReplyFocusChanged: (String, Boolean) -> Unit) {
  val colors = SkillBillTheme.colors
  Column(modifier.background(colors.bgInset)) {
    val file = when (val diff = state.diff) {
      DiffPaneUi.NoSelection -> null
      is DiffPaneUi.Loading -> diff.file
      is DiffPaneUi.Shown -> diff.file
    }
    Column(
      Modifier.fillMaxWidth().background(colors.bgPanel).hairlineBottom(colors.border).padding(horizontal = 14.dp, vertical = 10.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      if (file == null) {
        BasicText("Select a file", Modifier.heightIn(min = 16.dp), style = changesMono(12f, FontWeight.Medium, colors.fgMuted, lineHeight = 15.6f), maxLines = 1)
      } else {
        DiffTitle(file, showCounts = state.list is ScopeListUi.Listed, canOpen = state.selectedFile?.let { it.status != ChangeStatus.Deleted } == true) {
          onAction(ChangesWindowAction.OpenSelectedFile)
        }
      }
      state.openProblem?.let { Hint(it, tone = FeedbackTone.Error) }
      (state.diff as? DiffPaneUi.Shown)?.chips?.takeIf { it.isNotEmpty() }?.let { chips -> SubtaskChips(chips, onAction) }
    }
    if (state.list is ScopeListUi.Listed) {
      // A new scope or filter is a new stream, which starts on the selected file.
      key(state.scope, state.filter) {
        val threads = (state.comments as? DiffCommentsState.Available)?.threads.orEmpty()
        FileDiffStream(
          files = state.visibleFiles,
          loaded = state.fileDiffs,
          selectedPath = state.selectedPath,
          scrollRequest = state.scrollRequest,
          threads = threads,
          threadContent = { id ->
            threads.firstOrNull { it.id == id }?.let { thread ->
              DiffCommentThread(thread, state.threadUi[id] ?: ThreadUi(), onAction, onReplyFocusChanged)
            }
          },
          onAction = onAction,
          modifier = Modifier.weight(1f),
        )
      }
    }
  }
}

@Composable
private fun DiffTitle(file: ChangedFile, showCounts: Boolean, canOpen: Boolean, onOpen: () -> Unit) {
  val colors = SkillBillTheme.colors
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
    BasicText(file.status.letter(), style = changesMono(11f, FontWeight.SemiBold, file.status.statusColor()), maxLines = 1)
    val path = buildAnnotatedString {
      withStyle(SpanStyle(color = colors.fgFaint)) { append(file.directory()) }
      append(file.path.substringAfterLast('/'))
    }
    BasicText(path, Modifier.weight(1f), style = changesMono(12f, FontWeight.Medium, colors.fg, lineHeight = 15.6f), maxLines = 1, overflow = TextOverflow.Ellipsis)
    if (showCounts) {
      if (file.binary) {
        BasicText("binary", style = changesMono(11f, FontWeight.Medium, colors.fgFaint), maxLines = 1, softWrap = false)
      } else {
        AddCount(file.insertions ?: 0)
        DelCount(file.deletions ?: 0)
      }
    }
    ChangesButton("Open in editor", onOpen, enabled = canOpen)
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SubtaskChips(chips: List<SubtaskChipUi>, onAction: (ChangesWindowAction) -> Unit) {
  FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp), itemVerticalAlignment = Alignment.CenterVertically) {
    BasicText(
      if (chips.size == 1) "Changed by subtask" else "Changed by subtasks",
      style = changesSans(11f, FontWeight.Normal, SkillBillTheme.colors.fgFaint),
      maxLines = 1,
      softWrap = false,
    )
    chips.forEach { chip -> SubtaskChip(chip) { onAction(ChangesWindowAction.NarrowToSubtask(chip.id)) } }
  }
}

@Composable
private fun SubtaskChip(chip: SubtaskChipUi, onClick: () -> Unit) {
  val colors = SkillBillTheme.colors
  val interaction = remember { MutableInteractionSource() }
  val hovered by interaction.collectIsHoveredAsState()
  val shape = RoundedCornerShape(5.dp)
  val hot = chip.selectable && hovered
  val border = when {
    hot -> colors.accent
    chip.inScope -> colors.accent.copy(alpha = 0.4f)
    else -> colors.borderStrong
  }
  val content = if (hot || chip.inScope) colors.accentText else colors.fg2
  SbTooltip(chip.name) {
    BasicText(
      "${chip.id}",
      Modifier.alpha(if (chip.selectable) 1f else 0.4f)
        .then(if (chip.inScope) Modifier.background(colors.accentTint, shape) else Modifier)
        .border(1.dp, border, shape)
        .then(if (chip.selectable) Modifier.pointerHoverIcon(PointerIcon.Hand) else Modifier)
        .clickable(interaction, indication = null, enabled = chip.selectable, role = Role.Button, onClick = onClick)
        .padding(horizontal = 6.dp, vertical = 3.dp),
      style = changesMono(10.5f, FontWeight.Medium, content),
      maxLines = 1,
      softWrap = false,
    )
  }
}

/** The file's directory with its trailing slash; empty for a root-level file. */
internal fun ChangedFile.directory(): String = path.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
