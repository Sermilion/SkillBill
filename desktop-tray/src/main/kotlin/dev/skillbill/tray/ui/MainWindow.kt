package dev.skillbill.tray.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.skillbill.tray.application.DashboardModel
import dev.skillbill.tray.application.ListLoadState
import dev.skillbill.tray.domain.*
import java.time.Instant

@Composable
fun MainWindow(model: DashboardModel, onHistory: () -> Unit, onSettings: () -> Unit) {
  val state by model.state.collectAsState()
  val now by tickingNow()
  val sidebarScroll = rememberLazyListState()
  val filter = state.filter
  val attention = state.sections[WorkSection.NEEDS_YOU]?.items.orEmpty()
  val goals = state.sections[WorkSection.GOALS]?.items.orEmpty()
  val phases = state.sections[WorkSection.PHASES]?.items.orEmpty()
  LaunchedEffect(state.board?.items, state.selected) {
    if (state.selected == null) (goals.firstOrNull() ?: attention.firstOrNull() ?: phases.firstOrNull())?.let { model.select(it.identity) }
  }
  Row(Modifier.fillMaxSize().background(canvas)) {
    Column(Modifier.width(250.dp).fillMaxHeight().background(sidebar)) {
      BasicTextField(filter, model::filter,
        Modifier.padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 10.dp).fillMaxWidth().height(34.dp).background(surface3, RoundedCornerShape(8.dp)).border(1.dp, border, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp),
        singleLine = true, textStyle = TextStyle(color = ink, fontFamily = body, fontSize = 12.sp), cursorBrush = SolidColor(mint),
        decorationBox = { field -> Box(contentAlignment = Alignment.CenterStart) {
          if (filter.isEmpty()) Text("⌕  Filter by repo, issue, branch", color = dim, fontFamily = body, fontSize = 12.sp)
          field()
        } },
      )
      LazyColumn(Modifier.weight(1f), state = sidebarScroll) {
        listOf(Triple("Needs you", attention, WorkSection.NEEDS_YOU), Triple("Goals running", goals, WorkSection.GOALS), Triple("Standalone phases", phases, WorkSection.PHASES)).forEach { (title, rows, section) ->
          val hasMore = state.sections[section]?.nextCursor != null
          val loadState = state.sectionLoadStates[section] ?: if (section in state.sections) ListLoadState.READY else ListLoadState.INITIAL_LOADING
          val sectionLoading = loadState == ListLoadState.INITIAL_LOADING || loadState == ListLoadState.APPENDING
          item { DesignLabel("$title · ${rows.size}", Modifier.padding(start = 14.dp, top = if (title == "Needs you") 4.dp else 14.dp, bottom = 6.dp)) }
          if (rows.isEmpty() && section in state.sections) item { Text(if (filter.isBlank()) "No ${title.lowercase()}" else "No matching work", Modifier.padding(start = 16.dp, bottom = 8.dp), color = muted, style = MaterialTheme.typography.bodySmall) }
          if (rows.isNotEmpty()) item {
            val visibleRows = when (title) { "Needs you" -> 3; "Goals running" -> 4; else -> 2 }
            val sectionScroll = rememberLazyListState()
            LoadNextPageOnScroll(sectionScroll, hasMore, sectionLoading) { model.loadMore(section) }
            LazyColumn(Modifier.fillMaxWidth().height((minOf(rows.size, visibleRows) * 47).dp), state = sectionScroll) {
              items(rows, key = { it.identity }) { item -> SidebarWorkRow(item, state.selected == item.identity) { model.select(item.identity) } }
            }
          }
          if (hasMore) item { PageFooter(hasMore, loadState == ListLoadState.APPENDING, loadState == ListLoadState.APPEND_FAILED) { model.loadMore(section) } }
        }
      }
      HorizontalDivider(color = border)
      Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("History", Modifier.clickable(onClick = onHistory), color = dim, fontFamily = mono, fontSize = 11.sp)
        Text("Settings", Modifier.clickable(onClick = onSettings), color = dim, fontFamily = mono, fontSize = 11.sp)
      }
    }
    Box(Modifier.width(1.dp).fillMaxHeight().background(border))
    Column(Modifier.weight(1f).fillMaxHeight()) {
      val problem = state.problem ?: state.trayProblem
      if (problem != null) {
        Row(Modifier.fillMaxWidth().background(orange.copy(alpha = .10f)).padding(horizontal = 22.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
          Column(Modifier.weight(1f)) {
            Text(problem.summary, color = amber, style = MaterialTheme.typography.bodyMedium)
            if (state.stale) Text("Showing the last successful update.", color = muted, style = MaterialTheme.typography.bodySmall)
          }
          TextButton(onClick = model::refresh) { Text("Retry", color = amber) }
        }
      }
      if (state.detail != null) {
        DetailPane(state.detail, state.detailProblem, model, Modifier.weight(1f).fillMaxWidth(), now, state.stale)
      } else {
        EmptyDetail(state.detailLoading || state.boardLoading, state.problem != null, state.detailProblem?.summary, onSettings, model::refresh, Modifier.weight(1f), listOf(WorkSection.NEEDS_YOU, WorkSection.GOALS, WorkSection.PHASES).any { state.sections[it]?.nextCursor != null })
      }

    }
  }
}

@Composable
private fun SidebarWorkRow(item: WorkItem, selected: Boolean, onSelect: () -> Unit) {
  Row(Modifier.padding(horizontal = 8.dp).fillMaxWidth().background(if (selected) raised else sidebar, RoundedCornerShape(8.dp)).clickable(onClick = onSelect).heightIn(min = 47.dp)) {
    Box(Modifier.width(2.dp).height(47.dp).background(if (selected) mint else sidebar))
    Row(Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
      WorkDot(item.lifecycle)
      Column(Modifier.weight(1f)) {
        Text(FileName(item.repositoryRoot), color = ink, fontFamily = display, fontWeight = FontWeight.Bold, fontSize = 12.5.sp, lineHeight = 16.25.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(listOfNotNull(item.issueKey ?: item.phaseId, item.currentStepLabel).joinToString(" · "), color = if (item.needsAttention()) orange else muted, fontFamily = mono, fontSize = 10.5.sp, lineHeight = 14.7.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
      }
      if (item.progressTotal != null) Text("${item.progressCompleted ?: 0}/${item.progressTotal}", color = if (selected) amber else dim, style = MaterialTheme.typography.labelSmall)
    }
  }
}

@Composable
private fun EmptyDetail(loading: Boolean, failed: Boolean, detailProblem: String?, onSettings: () -> Unit, onRefresh: () -> Unit, modifier: Modifier, hasMore: Boolean = false) {
  Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
    Column(Modifier.widthIn(max = 380.dp).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Text(if (detailProblem != null) "Work detail unavailable" else if (failed) "Couldn't load your work" else if (loading) "Loading your work" else if (hasMore) "More work is available" else "All quiet", color = ink, style = MaterialTheme.typography.titleLarge)
      Text(detailProblem ?: if (failed) "Check the database path in Settings, then try refreshing." else if (loading) "Fetching goals and standalone phases." else "Running goals and standalone phases appear in the sidebar. Select one to see its progress.", color = muted, style = MaterialTheme.typography.bodyMedium)
      if (failed || detailProblem != null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onRefresh) { Text("Refresh") }
        OutlinedButton(onClick = onSettings) { Text("Settings") }
      }
    }
  }
}

internal fun lifecycleColor(lifecycle: Lifecycle) = when (lifecycle) {
  Lifecycle.ACTIVE -> green
  Lifecycle.BLOCKED -> orange
  Lifecycle.FAILED -> red
  Lifecycle.TERMINAL -> muted
  else -> muted
}
