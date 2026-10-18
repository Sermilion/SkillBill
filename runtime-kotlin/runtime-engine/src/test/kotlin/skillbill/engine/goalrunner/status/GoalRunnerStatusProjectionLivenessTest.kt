package skillbill.engine.goalrunner.status

import skillbill.application.FakeDatabaseSessionFactory
import skillbill.application.InMemoryWorkflowStates
import skillbill.application.testWorkflowSnapshotValidator
import skillbill.engine.featuretask.phase.record.openTestWorkflow
import skillbill.engine.goalrunner.InMemoryGoalManifestStore
import skillbill.engine.goalrunner.RecordingOutcomeStore
import skillbill.engine.goalrunner.execution.core.GoalRunnerStatusTestPorts
import skillbill.engine.goalrunner.execution.core.goalRunnerDefaultPhaseRecorder
import skillbill.engine.goalrunner.execution.core.testGoalRunnerStatusService
import skillbill.engine.goalrunner.execution.core.testPhaseRecorder
import skillbill.engine.goalrunner.manifest
import skillbill.engine.goalrunner.model.GoalRunnerStatusRequest
import skillbill.engine.goalrunner.model.GoalRunnerWorkflowProgress
import skillbill.engine.goalrunner.monitoring.GOAL_FINALIZATION_OPERATION_KIND
import skillbill.goalrunner.model.ExecutionLiveness
import skillbill.goalrunner.model.GoalRunnerControlState
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerOwnership
import skillbill.ports.taskruntime.FeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.taskruntime.NoopFeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessInspection
import skillbill.workflow.decomposition.model.CurrentSubtaskIntent
import skillbill.workflow.decomposition.withWorkflowId
import skillbill.workflow.model.WorkflowStatus
import skillbill.workflow.model.goalobservability.GoalProgressEvent
import skillbill.workflow.model.goalobservability.GoalProgressEventKind
import skillbill.workflow.model.goalobservability.GoalProgressOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GoalRunnerStatusProjectionLivenessTest {
  private val liveSupervisor =
    object : FeatureTaskRuntimeWorkerSupervisor by NoopFeatureTaskRuntimeWorkerSupervisor {
      override fun inspect(ownership: FeatureTaskRuntimeWorkerOwnership) = FeatureTaskRuntimeProcessInspection.ExactLive
    }

  @Test
  fun `reused pid and unsupported process inspection cannot prove a live goal owner`() {
    val store =
      InMemoryGoalManifestStore(manifest = manifest(subtaskCount = 1))
        .apply { executionLeaseForTest = liveLease() }
    val cases =
      listOf(
        FeatureTaskRuntimeProcessInspection.ExactLive to ExecutionLiveness.LIVE,
        FeatureTaskRuntimeProcessInspection.NotRunning to ExecutionLiveness.IDLE,
        FeatureTaskRuntimeProcessInspection.OwnershipMismatch("process birth differs") to ExecutionLiveness.IDLE,
        FeatureTaskRuntimeProcessInspection.Unsupported("inspection unavailable") to ExecutionLiveness.UNKNOWN,
      )
    for ((inspection, expected) in cases) {
      val supervisor =
        object : FeatureTaskRuntimeWorkerSupervisor by NoopFeatureTaskRuntimeWorkerSupervisor {
          override fun inspect(ownership: FeatureTaskRuntimeWorkerOwnership) = inspection
        }
      val status =
        requireNotNull(
          testGoalRunnerStatusService(
            manifestStore = store,
            outcomeStore = RecordingOutcomeStore(),
            ports = GoalRunnerStatusTestPorts(workerSupervisor = supervisor),
          ).status(GoalRunnerStatusRequest(issueKey = "SKILL-56")),
        )
      assertEquals(expected, status.executionLiveness, inspection.toString())
    }
  }

  @Test
  fun `parked parent progress with goal finalization on monitor aligns liveness with current step`() {
    val store =
      InMemoryGoalManifestStore(
        manifest = manifest(subtaskCount = 1),
      ).apply { executionLeaseForTest = liveLease() }
    val outcomes = RecordingOutcomeStore()
    outcomes.progresses["wfl-parent"] = parkedParentWithGoalFinalizationOnMonitor()
    val status =
      requireNotNull(
        testGoalRunnerStatusService(
          manifestStore = store,
          ports = GoalRunnerStatusTestPorts(workerSupervisor = liveSupervisor),
          outcomeStore = outcomes,
          phaseRecorder = goalRunnerDefaultPhaseRecorder(),
        ).status(
          GoalRunnerStatusRequest(
            issueKey = "SKILL-56",
            invokedAgentId = "codex",
          ),
        ),
      )

    assertEquals("monitor", status.currentStep)
    assertEquals("workflow_status=PAUSED; step=monitor", status.latestLivenessSignal)
    assertFalse(status.latestLivenessSignal!!.contains("step=plan"))
  }

  @Test
  fun `completed child workflow id does not override live monitor on the parent goal`() {
    val childWorkflowId = "wfl-child-complete"
    val store =
      InMemoryGoalManifestStore(
        manifest =
          manifest(subtaskCount = 1)
            .withWorkflowId(subtaskId = 1, workflowId = childWorkflowId),
      ).apply { executionLeaseForTest = liveLease() }
    val states = InMemoryWorkflowStates()
    val phaseRecorder =
      testPhaseRecorder(
        FakeDatabaseSessionFactory(states),
        testWorkflowSnapshotValidator,
      )
    phaseRecorder.openTestWorkflow(childWorkflowId, sessionId = "goal-status-liveness-test")
    val outcomes = RecordingOutcomeStore()
    outcomes.progresses[childWorkflowId] =
      GoalRunnerWorkflowProgress(
        workflowId = childWorkflowId,
        workflowStatus = WorkflowStatus.COMPLETED,
        currentStepId = "commit_push",
        progressToken = "child-done",
        latestLivenessSignal = "durable_progress step=commit_push attempt=1",
      )
    outcomes.progresses["wfl-parent"] = parkedParentWithGoalFinalizationOnMonitor()
    val status =
      requireNotNull(
        testGoalRunnerStatusService(
          manifestStore = store,
          ports = GoalRunnerStatusTestPorts(workerSupervisor = liveSupervisor),
          outcomeStore = outcomes,
          phaseRecorder = phaseRecorder,
        ).status(
          GoalRunnerStatusRequest(
            issueKey = "SKILL-56",
            invokedAgentId = "codex",
          ),
        ),
      )

    assertEquals("monitor", status.currentStep)
    assertEquals("workflow_status=PAUSED; step=monitor", status.latestLivenessSignal)
    assertFalse(status.latestLivenessSignal!!.contains("commit_push"))
  }

  @Test
  fun `operator pause from control state is still projected when parent goal step is live`() {
    val store =
      InMemoryGoalManifestStore(
        manifest = manifest(subtaskCount = 1),
        initialControlState =
          GoalRunnerControlState(
            paused = true,
            pauseReason = "operator requested pause",
            pausedAt = "2026-08-07T12:00:00Z",
          ),
      ).apply { executionLeaseForTest = liveLease() }
    val outcomes = RecordingOutcomeStore()
    outcomes.progresses["wfl-parent"] = parkedParentWithGoalFinalizationOnMonitor()
    val status =
      requireNotNull(
        testGoalRunnerStatusService(
          manifestStore = store,
          ports = GoalRunnerStatusTestPorts(workerSupervisor = liveSupervisor),
          outcomeStore = outcomes,
          phaseRecorder = goalRunnerDefaultPhaseRecorder(),
        ).status(
          GoalRunnerStatusRequest(
            issueKey = "SKILL-56",
            invokedAgentId = "codex",
          ),
        ),
      )

    assertTrue(status.paused)
    assertEquals("operator requested pause", status.pauseReason)
    assertEquals("monitor", status.currentStep)
  }

  @Test
  fun `live goal finalization reports its agent once every subtask is complete`() {
    val finished = manifest(subtaskCount = 1)
    val store =
      InMemoryGoalManifestStore(
        manifest =
          finished.copy(
            status = "complete",
            currentSubtaskIntent = CurrentSubtaskIntent(subtaskId = 0, action = "complete"),
            subtasks = finished.subtasks.map { it.copy(status = "complete", commitSha = "sha-1") },
          ),
      ).apply { executionLeaseForTest = liveLease() }
    val outcomes = RecordingOutcomeStore()
    val parked = parkedParentWithGoalFinalizationOnMonitor()
    outcomes.progresses["wfl-parent"] =
      parked.copy(latestDeclaredProgressEvent = parked.latestDeclaredProgressEvent?.copy(agentId = "claude"))
    val status =
      requireNotNull(
        testGoalRunnerStatusService(
          manifestStore = store,
          ports = GoalRunnerStatusTestPorts(workerSupervisor = liveSupervisor),
          outcomeStore = outcomes,
          phaseRecorder = goalRunnerDefaultPhaseRecorder(),
        ).status(GoalRunnerStatusRequest(issueKey = "SKILL-56", invokedAgentId = "codex")),
      )

    assertEquals("monitor", status.currentStep)
    assertEquals("claude", status.activeAgent)
  }

  private fun parkedParentWithGoalFinalizationOnMonitor(): GoalRunnerWorkflowProgress =
    GoalRunnerWorkflowProgress(
      workflowId = "wfl-parent",
      workflowStatus = WorkflowStatus.PAUSED,
      currentStepId = "plan",
      progressToken = "monitor-heartbeat",
      latestDeclaredProgressEvent =
        GoalProgressEvent(
          eventKind = GoalProgressEventKind.OPERATION_HEARTBEAT,
          workflowId = "wfl-parent",
          workflowPhase = "monitor",
          processAlive = true,
          sequenceNumber = 1,
          timestamp = "2026-08-07T12:00:00Z",
          stepId = "monitor",
          operationName = "monitor",
          operationKind = GOAL_FINALIZATION_OPERATION_KIND,
          outcome = GoalProgressOutcome.NONE,
        ),
      latestLivenessSignal = "workflow_status=PAUSED; step=plan",
    )
}
