package skillbill.cli.goal.status

import skillbill.cli.kernel.work.workDetailItemWireMap
import skillbill.goalrunner.assessment.model.GoalAssessmentExitReason
import skillbill.goalrunner.assessment.model.GoalAssessmentFindingState
import skillbill.goalrunner.assessment.model.GoalAssessmentVerification
import skillbill.goalrunner.model.GoalRunnerAssessmentFinding
import skillbill.goalrunner.model.GoalRunnerAssessmentResult
import skillbill.goalrunner.model.GoalRunnerAssessmentState
import skillbill.goalrunner.model.GoalRunnerStatusProjection
import skillbill.idestatus.model.IdeStatusFreshness
import skillbill.idestatus.model.IdeStatusLifecycleState
import skillbill.ports.idestatus.model.IdeStatusSnapshot
import skillbill.ports.idestatus.model.IdeStatusStep
import skillbill.ports.idestatus.model.WorkDetailItem
import skillbill.workflow.taskruntime.model.review.FeatureTaskRuntimeReviewSeverity
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GoalCliStatusAssessmentTest {
  @Test
  fun `goal status and work detail both carry the open and refuted findings with their evidence`() {
    val projection = projection(assessment())

    val text = goalStatusText("SKILL-442", projection)
    val wire = workDetailItemWireMap(detailItem(projection), emptyMap())["goal_assessment"] as Map<*, *>
    val findings = wire["findings"] as List<*>

    assertTrue(
      text.contains("goal_assessment: round=3 exit=persistent_finding confirmed=1 refuted=1 open=GA-002\n"),
      text,
    )
    assertTrue(
      text.contains(
        "goal_assessment_finding: ref=GA-002 severity=major location=src/Retry.kt:12 state=open " +
          "verification=confirmed scenario=The\\sretry\\sloop\\snever\\sstops. evidence=Reproduced\\stwice.\n",
      ),
      text,
    )
    assertTrue(
      text.contains("verification=refuted scenario=The\\scache\\sread\\scan\\sbe\\snull. evidence=The\\s"),
      text,
    )
    assertEquals(listOf("GA-002"), wire["open_finding_refs"])
    assertEquals(listOf("confirmed", "refuted"), findings.map { (it as Map<*, *>)["verification"] })
    assertEquals("Reproduced twice.", (findings.first() as Map<*, *>)["evidence"])
  }

  @Test
  fun `goal status prints a pending exit while a round runs and no line without a record`() {
    val running = assessment().copy(state = GoalRunnerAssessmentState.RUNNING, round = 2, exitReason = null)

    assertTrue(goalStatusText("SKILL-442", projection(running)).contains("goal_assessment: round=2 exit=pending "))
    assertFalse(goalStatusText("SKILL-442", projection(null)).contains("goal_assessment"))
  }

  private fun assessment() =
    GoalRunnerAssessmentResult(
      state = GoalRunnerAssessmentState.COMPLETED,
      round = 3,
      exitReason = GoalAssessmentExitReason.PERSISTENT_FINDING,
      fixRan = true,
      openFindingRefs = listOf("GA-002"),
      findings =
        listOf(
          GoalRunnerAssessmentFinding(
            ref = "GA-002",
            severity = FeatureTaskRuntimeReviewSeverity.MAJOR,
            path = "src/Retry.kt",
            line = 12,
            failureScenario = "The retry loop never stops.",
            verification = GoalAssessmentVerification.CONFIRMED,
            state = GoalAssessmentFindingState.OPEN,
            evidence = "Reproduced twice.",
          ),
          GoalRunnerAssessmentFinding(
            ref = "GA-004",
            severity = FeatureTaskRuntimeReviewSeverity.MAJOR,
            path = "src/Cache.kt",
            line = 30,
            failureScenario = "The cache read can be null.",
            verification = GoalAssessmentVerification.REFUTED,
            state = GoalAssessmentFindingState.REFUTED,
            evidence = "The caller guards the null.",
          ),
        ),
    )

  private fun projection(assessment: GoalRunnerAssessmentResult?) =
    GoalRunnerStatusProjection(
      issueKey = "SKILL-442",
      completeCount = 1,
      pendingCount = 0,
      blockedCount = 0,
      currentSubtaskId = null,
      currentStep = null,
      activeAgent = null,
      goalAssessmentResult = assessment,
    )

  private fun detailItem(projection: GoalRunnerStatusProjection) =
    WorkDetailItem(
      snapshot =
        IdeStatusSnapshot(
          repositoryIdentity = "repo-root-realpath-v1:/repo",
          lifecycleState = IdeStatusLifecycleState.IDLE,
          currentStep = IdeStatusStep("none", "No work"),
          updatedAt = Instant.EPOCH,
          freshness = IdeStatusFreshness.FRESH,
          summary = "No work.",
        ),
      recoveryWorkId = "goal-1",
      repositoryRoot = "/repo",
      stepPipeline = emptyList(),
      goalStatus = projection,
    )
}
