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
    assertFalse(text.contains("qa_criteria"))
  }

  @Test
  fun `goal status prints no QA lines for an incomplete run that carries only report text`() {
    val qa =
      GoalRunnerQaResult(
        outcome = "incomplete",
        reason = "The login flow crashed on launch.",
        qaFixAttempts = 0,
        recordedAt = Instant.parse("2026-10-20T16:14:00Z"),
        reportText = "Verdict: incomplete\n\nThe login flow crashed on launch.",
      )

    val text = goalStatusText("SKILL-500", projection(qa))

    assertFalse(text.contains("qa_findings"), text)
    assertFalse(text.contains("qa_criteria"), text)
  }

  @Test
  fun `goal status names the QA failure kind and reason and stays silent for outcomes without either`() {
    val recordedAt = Instant.parse("2026-10-24T09:00:00Z")
    val defect =
      GoalRunnerQaResult("incomplete", "QA could not complete:\nboom", 0, recordedAt, failureKind = "defect")
    val environment = defect.copy(reason = "device lease failed", failureKind = "environment")
    val clean = GoalRunnerQaResult("passed", null, 0, recordedAt)

    assertTrue(
      goalStatusText("SKILL-500", projection(defect))
        .contains("qa_outcome: incomplete (runtime defect) reason=QA could not complete: boom\n"),
    )
    assertTrue(
      goalStatusText("SKILL-500", projection(environment))
        .contains("qa_outcome: incomplete (environment) reason=device lease failed\n"),
    )
    assertFalse(goalStatusText("SKILL-500", projection(clean)).contains("qa_outcome"))
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
