package skillbill.goalrunner.assessment

import skillbill.goalrunner.assessment.model.GoalAssessmentDisposition
import skillbill.goalrunner.assessment.model.GoalAssessmentExitReason
import skillbill.goalrunner.assessment.model.GoalAssessmentFinding
import skillbill.goalrunner.assessment.model.GoalAssessmentFindingKind
import skillbill.goalrunner.assessment.model.GoalAssessmentFindingState
import skillbill.goalrunner.assessment.model.GoalAssessmentRecord
import skillbill.goalrunner.assessment.model.GoalAssessmentRegressionResult
import skillbill.goalrunner.assessment.model.GoalAssessmentRegressionState
import skillbill.goalrunner.assessment.model.GoalAssessmentRound
import skillbill.goalrunner.assessment.model.GoalAssessmentRoundDecision
import skillbill.goalrunner.assessment.model.GoalAssessmentVerification
import skillbill.workflow.taskruntime.model.review.FeatureTaskRuntimeReviewSeverity
import kotlin.test.Test
import kotlin.test.assertEquals

class GoalAssessmentLoopRulesTest {
  @Test
  fun `a confirmed Major from the current round is fixed and the loop continues`() {
    val record = recordOf(round(1, finding()))

    assertEquals(GoalAssessmentRoundDecision(fix = true, exit = null), GoalAssessmentLoopRules.decide(record, 1))
  }

  @Test
  fun `a confirmed Major that survived two fix rounds exits as persistent without another fix`() {
    val record = recordOf(round(1), round(2), round(3, finding(originRound = 1, fixRoundsSurvived = 2)))

    assertEquals(
      GoalAssessmentRoundDecision(fix = false, exit = GoalAssessmentExitReason.PERSISTENT_FINDING),
      GoalAssessmentLoopRules.decide(record, 3),
    )
  }

  @Test
  fun `a confirmed Major in the cap round still gets its fix and then exits at the cap`() {
    val record = recordOf(round(1), round(2), round(3, finding(originRound = 3)))

    assertEquals(
      GoalAssessmentRoundDecision(fix = true, exit = GoalAssessmentExitReason.ROUND_CAP),
      GoalAssessmentLoopRules.decide(record, 3),
    )
  }

  @Test
  fun `no confirmed Major converges without a fix`() {
    val unverified = finding(verification = GoalAssessmentVerification.UNVERIFIED)
    val refuted = finding(ref = "GA-002", state = GoalAssessmentFindingState.FIXED)

    assertEquals(
      GoalAssessmentRoundDecision(fix = false, exit = GoalAssessmentExitReason.CONVERGED),
      GoalAssessmentLoopRules.decide(recordOf(round(1, unverified, refuted)), 1),
    )
  }

  @Test
  fun `a not restored regression alone gets one fix before the loop converges`() {
    val regression = GoalAssessmentRegressionResult("TR-001", GoalAssessmentRegressionState.NOT_RESTORED, "src/A.kt:3")

    assertEquals(
      GoalAssessmentRoundDecision(fix = true, exit = GoalAssessmentExitReason.CONVERGED),
      GoalAssessmentLoopRules.decide(recordOf(round(1, regressions = listOf(regression))), 1),
    )
  }

  private fun recordOf(vararg rounds: GoalAssessmentRound) =
    GoalAssessmentRecord(issueKey = "SKILL-442", rounds = rounds.toList())

  private fun round(
    number: Int,
    vararg findings: GoalAssessmentFinding,
    regressions: List<GoalAssessmentRegressionResult> = emptyList(),
  ) = GoalAssessmentRound(
    round = number,
    headSha = "a".repeat(40),
    scopeFiles = emptyList(),
    assessorRuns = emptyList(),
    findings = findings.toList(),
    scenarios = emptyList(),
    regressions = regressions,
    fix = null,
  )

  private fun finding(
    ref: String = "GA-001",
    originRound: Int = 1,
    fixRoundsSurvived: Int = 0,
    verification: GoalAssessmentVerification = GoalAssessmentVerification.CONFIRMED,
    state: GoalAssessmentFindingState = GoalAssessmentFindingState.OPEN,
  ) = GoalAssessmentFinding(
    findingRef = ref,
    kind = GoalAssessmentFindingKind.DEFECT,
    severity = FeatureTaskRuntimeReviewSeverity.MAJOR,
    path = "src/Feature.kt",
    line = 10,
    failureScenario = "The handler drops the first call.",
    guardedRef = null,
    subtaskId = 1,
    originRound = originRound,
    verification = verification,
    verificationEvidence = "",
    fixRoundsSurvived = fixRoundsSurvived,
    disposition = GoalAssessmentDisposition.FIX,
    state = state,
  )
}
