package dev.skillbill.desktop.feature.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillRadius
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.detail.DocBlockGap
import dev.skillbill.desktop.core.domain.model.QaAssertionOutcome
import dev.skillbill.desktop.core.domain.model.VerifyRunDetails

internal data class VerifyScoreSegment(val count: Int, val tone: VerifyScoreTone)
internal enum class VerifyScoreTone { Green, Amber, Red, Faint }
internal data class VerifyScoreCell(val title: String, val value: String, val segments: List<VerifyScoreSegment>, val targetKey: String)
internal data class VerifyOutcomeView(val title: String, val verdict: String?, val message: String, val tone: VerifyScoreTone, val qaLowered: Boolean, val cells: List<VerifyScoreCell>)

internal fun verifyOutcomeView(run: VerifyRunDetails): VerifyOutcomeView {
  val state = verifyRunState(run)
  val steps = auditedSteps(run)
  val index = when (state) {
    VerifyRunState.Stopped, VerifyRunState.Superseded -> stoppedStepIndex(run)
    else -> steps.indexOfFirst { it.status == "running" }.coerceAtLeast(0)
  }
  val step = steps.getOrNull(index)
  val grades = run.completenessAudit?.grades.orEmpty()
  val findings = run.verdict?.findings.orEmpty()
  val warning = findings.any { it.startsWith("Warning:") }
  val message = when (state) {
    VerifyRunState.Pending -> "Waiting to start"
    VerifyRunState.Running -> "Step ${index + 1} of ${steps.size} · ${step?.let { verifyStepName(it.stepId) }.orEmpty()}"
    VerifyRunState.Parked -> if (run.criteria.isEmpty()) "No criteria to audit" else "Confirm the criteria"
    VerifyRunState.Superseded, VerifyRunState.Stopped -> "No verdict · stopped at step ${index + 1} of ${steps.size} · ${step?.let { verifyStepName(it.stepId) }.orEmpty()}" + if (run.staleReason == "no_terminal_before_threshold") " · Run was marked stale" else ""
    VerifyRunState.Finished -> findings.filterNot { it.startsWith("Warning:") }.joinToString("\n").removePrefix("**VERDICT** —").trimStart()
  }
  fun hasRun(stepId: String): Boolean = run.steps.firstOrNull { it.stepId == stepId }?.status in setOf("completed", "skipped")
  fun cell(title: String, value: String, segments: List<VerifyScoreSegment>, stepId: String, key: String = verifyStepKey(stepId)) =
    VerifyScoreCell(title, value, if (hasRun(stepId)) segments else emptyList(), key)
  val criterionGrades = run.criteria.mapNotNull { criterion -> grades.firstOrNull { it.number == criterion.number } }
  val ungradedCriteria = (run.criteria.size - criterionGrades.size).coerceAtLeast(0)
  val criteriaSegments = listOf(
    VerifyScoreSegment(criterionGrades.count { it.status.equals("PASS", true) }, VerifyScoreTone.Green),
    VerifyScoreSegment(criterionGrades.count { it.status.equals("PARTIAL", true) }, VerifyScoreTone.Amber),
    VerifyScoreSegment(criterionGrades.count { it.status.equals("FAIL", true) }, VerifyScoreTone.Red),
    VerifyScoreSegment(ungradedCriteria, VerifyScoreTone.Faint),
  ).filter { it.count > 0 }
  val review = run.reviewFindings
  val reviewFindingCount = review?.findings?.size ?: 0
  val knownReviewSeverityCount = review?.items?.count {
    it.severity.equals("nit", true) || it.severity.equals("minor", true) ||
      it.severity.equals("major", true) || it.severity.equals("blocker", true)
  } ?: 0
  val reviewSegments = when {
    review == null -> emptyList()
    reviewFindingCount == 0 -> listOf(VerifyScoreSegment(1, VerifyScoreTone.Green))
    else -> listOf(
      VerifyScoreSegment(review.items.count { it.severity.equals("nit", true) }, VerifyScoreTone.Faint),
      VerifyScoreSegment(review.items.count { it.severity.equals("minor", true) }, VerifyScoreTone.Amber),
      VerifyScoreSegment(review.items.count { it.severity.equals("major", true) || it.severity.equals("blocker", true) }, VerifyScoreTone.Red),
      VerifyScoreSegment((reviewFindingCount - knownReviewSeverityCount).coerceAtLeast(0), VerifyScoreTone.Faint),
    ).filter { it.count > 0 }
  }
  val tests = run.unitTestValue
  val testSegments = listOf(
    VerifyScoreSegment(tests?.rows?.count { it.verdict.equals("Valuable", true) } ?: 0, VerifyScoreTone.Green),
    VerifyScoreSegment(tests?.rows?.count { it.verdict.equals("Weak", true) } ?: 0, VerifyScoreTone.Amber),
    VerifyScoreSegment(tests?.rows?.count { it.verdict.equals("Harmful", true) } ?: 0, VerifyScoreTone.Red),
  ).filter { it.count > 0 }
  val qaSelected = run.qaSelection == "on"
  val qaSegments = listOf(
    VerifyScoreSegment(run.qa?.assertions?.count { it.outcome == QaAssertionOutcome.Passed } ?: 0, VerifyScoreTone.Green),
    VerifyScoreSegment(run.qa?.assertions?.count { it.outcome == QaAssertionOutcome.NotExercised } ?: 0, VerifyScoreTone.Amber),
    VerifyScoreSegment(run.qa?.assertions?.count { it.outcome == QaAssertionOutcome.Failed } ?: 0, VerifyScoreTone.Red),
  ).filter { it.count > 0 }
  val flagSegments = listOf(
    VerifyScoreSegment(run.flagAudit?.checks?.count { it.passed } ?: 0, VerifyScoreTone.Green),
    VerifyScoreSegment(run.flagAudit?.checks?.count { !it.passed } ?: 0, VerifyScoreTone.Red),
  ).filter { it.count > 0 }
  val criteriaValue =
    if (grades.isEmpty()) "${run.criteria.size} extracted"
    else "${criterionGrades.count { it.status.equals("PASS", true) }} / ${run.criteria.size} met"
  val testsValue = when {
    tests == null -> "unavailable"
    tests.verdict.equals("skipped", true) -> "skipped"
    !tests.overall.isNullOrBlank() -> tests.overall.orEmpty()
    else -> "unavailable"
  }
  val flag = run.flagAudit
  val qa = run.qa
  val flagValue = when {
    flag == null -> "unavailable"
    flag.verdict.equals("skipped", true) -> "no flag required"
    !flag.flagName.isNullOrBlank() -> flag.flagName.orEmpty()
    else -> "unavailable"
  }
  val qaValue = when {
    qa == null && run.qaVerdict?.equals("skipped", true) == true -> "skipped"
    qa == null && run.qaVerdict?.equals("passed", true) == true -> "unavailable"
    qa == null -> run.qaVerdict ?: "unavailable"
    qa.findings.isNotEmpty() -> "${qa.findings.size} open"
    run.qaVerdict.equals("passed", true) -> "passed"
    else -> run.qaVerdict ?: "unavailable"
  }
  val cells = if (state == VerifyRunState.Parked || state == VerifyRunState.Superseded) emptyList() else listOf(
    cell("Criteria", criteriaValue, if (grades.isEmpty()) emptyList() else criteriaSegments, "completeness_audit", VERIFY_CRITERIA_KEY),
    cell("Review", review?.let { reviewFindingCount.toString() } ?: "—", reviewSegments, "code_review"),
    cell("Tests", testsValue, testSegments, "unit_test_value_check"),
    if (qaSelected) cell("QA", qaValue, qaSegments, "qa", VERIFY_QA_KEY)
    else cell("Flag audit", flagValue, flagSegments, "feature_flag_audit"),
  )
  val verdict = run.verdict?.verdict.orEmpty()
  val tone = when (verdict.uppercase()) {
    "APPROVE" -> VerifyScoreTone.Green
    "APPROVE WITH FIXES" -> VerifyScoreTone.Amber
    "REQUEST CHANGES" -> VerifyScoreTone.Red
    else -> VerifyScoreTone.Faint
  }
  return VerifyOutcomeView(if (state == VerifyRunState.Finished) "Verdict" else "", verdict.takeIf { state == VerifyRunState.Finished && it.isNotBlank() }, message, tone, state == VerifyRunState.Finished && warning, cells)
}

@Composable
internal fun VerifyOutcomeCard(view: VerifyOutcomeView, onCellClick: (String) -> Unit) {
  val colors = SkillBillTheme.colors
  Column(Modifier.fillMaxWidth().padding(top = DocBlockGap), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    if (view.title.isNotEmpty()) androidx.compose.foundation.text.BasicText(view.title, style = SkillBillTheme.type.tMd.copy(color = colors.fg, fontWeight = FontWeight.SemiBold))
    view.verdict?.let { androidx.compose.foundation.text.BasicText(it, style = SkillBillTheme.type.tMd.copy(color = toneColor(view.tone, colors.stDone, colors.stBlocked, colors.stFailed, colors.fgFaint), fontWeight = FontWeight.SemiBold)) }
    androidx.compose.foundation.text.BasicText(view.message, style = SkillBillTheme.type.tSm.copy(color = toneColor(view.tone, colors.stDone, colors.stBlocked, colors.stFailed, colors.fgFaint)))
    if (view.qaLowered) androidx.compose.foundation.text.BasicText("QA findings lowered the verdict", style = SkillBillTheme.type.tSm.copy(color = colors.stBlocked))
    if (view.cells.isNotEmpty()) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      view.cells.forEach { cell ->
        Column(Modifier.weight(1f).clickable(role = Role.Button, onClickLabel = "Go to ${cell.title}", onClick = { onCellClick(cell.targetKey) }).semantics { contentDescription = "${cell.title}: ${cell.value}. Go to ${cell.title}" }.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
          androidx.compose.foundation.text.BasicText(cell.title, style = SkillBillTheme.type.tXs.copy(color = colors.fgMuted))
          androidx.compose.foundation.text.BasicText(cell.value, style = SkillBillTheme.type.tSm.copy(color = colors.fg, fontWeight = FontWeight.Medium), maxLines = 2)
          ScoreBar(cell.segments)
        }
      }
    }
  }
}

@Composable
private fun ScoreBar(segments: List<VerifyScoreSegment>) {
  val colors = SkillBillTheme.colors
  val shape = RoundedCornerShape(SkillBillRadius.xs)
  Row(Modifier.fillMaxWidth().height(6.dp).clip(shape).background(colors.borderSubtle).semantics { contentDescription = if (segments.isEmpty()) "No score recorded" else "Score breakdown" }) {
    val total = segments.sumOf { it.count }
    if (total > 0) segments.forEach { segment ->
      if (segment.count > 0) Box(Modifier.weight(segment.count.toFloat()).height(6.dp).background(when (segment.tone) {
        VerifyScoreTone.Green -> colors.stDone
        VerifyScoreTone.Amber -> colors.stBlocked
        VerifyScoreTone.Red -> colors.stFailed
        VerifyScoreTone.Faint -> colors.fgFaint
      }))
    }
  }
}

private fun toneColor(tone: VerifyScoreTone, green: Color, amber: Color, red: Color, faint: Color) = when (tone) {
  VerifyScoreTone.Green -> green
  VerifyScoreTone.Amber -> amber
  VerifyScoreTone.Red -> red
  VerifyScoreTone.Faint -> faint
}
