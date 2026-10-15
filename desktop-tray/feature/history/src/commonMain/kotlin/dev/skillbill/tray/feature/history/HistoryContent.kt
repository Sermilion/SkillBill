package dev.skillbill.tray.feature.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.skillbill.tray.core.designsystem.DesignLabel
import dev.skillbill.tray.core.designsystem.amber
import dev.skillbill.tray.core.designsystem.body
import dev.skillbill.tray.core.designsystem.dim
import dev.skillbill.tray.core.designsystem.muted
import dev.skillbill.tray.core.domain.model.HistoryFilter
import dev.skillbill.tray.core.domain.model.WorkIdentity
import dev.skillbill.tray.core.ui.AppendState
import dev.skillbill.tray.core.ui.LoadNextPageOnScroll
import dev.skillbill.tray.core.ui.PageFooter
import dev.skillbill.tray.core.ui.PagedListState
import dev.skillbill.tray.core.ui.SidebarWorkRow

private const val FOOTER_KEY = "history:footer"

/**
 * The finished-work list as rows of the window's one sidebar list, below the live work. The window owns the list, its
 * [scroll] state and the filter text, so history and work share one scroll region.
 */
fun LazyListScope.historySection(state: HistoryUiState, selected: WorkIdentity?, scroll: LazyListState, onAction: (HistoryAction) -> Unit) {
  val list = state.list
  val rows = (list as? PagedListState.Content)?.items.orEmpty()
  item(key = "history:header") { DesignLabel("History · ${rows.size}", Modifier.padding(start = 14.dp, top = 14.dp, bottom = 6.dp)) }
  item(key = "history:filters") {
    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
      HistoryFilter.entries.forEach { choice ->
        TextButton(onClick = { onAction(HistoryAction.ChooseFilter(choice)) }, contentPadding = PaddingValues(horizontal = 8.dp)) {
          Text(choice.name.lowercase().replaceFirstChar(Char::titlecase), color = if (state.historyFilter == choice) amber else muted, fontFamily = body, fontSize = 11.sp)
        }
      }
    }
    Text("Finished within ${state.retention.cliValue}", Modifier.padding(start = 16.dp, bottom = 6.dp), color = dim, style = MaterialTheme.typography.bodySmall)
    state.pendingFeedback.firstOrNull()?.let { feedback ->
      Text(feedback.message, Modifier.clickable { onAction(HistoryAction.AcknowledgeFeedback(feedback.id)) }.padding(start = 16.dp, bottom = 6.dp), color = amber, style = MaterialTheme.typography.bodySmall)
    }
  }
  when (list) {
    PagedListState.InitialLoading -> item(key = "history:loading") { Note("Loading...") }
    is PagedListState.InitialError -> item(key = "history:problem") { Problem(list.problem.summary) { onAction(HistoryAction.Retry) } }
    is PagedListState.LoadedEmpty -> {
      if (list.refreshing) item(key = "history:refreshing") { Note("Updating...") }
      item(key = "history:empty") { Note(if (state.filter.isNotBlank()) "No matching work" else "No finished work") }
    }
    is PagedListState.Content -> {
      if (list.refreshing) item(key = "history:refreshing") { Note("Updating...") }
      list.readError?.let { problem -> item(key = "history:problem") { Problem(problem.summary) { onAction(HistoryAction.Retry) } } }
      items(rows, key = { "history:${it.identity.repositoryIdentity}:${it.identity.id}" }) { item ->
        SidebarWorkRow(item, selected == item.identity) { onAction(HistoryAction.Select(item.identity)) }
      }
      if (list.append != AppendState.Exhausted) item(key = FOOTER_KEY) {
        LoadNextPageOnScroll(scroll, list.append !is AppendState.Failed, list.append == AppendState.Loading, FOOTER_KEY) { onAction(HistoryAction.LoadMore) }
        PageFooter(list.append) { onAction(HistoryAction.LoadMore) }
      }
    }
  }
}

@Composable
private fun Note(text: String) {
  Text(text, Modifier.padding(start = 16.dp, bottom = 8.dp), color = muted, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun Problem(summary: String, retry: () -> Unit) {
  Column(Modifier.padding(horizontal = 16.dp)) {
    Text(summary, color = amber, style = MaterialTheme.typography.bodySmall)
    TextButton(onClick = retry) { Text("Retry", color = amber) }
  }
}
