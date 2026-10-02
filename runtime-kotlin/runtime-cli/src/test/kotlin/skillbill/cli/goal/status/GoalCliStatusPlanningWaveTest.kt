package skillbill.cli.goal.status

import skillbill.goalrunner.model.GoalPlanningStatusSnapshot
import skillbill.goalrunner.model.GoalPlanningStatusState
import skillbill.goalrunner.model.GoalRunnerStatusProjection
import skillbill.workflow.model.goalobservability.GoalObservabilityEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GoalCliStatusPlanningWaveTest {
  @Test
  fun `goal status carries every concurrent planning subtask and names the count on the human line`() {
    val projection =
      GoalRunnerStatusProjection(
        issueKey = "SKILL-230",
        completeCount = 1,
        pendingCount = 7,
        blockedCount = 0,
        currentSubtaskId = 2,
        currentStep = "planning",
        activeAgent = "claude",
        planning =
          GoalPlanningStatusSnapshot(
            state = GoalPlanningStatusState.PARTIALLY_PLANNED,
            sharedPreplanPrepared = true,
            plannedSubtaskCount = 1,
            totalSubtaskCount = 8,
            currentPlanningSubtaskId = 2,
            planningWaveSubtaskIds = listOf(2, 3, 4, 5, 6),
            reason = "Planning resumes at subtask 2.",
          ),
      )

    val payload = projection.toGoalStatusCliMap("SKILL-230")
    val planning = payload["planning"] as Map<*, *>
    assertEquals(listOf(2, 3, 4, 5, 6), planning["planning_wave_subtasks"])
    assertEquals(2, planning["current_planning_subtask"])

    val text = goalStatusText("SKILL-230", projection)
    assertTrue(text.contains("wave=5 subtasks"), text)
  }

  @Test
  fun `goal status renders typed latest observability as a wire object`() {
    val projection =
      GoalRunnerStatusProjection(
        issueKey = "SKILL-230",
        completeCount = 1,
        pendingCount = 0,
        blockedCount = 0,
        currentSubtaskId = 2,
        currentStep = "implement",
        activeAgent = "claude",
        latestObservabilityEvent =
          GoalObservabilityEvent(
            issueKey = "SKILL-230",
            subtaskId = 2,
            workflowPhase = "implement",
            workerRole = "claude",
            livenessClass = "active",
            activitySummary = "working",
            timestamp = "2026-09-14T10:00:00Z",
            sequenceNumber = 4,
          ),
      )

    val payload = projection.toGoalStatusCliMap("SKILL-230")
    val event = payload["latest_observability_event"] as Map<*, *>
    assertEquals("implement", event["workflow_phase"])
    assertTrue(goalStatusText("SKILL-230", projection).contains("latest_observability: phase=implement"))
  }
}
