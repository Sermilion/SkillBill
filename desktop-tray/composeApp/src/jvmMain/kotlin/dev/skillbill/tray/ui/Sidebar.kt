package dev.skillbill.tray.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.skillbill.tray.core.designsystem.amber
import dev.skillbill.tray.core.designsystem.border
import dev.skillbill.tray.core.designsystem.dim
import dev.skillbill.tray.core.designsystem.mono
import dev.skillbill.tray.core.designsystem.sidebar
import dev.skillbill.tray.core.ui.ListScrollbar
import dev.skillbill.tray.feature.work.SidebarFilterField
import dev.skillbill.tray.navigation.Section
import dev.skillbill.tray.navigation.SectionMemory
import dev.skillbill.tray.navigation.WindowNavigator

/** The width the sidebar has always had. */
private val SIDEBAR_WIDTH = 250.dp

/**
 * The list pane: the filter field, one scrolling list, and the footer that switches sections and opens settings. The filter
 * text and the scroll anchor live in [memory], not in this composition, so a section that is left and entered again shows
 * them as they were.
 */
@Composable
fun Sidebar(
  navigator: WindowNavigator,
  memory: SectionMemory,
  scroll: LazyListState,
  onOpenSettings: () -> Unit,
  list: LazyListScope.() -> Unit,
) {
  LaunchedEffect(scroll, memory) {
    snapshotFlow { scroll.firstVisibleItemIndex to scroll.firstVisibleItemScrollOffset }
      .collect { (index, offset) ->
        memory.scrollIndex = index
        memory.scrollOffset = offset
      }
  }
  Column(Modifier.width(SIDEBAR_WIDTH).fillMaxHeight().background(sidebar)) {
    SidebarFilterField(memory.filter) { memory.filter = it }
    Box(Modifier.weight(1f)) {
      LazyColumn(Modifier.fillMaxSize().padding(end = 8.dp), state = scroll, content = list)
      ListScrollbar(scroll, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
    HorizontalDivider(color = border)
    Row(
      Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
      horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.End),
    ) {
      SidebarLink("Work", active = navigator.section == Section.WORK) { navigator.switchTo(Section.WORK) }
      SidebarLink("History", active = navigator.section == Section.HISTORY) { navigator.switchTo(Section.HISTORY) }
      SidebarLink("Settings", active = false, onClick = onOpenSettings)
    }
  }
}

@Composable
private fun SidebarLink(label: String, active: Boolean, onClick: () -> Unit) {
  Text(label, Modifier.clickable(onClick = onClick), color = if (active) amber else dim, fontFamily = mono, fontSize = 11.sp)
}
