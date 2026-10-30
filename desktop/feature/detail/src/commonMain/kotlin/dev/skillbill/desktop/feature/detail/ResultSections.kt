package dev.skillbill.desktop.feature.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.FindingRow
import dev.skillbill.desktop.core.designsystem.detail.PropertyList
import dev.skillbill.desktop.core.designsystem.detail.PropertyRow
import dev.skillbill.desktop.core.designsystem.detail.SbBox
import dev.skillbill.desktop.core.designsystem.detail.section
import dev.skillbill.desktop.core.domain.model.Detail
import dev.skillbill.desktop.core.domain.model.DetailList
import dev.skillbill.desktop.core.domain.model.GoalAssessmentFindingState
import dev.skillbill.desktop.core.domain.model.GoalAssessmentState
import dev.skillbill.desktop.core.domain.model.WorkKind
import kotlin.time.Instant

@Composable
private fun SectionText(text: String, color: Color = SkillBillTheme.colors.fg2) {
  BasicText(text, style = SkillBillTheme.type.tSm.copy(color = color))
}

/** HANDOFF 9.2 item 12: the latest agent activity, edit and event, as `desktop-tray`'s detail words them. */
internal fun LazyListScope.activitySection(kind: WorkKind?, detail: Detail, now: Instant) {
  fun ago(at: Instant?) = at?.let { " · ${age(now - it)} ago" }.orEmpty()
  val rows = buildList {
    detail.lastAgentActivityLabel?.let { add("Agent activity" to it + ago(detail.lastAgentActivityAt)) }
    detail.latestActivity.edit?.let { edit ->
      val paths = edit.paths.joinToString { "…/" + it.substringAfterLast('/') }
      add("Last edit" to "$paths +${edit.insertions ?: 0} −${edit.deletions ?: 0}${ago(edit.recordedAt)}")
    }
    detail.latestActivity.event?.let { event ->
      add("Event" to listOfNotNull(event.workflowPhase, event.workerRole, event.summary).joinToString(" · "))
    }
  }
  if (rows.isEmpty()) return
  val title = when (kind) {
    WorkKind.Goal -> "Goal activity"
    WorkKind.Operation -> "Operation activity"
    WorkKind.Phase, null -> "Phase activity"
  }
  section("activity", title) {
    item(key = "activity-rows") {
      PropertyList { rows.forEach { (label, value) -> PropertyRow(label) { SectionText(value) } } }
    }
  }
}

/** HANDOFF 9.2 item 13: one row per subtask's validation. */
internal fun LazyListScope.validationSection(detail: Detail, state: DetailUiState, onAction: (DetailAction) -> Unit) {
  if (detail.validation.isEmpty()) return
  section("validation", "Validation", count = detail.validation.size) {
    items(detail.validation, key = { "validation-${it.subtaskId}" }) { record ->
      val colors = SkillBillTheme.colors
      val word = when (record.passed) { true -> "passed"; false -> "failed"; null -> "unknown" }
      FindingRow(
        title = record.integrityProblem ?: "$word · ${record.result?.checks?.size ?: 0} checks",
        ref = "Subtask ${record.subtaskId}",
        state = word.takeIf { record.passed != null },
        stateColor = when (record.passed) { true -> colors.stDone; false -> colors.stFailed; null -> null },
      )
    }
    pageFooter(DetailList.Validation, state, onAction)
  }
}

/** HANDOFF 9.2 item 14: review passes with their findings, then the audit conclusion, per subtask. */
internal fun LazyListScope.phaseResultsSection(detail: Detail, state: DetailUiState, onAction: (DetailAction) -> Unit) {
  if (detail.phaseResults.isEmpty()) return
  section("phase-results", "Phase results") {
    detail.phaseResults.forEach { results ->
      item(key = "phase-results-${results.subtaskId}") {
        BasicText(
          "subtask ${results.subtaskId}",
          Modifier.padding(top = 6.dp, bottom = 2.dp),
          style = SkillBillTheme.type.tSmMedium.copy(color = SkillBillTheme.colors.fgMuted),
        )
      }
      results.reviewPasses.forEach { pass ->
        item(key = "review-${results.subtaskId}-${pass.passNumber}") {
          val colors = SkillBillTheme.colors
          SbBox {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
              SectionText("Review pass ${pass.passNumber}", colors.fg)
              SectionText(pass.verdict, colors.fgMuted)
              SectionText("· ${pass.unresolvedFindingCount} unresolved", colors.fgFaint)
            }
            pass.findings.forEachIndexed { index, finding ->
              FindingRow(title = finding.text, severity = finding.severity, location = finding.label, showDivider = index < pass.findings.lastIndex)
            }
          }
        }
      }
      results.audit?.let { audit ->
        item(key = "audit-${results.subtaskId}") {
          val colors = SkillBillTheme.colors
          Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SectionText("Audit", colors.fg)
            audit.verdict?.let { SectionText(it, colors.fgMuted) }
            audit.summary?.let { SectionText(it) }
          }
        }
      }
    }
    pageFooter(DetailList.Validation, state, onAction)
  }
}

/** HANDOFF 9.2 item 15: goal QA findings, the ones left for the operator first, with `desktop-tray`'s summary wording. */
internal fun LazyListScope.goalQaSection(detail: Detail) {
  val qa = detail.goalQa ?: return
  if (qa.findings.isEmpty()) return
  val (fixed, notFixed) = qa.findings.partition { it.fixed }
  section("goal-qa", "Goal QA", count = qa.findings.size) {
    item(key = "goal-qa-summary") {
      val colors = SkillBillTheme.colors
      SectionText(qaFindingsSummary(qa.findings.size, notFixed.size, qa.fixAttempts), if (notFixed.isEmpty()) colors.stDone else colors.stBlocked)
    }
    items(notFixed + fixed, key = { "goal-qa-${it.id}" }) { finding ->
      val colors = SkillBillTheme.colors
      FindingRow(
        title = finding.title.ifBlank { finding.id },
        ref = finding.id,
        location = finding.evidencePath,
        state = if (finding.fixed) "Fixed" else "Not fixed",
        stateColor = if (finding.fixed) colors.stDone else colors.stFailed,
      )
    }
  }
}

/** HANDOFF 9.2 item 16. */
internal fun LazyListScope.goalAssessmentSection(detail: Detail) {
  val assessment = detail.goalAssessment ?: return
  section("goal-assessment", "Goal assessment", count = assessment.findings.size.takeIf { it > 0 }) {
    item(key = "goal-assessment-state") {
      val round = assessment.round
      SectionText(if (assessment.state == GoalAssessmentState.Completed) "Completed · round $round" else "Running · round $round")
    }
    items(assessment.findings, key = { "assessment-${it.ref}" }) { finding ->
      val colors = SkillBillTheme.colors
      FindingRow(
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
        stateColor = when (finding.state) {
          GoalAssessmentFindingState.Fixed -> colors.stDone
          GoalAssessmentFindingState.Refuted -> colors.fgFaint
          GoalAssessmentFindingState.Open -> colors.stBlocked
        },
      )
    }
  }
}
