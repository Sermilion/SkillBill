package dev.skillbill.desktop.feature.detail

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.Segmented
import dev.skillbill.desktop.core.designsystem.component.SegmentedOption
import dev.skillbill.desktop.core.designsystem.component.ShortId
import dev.skillbill.desktop.core.designsystem.detail.PeekFacts
import dev.skillbill.desktop.core.designsystem.detail.PeekPart
import dev.skillbill.desktop.core.domain.model.GoalAssessmentFindingState
import dev.skillbill.desktop.core.domain.model.GoalAssessmentResult
import dev.skillbill.desktop.core.domain.model.PhaseCard

@Composable
internal fun GoalAssessmentCard(card: PhaseCard, assessment: GoalAssessmentResult?, order: MutableState<AssessmentOrder>, onAction: (DetailAction) -> Unit) {
  RecordedPhasePeek(card,
    title = "Goal assessment", phaseId = card.phaseId, state = card.state.toPeekState(),
    word = assessment?.let { "${it.state.word()} · round ${it.round}" },
    why = card.blockedReason,
    endControl = if (assessment?.unavailableReason != null || assessment?.findings.orEmpty().size < 2) null else { {
      Segmented(AssessmentOrder.entries.map { SegmentedOption(it, it.label) }, order.value) { order.value = it }
    } },
  ) {
    if (assessment == null || assessment.unavailableReason != null) {
      PeekPart("Findings") { BasicText("No assessment result is available for this run.", style = SkillBillTheme.type.tSm.copy(color = SkillBillTheme.colors.fgMuted)) }
    } else {
      val findings = orderedFindings(assessment.findings, order.value)
      PeekPart("Findings") {
        findings.forEachIndexed { index, finding ->
          BoxedFindingRow(
            index, findings.size, title = finding.failureScenario, severity = finding.severity, ref = finding.ref,
            location = "${finding.path}:${finding.line}", meta = goalAssessmentVerificationLabel(finding.verification),
            state = when (finding.state) {
              GoalAssessmentFindingState.Fixed -> "Fixed"
              GoalAssessmentFindingState.Refuted -> "Refuted"
              GoalAssessmentFindingState.Open -> "Open"
            },
            stateColor = if (finding.state == GoalAssessmentFindingState.Fixed) SkillBillTheme.colors.stDone else SkillBillTheme.colors.fgFaint,
            evidence = finding.evidence.takeIf(String::isNotBlank)?.let { text -> { ReportText(text) } },
          )
        }
      }
      if (assessment.exitReason != null || assessment.fixCommitSha != null) PeekPart("Result") {
        PeekFacts(listOf("Exit" to assessment.exitReason))
        assessment.fixCommitSha?.let { sha ->
          PeekPart("Fix commit") { ShortId(sha, { onAction(DetailAction.Copy(it)) }, label = "fix commit") }
        }
      }
    }
  }
}
