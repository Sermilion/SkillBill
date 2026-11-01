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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.SbTooltip
import dev.skillbill.desktop.core.designsystem.component.sbFocusRing
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.designsystem.icon.SbIcon
import dev.skillbill.desktop.core.domain.model.ActivityBlock
import dev.skillbill.desktop.core.domain.model.ActivityBlockKind
import dev.skillbill.desktop.core.domain.model.ActivityFact
import dev.skillbill.desktop.core.domain.model.ActivityGroup
import dev.skillbill.desktop.core.domain.model.ActivityPhase
import dev.skillbill.desktop.core.domain.model.ActivityStatus
import dev.skillbill.desktop.core.ui.changes.hairlineTop
import kotlin.time.Instant

@Composable
internal fun ActivityCard(group: ActivityGroup, open: Boolean, now: Instant, modifier: Modifier = Modifier, onToggle: () -> Unit) {
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
      ActivityText(group.name, Modifier.weight(1f), size = 12.5f, sans = true, weight = FontWeight.Medium, color = if (open) colors.fg else colors.fg2)
      val label = when (group.status) {
        ActivityStatus.RUNNING -> "running"
        ActivityStatus.BLOCKED -> "blocked"
        else -> group.blocks.size.takeIf { it > 0 }?.let { "$it block${if (it == 1) "" else "s"}" }
      }
      label?.let { ActivityTag(it, group.status == ActivityStatus.RUNNING) }
      ActivityTime(group.startedAt, now)
      val active = group.activeMilliseconds(now)
      val wall = group.startedAt?.let { (group.finishedAt ?: now) - it }
      SbTooltip(wall?.let { "Phase time; ${activityDuration(it.inWholeMilliseconds)} start to finish" } ?: "Phase time") {
        ActivityText(if (active > 0) activityDuration(active) else "", Modifier.width(52.dp))
      }
      val agents = (group.phases.mapNotNull { it.agent } + (group.before + group.after).mapNotNull { it.agent }).distinct()
      ActivityText(agents.joinToString(" + "), Modifier.width(104.dp), faint = true)
    }
    if (open) Column(Modifier.fillMaxWidth().hairlineTop(colors.border)) {
      val entries = remember(group) { activityEntries(group) }
      group.before.forEach { ActivityFactRow(it, now) }
      entries.forEach { entry -> when (entry) {
        is ActivityEntry.Block -> ActivityBlockRow(entry.block, now)
        is ActivityEntry.Wait -> ActivityWait(entry.milliseconds)
        is ActivityEntry.Phases -> ActivityPhaseFold(entry.phases, group.status == ActivityStatus.RUNNING, now)
      } }
      group.after.forEach { ActivityFactRow(it, now) }
      if (entries.isEmpty() && group.before.isEmpty() && group.after.isEmpty()) ActivityText("No phases ran.", Modifier.padding(20.dp), sans = true, faint = true)
    }
  }
}

@Composable
private fun ActivityTag(text: String, running: Boolean = false) {
  val color = if (running) SkillBillTheme.colors.accentText else SkillBillTheme.colors.stBlocked
  ActivityText(text, Modifier.background(color.copy(alpha = .12f), RoundedCornerShape(4.dp)).border(1.dp, color.copy(alpha = .35f), RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 2.dp),
    size = 10f, weight = FontWeight.Medium, color = color)
}

@Composable
private fun ActivityPhaseFold(phases: List<ActivityPhase>, live: Boolean, now: Instant) {
  var expanded by rememberSaveable(phases.first().phaseId, phases.first().attempt, phases.first().startedAt.toString()) { mutableStateOf(false) }
  if (phases.size == 1) ActivityPhaseRow(phases.single(), live, now) else {
    val last = phases.last()
    val counts = phases.groupingBy { it.phaseId }.eachCount().entries.joinToString(", ") { "${it.key} ×${it.value}" }
    val agents = phases.mapNotNull { it.agent }.distinct().joinToString(" + ")
    ActivityPhaseRow(last.copy(agent = agents, model = null, effort = null), false, now, loopLabel = "${activityLoopFamily(last.phaseId)} loop", loopDetail = counts,
      duration = phases.sumOf { phase -> phase.startedAt?.let { start -> phase.finishedAt?.let { (it - start).inWholeMilliseconds } } ?: 0 }, onToggle = { expanded = !expanded })
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
  onToggle: (() -> Unit)? = null,
) {
  val colors = SkillBillTheme.colors
  val running = live && phase.finishedAt == null && phase.startedAt != null
  Column(Modifier.fillMaxWidth().hairlineTop(colors.borderSubtle).then(if (onToggle != null) Modifier.clickable(role = Role.Button, onClick = onToggle) else Modifier).padding(horizontal = 12.dp, vertical = 7.dp)) {
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
        ActivityText(listOfNotNull(phase.agent?.let { when (it) { "claude" -> "Cl"; "codex" -> "Cx"; else -> it } }, model.ifBlank { null }).joinToString(" "), Modifier.width(164.dp))
      }
    }
    phase.summary?.takeIf { it.isNotBlank() }?.let { summary -> ActivityExpandable(summary, 2, Modifier.padding(start = 63.dp, top = 3.dp)) }
  }
}

@Composable
private fun ActivityBlockRow(block: ActivityBlock, now: Instant) {
  val colors = SkillBillTheme.colors
  Column(Modifier.fillMaxWidth().background(colors.stBlocked.copy(alpha = .07f)).hairlineTop(colors.borderSubtle).padding(horizontal = 12.dp, vertical = 9.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      ActivityTime(block.at, now)
      ActivityDot(ActivityStatus.BLOCKED)
      ActivityText(when (block.kind) {
        ActivityBlockKind.BLOCKED -> "Blocked"
        ActivityBlockKind.PULL_REQUEST_FAILED -> "Pull request failed"
        ActivityBlockKind.AWAITING_DECISION -> "Waiting for your decision"
      }, sans = true, size = 12f, weight = FontWeight.SemiBold, color = colors.stBlocked)
      ActivityText(listOfNotNull(block.phaseId, block.attempt?.let { "attempt $it" }, "needs your decision".takeIf { block.needsDecision }).joinToString(" · "))
    }
    block.reason?.let { ActivityExpandable(it, 3, Modifier.padding(start = 63.dp, top = 3.dp), block = true) }
  }
}

@Composable
private fun ActivityExpandable(text: String, lines: Int, modifier: Modifier, block: Boolean = false) {
  var expanded by rememberSaveable(text) { mutableStateOf(false) }
  ActivityText(text, modifier.clickable(role = Role.Button, onClickLabel = if (expanded) "Collapse text" else "Read full text") { expanded = !expanded },
    size = 12f, sans = true, color = if (block) SkillBillTheme.colors.fg2 else SkillBillTheme.colors.fgMuted, maxLines = if (expanded) Int.MAX_VALUE else lines)
}

@Composable
private fun ActivityFactRow(fact: ActivityFact, now: Instant) {
  val colors = SkillBillTheme.colors
  Column(Modifier.fillMaxWidth().hairlineTop(colors.borderSubtle).padding(horizontal = 12.dp, vertical = 7.dp)) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      ActivityTime(fact.at, now)
      ActivityDot(if (fact.running) ActivityStatus.RUNNING else ActivityStatus.COMPLETE)
      ActivityText(fact.name, Modifier.weight(1f), size = 12f, weight = FontWeight.Medium, color = colors.fg2)
      fact.detail?.let { ActivityText(it, faint = true) }
      ActivityText(listOfNotNull(fact.model?.removePrefix("claude-") ?: fact.agent, fact.effort).joinToString(" · "), Modifier.width(164.dp))
    }
    fact.summary?.let { ActivityExpandable(it, 2, Modifier.padding(start = 63.dp, top = 3.dp)) }
  }
}
