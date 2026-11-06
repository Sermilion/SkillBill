package dev.skillbill.desktop.feature.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.SbButton
import dev.skillbill.desktop.core.designsystem.component.SbButtonVariant
import dev.skillbill.desktop.core.designsystem.component.SbLabel
import dev.skillbill.desktop.core.designsystem.component.SbLabelStyle
import dev.skillbill.desktop.core.designsystem.detail.DocBlockGap
import dev.skillbill.desktop.core.designsystem.detail.Section
import dev.skillbill.desktop.core.designsystem.detail.SectionCollapse
import dev.skillbill.desktop.core.domain.model.VerifyCriterionGrade
import dev.skillbill.desktop.core.domain.model.VerifyRunDetails

internal data class VerifyCriteriaViewRow(val number: Int, val text: String, val grade: VerifyCriterionGrade?)
internal data class VerifyCriteriaView(val workflowId: String, val rows: List<VerifyCriteriaViewRow>, val filter: VerifyCriteriaFilter, val allCount: Int, val gapCount: Int, val metCount: Int, val showFilter: Boolean, val raw: String?, val nonGoals: List<String>, val technicalConstraints: List<String>, val rolloutExpectation: String?)

internal fun verifyCriteriaView(run: VerifyRunDetails, filter: VerifyCriteriaFilter): VerifyCriteriaView {
  val grades = run.completenessAudit?.grades.orEmpty()
  val rows = run.criteria.map { criterion -> VerifyCriteriaViewRow(criterion.number, criterion.text, grades.firstOrNull { it.number == criterion.number }) }
  val visible = when (filter) {
    VerifyCriteriaFilter.All -> rows
    VerifyCriteriaFilter.Gaps -> rows.filter { it.grade?.status in setOf("PARTIAL", "FAIL") }
    VerifyCriteriaFilter.Met -> rows.filter { it.grade?.status == "PASS" }
  }
  val raw = run.criteriaRaw.takeIf { run.criteria.isEmpty() }
  return VerifyCriteriaView(run.workflowId, visible, filter, rows.size, rows.count { it.grade?.status in setOf("PARTIAL", "FAIL") }, rows.count { it.grade?.status == "PASS" }, grades.isNotEmpty() && raw == null, raw, run.nonGoals, run.technicalConstraints, run.rolloutExpectation)
}

internal fun DetailSections.verifyCriteriaSection(view: VerifyCriteriaView, run: VerifyRunDetails, state: DetailUiState, items: List<FixItem>, onAction: (DetailAction) -> Unit) {
  val sectionCollapse = collapse
  section(VERIFY_CRITERIA_KEY, "Criteria", spaceBefore = DocBlockGap) {
    val allowed = verifySelectionAllowed(run)
    item(key = "verify-select-all-criteria") { VerifyFixSelectAll(items, FixKind.Criterion, state, allowed, onAction) }
    if (view.showFilter) item(key = "verify-filter") {
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        filterButton("All", view.allCount, VerifyCriteriaFilter.All, view.filter, onAction)
        filterButton("Gaps", view.gapCount, VerifyCriteriaFilter.Gaps, view.filter, onAction)
        filterButton("Met", view.metCount, VerifyCriteriaFilter.Met, view.filter, onAction)
      }
    }
    view.rows.forEach { row -> item(key = "verify-criterion-${row.number}") {
      val fix = items.filterIsInstance<FixItem.Criterion>().firstOrNull { it.grade.number == row.number }
      VerifyCriterionRow(row, leading = { fix?.let { VerifyFixItemControls(it, items, state, allowed, onAction) } })
    } }
    view.raw?.let { raw -> item(key = "verify-criteria-raw") { BasicText(raw) } }
    view.nonGoals.takeIf { it.isNotEmpty() }?.let { contextFold(sectionCollapse, "$VERIFY_NON_GOALS_KEY:${view.workflowId}", "Non-goals", it.joinToString("\n")) }
    view.technicalConstraints.takeIf { it.isNotEmpty() }?.let { contextFold(sectionCollapse, "$VERIFY_TECHNICAL_CONSTRAINTS_KEY:${view.workflowId}", "Technical constraints", it.joinToString("\n")) }
    view.rolloutExpectation?.let { contextFold(sectionCollapse, "$VERIFY_ROLLOUT_EXPECTATION_KEY:${view.workflowId}", "Rollout expectation", it) }
  }
}

private fun LazyListScope.contextFold(collapse: SectionCollapse, key: String, title: String, content: String) {
  item(key = key) {
    val collapsed = collapse.isCollapsed(key, default = true)
    Section(title, collapsed = collapsed, onToggle = { collapse.toggle(key, default = true) }) {
      BasicText(content, Modifier.padding(start = 8.dp))
    }
  }
}

@Composable
private fun filterButton(label: String, count: Int, filter: VerifyCriteriaFilter, selected: VerifyCriteriaFilter, onAction: (DetailAction) -> Unit) {
  SbButton("$label $count", { onAction(DetailAction.SetVerifyCriteriaFilter(filter)) }, variant = if (filter == selected) SbButtonVariant.Primary else SbButtonVariant.Secondary, size = dev.skillbill.desktop.core.designsystem.component.SbControlSize.Small)
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun VerifyCriterionRow(row: VerifyCriteriaViewRow, modifier: Modifier = Modifier, leading: @Composable () -> Unit = {}) {
  val colors = SkillBillTheme.colors
  val grade = row.grade
  val gradeColor = when (grade?.status) {
    "PASS" -> colors.stDone
    "PARTIAL" -> colors.stBlocked
    "FAIL" -> colors.stFailed
    else -> colors.fgFaint
  }
  Column(modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      leading()
      BasicText("#${row.number}", style = SkillBillTheme.type.tSmMedium.copy(color = colors.fgMuted))
      BasicText(row.text, Modifier.weight(1f), style = SkillBillTheme.type.tSm.copy(color = colors.fg))
      grade?.let { SbLabel(it.status, SbLabelStyle.Tint(gradeColor)) }
    }
    grade?.evidence?.takeIf { it.isNotEmpty() }?.let { evidence ->
      FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        evidence.forEach { SbLabel(it) }
      }
    }
    grade?.missing?.let { BasicText("What's missing\n$it", style = SkillBillTheme.type.tSm.copy(color = colors.fg2)) }
    grade?.notFound?.let { BasicText("Not found\n$it", style = SkillBillTheme.type.tSm.copy(color = colors.fg2)) }
  }
}
