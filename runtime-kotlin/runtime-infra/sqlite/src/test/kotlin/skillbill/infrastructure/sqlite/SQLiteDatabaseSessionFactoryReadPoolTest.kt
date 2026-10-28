package skillbill.infrastructure.sqlite

import skillbill.contracts.persistence.DesktopReadSchema
import skillbill.error.core.DatabaseFailureCode
import skillbill.error.core.SkillBillRuntimeException
import skillbill.ports.workflow.model.WorkflowStateRecord
import skillbill.workflow.model.FeatureTaskWorkflowMode.RUNTIME
import skillbill.workflow.model.WorkflowStatus
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.sql.DriverManager
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class SQLiteDatabaseSessionFactoryReadPoolTest {
  @Test
  fun `repeated reads reuse one verified connection`() {
    val tempDir = Files.createTempDirectory("skillbill-read-pool-reuse")
    val dbPath = tempDir.resolve("metrics.db")
    val database = boundDatabase(tempDir, dbPath)
    database.transaction { it.workflowStates.saveFeatureTaskWorkflow(workflowRecord("wftr-pool-reuse"), RUNTIME) }
    val opened = AtomicInteger()
    val normalized = dbPath.toAbsolutePath().normalize().toString()
    val delegate = DriverManager.getDriver("jdbc:sqlite:$dbPath")
    val recordingDriver =
      SqliteConnectionRecordingDriver(delegate) { connection ->
        if (connection.metaData.url.contains(normalized)) opened.incrementAndGet()
      }
    DriverManager.deregisterDriver(delegate)
    DriverManager.registerDriver(recordingDriver)
    DriverManager.registerDriver(delegate)
    try {
      repeat(3) { database.read { it.workflowStates.getFeatureTaskWorkflow("wftr-pool-reuse") } }
      repeat(2) { database.readIfPresent { it.workflowStates.getFeatureTaskWorkflow("wftr-pool-reuse") } }
      assertEquals(1, opened.get())
    } finally {
      DriverManager.deregisterDriver(recordingDriver)
    }
  }

  @Test
  fun `a read after the database file is replaced sees the new file`() {
    val tempDir = Files.createTempDirectory("skillbill-read-pool-replace")
    val dbPath = tempDir.resolve("metrics.db")
    val replacementPath = tempDir.resolve("replacement.db")
    val database = boundDatabase(tempDir, dbPath)
    database.transaction { it.workflowStates.saveFeatureTaskWorkflow(workflowRecord("wftr-original"), RUNTIME) }
    boundDatabase(tempDir, replacementPath).transaction {
      it.workflowStates.saveFeatureTaskWorkflow(workflowRecord("wftr-replacement"), RUNTIME)
    }
    assertNotNull(database.read { it.workflowStates.getFeatureTaskWorkflow("wftr-original") })

    Files.move(replacementPath, dbPath, REPLACE_EXISTING, ATOMIC_MOVE)

    assertNotNull(database.read { it.workflowStates.getFeatureTaskWorkflow("wftr-replacement") })
  }

  @Test
  fun `idle pooled connections do not block a truncating checkpoint`() {
    val tempDir = Files.createTempDirectory("skillbill-read-pool-checkpoint")
    val dbPath = tempDir.resolve("metrics.db")
    val database = boundDatabase(tempDir, dbPath)
    database.transaction { it.workflowStates.saveFeatureTaskWorkflow(workflowRecord("wftr-checkpoint-1"), RUNTIME) }
    database.read { it.workflowStates.getFeatureTaskWorkflow("wftr-checkpoint-1") }
    database.readIfPresent { it.workflowStates.getFeatureTaskWorkflow("wftr-checkpoint-1") }
    database.transaction { it.workflowStates.saveFeatureTaskWorkflow(workflowRecord("wftr-checkpoint-2"), RUNTIME) }

    DriverManager.getConnection("jdbc:sqlite:$dbPath").use { writer ->
      writer.createStatement().use { statement ->
        statement.executeQuery("PRAGMA wal_checkpoint(TRUNCATE)").use { rows ->
          rows.next()
          assertEquals(0, rows.getInt(1))
        }
      }
    }
    assertEquals(0L, Files.size(Path.of("$dbPath-wal")))
  }

  @Test
  fun `a reused connection is reverified after the store generation changes`() {
    val tempDir = Files.createTempDirectory("skillbill-read-pool-generation")
    val dbPath = tempDir.resolve("metrics.db")
    val database = boundDatabase(tempDir, dbPath)
    database.transaction { it.workflowStates.saveFeatureTaskWorkflow(workflowRecord("wftr-generation"), RUNTIME) }
    database.readIfPresent { it.workflowStates.getFeatureTaskWorkflow("wftr-generation") }

    DriverManager.getConnection("jdbc:sqlite:$dbPath").use { writer ->
      writer.createStatement().use { statement ->
        statement.executeUpdate("UPDATE ${DesktopReadSchema.GENERATION_TABLE} SET generation = 'migrated' WHERE id = 1")
        statement.execute("PRAGMA user_version = 9999")
      }
    }

    val failure =
      assertFailsWith<SkillBillRuntimeException> {
        database.readIfPresent { it.workflowStates.getFeatureTaskWorkflow("wftr-generation") }
      }
    assertEquals(DatabaseFailureCode.INCOMPATIBLE_SCHEMA, failure.code)
  }

  @Test
  fun `read still initializes an existing schemaless database`() {
    val tempDir = Files.createTempDirectory("skillbill-read-pool-schemaless")
    val dbPath = Files.createFile(tempDir.resolve("metrics.db"))
    val database = boundDatabase(tempDir, dbPath)

    database.read { Unit }

    assertEquals(Unit, database.readIfPresent { Unit })
  }

  private fun boundDatabase(
    tempDir: Path,
    dbPath: Path,
  ): SQLiteDatabaseSessionFactory =
    sqliteDatabaseSessionFactory(userHome = tempDir, dbPathOverride = dbPath.toString(), environment = emptyMap())

  private fun workflowRecord(workflowId: String) =
    WorkflowStateRecord(
      workflowId = workflowId,
      sessionId = "ftr-read-pool",
      workflowName = "bill-feature-task",
      contractVersion = "1.0",
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
