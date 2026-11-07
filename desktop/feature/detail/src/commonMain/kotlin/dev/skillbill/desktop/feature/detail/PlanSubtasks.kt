package dev.skillbill.desktop.feature.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.LocalContentColor
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.LocalUiTicker
import dev.skillbill.desktop.core.designsystem.component.SbSpinner
import dev.skillbill.desktop.core.designsystem.detail.SbBox
import dev.skillbill.desktop.core.designsystem.detail.sbBoxSegment
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.designsystem.icon.SbIcon
import dev.skillbill.desktop.core.designsystem.icon.SbIconSize
import dev.skillbill.desktop.core.domain.model.Detail
import dev.skillbill.desktop.core.domain.model.StandalonePlanProgress
import dev.skillbill.desktop.core.domain.model.StandalonePlanUnit
import dev.skillbill.desktop.core.domain.model.WorkItem
import kotlin.time.Instant

internal fun DetailSections.planRows(item: WorkItem, plan: StandalonePlanProgress, detail: Detail, onAction: (DetailAction) -> Unit) {
  val saved = plan.units.count { it.state == "saved" }
  val wave = planWaveLabel(plan).takeUnless { planFinished(item, detail) }
  section("plan-subtasks", "Subtasks", count = listOfNotNull("$saved/${plan.totalSubtaskCount} saved", wave).joinToString(" · ")) {
    itemsIndexed(plan.units, key = { _, unit -> "plan-unit-${unit.subtaskId}" }) { index, unit ->
      PlanSubtaskRow(unit, item, detail, Modifier.sbBoxSegment(index, plan.units.size)) {
        onAction(DetailAction.OpenSpecFile(unit.specPath))
      }
    }
  }
}

internal fun planWaveLabel(plan: StandalonePlanProgress): String? {
  val wave = plan.planningWaveNumber ?: return null
  val capacity = plan.planningWaveCapacity?.takeIf { it > 0 } ?: return "Wave $wave"
  val remaining = plan.units.count { it.state == "queued" }
  val total = wave + (remaining + capacity - 1) / capacity
  return "Wave $wave of $total · ${if (hasBlockedSubtasks(plan)) "next wave waits" else "$capacity at a time"}"
}

@Composable
private fun PlanSubtaskRow(unit: StandalonePlanUnit, item: WorkItem, detail: Detail, modifier: Modifier, onOpen: () -> Unit) {
  val ticker = LocalUiTicker.current
  val running = item.running
  val writing = unit.state == "writing" && running
  if (writing) DisposableEffect(ticker) { ticker.retain(); onDispose { ticker.release() } }
  val now = if (running) ticker.now.value else detail.updatedAt
  val quiet = planUnitQuietSince(unit, now).takeIf { writing }
  val saved = unit.state == "saved"
  val blocked = unit.state == "blocked"
  val colors = SkillBillTheme.colors
  val color = when {
    blocked || quiet != null -> colors.stBlocked
    saved -> colors.stDone
    writing -> colors.stActive
    else -> colors.fgFaint
  }
  val age = unit.startedAt?.let { elapsedText(it, unit.finishedAt ?: now) }
  val status = when {
    saved -> age ?: "Saved"
    blocked -> "Blocked"
    quiet != null -> "quiet ${elapsedText(quiet, now)}"
    writing -> listOfNotNull("Writing", age).joinToString(" · ")
    unit.state == "writing" -> listOfNotNull("Stopped", age).joinToString(" · ")
    running -> "Queued"
    else -> "Not started"
  }
  val background = when {
    blocked || quiet != null -> colors.stBlocked.copy(alpha = .06f)
    writing -> colors.stActive.copy(alpha = .04f)
    else -> Color.Transparent
  }
  Column(
    modifier.fillMaxWidth().background(background)
      .then(if (saved) Modifier.clickable(role = Role.Button, onClickLabel = "Open saved spec", onClick = onOpen) else Modifier)
      .semantics { stateDescription = status }.padding(12.dp),
    verticalArrangement = Arrangement.spacedBy(5.dp),
  ) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
      PlanGlyph(if (unit.state == "writing" && !running) "queued" else unit.state, color)
      BasicText("${unit.subtaskId}. ${unit.name}", Modifier.weight(1f), style = SkillBillTheme.type.tSmMedium.copy(color = if (unit.state == "queued") colors.fgFaint else colors.fg), maxLines = 1, overflow = TextOverflow.Ellipsis)
      BasicText(status, style = SkillBillTheme.type.tXs.copy(color = if (saved) colors.fgFaint else color), maxLines = 1)
      if (saved) CompositionLocalProvider(LocalContentColor provides colors.fgFaint) { SbIcon(LucideIcons.external, SbIconSize.Sm) }
    }
    val outputIsCurrent = unit.latestOutputAt?.let { unit.startedAt?.let { started -> it >= started } != false } != false
    val line = when {
      blocked -> unit.blockedReason
      writing && outputIsCurrent -> unit.latestOutput ?: detail.planSubtaskLatestLines[unit.subtaskId]
      else -> null
    } ?: if (unit.dependsOn.isEmpty()) "no dependencies" else "after ${unit.dependsOn.joinToString(", ")}"
    BasicText(line, Modifier.padding(start = 24.dp), style = (if (writing) SkillBillTheme.type.monoSm else SkillBillTheme.type.tXs).copy(color = if (blocked) colors.stBlocked else colors.fgMuted), maxLines = if (blocked) Int.MAX_VALUE else 1, overflow = TextOverflow.Ellipsis)
  }
}

internal fun planUnitQuietSince(unit: StandalonePlanUnit, now: Instant): Instant? {
  if (unit.state != "writing") return null
  val latest = unit.latestOutputAt?.takeIf { unit.startedAt?.let { started -> it >= started } != false } ?: unit.startedAt ?: return null
  return latest.takeIf { (now - it).inWholeSeconds >= PLAN_QUIET_SECONDS }
}

@Composable
internal fun PlanGlyph(state: String, color: Color) {
  when (state) {
    "writing" -> SbSpinner(color = color)
    "saved", "blocked" -> CompositionLocalProvider(LocalContentColor provides color) {
      SbIcon(if (state == "saved") LucideIcons.check else LucideIcons.alert, SbIconSize.Sm)
    }
    else -> Box(Modifier.size(12.dp).border(1.dp, color, CircleShape))
  }
}

@Composable
internal fun PlanSubtasksPending() {
  SbBox {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      BasicText("Subtasks appear here when the work is split.", style = SkillBillTheme.type.tSm.copy(color = SkillBillTheme.colors.fgFaint))
      listOf(.72f, .58f, .66f).forEach { fraction ->
        Box(Modifier.fillMaxWidth(fraction).height(8.dp).background(SkillBillTheme.colors.borderSubtle, RoundedCornerShape(4.dp)))
      }
    }
  }
}

internal fun elapsedTextOrNull(start: Instant?, end: Instant): String? = start?.let { elapsedText(it, end) }

internal fun elapsedText(start: Instant, end: Instant): String {
  val seconds = ((end - start).inWholeSeconds).coerceAtLeast(0)
  return when {
    seconds >= 3_600 -> "${seconds / 3_600}h ${(seconds % 3_600) / 60}m"
    seconds >= 60 -> "${seconds / 60}m ${(seconds % 60).toString().padStart(2, '0')}s"
    else -> "${seconds}s"
  }
}
