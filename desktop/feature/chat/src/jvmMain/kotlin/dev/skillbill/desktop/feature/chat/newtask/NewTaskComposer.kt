package dev.skillbill.desktop.feature.chat.newtask

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.component.SbButton
import dev.skillbill.desktop.core.designsystem.component.SbButtonVariant
import dev.skillbill.desktop.core.designsystem.component.SbControlSize
import dev.skillbill.desktop.core.designsystem.component.SbField
import dev.skillbill.desktop.core.designsystem.component.SbInput
import dev.skillbill.desktop.core.designsystem.component.SbMenu
import dev.skillbill.desktop.core.designsystem.component.SbMenuEntry
import dev.skillbill.desktop.core.designsystem.component.SbPopover
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.ui.input.ShellLayer
import dev.skillbill.desktop.core.ui.input.gatesShortcutsWhileFocused
import dev.skillbill.desktop.feature.chat.AgentPicker
import dev.skillbill.desktop.feature.chat.ChatPill

internal val COMPOSER_MAX_WIDTH = 728.dp

/** The task input with its repository, agent and Send controls. Send stays disabled, so Enter is consumed and does nothing. */
@Composable
internal fun NewTaskComposer(state: NewTaskUiState, draft: NewTaskDraft, focus: FocusRequester = remember { FocusRequester() }, onAction: (NewTaskAction) -> Unit) {
  LaunchedEffect(focus) { focus.requestFocus() }
  Column(Modifier.widthIn(max = COMPOSER_MAX_WIDTH).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    SbInput(
      draft.text, { onAction(NewTaskAction.TextChanged(it)) },
      modifier = Modifier.fillMaxWidth().focusRequester(focus).gatesShortcutsWhileFocused(),
      placeholder = "Describe the work, or paste an issue key or spec path",
      multiline = true,
      onSubmit = {},
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
      RepositoryPicker(state, draft, onAction)
      AgentPicker(state.agent, state.agents, enabled = true) { onAction(NewTaskAction.ChooseAgent(it)) }
      Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
        SbButton("Send", {}, variant = SbButtonVariant.Primary, size = SbControlSize.Small, enabled = false, shortcut = "↵")
      }
    }
  }
}

@Composable
private fun RepositoryPicker(state: NewTaskUiState, draft: NewTaskDraft, onAction: (NewTaskAction) -> Unit) {
  val close = { onAction(NewTaskAction.CloseRepositoryPicker) }
  Box {
    ChatPill(
      draft.repository?.let { "${it.name} · ${draft.baseBranch.ifBlank { "default branch" }}" } ?: "Choose repository",
      mono = true,
      icon = LucideIcons.folder,
      maxWidth = 280.dp,
      modifier = Modifier.pointerHoverIcon(PointerIcon.Hand).clickable(onClickLabel = "Change repository or base branch") { onAction(NewTaskAction.OpenRepositoryPicker) },
    )
    if (state.repositoryPickerOpen) {
      ShellLayer(close)
      SbPopover(expanded = true, onDismissRequest = close) { RepositoryPickerContent(state, draft, onAction) }
    }
  }
}

@Composable
private fun RepositoryPickerContent(state: NewTaskUiState, draft: NewTaskDraft, onAction: (NewTaskAction) -> Unit) {
  var path by remember { mutableStateOf("") }
  if (state.repositories.isNotEmpty()) {
    SbMenu(
      state.repositories.map { repository ->
        SbMenuEntry.Item(repository.name, { onAction(NewTaskAction.ChooseRepository(repository)) }, checked = repository.root == draft.repository?.root)
      },
    )
  }
  Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
    SbField("Repository path", error = state.repositoryError) {
      SbInput(
        path, { path = it },
        modifier = fieldModifier.fillMaxWidth().gatesShortcutsWhileFocused(),
        mono = true,
        isError = isError,
        onSubmit = { onAction(NewTaskAction.SubmitRepositoryPath(path)) },
      )
    }
    SbField("Base branch") {
      SbInput(
        draft.baseBranch, { onAction(NewTaskAction.BaseBranchChanged(it)) },
        modifier = fieldModifier.fillMaxWidth().gatesShortcutsWhileFocused(),
        mono = true,
      )
    }
  }
}
