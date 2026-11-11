package dev.skillbill.desktop.feature.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.composite.Edge
import dev.skillbill.desktop.core.designsystem.composite.hairline
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
  val allowed = verifySelectionAllowed(run)
  verifySection(VERIFY_CRITERIA_KEY, "Criteria", view.allCount.toString(), end = if (view.showFilter) ({
    VerifyRouteSwitch(VerifyCriteriaFilter.entries.map { filter -> filter to when (filter) {
      VerifyCriteriaFilter.All -> "All"
      VerifyCriteriaFilter.Gaps -> "Gaps ${view.gapCount}"
      VerifyCriteriaFilter.Met -> "Met ${view.metCount}"
    } }, view.filter, large = true) { onAction(DetailAction.SetVerifyCriteriaFilter(it)) }
  }) else null) {
    if (!view.showFilter && view.allCount > 0) item(key = "verify-criteria-sub") {
      val text = when (verifyRunState(run)) {
        VerifyRunState.Parked -> "Extracted from the intake. The audit grades the code against this list once you confirm."
        VerifyRunState.Superseded -> "Extracted but never confirmed. The newer run extracted its own list."
        else -> if (run.completenessAudit != null) "Audit results are incomplete. See Completeness audit in Steps." else "Extracted and confirmed. Grades appear after the completeness audit."
      }
      BasicText(text, Modifier.padding(bottom = 12.dp), style = SkillBillTheme.type.tSm.copy(color = SkillBillTheme.colors.fgMuted))
    }
    val selectAll = allowed && selectAllState(items.filter { it.kind == FixKind.Criterion }, state.fixSelection) != null
    val count = view.rows.size.coerceAtLeast(1) + if (selectAll) 1 else 0
    if (selectAll) item(key = "verify-select-all-criteria") {
      Column(Modifier.fillMaxWidth().sbBoxSegment(0, count, borderColor = SkillBillTheme.colors.border).padding(horizontal = 12.dp, vertical = 6.dp)) { VerifyFixSelectAll(items, FixKind.Criterion, state, allowed, onAction) }
    }
    view.rows.forEachIndexed { index, row -> item(key = "verify-criterion-${row.number}") {
      val fix = items.filterIsInstance<FixItem.Criterion>().firstOrNull { it.grade.number == row.number }
      val key = "verify-criterion:${view.workflowId}:${row.number}"
      VerifyCriterionRow(row, Modifier.sbBoxSegment(index + if (selectAll) 1 else 0, count, borderColor = SkillBillTheme.colors.border),
        open = !sectionCollapse.isCollapsed(key, default = true), onToggle = { sectionCollapse.toggle(key, default = true) },
        pendingLabel = if (verifyRunState(run) == VerifyRunState.Parked) "To confirm" else if (run.completenessAudit != null) "No grade" else "Not audited",
        mark = state.criterionMarks[row.number], selectable = allowed && view.showFilter,
        leading = { if (allowed && fix != null) VerifyFixCheckbox(fix, state, onAction) },
        end = { if (allowed && fix != null) VerifyFixRoute(fix, state, onAction) },
        extra = { if (allowed && fix != null) VerifyFixItemDetails(fix, items, onAction) })
    } }
    if (view.rows.isEmpty() && view.raw == null) item(key = "verify-criteria-empty") {
      BasicText("Nothing in this filter.", Modifier.sbBoxSegment(if (selectAll) 1 else 0, count).padding(12.dp), style = SkillBillTheme.type.tSm.copy(color = SkillBillTheme.colors.fgMuted))
    }
    view.raw?.let { raw -> item(key = "verify-criteria-raw") { VerifyQuote(raw) } }
    view.nonGoals.takeIf { it.isNotEmpty() }?.let { verifyContextSection(sectionCollapse, "$VERIFY_NON_GOALS_KEY:${view.workflowId}", VerifyContextKind.NonGoals, it) }
    view.technicalConstraints.takeIf { it.isNotEmpty() }?.let { verifyContextSection(sectionCollapse, "$VERIFY_TECHNICAL_CONSTRAINTS_KEY:${view.workflowId}", VerifyContextKind.Constraints, it) }
    view.rolloutExpectation?.let { verifyContextSection(sectionCollapse, "$VERIFY_ROLLOUT_EXPECTATION_KEY:${view.workflowId}", VerifyContextKind.Rollout, listOf(it)) }
  }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun VerifyCriterionRow(
  row: VerifyCriteriaViewRow, modifier: Modifier = Modifier, open: Boolean = false, onToggle: () -> Unit = {},
  pendingLabel: String = "Not audited", mark: CriterionMark? = null, selectable: Boolean = false,
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
  Column(modifier.fillMaxWidth().then(if (row.number > 1) Modifier.hairline(Edge.Top, colors.borderSubtle) else Modifier)) {
    Row {
      if (selectable) Column(Modifier.width(30.dp).padding(start = 12.dp, top = 11.dp)) { leading() }
      Row(Modifier.weight(1f).clickable(role = Role.Button, onClickLabel = "Toggle criterion ${row.number}", onClick = onToggle).semantics { stateDescription = if (open) "Expanded" else "Collapsed" }.padding(start = if (selectable) 2.dp else 12.dp, end = 12.dp, top = 9.dp, bottom = 9.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        BasicText(row.number.toString(), Modifier.width(22.dp).padding(top = 2.dp), style = SkillBillTheme.type.monoSm.copy(fontSize = 11.5.sp, fontWeight = FontWeight.Medium, color = colors.fgFaint))
        BasicText(verifyText(row.text), Modifier.weight(1f), style = SkillBillTheme.type.tBase.copy(color = colors.fg, lineHeight = 1.45.em), maxLines = if (open) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis)
        Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
          VerifyBadge(when (mark) { CriterionMark.NotInRepo -> "Not in this repo"; CriterionMark.SpecQuestion -> "Spec question"; null -> grade?.status ?: pendingLabel }, gradeColor)
          VerifyChevron(open)
        }
      }
      if (selectable) Column(Modifier.width(104.dp).padding(top = 8.dp, end = 10.dp), horizontalAlignment = Alignment.End) { end() }
    }
    if (open) Column(Modifier.padding(start = if (selectable) 62.dp else 44.dp, end = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      if (grade == null) BasicText(when (pendingLabel) { "To confirm" -> "The audit runs after you confirm."; "No grade" -> "No grade was recorded for this criterion. See Completeness audit in Steps."; else -> "The completeness audit has not run yet." }, style = SkillBillTheme.type.tSm.copy(color = colors.fgMuted))
      grade?.evidence?.takeIf { it.isNotEmpty() }?.let { evidence ->
        BasicText("Evidence", style = SkillBillTheme.type.tXs.copy(color = colors.fgFaint))
        val (prose, locations) = evidence.partition { it.any(Char::isWhitespace) }
        if (locations.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { locations.forEach { VerifyFileChip(it) } }
        prose.forEach { VerifyReportText(it) }
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
    VerifyReportText(text)
  }
}
