package dev.skillbill.tray.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.ui.window.WindowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.skillbill.tray.application.DashboardModel
import dev.skillbill.tray.domain.*
import dev.skillbill.tray.presentation.effectiveFreshness
import dev.skillbill.tray.presentation.presentPopover
import java.time.Duration
import java.time.Instant

@Composable
fun WindowScope.TrayPopover(model: DashboardModel, onClose: () -> Unit, openMain: () -> Unit, openSettings: () -> Unit, onQuit: () -> Unit) {
  val state by model.state.collectAsState()
  val now by tickingNow()
  val view = presentPopover(state, now)
  Column(Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp)).background(panel).border(1.dp, border, RoundedCornerShape(14.dp))) {
    WindowDraggableArea {
      Row(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(mint.copy(alpha = .07f), Color.Transparent))).padding(start = 16.dp, end = 16.dp, top = 13.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.CenterVertically) {
        Image(androidx.compose.ui.res.painterResource("icons/skill-bill.svg"), "", Modifier.size(18.dp).clip(RoundedCornerShape(4.dp)))
        Text("Skill Bill", Modifier.weight(1f), color = ink, fontFamily = display, fontWeight = FontWeight.Bold, fontSize = 15.sp, letterSpacing = (-.3).sp)
        IconButton(onClick = onClose, modifier = Modifier.size(24.dp)) {
          Icon(Icons.Default.Close, contentDescription = "Close tray window", tint = dim, modifier = Modifier.size(16.dp))
        }
      }
    }
    HorizontalDivider(color = border)
    Row(Modifier.fillMaxWidth().background(sidebar).padding(horizontal = 16.dp, vertical = 11.dp)) {
      PopoverStat("Running", view.running, green, Modifier.weight(1f))
      PopoverStat("Needs you", view.needsYou, Color(0xFFF49463), Modifier.weight(1f))
      PopoverStat("Done 24h", view.done24h, ink, Modifier.weight(1f))
    }
    HorizontalDivider(color = border)
    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp)) {
      listOf("Goals" to view.goals, "Phases" to view.livePhases).forEach { (tab, rows) ->
        val selected = state.settings.lastPopoverTab == tab
        Column(Modifier.weight(1f).clickable { model.chooseTab(tab) }) {
          Row(Modifier.fillMaxWidth().padding(top = 11.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
            Text(tab, color = if (selected) ink else muted, fontFamily = body, fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp)
            Text(rows.size.toString(), Modifier.background(if (selected) mint.copy(alpha = .12f) else surface3, RoundedCornerShape(50)).padding(horizontal = 6.dp, vertical = 3.dp), color = if (selected) amber else dim, fontFamily = mono, fontWeight = FontWeight.Medium, fontSize = 10.5.sp)
            if (rows.any { it.needsAttention() }) Box(Modifier.size(6.dp).background(orange, RoundedCornerShape(50)))
          }
          Box(Modifier.fillMaxWidth().height(2.dp).background(if (selected) mint else Color.Transparent))
        }
      }
    }
    HorizontalDivider(color = border)
    val rows = if (state.settings.lastPopoverTab == "Goals") view.goals else view.livePhases
    state.problem?.let { Text(it.summary, Modifier.fillMaxWidth().background(orange.copy(alpha = .12f)).padding(12.dp), color = amber, fontFamily = body, fontSize = 11.sp) }
    val scroll = rememberLazyListState()
    val hasMore = state.board?.nextCursor != null
    LoadNextPageOnScroll(scroll, hasMore, state.boardLoading, model::loadMoreBoard)
    LazyColumn(Modifier.weight(1f), state = scroll) {
      if (rows.isEmpty()) item {
        Text(if (state.boardLoading) "Loading your work..." else "No live ${state.settings.lastPopoverTab.lowercase()}", Modifier.padding(16.dp), color = muted, fontFamily = body, fontSize = 12.sp)
      }
      items(rows, key = { it.identity }) { item ->
        PopoverWorkRow(item, state.selected == item.identity, now, state.stale) { model.select(item.identity); openMain() }
      }
      if (state.settings.lastPopoverTab == "Phases" && view.finishedPhases.isNotEmpty()) {
        item { DesignLabel("Finished in the last hour", Modifier.padding(horizontal = 16.dp, vertical = 11.dp)) }
        items(view.finishedPhases, key = { it.identity }) { item -> PopoverWorkRow(item, false, now, state.stale) { model.select(item.identity); openMain() } }
      }
      if (hasMore) item { PageFooter(hasMore, state.boardLoading, model::loadMoreBoard) }
    }
    HorizontalDivider(color = border)
    Row(Modifier.fillMaxWidth().background(sidebar).padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
      Text("Open Skill Bill", Modifier.weight(1f).clickable(onClick = openMain), color = amber, fontFamily = body, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
      Text("Refresh", Modifier.clickable(onClick = model::refresh), color = dim, fontFamily = mono, fontSize = 11.5.sp)
      Text("Settings", Modifier.clickable(onClick = openSettings), color = dim, fontFamily = mono, fontSize = 11.5.sp)
      Text("Quit", Modifier.clickable(onClick = onQuit), color = dim, fontFamily = mono, fontSize = 11.5.sp)
    }
  }
}

@Composable
private fun PopoverStat(label: String, value: Int, color: Color, modifier: Modifier) {
  Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Text(value.toString(), color = color, fontFamily = display, fontWeight = FontWeight.Bold, fontSize = 17.sp, lineHeight = 17.sp)
    DesignLabel(label)
  }
}

@Composable
internal fun PopoverWorkRow(item: WorkItem, selected: Boolean, now: Instant, stale: Boolean, select: () -> Unit) {
  val color = lifecycleColor(item.lifecycle)
  val attention = item.needsAttention()
  Row(Modifier.fillMaxWidth().background(if (attention) color.copy(alpha = .07f) else if (selected) mint.copy(alpha = .06f) else Color.Transparent).clickable(onClick = select).height(IntrinsicSize.Min)) {
    Box(Modifier.width(2.dp).fillMaxHeight().background(if (attention) color else if (selected) mint else Color.Transparent))
    Column(Modifier.weight(1f).padding(start = 14.dp, end = 16.dp, top = 10.dp, bottom = 11.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        WorkDot(item.lifecycle)
        Text(FileName(item.repositoryRoot), Modifier.weight(1f), color = ink, fontFamily = display, fontWeight = FontWeight.Bold, fontSize = 13.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(item.issueKey ?: item.phaseId ?: item.currentStepId, Modifier.border(1.dp, color.copy(alpha = .45f), RoundedCornerShape(50)).padding(horizontal = 7.dp, vertical = 3.dp), color = color, fontFamily = mono, fontSize = 10.5.sp)
      }
      val planning = item.planningState?.let { it != "prepared" } == true
      val phase = if (item.lifecycle == Lifecycle.ACTIVE) item.currentStepLabel else item.lifecycle.name.lowercase()
      val duration = if (item.scope == ExecutionScope.STANDALONE_PHASE) phaseElapsedText(item, now, stale) else durationText(item.activeDurationMs, item.activeDurationAsOf, item, now, stale)
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(listOfNotNull(phase, item.phaseExecution?.label(), if (planning) "${item.plannedSubtasks ?: 0}/${item.totalSubtasks ?: "?"} plans saved" else duration.takeIf { it != "not available" }).joinToString(" · "), Modifier.weight(1f), color = if (attention) color else muted, fontFamily = mono, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        item.progressTotal?.let { Text("${item.progressCompleted ?: 0}/$it", color = ink, fontFamily = mono, fontSize = 11.5.sp) }
      }
      if (!attention && item.progressTotal != null && item.progressTotal > 0) Box(Modifier.fillMaxWidth().height(3.dp).background(surface3, RoundedCornerShape(50))) {
        Box(Modifier.fillMaxWidth(((item.progressCompleted ?: 0).toFloat() / item.progressTotal).coerceIn(0f, 1f)).fillMaxHeight().background(if (planning) blue else mint))
      }
      (item.pauseReason ?: if (item.lifecycle == Lifecycle.BLOCKED) "Block reason is not available" else if (item.pauseRequested) "Pause requested" else if (effectiveFreshness(item, stale) == Freshness.STALE) "Last update ${age(Duration.between(item.updatedAt, now).coerceNonNegative())} ago" else null)?.let {
        Text(it, color = muted, fontFamily = body, fontSize = 11.sp, lineHeight = 16.sp, maxLines = if (item.lifecycle == Lifecycle.BLOCKED) 3 else 2, overflow = TextOverflow.Ellipsis)
      }
    }
  }
  HorizontalDivider(color = border)
}
