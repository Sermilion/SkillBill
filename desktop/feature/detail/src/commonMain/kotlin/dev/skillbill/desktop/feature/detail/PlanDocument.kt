package dev.skillbill.desktop.feature.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import dev.skillbill.desktop.core.designsystem.SkillBillRadius
import dev.skillbill.desktop.core.designsystem.SkillBillSpace
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.SbButton
import dev.skillbill.desktop.core.designsystem.component.SbButtonVariant
import dev.skillbill.desktop.core.designsystem.component.SbControlSize
import dev.skillbill.desktop.core.designsystem.component.ReasonBlock
import dev.skillbill.desktop.core.designsystem.LocalContentColor
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.designsystem.icon.SbIcon
import dev.skillbill.desktop.core.domain.model.Detail
import dev.skillbill.desktop.core.domain.model.DetailList
import dev.skillbill.desktop.core.domain.model.StandalonePlanUnit
import dev.skillbill.desktop.core.domain.model.WorkItem
import kotlinx.coroutines.launch

internal fun DetailSections.planDocument(
  item: WorkItem,
  detail: Detail,
  state: DetailUiState,
  view: RunActionsView,
  onAction: (DetailAction) -> Unit,
) {
  val sections = this
  val screen = requireNotNull(planScreenState)
  val plan = detail.standalonePlan
  val blockedIds = blockedSubtaskIds(plan)
  item(key = "plan-stage-strip") {
    PlanStageStrip(item, detail) { phaseId ->
      screen.closedActivityGroups.remove(phaseId)
      screen.follow = false
      screen.activityJumpPhaseId = phaseId
      if (sections.collapse.isCollapsed("plan-activity")) sections.collapse.toggle("plan-activity")
      screen.scrollScope.launch { sections.indexOf("plan-activity")?.let { sections.scrollState.animateScrollToItem(it) } }
    }
  }
  if (blockedIds.isNotEmpty()) {
    item(key = "plan-blocked") {
      val incidentIndex = sections.indexOf("incident") ?: sections.indexOf("decision")
      PlanBlockedCallout(
        blockedIds,
        blockedIds.mapNotNull { id ->
          val reason = plan?.units?.firstOrNull { it.subtaskId == id }?.blockedReason?.takeIf(String::isNotBlank)
            ?: plan?.blockedReason?.takeIf(String::isNotBlank)
          reason?.let { if (blockedIds.size == 1) it else "Subtask $id: $it" }
        },
        view,
        onRetry = { onAction(ControlsAction.Recover) },
        onOpenIncident = incidentIndex?.let { index ->
          { screen.scrollScope.launch { sections.scrollState.animateScrollToItem(index) }; Unit }
        },
      )
    }
  } else {
    item(key = "plan-now") { PlanNowBlock(item, detail) }
  }
  if (blockedIds.isNotEmpty() || detail.incident != null || view.decision != null) incident(state, view, onAction, planHeader = true)
  if (plan?.units.isNullOrEmpty() && detail.planActivity.none { it.kind != "stream" }) planOutputTail(item, detail)
  planActivitySection(item, detail, onAction)
  if (plan != null && plan.units.isNotEmpty()) {
    planRows(item, plan, detail, onAction)
  } else {
    planSection("plan-subtasks", "Subtasks") {
      item(key = "plan-subtasks-pending") { PlanSubtasksPending() }
    }
  }
  planBundle(detail, plan?.units.orEmpty(), onAction)
  val transcriptCount = detail.planOutputLines.size
  planSection(
    "plan-transcript",
    "Transcript",
    count = "$transcriptCount lines",
    tools = {
      SbButton(if (screen.transcriptExpanded) "Raw" else "Structured", { screen.transcriptExpanded = !screen.transcriptExpanded }, variant = SbButtonVariant.Ghost, size = SbControlSize.Small)
    },
  ) {
    item(key = "plan-transcript-log") { PlanOutputLog(state, detail, onAction, raw = screen.transcriptExpanded) }
  }
  val briefings = plan?.stages.orEmpty().mapNotNull { stage -> stage.briefingText?.takeIf(String::isNotBlank)?.let { stage.phaseId to it } }
  if (briefings.isNotEmpty()) planSection("plan-briefing", "Briefing", "${briefings.size} stages", collapsedByDefault = true) {
    items(briefings, key = { "plan-briefing-${it.first}" }) { (id, text) ->
      ReasonBlock("$id briefing", text, onCopy = { onAction(DetailAction.Copy(it)) })
    }
  }
  pageFooter(DetailList.Executions, state, onAction)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlanBlockedCallout(
  ids: List<Int>,
  reasons: List<String>,
  view: RunActionsView,
  onRetry: () -> Unit,
  onOpenIncident: (() -> Unit)?,
) {
  val colors = SkillBillTheme.colors
  val label = ids.joinToString(", ")
  Column(
    Modifier.fillMaxWidth().padding(top = SkillBillSpace.s3)
      .border(1.dp, colors.stBlocked.copy(alpha = .40f), RoundedCornerShape(SkillBillRadius.md))
      .background(colors.stBlocked.copy(alpha = .06f), RoundedCornerShape(SkillBillRadius.md)).padding(12.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
      CompositionLocalProvider(LocalContentColor provides colors.stBlocked) { SbIcon(LucideIcons.warn, 14.dp) }
      BasicText("Subtask${if (ids.size == 1) "" else "s"} $label need${if (ids.size == 1) "s" else ""} your decision", style = SkillBillTheme.type.tSmMedium.copy(color = colors.stBlocked))
    }
    BasicText(
      if (ids.size == 1) buildBlockedReason("Planning stopped at subtask $label", reasons.firstOrNull())
      else buildBlockedReason("Planning stopped at subtasks $label", null),
      style = SkillBillTheme.type.tSm.copy(color = colors.fg2, lineHeight = 1.5.em),
    )
    if (ids.size > 1) reasons.forEach { BasicText(buildBlockedReason("", it), style = SkillBillTheme.type.tSm.copy(color = colors.fg2)) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      view.recover?.let { recover -> SbButton("Retry subtask${if (ids.size == 1) "" else "s"} $label", onRetry, variant = SbButtonVariant.Secondary, size = SbControlSize.Small, enabled = recover.enabled) }
      if (onOpenIncident != null) SbButton("Open incident", onOpenIncident, variant = SbButtonVariant.Ghost, size = SbControlSize.Small)
    }
  }
}

@Composable
private fun buildBlockedReason(prefix: String, reason: String?) = buildAnnotatedString {
  if (prefix.isNotBlank()) append("$prefix${if (reason.isNullOrBlank()) "." else ". "}")
  if (!reason.isNullOrBlank()) {
    val parts = Regex("`([^`]+)`").findAll(reason)
    var index = 0
    parts.forEach { match ->
      append(reason.substring(index, match.range.first))
      withStyle(SpanStyle(color = SkillBillTheme.colors.accentText, fontFamily = FontFamily.Monospace)) { append(match.groupValues[1]) }
      index = match.range.last + 1
    }
    append(reason.substring(index))
  }
}

private fun DetailSections.planBundle(detail: Detail, units: List<StandalonePlanUnit>, onAction: (DetailAction) -> Unit) {
  val files = detail.standalonePlan?.report?.bundleFiles.orEmpty()
  val saved = units.filter { it.state == "saved" }.map { it.specPath }
  val first = listOf("decomposition-manifest.yaml", "decomposition-manifest.yml", "spec.md")
    .mapNotNull { name -> files.firstOrNull { fileName(it) == name } }
  val ordered = (first + saved).distinct()
  val pending = if (units.isEmpty()) listOf("decomposition-manifest.yaml", "spec.md")
    .filter { name -> ordered.none { fileName(it) == name || name.endsWith(".yaml") && fileName(it) == name.removeSuffix(".yaml") + ".yml" } } else emptyList()
  planSection("plan-bundle", "Spec bundle", count = "${ordered.size} ${if (ordered.size == 1) "file" else "files"}") {
    item(key = "plan-bundle-chips") { PlanBundleChips(ordered, pending) { onAction(DetailAction.OpenSpecFile(it)) } }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlanBundleChips(paths: List<String>, pending: List<String>, onOpen: (String) -> Unit) {
  FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    paths.forEach { path -> EvidenceChip(path) { onOpen(path) } }
    pending.forEach { name ->
      Row(Modifier.border(1.dp, SkillBillTheme.colors.borderSubtle, RoundedCornerShape(4.dp)).padding(horizontal = 8.dp, vertical = 4.dp)) {
        BasicText(name, style = SkillBillTheme.type.monoSm.copy(color = SkillBillTheme.colors.fgFaint))
      }
    }
  }
}
