package dev.skillbill.desktop.feature.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.LocalContentColor
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.LocalUiTicker
import dev.skillbill.desktop.core.designsystem.detail.DocBlockGap
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.designsystem.icon.SbIcon
import dev.skillbill.desktop.core.domain.model.Detail
import dev.skillbill.desktop.core.domain.model.WorkItem

@Composable
internal fun PlanNowCard(item: WorkItem, detail: Detail) {
  if (!item.running) return
  val ticker = LocalUiTicker.current
  DisposableEffect(ticker) { ticker.retain(); onDispose { ticker.release() } }
  val events = detail.planActivity.filterNot { it.kind == "stream" }
  val latest = events.maxByOrNull { it.updatedAt }
  val lastAt = detail.planOutputUpdatedAt ?: latest?.updatedAt ?: item.startedAt
  val quiet = lastAt?.let { (ticker.now.value - it).inWholeSeconds >= PLAN_QUIET_SECONDS } == true
  val colors = SkillBillTheme.colors
  val color = if (quiet || latest?.state == "failed") colors.stBlocked else colors.stActive
  val phaseView = planStageViews(item, detail).lastOrNull { it.state == PlanStageState.Running }
  val phase = phaseView?.phaseId
  val phaseTitle = phaseView?.label ?: PLAN_STAGES.first().second
  val what = when {
    quiet -> "No new activity for ${elapsedText(requireNotNull(lastAt), ticker.now.value)}"
    latest != null -> {
      val event = PlanActivityEvent(latest.id, latest.at, latest.text, kind = latest.kind, state = latest.state)
      val output = planOutputText(latest.text, planRoots(item))
      if (output.kind != "note") output.display
      else listOf(planActivityVerb(event), latest.path?.let(::fileName) ?: output.subject).filter(String::isNotBlank).joinToString(" ")
    }
    else -> phaseTitle
  }
  val context = when {
    quiet -> "The agent may be thinking on a long step."
    latest == null -> "Live tool steps are not available yet. Recorded phases and saved files appear below."
    else -> listOfNotNull(phase, latest.subtaskId?.let { "subtask $it" + detail.standalonePlan?.totalSubtaskCount?.let { total -> " of $total" }.orEmpty() }, latest.agentId).joinToString(" · ")
  }
  Row(Modifier.fillMaxWidth().padding(top = DocBlockGap).border(1.dp, color.copy(alpha = .25f), RoundedCornerShape(8.dp)).background(Brush.horizontalGradient(listOf(color.copy(alpha = .06f), colors.bgPanel)), RoundedCornerShape(8.dp)).padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
    CompositionLocalProvider(LocalContentColor provides color) { SbIcon(if (quiet) LucideIcons.clock else if (latest?.kind == "write") LucideIcons.pencil else LucideIcons.bot, 26.dp) }
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
      BasicText(what, style = SkillBillTheme.type.tSmMedium.copy(color = colors.fg), maxLines = 1, overflow = TextOverflow.Ellipsis)
      BasicText(context, style = SkillBillTheme.type.tXs.copy(color = colors.fgMuted))
    }
    latest?.updatedAt?.let { BasicText("${elapsedText(it, ticker.now.value)} ago", style = SkillBillTheme.type.monoSm.copy(color = colors.fgFaint)) }
  }
}
