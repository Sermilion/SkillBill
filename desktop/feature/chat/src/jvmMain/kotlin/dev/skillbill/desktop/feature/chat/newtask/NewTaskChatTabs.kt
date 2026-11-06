package dev.skillbill.desktop.feature.chat.newtask

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillRadius
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.SbControlSize
import dev.skillbill.desktop.core.designsystem.component.SbIconButton
import dev.skillbill.desktop.core.designsystem.component.SbSpinner
import dev.skillbill.desktop.core.designsystem.component.sbFocusRing
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.ui.pane.MOD_KEY
import dev.skillbill.desktop.feature.chat.ChatMessage
import kotlinx.coroutines.flow.Flow

private const val EMPTY_CHAT_TITLE = "New chat"

/** The first line of the chat's first operator message, or "New chat" before the operator sends one. */
internal fun newTaskChatTitle(transcript: List<ChatMessage>): String =
  transcript.firstNotNullOfOrNull { it as? ChatMessage.Operator }?.text?.lineSequence()?.firstOrNull()?.trim()
    ?.takeIf(String::isNotBlank) ?: EMPTY_CHAT_TITLE

/** A chat's reply state: a spinner while it replies, an accent dot for an unread reply, nothing when idle. */
@Composable
internal fun NewTaskChatMarker(status: NewTaskChatStatus, modifier: Modifier = Modifier) {
  Box(modifier.size(14.dp), contentAlignment = Alignment.Center) {
    when (status) {
      NewTaskChatStatus.Replying -> SbSpinner(size = 11.dp, contentDescription = "Replying")
      NewTaskChatStatus.NewReply -> {
        val accent = SkillBillTheme.colors.accent
        Box(Modifier.size(7.dp).background(accent, CircleShape).semantics { contentDescription = "New reply" })
      }
      NewTaskChatStatus.Idle -> Unit
    }
  }
}

/** One tab per open chat in [open] order, then the button that opens a new chat. */
@Composable
internal fun NewTaskChatTabs(
  open: List<NewTaskDraft>,
  selected: NewTaskDraft,
  status: (NewTaskDraft) -> Flow<NewTaskChatStatus>,
  onSelect: (NewTaskDraft) -> Unit,
  onNew: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val colors = SkillBillTheme.colors
  val divider = colors.borderSubtle
  Row(
    modifier.fillMaxWidth().height(36.dp).background(colors.bgFrame)
      .drawBehind { drawLine(divider, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1.dp.toPx()) }
      .padding(horizontal = 8.dp, vertical = 4.dp),
    horizontalArrangement = Arrangement.spacedBy(2.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    open.forEach { draft ->
      NewTaskChatTab(draft, draft === selected, remember(draft) { status(draft) }, onClick = { onSelect(draft) })
    }
    SbIconButton(LucideIcons.plus, EMPTY_CHAT_TITLE, onNew, size = SbControlSize.Small, shortcut = "$MOD_KEY T")
  }
}

@Composable
private fun NewTaskChatTab(draft: NewTaskDraft, selected: Boolean, statusFlow: Flow<NewTaskChatStatus>, onClick: () -> Unit) {
  val colors = SkillBillTheme.colors
  val type = SkillBillTheme.type
  val chat by draft.chat.collectAsState()
  val transcript = chat?.thread?.transcript?.collectAsState()?.value.orEmpty()
  val status by statusFlow.collectAsState(NewTaskChatStatus.Idle)
  val unread = status == NewTaskChatStatus.NewReply
  val shape = RoundedCornerShape(SkillBillRadius.sm)
  val interaction = remember { MutableInteractionSource() }
  Row(
    Modifier.widthIn(min = 112.dp, max = 196.dp).fillMaxHeight()
      .background(if (selected) colors.bgPanel else Color.Transparent, shape)
      .border(1.dp, if (selected) colors.borderSubtle else Color.Transparent, shape)
      .clickable(interaction, indication = null, onClick = onClick)
      .sbFocusRing(interaction, shape)
      .semantics {
        role = Role.Tab
        this.selected = selected
      }
      .padding(horizontal = 8.dp),
    horizontalArrangement = Arrangement.spacedBy(6.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    NewTaskChatMarker(status)
    BasicText(
      newTaskChatTitle(transcript),
      style = if (unread) type.tSm.copy(color = colors.fg, fontWeight = FontWeight.Medium) else type.tSm.copy(color = if (selected) colors.fg else colors.fg2),
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
}
