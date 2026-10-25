package skillbill.infrastructure.sqlite.review.stats

import skillbill.contracts.telemetry.TelemetryMeasurementAvailability
import kotlin.test.Test
import kotlin.test.assertEquals

class GoalStatsLogicalGoalGrainTest {
  @Test
  fun `three resume segments of one goal report one goal and three invocations`() {
    val stats =
      buildGoalStats(
        listOf(
          segment("parent-1:seg:1", parentWorkflowId = "parent-1", resumed = false, status = "blocked"),
          segment("parent-1:seg:2", parentWorkflowId = "parent-1", resumed = true, status = "blocked"),
          segment("parent-1:seg:3", parentWorkflowId = "parent-1", resumed = true, status = "completed"),
        ),
        emptyList(),
      )

    assertEquals(3, stats.totalRuns, "the store holds three invocation segments")
    assertEquals(1, stats.logicalGoals, "all three segments belong to one goal")
    assertEquals(2, stats.resumedInvocations)
    assertEquals(0, stats.invocationsWithUnknownGoal)
    assertEquals(TelemetryMeasurementAvailability.MEASURED.wireValue, stats.goalIdentityAvailability)
  }

  @Test
  fun `segments written before the parent id was persisted are counted as unattributed, not as one goal`() {
    val stats =
      buildGoalStats(
        listOf(
          segment("legacy-1", parentWorkflowId = null, resumed = false, status = "completed"),
          segment("legacy-2", parentWorkflowId = null, resumed = true, status = "completed"),
          segment("parent-9:seg:1", parentWorkflowId = "parent-9", resumed = false, status = "completed"),
        ),
        emptyList(),
      )

    assertEquals(1, stats.logicalGoals, "only the attributable segment names a goal")
    assertEquals(2, stats.invocationsWithUnknownGoal)
    assertEquals(
      TelemetryMeasurementAvailability.UNAVAILABLE_INCOMPLETE.wireValue,
      stats.goalIdentityAvailability,
      "a partially attributable store must not read as a measured goal count",
    )
  }

  @Test
  fun `assessment totals count one snapshot per logical goal and tally its exit reason`() {
    val stats =
      buildGoalStats(
        listOf(
          segment(
            "parent-1:seg:1",
            parentWorkflowId = "parent-1",
            resumed = false,
            status = "blocked",
            overrides =
              assessment(
                findings = 1,
                rounds = 1,
                exitReason = "round_cap",
                finishedAt = "2026-09-15T11:00:00Z",
              ),
          ),
          segment(
            "parent-1:seg:2",
            parentWorkflowId = "parent-1",
            resumed = true,
            status = "completed",
            overrides =
              assessment(
                findings = 3,
                rounds = 2,
                exitReason = "converged",
                finishedAt = "2026-09-15T12:00:00Z",
              ),
          ),
          segment("parent-2:seg:1", parentWorkflowId = "parent-2", resumed = false, status = "completed"),
        ),
        emptyList(),
      )

    assertEquals(3L, stats.goalAssessment.majorFindings, "the latest segment's cumulative record wins")
    assertEquals(2L, stats.goalAssessment.rounds)
    assertEquals(mapOf("converged" to 1), stats.goalAssessment.exitReasonCounts)
  }

  @Test
  fun `subtask trace and probe counters are summed across subtask events`() {
    val stats =
      buildGoalStats(
        emptyList(),
        listOf(
          subtaskEvent(1, traced = 4, broken = 2, killed = 3, survived = 1),
          subtaskEvent(2, traced = 1, broken = 0, killed = 2, survived = 0),
        ),
      )

    assertEquals(5L, stats.interactionScenarios.traced)
    assertEquals(2L, stats.interactionScenarios.broken)
    assertEquals(5L, stats.testProbes.killed)
    assertEquals(1L, stats.testProbes.survived)
    assertEquals(0L, stats.testProbes.notRun)
  }

  private fun assessment(
    findings: Int,
    rounds: Int,
    exitReason: String,
    finishedAt: String,
  ): Map<String, Any?> =
    mapOf(
      "finished_at" to finishedAt,
      "goal_assessment_major_findings" to findings,
      "goal_assessment_rounds" to rounds,
      "goal_assessment_exit_reason" to exitReason,
    )

  private fun subtaskEvent(
    subtaskId: Int,
    traced: Int,
    broken: Int,
    killed: Int,
    survived: Int,
  ): Map<String, Any?> =
    mapOf(
      "issue_key" to "SKILL-442",
      "workflow_id" to "wftr-$subtaskId",
      "subtask_id" to subtaskId,
      "subtask_name" to "subtask $subtaskId",
      "status" to "complete",
      "started_at" to "2026-09-15T10:00:00Z",
      "finished_at" to "2026-09-15T11:00:00Z",
      "duration_ms" to 1_000L,
      "attempt_count" to 1,
      "interaction_scenarios_traced" to traced,
      "interaction_scenarios_broken" to broken,
      "interaction_scenarios_repaired" to 0,
      "interaction_scenarios_unknown" to 0,
      "test_probes_killed" to killed,
      "test_probes_survived" to survived,
      "test_probes_repaired" to 0,
      "test_probes_not_run" to 0,
    )

  private fun segment(
    workflowId: String,
    parentWorkflowId: String?,
    resumed: Boolean,
    status: String,
    overrides: Map<String, Any?> = emptyMap(),
  ): Map<String, Any?> =
    mapOf(
      "workflow_id" to workflowId,
      "issue_key" to "SKILL-236",
      "feature_name" to "telemetry truth",
      "subtask_total" to 2,
      "resumed" to if (resumed) 1 else 0,
      "started_at" to "2026-09-15T10:00:00Z",
      "status" to status,
      "finished_at" to "2026-09-15T11:00:00Z",
      "finished_duration_ms" to 3_600_000L,
      "subtasks_complete" to 2,
      "subtasks_blocked" to 0,
      "subtasks_skipped" to 0,
      "mode" to "runtime",
      "parent_workflow_id" to parentWorkflowId,
      "goal_assessment_blocker_findings" to 0,
      "goal_assessment_major_findings" to 0,
      "goal_assessment_minor_findings" to 0,
      "goal_assessment_confirmed" to 0,
      "goal_assessment_refuted" to 0,
      "goal_assessment_rounds" to 0,
      "goal_assessment_open_findings" to 0,
    ) + overrides
}
