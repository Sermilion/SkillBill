package dev.skillbill.desktop.feature.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.SbLabel
import dev.skillbill.desktop.core.designsystem.component.SbLabelStyle
import dev.skillbill.desktop.core.designsystem.component.Segmented
import dev.skillbill.desktop.core.designsystem.component.SegmentedOption
import dev.skillbill.desktop.core.designsystem.component.rememberRuntimeText
import dev.skillbill.desktop.core.designsystem.composite.Edge
import dev.skillbill.desktop.core.designsystem.composite.hairline
import dev.skillbill.desktop.core.designsystem.detail.Section
import dev.skillbill.desktop.core.designsystem.detail.SectionCollapse
import dev.skillbill.desktop.core.designsystem.detail.sbBoxSegment
import dev.skillbill.desktop.core.domain.model.VerifyCriterionGrade
import dev.skillbill.desktop.core.domain.model.VerifyRunDetails
import dev.skillbill.desktop.core.domain.settings.CriterionMark

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
  if (view.allCount == 0 && view.raw == null && view.nonGoals.isEmpty() && view.technicalConstraints.isEmpty() && view.rolloutExpectation == null) return
  val sectionCollapse = collapse
  verifySection(VERIFY_CRITERIA_KEY, "Criteria", view.allCount.toString()) {
    val allowed = verifySelectionAllowed(run)
    if (view.showFilter) item(key = "verify-filter") {
      Segmented(VerifyCriteriaFilter.entries.map { filter -> SegmentedOption(filter, when (filter) {
        VerifyCriteriaFilter.All -> "All"
        VerifyCriteriaFilter.Gaps -> "Gaps ${view.gapCount}"
        VerifyCriteriaFilter.Met -> "Met ${view.metCount}"
      }) }, selected = view.filter, onSelect = { onAction(DetailAction.SetVerifyCriteriaFilter(it)) })
    }
    if (!view.showFilter && view.allCount > 0) item(key = "verify-criteria-sub") {
      val text = when (verifyRunState(run)) {
        VerifyRunState.Parked -> "Extracted from the intake. The audit grades the code against this list once you confirm."
        VerifyRunState.Superseded -> "Extracted but never confirmed. The newer run extracted its own list."
        else -> "Extracted and confirmed. Grades appear after the completeness audit."
      }
      BasicText(text, Modifier.padding(bottom = 12.dp), style = SkillBillTheme.type.tSm.copy(color = SkillBillTheme.colors.fgMuted))
    }
    val selectAll = allowed && selectAllState(items.filter { it.kind == FixKind.Criterion }, state.fixSelection) != null
    val count = view.rows.size.coerceAtLeast(1) + if (selectAll) 1 else 0
    if (selectAll) item(key = "verify-select-all-criteria") {
      Column(Modifier.fillMaxWidth().sbBoxSegment(0, count).padding(12.dp)) { VerifyFixSelectAll(items, FixKind.Criterion, state, allowed, onAction) }
    }
    view.rows.forEachIndexed { index, row -> item(key = "verify-criterion-${row.number}") {
      val fix = items.filterIsInstance<FixItem.Criterion>().firstOrNull { it.grade.number == row.number }
      val key = "verify-criterion:${view.workflowId}:${row.number}"
      VerifyCriterionRow(row, Modifier.sbBoxSegment(index + if (selectAll) 1 else 0, count),
        open = !sectionCollapse.isCollapsed(key, default = true), onToggle = { sectionCollapse.toggle(key, default = true) },
        pendingLabel = if (verifyRunState(run) == VerifyRunState.Parked) "To confirm" else "Not audited",
        mark = state.criterionMarks[row.number],
        leading = { if (allowed && fix != null) VerifyFixCheckbox(fix, state, onAction) },
        end = { if (allowed && fix != null) VerifyFixRoute(fix, state, onAction) },
        extra = { if (allowed && fix != null) VerifyFixItemDetails(fix, items, onAction) })
    } }
    if (view.rows.isEmpty() && view.raw == null) item(key = "verify-criteria-empty") {
      BasicText("Nothing in this filter.", Modifier.sbBoxSegment(if (selectAll) 1 else 0, count).padding(12.dp), style = SkillBillTheme.type.tSm.copy(color = SkillBillTheme.colors.fgMuted))
    }
    view.raw?.let { raw -> item(key = "verify-criteria-raw") { VerifyQuote(raw) } }
    view.nonGoals.takeIf { it.isNotEmpty() }?.let { contextFold(sectionCollapse, "$VERIFY_NON_GOALS_KEY:${view.workflowId}", "Non-goals", it.joinToString("\n")) }
    view.technicalConstraints.takeIf { it.isNotEmpty() }?.let { contextFold(sectionCollapse, "$VERIFY_TECHNICAL_CONSTRAINTS_KEY:${view.workflowId}", "Technical constraints", it.joinToString("\n")) }
    view.rolloutExpectation?.let { contextFold(sectionCollapse, "$VERIFY_ROLLOUT_EXPECTATION_KEY:${view.workflowId}", "Rollout", it) }
  }
}

private fun LazyListScope.contextFold(collapse: SectionCollapse, key: String, title: String, content: String) {
  item(key = key) {
    Section(title, collapsed = collapse.isCollapsed(key, default = true), onToggle = { collapse.toggle(key, default = true) }) {
      BasicText(rememberRuntimeText(content), Modifier.padding(start = 8.dp), style = SkillBillTheme.type.tSm.copy(color = SkillBillTheme.colors.fg2))
    }
  }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun VerifyCriterionRow(
  row: VerifyCriteriaViewRow, modifier: Modifier = Modifier, open: Boolean = false, onToggle: () -> Unit = {},
  pendingLabel: String = "Not audited", mark: CriterionMark? = null,
  leading: @Composable () -> Unit = {}, end: @Composable () -> Unit = {}, extra: @Composable () -> Unit = {},
) {
  val colors = SkillBillTheme.colors
  val grade = row.grade
  val gradeColor = if (mark != null) colors.fgFaint else when (grade?.status) {
    "PASS" -> colors.stDone
    "PARTIAL" -> colors.stBlocked
    "FAIL" -> colors.stFailed
    else -> colors.fgFaint
  }
  Column(modifier.fillMaxWidth().hairline(Edge.Top, colors.borderSubtle)) {
    Row(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      leading()
      Row(Modifier.weight(1f).clickable(role = Role.Button, onClickLabel = "Toggle criterion ${row.number}", onClick = onToggle).semantics { stateDescription = if (open) "Expanded" else "Collapsed" }, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        BasicText(row.number.toString(), style = SkillBillTheme.type.monoSm.copy(color = colors.fgFaint))
        BasicText(rememberRuntimeText(row.text), Modifier.weight(1f), style = SkillBillTheme.type.tBase.copy(color = colors.fg), maxLines = if (open) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis)
        Column(horizontalAlignment = Alignment.End) {
          SbLabel(when (mark) { CriterionMark.NotInRepo -> "Not in this repo"; CriterionMark.SpecQuestion -> "Spec question"; null -> grade?.status ?: pendingLabel }, SbLabelStyle.Tint(gradeColor))
          BasicText(if (open) "⌄" else "›", style = SkillBillTheme.type.tSm.copy(color = colors.fgFaint))
        }
      }
      end()
    }
    if (open) Column(Modifier.padding(start = 44.dp, end = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      if (grade == null) BasicText(if (pendingLabel == "To confirm") "The audit runs after you confirm." else "The completeness audit has not run yet.", style = SkillBillTheme.type.tSm.copy(color = colors.fgMuted))
      grade?.evidence?.takeIf { it.isNotEmpty() }?.let { evidence ->
        BasicText("Evidence", style = SkillBillTheme.type.tXs.copy(color = colors.fgFaint))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { evidence.forEach { SbLabel(it) } }
      }
      grade?.missing?.let { VerifyCriterionField("What's missing", it) }
      grade?.notFound?.let { VerifyCriterionField("Not found", it) }
      extra()
    }
  }
}

@Composable
private fun VerifyCriterionField(label: String, text: String) {
  Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
    BasicText(label, style = SkillBillTheme.type.tXs.copy(color = SkillBillTheme.colors.fgFaint))
    BasicText(rememberRuntimeText(text), style = SkillBillTheme.type.tSm.copy(color = SkillBillTheme.colors.fg2))
  }
}
