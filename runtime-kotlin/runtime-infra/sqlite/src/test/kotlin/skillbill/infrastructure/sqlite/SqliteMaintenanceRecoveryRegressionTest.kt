package skillbill.infrastructure.sqlite

import skillbill.error.core.DatabaseFailureCode
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.WorkflowFailureCode
import skillbill.infrastructure.sqlite.workflow.WorkflowStateStore
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.workflow.model.WorkflowStatus
import java.nio.file.Files
import java.sql.DriverManager
import java.time.Clock
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SqliteMaintenanceRecoveryRegressionTest {
  @Test
  fun `T3-02 aborted nested write preserves committed maintenance rows`() {
    val tempDir = Files.createTempDirectory("skillbill-maint-nested-abort")
    val dbPath = tempDir.resolve("metrics.db")
    val expected = seedMaintenanceWorkflowHistory(dbPath)

    DriverManager.getConnection("jdbc:sqlite:$dbPath").use { connection ->
      assertFailsWith<IllegalStateException> {
        connection.inNestedWriteTransaction {
          createStatement().use {
            it.executeUpdate(
              "UPDATE feature_task_workflows SET current_step_id = 'plan' WHERE workflow_id = '${expected.workflowId}'",
            )
          }
          error("simulated interrupted promotion")
        }
      }
    }

    assertEquals("ok", pragmaIntegrityCheck(dbPath))
    assertEquals(expected, readMaintenanceWorkflowSnapshot(dbPath, expected))
  }

  @Test
  fun `T3-03 unreadable backup retains primary database bytes and surfaces typed access failure`() {
    val userHome = Files.createTempDirectory("skillbill-maint-backup-refusal")
    val dbPath = SqliteTestDatabasePaths.defaultDbPath(userHome)
    Files.createDirectories(dbPath.parent)
    val expected = seedMaintenanceWorkflowHistory(dbPath)
    walCheckpointTruncate(dbPath)
    val primaryBytes = dbPath.readBytes()

    val backupPath = baselineReviewMetricsBackupPath(userHome)
    Files.createDirectories(backupPath.parent)
    backupPath.writeBytes(byteArrayOf(0x00, 0x01, 0x02, 0x03) + primaryBytes)

    val error = attemptBaselineReviewMetricsRestore(userHome)
    assertEquals(DatabaseFailureCode.CORRUPT, error.code)

    assertEquals(primaryBytes.toList(), dbPath.readBytes().toList())
    assertEquals("ok", pragmaIntegrityCheck(dbPath))
    assertEquals(expected, readMaintenanceWorkflowSnapshot(dbPath, expected))
    sqliteSessionFactoryForTests(userHome = userHome, environment = emptyMap()).read { unitOfWork ->
      val row = unitOfWork.workflowStates.getFeatureTaskWorkflow(expected.workflowId)
      require(row != null) { "live database must remain readable after refused baseline restore" }
    }
  }

  @Test
  fun `T3-03 rollback journal recovery reopens exact maintenance rows`() {
    val tempDir = Files.createTempDirectory("skillbill-maint-rollback-journal")
    val dbPath = tempDir.resolve("metrics.db")
    val expected = seedMaintenanceWorkflowHistory(dbPath)

    DriverManager.getConnection("jdbc:sqlite:$dbPath").use { connection ->
      connection.createStatement().use { it.execute("PRAGMA journal_mode = DELETE") }
      connection.autoCommit = false
      connection.createStatement().use {
        it.executeUpdate(
          "UPDATE feature_task_workflows SET workflow_status = '${WorkflowStatus.BLOCKED.wireValue}' " +
            "WHERE workflow_id = '${expected.workflowId}'",
        )
      }
      connection.commit()
    }

    assertEquals("ok", pragmaIntegrityCheck(dbPath))
    val reopened =
      readMaintenanceWorkflowSnapshot(
        dbPath,
        expected.copy(workflowStatus = WorkflowStatus.BLOCKED.wireValue, currentStepId = expected.currentStepId),
      )
    assertEquals(WorkflowStatus.BLOCKED.wireValue, reopened.workflowStatus)
    assertEquals(expected.settlementEnvelopeJson, reopened.settlementEnvelopeJson)
    assertEquals(expected.issueKey, reopened.issueKey)
  }

  @Test
  fun `T3-05 corrupt workflow row preserves database bytes and refuses silent parse recovery`() {
    val tempDir = Files.createTempDirectory("skillbill-maint-corrupt-row")
    val dbPath = tempDir.resolve("metrics.db")
    val expected = seedMaintenanceWorkflowHistory(dbPath)
    walCheckpointTruncate(dbPath)
    val beforeBytes = dbPath.readBytes()

    DriverManager.getConnection("jdbc:sqlite:$dbPath").use { connection ->
      connection.createStatement().use {
        it.executeUpdate(
          "UPDATE feature_task_workflows SET steps_json = '{not json}' WHERE workflow_id = '${expected.workflowId}'",
        )
      }
    }

    val afterCorruptBytes = dbPath.readBytes()
    assertNotEquals(beforeBytes.toList(), afterCorruptBytes.toList())

    DriverManager.getConnection("jdbc:sqlite:$dbPath").use { connection ->
      connection.createStatement().executeQuery(
        "SELECT steps_json FROM feature_task_workflows WHERE workflow_id = '${expected.workflowId}'",
      ).use { rows ->
        assertTrue(rows.next())
        assertEquals("{not json}", rows.getString(1))
      }
      val settlement =
        SqliteFeatureTaskPhaseSettlementStore(connection, Clock.systemUTC(), SqliteTestDiagnostics).find(
          expected.workflowId,
          expected.phaseId,
          expected.phaseAttempt,
        )
      assertEquals(
        """{"status":"completed","produced_outputs":{"value":"maintenance-seed"}}""",
        settlement?.envelopeJson,
      )
    }
    val parseError =
      assertFailsWith<SkillBillRuntimeException> {
        ensureTestDatabase(dbPath).use { connection ->
          WorkflowStateStore(
            connection,
            Clock.systemUTC(),
            SqliteTestWorkflowSnapshotValidator,
            SqliteTestDiagnostics,
          ).get(WorkflowFamily.TASK_RUNTIME, expected.workflowId)
        }
      }
    assertEquals(WorkflowFailureCode.INVALID_WORKFLOW_STATE_SCHEMA, parseError.code)
    assertEquals(afterCorruptBytes.toList(), dbPath.readBytes().toList())
    assertEquals("ok", pragmaIntegrityCheck(dbPath))
  }

  @Test
  fun `T3-02 wal checkpoint after open writer leaves recoverable rows on disk`() {
    val tempDir = Files.createTempDirectory("skillbill-maint-wal-writer")
    val dbPath = tempDir.resolve("metrics.db")
    val expected = seedMaintenanceWorkflowHistory(dbPath)
    DriverManager.getConnection("jdbc:sqlite:$dbPath").use { heldConnection ->
      heldConnection.createStatement().use {
        it.executeUpdate(
          "UPDATE feature_task_workflows SET artifacts_json = '{\"issue_key\":\"SKILL-422\",\"wal\":true}' " +
            "WHERE workflow_id = '${expected.workflowId}'",
        )
      }
      walCheckpointTruncate(dbPath)
    }

    assertEquals("ok", pragmaIntegrityCheck(dbPath))
    val reopened = readMaintenanceWorkflowSnapshot(dbPath, expected)
    assertTrue(reopened.settlementEnvelopeJson.contains("maintenance-seed"))
    val factory =
      sqliteSessionFactoryForTests(
        userHome = tempDir,
        dbPathOverride = dbPath.toString(),
        environment = emptyMap(),
      )
    factory.read { unitOfWork ->
      val row = unitOfWork.workflowStates.getFeatureTaskWorkflow(expected.workflowId)
      assertTrue(row?.artifactsJson?.contains("\"wal\":true") == true)
    }
  }
}
