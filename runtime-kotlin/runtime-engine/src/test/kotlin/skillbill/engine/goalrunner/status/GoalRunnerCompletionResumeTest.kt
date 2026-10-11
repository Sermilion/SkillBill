package skillbill.engine.goalrunner.status

import skillbill.application.TestDecompositionManifestStore
import skillbill.engine.goalrunner.execution.core.testWorkflowGoalRunnerManifestStore
import skillbill.engine.goalrunner.manifest
import skillbill.engine.goalrunner.model.GoalRunnerManifestState
import skillbill.goalrunner.model.GoalRunnerControlState
import skillbill.infrastructure.sqlite.sqliteDatabaseSessionFactory
import skillbill.ports.workflow.model.WorkflowStateRecord
import skillbill.workflow.model.FeatureTaskWorkflowMode
import skillbill.workflow.model.WorkflowStatus
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GoalRunnerCompletionResumeTest {
  @Test
  fun `reopened goal clears historical completion only after execution fencing accepts its owner`() {
    val root = Files.createTempDirectory("goal-completion-resume")
    val database =
      sqliteDatabaseSessionFactory(
        userHome = root,
        dbPathOverride = root.resolve("runtime.db").toString(),
        environment = emptyMap(),
      )
    val workflowId = "parent-resumed"
    database.transaction { unit ->
      unit.workflowStates.saveFeatureTaskWorkflow(
        WorkflowStateRecord(
          workflowId = workflowId,
          sessionId = "session",
          workflowName = "bill-feature-task",
          contractVersion = "0.1",
          workflowStatus = WorkflowStatus.RUNNING.wireValue,
          currentStepId = "implement",
          stepsJson = "[]",
          artifactsJson = "{}",
          startedAt = null,
          updatedAt = null,
          finishedAt = null,
          mode = FeatureTaskWorkflowMode.RUNTIME,
          issueKey = "SKILL-56",
        ),
        FeatureTaskWorkflowMode.RUNTIME,
      )
    }
    val store =
      testWorkflowGoalRunnerManifestStore(
        database,
        TestDecompositionManifestStore,
        Clock.fixed(Instant.parse("2026-08-02T10:00:00Z"), ZoneOffset.UTC),
      )
    store.save(
      GoalRunnerManifestState(
        parentWorkflowId = workflowId,
        dbPath = database.resolveDbPath().toString(),
        manifest = manifest(1),
        repoRoot = root,
      ),
    )
    val completedAt = "2026-08-01T10:00:00Z"
    val completion =
      GoalRunnerControlState(
        goalCompletedAt = completedAt,
        goalPullRequestUrl = "https://github.com/example/repo/pull/1",
        activeDurationMs = 1234,
      )
    store.persistControlState(workflowId, completion)
    val lease = liveLease()

    assertFalse(store.acquireExecutionLease(workflowId, lease, expectedOwnerToken = "different-owner"))
    assertEquals(completion, store.controlState(workflowId))

    assertTrue(store.acquireExecutionLease(workflowId, lease, expectedOwnerToken = null))
    val resumed = store.controlState(workflowId)
    assertNull(resumed.goalCompletedAt)
    assertNull(resumed.goalPullRequestUrl)
    assertEquals(1234, resumed.activeDurationMs)
    assertEquals(lease.ownerToken, resumed.executionLease?.ownerToken)
  }
}
