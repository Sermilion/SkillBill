package skillbill.infrastructure.sqlite

import skillbill.contracts.JsonCodec
import skillbill.infrastructure.sqlite.worklist.SQLiteWorkListRepository
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.workflow.decomposition.encodeDecompositionManifestWireMap
import skillbill.workflow.decomposition.model.CurrentSubtaskIntent
import skillbill.workflow.decomposition.model.DecompositionDependency
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.decomposition.model.DecompositionSubtask
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WorkGoalSubtaskPageTest {
  @Test
  fun `goal detail returns only the requested subtask window and filters validation before paging`() {
    val path = Files.createTempDirectory("goal-subtask-pages").resolve("metrics.db")
    val artifacts = fixtureArtifacts()
    DatabaseRuntime.ensureDatabase(path).use { connection ->
      connection.prepareStatement(
        "INSERT INTO feature_task_workflows " +
          "(workflow_id, mode, contract_version, workflow_status, artifacts_json) " +
          "VALUES ('parent', 'runtime', '1.0.0', 'running', ?)",
      ).use { statement ->
        statement.setString(1, artifacts)
        statement.executeUpdate()
      }
      val repository = SQLiteWorkListRepository(connection, NoopRuntimeDiagnostics)
      val first = assertNotNull(repository.goalSubtaskPage("parent", 0, 10))
      assertEquals((1..10).toList(), first.manifest.subtasks.map { it.id })
      assertEquals(1000, first.totalCount)
      val dependencies = repository.goalDependencyPage("parent", 0, 10)
      assertEquals((1..10).toList(), dependencies.entries.map { it.dependency.subtaskId })
      assertEquals(10, dependencies.nextOffset)
      val dependenciesTail = repository.goalDependencyPage("parent", 990, 10, subtaskId = 1000)
      assertEquals((991..999).toList(), dependenciesTail.entries.map { it.dependency.subtaskId })
      assertEquals(null, dependenciesTail.nextOffset)
      assertEquals("child-997", first.currentWorkflowId)
      val tail = assertNotNull(repository.goalSubtaskPage("parent", 990, 10))
      assertEquals(10, tail.manifest.subtasks.last().dependencies.size)
      assertEquals(listOf("agent-1"), tail.manifest.subtasks.last().participatingAgentIds)
      assertEquals((991..1000).toList(), tail.manifest.subtasks.map { it.id })
      val validation = assertNotNull(repository.goalSubtaskPage("parent", 10, 10, completedOnly = true))
      assertEquals((22..40 step 2).toList(), validation.manifest.subtasks.map { it.id })
      assertEquals(500, validation.totalCount)
      assertTrue(assertNotNull(repository.goalSubtaskPage("parent", 1000, 10)).manifest.subtasks.isEmpty())
      connection.createStatement().use { statement ->
        statement.executeQuery("SELECT artifacts_json FROM feature_task_workflows WHERE workflow_id = 'parent'")
          .use { rows ->
            assertTrue(rows.next())
            assertEquals(artifacts, rows.getString(1))
          }
      }
    }
  }

  private fun fixtureArtifacts(): String {
    val manifest =
      DecompositionManifest(
        issueKey = "SKILL-423",
        featureName = "Goal detail fixture",
        parentSpecPath = "spec.md",
        baseBranch = "main",
        featureBranch = "feat/SKILL-423",
        currentSubtaskIntent = CurrentSubtaskIntent(997, "start"),
        subtasks =
          (1..1000).map { id ->
            DecompositionSubtask(
              id,
              "Subtask $id",
              "spec_$id.md",
              status = if (id % 2 == 0) "complete" else "pending",
              workflowId = "child-$id",
              dependencies =
                if (id == 1000) {
                  (1..999).map {
                    DecompositionDependency(
                      it,
                      false,
                      false,
                    )
                  }
                } else {
                  emptyList()
                },
              participatingAgentIds = if (id == 1000) (1..1000).map { "agent-$it" } else emptyList(),
            )
          },
      )
    return JsonCodec.mapToJsonString(
      mapOf(
        DurableWorkflowArtifactFamily.DECOMPOSITION_RUNTIME.entry(
          encodeDecompositionManifestWireMap(manifest).toMap(),
        ),
      ),
    )
  }
}
