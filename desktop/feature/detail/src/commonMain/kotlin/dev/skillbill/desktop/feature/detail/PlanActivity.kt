package dev.skillbill.desktop.feature.detail

import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.skillbill.desktop.core.designsystem.LocalContentColor
import dev.skillbill.desktop.core.designsystem.LocalReducedMotion
import dev.skillbill.desktop.core.designsystem.SkillBillRadius
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.SbButton
import dev.skillbill.desktop.core.designsystem.component.SbButtonVariant
import dev.skillbill.desktop.core.designsystem.detail.DocBlockGap
import dev.skillbill.desktop.core.designsystem.detail.SectionCollapse
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.designsystem.icon.SbIcon
import dev.skillbill.desktop.core.domain.model.Detail
import dev.skillbill.desktop.core.domain.model.WorkItem

internal fun DetailSections.planActivitySection(item: WorkItem, detail: Detail, onAction: (DetailAction) -> Unit, onOpenBriefing: ((String) -> Unit)? = null) {
  val screen = requireNotNull(planScreenState)
  val events = planActivity(item, detail)
  planSection("plan-activity", "Activity", count = "${events.size} events", tools = { PlanActivityControls(screen, events) }) {
    item(key = "plan-activity-feed-panel") { PlanActivityFeed(item, detail, screen, onAction, onOpenBriefing) }
  }
}

@Composable
internal fun PlanActivityPanel(item: WorkItem, detail: Detail, collapse: SectionCollapse, screen: PlanScreenState, onAction: (DetailAction) -> Unit) {
  val events = planActivity(item, detail)
  val key = "plan-activity"
  val open = !collapse.isCollapsed(key)
  Column(Modifier.fillMaxWidth().padding(top = DocBlockGap), verticalArrangement = Arrangement.spacedBy(10.dp)) {
    PlanSectionHeader("Activity", "${events.size} events", open, { collapse.toggle(key) }, { PlanActivityControls(screen, events) })
    if (open) PlanActivityFeed(item, detail, screen, onAction)
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlanActivityControls(screen: PlanScreenState, events: List<PlanActivityEvent>) {
  val counts = mapOf(
    PlanActivityFilter.All to null,
    PlanActivityFilter.Files to events.count { it.kind in setOf("read", "write", "search") },
    PlanActivityFilter.Commands to events.count { it.kind in setOf("command", "mcp", "tool") },
    PlanActivityFilter.Notes to events.count { it.kind == "note" },
  )
  FlowRow(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
    PlanActivityFilter.entries.forEach { filter ->
      PlanFilterSegment(filter.label, counts[filter], screen.filter == filter) { screen.filter = filter }
    }
    PlanFollowToggle(screen.follow) { screen.follow = !screen.follow }
  }
}

@Composable
private fun PlanFilterSegment(label: String, count: Int?, selected: Boolean, onClick: () -> Unit) {
  val colors = SkillBillTheme.colors
  val source = remember { MutableInteractionSource() }
  val hovered by source.collectIsHoveredAsState()
  Row(
    Modifier.heightIn(min = 22.dp).background(if (selected) colors.bgActive else if (hovered) colors.bgHover else Color.Transparent, RoundedCornerShape(SkillBillRadius.xs))
      .hoverable(source).clickable(source, null, role = Role.Tab, onClick = onClick).semantics { this.selected = selected }
      .padding(horizontal = 7.dp),
    horizontalArrangement = Arrangement.spacedBy(4.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    BasicText(label, style = SkillBillTheme.type.tXs.copy(color = if (selected) colors.fg else colors.fgFaint))
    count?.let { BasicText(it.toString(), style = SkillBillTheme.type.tXs.copy(color = colors.fgFaint)) }
  }
}

@Composable
private fun PlanFollowToggle(on: Boolean, onToggle: () -> Unit) {
  val colors = SkillBillTheme.colors
  val color = if (on) colors.stActive else colors.fgFaint
  Row(
    Modifier.padding(start = 4.dp).heightIn(min = 22.dp).clickable(role = Role.Button, onClick = onToggle)
      .semantics { stateDescription = if (on) "Following latest activity" else "Paused" }.padding(horizontal = 7.dp),
    horizontalArrangement = Arrangement.spacedBy(4.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    CompositionLocalProvider(LocalContentColor provides color) { SbIcon(LucideIcons.arrowDown, 12.dp) }
    BasicText("Follow", style = SkillBillTheme.type.tXs.copy(color = color))
  }
}

@Composable
private fun PlanActivityFeed(item: WorkItem, detail: Detail, screen: PlanScreenState, onAction: (DetailAction) -> Unit, onOpenBriefing: ((String) -> Unit)? = null) {
  val events = planActivity(item, detail)
  val list = screen.activityFeed
  val rows = planActivityRows(events, screen.filter)
  val views = planStageViews(item, detail)
  val reached = views.filter { it.state != PlanStageState.NotStarted || events.any { event -> event.phaseId == it.phaseId } }
  val closed = screen.closedActivityGroups
  val noStream = detail.planActivity.none { it.kind != "stream" }
  val tail = item.running && planQuietSince(item, detail) == null && !hasBlockedSubtasks(detail.standalonePlan)
  val newestKey = planActivityNewestKey(events)
  val visibleCount = reached.sumOf { view -> 1 + if (view.phaseId in closed) 0 else rows.count { it.phaseId == view.phaseId } } + if (tail) 1 else 0
  val scroll = remember(list) { object : NestedScrollConnection {
    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
      if (source == NestedScrollSource.UserInput && available.y > 0) screen.follow = false
      return Offset.Zero
    }
    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
      if (source == NestedScrollSource.UserInput && !list.canScrollForward) screen.follow = true
      return Offset.Zero
    }
  } }
  LaunchedEffect(screen.follow, visibleCount, events.mapNotNull { it.updatedAt ?: it.at }.maxOrNull(), screen.filter, tail) {
    if (screen.follow && visibleCount > 0) list.scrollToItem(visibleCount - 1)
  }
  LaunchedEffect(screen.activityJumpPhaseId, reached, rows, closed.toList()) {
    val phaseId = screen.activityJumpPhaseId ?: return@LaunchedEffect
    val index = reached.takeWhile { it.phaseId != phaseId }.sumOf { view ->
      1 + if (view.phaseId in closed) 0 else rows.count { it.phaseId == view.phaseId }
    }
    if (index < visibleCount) list.scrollToItem(index)
    screen.activityJumpPhaseId = null
  }
  Box(Modifier.fillMaxWidth().height(282.dp).border(1.dp, SkillBillTheme.colors.borderSubtle, RoundedCornerShape(SkillBillRadius.md))) {
    LazyColumn(Modifier.fillMaxSize().testTag("plan-activity-feed").nestedScroll(scroll).onPreviewKeyEvent { event ->
        if (event.type == KeyEventType.KeyDown && event.key in setOf(Key.DirectionUp, Key.PageUp, Key.MoveHome)) screen.follow = false
        if (event.type == KeyEventType.KeyDown && event.key == Key.MoveEnd) screen.follow = true
        false
      }, state = list) {
      reached.forEach { view ->
        val id = view.phaseId
        val groupRows = rows.filter { it.phaseId == id }
        stickyHeader(key = "plan-group-$id") {
          PlanActivityGroup(view, events.filter { it.phaseId == id }, id !in closed, !noStream) {
            if (id in closed) closed.remove(id) else closed.add(id)
          }
        }
        if (id !in closed) items(groupRows, key = { it.key }) { event ->
          val first = groupRows.firstOrNull()?.key == event.key
          val hasBriefing = detail.standalonePlan?.stages?.any { it.phaseId == event.phaseId && !it.briefingText.isNullOrBlank() } == true
          PlanActivityRow(event, planRoots(item), item.running && (event.key == newestKey || event.children.any { it.key == newestKey }), first, onAction, onOpenBriefing.takeIf { hasBriefing })
        }
      }
      if (tail) item(key = "plan-activity-tail") { PlanActivityTail() }
    }
    if (!screen.follow) SbButton("Jump to latest", { screen.follow = true }, Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp), variant = SbButtonVariant.Secondary, icon = LucideIcons.arrowDown)
  }
}

@Composable
private fun PlanActivityGroup(view: PlanStageView, groupEvents: List<PlanActivityEvent>, open: Boolean, showSummary: Boolean, onToggle: () -> Unit) {
  val colors = SkillBillTheme.colors
  Row(
    Modifier.fillMaxWidth().heightIn(min = 28.dp).background(colors.bgPanel).drawBehind { drawLine(colors.borderSubtle, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx()) }
      .clickable(role = Role.Button, onClickLabel = "${if (open) "Collapse" else "Expand"} ${view.label}", onClick = onToggle).padding(horizontal = 12.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
  ) {
    CompositionLocalProvider(LocalContentColor provides colors.fgFaint) { SbIcon(LucideIcons.chevronDown, 12.dp, modifier = Modifier.rotate(if (open) 0f else -90f)) }
    BasicText(view.label, style = SkillBillTheme.type.tSmMedium.copy(color = colors.fg2), maxLines = 1, overflow = TextOverflow.Ellipsis)
    BasicText(view.phaseId, style = monoXs().copy(color = colors.fgFaint))
    Spacer(Modifier.weight(1f))
    val summary = if (showSummary) planActivitySummary(groupEvents) else ""
    if (summary.isNotBlank()) BasicText(summary, style = SkillBillTheme.type.tXs.copy(color = colors.fgFaint), maxLines = 1, overflow = TextOverflow.Ellipsis)
  }
}

@Composable
internal fun PlanActivityRow(event: PlanActivityEvent, roots: List<String> = emptyList(), live: Boolean = false, first: Boolean = false, onAction: (DetailAction) -> Unit = {}, onOpenBriefing: ((String) -> Unit)? = null) {
  var open by rememberSaveable(event.key) { mutableStateOf(false) }
  val expandable = event.kind == "note" || (event.kind == "read" && event.children.isNotEmpty())
  val colors = SkillBillTheme.colors
  val monoSize = SkillBillTheme.type.tXs.fontSize
  val codeSpanStyle = SpanStyle(fontFamily = FontFamily.Monospace, fontSize = monoSize, color = colors.fg2, background = colors.bgInset)
  val interaction = remember { MutableInteractionSource() }
  val hovered by interaction.collectIsHoveredAsState()
  val eventPath = event.path
  val text = if (event.kind in setOf("read", "write") && eventPath != null) shortenPlanPath(eventPath, roots)
    else planOutputText(event.text, roots).subject
  val verb = if (event.state == "failed" || event.kind == "error") planActivityVerb(event) else event.verb?.takeIf(String::isNotBlank) ?: planActivityVerb(event)
  val rowModifier = Modifier.fillMaxWidth().heightIn(min = 28.dp)
    .then(if (hovered && expandable) Modifier.background(colors.bgHover) else Modifier)
    .then(if (expandable) Modifier.hoverable(interaction).clickable(role = Role.Button, onClickLabel = if (open) "Collapse activity" else "Expand activity") { open = !open } else Modifier)
    .then(if (first) Modifier else Modifier.drawBehind { drawLine(colors.borderSubtle, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) })
    .padding(horizontal = 12.dp, vertical = 4.dp)
  Row(rowModifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
    Box(Modifier.width(62.dp)) {
      BasicText(event.at?.let { formatInstant(it, "HH:mm:ss") } ?: "—", style = monoXs().copy(color = if (live) colors.stActive else colors.fgFaint))
    }
    Box(Modifier.width(16.dp).height(20.dp), contentAlignment = Alignment.Center) {
      CompositionLocalProvider(LocalContentColor provides planActivityTone(event, colors)) { SbIcon(planActivityGlyph(event), 14.dp) }
    }
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
      when {
        event.kind == "note" -> Row(Modifier.drawBehind { drawLine(colors.borderSubtle, Offset.Zero, Offset(0f, size.height), 2.dp.toPx()) }.padding(start = 8.dp)) {
          unitPrefix(event, colors)
          BasicText(text, style = SkillBillTheme.type.tSm.copy(color = colors.fg2, lineHeight = 20.sp), maxLines = if (open) Int.MAX_VALUE else 1, overflow = TextOverflow.Ellipsis)
        }
        event.kind == "phase" -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          BasicText(buildString {
            append(event.text.substringBefore(": "))
            event.text.substringAfter(": ", "").takeIf(String::isNotBlank)?.let { append(" · $it") }
          }, Modifier.weight(1f), style = SkillBillTheme.type.tSm.copy(color = colors.fgMuted, lineHeight = 20.sp), maxLines = if (open) Int.MAX_VALUE else 1, overflow = TextOverflow.Ellipsis)
          val phaseId = event.key.substringBefore(":").takeIf { event.key.endsWith(":start") }
          if (phaseId != null && event.phaseId == phaseId && onOpenBriefing != null) BasicText(
            "briefing",
            Modifier.clickable(role = Role.Button, onClickLabel = "Open $phaseId briefing") { onOpenBriefing.invoke(phaseId) },
            style = SkillBillTheme.type.tXs.copy(color = colors.accentText),
          )
        }
        event.kind == "write" -> Row {
          unitPrefix(event, colors)
          BasicText("$verb ", style = SkillBillTheme.type.tSm.copy(color = if (event.state == "failed") colors.stBlocked else colors.fgMuted, lineHeight = 20.sp))
          if (eventPath != null) BasicText(fileName(text), Modifier.clickable(role = Role.Button, onClickLabel = "Open file") { onAction(DetailAction.OpenSpecFile(event.openPath ?: eventPath)) }, style = monoXs().copy(color = colors.accentText))
          else BasicText(text, style = monoXs().copy(color = colors.fg2, background = colors.bgInset))
        }
        event.kind == "error" -> Row {
          unitPrefix(event, colors)
          BasicText(verb, style = SkillBillTheme.type.tSm.copy(color = colors.stBlocked))
          if (text.isNotBlank()) BasicText(" · $text", style = SkillBillTheme.type.tSm.copy(color = colors.fgMuted))
        }
        else -> BasicText(buildAnnotatedString {
          event.subtaskId?.let { withStyle(SpanStyle(fontFamily = FontFamily.Monospace, fontSize = monoSize, color = colors.fgMuted)) { append("#$it ") } }
          withStyle(SpanStyle(color = if (event.state == "failed") colors.stBlocked else colors.fgMuted)) { append(verb) }
          if (text.isNotBlank()) { append(" "); withStyle(codeSpanStyle) { append(text) } }
          event.exitCode?.let { withStyle(SpanStyle(color = colors.fgMuted)) { append(" · exit $it") } }
          if (event.kind == "read" && event.children.isNotEmpty()) withStyle(SpanStyle(fontSize = monoSize, color = colors.fgFaint)) { append(" +${event.children.size} more files") }
        }, style = SkillBillTheme.type.tSm.copy(color = colors.fg2, lineHeight = 20.sp), maxLines = if (open) Int.MAX_VALUE else 1, overflow = TextOverflow.Ellipsis)
      }
      if (open && event.kind == "read") event.children.forEach { child ->
        BasicText(buildAnnotatedString { withStyle(codeSpanStyle) { append(shortenPlanPath(child.path ?: child.text, roots)) } }, style = monoXs())
      }
    }
    event.durationMs?.let { BasicText(planToolDuration(it), style = monoXs().copy(color = colors.fgFaint)) }
  }
}

@Composable
private fun unitPrefix(event: PlanActivityEvent, colors: dev.skillbill.desktop.core.designsystem.SkillBillColors) {
  event.subtaskId?.let { BasicText("#$it ", style = monoXs().copy(color = colors.fgMuted)) }
}

@Composable
private fun PlanActivityTail() {
  val colors = SkillBillTheme.colors
  val reducedMotion = LocalReducedMotion.current
  val transition = if (reducedMotion) null else rememberInfiniteTransition()
  Row(Modifier.fillMaxWidth().drawBehind { drawLine(colors.borderSubtle, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) }.padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
      repeat(3) { index ->
        val alpha = transition?.animateFloat(.2f, .2f, infiniteRepeatable(keyframes { durationMillis = 1200; .2f at 0; 1f at 600; .2f at 1200 }, initialStartOffset = StartOffset(index * 200)), label = "plan-activity-dot-$index")?.value ?: 1f
        Box(Modifier.size(3.dp).alpha(alpha).background(colors.stActive, CircleShape))
      }
    }
    BasicText("Agent is working", style = SkillBillTheme.type.tXs.copy(color = colors.fgMuted))
  }
}

@Composable
private fun monoXs() = SkillBillTheme.type.monoSm.copy(fontSize = SkillBillTheme.type.tXs.fontSize, lineHeight = 20.sp)

private fun planToolDuration(milliseconds: Long): String =
  if (milliseconds < 10000) "${milliseconds / 1000}.${milliseconds % 1000 / 100}s" else activityDuration(milliseconds)
