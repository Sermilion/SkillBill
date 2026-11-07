package dev.skillbill.desktop.feature.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.FeedbackTone
import dev.skillbill.desktop.core.designsystem.component.Callout
import dev.skillbill.desktop.core.designsystem.component.ExpandableText
import dev.skillbill.desktop.core.designsystem.component.ReasonBlock
import dev.skillbill.desktop.core.designsystem.component.SbTag
import dev.skillbill.desktop.core.designsystem.detail.DocBlockGap
import dev.skillbill.desktop.core.domain.model.Detail
import dev.skillbill.desktop.core.domain.model.DetailList
import dev.skillbill.desktop.core.domain.model.StandalonePlanUnit
import dev.skillbill.desktop.core.domain.model.WorkItem

internal fun DetailSections.planDocument(
  item: WorkItem,
  detail: Detail,
  state: DetailUiState,
  view: RunActionsView,
  onAction: (DetailAction) -> Unit,
) {
  val sectionCollapse = collapse
  val plan = detail.standalonePlan
  planStatus(item, detail)
  val blockedIds = plan?.blockedSubtaskIds.orEmpty().ifEmpty { plan?.blockedSubtaskId?.let(::listOf).orEmpty() }
  if (blockedIds.isNotEmpty()) {
    this.item(key = "plan-blocked") {
      // The mock's blocked `.callout`, drawn with the same Callout the goal incident uses.
      val title = "${if (blockedIds.size == 1) "Subtask" else "Subtasks"} ${blockedIds.joinToString(", ")} ${if (blockedIds.size == 1) "needs" else "need"} ${if (view.decision != null) "your decision" else "attention"}"
      val reasons = blockedIds.mapNotNull { id ->
        val reason = plan?.units?.firstOrNull { it.subtaskId == id }?.blockedReason
          ?: plan?.blockedReason?.takeIf { blockedIds.size == 1 }
        reason?.takeIf(String::isNotBlank)?.let { if (blockedIds.size == 1) it else "Subtask $id: $it" }
      }
      Callout(FeedbackTone.Warn, title, modifier = Modifier.padding(top = DocBlockGap).fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
          reasons.forEach { ExpandableText(it, SkillBillTheme.type.tSm.copy(color = SkillBillTheme.colors.fgMuted, lineHeight = 1.5.em)) }
        }
      }
    }
  }
  if (blockedIds.isNotEmpty() || detail.incident != null || view.decision != null) incident(state, view, onAction)
  item(key = "plan-current-activity") { PlanNowCard(item, detail) }
  if (plan?.units.isNullOrEmpty() && detail.planActivity.none { it.kind != "stream" }) planOutputTail(detail)
  item(key = "plan-live-activity") { PlanActivityPanel(item, detail, state, sectionCollapse, onAction) }
  if (plan != null && plan.units.isNotEmpty()) {
    planRows(item, plan, detail, onAction)
  } else {
    section("plan-subtasks", "Subtasks") {
      item(key = "plan-subtasks-pending") {
        PlanSubtasksPending()
      }
    }
  }
  planBundle(detail, plan?.units.orEmpty(), onAction)
  val briefings = plan?.stages.orEmpty().mapNotNull { stage -> stage.briefingText?.takeIf(String::isNotBlank)?.let { stage.phaseId to it } }
  if (briefings.isNotEmpty()) section("plan-briefing", "Briefing") {
    items(briefings, key = { "plan-briefing-${it.first}" }) { (id, text) -> ReasonBlock("$id briefing", text, onCopy = { onAction(DetailAction.Copy(it)) }) }
  }
  pageFooter(DetailList.Executions, state, onAction)
}

private fun DetailSections.planBundle(detail: Detail, units: List<StandalonePlanUnit>, onAction: (DetailAction) -> Unit) {
  val files = detail.standalonePlan?.report?.bundleFiles.orEmpty()
  val saved = units.filter { it.state == "saved" }.map { it.specPath }
  val first = listOf("decomposition-manifest.yaml", "decomposition-manifest.yml", "spec.md")
    .mapNotNull { name -> files.firstOrNull { fileName(it) == name } }
  val ordered = (first + saved).distinct()
  val pending = if (units.isEmpty()) listOf("decomposition-manifest.yaml", "spec.md")
    .filter { name -> ordered.none { fileName(it) == name || name.endsWith(".yaml") && fileName(it) == name.removeSuffix(".yaml") + ".yml" } } else emptyList()
  section("plan-bundle", "Spec bundle", count = "${ordered.size} ${if (ordered.size == 1) "file" else "files"}") {
    item(key = "plan-bundle-chips") {
      PlanBundleChips(ordered, pending) { onAction(DetailAction.OpenSpecFile(it)) }
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlanBundleChips(paths: List<String>, pending: List<String>, onOpen: (String) -> Unit) {
  FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    paths.forEach { path -> EvidenceChip(path) { onOpen(path) } }
    pending.forEach { name ->
      SbTag(name, outlined = true, mono = true)
    }
  }
}
