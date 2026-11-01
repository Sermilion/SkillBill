package skillbill.engine.goalrunner.status

import skillbill.application.FakeDatabaseSessionFactory
import skillbill.application.InMemoryWorkflowStates
import skillbill.application.testWorkflowSnapshotValidator
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseStateRequest
import skillbill.engine.featuretask.phase.record.openTestWorkflow
import skillbill.engine.goal.GoalRunnerStatusRequest
import skillbill.engine.goalrunner.InMemoryGoalManifestStore
import skillbill.engine.goalrunner.RecordingOutcomeStore
import skillbill.engine.goalrunner.execution.core.testGoalRunnerStatusService
import skillbill.engine.goalrunner.execution.core.testPhaseRecorder
import skillbill.engine.goalrunner.manifest
import skillbill.workflow.decomposition.model.DecompositionSubtask
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GoalRunnerSubtaskExecutionProjectionTest {
  @Test
  fun `execution uses earliest start latest update and latest available agent and launch`() {
    val older =
      phaseRecord(
        phaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT,
        startedAt = "2026-10-01T10:00:00Z",
        agent = "older-agent",
      ).copy(
        firstStartedAt = Instant.parse("2026-10-01T09:00:00Z"),
        finishedAt = Instant.parse("2026-10-01T10:30:00Z"),
        launchedModel = "claude-opus-5-5",
        launchedEffort = "high",
      )
    val newer =
      phaseRecord(
        phaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT,
        startedAt = "2026-10-01T11:00:00Z",
        agent = "newer-agent",
      )
    val execution = subtaskExecution("wf-sub-1", listOf(older, newer))

    assertEquals("wf-sub-1", execution?.workflowId)
    assertEquals("newer-agent", execution?.agentId)
    assertEquals("claude-opus-5-5", execution?.model)
    assertEquals("high", execution?.effort)
    assertEquals(Instant.parse("2026-10-01T09:00:00Z"), execution?.startedAt)
    assertEquals(Instant.parse("2026-10-01T11:00:00Z"), execution?.updatedAt)
    assertEquals(mapOf("implement" to 1, "audit" to 1), execution?.phaseAttemptCounts)
    assertNull(subtaskExecution("wf-sub-1", emptyList()))
  }

  @Test
  fun `status service carries the subtask workflow into phase results`() {
    val workflowId = "wf-subtask-execution"
    val database = FakeDatabaseSessionFactory(InMemoryWorkflowStates())
    val recorder = testPhaseRecorder(database, testWorkflowSnapshotValidator)
    recorder.openTestWorkflow(workflowId, "session-execution")
    recorder.recordPhaseState(
      FeatureTaskRuntimePhaseStateRequest(
        workflowId = workflowId,
        phaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT,
        status = "completed",
        attemptCount = 1,
        resolvedAgentId = "claude",
        finished = true,
        launchedModel = "claude-opus-5-5",
        launchedEffort = "high",
        launchOutcomeKnown = true,
      ),
    )
    val manifest =
      manifest(1).copy(
        status = "complete",
        subtasks =
          listOf(
            DecompositionSubtask(
              id = 1,
              name = "Subtask 1",
              specPath = ".feature-specs/SKILL-456/spec_subtask_1.md",
              dependencies = emptyList(),
              status = "complete",
              workflowId = workflowId,
              commitSha = "sha-1",
            ),
          ),
      )

    val result =
      requireNotNull(
        testGoalRunnerStatusService(
          manifestStore = InMemoryGoalManifestStore(manifest),
          outcomeStore = RecordingOutcomeStore(),
          phaseRecorder = recorder,
        ).status(GoalRunnerStatusRequest(issueKey = manifest.issueKey, invokedAgentId = "codex")),
      )

    assertEquals(workflowId, result.completedSubtaskPhaseResults.single().execution?.workflowId)
  }
}

private fun phaseRecord(
  phaseId: String,
  startedAt: String,
  agent: String,
) = FeatureTaskRuntimePhaseRecord(
  phaseId = phaseId,
  status = WorkflowStepStatus.COMPLETED,
  attemptCount = 1,
  startedAt = Instant.parse(startedAt),
  firstStartedAt = Instant.parse(startedAt),
  resolvedAgentId = agent,
)
