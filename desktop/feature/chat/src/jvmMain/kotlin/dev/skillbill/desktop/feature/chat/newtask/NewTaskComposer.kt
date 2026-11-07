package dev.skillbill.desktop.feature.chat.newtask

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.component.Callout
import dev.skillbill.desktop.core.designsystem.component.FeedbackTone
import dev.skillbill.desktop.core.designsystem.component.SbButton
import dev.skillbill.desktop.core.designsystem.component.SbButtonVariant
import dev.skillbill.desktop.core.designsystem.component.SbControlSize
import dev.skillbill.desktop.core.designsystem.component.SbInput
import dev.skillbill.desktop.core.designsystem.component.SbLabel
import dev.skillbill.desktop.core.designsystem.component.SbPopover
import dev.skillbill.desktop.core.designsystem.component.SbPopoverPlacement
import dev.skillbill.desktop.core.domain.model.CursorMcpDependency
import dev.skillbill.desktop.core.ui.input.ShellLayer
import dev.skillbill.desktop.core.ui.input.gatesShortcutsWhileFocused
import dev.skillbill.desktop.feature.chat.AgentPicker
import dev.skillbill.desktop.feature.chat.CursorMcpPicker
import dev.skillbill.desktop.feature.chat.ChatActivityPill
import dev.skillbill.desktop.feature.chat.ResumePalette
import dev.skillbill.desktop.feature.chat.ResumeScope
import dev.skillbill.desktop.feature.chat.RESUME_COMMAND
import dev.skillbill.desktop.feature.chat.SendNowGate
import dev.skillbill.desktop.feature.chat.resumableDrafts
import kotlin.time.Clock
import kotlin.time.Instant

internal val COMPOSER_MAX_WIDTH = 728.dp

internal const val PICKERS_DISABLED_BUSY = "Wait for the current reply to finish"
internal const val PICKERS_DISABLED_STARTING = "Wait for the start to finish"
internal const val PICKERS_DISABLED_MISSING_REPO = "This draft's repository is missing"
private val REPOSITORY_PICKER_WIDTH = 620.dp

/** The task input with repository, agent, Send, Stop and Send now. Enter and Send share one condition; a busy turn queues the next message. */
@Composable
internal fun NewTaskComposer(state: NewTaskUiState, draft: NewTaskDraft, focus: FocusRequester = remember { FocusRequester() }, now: () -> Instant = Clock.System::now, onAction: (NewTaskAction) -> Unit) {
  LaunchedEffect(focus) { focus.requestFocus() }
  val focusManager = LocalFocusManager.current
  val chat by draft.chat.collectAsState()
  val busy = chat?.let { key(it) { it.thread.busy.collectAsState().value } } ?: false
  val stoppable = chat?.let { key(it) { it.thread.stoppable.collectAsState().value } } ?: false
  val queued = chat?.let { key(it) { it.thread.queued.collectAsState().value } }.orEmpty()
  val gate = remember(chat) { SendNowGate() }
  val cursorMcpDependency = draft.cursorMcpDependency
  val offer by draft.offer.collectAsState()
  val ready = !offer.starting && draft.canSend()
  val sendNow = stoppable && queued.isNotEmpty() && draft.text.isBlank() && !offer.starting
  val pickersDisabledWhy = when {
    busy -> PICKERS_DISABLED_BUSY
    offer.starting -> PICKERS_DISABLED_STARTING
    chat?.repositoryMissing == true -> PICKERS_DISABLED_MISSING_REPO
    else -> null
  }
  val pickersEnabled = pickersDisabledWhy == null
  val saved by draft.saved.collectAsState()
  val submit = {
    val startsTurn = ready && !busy && draft.text.trim() != RESUME_COMMAND
    draft.cursorMcpDependency = cursorMcpDependency
    onAction(NewTaskAction.Send)
    if (startsTurn) focusManager.clearFocus()
  }
  val press = { viaEnter: Boolean ->
    if (sendNow) {
      if (gate.admit(now(), viaEnter)) {
        onAction(NewTaskAction.SendNow)
        focus.requestFocus()
      }
    } else if (ready) {
      submit()
      gate.pressed(now(), viaEnter)
    }
  }
  Column(Modifier.widthIn(max = COMPOSER_MAX_WIDTH).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    // A draft whose repository is gone refuses every turn, so it has no activity to report and is not "Ready".
    chat?.takeUnless { it.repositoryMissing }?.let { key(it) { ChatActivityPill(it.thread) } }
    Box(Modifier.fillMaxWidth()) {
      SbInput(
        draft.text, { onAction(NewTaskAction.TextChanged(it)) },
        modifier = Modifier.fillMaxWidth().focusRequester(focus).gatesShortcutsWhileFocused()
          .onPreviewKeyEvent {
            if (it.key == Key.Enter && it.type == KeyEventType.KeyUp) gate.enterReleased()
            false
          },
        placeholder = if (chat != null) "Reply to the agent" else "Describe the work, or paste an issue key or spec path",
        multiline = true,
        onSubmit = { press(true) },
      )
      if (state.resumePickerOpen) {
        ResumePalette(
          resumableDrafts(saved, draft.repository?.root), now(), ResumeScope(draft.repository?.name, worktree = false),
          { it.newTask?.baseBranch }, focus, { onAction(NewTaskAction.ResumeDraft(it)) }, { onAction(NewTaskAction.DismissResume) },
        )
      }
    }
    if (queued.isNotEmpty()) SbLabel("${queued.size} ${if (queued.size == 1) "message" else "messages"} queued")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
      Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        RepositoryPicker(state, draft, chat, busy, offer.starting, focus, now, Modifier.weight(1f, fill = false), onAction)
        AgentPicker(state.agent, state.agents, enabled = pickersEnabled, disabledWhy = pickersDisabledWhy) { onAction(NewTaskAction.ChooseAgent(it)) }
        if (state.agent?.agentId?.startsWith("cursor", ignoreCase = true) == true) {
          CursorMcpPicker(cursorMcpDependency, enabled = pickersEnabled) { draft.cursorMcpDependency = it }
        }
      }
      Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (stoppable) {
          SbButton(
            "Stop", { onAction(NewTaskAction.Stop); focus.requestFocus() },
            size = SbControlSize.Small,
            tooltip = "Stop the reply. Queued messages return to the draft.",
          )
        }
        SbButton(
          when {
            sendNow -> "Send now"
            busy -> "Queue"
            else -> "Send"
          },
          { press(false) },
          variant = SbButtonVariant.Primary,
          size = SbControlSize.Small,
          enabled = ready || sendNow,
          shortcut = "↵",
        )
      }
    }
    state.agent?.approvalUnavailableReason?.let { reason ->
      SelectionContainer {
        Callout(
          tone = FeedbackTone.Info,
          body = reason,
          modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        )
      }
    }
  }
}

@Composable
private fun RepositoryPicker(state: NewTaskUiState, draft: NewTaskDraft, chat: NewTaskChat?, busy: Boolean, starting: Boolean, composerFocus: FocusRequester, now: () -> Instant, modifier: Modifier, onAction: (NewTaskAction) -> Unit) {
  val pillFocus = remember { FocusRequester() }
  val repositoryFilterFocus = remember { FocusRequester() }
  val branchFilterFocus = remember { FocusRequester() }
  var closeFocus by remember { mutableStateOf(pillFocus) }
  val close = { onAction(NewTaskAction.CloseRepositoryPicker) }
  val repository = draft.repository
  val mode = repositoryPillMode(chat, busy, starting, repository)
  Box(modifier) {
    NewTaskRepositoryPill(
      mode,
      repository?.name,
      repositoryPillBranch(draft.baseBranch, repository?.root, state.branchRead),
      pillFocus,
    ) { closeFocus = pillFocus; onAction(NewTaskAction.OpenRepositoryPicker) }
    val open = (mode == RepositoryPillMode.Interactive || mode == RepositoryPillMode.Choose) && state.repositoryPickerOpen
    if (open) ShellLayer(close)
    SbPopover(
      expanded = open,
      onDismissRequest = close,
      width = REPOSITORY_PICKER_WIDTH,
      maxWidth = REPOSITORY_PICKER_WIDTH,
      placement = SbPopoverPlacement.Above,
      returnFocusTo = closeFocus,
      arrowKeysMoveFocus = false,
    ) {
      NewTaskRepositoryPickerContent(state, draft, now = now, repositoryFilterFocus = repositoryFilterFocus, branchFilterFocus = branchFilterFocus, composerFocus = composerFocus, onAction = { action ->
        if (action is NewTaskAction.ChooseBaseBranch) closeFocus = composerFocus
        onAction(action)
      })
    }
  }
}
