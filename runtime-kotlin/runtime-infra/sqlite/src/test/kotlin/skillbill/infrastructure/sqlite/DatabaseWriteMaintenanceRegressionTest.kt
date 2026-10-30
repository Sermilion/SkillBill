package skillbill.infrastructure.sqlite

import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import skillbill.ports.workflow.model.WorkflowStateRecord
import skillbill.workflow.model.FeatureTaskWorkflowMode.RUNTIME
import skillbill.workflow.model.WorkflowStatus
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Execution(ExecutionMode.SAME_THREAD)
class DatabaseWriteMaintenanceRegressionTest {
  @Test
  fun `warm and fresh readiness opens avoid pragma table info probes`() {
    val tempDir = Files.createTempDirectory("skillbill-write-maintenance")
    val dbPath = tempDir.resolve("metrics.db")
    val database =
      sqliteDatabaseSessionFactory(
        userHome = tempDir,
        dbPathOverride = dbPath.toString(),
        environment = emptyMap(),
      )

    val delegate = DriverManager.getDriver("jdbc:sqlite:$dbPath")
    val recordingDriver = SqliteConnectionRecordingDriver(delegate)
    DriverManager.deregisterDriver(delegate)
    DriverManager.registerDriver(recordingDriver)
    DriverManager.registerDriver(delegate)
    try {
      database.transaction { unitOfWork ->
        unitOfWork.workflowStates.saveFeatureTaskWorkflow(sampleWorkflow("wftr-maintenance-1"), RUNTIME)
      }
      val before = recordingDriver.categories.toSet()
      recordingDriver.clear()

      database.transaction { unitOfWork ->
        unitOfWork.workflowStates.saveFeatureTaskWorkflow(sampleWorkflow("wftr-maintenance-2"), RUNTIME)
      }
      database.selfManagedWrite { unitOfWork ->
        unitOfWork.workflowStates.saveFeatureTaskWorkflow(sampleWorkflow("wftr-maintenance-3"), RUNTIME)
      }
      val after = recordingDriver.categories.toSet()

      assertTrue(
        before.containsAll(setOf("schema_maintenance", "repair_scan")),
        "before=$before",
      )
      assertFalse("schema_maintenance" in after, "before=$before after=$after")
      assertFalse("repair_scan" in after, "before=$before after=$after")
      assertEquals(0, recordingDriver.pragmaTableInfoQueries)
      assertTrue("connection_pragmas" in after, "before=$before after=$after")
      assertTrue("transaction_control" in after, "before=$before after=$after")
      assertTrue("application_work" in after, "before=$before after=$after")

      recordingDriver.clear()
      DatabaseRuntime.establishSchemaReadiness(dbPath)
      assertEquals(0, recordingDriver.pragmaTableInfoQueries)
    } finally {
      DriverManager.deregisterDriver(recordingDriver)
      DriverManager.deregisterDriver(delegate)
      DriverManager.registerDriver(delegate)
    }
  }

  private fun sampleWorkflow(workflowId: String) =
    WorkflowStateRecord(
      workflowId = workflowId,
      sessionId = "ftr-maintenance",
      workflowName = "bill-feature-task",
      contractVersion = "",
      workflowStatus = WorkflowStatus.RUNNING.wireValue,
      currentStepId = "implement",
      stepsJson = "[]",
      artifactsJson = "{}",
      startedAt = null,
      updatedAt = null,
      finishedAt = null,
      mode = RUNTIME,
    )
}
