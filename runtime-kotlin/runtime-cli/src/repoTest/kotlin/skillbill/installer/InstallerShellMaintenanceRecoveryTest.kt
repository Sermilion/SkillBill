package skillbill.installer

import skillbill.infrastructure.sqlite.pragmaIntegrityCheck
import skillbill.infrastructure.sqlite.readMaintenanceWorkflowSnapshot
import skillbill.infrastructure.sqlite.seedMaintenanceWorkflowHistory
import skillbill.infrastructure.sqlite.sqliteSessionFactoryForTests
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InstallerShellMaintenanceRecoveryTest {
  @Test
  fun `T3-01 pre-install preserve wipe retains wal backed workflow history`() {
    val wipe = seedStateDirForWipe()
    Files.deleteIfExists(wipe.stateDb)
    val snapshot = seedMaintenanceWorkflowHistory(wipe.stateDb)
    val heldWriter = DriverManager.getConnection("jdbc:sqlite:${wipe.stateDb}")
    heldWriter.use { connection ->
      connection.createStatement().use {
        it.executeUpdate(
          "UPDATE feature_task_workflows SET artifacts_json = '{\"issue_key\":\"SKILL-422\",\"open_writer\":true}' " +
            "WHERE workflow_id = '${snapshot.workflowId}'",
        )
      }
      val run = runUninstaller(wipe, preserveSource = true, goalContinuation = false)
      assertEquals(0, run.exitCode, run.output)
      assertFalse(Files.exists(wipe.runtimeBin))
      assertTrue(Files.isRegularFile(wipe.stateDb))
      assertTrue(Files.exists(wipe.stateDb.resolveSibling("review-metrics.db-wal")))
    }
    assertEquals("ok", pragmaIntegrityCheck(wipe.stateDb))
    assertEquals(snapshot, readMaintenanceWorkflowSnapshot(wipe.stateDb, snapshot))
    val factory =
      sqliteSessionFactoryForTests(
        userHome = wipe.home,
        environment = emptyMap(),
      )
    factory.read { unitOfWork ->
      val row = unitOfWork.workflowStates.getFeatureTaskWorkflow(snapshot.workflowId)
      assertTrue(row?.artifactsJson?.contains("open_writer") == true)
    }
  }

  @Test
  fun `T3-04 explicit shell uninstall removes copied-in source but keeps database history`() {
    val wipe = seedStateDirForWipe()
    Files.deleteIfExists(wipe.stateDb)
    val snapshot = seedMaintenanceWorkflowHistory(wipe.stateDb)
    val preserved = runUninstaller(wipe, preserveSource = true, goalContinuation = false)
    assertEquals(0, preserved.exitCode, preserved.output)
    assertTrue(Files.isRegularFile(wipe.skillContent))

    val explicit = runUninstaller(wipe, preserveSource = false, goalContinuation = false)
    assertEquals(0, explicit.exitCode, explicit.output)
    assertFalse(Files.exists(wipe.skillContent))
    assertTrue(Files.isRegularFile(wipe.stateDb))
    assertEquals("ok", pragmaIntegrityCheck(wipe.stateDb))
    assertEquals(snapshot, readMaintenanceWorkflowSnapshot(wipe.stateDb, snapshot))
  }
}
