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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalFocusManager
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
import dev.skillbill.desktop.feature.chat.ChatActivityPill
import dev.skillbill.desktop.feature.chat.ChatPill
import dev.skillbill.desktop.feature.chat.ResumePicker
import dev.skillbill.desktop.feature.chat.RESUME_COMMAND
import kotlin.time.Clock
import kotlin.time.Instant

internal val COMPOSER_MAX_WIDTH = 728.dp

/** The task input with its repository, agent and Send controls. Enter and Send share one condition. A busy turn locks the pickers; a change rebinds the open chat. */
@Composable
internal fun NewTaskComposer(state: NewTaskUiState, draft: NewTaskDraft, focus: FocusRequester = remember { FocusRequester() }, now: () -> Instant = Clock.System::now, onAction: (NewTaskAction) -> Unit) {
  LaunchedEffect(focus) { focus.requestFocus() }
  val focusManager = LocalFocusManager.current
  val chat by draft.chat.collectAsState()
  val busy = chat?.let { key(it) { it.thread.busy.collectAsState().value } } ?: false
  val offer by draft.offer.collectAsState()
  val ready = !busy && !offer.starting && draft.canSend()
  val pickersEnabled = !busy && !offer.starting && chat?.repositoryMissing != true
  val saved by draft.saved.collectAsState()
  val submit = {
    val startsTurn = ready && draft.text.trim() != RESUME_COMMAND
    onAction(NewTaskAction.Send)
    if (startsTurn) focusManager.clearFocus()
  }
  Column(Modifier.widthIn(max = COMPOSER_MAX_WIDTH).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    // A draft whose repository is gone refuses every turn, so it has no activity to report and is not "Ready".
    chat?.takeUnless { it.repositoryMissing }?.let { key(it) { ChatActivityPill(it.thread) } }
    Box(Modifier.fillMaxWidth()) {
      SbInput(
        draft.text, { onAction(NewTaskAction.TextChanged(it)) },
        modifier = Modifier.fillMaxWidth().focusRequester(focus).gatesShortcutsWhileFocused(),
        placeholder = if (chat != null) "Reply, or press Start goal run" else "Describe the work, or paste an issue key or spec path",
        multiline = true,
        onSubmit = { if (ready) submit() },
      )
      if (state.resumePickerOpen) {
        ResumePicker(saved, now(), { onAction(NewTaskAction.ResumeDraft(it)) }, { onAction(NewTaskAction.DismissResume) })
      }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
      RepositoryPicker(state, draft, enabled = pickersEnabled, onAction)
      AgentPicker(state.agent, state.agents, enabled = pickersEnabled) { onAction(NewTaskAction.ChooseAgent(it)) }
      Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
        SbButton("Send", submit, variant = SbButtonVariant.Primary, size = SbControlSize.Small, enabled = ready, shortcut = "↵")
      }
    }
  }
}

@Composable
private fun RepositoryPicker(state: NewTaskUiState, draft: NewTaskDraft, enabled: Boolean, onAction: (NewTaskAction) -> Unit) {
  val close = { onAction(NewTaskAction.CloseRepositoryPicker) }
  Box {
    ChatPill(
      draft.repository?.let { "${it.name} · ${draft.baseBranch.ifBlank { "default branch" }}" } ?: "Choose repository",
      mono = true,
      icon = LucideIcons.folder,
      maxWidth = 280.dp,
      modifier = Modifier.then(if (enabled) Modifier.pointerHoverIcon(PointerIcon.Hand) else Modifier)
        .clickable(enabled = enabled, onClickLabel = "Change repository or base branch") { onAction(NewTaskAction.OpenRepositoryPicker) },
    )
    if (enabled && state.repositoryPickerOpen) {
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
