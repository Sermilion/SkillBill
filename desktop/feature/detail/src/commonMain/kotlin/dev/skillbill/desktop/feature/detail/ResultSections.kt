package dev.skillbill.desktop.feature.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.ExpandableText
import dev.skillbill.desktop.core.domain.model.Detail
import dev.skillbill.desktop.core.domain.model.GoalAssessmentState
import dev.skillbill.desktop.core.domain.model.ReviewPass
import dev.skillbill.desktop.core.domain.model.WorkItem
import dev.skillbill.desktop.core.domain.model.WorkKind

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

/** A review pass: the `t-sm` header line (500 title, muted verdict, faint status tally), its lanes, then the review findings table, 8 apart. */
@Composable
private fun ReviewPassBlock(pass: ReviewPass) {
  val colors = SkillBillTheme.colors
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      BasicText("Review pass ${pass.passNumber}", style = SkillBillTheme.type.tSmMedium.copy(color = colors.fg))
      SectionText(reviewVerdictLabel(pass.verdict), colors.fgMuted)
      SectionText("· ${reviewFindingsTally(pass.findings)}", colors.fgFaint)
    }
    if (pass.lanes.isNotEmpty()) ReviewLanes(pass.lanes, pass.findings)
    if (pass.findings.isNotEmpty()) ReviewFindingsBox(pass.findings)
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

internal fun GoalAssessmentState.word(): String = when (this) {
  GoalAssessmentState.Completed -> "Completed"
  GoalAssessmentState.Blocked -> "Blocked"
  GoalAssessmentState.Running -> "Running"
  GoalAssessmentState.Pending -> "Pending"
  GoalAssessmentState.Skipped -> "Skipped"
}

