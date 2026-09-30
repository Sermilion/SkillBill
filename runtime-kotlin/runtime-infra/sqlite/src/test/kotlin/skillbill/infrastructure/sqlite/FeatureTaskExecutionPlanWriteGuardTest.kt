package skillbill.infrastructure.sqlite

import skillbill.contracts.JsonCodec
import skillbill.contracts.workflow.identity.task.FeatureTaskRuntimeExecutionPlanKeys
import skillbill.error.featuretask.FeatureTaskRuntimeExecutionPlanConflictError
import skillbill.ports.workflow.model.WorkflowStateRecord
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.model.FeatureTaskWorkflowMode.RUNTIME
import skillbill.workflow.model.WorkflowStatus
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class FeatureTaskExecutionPlanWriteGuardTest {
  @Test
  fun `descriptor replacement removal and legacy adoption leave the authoritative row unchanged`() {
    withDatabase { database ->
      val original = row("wftr-plan").copy(artifactsJson = descriptor("definition-1"))
      val legacy = row("wftr-legacy")
      database.transaction { unit ->
        unit.workflowStates.saveFeatureTaskWorkflow(original, RUNTIME)
        unit.workflowStates.saveFeatureTaskWorkflow(legacy, RUNTIME)
      }
      val before = database.read { assertNotNull(it.workflowStates.getFeatureTaskWorkflow(original.workflowId)) }
      val legacyBefore = database.read { assertNotNull(it.workflowStates.getFeatureTaskWorkflow(legacy.workflowId)) }
      listOf(
        before.copy(artifactsJson = descriptor("definition-2")),
        before.copy(artifactsJson = "{}"),
        before.copy(artifactsJson = JsonCodec.mapToJsonString(mapOf(family.entry(null)))),
        legacyBefore.copy(artifactsJson = descriptor("definition-1")),
      ).forEach { proposed ->
        assertFailsWith<FeatureTaskRuntimeExecutionPlanConflictError> {
          database.transaction { unit ->
            unit.workflowStates.saveFeatureTaskWorkflow(
              proposed.copy(workflowStatus = WorkflowStatus.RUNNING.wireValue, currentStepId = "implement"),
              RUNTIME,
            )
          }
        }
        database.read { unit ->
          assertEquals(before, unit.workflowStates.getFeatureTaskWorkflow(original.workflowId))
          assertEquals(legacyBefore, unit.workflowStates.getFeatureTaskWorkflow(legacy.workflowId))
        }
      }
    }
  }

  @Test
  fun `identical descriptor can accompany an ordinary state update and survives database reopen`() {
    withDatabase { database ->
      val original = row("wftr-plan").copy(artifactsJson = descriptor("definition-1"))
      database.transaction { it.workflowStates.saveFeatureTaskWorkflow(original, RUNTIME) }
      database.transaction { unit ->
        val stored = assertNotNull(unit.workflowStates.getFeatureTaskWorkflow(original.workflowId))
        unit.workflowStates.saveFeatureTaskWorkflow(stored.copy(currentStepId = "implement"), RUNTIME)
      }
      database.read { unit ->
        val stored = assertNotNull(unit.workflowStates.getFeatureTaskWorkflow(original.workflowId))
        assertEquals("implement", stored.currentStepId)
        assertEquals(original.artifactsJson, stored.artifactsJson)
      }
    }
  }

  @Test
  fun `descriptor conflict rolls back earlier workflow and route identity writes in the transaction`() {
    withDatabase { database ->
      val original = row("wftr-plan").copy(artifactsJson = descriptor("definition-1"))
      database.transaction { it.workflowStates.saveFeatureTaskWorkflow(original, RUNTIME) }
      val before = database.read { assertNotNull(it.workflowStates.getFeatureTaskWorkflow(original.workflowId)) }
      val child = row("wftr-child").copy(artifactsJson = descriptor("definition-1"))
      assertFailsWith<FeatureTaskRuntimeExecutionPlanConflictError> {
        database.transaction { unit ->
          unit.workflowStates.saveFeatureTaskWorkflow(child, RUNTIME)
          unit.workflowStates.saveFeatureTaskExecutionIdentity(goalChildIdentity(child))
          unit.workflowStates.saveFeatureTaskWorkflow(before.copy(artifactsJson = descriptor("definition-2")), RUNTIME)
        }
      }
      database.read { unit ->
        assertNull(unit.workflowStates.getFeatureTaskWorkflow(child.workflowId))
        assertNull(unit.workflowStates.getFeatureTaskExecutionIdentity(child.workflowId))
        assertEquals(before, unit.workflowStates.getFeatureTaskWorkflow(original.workflowId))
      }
    }
  }

  private fun row(id: String): WorkflowStateRecord =
    workflowRow(id, "session-$id", "bill-feature-task", "plan", RUNTIME)
      .copy(workflowStatus = WorkflowStatus.PAUSED.wireValue)

  private fun descriptor(id: String): String =
    JsonCodec.mapToJsonString(
      mapOf(family.entry(mapOf(FeatureTaskRuntimeExecutionPlanKeys.ID to id))),
    )

  private fun withDatabase(block: (SQLiteDatabaseSessionFactory) -> Unit) {
    val directory = Files.createTempDirectory("execution-plan-write")
    try {
      block(
        sqliteDatabaseSessionFactory(
          userHome = directory,
          dbPathOverride = directory.resolve("metrics.db").toString(),
          environment = emptyMap(),
        ),
      )
    } finally {
      directory.toFile().deleteRecursively()
    }
  }

  private val family = DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_EXECUTION_PLAN
}
