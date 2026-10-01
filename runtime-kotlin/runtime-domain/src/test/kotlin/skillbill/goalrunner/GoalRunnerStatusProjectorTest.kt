package skillbill.goalrunner

import skillbill.goalrunner.model.GoalRunnerStatusProjectionRuntimeInputs
import skillbill.goalrunner.model.GoalRunnerStatusProjector
import skillbill.workflow.decomposition.model.CurrentSubtaskIntent
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.decomposition.model.DecompositionSubtask
import skillbill.workflow.model.WorkflowStatus
import skillbill.workflow.model.goalobservability.GoalObservabilityEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GoalRunnerStatusProjectorTest {
  @Test
  fun `a relaunched subtask is not counted as blocked while its child workflow runs`() {
    val projection =
      GoalRunnerStatusProjector.project(
        manifest = manifest(currentSubtaskStatus = "blocked"),
        extras = GoalRunnerStatusProjectionRuntimeInputs(currentWorkflowStatus = WorkflowStatus.RUNNING),
      )

    assertEquals(0, projection.blockedCount)
    assertEquals(1, projection.pendingCount)
  }

  @Test
  fun `a subtask with no live child keeps its durable blocked status`() {
    val projection =
      GoalRunnerStatusProjector.project(
        manifest = manifest(currentSubtaskStatus = "blocked"),
        extras = GoalRunnerStatusProjectionRuntimeInputs(currentWorkflowStatus = WorkflowStatus.BLOCKED),
      )

    assertEquals(1, projection.blockedCount)
    assertEquals(0, projection.pendingCount)
  }

  @Test
  fun `a block liveness signal is withheld while the child workflow runs`() {
    val projection =
      GoalRunnerStatusProjector.project(
        manifest = manifest(currentSubtaskStatus = "blocked"),
        extras =
          GoalRunnerStatusProjectionRuntimeInputs(
            currentWorkflowStatus = WorkflowStatus.RUNNING,
            latestLivenessSignal = "liveness=block phase=review role=goal_runner_supervisor",
            latestObservabilityEvent = observabilityEvent("block"),
          ),
      )

    assertNull(projection.latestLivenessSignal)
    assertNull(projection.latestObservabilityEvent)
  }

  @Test
  fun `a non-block liveness signal is preserved while the child workflow runs`() {
    val projection =
      GoalRunnerStatusProjector.project(
        manifest = manifest(currentSubtaskStatus = "in_progress"),
        extras =
          GoalRunnerStatusProjectionRuntimeInputs(
            currentWorkflowStatus = WorkflowStatus.RUNNING,
            latestLivenessSignal = "liveness=durable_progress phase=implement",
            latestObservabilityEvent = observabilityEvent("durable_progress"),
          ),
      )

    assertEquals("liveness=durable_progress phase=implement", projection.latestLivenessSignal)
    assertEquals(observabilityEvent("durable_progress"), projection.latestObservabilityEvent)
  }

  @Test
  fun `a worker output summary from a superseded phase is withheld while the child workflow runs`() {
    val projection =
      GoalRunnerStatusProjector.project(
        manifest = manifest(currentSubtaskStatus = "in_progress"),
        extras =
          GoalRunnerStatusProjectionRuntimeInputs(
            currentWorkflowStatus = WorkflowStatus.RUNNING,
            currentStepOverride = "implement_fix",
            latestLivenessSignal = "liveness=worker_output_summary phase=audit activity=exit_status=1",
            latestObservabilityEvent = observabilityEvent("worker_output_summary", workflowPhase = "audit"),
          ),
      )

    assertEquals("implement_fix", projection.currentStep)
    assertNull(projection.latestLivenessSignal)
    assertNull(projection.latestObservabilityEvent)
  }

  @Test
  fun `a worker output summary for the live phase is preserved`() {
    val event = observabilityEvent("worker_output_summary", workflowPhase = "implement")
    val signal = "liveness=worker_output_summary phase=implement activity=exit_status=0"
    val projection =
      GoalRunnerStatusProjector.project(
        manifest = manifest(currentSubtaskStatus = "in_progress"),
        extras =
          GoalRunnerStatusProjectionRuntimeInputs(
            currentWorkflowStatus = WorkflowStatus.RUNNING,
            currentStepOverride = "implement",
            latestLivenessSignal = signal,
            latestObservabilityEvent = event,
          ),
      )

    assertEquals(signal, projection.latestLivenessSignal)
    assertEquals(event, projection.latestObservabilityEvent)
  }

  @Test
  fun `a stored block signal is reported once the child workflow is no longer running`() {
    val projection =
      GoalRunnerStatusProjector.project(
        manifest = manifest(currentSubtaskStatus = "blocked"),
        extras =
          GoalRunnerStatusProjectionRuntimeInputs(
            currentWorkflowStatus = WorkflowStatus.BLOCKED,
            latestLivenessSignal = "liveness=block phase=review role=goal_runner_supervisor",
            latestObservabilityEvent = observabilityEvent("block"),
          ),
      )

    assertEquals("liveness=block phase=review role=goal_runner_supervisor", projection.latestLivenessSignal)
  }

  private fun observabilityEvent(
    livenessClass: String,
    workflowPhase: String = "implement",
  ): GoalObservabilityEvent =
    GoalObservabilityEvent(
      issueKey = "SKILL-TEST",
      subtaskId = 1,
      workflowPhase = workflowPhase,
      workerRole = "goal_runner_supervisor",
      livenessClass = livenessClass,
      activitySummary = "test",
      timestamp = "2026-01-01T00:00:00Z",
      sequenceNumber = 1,
    )

  private fun manifest(currentSubtaskStatus: String): DecompositionManifest =
    DecompositionManifest(
      issueKey = "SKILL-135",
      featureName = "audit-first-review-gate",
      parentSpecPath = ".feature-specs/SKILL-135/spec.md",
      baseBranch = "main",
      featureBranch = "feat/SKILL-135-audit-first-review-gate",
      currentSubtaskIntent = CurrentSubtaskIntent(subtaskId = 1, action = "resume"),
      subtasks =
        listOf(
          DecompositionSubtask(
            id = 1,
            name = "Only subtask",
            specPath = ".feature-specs/SKILL-135/spec_subtask_1.md",
            status = currentSubtaskStatus,
            workflowId = "wftr-20260720-192238-iwxj",
          ),
        ),
    )
}
