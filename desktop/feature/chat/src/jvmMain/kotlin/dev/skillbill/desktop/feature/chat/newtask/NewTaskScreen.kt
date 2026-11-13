package dev.skillbill.desktop.feature.chat.newtask

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.LocalReducedMotion
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.Callout
import dev.skillbill.desktop.core.designsystem.component.FeedbackTone
import dev.skillbill.desktop.core.designsystem.hoverSpec
import dev.skillbill.desktop.core.domain.model.ChatPermissionMode
import dev.skillbill.desktop.core.domain.model.ChatSuggestedAction
import dev.skillbill.desktop.core.domain.model.NewTaskActionKind
import dev.skillbill.desktop.core.domain.model.WorkItem
import dev.skillbill.desktop.core.ui.changes.ChangesSource
import dev.skillbill.desktop.core.domain.settings.NewTaskChatLayout
import dev.skillbill.desktop.feature.chat.ChatApprovalActions
import dev.skillbill.desktop.feature.chat.ChatTranscript
import dev.skillbill.desktop.feature.chat.chipText
import dev.skillbill.desktop.feature.chat.resumableDrafts
import kotlin.time.Instant

private val CONTENT_MAX_WIDTH = 720.dp

private const val DIMMED_ALPHA = .45f

/** What the screen asks of its host. A null [onStart] keeps the chips disabled until a start path exists. */
internal class NewTaskScreenHost(
  val now: () -> Instant,
  val onOpenRun: (WorkItem) -> Unit,
  val onShowAllWork: () -> Unit,
  val onStart: ((NewTaskStartOrigin, String, String?, ChatSuggestedAction?) -> Unit)? = null,
  val onOpenChatChanges: (ChangesSource.Chat, String?) -> Unit = { _, _ -> },
)

@Composable
internal fun NewTaskScreen(state: NewTaskUiState, chats: NewTaskChats, host: NewTaskScreenHost, onAction: (NewTaskAction) -> Unit) {
  val selected by chats.selected.collectAsState()
  val pendingClose by chats.pendingClose.collectAsState()
  val items = rememberOpenChatItems(chats)
  LaunchedEffect(Unit) { chats.refreshSaved() }
  Column(Modifier.fillMaxSize().onPreviewKeyEvent { handleChatShortcut(it, chats) }) {
    when (state.newTaskChatLayout) {
      NewTaskChatLayout.Tabs -> {
        NewTaskChatTabs(items, selected, chats::select, chats::requestClose, chats::discard, onNew = chats::newChat)
        key(selected) { NewTaskChatContent(state, selected, chats, host, onAction) }
      }
      NewTaskChatLayout.SideList -> Row(Modifier.weight(1f)) {
        NewTaskChatList(items, selected, chats::select, chats::newChat, chats::requestClose, chats::discard)
        Column(Modifier.weight(1f)) {
          key(selected) { NewTaskChatContent(state, selected, chats, host, onAction) }
        }
      }
    }
  }
  pendingClose?.let { draft ->
    val transcript = draft.chat.collectAsState().value?.thread?.transcript?.collectAsState()?.value.orEmpty()
    NewTaskCloseDialog(newTaskChatTitle(transcript), chipText(draft.selectedAgent), chats::keepOpen, chats::confirmClose)
  }
}

/** Ctrl or Cmd with T, W, Tab, Shift+Tab and 1 to 9 act on the open chats; anything else goes on to the composer and the shell. */
private fun handleChatShortcut(event: KeyEvent, chats: NewTaskChats): Boolean {
  if (event.type != KeyEventType.KeyDown || !(event.isCtrlPressed || event.isMetaPressed) || event.isAltPressed) return false
  return when (val key = event.key) {
    Key.T -> {
      chats.newChat()
      true
    }
    Key.W -> {
      chats.requestClose(chats.selected.value)
      true
    }
    Key.Tab -> {
      chats.selectRelative(if (event.isShiftPressed) -1 else 1)
      true
    }
    // indexOf gives -1 for a non-digit key, and selectAt(-1) finds no chat.
    else -> chats.selectAt(DIGIT_KEYS.indexOf(key))
  }
}

private val DIGIT_KEYS = listOf(Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six, Key.Seven, Key.Eight, Key.Nine)

@Composable
private fun ColumnScope.NewTaskChatContent(
  state: NewTaskUiState,
  draft: NewTaskDraft,
  chats: NewTaskChats,
  host: NewTaskScreenHost,
  onAction: (NewTaskAction) -> Unit,
) {
  val chat = draft.chat.collectAsState().value
  val offer by draft.offer.collectAsState()
  val chatBusy = chat?.thread?.busy?.collectAsState()?.value ?: false
  val contextRevision = draft.contextRevision
  val selectedRoot = draft.repository?.root
  val startOrigin = NewTaskStartOrigin(draft, chat, contextRevision, selectedRoot)
  val focusManager = LocalFocusManager.current
  val focus = remember { FocusRequester() }
  if (chat == null) {
    NewTaskHomeBody(state, draft, chats, host, focus, onAction, Modifier.weight(1f))
  } else {
    key(chat) {
      ChatTranscript(
        chat.thread,
        gone = false,
        mode = ChatPermissionMode.Act,
        modifier = Modifier.weight(1f).align(Alignment.CenterHorizontally).widthIn(max = COMPOSER_MAX_WIDTH),
        allowStartActions = host.onStart != null,
        startIssueKey = draft.acceptedIssueKey,
        allowSuggestedActions = !offer.starting,
        footer = if (offer.prefixKind != null) {
          {
            NewTaskIssueKeyPrompt(
              offer.choices,
              selectedKey = offer.selectedKey,
              linkedKeys = draft.issueKeyProposals,
              enabled = !chatBusy && !offer.starting && offer.started == null,
              onChoose = { draft.selectIssueKeyForContext(contextRevision, selectedRoot, it) },
              onStart = {
                draft.startForContext(
                  contextRevision,
                  chat,
                  selectedRoot,
                  checkNotNull(offer.prefixKind),
                  offer.selectedKey,
                  offer.action,
                  offer.action?.target,
                )
              },
            )
          }
        } else draft.acceptedIssueKey?.let { key ->
          { Callout(tone = FeedbackTone.Info, body = "Selected issue key: $key") }
        },
        approvals = ChatApprovalActions(
          live = chat.approvalLive.collectAsState().value,
          onDecide = chat::decideApproval,
          onDismiss = chat::dismissApproval,
        ),
        operationCards = draft.operationCardActions(chat),
      ) { _, action ->
        val start = action.start
        if (start == null) {
          if (draft.sendSuggestedActionForContext(contextRevision, selectedRoot, action)) {
            focusManager.clearFocus()
          }
        } else if (host.onStart != null) {
          val monitorTarget =
            action.target?.trim()?.takeIf(String::isNotBlank)
              ?: action.instruction.trim().takeIf { start == NewTaskActionKind.Monitor }
          if (draft.contextRevision == contextRevision && draft.repository?.root == selectedRoot) {
            host.onStart?.invoke(startOrigin, start.wire, monitorTarget, action)
          }
        }
      }
    }
  }
  Column(
    Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 16.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    if (chat == null && state.home is NewTaskHome.Returning) {
      val startable = draft.repository != null && draft.agentId != null
      NewTaskChips(
        host.onStart?.takeIf { startable }?.let { onStart -> { wire, target -> onStart(startOrigin, wire, target, null) } },
        Modifier.widthIn(max = COMPOSER_MAX_WIDTH).fillMaxWidth(),
        qaDeviceName = state.qaDeviceName,
      )
    }
    NewTaskComposer(state, draft, focus, host.now, onOpenChanges = host.onOpenChatChanges, onAction = onAction)
  }
}

@Composable
private fun NewTaskHomeBody(
  state: NewTaskUiState,
  draft: NewTaskDraft,
  chats: NewTaskChats,
  host: NewTaskScreenHost,
  focus: FocusRequester,
  onAction: (NewTaskAction) -> Unit,
  modifier: Modifier,
) {
  val colors = SkillBillTheme.colors
  val type = SkillBillTheme.type
  val reducedMotion = LocalReducedMotion.current
  val preview = previewRoute(draft.text)
  val progress = if (!reducedMotion && preview == null) rememberIdleProgress() else null
  val homeAlpha by animateFloatAsState(if (preview != null) DIMMED_ALPHA else 1f, hoverSpec())
  val saved by draft.saved.collectAsState()
  val openSessionIds by chats.openSessionIds.collectAsState()
  val needsYouFocusRequester = remember { FocusRequester() }
  val root = draft.repository?.root
  val recent = remember(saved, root) { resumableDrafts(saved, root) }
  BoxWithConstraints(modifier.fillMaxWidth()) {
    Column(
      Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight).padding(24.dp),
      verticalArrangement = Arrangement.Center,
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      val returning = state.home as? NewTaskHome.Returning
      val hasNeedsYouCard = returning != null && returning.needsYou.isNotEmpty()
      val hasSections = recent.isNotEmpty() || returning != null
      Column(Modifier.widthIn(max = CONTENT_MAX_WIDTH).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(if (hasSections) 32.dp else 40.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          BasicText("What should we ship?", Modifier.semantics { heading() }, style = type.t2xl.copy(color = colors.fg))
          BasicText(
            "Describe the work or paste an issue. Sona plans it, builds it, audits it and opens the PR.",
            style = type.tBase.copy(color = colors.fgMuted),
          )
        }
        StepLine(progress, preview)
        if (recent.isNotEmpty()) {
          RecentChatsSection(
            chats = recent,
            selectedRoot = root,
            openSessionIds = openSessionIds,
            now = host.now,
            onResume = { onAction(NewTaskAction.ResumeDraft(it)) },
            onDiscard = { onAction(NewTaskAction.DiscardDraft(it)) },
            onSearch = { onAction(NewTaskAction.ShowAllDrafts) },
            modifier = Modifier.alpha(homeAlpha),
            nextCardFocusRequester = if (hasNeedsYouCard) needsYouFocusRequester else FocusRequester.Default,
          )
        }
        if (returning != null) {
          NeedsYouSection(
            returning,
            host.now,
            host.onOpenRun,
            host.onShowAllWork,
            Modifier.alpha(homeAlpha),
            needsYouFocusRequester.takeIf { hasNeedsYouCard },
          )
        }
        if (state.home == NewTaskHome.FirstRun) {
          ExamplesSection(
            readinessLine(state.detectedAgents, draft.repository?.name),
            onExample = {
              onAction(NewTaskAction.TextChanged(it))
              focus.requestFocus()
            },
            modifier = Modifier.alpha(homeAlpha),
          )
        }
      }
    }
  }
}
