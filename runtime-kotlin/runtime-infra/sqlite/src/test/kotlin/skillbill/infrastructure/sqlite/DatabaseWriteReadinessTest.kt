package skillbill.infrastructure.sqlite

import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import skillbill.error.core.DatabaseAccessOperation
import skillbill.error.core.DatabaseFailureCode
import skillbill.error.core.SkillBillRuntimeException
import skillbill.infrastructure.sqlite.core.migration.DatabaseMigration
import skillbill.infrastructure.sqlite.core.migration.DatabaseMigrations
import skillbill.infrastructure.sqlite.core.schema.DatabaseIdentity
import skillbill.infrastructure.sqlite.core.schema.DatabaseRuntime
import skillbill.infrastructure.sqlite.core.schema.DatabaseWriteReadinessGate
import skillbill.infrastructure.sqlite.core.schema.databaseFileIdentity
import skillbill.ports.workflow.model.WorkflowStateRecord
import skillbill.workflow.model.FeatureTaskWorkflowMode.RUNTIME
import skillbill.workflow.model.WorkflowStatus
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private class CountingReadinessGate(private val onEstablish: () -> Unit) {
  private val gate = DatabaseWriteReadinessGate()

  fun ensureReady(dbPath: Path) {
    gate.ensureReady(dbPath) {
      onEstablish()
      DatabaseRuntime.establishSchemaReadiness(dbPath.toAbsolutePath().normalize())
    }
  }
}

@Execution(ExecutionMode.SAME_THREAD)
class DatabaseWriteReadinessTest {
  @Test
  fun `file identity falls back to creation time when the filesystem has no file key`() {
    val creationTime = FileTime.fromMillis(1234)
    val attributes =
      object : BasicFileAttributes {
        override fun lastModifiedTime() = creationTime

        override fun lastAccessTime() = creationTime

        override fun creationTime() = creationTime

        override fun isRegularFile() = true

        override fun isDirectory() = false

        override fun isSymbolicLink() = false

        override fun isOther() = false

        override fun size() = 0L

        override fun fileKey(): Any? = null
      }

    assertEquals("creation-time:1234", databaseFileIdentity(attributes))
  }

  @Test
  fun `warm readiness cache avoids duplicate schema establishment work`() {
    var executions = 0
    val gate = CountingReadinessGate { executions += 1 }
    val tempDir = Files.createTempDirectory("skillbill-write-readiness-gate-warm")
    val dbPath = tempDir.resolve("metrics.db")
    gate.ensureReady(dbPath)
    val afterFirst = executions
    gate.ensureReady(dbPath)
    assertEquals(afterFirst, executions)
  }

  @Test
  fun `repeated write acquisitions succeed on a warmed database`() {
    val tempDir = Files.createTempDirectory("skillbill-write-readiness")
    val dbPath = tempDir.resolve("metrics.db")
    val database =
      sqliteSessionFactoryForTests(
        userHome = tempDir,
        dbPathOverride = dbPath.toString(),
        environment = emptyMap(),
      )

    database.transaction { unitOfWork ->
      unitOfWork.workflowStates.saveFeatureTaskWorkflow(sampleWorkflow("wftr-readiness-1"), RUNTIME)
    }
    database.transaction { unitOfWork ->
      unitOfWork.workflowStates.saveFeatureTaskWorkflow(sampleWorkflow("wftr-readiness-2"), RUNTIME)
    }
    database.selfManagedWrite { unitOfWork ->
      assertNotNull(unitOfWork.workflowStates.getFeatureTaskWorkflowAsMode("wftr-readiness-1", RUNTIME))
    }

    relocateDatabaseFromPath(dbPath, tempDir.resolve("metrics-archived.db"))
    database.transaction { unitOfWork ->
      unitOfWork.workflowStates.saveFeatureTaskWorkflow(sampleWorkflow("wftr-readiness-3"), RUNTIME)
    }
    assertNotNull(
      database.read { it.workflowStates.getFeatureTaskWorkflowAsMode("wftr-readiness-3", RUNTIME) },
    )
  }

  @Test
  fun `truncating database file at the same path forces schema re-establishment`() {
    var executions = 0
    val gate = CountingReadinessGate { executions += 1 }
    val tempDir = Files.createTempDirectory("skillbill-write-readiness-truncate")
    val dbPath = tempDir.resolve("metrics.db")
    gate.ensureReady(dbPath)
    val afterInit = executions
    Files.write(dbPath, ByteArray(0))
    gate.ensureReady(dbPath)
    assertTrue(executions > afterInit)
  }

  @Test
  fun `appending a migration changes identity and re-establishes readiness once`() {
    var executions = 0
    val gate = CountingReadinessGate { executions += 1 }
    val tempDir = Files.createTempDirectory("skillbill-write-readiness-version")
    val dbPath = tempDir.resolve("metrics.db")
    gate.ensureReady(dbPath)
    val afterInit = executions
    val initialIdentity = assertNotNull(DatabaseIdentity.read(dbPath))
    val appendedMigration =
      DatabaseMigration(
        version = DatabaseMigrations.migrations.maxOf { migration -> migration.version } + 1,
        name = "test-only-appended-readiness-migration",
      ) {}

    DriverManager.getConnection("jdbc:sqlite:$dbPath").use { connection ->
      DatabaseMigrations.apply(
        connection,
        SqliteTestDiagnostics,
        migrationSet = DatabaseMigrations.migrations + appendedMigration,
      )
    }

    val appendedIdentity = assertNotNull(DatabaseIdentity.read(dbPath))
    assertTrue(appendedIdentity.userVersion > initialIdentity.userVersion)
    gate.ensureReady(dbPath)
    assertEquals(afterInit + 1, executions)
    gate.ensureReady(dbPath)
    assertEquals(afterInit + 1, executions)
  }

  @Test
  fun `unreadable database file surfaces read CORRUPT database failure from ensureReady`() {
    val tempDir = Files.createTempDirectory("skillbill-write-readiness-unreadable")
    val dbPath = tempDir.resolve("metrics.db")
    Files.writeString(dbPath, "not-a-sqlite-database")
    var establishments = 0
    val gate = CountingReadinessGate { establishments += 1 }

    val error =
      assertFailsWith<SkillBillRuntimeException> {
        gate.ensureReady(dbPath)
      }
    assertEquals(DatabaseFailureCode.CORRUPT, error.code)
    assertTrue(error.message.orEmpty().startsWith("Database ${DatabaseAccessOperation.READ.wireValue} failed for '"))
    assertEquals(0, establishments)
  }

  @Test
  fun `failed readiness is not published and recovery retries initialization`() {
    val tempDir = Files.createTempDirectory("skillbill-write-readiness-failure")
    val invalidPath = tempDir.resolve("metrics.db")
    Files.createDirectory(invalidPath)
    val database =
      sqliteSessionFactoryForTests(
        userHome = tempDir,
        dbPathOverride = invalidPath.toString(),
        environment = emptyMap(),
      )

    val failure =
      assertFailsWith<SkillBillRuntimeException> {
        database.transaction { Unit }
      }
    assertEquals(DatabaseFailureCode.ACCESS, failure.code)

    Files.delete(invalidPath)
    database.transaction { unitOfWork ->
      unitOfWork.workflowStates.saveFeatureTaskWorkflow(sampleWorkflow("wftr-recovered"), RUNTIME)
    }
    assertNotNull(
      database.read { it.workflowStates.getFeatureTaskWorkflowAsMode("wftr-recovered", RUNTIME) },
    )
  }

  private fun relocateDatabaseFromPath(
    dbPath: Path,
    archivePath: Path,
  ) {
    Files.deleteIfExists(archivePath)
    Files.move(dbPath, archivePath, StandardCopyOption.ATOMIC_MOVE)
  }

  private fun sampleWorkflow(workflowId: String): WorkflowStateRecord =
    WorkflowStateRecord(
      workflowId = workflowId,
      sessionId = "session-$workflowId",
      workflowName = "bill-feature-task",
      contractVersion = "0.3",
      workflowStatus = WorkflowStatus.PENDING.wireValue,
      currentStepId = "plan",
      stepsJson = "[]",
      artifactsJson = "{}",
      issueKey = "SKILL-356",
      startedAt = "2026-09-18T00:00:00Z",
      updatedAt = "2026-09-18T00:00:00Z",
      finishedAt = null,
      mode = RUNTIME,
    )
}
