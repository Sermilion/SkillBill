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
import skillbill.goalrunner.model.GoalRunnerProgressEvent
import skillbill.goalrunner.model.GoalRunnerStatusProjection
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
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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
        FeatureTaskRuntimeProcessInspection.PidReused to ExecutionLiveness.PID_REUSED,
        FeatureTaskRuntimeProcessInspection.ForeignHost to ExecutionLiveness.FOREIGN_HOST,
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

  @Test
  fun `live goal whose last durable progress is past the stall threshold reports stalled despite child heartbeats`() {
    val lastProgress = stallObservedAt.minus(Duration.ofMinutes(106))
    val status = liveGoalStatus(qaProgress(lastDurableProgressAt = lastProgress))

    assertEquals(ExecutionLiveness.LIVE, status.executionLiveness)
    assertEquals(lastProgress, status.progressStall?.lastProgressAt)
    assertEquals("no progress for 1h 46m", status.progressStall?.describe())
  }

  @Test
  fun `live goal with recent durable progress is not stalled`() {
    val status = liveGoalStatus(qaProgress(lastDurableProgressAt = stallObservedAt.minus(Duration.ofMinutes(5))))

    assertEquals(ExecutionLiveness.LIVE, status.executionLiveness)
    assertNull(status.progressStall)
  }

  private val stallObservedAt: Instant = Instant.parse("2026-10-20T11:35:00Z")

  private fun liveGoalStatus(parentProgress: GoalRunnerWorkflowProgress): GoalRunnerStatusProjection {
    val store =
      InMemoryGoalManifestStore(manifest = manifest(subtaskCount = 1))
        .apply { executionLeaseForTest = liveLease() }
    val outcomes = RecordingOutcomeStore()
    outcomes.progresses["wfl-parent"] = parentProgress
    return requireNotNull(
      testGoalRunnerStatusService(
        manifestStore = store,
        outcomeStore = outcomes,
        clock = Clock.fixed(stallObservedAt, ZoneOffset.UTC),
        ports = GoalRunnerStatusTestPorts(workerSupervisor = liveSupervisor),
      ).status(GoalRunnerStatusRequest(issueKey = "SKILL-56")),
    )
  }

  private fun qaProgress(lastDurableProgressAt: Instant): GoalRunnerWorkflowProgress =
    GoalRunnerWorkflowProgress(
      workflowId = "wfl-parent",
      workflowStatus = WorkflowStatus.RUNNING,
      currentStepId = "qa",
      progressToken = "qa-running",
      latestDurableProgressEvent =
        GoalRunnerProgressEvent(
          stepId = "qa",
          attemptCount = 1,
          kind = "heartbeat",
          message = "receipt written",
          sequence = 3,
          timestamp = lastDurableProgressAt.toString(),
        ),
      latestDeclaredProgressEvent =
        GoalProgressEvent(
          eventKind = GoalProgressEventKind.OPERATION_HEARTBEAT,
          workflowId = "wfl-parent",
          workflowPhase = "qa",
          processAlive = true,
          sequenceNumber = 9,
          timestamp = stallObservedAt.minusSeconds(30),
          stepId = "qa",
          operationName = "The 'qa' child is active; source progress is not confirmed.",
          operationKind = GOAL_FINALIZATION_OPERATION_KIND,
          expectedLong = true,
          outcome = GoalProgressOutcome.NONE,
        ),
    )

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
