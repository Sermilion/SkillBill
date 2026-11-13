package skillbill.engine.featuretask.lifecycle.assignment

import skillbill.engine.featuretask.lifecycle.execution.ExecutionPlanAdmissionFixture
import skillbill.engine.featuretask.lifecycle.execution.workflowAgentAssignmentStoreForTests
import skillbill.infrastructure.contracts.workflow.WorkflowAgentAssignmentsSchemaValidator
import skillbill.infrastructure.sqlite.sqliteSessionFactoryForTests
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.taskruntime.executionplan.FeatureTaskRuntimeExecutionPlanValidator
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.DurableWorkflowArtifacts
import java.nio.file.Files
import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class WorkflowAgentAssignmentStoreTest {
  @Test
  fun `cancelled descriptor reads propagate without reclassifying or changing retained routing`() {
    val home = Files.createTempDirectory("routing-cancellation")
    val database = sqliteSessionFactoryForTests(home, home.resolve("state.db").toString(), emptyMap())
    val workflowId = "wftr-cancelled-routing"
    val execution = ExecutionPlanAdmissionFixture(database = database)
    val originalStore = workflowAgentAssignmentStoreForTests(database)
    val artifacts =
      database.transaction { unit ->
        execution.seed(unit.workflowStates, workflowId)
        val snapshot = requireNotNull(unit.workflowStates.get(WorkflowFamily.TASK_RUNTIME, workflowId))
        val routing = originalStore.prepareLegacyRecord(workflowId, snapshot.artifacts, "claude")
        val retained =
          DurableWorkflowArtifacts.fromMap(
            snapshot.artifacts + DurableWorkflowArtifactFamily.WORKFLOW_AGENT_ASSIGNMENTS.entry(routing),
          )
        unit.workflowStates.save(WorkflowFamily.TASK_RUNTIME, snapshot.copy(artifacts = retained))
        retained
      }
    val before = database.read { it.workflowStates.getFeatureTaskWorkflow(workflowId) }
    val cancelled = CancellationException("caller cancelled routing inspection")
    val validator =
      object : FeatureTaskRuntimeExecutionPlanValidator {
        override fun canonicalize(
          encoded: ByteArray,
          sourceLabel: String,
        ): ByteArray = throw cancelled
      }
    val store =
      WorkflowAgentAssignmentStore(
        database,
        WorkflowAgentAssignmentsSchemaValidator(),
        validator,
        NoopRuntimeDiagnostics,
      )

    assertSame(cancelled, assertFailsWith<CancellationException> { store.inspect(workflowId) })
    assertSame(
      cancelled,
      assertFailsWith<CancellationException> { store.prepareLegacyRecord(workflowId, artifacts, "claude") },
    )
    assertEquals(before, database.read { it.workflowStates.getFeatureTaskWorkflow(workflowId) })
  }
}
