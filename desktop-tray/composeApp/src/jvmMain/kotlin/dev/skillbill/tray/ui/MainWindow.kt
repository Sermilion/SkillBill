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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.skillbill.tray.application.defaultSelection
import dev.skillbill.tray.composition.ViewModelFactory
import dev.skillbill.tray.core.designsystem.amber
import dev.skillbill.tray.core.designsystem.border
import dev.skillbill.tray.core.designsystem.canvas
import dev.skillbill.tray.core.designsystem.dim
import dev.skillbill.tray.core.designsystem.ink
import dev.skillbill.tray.core.designsystem.mono
import dev.skillbill.tray.core.designsystem.muted
import dev.skillbill.tray.core.designsystem.orange
import dev.skillbill.tray.core.designsystem.sidebar
import dev.skillbill.tray.core.domain.model.Problem
import dev.skillbill.tray.core.domain.model.WorkIdentity
import dev.skillbill.tray.core.ui.ListScrollbar
import dev.skillbill.tray.core.ui.rememberEntryViewModelStoreOwner
import dev.skillbill.tray.core.ui.rememberViewModel
import dev.skillbill.tray.feature.detail.DetailEffect
import dev.skillbill.tray.feature.detail.DetailRoute
import dev.skillbill.tray.feature.history.HistoryAction
import dev.skillbill.tray.feature.history.HistoryEffect
import dev.skillbill.tray.feature.history.HistoryViewModel
import dev.skillbill.tray.feature.history.historySection
import dev.skillbill.tray.feature.work.SidebarFilterField
import dev.skillbill.tray.feature.work.WorkAction
import dev.skillbill.tray.feature.work.WorkEffect
import dev.skillbill.tray.feature.work.WorkViewModel
import dev.skillbill.tray.feature.work.workSections

/**
 * The main window: live work and history on the left, the selected item's detail on the right. The window owns what the
 * screens share. Selection lives here because a row, a tray notification and the tray menu can each change it, and the
 * screens only report that a row was picked.
 */
@Composable
fun MainWindow(
  work: WorkViewModel,
  history: HistoryViewModel,
  viewModels: ViewModelFactory,
  selected: WorkIdentity?,
  onSelect: (WorkIdentity?) -> Unit,
  shellProblem: Problem?,
  onSettings: () -> Unit,
) {
  val workState by work.state.collectAsState()
  val historyState by history.state.collectAsState()
  var seenGeneration by remember { mutableStateOf<Long?>(null) }
  // The sidebar's text lives here and goes to both lists, so neither screen reads the other's state.
  var filter by remember { mutableStateOf("") }
  val sidebarScroll = rememberLazyListState()
  LaunchedEffect(work, filter) { work.onAction(WorkAction.FilterChanged(filter)) }
  LaunchedEffect(history, filter) { history.onAction(HistoryAction.FilterChanged(filter)) }

  LaunchedEffect(work) {
    work.effects.collect { effect -> if (effect is WorkEffect.OpenDetail) onSelect(effect.identity) }
  }
  LaunchedEffect(history) {
    history.effects.collect { effect -> if (effect is HistoryEffect.OpenDetail) onSelect(effect.identity) }
  }
  // A different store holds different work, so what was selected no longer exists.
  LaunchedEffect(workState.store?.generation) {
    val generation = workState.store?.generation ?: return@LaunchedEffect
    if (seenGeneration != null && seenGeneration != generation) onSelect(null)
    seenGeneration = generation
  }
  val initial = if (selected == null) defaultSelection(workState, historyState) else null
  LaunchedEffect(initial) { initial?.let(onSelect) }

  Row(Modifier.fillMaxSize().background(canvas)) {
    Column(Modifier.width(250.dp).fillMaxHeight().background(sidebar)) {
      SidebarFilterField(filter) { filter = it }
      // One list and one scroll for live work and history, as before the screens were split.
      Box(Modifier.weight(1f)) {
        LazyColumn(Modifier.fillMaxSize().padding(end = 8.dp), state = sidebarScroll) {
          workSections(workState, filter.isNotBlank(), selected, sidebarScroll, work::onAction)
          historySection(historyState, selected, sidebarScroll, history::onAction)
        }
        ListScrollbar(sidebarScroll, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
      }
      HorizontalDivider(color = border)
      Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), horizontalArrangement = Arrangement.End) {
        Text("Settings", Modifier.clickable(onClick = onSettings), color = dim, fontFamily = mono, fontSize = 11.sp)
      }
    }
    Box(Modifier.width(1.dp).fillMaxHeight().background(border))
    Column(Modifier.weight(1f).fillMaxHeight()) {
      val problem = workState.liveProblem ?: shellProblem
      if (problem != null) {
        Row(Modifier.fillMaxWidth().background(orange.copy(alpha = .10f)).padding(horizontal = 22.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
          Column(Modifier.weight(1f)) {
            Text(problem.summary, color = amber, style = MaterialTheme.typography.bodyMedium)
            if (workState.stale) Text("Showing the last successful update.", color = muted, style = MaterialTheme.typography.bodySmall)
          }
          TextButton(onClick = { work.onAction(WorkAction.Refresh) }) { Text("Retry", color = amber) }
        }
      }
      if (selected != null) {
        SelectedDetail(viewModels, selected, onSelect, Modifier.weight(1f).fillMaxWidth())
      } else {
        EmptyDetail(
          loading = !workState.loaded,
          failed = workState.liveProblem != null,
          onSettings = onSettings,
          onRefresh = { work.onAction(WorkAction.Refresh) },
          modifier = Modifier.weight(1f),
          hasMore = workState.hasMoreLive,
        )
      }
    }
  }
}

/** The detail of [selected]; its ViewModel lives while that item stays selected and is replaced with the selection. */
@Composable
private fun SelectedDetail(
  viewModels: ViewModelFactory,
  selected: WorkIdentity,
  onSelect: (WorkIdentity?) -> Unit,
  modifier: Modifier,
) {
  val owner = rememberEntryViewModelStoreOwner(selected)
  val detail = rememberViewModel(owner, "detail") { viewModels.detail(selected) }
  LaunchedEffect(detail) {
    detail.effects.collect { effect ->
      if (effect is DetailEffect.SelectSuccessor && effect.from == selected) onSelect(effect.successor)
    }
  }
  DetailRoute(detail, modifier)
}

@Composable
private fun EmptyDetail(
  loading: Boolean,
  failed: Boolean,
  onSettings: () -> Unit,
  onRefresh: () -> Unit,
  modifier: Modifier,
  hasMore: Boolean,
) {
  Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
    Column(Modifier.widthIn(max = 380.dp).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Text(if (failed) "Couldn't load your work" else if (loading) "Loading your work" else if (hasMore) "More work is available" else "All quiet", color = ink, style = MaterialTheme.typography.titleLarge)
      Text(if (failed) "Check the database path in Settings, then try refreshing." else if (loading) "Fetching goals, standalone phases, and history." else "Select a goal, standalone phase, or history item in the sidebar to see its details.", color = muted, style = MaterialTheme.typography.bodyMedium)
      if (failed) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onRefresh) { Text("Refresh") }
        OutlinedButton(onClick = onSettings) { Text("Settings") }
      }
    }
  }
}
