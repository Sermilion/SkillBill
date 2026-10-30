package dev.skillbill.desktop.feature.detail

import dev.skillbill.desktop.core.domain.model.GoalAssessmentFinding
import dev.skillbill.desktop.core.domain.model.GoalAssessmentFindingState
import dev.skillbill.desktop.core.domain.model.GoalAssessmentFindingState.Fixed
import dev.skillbill.desktop.core.domain.model.GoalAssessmentFindingState.Open
import dev.skillbill.desktop.core.domain.model.GoalAssessmentFindingState.Refuted
import dev.skillbill.desktop.core.domain.model.GoalAssessmentVerification
import kotlin.test.Test
import kotlin.test.assertEquals

class AssessmentOrderTest {
  private val findings = listOf(
    finding("F1", "minor", Open),
    finding("F2", "Blocker", Fixed),
    finding("F3", "major", Refuted),
    finding("F4", "blocker", Open),
    finding("F5", "unusual", Open),
  )

  @Test
  fun `priority puts the most severe first, open before fixed among equals, unknown severities last`() {
    assertEquals(listOf("F4", "F2", "F3", "F1", "F5"), orderedFindings(findings, AssessmentOrder.Priority).map { it.ref })
  }

  @Test
  fun `status puts open findings first, most severe first among them, fixed last`() {
    assertEquals(listOf("F4", "F1", "F5", "F3", "F2"), orderedFindings(findings, AssessmentOrder.Status).map { it.ref })
  }

  @Test
  fun `default keeps the runtime's order`() {
    assertEquals(findings, orderedFindings(findings, AssessmentOrder.Default))
  }

  private fun finding(ref: String, severity: String, state: GoalAssessmentFindingState) =
    GoalAssessmentFinding(ref, severity, "src/A.kt", 1, "scenario $ref", GoalAssessmentVerification.Confirmed, state, "")
}
