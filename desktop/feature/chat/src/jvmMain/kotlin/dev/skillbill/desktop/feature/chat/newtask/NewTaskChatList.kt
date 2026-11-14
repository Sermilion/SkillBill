package dev.skillbill.desktop.feature.chat.newtask

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.LocalReducedMotion
import dev.skillbill.desktop.core.designsystem.SkillBillRadius
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.SbControlSize
import dev.skillbill.desktop.core.designsystem.component.SbIconButton
import dev.skillbill.desktop.core.designsystem.component.SbTooltip
import dev.skillbill.desktop.core.designsystem.component.agentDisplayName
import dev.skillbill.desktop.core.designsystem.component.sbFocusRing
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.designsystem.icon.SbIcon
import dev.skillbill.desktop.core.designsystem.icon.SbIconSize
import dev.skillbill.desktop.core.designsystem.navigation.LocalSidebarToggle
import dev.skillbill.desktop.core.ui.pane.MOD_KEY
import dev.skillbill.desktop.feature.chat.chipText

internal data class OpenChatItem(
  val draft: NewTaskDraft,
  val title: String,
  val repositoryName: String?,
  val agentLabel: String,
  val status: NewTaskChatStatus,
  /** True once the chat has a conversation, which is when Discard has a save to delete. */
  val hasConversation: Boolean = false,
)

@Composable
internal fun rememberOpenChatItems(chats: NewTaskChats): List<OpenChatItem> {
  val open by chats.open.collectAsState()
  val titles by chats.chatTitles.collectAsState()
  return open.map { draft ->
    key(draft) {
      val chat by draft.chat.collectAsState()
      val transcript = chat?.thread?.transcript?.collectAsState()?.value.orEmpty()
      val sessionId = chat?.sessionIdFlow?.collectAsState()?.value
      val status by remember(draft) { chats.status(draft) }.collectAsState(NewTaskChatStatus.Idle)
      val generated = sessionId?.let(titles::get)
      OpenChatItem(draft, generated ?: newTaskChatTitle(transcript), draft.repository?.name, draft.selectedAgent?.let(::chipText) ?: draft.agentId?.let(::agentDisplayName).orEmpty(), status, chat != null)
    }
  }
}

internal fun filterOpenChats(items: List<OpenChatItem>, query: String): List<OpenChatItem> {
  val match = query.trim()
  return if (match.isEmpty()) items else items.filter { item ->
    item.title.contains(match, ignoreCase = true) || item.repositoryName?.contains(match, ignoreCase = true) == true
  }
}

internal fun openChatStatusText(item: OpenChatItem): String = when (item.status) {
  NewTaskChatStatus.Replying -> "replying…"
  NewTaskChatStatus.NewReply -> "new reply"
  NewTaskChatStatus.Idle -> item.agentLabel
}

@Composable
internal fun OpenChatRow(item: OpenChatItem, selected: Boolean, onClick: () -> Unit, onClose: (() -> Unit)?, onDiscard: (() -> Unit)? = null) {
  val colors = SkillBillTheme.colors
  val type = SkillBillTheme.type
  val interaction = remember { MutableInteractionSource() }
  val hovered by interaction.collectIsHoveredAsState()
  val shape = RoundedCornerShape(SkillBillRadius.sm)
  val menu = remember { ChatMenuAnchor() }
  val focus = remember { FocusRequester() }
  val hasMenu = onClose != null && onDiscard != null
  Box {
    Row(
      Modifier.fillMaxWidth().background(if (selected) colors.bgActive else if (hovered) colors.bgHover else Color.Transparent, shape)
        .then(if (hasMenu) Modifier.focusRequester(focus).chatMenuTrigger(menu) else Modifier)
        .hoverable(interaction).clickable(interaction, indication = null, onClick = onClick).pointerHoverIcon(PointerIcon.Hand)
        .sbFocusRing(interaction, shape)
        .semantics { role = Role.Button; this.selected = selected }.padding(horizontal = 8.dp, vertical = 6.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      NewTaskChatMarker(item.status)
      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        BasicText(item.title, style = type.tSm.copy(color = if (item.status == NewTaskChatStatus.NewReply) colors.fg else colors.fg2, fontWeight = if (item.status == NewTaskChatStatus.NewReply) FontWeight.Bold else FontWeight.Normal), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
          item.repositoryName?.let {
            BasicText(it, Modifier.weight(1f, fill = false), style = type.monoSm.copy(color = colors.fgFaint, fontSize = type.tXs.fontSize), maxLines = 1, overflow = TextOverflow.Ellipsis)
          }
          if (item.repositoryName != null) BasicText("·", style = type.tXs.copy(color = colors.fgFaint), maxLines = 1)
          val statusModifier = if (item.repositoryName != null) Modifier.weight(1f, fill = false) else Modifier
          BasicText(openChatStatusText(item), statusModifier, style = type.tXs.copy(color = colors.fgFaint), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
      }
      if (onClose != null) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
          if (hovered) {
            val closeInteraction = remember { MutableInteractionSource() }
            val closeHovered by closeInteraction.collectIsHoveredAsState()
            val closeShape = RoundedCornerShape(SkillBillRadius.sm)
            SbTooltip("Close", "$MOD_KEY W") {
              Box(Modifier.size(16.dp).background(if (closeHovered) colors.bgHover else Color.Transparent, closeShape)
                .hoverable(closeInteraction).clickable(closeInteraction, indication = null, onClick = onClose)
                .sbFocusRing(closeInteraction, closeShape).semantics { contentDescription = "Close ${item.title}"; role = Role.Button }, contentAlignment = Alignment.Center) {
                SbIcon(LucideIcons.x, SbIconSize.Xs)
              }
            }
          }
        }
      }
    }
    if (onClose != null && onDiscard != null) NewTaskChatMenu(menu, item, onClose, onDiscard, focus)
  }
}

/** The side-list layout keeps the chat rail independent from the selected chat content. */
@Composable
internal fun NewTaskChatList(
  items: List<OpenChatItem>, selected: NewTaskDraft, onSelect: (NewTaskDraft) -> Unit,
  onNewChat: () -> Unit, onClose: (NewTaskDraft) -> Unit, onDiscard: (NewTaskDraft) -> Unit, modifier: Modifier = Modifier,
) {
  val colors = SkillBillTheme.colors
  val type = SkillBillTheme.type
  val listState = rememberLazyListState()
  val reducedMotion = LocalReducedMotion.current
  LaunchedEffect(items.map { it.draft }, selected, reducedMotion) {
    val index = items.indexOfFirst { it.draft === selected }
    if (index >= 0) {
      val visible = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
      if (visible == null || visible.offset < 0 || visible.offset + visible.size > listState.layoutInfo.viewportEndOffset) {
        if (reducedMotion) listState.scrollToItem(index) else listState.animateScrollToItem(index)
      }
    }
  }
  Column(modifier.width(248.dp).fillMaxHeight().background(colors.bgFrame).drawBehind {
    drawLine(colors.borderSubtle, Offset(size.width - 0.5f, 0f), Offset(size.width - 0.5f, size.height), 1.dp.toPx())
  }) {
    Row(Modifier.fillMaxWidth().height(38.dp).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      // New task has no view header, so the rail carries the sidebar toggle while the sidebar is a drawer.
      LocalSidebarToggle.current?.let { SbIconButton(LucideIcons.panelLeft, "Toggle sidebar", it, size = SbControlSize.Small, shortcut = "[") }
      BasicText("Open chats · ${items.size}", Modifier.weight(1f), style = type.tXs.copy(color = colors.fgFaint))
      SbIconButton(LucideIcons.plus, "New chat", onNewChat, size = SbControlSize.Small, shortcut = "$MOD_KEY T")
    }
    LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, verticalArrangement = Arrangement.spacedBy(2.dp)) {
      items(items, key = { it.draft }) { item ->
        OpenChatRow(item, item.draft === selected, onClick = { onSelect(item.draft) }, onClose = { onClose(item.draft) }, onDiscard = { onDiscard(item.draft) })
      }
    }
    Column(Modifier.fillMaxWidth().drawBehind { drawLine(colors.borderSubtle, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) }.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        BasicText("Replying", style = type.tXs.copy(color = colors.fgFaint))
        BasicText(items.count { it.status == NewTaskChatStatus.Replying }.toString(), style = type.tXs.copy(color = colors.fgFaint))
      }
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        BasicText("New replies", style = type.tXs.copy(color = colors.fgFaint))
        BasicText(items.count { it.status == NewTaskChatStatus.NewReply }.toString(), style = type.tXs.copy(color = colors.fgFaint))
      }
    }
  }
}
