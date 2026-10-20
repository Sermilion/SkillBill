package skillbill.cli.goal.status

import skillbill.goalrunner.model.GoalRunnerQaFinding
import skillbill.goalrunner.model.GoalRunnerQaResult
import skillbill.goalrunner.model.GoalRunnerStatusProjection
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GoalCliStatusQaFindingsTest {
  @Test
  fun `goal status counts fixed and not fixed QA findings and names the ones left open`() {
    val qa =
      GoalRunnerQaResult(
        outcome = "findings",
        reason = null,
        qaFixAttempts = 3,
        recordedAt = Instant.parse("2026-10-20T10:15:30Z"),
        findings =
          listOf(
            GoalRunnerQaFinding("F-1", "Login button does nothing.", "/qa/F-1.png", fixed = true),
            GoalRunnerQaFinding("F-2", "Home screen stays blank.", "/qa/F-2.png", fixed = false),
          ),
      )

    val text = goalStatusText("SKILL-500", projection(qa))
    val clean = goalStatusText("SKILL-500", projection(qa.copy(findings = emptyList())))

    assertTrue(text.contains("qa_findings: fixed=1 not_fixed=1 not_fixed_ids=F-2\n"), text)
    assertFalse(clean.contains("qa_findings"))
  }

  private fun projection(qa: GoalRunnerQaResult): GoalRunnerStatusProjection =
    GoalRunnerStatusProjection(
      issueKey = "SKILL-500",
      completeCount = 1,
      pendingCount = 0,
      blockedCount = 0,
      currentSubtaskId = null,
      currentStep = null,
      activeAgent = null,
      goalQaResult = qa,
    )
}
