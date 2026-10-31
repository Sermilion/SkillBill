package dev.skillbill.desktop.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillRadius
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.SbLabel

/** The chat's messages, oldest first; it follows the newest message, including a streaming answer as it grows. */
@Composable
internal fun ChatTranscript(session: ChatSession, modifier: Modifier = Modifier) {
  val messages by session.transcript.collectAsState()
  val listState = rememberLazyListState()
  val newest = messages.lastOrNull() as? ChatMessage.Agent
  LaunchedEffect(messages.size, newest?.text?.length, newest?.tools?.size) {
    if (messages.isEmpty()) return@LaunchedEffect
    listState.scrollToItem(messages.lastIndex)
    listState.layoutInfo.visibleItemsInfo.lastOrNull()?.let { listState.scrollBy(it.size.toFloat()) }
  }
  LazyColumn(modifier.fillMaxWidth(), listState, verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(12.dp)) {
    items(messages) { ChatMessageRow(it) }
  }
}

@Composable
private fun ChatMessageRow(message: ChatMessage) {
  val colors = SkillBillTheme.colors
  val type = SkillBillTheme.type
  when (message) {
    is ChatMessage.StateLine -> SbLabel(message.text)
    is ChatMessage.Operator -> MessageBlock(message.text, colors.bgInset)
    is ChatMessage.Agent -> Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
      message.tools.forEach { BasicText("Ran $it", style = type.monoSm.copy(color = colors.fgMuted)) }
      if (message.text.isNotEmpty()) MessageBlock(message.text, Color.Transparent)
    }
    is ChatMessage.Notice -> BasicText(message.text, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = type.tBase.copy(color = colors.fgMuted))
    is ChatMessage.Error -> BasicText(message.text, Modifier.semantics { liveRegion = LiveRegionMode.Assertive }, style = type.tBase.copy(color = colors.stFailed))
  }
}

@Composable
private fun MessageBlock(text: String, background: Color) {
  BasicText(
    text,
    Modifier.fillMaxWidth().background(background, RoundedCornerShape(SkillBillRadius.sm)).padding(horizontal = 10.dp, vertical = 8.dp),
    style = SkillBillTheme.type.tBase.copy(color = SkillBillTheme.colors.fg),
  )
}
