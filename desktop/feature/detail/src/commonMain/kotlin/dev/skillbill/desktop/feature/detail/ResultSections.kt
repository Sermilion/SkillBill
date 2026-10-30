package dev.skillbill.desktop.feature.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.FindingRow
import dev.skillbill.desktop.core.designsystem.detail.PropertyList
import dev.skillbill.desktop.core.designsystem.detail.PropertyRow
import dev.skillbill.desktop.core.designsystem.detail.SbBox
import dev.skillbill.desktop.core.designsystem.detail.section
import dev.skillbill.desktop.core.domain.model.Detail
import dev.skillbill.desktop.core.domain.model.GoalAssessmentFindingState
import dev.skillbill.desktop.core.domain.model.GoalAssessmentState
import dev.skillbill.desktop.core.domain.model.ReviewPass
import dev.skillbill.desktop.core.domain.model.WorkKind
import kotlin.time.Instant

@Composable
private fun SectionText(text: String, color: Color = SkillBillTheme.colors.fg2, modifier: Modifier = Modifier) {
  BasicText(text, modifier, style = SkillBillTheme.type.tSm.copy(color = color))
}

/** One `.sb-prop` value of the activity list: the text, then a faint "· 4m ago" when it has an age. */
@Composable
private fun RowScope.ActivityValue(text: String, ago: String?, mono: Boolean) {
  val colors = SkillBillTheme.colors
  val type = SkillBillTheme.type
  val main = if (mono) type.monoSm.copy(fontSize = type.tXs.fontSize, color = colors.fg2) else type.tSm.copy(color = colors.fg2)
  if (mono) {
    // Last edit: the mono span and the faint age are two `dd` children, 8 apart.
    BasicText(text, Modifier.weight(1f, fill = false), style = main, maxLines = 2, overflow = TextOverflow.Ellipsis)
    ago?.let { BasicText("· $it ago", style = type.tSm.copy(color = colors.fgFaint), maxLines = 1) }
  } else {
    // Agent activity: one span, the faint age inline after a space.
    val value = buildAnnotatedString {
      append(text)
      ago?.let { withStyle(SpanStyle(color = colors.fgFaint)) { append(" · $it ago") } }
    }
    BasicText(value, Modifier.weight(1f, fill = false), style = main, maxLines = 2, overflow = TextOverflow.Ellipsis)
  }
}

private class ActivityRow(val label: String, val text: String, val ago: String?, val mono: Boolean = false)

/** HANDOFF 9.2 item 12: the latest agent activity, edit and event in a property list, as `desktop-tray`'s detail words them. */
internal fun LazyListScope.activitySection(kind: WorkKind?, detail: Detail, now: Instant) {
  fun ago(at: Instant?) = at?.let { age(now - it) }
  val rows = buildList {
    detail.lastAgentActivityLabel?.let { add(ActivityRow("Agent activity", it, ago(detail.lastAgentActivityAt))) }
    detail.latestActivity.edit?.let { edit ->
      val paths = edit.paths.joinToString { "…/" + it.substringAfterLast('/') }
      add(ActivityRow("Last edit", "$paths +${edit.insertions ?: 0} −${edit.deletions ?: 0}", ago(edit.recordedAt), mono = true))
    }
    detail.latestActivity.event?.let { event ->
      add(ActivityRow("Event", listOfNotNull(event.workflowPhase, event.workerRole, event.summary).joinToString(" · "), null))
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
      PropertyList {
        rows.forEach { row -> PropertyRow(row.label) { ActivityValue(row.text, row.ago, row.mono) } }
      }
    }
  }
}

/** HANDOFF 9.2 item 13: one boxed row per subtask's validation. */
internal fun LazyListScope.validationSection(detail: Detail) {
  if (detail.validation.isEmpty()) return
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
 * HANDOFF 9.2 item 14: review passes with their findings, then the audit conclusion, per subtask, 16 apart. One subtask names
 * itself in the header count ("subtask 4"); several get a caption each.
 */
internal fun LazyListScope.phaseResultsSection(detail: Detail) {
  if (detail.phaseResults.isEmpty()) return
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
            audit.summary?.let { SectionText("· $it", colors.fgFaint) }
          }
        }
      }
    }
  }
}

/** HANDOFF 9.2 item 15: goal QA findings, the ones left for the operator first, with `desktop-tray`'s summary wording. */
internal fun LazyListScope.goalQaSection(detail: Detail) {
  val qa = detail.goalQa ?: return
  if (qa.findings.isEmpty()) return
  // The design lists the findings in the runtime's order, each as ref, title and state with no meta line.
  val ordered = qa.findings
  val notFixed = ordered.count { !it.fixed }
  section("goal-qa", "Goal QA", count = qa.outcome) {
    item(key = "goal-qa-summary") {
      // `.t-muted` at `--t-sm`, then the box 8 below.
      SectionText(qaFindingsSummary(qa.findings.size, notFixed, qa.fixAttempts), SkillBillTheme.colors.fgMuted, Modifier.padding(bottom = 8.dp))
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

private fun GoalAssessmentState.word(): String = when (this) {
  GoalAssessmentState.Completed -> "Completed"
  GoalAssessmentState.Running -> "Running"
  GoalAssessmentState.Pending -> "Pending"
  GoalAssessmentState.Skipped -> "Skipped"
}

/** HANDOFF 9.2 item 16: the state and round in the header count, the findings in one box. */
internal fun LazyListScope.goalAssessmentSection(detail: Detail) {
  val assessment = detail.goalAssessment ?: return
  val count = assessment.findings.size
  section("goal-assessment", "Goal assessment", count = "${assessment.state.word()} · round ${assessment.round}") {
    itemsIndexed(assessment.findings, key = { _, finding -> "assessment-${finding.ref}" }) { index, finding ->
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
