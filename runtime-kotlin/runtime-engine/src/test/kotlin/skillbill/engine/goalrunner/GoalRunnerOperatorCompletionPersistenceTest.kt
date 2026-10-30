package skillbill.engine.goalrunner

import skillbill.application.TestDecompositionManifestStore
import skillbill.application.testHarnessClock
import skillbill.engine.goalrunner.execution.core.testWorkflowGoalRunnerManifestStore
import skillbill.engine.goalrunner.model.GoalRunnerManifestState
import skillbill.engine.goalrunner.persist.taskRuntimeWorkflowRecord
import skillbill.infrastructure.sqlite.sqliteDatabaseSessionFactory
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.workflow.decomposition.runtime.decompositionRuntime
import skillbill.workflow.model.FeatureTaskWorkflowMode
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class GoalRunnerOperatorCompletionPersistenceTest {
  @Test
  fun `operator completion durably accepts unfinished subtasks and records goal completion`() {
    val root = Files.createTempDirectory("goal-operator-completion")

    fun open() =
      sqliteDatabaseSessionFactory(
        userHome = root,
        dbPathOverride = root.resolve("runtime.db").toString(),
        environment = emptyMap(),
      )
    val database = open()
    database.transaction { unit ->
      unit.workflowStates.saveFeatureTaskWorkflow(taskRuntimeWorkflowRecord("parent"), FeatureTaskWorkflowMode.RUNTIME)
    }
    val store = testWorkflowGoalRunnerManifestStore(database, TestDecompositionManifestStore, testHarnessClock)
    val state =
      store.save(
        GoalRunnerManifestState(
          parentWorkflowId = "parent",
          dbPath = database.resolveDbPath().toString(),
          manifest =
            manifest(2).let { original ->
              original.copy(
                issueKey = "SKILL-446",
                subtasks =
                  original.subtasks.map {
                    if (it.id == 1) {
                      it.copy(status = "blocked", blockedReason = "session limit", lastResumableStep = "review")
                    } else {
                      it
                    }
                  },
              )
            },
          repoRoot = root,
        ),
      )
    val commitSha = "b".repeat(40)

    store.saveOperatorGoalCompletion(state, commitSha, "Finished outside the runtime.", "2026-10-29T10:00:00Z")

    open().read { unit ->
      val manifest = unit.workflowStates.get(WorkflowFamily.TASK_RUNTIME, "parent")!!.decompositionRuntime()!!
      assertEquals("complete", manifest.status)
      assertEquals(0, manifest.currentSubtaskIntent.subtaskId)
      assertEquals(listOf("complete", "complete"), manifest.subtasks.map { it.status })
      assertEquals(listOf(commitSha, commitSha), manifest.subtasks.map { it.commitSha })
      assertEquals(listOf(null, null), manifest.subtasks.map { it.blockedReason })
      val acceptances = unit.goalRunnerControls.outOfBandAcceptances("parent")
      assertEquals(setOf(1, 2), acceptances.keys)
      assertEquals(setOf(commitSha), acceptances.values.map { it.commitSha }.toSet())
      assertEquals("2026-10-29T10:00:00Z", unit.goalRunnerControls.controlState("parent").goalCompletedAt)
    }
  }
}
