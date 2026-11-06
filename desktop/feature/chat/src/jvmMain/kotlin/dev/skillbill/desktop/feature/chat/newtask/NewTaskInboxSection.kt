package dev.skillbill.desktop.feature.chat.newtask

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillRadius
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.LocalUiTicker
import dev.skillbill.desktop.core.domain.model.ChatHistoryEntry
import dev.skillbill.desktop.core.domain.model.WorkItem
import kotlin.time.Instant

@Composable
internal fun RecentChatsSection(
  chats: List<ChatHistoryEntry>,
  selectedRoot: String?,
  now: () -> Instant,
  onResume: (ChatHistoryEntry) -> Unit,
  onDiscard: (ChatHistoryEntry) -> Unit,
  onSearch: () -> Unit,
  modifier: Modifier = Modifier,
) {
  HomeSection("Recent chats", chats.size, { SearchChatsButton(onSearch) }, modifier) {
    val current = rememberHomeNow(now)
    HomeCard {
      chats.forEachIndexed { index, entry ->
        key(entry.sessionId) {
          InboxChatRow(entry, selectedRoot, current, divider = index > 0, onResume, onDiscard)
        }
      }
    }
  }
}

@Composable
internal fun NeedsYouSection(
  home: NewTaskHome.Returning,
  now: () -> Instant,
  onOpenRun: (WorkItem) -> Unit,
  onShowAllWork: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val title = if (home.needsYou.isEmpty()) "All clear" else "Needs you"
  val count = home.needsYou.size.takeIf { it > 0 }
  HomeSection(title, count, { WorkSummaryLink(home.running, home.finished, onShowAllWork) }, modifier) {
    if (home.needsYou.isNotEmpty()) {
      val current = rememberHomeNow(now)
      HomeCard {
        home.needsYou.forEachIndexed { index, item ->
          key(item.id) { InboxRunRow(item, current, divider = index > 0, onOpenRun) }
        }
      }
    }
  }
}

@Composable
private fun HomeSection(title: String, count: Int?, action: @Composable () -> Unit, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
  Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    HomeSectionHeader(title, count, action)
    content()
  }
}

@Composable
private fun HomeCard(content: @Composable ColumnScope.() -> Unit) {
  val colors = SkillBillTheme.colors
  val shape = RoundedCornerShape(SkillBillRadius.md)
  Column(
    Modifier.fillMaxWidth().background(Color.White.copy(alpha = .012f), shape).clip(shape)
      .border(1.dp, colors.borderSubtle, shape).padding(1.dp),
    content = content,
  )
}

@Composable
private fun rememberHomeNow(now: () -> Instant): Instant {
  val ticker = LocalUiTicker.current
  DisposableEffect(ticker) {
    ticker.retain()
    onDispose { ticker.release() }
  }
  val tick by ticker.now
  return tick.let { now() }
}
