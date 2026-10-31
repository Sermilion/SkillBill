package skillbill.engine.goalrunner.execution.core

import skillbill.engine.goalrunner.execution.support.toPullRequestRequest
import skillbill.engine.goalrunner.model.GoalSubtaskUnknownScenarios
import skillbill.engine.goalrunner.model.GoalUnknownScenario
import skillbill.engine.goalrunner.persist.assessment.GoalAssessmentRecordRead
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
import skillbill.goalrunner.assessment.model.GoalAssessmentRole
import skillbill.goalrunner.assessment.model.GoalAssessmentRound
import skillbill.goalrunner.assessment.model.GoalAssessmentRun
import skillbill.goalrunner.assessment.model.GoalAssessmentRunStatus
import skillbill.goalrunner.assessment.model.GoalAssessmentVerification
import skillbill.workflow.decomposition.model.CurrentSubtaskIntent
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.decomposition.model.DecompositionSubtask
import skillbill.workflow.taskruntime.model.review.FeatureTaskRuntimeReviewSeverity
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GoalAssessmentPullRequestTextTest {
  @Test
  fun `pull request reports a persistent finding with every surviving finding and the failed assessor run`() {
    val record =
      GoalAssessmentRecord(
        issueKey = "333",
        rounds =
          listOf(
            round(1, runs = listOf(GoalAssessmentRun(GoalAssessmentRole.SUBTASK, 2, GoalAssessmentRunStatus.FAILED))),
            round(2),
            round(
              3,
              findings = listOf(persistent, refuted, testValue),
              regressions = listOf(unrestored),
            ),
          ),
        exitReason = GoalAssessmentExitReason.PERSISTENT_FINDING,
        openFindingRefs = listOf("GA-002", "GA-005"),
      )

    val body =
      manifest().toPullRequestRequest(
        Path.of("/repo"),
        goalAssessment = GoalAssessmentRecordRead.Recorded(record),
        unknownScenarios = { GoalSubtaskUnknownScenarios.Recorded(listOf(GoalUnknownScenario("IS-001", "no trace"))) },
      ).body

    assertTrue(body.contains("- Exit reason: persistent_finding after 3 round(s)."))
    assertTrue(
      body.contains(
        "- GA-002 (major) src/Retry.kt:12 [open; verification: confirmed]: The retry loop never stops. " +
          "Evidence: Reproduced twice.",
      ),
    )
    assertTrue(
      body.contains(
        "- GA-004 (major) src/Cache.kt:30 [refuted; verification: refuted]: The cache read can be null. " +
          "Evidence: The caller guards the null.",
      ),
    )
    assertTrue(
      body.contains(
        "- GA-005 (minor) src/RetryTest.kt:8 [open, pr_only; verification: not_required]: The test asserts nothing. " +
          "Guards: src/Retry.kt:12",
      ),
    )
    assertTrue(body.contains("- TR-001 not restored: The cache is still stale."))
    assertTrue(body.contains("- Warning: the subtask run for subtask 2 failed in round 1."))
    assertTrue(body.contains("Interaction scenarios not traced: IS-001 (no trace)"))
  }

  @Test
  fun `pull request marks open findings the last round fixed as not re-verified`() {
    val record =
      GoalAssessmentRecord(
        issueKey = "333",
        rounds =
          listOf(
            round(
              1,
              findings = listOf(persistent),
              fix =
                GoalAssessmentFix(
                  GoalAssessmentFixStatus.COMPLETED,
                  listOf("src/Retry.kt"),
                  "abc1234",
                  GoalAssessmentFixValidation.PASSED,
                ),
            ),
          ),
        exitReason = GoalAssessmentExitReason.ROUND_CAP,
        openFindingRefs = listOf("GA-002"),
      )

    val body =
      manifest().toPullRequestRequest(Path.of("/repo"), goalAssessment = GoalAssessmentRecordRead.Recorded(record)).body

    assertTrue(body.contains("- GA-002 (major) src/Retry.kt:12 [fixed, not re-verified; verification: confirmed]"))
  }

  @Test
  fun `pull request says assessment did not run when the record is absent or skipped`() {
    val absent = manifest().toPullRequestRequest(Path.of("/repo")).body
    val skipped =
      manifest().toPullRequestRequest(
        Path.of("/repo"),
        goalAssessment =
          GoalAssessmentRecordRead.Recorded(
            GoalAssessmentRecord("333", exitReason = GoalAssessmentExitReason.SKIPPED_NO_CHANGES),
          ),
      ).body

    assertTrue(absent.contains("- Assessment did not run: no assessment record was stored."))
    assertTrue(skipped.contains("- Assessment did not run: the goal made no changes to assess."))
    assertFalse(skipped.contains("Exit reason"))
    assertEquals(1, Regex("Assessment did not run").findAll(skipped).count())
  }

  private val persistent =
    finding("GA-002", "src/Retry.kt", 12, "The retry loop never stops.", GoalAssessmentVerification.CONFIRMED)
      .copy(verificationEvidence = "Reproduced twice.", fixRoundsSurvived = 2)

  private val refuted =
    finding("GA-004", "src/Cache.kt", 30, "The cache read can be null.", GoalAssessmentVerification.REFUTED)
      .copy(
        verificationEvidence = "The caller guards the null.",
        state = GoalAssessmentFindingState.REFUTED,
        disposition = GoalAssessmentDisposition.PR_ONLY,
      )

  private val testValue =
    finding("GA-005", "src/RetryTest.kt", 8, "The test asserts nothing.", GoalAssessmentVerification.NOT_REQUIRED)
      .copy(
        kind = GoalAssessmentFindingKind.TEST_VALUE,
        severity = FeatureTaskRuntimeReviewSeverity.MINOR,
        guardedRef = "src/Retry.kt:12",
        disposition = GoalAssessmentDisposition.PR_ONLY,
      )

  private val unrestored =
    GoalAssessmentRegressionResult("TR-001", GoalAssessmentRegressionState.NOT_RESTORED, "The cache is still stale.")

  private fun finding(
    ref: String,
    path: String,
    line: Int,
    scenario: String,
    verification: GoalAssessmentVerification,
  ) = GoalAssessmentFinding(
    findingRef = ref,
    kind = GoalAssessmentFindingKind.DEFECT,
    severity = FeatureTaskRuntimeReviewSeverity.MAJOR,
    path = path,
    line = line,
    failureScenario = scenario,
    guardedRef = null,
    subtaskId = null,
    originRound = 1,
    verification = verification,
    verificationEvidence = "",
    fixRoundsSurvived = 0,
    disposition = GoalAssessmentDisposition.FIX,
    state = GoalAssessmentFindingState.OPEN,
  )

  private fun round(
    number: Int,
    runs: List<GoalAssessmentRun> = emptyList(),
    findings: List<GoalAssessmentFinding> = emptyList(),
    regressions: List<GoalAssessmentRegressionResult> = emptyList(),
    fix: GoalAssessmentFix? = null,
  ) = GoalAssessmentRound(number, "abc1234", emptyList(), runs, findings, emptyList(), regressions, fix)

  private fun manifest() =
    DecompositionManifest(
      issueKey = "333",
      featureName = "review-citation-ingestion",
      parentSpecPath = ".feature-specs/333-review-citation-ingestion/spec.md",
      baseBranch = "main",
      featureBranch = "feat/333-review-citation-ingestion",
      currentSubtaskIntent = CurrentSubtaskIntent(subtaskId = 1, action = "start"),
      subtasks =
        listOf(
          DecompositionSubtask(
            id = 1,
            name = "Parse citation metadata",
            specPath = ".feature-specs/333-review-citation-ingestion/spec_subtask_1.md",
          ),
        ),
    )
}
