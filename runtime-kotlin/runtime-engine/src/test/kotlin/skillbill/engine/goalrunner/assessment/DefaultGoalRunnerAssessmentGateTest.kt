package skillbill.engine.goalrunner.assessment

import org.junit.jupiter.api.io.TempDir
import skillbill.engine.goalrunner.persist.assessment.GoalAssessmentRecordRead
import skillbill.goalrunner.assessment.model.GoalAssessmentDisposition
import skillbill.goalrunner.assessment.model.GoalAssessmentFinding
import skillbill.goalrunner.assessment.model.GoalAssessmentFindingKind
import skillbill.goalrunner.assessment.model.GoalAssessmentFindingState
import skillbill.goalrunner.assessment.model.GoalAssessmentRecord
import skillbill.goalrunner.assessment.model.GoalAssessmentRole
import skillbill.goalrunner.assessment.model.GoalAssessmentRound
import skillbill.goalrunner.assessment.model.GoalAssessmentRun
import skillbill.goalrunner.assessment.model.GoalAssessmentRunStatus
import skillbill.goalrunner.assessment.model.GoalAssessmentVerification
import skillbill.workflow.taskruntime.model.review.FeatureTaskRuntimeReviewSeverity
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DefaultGoalRunnerAssessmentGateTest {
  @TempDir lateinit var home: Path

  @Test
  fun `a refuted Major is recorded as refuted, stays pr only and grants no fix authority`() {
    val fixture =
      GoalAssessmentGateFixture(home) { call ->
        when (call) {
          AssessmentCall.FIRST_SUBTASK -> MAJOR_FINDING
          AssessmentCall.VERIFIER -> "Verify GA-001: refuted evidence src/Feature.kt:10 already handles the first call"
          else -> NO_FINDING
        }
      }

    val result = fixture.assess()

    val finding = result.record.rounds.single().findings.single()
    assertEquals(GoalAssessmentVerification.REFUTED, finding.verification)
    assertEquals(GoalAssessmentFindingState.REFUTED, finding.state)
    assertEquals(GoalAssessmentDisposition.PR_ONLY, finding.disposition)
    assertEquals(emptyList(), result.record.openFindingRefs)
    assertTrue(result.fixSet.isEmpty)
    assertEquals(result.record, (fixture.store.stored as GoalAssessmentRecordRead.Recorded).record)
  }

  @Test
  fun `a failed verifier is contained, recorded as failed and leaves the Major without fix authority`() {
    val fixture =
      GoalAssessmentGateFixture(home) { call ->
        when (call) {
          AssessmentCall.FIRST_SUBTASK -> MAJOR_FINDING
          AssessmentCall.VERIFIER -> null
          else -> NO_FINDING
        }
      }

    val result =
      assertIs<GoalAssessmentRoundResult.Unavailable>(fixture.gate().runRound(fixture.state(), fixture.request(), 1))

    val round = result.record.rounds.single()
    val verifierRun = round.assessorRuns.single { it.role == GoalAssessmentRole.VERIFIER }
    assertEquals(GoalAssessmentRunStatus.FAILED, verifierRun.status)
    assertEquals(GoalAssessmentVerification.UNVERIFIED, round.findings.single().verification)
    assertEquals(listOf("GA-001"), result.record.openFindingRefs)
    assertTrue(fixture.diagnostics.warnings.any { "the verifier" in it }, fixture.diagnostics.warnings.toString())
  }

  @Test
  fun `a re-entered round skips the recorded assessor run and adds no duplicate finding`() {
    val recorded = recordedFirstSubtaskRound()
    val store = AssessmentOutcomeStore(GoalAssessmentRecordRead.Recorded(recorded))
    val fixture =
      GoalAssessmentGateFixture(home, store) { call ->
        if (call == AssessmentCall.VERIFIER) "Verify GA-001: confirmed evidence src/Feature.kt:4" else NO_FINDING
      }

    val result = fixture.assess()

    assertEquals(
      listOf(AssessmentCall.SECOND_SUBTASK, AssessmentCall.CROSS_SUBTASK, AssessmentCall.VERIFIER),
      fixture.calls,
    )
    val round = result.record.rounds.single()
    assertEquals(recorded.rounds.single().findings.single().findingRef, round.findings.single().findingRef)
    val expectedRuns =
      listOf(GoalAssessmentRole.SUBTASK to 1, GoalAssessmentRole.SUBTASK to 2, GoalAssessmentRole.CROSS_SUBTASK to null)
    assertEquals(
      expectedRuns + (GoalAssessmentRole.VERIFIER to null),
      round.assessorRuns.map { it.role to it.subtaskId },
    )
  }

  private fun GoalAssessmentGateFixture.assess(): GoalAssessmentRoundResult.Assessed =
    assertIs<GoalAssessmentRoundResult.Assessed>(
      gate().runRound(state(), request(), 1),
      diagnostics.warnings.toString(),
    )

  private fun recordedFirstSubtaskRound(): GoalAssessmentRecord =
    GoalAssessmentRecord(
      issueKey = "SKILL-442",
      rounds =
        listOf(
          GoalAssessmentRound(
            round = 1,
            headSha = ASSESSMENT_HEAD_SHA,
            scopeFiles = emptyList(),
            assessorRuns = listOf(GoalAssessmentRun(GoalAssessmentRole.SUBTASK, 1, GoalAssessmentRunStatus.COMPLETED)),
            findings = listOf(recordedMinorFinding()),
            scenarios = emptyList(),
            regressions = emptyList(),
            fix = null,
          ),
        ),
      openFindingRefs = listOf("GA-001"),
    )

  private fun recordedMinorFinding() =
    GoalAssessmentFinding(
      findingRef = "GA-001",
      kind = GoalAssessmentFindingKind.DEFECT,
      severity = FeatureTaskRuntimeReviewSeverity.MINOR,
      path = "src/Feature.kt",
      line = 4,
      failureScenario = "The log line names the wrong subtask.",
      guardedRef = null,
      subtaskId = 1,
      originRound = 1,
      verification = GoalAssessmentVerification.UNVERIFIED,
      verificationEvidence = "",
      fixRoundsSurvived = 0,
      disposition = GoalAssessmentDisposition.PR_ONLY,
      state = GoalAssessmentFindingState.OPEN,
    )

  private companion object {
    const val NO_FINDING = "Finding: none"
    const val MAJOR_FINDING = "Finding: Major src/Feature.kt:10 scenario The handler drops the first call"
  }
}
