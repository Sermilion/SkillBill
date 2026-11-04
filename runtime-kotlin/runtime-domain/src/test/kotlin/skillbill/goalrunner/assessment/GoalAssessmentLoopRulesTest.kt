package skillbill.goalrunner.assessment

import skillbill.goalrunner.assessment.model.GoalAssessmentDisposition
import skillbill.goalrunner.assessment.model.GoalAssessmentExitReason
import skillbill.goalrunner.assessment.model.GoalAssessmentFinding
import skillbill.goalrunner.assessment.model.GoalAssessmentFindingKind
import skillbill.goalrunner.assessment.model.GoalAssessmentFindingState
import skillbill.goalrunner.assessment.model.GoalAssessmentFix
import skillbill.goalrunner.assessment.model.GoalAssessmentFixStatus
import skillbill.goalrunner.assessment.model.GoalAssessmentFixValidation
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
  fun `a confirmed open test value finding remains repairable and blocks publication`() {
    val testValue =
      finding("GA-002").copy(
        kind = GoalAssessmentFindingKind.TEST_VALUE,
        severity = FeatureTaskRuntimeReviewSeverity.MINOR,
        path = "src/HandlerTest.kt",
        guardedRef = "src/Handler.kt:10",
      )
    val record = recordOf(round(1, testValue)).copy(exitReason = GoalAssessmentExitReason.CONVERGED)

    assertEquals(FocusedRepairAction.Plan("GA-002"), GoalAssessmentLoopRules.next(record))
    assertTrue(GoalAssessmentLoopRules.publicationBlockReason(record).orEmpty().contains("GA-002"))
  }

  @Test
  fun `an unrestored regression cannot be accepted without independent verification`() {
    val regression = GoalAssessmentRegressionResult("TR-001", GoalAssessmentRegressionState.NOT_RESTORED, "src/A.kt:3")
    val record = recordOf(round(1, regressions = listOf(regression)))

    assertEquals(FocusedRepairAction.Plan("TR-001"), GoalAssessmentLoopRules.next(record))
    assertNotNull(GoalAssessmentLoopRules.publicationBlockReason(record))
  }

  @Test
  fun `an unavailable exit names the uncommitted repair it is waiting on`() {
    val fix =
      GoalAssessmentFix(
        GoalAssessmentFixStatus.COMPLETED,
        listOf("src/FeatureTest.kt"),
        null,
        GoalAssessmentFixValidation.NOT_RUN,
      )
    val record =
      recordOf(round(1, finding()).copy(targetRef = "GA-001", fix = fix))
        .copy(exitReason = GoalAssessmentExitReason.ASSESSMENT_UNAVAILABLE)

    assertEquals(
      "Goal assessment did not accept the goal: assessment_unavailable. The round 1 repair of GA-001 changed " +
        "src/FeatureTest.kt and is not validated or committed yet. It resumes only on the feature branch at " +
        "assessed head aaaaaaaaaaaa with only those files changed.",
      GoalAssessmentLoopRules.publicationBlockReason(record),
    )
  }

  @Test
  fun `an unavailable exit names the repair commit that must stay at head`() {
    val fix =
      GoalAssessmentFix(
        GoalAssessmentFixStatus.COMPLETED,
        listOf("src/Feature.kt"),
        "b".repeat(40),
        GoalAssessmentFixValidation.PASSED,
      )
    val record =
      recordOf(round(1, finding()).copy(fix = fix)).copy(exitReason = GoalAssessmentExitReason.ASSESSMENT_UNAVAILABLE)

    assertEquals(
      "Goal assessment did not accept the goal: assessment_unavailable. The round 1 repair was committed as " +
        "bbbbbbbbbbbb. It resumes only on the feature branch with that commit as head.",
      GoalAssessmentLoopRules.publicationBlockReason(record),
    )
  }

  @Test
  fun `other exits keep the bare reason even with a pending repair`() {
    val fix =
      GoalAssessmentFix(
        GoalAssessmentFixStatus.COMPLETED,
        listOf("src/Feature.kt"),
        null,
        GoalAssessmentFixValidation.NOT_RUN,
      )
    val record =
      recordOf(round(1, finding()).copy(fix = fix)).copy(exitReason = GoalAssessmentExitReason.ROUND_CAP)

    assertEquals(
      "Goal assessment did not accept the goal: round_cap.",
      GoalAssessmentLoopRules.publicationBlockReason(record),
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
