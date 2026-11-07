package dev.skillbill.desktop.feature.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.LocalContentColor
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.SbButton
import dev.skillbill.desktop.core.designsystem.component.SbButtonVariant
import dev.skillbill.desktop.core.designsystem.component.SbControlSize
import dev.skillbill.desktop.core.designsystem.component.SbLabel
import dev.skillbill.desktop.core.designsystem.component.SbLabelStyle
import dev.skillbill.desktop.core.designsystem.detail.DocBlockGap
import dev.skillbill.desktop.core.designsystem.detail.SectionCollapse
import dev.skillbill.desktop.core.designsystem.detail.Section
import dev.skillbill.desktop.core.designsystem.detail.SbBox
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.designsystem.icon.SbIcon
import dev.skillbill.desktop.core.domain.model.Detail
import dev.skillbill.desktop.core.domain.model.StandalonePlanStage
import dev.skillbill.desktop.core.domain.model.WorkItem

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PlanActivityPanel(item: WorkItem, detail: Detail, state: DetailUiState, collapse: SectionCollapse, onAction: (DetailAction) -> Unit) {
  val events = planActivity(item, detail)
  var filter by rememberSaveable(item.id) { mutableStateOf(PlanActivityFilter.All) }
  var follow by rememberSaveable(item.id) { mutableStateOf(true) }
  var transcript by rememberSaveable(item.id) { mutableStateOf(false) }
  var closed by rememberSaveable(item.id) { mutableStateOf(emptyList<String>()) }
  val list = rememberLazyListState()
  val stageIds = listOf("preplan", "plan", "plan_subtasks")
  val rows = planActivityRows(events, filter)
  val stages = detail.standalonePlan?.stages.orEmpty()
  val visibleCount = stageIds.sumOf { id -> 1 + if (id in closed) 0 else rows.count { it.phaseId == id } } + if (item.running) 1 else 0
  val scroll = remember(list) { object : NestedScrollConnection {
    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
      if (source == NestedScrollSource.UserInput && available.y > 0) follow = false
      return Offset.Zero
    }
    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
      if (source == NestedScrollSource.UserInput && !list.canScrollForward) follow = true
      return Offset.Zero
    }
  } }
  LaunchedEffect(follow, visibleCount, events.lastOrNull()?.updatedAt, filter) {
    if (follow && visibleCount > 0) list.scrollToItem(visibleCount - 1)
  }
  val key = "${item.id}:live-plan-activity"
  Section("Activity", Modifier.padding(top = DocBlockGap), count = "${events.size} events", collapsed = collapse.isCollapsed(key), onToggle = { collapse.toggle(key) }) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      PlanActivityFilter.entries.forEach { choice ->
        SbButton(choice.label, { filter = choice }, variant = if (choice == filter) SbButtonVariant.Secondary else SbButtonVariant.Ghost, size = SbControlSize.Small)
      }
      SbButton("Follow", { follow = !follow }, variant = if (follow) SbButtonVariant.Secondary else SbButtonVariant.Ghost, icon = LucideIcons.arrowDown, size = SbControlSize.Small, modifier = Modifier.semantics { stateDescription = if (follow) "Following latest activity" else "Paused" })
      SbButton("Transcript", { transcript = !transcript }, variant = SbButtonVariant.Ghost, icon = LucideIcons.external, size = SbControlSize.Small)
    }
    SbBox(Modifier.height(282.dp)) {
      Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().testTag("plan-activity-feed").nestedScroll(scroll).onPreviewKeyEvent { event ->
          if (event.type == KeyEventType.KeyDown && event.key in setOf(Key.DirectionUp, Key.PageUp, Key.MoveHome)) follow = false
          if (event.type == KeyEventType.KeyDown && event.key == Key.MoveEnd) follow = true
          false
        }, state = list) {
          stageIds.forEach { id ->
            stickyHeader(key = "group-$id") {
              val phase = stages.firstOrNull { it.phaseId == id }
              val status = when (phase?.status) { "running" -> if (item.running) "active" else "paused"; "completed" -> "done"; else -> phase?.status ?: "queued" }
              PlanActivityGroup(id, status, phase, events.filter { it.phaseId == id }, id !in closed) { closed = if (id in closed) closed - id else closed + id }
            }
            if (id !in closed) items(rows.filter { it.phaseId == id }, key = { it.key }) { event -> PlanActivityRow(event, onAction) }
          }
          if (item.running) item(key = "working") {
            BasicText(if (planQuietSince(item, detail) == null) "Agent is working" else "Waiting for new activity", Modifier.padding(12.dp), style = SkillBillTheme.type.tXs.copy(color = SkillBillTheme.colors.fgFaint))
          }
        }
        if (!follow) SbButton("Jump to latest", { follow = true }, Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp), variant = SbButtonVariant.Secondary, icon = LucideIcons.arrowDown, size = SbControlSize.Small)
      }
    }
    if (transcript) PlanOutputLog(state, detail, onAction, raw = true)
  }
}

@Composable
private fun PlanActivityGroup(id: String, status: String, phase: StandalonePlanStage?, events: List<PlanActivityEvent>, open: Boolean, onToggle: () -> Unit) {
  val colors = SkillBillTheme.colors
  val title = when (id) { "preplan" -> "Reading the repo"; "plan" -> "Splitting the work"; else -> "Writing subtask specs" }
  Row(Modifier.fillMaxWidth().background(colors.bgPanel).clickable(role = Role.Button, onClickLabel = if (open) "Collapse $title" else "Expand $title", onClick = onToggle).padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
    CompositionLocalProvider(LocalContentColor provides colors.fgFaint) { SbIcon(if (open) LucideIcons.chevronDown else LucideIcons.chevronRight, 12.dp) }
    BasicText(title, Modifier.weight(1f), style = SkillBillTheme.type.tSmMedium.copy(color = colors.fg2), maxLines = 1, overflow = TextOverflow.Ellipsis)
    BasicText(id, style = SkillBillTheme.type.monoSm.copy(color = colors.fgFaint))
    SbLabel(status, SbLabelStyle.Tint(if (status == "active") colors.stActive else if (status == "blocked" || status == "failed") colors.stBlocked else colors.fgFaint))
  }
  val summary = listOf("read" to "reads", "write" to "writes", "search" to "searches", "command" to "commands", "note" to "notes").mapNotNull { (kind, label) ->
    events.count { it.kind == kind }.takeIf { it > 0 }?.let { count ->
      val singular = when (kind) { "search" -> "search"; else -> label.removeSuffix("s") }
      "$count ${if (count == 1) singular else label}"
    }
  }.joinToString(" · ")
  val duration = phase?.finishedAt?.let { end -> phase.firstStartedAt?.let { activityDuration((end - it).inWholeMilliseconds) } }
  if (summary.isNotEmpty() || duration != null) Row(Modifier.fillMaxWidth().background(colors.bgPanel).padding(start = 32.dp, end = 12.dp, bottom = 5.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    BasicText(summary, Modifier.weight(1f), style = SkillBillTheme.type.tXs.copy(color = colors.fgFaint), maxLines = 1, overflow = TextOverflow.Ellipsis)
    duration?.let { BasicText(it, style = SkillBillTheme.type.monoSm.copy(color = colors.fgFaint)) }
  }
}

@Composable
internal fun PlanActivityRow(event: PlanActivityEvent, onAction: (DetailAction) -> Unit = {}) {
  var open by rememberSaveable(event.key) { mutableStateOf(false) }
  val expandable = event.kind == "note" || event.children.isNotEmpty()
  val colors = SkillBillTheme.colors
  val failed = event.state == "failed"
  val verb = planActivityVerb(event)
  val isSpec = event.kind == "write" && event.path?.let { fileName(it).endsWith(".md") || fileName(it).endsWith(".yaml") || fileName(it).endsWith(".yml") } == true
  val text = event.subtaskId?.let { "#$it · " }.orEmpty() + listOf(verb, if (isSpec) fileName(requireNotNull(event.path)) else event.text).filter(String::isNotBlank).joinToString(" ") + event.exitCode?.let { " · exit $it" }.orEmpty()
  val modifier = if (expandable) Modifier.clickable(role = Role.Button, onClickLabel = if (open) "Collapse activity" else "Expand activity") { open = !open }
    else if (isSpec) Modifier.clickable(role = Role.Button, onClickLabel = "Open spec") { onAction(DetailAction.OpenSpecFile(requireNotNull(event.path))) } else Modifier
  Row(Modifier.fillMaxWidth().then(modifier).padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
    BasicText(event.at?.let { formatInstant(it, "HH:mm:ss") } ?: "—", style = SkillBillTheme.type.monoSm.copy(color = colors.fgFaint))
    CompositionLocalProvider(LocalContentColor provides if (failed) colors.stBlocked else if (event.kind == "write") colors.stDone else colors.fgFaint) { SbIcon(activityIcon(event), 14.dp) }
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
      BasicText(text, style = (if (event.kind == "note" || event.kind == "phase") SkillBillTheme.type.tSm else SkillBillTheme.type.monoSm).copy(color = if (failed) colors.stBlocked else if (isSpec) colors.accentText else colors.fg2), maxLines = if (open) Int.MAX_VALUE else 1, overflow = TextOverflow.Ellipsis)
      if (event.children.isNotEmpty()) BasicText("+${event.children.size} more files", style = SkillBillTheme.type.tXs.copy(color = colors.fgFaint))
      if (open) event.children.forEach { child -> BasicText(child.text, style = SkillBillTheme.type.monoSm.copy(color = colors.fgMuted)) }
    }
    event.durationMs?.let { duration -> BasicText(planToolDuration(duration), style = SkillBillTheme.type.monoSm.copy(color = colors.fgFaint)) }
  }
}

private fun activityIcon(event: PlanActivityEvent): ImageVector = when {
  event.state == "failed" || event.kind == "error" -> LucideIcons.alert
  event.kind == "write" -> LucideIcons.pencil
  event.kind == "read" -> LucideIcons.fileText
  event.kind == "search" -> LucideIcons.search
  event.kind == "command" -> LucideIcons.terminal
  event.kind == "mcp" -> LucideIcons.link
  event.kind == "phase" -> LucideIcons.play
  else -> LucideIcons.bot
}

private fun planToolDuration(milliseconds: Long): String =
  if (milliseconds < 10000) "${milliseconds / 1000}.${milliseconds % 1000 / 100}s" else activityDuration(milliseconds)
