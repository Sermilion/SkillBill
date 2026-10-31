package dev.skillbill.desktop.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillRadius
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.SbLabel
import dev.skillbill.desktop.core.domain.model.ChatPermissionMode
import dev.skillbill.desktop.core.domain.model.ChatSuggestedAction

/** The chat's messages, oldest first; it follows the newest message, including a streaming answer as it grows. */
@Composable
internal fun ChatTranscript(session: ChatSession, modifier: Modifier = Modifier, onAction: (ChatMessage.Agent, ChatSuggestedAction) -> Unit) {
  val busy by session.busy.collectAsState()
  val supported by session.agentSupported.collectAsState()
  val gone by session.gone.collectAsState()
  val mode by session.mode.collectAsState()
  val messages by session.transcript.collectAsState()
  val listState = rememberLazyListState()
  val newest = messages.lastOrNull() as? ChatMessage.Agent
  LaunchedEffect(messages.size, newest?.text, newest?.tools, newest?.actions, newest?.streaming) {
    if (messages.isEmpty()) return@LaunchedEffect
    listState.scrollToItem(messages.lastIndex)
    listState.layoutInfo.visibleItemsInfo.lastOrNull()?.let { listState.scrollBy(it.size.toFloat()) }
  }
  LazyColumn(modifier.fillMaxWidth(), listState, verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(12.dp)) {
    items(messages) { message ->
      ChatMessageRow(message, !busy && supported && !gone && message === messages.lastOrNull(), mode, onAction)
    }
  }
}

@Composable
private fun ChatMessageRow(
  message: ChatMessage,
  actionsEnabled: Boolean,
  mode: ChatPermissionMode,
  onAction: (ChatMessage.Agent, ChatSuggestedAction) -> Unit,
) {
  val colors = SkillBillTheme.colors
  val type = SkillBillTheme.type
  when (message) {
    is ChatMessage.StateLine -> SbLabel(message.text)
    is ChatMessage.Operator -> OperatorMessage(message.text)
    is ChatMessage.Agent -> Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
      message.tools.forEach { tool -> key(tool.call.id) { ChatToolRow(tool) } }
      if (message.text.isNotEmpty()) ChatMarkdown(message.text, Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp))
      if (!message.streaming && message.actions.isNotEmpty()) ChatActionButtons(message.actions, actionsEnabled, mode) { onAction(message, it) }
    }
    is ChatMessage.Notice -> BasicText(message.text, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = type.tBase.copy(color = colors.fgMuted))
    is ChatMessage.Error -> BasicText(message.text, Modifier.semantics { liveRegion = LiveRegionMode.Assertive }, style = type.tBase.copy(color = colors.stFailed))
  }
}

@Composable
private fun OperatorMessage(text: String) {
  BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
    BasicText(
      text,
      Modifier.widthIn(max = maxWidth * 0.85f)
        .background(SkillBillTheme.colors.bgInset, RoundedCornerShape(SkillBillRadius.sm))
        .padding(horizontal = 10.dp, vertical = 8.dp),
      style = SkillBillTheme.type.tBase.copy(color = SkillBillTheme.colors.fg, textAlign = TextAlign.Right),
    )
  }
}
