package dev.skillbill.desktop.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.LocalContentColor
import dev.skillbill.desktop.core.designsystem.SkillBillRadius
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.Avatar
import dev.skillbill.desktop.core.designsystem.component.Callout
import dev.skillbill.desktop.core.designsystem.component.FeedbackTone
import dev.skillbill.desktop.core.designsystem.component.SbControlSize
import dev.skillbill.desktop.core.designsystem.component.SbIconButton
import dev.skillbill.desktop.core.designsystem.component.SbLabel
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.designsystem.icon.SbIcon
import dev.skillbill.desktop.core.designsystem.icon.SbIconSize
import dev.skillbill.desktop.core.domain.model.ChatAgent
import dev.skillbill.desktop.core.domain.model.ChatPermissionMode
import dev.skillbill.desktop.core.domain.model.ChatSuggestedAction
import java.awt.Toolkit
import kotlinx.coroutines.flow.filter
import java.awt.datatransfer.StringSelection

/** The chat's messages, oldest first; it follows the newest message, including a streaming answer as it grows. */
@Composable
internal fun ChatTranscript(session: ChatSession, modifier: Modifier = Modifier, onAction: (ChatMessage.Agent, ChatSuggestedAction) -> Unit) {
  val busy by session.busy.collectAsState()
  val supported by session.agentSupported.collectAsState()
  val gone by session.gone.collectAsState()
  val mode by session.mode.collectAsState()
  val agent by session.agent.collectAsState()
  val messages by session.transcript.collectAsState()
  val listState = rememberLazyListState()
  // Follows the bottom until the operator scrolls away from it; scrolling back to the end resumes following.
  var following by remember { mutableStateOf(true) }
  LaunchedEffect(listState) {
    snapshotFlow { listState.isScrollInProgress }.filter { !it }.collect { following = !listState.canScrollForward }
  }
  LaunchedEffect(messages.size) {
    following = true
    listState.scrollToEnd()
  }
  // Content can grow without a new message (streamed text, tool rows, rewrapping), so follow the height, not the keys.
  LaunchedEffect(listState) {
    snapshotFlow { listState.canScrollForward }.filter { it }.collect {
      if (following && !listState.isScrollInProgress) listState.scrollToEnd()
    }
  }
  LazyColumn(
    modifier.fillMaxWidth(),
    listState,
    verticalArrangement = Arrangement.spacedBy(18.dp),
    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
  ) {
    items(messages) { message ->
      ChatMessageRow(message, agent, !busy && supported && !gone && message === messages.lastOrNull(), mode, onAction)
    }
  }
}

/** Jumps to the last item, then scrolls until its bottom edge is in view; each `scrollBy` remeasures, so no stale layout is read. */
private suspend fun LazyListState.scrollToEnd() {
  val last = layoutInfo.totalItemsCount - 1
  if (last < 0) return
  scrollToItem(last)
  scroll {
    repeat(MAX_END_SCROLL_STEPS) { if (scrollBy(END_SCROLL_STEP_PX) == 0f) return@scroll }
  }
}

private const val END_SCROLL_STEP_PX = 10_000f
private const val MAX_END_SCROLL_STEPS = 20

@Composable
private fun ChatMessageRow(
  message: ChatMessage,
  agent: ChatAgent?,
  actionsEnabled: Boolean,
  mode: ChatPermissionMode,
  onAction: (ChatMessage.Agent, ChatSuggestedAction) -> Unit,
) {
  val colors = SkillBillTheme.colors
  val type = SkillBillTheme.type
  when (message) {
    is ChatMessage.StateLine -> StateCard(message)
    is ChatMessage.Operator -> OperatorMessage(message)
    is ChatMessage.Agent -> AgentTurn(message, agent, actionsEnabled, mode) { onAction(message, it) }
    is ChatMessage.Notice -> Row(
      Modifier.semantics { liveRegion = LiveRegionMode.Polite },
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = Alignment.Top,
    ) {
      CompositionLocalProvider(LocalContentColor provides colors.fgMuted) { SbIcon(LucideIcons.info, SbIconSize.Sm, modifier = Modifier.padding(top = 2.dp)) }
      BasicText(message.text, style = type.tSm.copy(color = colors.fgMuted))
    }
    is ChatMessage.Error -> Callout(
      tone = FeedbackTone.Error,
      body = message.text,
      icon = LucideIcons.alert,
      modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
    )
  }
}

@Composable
private fun StateCard(message: ChatMessage.StateLine) {
  val state = message.state ?: return SbLabel(message.text)
  val colors = SkillBillTheme.colors
  val type = SkillBillTheme.type
  val tone = stateTone(state, colors)
  val shape = RoundedCornerShape(SkillBillRadius.md)
  Row(
    Modifier.fillMaxWidth().border(1.dp, colors.border, shape).background(colors.bgRaised, shape).padding(12.dp),
    horizontalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    CompositionLocalProvider(LocalContentColor provides tone) { SbIcon(stateIcon(state), SbIconSize.Md, modifier = Modifier.padding(top = 2.dp)) }
    SelectionContainer {
      Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        BasicText(
          listOfNotNull(state.name, message.phase?.let { "on $it" }).joinToString(" "),
          style = type.tBaseMedium.copy(color = colors.fg),
        )
        message.detail?.let { BasicText(it, style = type.tSm.copy(color = colors.fgMuted)) }
      }
    }
  }
}

@Composable
private fun OperatorMessage(message: ChatMessage.Operator) {
  val colors = SkillBillTheme.colors
  val type = SkillBillTheme.type
  val action = message.action
  var showInstruction by rememberSaveable(message) { mutableStateOf(false) }
  BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
    Column(Modifier.widthIn(max = maxWidth * 0.85f), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
      val shape = RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp, bottomStart = 10.dp, bottomEnd = 4.dp)
      SelectionContainer {
        Column(
          Modifier.border(1.dp, colors.border, shape).background(colors.bgOverlay, shape).padding(horizontal = 12.dp, vertical = 8.dp),
          verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          BasicText(action?.label ?: message.text, style = type.tBase.copy(color = colors.fg))
          if (action != null && showInstruction) BasicText(message.text, style = type.tSm.copy(color = colors.fgMuted))
        }
      }
      if (action != null) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
          ModeBadge(action.mode)
          BasicText(
            if (showInstruction) "Hide instruction" else "Suggested action · show instruction",
            Modifier.clickable(role = Role.Button) { showInstruction = !showInstruction }.pointerHoverIcon(PointerIcon.Hand),
            style = type.t2xs.copy(color = colors.fgMuted),
          )
        }
      }
    }
  }
}

@Composable
private fun AgentTurn(
  message: ChatMessage.Agent,
  agent: ChatAgent?,
  actionsEnabled: Boolean,
  mode: ChatPermissionMode,
  onAction: (ChatSuggestedAction) -> Unit,
) {
  val colors = SkillBillTheme.colors
  val type = SkillBillTheme.type
  Column(Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(10.dp)) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
      val agentId = agent?.agentId ?: "agent"
      Avatar(agentId, small = true)
      BasicText(agentId.replaceFirstChar { it.uppercase() }, style = type.tBaseMedium.copy(color = colors.fg))
      val start = message.startedAt
      val end = message.finishedAt
      val timing = when {
        start == null -> null
        message.streaming -> "Working · ${durationLabel(liveElapsed(start))}"
        end != null -> durationLabel(end - start)
        else -> null
      }
      val meta = listOfNotNull(agent?.model, timing.takeIf { !message.streaming }).joinToString(" · ")
      if (meta.isNotEmpty()) BasicText(meta, style = type.tSm.copy(color = colors.fgMuted))
      if (message.streaming) BasicText(timing ?: "Working", style = type.tSm.copy(color = colors.accentText))
    }
    if (message.tools.isNotEmpty()) ChatToolGroup(message.tools, message.streaming)
    if (message.text.isNotEmpty()) ChatMarkdown(message.text, Modifier.fillMaxWidth())
    if (!message.streaming && message.text.isNotEmpty()) ReplyFooter(message.text)
    if (!message.streaming && message.actions.isNotEmpty()) ChatActionButtons(message.actions, actionsEnabled, mode, onAction)
  }
}

@Composable
private fun ReplyFooter(text: String) {
  Row(Modifier.padding(start = 0.dp), verticalAlignment = Alignment.CenterVertically) {
    SbIconButton(
      icon = LucideIcons.copy,
      label = "Copy reply",
      size = SbControlSize.Small,
      onClick = {
        val selection = StringSelection(text)
        Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, selection)
      },
    )
  }
}
