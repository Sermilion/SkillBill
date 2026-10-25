package skillbill.application.review.stats

import skillbill.review.model.GoalAssessmentStats
import skillbill.review.model.GoalInteractionScenarioStats
import skillbill.review.model.GoalTestProbeStats
import skillbill.review.model.GoalWorkflowStats
import kotlin.test.Test
import kotlin.test.assertEquals

class GoalStatsPayloadMappingTest {
  @Test
  fun `each scenario probe and assessment counter lands under its own payload key`() {
    val payload =
      GoalWorkflowStats(
        totalRuns = 0,
        finishedRuns = 0,
        inProgressRuns = 0,
        completionStatusCounts = emptyMap(),
        completedRuns = 0,
        completedRate = 0.0,
        blockedRuns = 0,
        blockedRate = 0.0,
        subtaskOutcomeCounts = emptyMap(),
        totalSubtaskEvents = 0,
        averageRunDurationMs = 0.0,
        averageSubtaskDurationMs = 0.0,
        averageAttemptCount = 0.0,
        mostRecentRun = null,
        topBlockedSubtasks = emptyList(),
        logicalGoals = 0,
        invocationsWithUnknownGoal = 0,
        goalIdentityAvailability = "measured",
        resumedInvocations = 0,
        interactionScenarios = GoalInteractionScenarioStats(traced = 11, broken = 12, repaired = 13, unknown = 14),
        testProbes = GoalTestProbeStats(killed = 21, survived = 22, repaired = 23, notRun = 24),
        goalAssessment =
          GoalAssessmentStats(
            blockerFindings = 31,
            majorFindings = 32,
            minorFindings = 33,
            confirmed = 34,
            refuted = 35,
            rounds = 36,
            openFindings = 37,
            exitReasonCounts = mapOf("converged" to 2, "round_cap" to 1),
          ),
      ).toPayload()

    assertEquals(
      mapOf("traced" to 11L, "broken" to 12L, "repaired" to 13L, "unknown" to 14L),
      payload["interaction_scenarios"],
    )
    assertEquals(
      mapOf("killed" to 21L, "survived" to 22L, "repaired" to 23L, "not_run" to 24L),
      payload["test_probes"],
    )
    assertEquals(
      mapOf(
        "findings_by_severity" to mapOf("blocker" to 31L, "major" to 32L, "minor" to 33L),
        "confirmed" to 34L,
        "refuted" to 35L,
        "rounds" to 36L,
        "open_findings" to 37L,
        "exit_reasons" to mapOf("converged" to 2, "round_cap" to 1),
      ),
      payload["goal_assessment"],
    )
  }
}
