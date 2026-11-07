package dev.skillbill.desktop.feature.chat.newtask

import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.skillbill.desktop.core.designsystem.SkillBillRadius
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.LocalUiTicker
import dev.skillbill.desktop.core.designsystem.component.sbFocusRing
import dev.skillbill.desktop.core.designsystem.detail.GroupLabel
import dev.skillbill.desktop.core.designsystem.detail.SbBox
import dev.skillbill.desktop.core.domain.model.ChatHistoryEntry
import dev.skillbill.desktop.core.domain.model.WorkItem
import kotlin.time.Instant

private val SECTION_SPACING = 8.dp

@Composable
internal fun InboxSection(
  needsYou: List<WorkItem>,
  running: List<WorkItem>,
  chats: List<ChatHistoryEntry>,
  savedCount: Int,
  selectedRoot: String?,
  home: NewTaskHome.Returning?,
  showAll: Boolean,
  now: () -> Instant,
  onOpenRun: (WorkItem) -> Unit,
  onResume: (ChatHistoryEntry) -> Unit,
  onDiscard: (ChatHistoryEntry) -> Unit,
  onShowAll: () -> Unit,
  onShowAllWork: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val colors = SkillBillTheme.colors
  val shape = RoundedCornerShape(SkillBillRadius.md)
  val ticker = LocalUiTicker.current
  var filter by remember { mutableStateOf(InboxFilter.All) }
  var selectedIndex by remember(filter) { mutableIntStateOf(0) }
  val cardInteraction = remember { MutableInteractionSource() }
  val cardFocused by cardInteraction.collectIsFocusedAsState()
  val cardHovered by cardInteraction.collectIsHoveredAsState()
  DisposableEffect(ticker) {
    ticker.retain()
    onDispose { ticker.release() }
  }
  val tick by ticker.now
  val current = tick.let { now() }
  val items = remember(needsYou, running, chats, selectedRoot, current, filter) {
    inboxItems(needsYou, running, chats, selectedRoot, current, filter = filter)
  }
  val visibleItems = remember(items) { items.filter { it !is InboxItem.Group } }
  val selected = selectedIndex.coerceIn(0, (visibleItems.size - 1).coerceAtLeast(0))
  LaunchedEffect(visibleItems) { selectedIndex = selected }
  Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(SECTION_SPACING)) {
    GroupLabel("Pick up where you left off", end = {
      InboxFilterControl(filter, needsYou.size, savedCount) { filter = it }
    })
    SbBox(
      Modifier.sbFocusRing(cardInteraction, shape)
        .hoverable(cardInteraction)
        .semantics {
          contentDescription = "Pick up where you left off"
          val row = visibleItems.getOrNull(selected)
          val title = when (row) {
            is InboxItem.Run -> row.item.title
            is InboxItem.Chat -> row.title.title
            else -> null
          }
          stateDescription = if (title == null) "No items" else "${selected + 1} of ${visibleItems.size}, $title"
        }
        .onFocusChanged { if (it.isFocused) selectedIndex = 0 }
        .onPreviewKeyEvent { event ->
          if (event.type != KeyEventType.KeyDown || event.isCtrlPressed || event.isMetaPressed || event.isAltPressed) return@onPreviewKeyEvent false
          val currentSelection = selectedIndex.coerceIn(0, visibleItems.lastIndex.coerceAtLeast(0))
          val row = visibleItems.getOrNull(currentSelection)
          when (event.key) {
            Key.DirectionDown -> { selectedIndex = (currentSelection + 1).coerceAtMost(visibleItems.lastIndex.coerceAtLeast(0)); true }
            Key.DirectionUp -> { selectedIndex = (currentSelection - 1).coerceAtLeast(0); true }
            Key.Enter, Key.NumPadEnter -> { row?.let { openInboxItem(it, onOpenRun, onResume) }; row != null }
            Key.Delete -> if (row is InboxItem.Chat) { onDiscard(row.entry); true } else false
            Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six, Key.Seven, Key.Eight, Key.Nine -> {
              val index = listOf(Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six, Key.Seven, Key.Eight, Key.Nine).indexOf(event.key)
              val target = visibleItems.getOrNull(index) ?: return@onPreviewKeyEvent false
              selectedIndex = index
              openInboxItem(target, onOpenRun, onResume)
              true
            }
            else -> false
          }
        }
        .focusable(interactionSource = cardInteraction),
    ) {
      if (items.isEmpty()) {
        BasicText("Nothing here. Start something below.", Modifier.padding(horizontal = 12.dp, vertical = 14.dp), style = inboxTextStyle(12.sp).copy(color = colors.fgFaint))
      } else {
        items.forEachIndexed { index, item ->
          val previous = items.getOrNull(index - 1)
          val rowIndex = visibleItems.indexOf(item)
          val divider = index > 0 && item !is InboxItem.Group && previous !is InboxItem.Group
          val selectedRow = cardFocused && selected == rowIndex
          val shortcut = (rowIndex + 1).takeIf { it in 1..9 && (cardHovered || cardFocused) }
          when (item) {
            is InboxItem.Group -> key(item.day) { InboxGroupHeader(item.day) }
            is InboxItem.Run -> key(item.item.id) { InboxRunRow(item, current, selectedRow, shortcut, divider, onOpenRun) }
            is InboxItem.Chat -> key(item.entry.sessionId) { InboxChatRow(item, current, selectedRow, shortcut, divider, onResume, onDiscard) }
          }
        }
      }
    }
    InboxHint(home, showAll, onShowAll, onShowAllWork)
  }
}

private fun openInboxItem(item: InboxItem, onOpenRun: (WorkItem) -> Unit, onResume: (ChatHistoryEntry) -> Unit) {
  when (item) {
    is InboxItem.Run -> onOpenRun(item.item)
    is InboxItem.Chat -> onResume(item.entry)
    is InboxItem.Group -> Unit
  }
}

