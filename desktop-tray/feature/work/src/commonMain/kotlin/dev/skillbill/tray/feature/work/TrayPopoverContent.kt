package dev.skillbill.tray.feature.work

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.skillbill.tray.core.designsystem.DesignLabel
import dev.skillbill.tray.core.designsystem.amber
import dev.skillbill.tray.core.designsystem.blue
import dev.skillbill.tray.core.designsystem.body
import dev.skillbill.tray.core.designsystem.dim
import dev.skillbill.tray.core.designsystem.display
import dev.skillbill.tray.core.designsystem.green
import dev.skillbill.tray.core.designsystem.ink
import dev.skillbill.tray.core.designsystem.mint
import dev.skillbill.tray.core.designsystem.mono
import dev.skillbill.tray.core.designsystem.muted
import dev.skillbill.tray.core.designsystem.orange
import dev.skillbill.tray.core.designsystem.panel
import dev.skillbill.tray.core.designsystem.sidebar
import dev.skillbill.tray.core.designsystem.surface3
import dev.skillbill.tray.core.domain.model.ExecutionScope
import dev.skillbill.tray.core.domain.model.Freshness
import dev.skillbill.tray.core.domain.model.Lifecycle
import dev.skillbill.tray.core.domain.model.WorkIdentity
import dev.skillbill.tray.core.domain.model.WorkItem
import dev.skillbill.tray.core.domain.model.needsAttention
import dev.skillbill.tray.core.ui.AppendState
import dev.skillbill.tray.core.ui.LoadNextPageOnScroll
import dev.skillbill.tray.core.ui.PageFooter
import dev.skillbill.tray.core.ui.PagedListState
import dev.skillbill.tray.core.ui.WorkDot
import dev.skillbill.tray.core.ui.age
import dev.skillbill.tray.core.ui.coerceNonNegative
import dev.skillbill.tray.core.ui.durationText
import dev.skillbill.tray.core.ui.effectiveFreshness
import dev.skillbill.tray.core.ui.label
import dev.skillbill.tray.core.ui.lifecycleColor
import dev.skillbill.tray.core.ui.phaseElapsedText
import dev.skillbill.tray.core.ui.repositoryTitle
import dev.skillbill.tray.core.ui.tickingNow
import kotlin.time.Instant

/** The tray popover's content. The window around it (drag area, close button) is the shell's; it arrives as [header]. */
@Composable
fun TrayPopoverRoute(
  viewModel: TrayViewModel,
  selected: WorkIdentity?,
  openMain: () -> Unit,
  openSettings: () -> Unit,
  onQuit: () -> Unit,
  modifier: Modifier = Modifier,
  header: @Composable () -> Unit,
) {
  val state by viewModel.state.collectAsState()
  val now by tickingNow()
  TrayPopoverContent(state, selected, now, viewModel::onAction, openMain, openSettings, onQuit, modifier, header)
}

@Composable
fun TrayPopoverContent(
  state: TrayUiState,
  selected: WorkIdentity?,
  now: Instant,
  onAction: (TrayAction) -> Unit,
  openMain: () -> Unit,
  openSettings: () -> Unit,
  onQuit: () -> Unit,
  modifier: Modifier = Modifier,
  header: @Composable () -> Unit = {},
) {
  Column(modifier.fillMaxSize().clip(RoundedCornerShape(14.dp)).background(panel).border(1.dp, dev.skillbill.tray.core.designsystem.border, RoundedCornerShape(14.dp))) {
    header()
    HorizontalDivider(color = dev.skillbill.tray.core.designsystem.border)
    Row(Modifier.fillMaxWidth().background(sidebar).padding(horizontal = 16.dp, vertical = 11.dp)) {
      PopoverStat("Running", state.running, green, Modifier.weight(1f))
      PopoverStat("Needs you", state.needsYouCount, Color(0xFFF49463), Modifier.weight(1f))
      PopoverStat("Done 24h", state.doneWithinDay, ink, Modifier.weight(1f))
    }
    HorizontalDivider(color = dev.skillbill.tray.core.designsystem.border)
    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp)) {
      listOf(PopoverTab.GOALS to state.popoverGoals, PopoverTab.PHASES to state.popoverLivePhases).forEach { (tab, rows) ->
        val active = state.popoverTab == tab
        Column(Modifier.weight(1f).clickable { onAction(TrayAction.ChooseTab(tab)) }) {
          Row(Modifier.fillMaxWidth().padding(top = 11.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
            Text(tab.label, color = if (active) ink else muted, fontFamily = body, fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp)
            Text(rows.size.toString(), Modifier.background(if (active) mint.copy(alpha = .12f) else surface3, RoundedCornerShape(50)).padding(horizontal = 6.dp, vertical = 3.dp), color = if (active) amber else dim, fontFamily = mono, fontWeight = FontWeight.Medium, fontSize = 10.5.sp)
            if (rows.any { it.needsAttention() }) Box(Modifier.size(6.dp).background(orange, RoundedCornerShape(50)))
          }
          Box(Modifier.fillMaxWidth().height(2.dp).background(if (active) mint else Color.Transparent))
        }
      }
    }
    HorizontalDivider(color = dev.skillbill.tray.core.designsystem.border)
    val rows = if (state.popoverTab == PopoverTab.GOALS) state.popoverGoals else state.popoverLivePhases
    state.liveProblem?.let {
      Text(it.summary, Modifier.fillMaxWidth().background(orange.copy(alpha = .12f)).padding(12.dp), color = amber, fontFamily = body, fontSize = 11.sp)
    }
    state.pendingFeedback.firstOrNull()?.let { feedback ->
      Text(
        feedback.message,
        Modifier.fillMaxWidth().background(orange.copy(alpha = .12f)).clickable { onAction(TrayAction.AcknowledgeFeedback(feedback.id)) }.padding(12.dp),
        color = amber, fontFamily = body, fontSize = 11.sp,
      )
    }
    val scroll = rememberLazyListState()
    val liveLists = listOf(state.sections.needsYou, state.sections.goals, state.sections.phases)
    val loading = liveLists.any { it is PagedListState.Content && it.append == AppendState.Loading }
    LoadNextPageOnScroll(scroll, state.hasMoreLive, loading) { onAction(TrayAction.LoadMoreLive) }
    LazyColumn(Modifier.weight(1f), state = scroll) {
      if (rows.isEmpty()) item {
        Text(if (!state.loaded) "Loading your work..." else "No live ${state.popoverTab.label.lowercase()}", Modifier.padding(16.dp), color = muted, fontFamily = body, fontSize = 12.sp)
      }
      items(rows, key = { it.identity }) { item ->
        PopoverWorkRow(item, selected == item.identity, now, state.stale) { onAction(TrayAction.Select(item.identity)); openMain() }
      }
      if (state.popoverTab == PopoverTab.PHASES && state.popoverFinishedPhases.isNotEmpty()) {
        item { DesignLabel("Finished in the last hour", Modifier.padding(horizontal = 16.dp, vertical = 11.dp)) }
        items(state.popoverFinishedPhases, key = { it.identity }) { item ->
          PopoverWorkRow(item, false, now, state.stale) { onAction(TrayAction.Select(item.identity)); openMain() }
        }
      }
      if (state.hasMoreLive) item {
        PageFooter(if (loading) AppendState.Loading else AppendState.Idle) { onAction(TrayAction.LoadMoreLive) }
      }
    }
    HorizontalDivider(color = dev.skillbill.tray.core.designsystem.border)
    Row(Modifier.fillMaxWidth().background(sidebar).padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
      Text("Open Skill Bill", Modifier.weight(1f).clickable(onClick = openMain), color = amber, fontFamily = body, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
      Text("Refresh", Modifier.clickable { onAction(TrayAction.Refresh) }, color = dim, fontFamily = mono, fontSize = 11.5.sp)
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
        Text(item.repositoryTitle(), Modifier.weight(1f), color = ink, fontFamily = display, fontWeight = FontWeight.Bold, fontSize = 13.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(item.issueKey ?: item.phaseId ?: item.currentStepId, Modifier.border(1.dp, color.copy(alpha = .45f), RoundedCornerShape(50)).padding(horizontal = 7.dp, vertical = 3.dp), color = color, fontFamily = mono, fontSize = 10.5.sp)
      }
      val planning = item.planningState?.let { it != "prepared" } == true
      val phase = if (item.lifecycle == Lifecycle.ACTIVE) item.currentStepLabel else item.lifecycle.name.lowercase()
      val duration = if (item.scope == ExecutionScope.STANDALONE_PHASE) phaseElapsedText(item, now, stale) else durationText(item.activeDurationMs, item.activeDurationAsOf, item, now, stale)
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
          listOfNotNull(phase, item.phaseExecution?.label(), if (planning) "${item.plannedSubtasks ?: 0}/${item.totalSubtasks ?: "?"} plans saved" else duration.takeIf { it != "not available" }).joinToString(" · "),
          Modifier.weight(1f), color = if (attention) color else muted, fontFamily = mono, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        item.progressTotal?.let { Text("${item.progressCompleted ?: 0}/$it", color = ink, fontFamily = mono, fontSize = 11.5.sp) }
      }
      val progressTotal = item.progressTotal
      if (!attention && progressTotal != null && progressTotal > 0) Box(Modifier.fillMaxWidth().height(3.dp).background(surface3, RoundedCornerShape(50))) {
        Box(Modifier.fillMaxWidth(((item.progressCompleted ?: 0).toFloat() / progressTotal).coerceIn(0f, 1f)).fillMaxHeight().background(if (planning) blue else mint))
      }
      val note = item.pauseReason ?: when {
        item.lifecycle == Lifecycle.BLOCKED -> "Block reason is not available"
        item.pauseRequested -> "Pause requested"
        effectiveFreshness(item, stale) == Freshness.STALE -> "Last update ${age((now - item.updatedAt).coerceNonNegative())} ago"
        else -> null
      }
      note?.let {
        Text(it, color = muted, fontFamily = body, fontSize = 11.sp, lineHeight = 16.sp, maxLines = if (item.lifecycle == Lifecycle.BLOCKED) 3 else 2, overflow = TextOverflow.Ellipsis)
      }
    }
  }
  HorizontalDivider(color = dev.skillbill.tray.core.designsystem.border)
}
