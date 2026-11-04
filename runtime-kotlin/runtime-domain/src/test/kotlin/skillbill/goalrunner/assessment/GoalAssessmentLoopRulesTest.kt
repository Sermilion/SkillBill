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
import skillbill.goalrunner.assessment.model.GoalAssessmentVerification
import skillbill.workflow.model.repair.FocusedRepairAction
import skillbill.workflow.taskruntime.model.review.FeatureTaskRuntimeReviewSeverity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GoalAssessmentLoopRulesTest {
  @Test
  fun `open source defects prevent publication even when the exit claims convergence`() {
    val record = recordOf(round(1, finding())).copy(exitReason = GoalAssessmentExitReason.CONVERGED)

    assertEquals(FocusedRepairAction.Plan("GA-001"), GoalAssessmentLoopRules.next(record))
    assertTrue(GoalAssessmentLoopRules.publicationBlockReason(record).orEmpty().contains("GA-001"))
  }

  @Test
  fun `an unrestored regression cannot be accepted without independent verification`() {
    val regression = GoalAssessmentRegressionResult("TR-001", GoalAssessmentRegressionState.NOT_RESTORED, "src/A.kt:3")
    val record = recordOf(round(1, regressions = listOf(regression)))

    assertEquals(FocusedRepairAction.Plan("TR-001"), GoalAssessmentLoopRules.next(record))
    assertNotNull(GoalAssessmentLoopRules.publicationBlockReason(record))
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
