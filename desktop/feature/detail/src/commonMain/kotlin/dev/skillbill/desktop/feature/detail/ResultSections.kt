package dev.skillbill.desktop.feature.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.ExpandableText
import dev.skillbill.desktop.core.designsystem.component.FindingRow
import dev.skillbill.desktop.core.designsystem.detail.SbBox
import dev.skillbill.desktop.core.domain.model.Detail
import dev.skillbill.desktop.core.domain.model.GoalAssessmentFindingState
import dev.skillbill.desktop.core.domain.model.GoalAssessmentState
import dev.skillbill.desktop.core.domain.model.ReviewPass
import dev.skillbill.desktop.core.domain.model.WorkItem
import dev.skillbill.desktop.core.domain.model.WorkKind
import dev.skillbill.desktop.core.designsystem.component.Segmented
import dev.skillbill.desktop.core.designsystem.component.SegmentedOption

@Composable
private fun SectionText(text: String, color: Color = SkillBillTheme.colors.fg2, modifier: Modifier = Modifier) {
  BasicText(text, modifier, style = SkillBillTheme.type.tSm.copy(color = color))
}

/** HANDOFF 9.2 item 15: a standalone run's validation, one boxed row per subtask; goals show it in the subtask inspector. */
internal fun DetailSections.validationSection(item: WorkItem, detail: Detail) {
  if (item.kind == WorkKind.Goal || detail.validation.isEmpty()) return
  val count = detail.validation.size
  section("validation", "Validation") {
    itemsIndexed(detail.validation, key = { _, record -> "validation-${record.subtaskId}" }) { index, record ->
      val colors = SkillBillTheme.colors
      val word = when (record.passed) { true -> "passed"; false -> "failed"; null -> "unknown" }
      BoxedFindingRow(
        index, count,
        title = record.integrityProblem ?: "$word · ${record.result?.checks?.size ?: 0} checks",
        ref = "Subtask ${record.subtaskId}",
        state = word.takeIf { record.passed != null },
        stateColor = when (record.passed) { true -> colors.stDone; false -> colors.stFailed; null -> null },
      )
    }
  }
}

/** A review pass: the `t-sm` header line (500 title, muted verdict, faint unresolved count), then its findings in a box, 8 apart. */
@Composable
private fun ReviewPassBlock(pass: ReviewPass) {
  val colors = SkillBillTheme.colors
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      BasicText("Review pass ${pass.passNumber}", style = SkillBillTheme.type.tSmMedium.copy(color = colors.fg))
      SectionText(pass.verdict, colors.fgMuted)
      SectionText("· ${pass.unresolvedFindingCount} unresolved", colors.fgFaint)
    }
    if (pass.findings.isNotEmpty()) {
      SbBox {
        pass.findings.forEachIndexed { index, finding ->
          FindingRow(title = finding.text, severity = finding.severity, location = finding.label, showDivider = index > 0)
        }
      }
    }
  }
}

/**
 * HANDOFF 9.2 item 15, standalone runs only: review passes with their findings, then the audit conclusion, per subtask, 16 apart. One subtask names
 * itself in the header count ("subtask 4"); several get a caption each.
 */
internal fun DetailSections.phaseResultsSection(item: WorkItem, detail: Detail) {
  if (item.kind == WorkKind.Goal || detail.phaseResults.isEmpty()) return
  val single = detail.phaseResults.singleOrNull()
  section("phase-results", "Phase results", count = single?.let { "subtask ${it.subtaskId}" }) {
    detail.phaseResults.forEachIndexed { resultIndex, results ->
      if (single == null) {
        item(key = "phase-results-${results.subtaskId}") {
          BasicText(
            "subtask ${results.subtaskId}",
            Modifier.padding(top = if (resultIndex > 0) 16.dp else 0.dp, bottom = 8.dp),
            style = SkillBillTheme.type.tSmMedium.copy(color = SkillBillTheme.colors.fgMuted),
          )
        }
      }
      results.reviewPasses.forEachIndexed { passIndex, pass ->
        item(key = "review-${results.subtaskId}-${pass.passNumber}") {
          Column(Modifier.padding(top = if (passIndex > 0) 16.dp else 0.dp)) { ReviewPassBlock(pass) }
        }
      }
      results.audit?.let { audit ->
        item(key = "audit-${results.subtaskId}") {
          val colors = SkillBillTheme.colors
          Row(Modifier.padding(top = if (results.reviewPasses.isNotEmpty()) 16.dp else 0.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BasicText("Audit", style = SkillBillTheme.type.tSmMedium.copy(color = colors.fg))
            audit.verdict?.let { SectionText(it, colors.fgMuted) }
            audit.summary?.let { ExpandableText("· $it", SkillBillTheme.type.tSm.copy(color = colors.fgFaint), Modifier.weight(1f, fill = false)) }
          }
        }
      }
    }
  }
}

/** HANDOFF 9.2 item 15: goal QA findings, the ones left for the operator first, with `desktop-tray`'s summary wording. */
internal fun DetailSections.goalQaSection(detail: Detail) {
  val qa = detail.goalQa ?: return
  // The design lists the findings in the runtime's order, each as ref, title and state with no meta line.
  val ordered = qa.findings
  val notFixed = ordered.count { !it.fixed }
  section(GOAL_QA_KEY, "Goal QA", count = qa.outcome) {
    item(key = "goal-qa-summary") {
      Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(bottom = 12.dp)) {
        if (qa.findings.isNotEmpty()) SectionText(qaFindingsSummary(qa.findings.size, notFixed, qa.fixAttempts), SkillBillTheme.colors.fgMuted)
        if (!qa.reportText.isNullOrBlank() || !qa.reason.isNullOrBlank() || qa.failureKind != null) QaReportCard(qa)
      }
    }
    itemsIndexed(ordered, key = { _, finding -> "goal-qa-${finding.id}" }) { index, finding ->
      val colors = SkillBillTheme.colors
      BoxedFindingRow(
        index, ordered.size,
        title = finding.title.ifBlank { finding.id },
        ref = finding.id,
        state = if (finding.fixed) "Fixed" else "Not fixed",
        stateColor = if (finding.fixed) colors.stDone else colors.stFailed,
      )
    }
  }
}

internal fun GoalAssessmentState.word(): String = when (this) {
  GoalAssessmentState.Completed -> "Completed"
  GoalAssessmentState.Blocked -> "Blocked"
  GoalAssessmentState.Running -> "Running"
  GoalAssessmentState.Pending -> "Pending"
  GoalAssessmentState.Skipped -> "Skipped"
}

/** HANDOFF 9.2 item 16: the state and round in the header count, the findings in one box. */
@OptIn(ExperimentalLayoutApi::class)
internal fun DetailSections.goalAssessmentSection(detail: Detail) {
  val assessment = detail.goalAssessment ?: return
  val count = assessment.findings.size
  val order = assessmentOrder
  val findings = orderedFindings(assessment.findings, order.value)
  section(GOAL_ASSESSMENT_KEY, "Goal assessment") {
    item(key = "assessment-order") {
      FlowRow(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(bottom = 12.dp),
      ) {
        SectionText("${assessment.state.word()} · round ${assessment.round}", SkillBillTheme.colors.fgFaint)
        if (count > 1) {
          Segmented(AssessmentOrder.entries.map { SegmentedOption(it, it.label) }, order.value) { order.value = it }
        }
      }
    }
    itemsIndexed(findings, key = { _, finding -> "assessment-${finding.ref}" }) { index, finding ->
      val colors = SkillBillTheme.colors
      BoxedFindingRow(
        index, count,
        title = finding.failureScenario,
        severity = finding.severity,
        ref = finding.ref,
        location = "${finding.path}:${finding.line}",
        meta = goalAssessmentVerificationLabel(finding.verification),
        state = when (finding.state) {
          GoalAssessmentFindingState.Fixed -> "Fixed"
          GoalAssessmentFindingState.Refuted -> "Refuted"
          GoalAssessmentFindingState.Open -> "Open"
        },
        stateColor = if (finding.state == GoalAssessmentFindingState.Fixed) colors.stDone else colors.fgFaint,
      )
    }
  }
}
