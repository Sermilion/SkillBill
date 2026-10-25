package skillbill.engine.goalrunner.status

import skillbill.engine.goalrunner.InMemoryGoalManifestStore
import skillbill.engine.goalrunner.RecordingOutcomeStore
import skillbill.engine.goalrunner.execution.core.testGoalRunnerStatusService
import skillbill.engine.goalrunner.manifest
import skillbill.engine.goalrunner.model.GoalRunnerStatusRequest
import skillbill.goalrunner.assessment.model.GoalAssessmentDisposition
import skillbill.goalrunner.assessment.model.GoalAssessmentExitReason
import skillbill.goalrunner.assessment.model.GoalAssessmentFinding
import skillbill.goalrunner.assessment.model.GoalAssessmentFindingKind
import skillbill.goalrunner.assessment.model.GoalAssessmentFindingState
import skillbill.goalrunner.assessment.model.GoalAssessmentFix
import skillbill.goalrunner.assessment.model.GoalAssessmentFixStatus
import skillbill.goalrunner.assessment.model.GoalAssessmentFixValidation
import skillbill.goalrunner.assessment.model.GoalAssessmentRecord
import skillbill.goalrunner.assessment.model.GoalAssessmentRound
import skillbill.goalrunner.assessment.model.GoalAssessmentVerification
import skillbill.goalrunner.model.ExecutionLiveness
import skillbill.goalrunner.model.GoalRunnerAssessmentResult
import skillbill.goalrunner.model.GoalRunnerAssessmentState
import skillbill.workflow.taskruntime.model.review.FeatureTaskRuntimeReviewSeverity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GoalRunnerStatusAssessmentResultTest {
  @Test
  fun `a mid-round record with no executor projects pending and keeps the first round findings`() {
    val projection = status(GoalAssessmentRecord("SKILL-56", rounds = listOf(roundOne(fixed = true))))
    val assessment = requireNotNull(projection.goalAssessmentResult)

    assertEquals(GoalRunnerAssessmentState.PENDING, assessment.state)
    assertEquals(2, assessment.round)
    assertNull(assessment.exitReason)
    assertEquals(true, assessment.fixRan)
    assertEquals(listOf("GA-002"), assessment.openFindingRefs)
    assertEquals(
      listOf(
        Triple("GA-002", GoalAssessmentVerification.CONFIRMED, "Reproduced twice."),
        Triple("GA-004", GoalAssessmentVerification.REFUTED, "The caller guards the null."),
      ),
      assessment.findings.map { Triple(it.ref, it.verification, it.evidence) },
    )
  }

  @Test
  fun `a mid-round record stays running only while the goal executor is alive`() {
    val running =
      GoalRunnerAssessmentResult(GoalRunnerAssessmentState.RUNNING, 2, null, false, emptyList(), emptyList())

    assertEquals(GoalRunnerAssessmentState.RUNNING, running.reconciledWith(ExecutionLiveness.LIVE).state)
    assertEquals(GoalRunnerAssessmentState.PENDING, running.reconciledWith(ExecutionLiveness.IDLE).state)
    assertEquals(
      GoalRunnerAssessmentState.COMPLETED,
      running.copy(state = GoalRunnerAssessmentState.COMPLETED).reconciledWith(ExecutionLiveness.IDLE).state,
    )
  }

  @Test
  fun `an absent record stays absent and a skipped record stays skipped`() {
    assertNull(status(null).goalAssessmentResult)

    val skipped =
      requireNotNull(
        status(GoalAssessmentRecord("SKILL-56", exitReason = GoalAssessmentExitReason.SKIPPED_NO_CHANGES))
          .goalAssessmentResult,
      )
    assertEquals(GoalRunnerAssessmentState.SKIPPED, skipped.state)
    assertEquals(emptyList(), skipped.findings)
  }

  private fun status(record: GoalAssessmentRecord?) =
    requireNotNull(
      testGoalRunnerStatusService(
        manifestStore = InMemoryGoalManifestStore(manifest(subtaskCount = 1)),
        outcomeStore = RecordingOutcomeStore().apply { record?.let { goalAssessmentRecords["wfl-parent"] = it } },
      ).status(GoalRunnerStatusRequest(issueKey = "SKILL-56", invokedAgentId = "codex")),
    )

  private fun roundOne(fixed: Boolean) =
    GoalAssessmentRound(
      round = 1,
      headSha = "abc1234",
      scopeFiles = emptyList(),
      assessorRuns = emptyList(),
      findings =
        listOf(
          finding("GA-002", GoalAssessmentVerification.CONFIRMED, "Reproduced twice.", GoalAssessmentFindingState.OPEN),
          finding(
            "GA-004",
            GoalAssessmentVerification.REFUTED,
            "The caller guards the null.",
            GoalAssessmentFindingState.REFUTED,
          ),
        ),
      scenarios = emptyList(),
      regressions = emptyList(),
      fix =
        GoalAssessmentFix(
          GoalAssessmentFixStatus.COMPLETED,
          listOf("src/Retry.kt"),
          "abc1234",
          GoalAssessmentFixValidation.PASSED,
        ).takeIf { fixed },
    )

  private fun finding(
    ref: String,
    verification: GoalAssessmentVerification,
    evidence: String,
    state: GoalAssessmentFindingState,
  ) = GoalAssessmentFinding(
    findingRef = ref,
    kind = GoalAssessmentFindingKind.DEFECT,
    severity = FeatureTaskRuntimeReviewSeverity.MAJOR,
    path = "src/Retry.kt",
    line = 12,
    failureScenario = "The retry loop never stops.",
    guardedRef = null,
    subtaskId = null,
    originRound = 1,
    verification = verification,
    verificationEvidence = evidence,
    fixRoundsSurvived = 0,
    disposition =
      if (state == GoalAssessmentFindingState.REFUTED) {
        GoalAssessmentDisposition.PR_ONLY
      } else {
        GoalAssessmentDisposition.FIX
      },
    state = state,
  )
}
