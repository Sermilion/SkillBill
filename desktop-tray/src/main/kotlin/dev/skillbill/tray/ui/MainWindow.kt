package dev.skillbill.tray.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.rememberScrollbarAdapter
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
import androidx.compose.ui.graphics.Color
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
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun MainWindow(model: DashboardModel, onSettings: () -> Unit) {
  val state by model.state.collectAsState()
  val now by tickingNow()
  val sidebarScroll = rememberLazyListState()
  val filter = state.filter
  val attention = state.sections[WorkSection.NEEDS_YOU]?.items.orEmpty()
  val goals = state.sections[WorkSection.GOALS]?.items.orEmpty()
  val phases = state.sections[WorkSection.PHASES]?.items.orEmpty()
  val history = state.sections[WorkSection.HISTORY]?.items.orEmpty()
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
      Box(Modifier.weight(1f)) {
        LazyColumn(Modifier.fillMaxSize().padding(end = 8.dp), state = sidebarScroll) {
          val shown = mutableSetOf<WorkIdentity>()
          listOf(Triple("Needs you", attention, WorkSection.NEEDS_YOU), Triple("Standalone phases", phases, WorkSection.PHASES), Triple("Goal runs", goals, WorkSection.GOALS), Triple("History", history, WorkSection.HISTORY)).forEach { (title, rows, section) ->
            val visibleRows = rows.filter { shown.add(it.identity) }
            val hasMore = state.sections[section]?.nextCursor != null
            val loadState = state.sectionLoadStates[section] ?: if (section in state.sections) ListLoadState.READY else ListLoadState.INITIAL_LOADING
            val sectionLoading = loadState in setOf(ListLoadState.INITIAL_LOADING, ListLoadState.REFRESHING, ListLoadState.APPENDING)
            item(key = "$section:header") { DesignLabel("$title · ${visibleRows.size}", Modifier.padding(start = 14.dp, top = if (title == "Needs you") 4.dp else 14.dp, bottom = 6.dp)) }
            if (section == WorkSection.HISTORY) item(key = "$section:filters") {
              Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                HistoryFilter.entries.forEach { choice ->
                  TextButton(onClick = { model.updateSettings(state.settings.copy(historyFilter = choice)) },
                    contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text(choice.name.lowercase().replaceFirstChar(Char::titlecase), color = if (state.settings.historyFilter == choice) amber else muted,
                      fontFamily = body, fontSize = 11.sp)
                  }
                }
              }
              Text("Finished within ${state.settings.retention.cliValue}", Modifier.padding(start = 16.dp, bottom = 6.dp), color = dim, style = MaterialTheme.typography.bodySmall)
            }
            state.sectionProblems[section]?.let { problem -> item(key = "$section:problem") {
              Column(Modifier.padding(horizontal = 16.dp)) {
                Text(problem.summary, color = amber, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = model::refresh) { Text("Retry", color = amber) }
              }
            } }
            if (loadState == ListLoadState.INITIAL_LOADING) item(key = "$section:loading") {
              Text("Loading...", Modifier.padding(start = 16.dp, bottom = 8.dp), color = muted, style = MaterialTheme.typography.bodySmall)
            }
            if (visibleRows.isEmpty() && section in state.sections) item(key = "$section:empty") { Text(if (filter.isNotBlank()) "No matching work" else if (section == WorkSection.HISTORY) "No finished work" else "No ${title.lowercase()}", Modifier.padding(start = 16.dp, bottom = 8.dp), color = muted, style = MaterialTheme.typography.bodySmall) }
            items(visibleRows, key = { "$section:${it.identity.repositoryIdentity}:${it.identity.id}" }) { item ->
              SidebarWorkRow(item, state.selected == item.identity) { model.select(item.identity) }
            }
            if (hasMore) item(key = "$section:footer") {
              LoadNextPageOnScroll(sidebarScroll, hasMore && loadState != ListLoadState.APPEND_FAILED, sectionLoading, "$section:footer") { model.loadMore(section) }
              PageFooter(hasMore, sectionLoading, loadState == ListLoadState.APPEND_FAILED) { model.loadMore(section) }
            }
          }
        }
        VerticalScrollbar(rememberScrollbarAdapter(sidebarScroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
      }
      HorizontalDivider(color = border)
      Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), horizontalArrangement = Arrangement.End) {
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
        EmptyDetail(state.detailLoading || state.boardLoading, state.problem != null, state.detailProblem?.summary, onSettings, model::refresh, Modifier.weight(1f), listOf(WorkSection.NEEDS_YOU, WorkSection.GOALS, WorkSection.PHASES, WorkSection.HISTORY).any { state.sections[it]?.nextCursor != null })
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
        Text(item.repositoryTitle(), color = ink, fontFamily = display, fontWeight = FontWeight.Bold, fontSize = 12.5.sp, lineHeight = 16.25.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val status = if (item.scope == ExecutionScope.STANDALONE_PHASE || item.lifecycle == Lifecycle.CANCELED) {
          item.lifecycle.name.lowercase().replaceFirstChar(Char::uppercase)
        } else null
        val step = item.currentStepLabel.takeUnless { item.lifecycle == Lifecycle.CANCELED || status != null && it == item.phaseId }
        Text(listOfNotNull(item.issueKey ?: item.phaseId, status, step).joinToString(" · "), color = if (item.lifecycle == Lifecycle.CANCELED) lifecycleColor(item.lifecycle) else if (item.needsAttention()) orange else muted, fontFamily = mono, fontSize = 10.5.sp, lineHeight = 14.7.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (item.scope == ExecutionScope.STANDALONE_PHASE) {
          val started = item.startedAt?.atZone(ZoneId.systemDefault())?.format(DateTimeFormatter.ofPattern("d MMM HH:mm"))
          val invocation = item.invocationId?.removePrefix("phr-")?.take(8)
          Text(listOfNotNull(started, invocation).joinToString(" · "), color = dim, fontFamily = mono, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
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
      Text(detailProblem ?: if (failed) "Check the database path in Settings, then try refreshing." else if (loading) "Fetching goals, standalone phases, and history." else "Select a goal, standalone phase, or history item in the sidebar to see its details.", color = muted, style = MaterialTheme.typography.bodyMedium)
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
  Lifecycle.CANCELED -> Color(0xFFB59AF4)
  Lifecycle.TERMINAL -> muted
  else -> muted
}
