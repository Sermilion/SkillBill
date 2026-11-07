package dev.skillbill.desktop.feature.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.composite.Edge
import dev.skillbill.desktop.core.designsystem.composite.hairline
import dev.skillbill.desktop.core.designsystem.component.SbControlSize
import dev.skillbill.desktop.core.designsystem.component.SbIconButton
import dev.skillbill.desktop.core.designsystem.component.SbTooltip
import dev.skillbill.desktop.core.designsystem.component.SbTag
import dev.skillbill.desktop.core.designsystem.component.sbFocusRing
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.designsystem.icon.SbIcon
import dev.skillbill.desktop.core.domain.model.ActivityBlock
import dev.skillbill.desktop.core.domain.model.ActivityBlockKind
import dev.skillbill.desktop.core.domain.model.ActivityFact
import dev.skillbill.desktop.core.domain.model.ActivityGroup
import dev.skillbill.desktop.core.domain.model.ActivityPhase
import dev.skillbill.desktop.core.domain.model.ActivityStatus
import kotlin.time.Instant

@Composable
internal fun ActivityCard(
  group: ActivityGroup,
  open: Boolean,
  now: Instant,
  modifier: Modifier = Modifier,
  onCopyReason: ((String) -> Unit)? = null,
  onToggle: () -> Unit,
) {
  val colors = SkillBillTheme.colors
  val shape = RoundedCornerShape(8.dp)
  val source = remember { MutableInteractionSource() }
  Column(modifier.fillMaxWidth().clip(shape).background(colors.bgPanel).border(1.dp, if (group.status == ActivityStatus.RUNNING) colors.accentRing else colors.border, shape)) {
    Row(Modifier.fillMaxWidth().sbFocusRing(source, shape).semantics { stateDescription = if (open) "Expanded" else "Collapsed" }
      .clickable(source, null, role = Role.Button, onClickLabel = if (open) "Collapse activity" else "Expand activity", onClick = onToggle)
      .padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      SbIcon(if (open) LucideIcons.chevronDown else LucideIcons.chevronRight, 12.dp)
      ActivityDot(group.status)
      ActivityText(group.subtaskId?.toString() ?: "··", Modifier.width(22.dp), weight = FontWeight.SemiBold, faint = true)
      val label = when (group.status) {
        ActivityStatus.RUNNING -> "running"
        ActivityStatus.BLOCKED -> "blocked"
        else -> group.blocks.size.takeIf { it > 0 }?.let { "$it block${if (it == 1) "" else "s"}" }
      }
      Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ActivityText(group.name, Modifier.weight(1f, fill = false), size = 12.5f, sans = true, weight = FontWeight.Medium, color = if (open) colors.fg else colors.fg2)
        label?.let { SbTag(it, tone = if (group.status == ActivityStatus.RUNNING) colors.accentText else colors.stBlocked) }
      }
      ActivityTime(group.startedAt, now)
      val active = group.activeMilliseconds(now)
      val wall = group.startedAt?.let { (group.finishedAt ?: now) - it }
      SbTooltip(wall?.let { "Phase time; ${activityDuration(it.inWholeMilliseconds)} start to finish" } ?: "Phase time") {
        ActivityText(if (active > 0) activityDuration(active) else "", Modifier.width(52.dp))
      }
      val agents = (group.phases.mapNotNull { it.agent } + (group.before + group.after).mapNotNull { it.agent }).distinct()
      ActivityText(agents.joinToString(" + "), Modifier.width(104.dp), faint = true)
    }
    if (open) Column(Modifier.fillMaxWidth().hairline(Edge.Top, colors.border)) {
      val entries = remember(group) { activityEntries(group) }
      group.before.forEach { ActivityFactRow(it, now) }
      entries.forEach { entry -> when (entry) {
        is ActivityEntry.Block -> ActivityBlockRow(
          entry.block,
          now,
          current = group.status == ActivityStatus.BLOCKED && entry.block.id == group.blocks.maxByOrNull { it.at }?.id,
          onCopyReason = onCopyReason,
        )
        is ActivityEntry.Wait -> ActivityWait(entry.milliseconds)
        is ActivityEntry.Phases -> ActivityPhaseFold(entry.phases, group.status == ActivityStatus.RUNNING, now)
      } }
      group.after.forEach { ActivityFactRow(it, now) }
      if (entries.isEmpty() && group.before.isEmpty() && group.after.isEmpty()) ActivityText("No phases ran.", Modifier.padding(20.dp), sans = true, faint = true)
    }
  }
}

@Composable
private fun ActivityPhaseFold(phases: List<ActivityPhase>, live: Boolean, now: Instant) {
  var expanded by rememberSaveable(phases.first().phaseId, phases.first().attempt, phases.first().startedAt.toString()) { mutableStateOf(false) }
  if (phases.size == 1) ActivityPhaseRow(phases.single(), live, now) else {
    val last = phases.last()
    val counts = phases.groupingBy { it.phaseId }.eachCount().entries.joinToString(", ") { "${it.key} ×${it.value}" }
    val agents = phases.mapNotNull { it.agent }.distinct().joinToString(" + ")
    ActivityPhaseRow(last.copy(agent = agents, model = null, effort = null), false, now, loopLabel = "${activityLoopFamily(last.phaseId)} loop", loopDetail = counts,
      duration = phases.sumOf { phase -> phase.startedAt?.let { start -> phase.finishedAt?.let { (it - start).inWholeMilliseconds } } ?: 0 }, expanded = expanded, onToggle = { expanded = !expanded })
    if (expanded) Column(Modifier.background(SkillBillTheme.colors.bgInset).padding(start = 18.dp)) {
      phases.forEach { ActivityPhaseRow(it, false, now) }
    }
  }
}

@Composable
private fun ActivityPhaseRow(
  phase: ActivityPhase,
  live: Boolean,
  now: Instant,
  loopLabel: String? = null,
  loopDetail: String? = null,
  duration: Long? = phase.startedAt?.let { start -> phase.finishedAt?.let { (it - start).inWholeMilliseconds } },
  expanded: Boolean = false,
  onToggle: (() -> Unit)? = null,
) {
  val colors = SkillBillTheme.colors
  val running = live && phase.finishedAt == null && phase.startedAt != null
  val source = remember { MutableInteractionSource() }
  val toggle = if (onToggle != null) Modifier.sbFocusRing(source, RoundedCornerShape(0.dp))
    .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }
    .clickable(source, null, role = Role.Button, onClickLabel = if (expanded) "Collapse loop" else "Expand loop", onClick = onToggle) else Modifier
  Column(Modifier.fillMaxWidth().hairline(Edge.Top, colors.borderSubtle).then(toggle).padding(horizontal = 12.dp, vertical = 7.dp)) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      ActivityTime(phase.finishedAt ?: phase.startedAt, now)
      ActivityDot(if (running) ActivityStatus.RUNNING else ActivityStatus.COMPLETE)
      Column(Modifier.weight(1f)) {
        ActivityText(loopLabel ?: phase.phaseId, size = 12f, weight = FontWeight.Medium, color = colors.fg2)
        if (loopDetail != null) ActivityText(loopDetail, size = 10f, faint = true)
        else if (phase.attempt > 1) ActivityText("#${phase.attempt}", size = 10f, faint = true)
      }
      phase.verdict?.let { ActivityText(it, size = 10f, faint = true) }
      ActivityText(duration?.let(::activityDuration).orEmpty(), Modifier.width(44.dp))
      val model = listOfNotNull(phase.model?.removePrefix("claude-"), phase.effort).joinToString(" · ")
      SbTooltip(listOfNotNull(phase.agent, phase.model, phase.effort).joinToString(" · ")) {
        val label = phase.agent?.let { agent ->
          if (loopLabel != null) agent else listOf(when (agent) { "claude" -> "Cl"; "codex" -> "Cx"; else -> agent }, model.ifBlank { agent }).joinToString(" ")
        }.orEmpty()
        ActivityText(label, Modifier.width(164.dp))
      }
      ActivityDisclosure(onToggle != null, expanded)
    }
    phase.summary?.takeIf { it.isNotBlank() }?.let { summary -> ActivityExpandable(summary, 2, Modifier.padding(start = 63.dp, top = 3.dp)) }
  }
}

@Composable
internal fun ActivityBlockRow(
  block: ActivityBlock,
  now: Instant,
  current: Boolean = false,
  onCopyReason: ((String) -> Unit)? = null,
) {
  val colors = SkillBillTheme.colors
  var expanded by rememberSaveable(block.id) { mutableStateOf(false) }
  var truncated by remember(block.reason) { mutableStateOf(false) }
  val source = remember { MutableInteractionSource() }
  val expand = if (expanded || truncated) Modifier.sbFocusRing(source, RoundedCornerShape(0.dp))
    .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }
    .clickable(source, null, role = Role.Button, onClickLabel = if (expanded) "Collapse block" else "Read full block") { expanded = !expanded } else Modifier
  Column(Modifier.fillMaxWidth().then(expand).background(colors.stBlocked.copy(alpha = .07f)).drawBehind { drawLine(colors.stBlocked, Offset.Zero, Offset(0f, size.height), 3.dp.toPx()) }.hairline(Edge.Top, colors.borderSubtle).padding(horizontal = 12.dp, vertical = 9.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      ActivityTime(block.at, now)
      ActivityDot(ActivityStatus.BLOCKED)
      ActivityText(when (block.kind) {
        ActivityBlockKind.BLOCKED -> "Blocked"
        ActivityBlockKind.PULL_REQUEST_FAILED -> "Pull request failed"
        ActivityBlockKind.AWAITING_DECISION -> if (current && block.needsDecision) "Waiting for your decision" else "Decision requested"
      }, sans = true, size = 12f, weight = FontWeight.SemiBold, color = colors.stBlocked)
      ActivityText(listOfNotNull(block.phaseId, block.attempt?.let { "attempt $it" }, "needs your decision".takeIf { current && block.needsDecision }).joinToString(" · "), Modifier.weight(1f))
      ActivityDisclosure(expanded || truncated, expanded)
      block.reason?.takeIf { onCopyReason != null }?.let { reason ->
        SbIconButton(LucideIcons.copy, "Copy block reason", { onCopyReason?.invoke(reason) }, size = SbControlSize.Small)
      }
    }
    block.reason?.let { ActivityText(it, Modifier.padding(start = 63.dp, top = 3.dp), size = 12f, sans = true, color = colors.fg2, maxLines = if (expanded) Int.MAX_VALUE else 3,
      onTextLayout = { if (!expanded) truncated = it.hasVisualOverflow }) }
  }
}

@Composable
private fun ActivityDisclosure(visible: Boolean, expanded: Boolean) {
  Box(Modifier.width(12.dp)) {
    if (visible) SbIcon(if (expanded) LucideIcons.chevronDown else LucideIcons.chevronRight, 12.dp)
  }
}

@Composable
private fun ActivityExpandable(text: String, lines: Int, modifier: Modifier) {
  var expanded by rememberSaveable(text) { mutableStateOf(false) }
  var truncated by remember(text, lines) { mutableStateOf(false) }
  val source = remember { MutableInteractionSource() }
  val toggle = if (expanded || truncated) Modifier.sbFocusRing(source, RoundedCornerShape(0.dp))
    .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }
    .clickable(source, null, role = Role.Button, onClickLabel = if (expanded) "Collapse text" else "Read full text") { expanded = !expanded } else Modifier
  Row(modifier.fillMaxWidth().then(toggle), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    ActivityText(text, Modifier.weight(1f), size = 12f, sans = true, color = SkillBillTheme.colors.fgMuted,
      maxLines = if (expanded) Int.MAX_VALUE else lines, onTextLayout = { if (!expanded) truncated = it.hasVisualOverflow })
    ActivityDisclosure(expanded || truncated, expanded)
  }
}

@Composable
private fun ActivityFactRow(fact: ActivityFact, now: Instant) {
  val colors = SkillBillTheme.colors
  Column(Modifier.fillMaxWidth().hairline(Edge.Top, colors.borderSubtle).padding(horizontal = 12.dp, vertical = 7.dp)) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      ActivityTime(fact.at, now)
      ActivityDot(if (fact.running) ActivityStatus.RUNNING else ActivityStatus.COMPLETE)
      Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        ActivityText(fact.name, Modifier.weight(1f, fill = false), size = 12f, weight = FontWeight.Medium, color = colors.fg2)
        fact.detail?.let { ActivityText(it, faint = true) }
      }
      if (fact.agent != null) ActivityText(listOfNotNull(fact.model?.removePrefix("claude-") ?: fact.agent, fact.effort).joinToString(" · "), Modifier.widthIn(max = 164.dp))
    }
    fact.summary?.let { ActivityExpandable(it, 2, Modifier.padding(start = 63.dp, top = 3.dp)) }
  }
}
