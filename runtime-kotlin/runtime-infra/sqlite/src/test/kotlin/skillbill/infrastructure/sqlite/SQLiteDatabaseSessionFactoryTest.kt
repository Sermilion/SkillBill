package skillbill.infrastructure.sqlite

import org.sqlite.SQLiteErrorCode
import org.sqlite.SQLiteException
import skillbill.error.core.DatabaseAccessOperation
import skillbill.error.core.DatabaseFailureCode
import skillbill.error.core.SkillBillRuntimeException
import skillbill.ports.WorkBoardReadModelProjector
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerLeaseState
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerOwnership
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.workflow.model.WorkflowStateRecord
import skillbill.workflow.model.FeatureTaskWorkflowMode.RUNTIME
import skillbill.workflow.model.WorkflowStatus
import skillbill.workflow.model.workflowStatus
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SQLiteDatabaseSessionFactoryTest {
  @Test
  fun `cancelled self-managed projection invalidates the read model without replaying committed writes`() {
    val tempDir = Files.createTempDirectory("skillbill-projection-cancellation")
    val cancellation = CancellationException("projection cancelled")
    val projector =
      object : WorkBoardReadModelProjector {
        override fun refresh(
          unitOfWork: UnitOfWork,
          workIds: Set<String>,
          now: Instant,
        ) {
          throw cancellation
        }

        override fun rebuild(
          unitOfWork: UnitOfWork,
          now: Instant,
        ) {
          unitOfWork.workBoardReadModel.setComplete(true)
        }
      }
    val database =
      sqliteDatabaseSessionFactory(
        userHome = tempDir,
        dbPathOverride = tempDir.resolve("metrics.db").toString(),
        environment = emptyMap(),
        boardReadModelProjector = projector,
      )
    database.transaction { }
    assertTrue(database.read { it.workBoardReadModel.isComplete() })
    var writes = 0

    val failure =
      assertFailsWith<CancellationException> {
        database.selfManagedWrite {
          writes++
          it.workflowStates.saveFeatureTaskWorkflow(workflowRecord("wftr-projection-cancellation"), RUNTIME)
        }
      }

    assertSame(cancellation, failure)
    assertEquals(1, writes)
    database.read {
      assertFalse(it.workBoardReadModel.isComplete())
      assertEquals("running", it.workflowStates.getFeatureTaskWorkflow("wftr-projection-cancellation")?.workflowStatus)
    }
    database.transaction { }
    assertTrue(database.read { it.workBoardReadModel.isComplete() })
  }

  @Test
  fun `read cancellation propagates and releases its transaction connection`() {
    val tempDir = Files.createTempDirectory("skillbill-read-cancellation")
    val database = boundDatabase(tempDir, tempDir.resolve("metrics.db"))
    val cancellation = CancellationException("cancelled")

    assertSame(cancellation, assertFailsWith<CancellationException> { database.read { throw cancellation } })

    database.transaction { unitOfWork ->
      unitOfWork.workflowStates.saveFeatureTaskWorkflow(workflowRecord("wftr-read-cancellation"), RUNTIME)
    }
    assertEquals(
      "running",
      database.read {
        it.workflowStates.getFeatureTaskWorkflowAsMode("wftr-read-cancellation", RUNTIME)?.workflowStatus
      },
    )
  }

  @Test
  fun `nested control writes share the outer transaction and roll back together`() {
    val tempDir = Files.createTempDirectory("skillbill-nested-write")
    val database = boundDatabase(tempDir, tempDir.resolve("metrics.db"))
    val initial = workflowRecord("wftr-nested-write")
    database.transaction { it.workflowStates.saveFeatureTaskWorkflow(initial, RUNTIME) }
    assertFailsWith<IllegalStateException> {
      database.transaction { outer ->
        database.selfManagedWrite { nested ->
          assertTrue(outer === nested)
          nested.workflowStates.saveFeatureTaskWorkflow(initial.copy(workflowStatus = "completed"), RUNTIME)
        }
        database.transaction { nested ->
          assertTrue(outer === nested)
          assertEquals(
            "completed",
            database.read {
              it.workflowStates.getFeatureTaskWorkflow(initial.workflowId)?.workflowStatus
            },
          )
          error("reject the coupled control")
        }
      }
    }
    assertEquals(
      "running",
      database.read { it.workflowStates.getFeatureTaskWorkflow(initial.workflowId)?.workflowStatus },
    )
    database.transaction {
      it.workflowStates.saveFeatureTaskWorkflow(
        initial.copy(workflowStatus = "completed"),
        RUNTIME,
      )
    }
    assertEquals(
      "completed",
      database.read {
        it.workflowStates.getFeatureTaskWorkflow(initial.workflowId)?.workflowStatus
      },
    )
  }

  @Test
  fun `nested reads share a snapshot and release it after failure`() {
    val tempDir = Files.createTempDirectory("skillbill-nested-read")
    val database = boundDatabase(tempDir, tempDir.resolve("metrics.db"))
    val workflowId = "wftr-nested-read"
    val initial = workflowRecord(workflowId)
    database.transaction { it.workflowStates.saveFeatureTaskWorkflow(initial, RUNTIME) }

    assertFailsWith<IllegalStateException> {
      database.read { outer ->
        assertEquals("running", outer.workflowStates.getFeatureTaskWorkflow(workflowId)?.workflowStatus)
        database.transaction {
          it.workflowStates.saveFeatureTaskWorkflow(initial.copy(workflowStatus = "completed"), RUNTIME)
        }
        database.read { nested ->
          assertEquals("running", nested.workflowStates.getFeatureTaskWorkflow(workflowId)?.workflowStatus)
        }
        database.readIfPresent { nested ->
          assertEquals("running", nested.workflowStates.getFeatureTaskWorkflow(workflowId)?.workflowStatus)
        }
        error("end read")
      }
    }

    assertEquals("completed", database.read { it.workflowStates.getFeatureTaskWorkflow(workflowId)?.workflowStatus })
  }

  @Test
  fun `transaction maps write SQLException to ACCESS database failure`() {
    val tempDir = Files.createTempDirectory("skillbill-write-sql")
    val dbPath = tempDir.resolve("metrics.db")
    val database = boundDatabase(tempDir, dbPath)

    val error =
      assertFailsWith<SkillBillRuntimeException> {
        database.transaction { unitOfWork ->
          val connection =
            unitOfWork::class.java.getDeclaredField("connection").apply { isAccessible = true }
              .get(unitOfWork) as Connection
          connection.createStatement().use {
            it.execute("INSERT INTO missing_write_probe_table VALUES (1)")
          }
        }
      }
    assertEquals(DatabaseFailureCode.ACCESS, error.code)
    assertTrue(error.message.orEmpty().startsWith("Database ${DatabaseAccessOperation.WRITE.wireValue} failed for '"))
  }

  @Test
  fun `self-managed write retries a busy failure and does not retry other failures`() {
    val tempDir = Files.createTempDirectory("skillbill-self-managed-busy")
    val database = boundDatabase(tempDir, tempDir.resolve("metrics.db"))
    var busyCalls = 0

    val result =
      database.selfManagedWrite {
        busyCalls += 1
        if (busyCalls == 1) {
          throw SQLiteException(
            "[SQLITE_BUSY] The database file is locked (database is locked)",
            SQLiteErrorCode.SQLITE_BUSY,
          )
        }
        "written"
      }

    var otherCalls = 0
    val error =
      assertFailsWith<IllegalStateException> {
        database.selfManagedWrite {
          otherCalls += 1
          error("constraint violated")
        }
      }
    assertEquals("written", result)
    assertEquals(2, busyCalls)
    assertEquals(1, otherCalls)
    assertEquals("constraint violated", error.message)
  }

  @Test
  fun `repository SQLException inside transaction is typed and rolls back prior repository writes`() {
    val tempDir = Files.createTempDirectory("skillbill-repository-write-sql")
    val dbPath = tempDir.resolve("metrics.db")
    val database = boundDatabase(tempDir, dbPath)
    val workflowId = "wftr-repository-rollback"

    val error =
      assertFailsWith<SkillBillRuntimeException> {
        database.transaction { unitOfWork ->
          unitOfWork.workflowStates.saveFeatureTaskWorkflow(workflowRecord(workflowId), RUNTIME)
          val connection =
            unitOfWork::class.java.getDeclaredField("connection").apply { isAccessible = true }
              .get(unitOfWork) as Connection
          connection.createStatement().use { statement ->
            statement.execute("DROP TABLE feature_task_workflows")
          }
          unitOfWork.workflowStates.getFeatureTaskWorkflowAsMode(workflowId, RUNTIME)
        }
      }

    assertEquals(DatabaseFailureCode.ACCESS, error.code)
    assertTrue(error.message.orEmpty().startsWith("Database ${DatabaseAccessOperation.WRITE.wireValue} failed for '"))
    assertEquals(
      null,
      database.read { it.workflowStates.getFeatureTaskWorkflowAsMode(workflowId, RUNTIME)?.workflowStatus },
    )
  }

  @Test
  fun `transaction rolls back repository writes when the use case fails`() {
    val tempDir = Files.createTempDirectory("skillbill-sqlite-session")
    val dbPath = tempDir.resolve("metrics.db")
    val database = boundDatabase(tempDir, dbPath)

    assertFailsWith<IllegalStateException> {
      database.transaction { unitOfWork ->
        unitOfWork.workflowStates.saveFeatureTaskWorkflow(
          WorkflowStateRecord(
            workflowId = "wftr-rollback",
            sessionId = "ftr-rollback",
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
          ),
          RUNTIME,
        )
        error("force rollback")
      }
    }

    val saved =
      database.read { unitOfWork ->
        unitOfWork.workflowStates.getFeatureTaskWorkflowAsMode("wftr-rollback", RUNTIME)
      }
    assertNull(saved)
  }

  @Test
  fun `resolve path and existence are provided by the database session factory`() {
    val tempDir = Files.createTempDirectory("skillbill-sqlite-session-path")
    val dbPath = tempDir.resolve("metrics.db")
    val database = boundDatabase(tempDir, dbPath)

    assertEquals(dbPath.toAbsolutePath().normalize(), database.resolveDbPath())
    assertEquals(false, database.databaseExists())
    assertNull(database.readIfPresent { Unit })
    assertFalse(database.databaseExists())
    database.read { Unit }
    assertTrue(database.databaseExists())
  }

  @Test
  fun `bound context without override selects the default database path`() {
    val tempDir = Files.createTempDirectory("skillbill-sqlite-default-path")
    val database = sqliteDatabaseSessionFactory(userHome = tempDir, environment = emptyMap())

    assertEquals(
      tempDir.resolve(".skill-bill/review-metrics.db").toAbsolutePath().normalize(),
      database.resolveDbPath(),
    )
  }

  @Test
  fun `read if present does not create an absent database`() {
    val tempDir = Files.createTempDirectory("skillbill-sqlite-read-if-present")
    val dbPath = tempDir.resolve("metrics.db")
    val database = boundDatabase(tempDir, dbPath)

    assertNull(database.readIfPresent { Unit })
    assertFalse(Files.exists(dbPath))
  }

  @Test
  fun `read if present rejects an existing schemaless database`() {
    val tempDir = Files.createTempDirectory("skillbill-sqlite-read-if-present-schemaless")
    val dbPath = Files.createFile(tempDir.resolve("metrics.db"))
    val database = boundDatabase(tempDir, dbPath)

    val failure =
      assertFailsWith<SkillBillRuntimeException> {
        database.readIfPresent { Unit }
      }
    assertEquals(DatabaseFailureCode.INCOMPATIBLE_SCHEMA, failure.code)
    assertEquals(0L, Files.size(dbPath))
  }

  @Test
  fun `read opens an existing database while another connection holds the writer lock`() {
    val tempDir = Files.createTempDirectory("skillbill-sqlite-read-contention")
    val dbPath = tempDir.resolve("metrics.db")
    val database = boundDatabase(tempDir, dbPath)
    val workflowId = "wftr-read-contention"
    database.transaction { unitOfWork ->
      unitOfWork.workflowStates.saveFeatureTaskWorkflow(workflowRecord(workflowId), RUNTIME)
    }

    DriverManager.getConnection("jdbc:sqlite:$dbPath").use { writer ->
      writer.createStatement().use { it.execute("BEGIN IMMEDIATE") }
      try {
        val status =
          database.read { unitOfWork ->
            unitOfWork.workflowStates.getFeatureTaskWorkflowAsMode(workflowId, RUNTIME)?.workflowStatus
          }
        assertEquals("running", status)
      } finally {
        writer.createStatement().use { it.execute("ROLLBACK") }
      }
    }
  }

  @Test
  fun `the read seam hands out a connection without write capability`() {
    val tempDir = Files.createTempDirectory("skillbill-sqlite-read-only-open")
    val dbPath = tempDir.resolve("metrics.db")
    val database = boundDatabase(tempDir, dbPath)
    database.transaction { unitOfWork ->
      unitOfWork.workflowStates.saveFeatureTaskWorkflow(workflowRecord("wftr-read-only"), RUNTIME)
    }

    DatabaseRuntime.openReadDb(cliValue = dbPath.toString(), environment = emptyMap(), userHome = tempDir)
      .use { openDb ->
        assertEquals(
          "running",
          workflowStatus(openDb.connection, "wftr-read-only"),
          "A read-only open must still serve queries against a WAL database closed by its writer.",
        )
        assertFailsWith<SQLException>("The read seam must not hand out a write-capable connection.") {
          openDb.connection.createStatement().use {
            it.executeUpdate("UPDATE feature_task_workflows SET workflow_status = 'tampered'")
          }
        }
        assertFailsWith<SQLException>("The read seam must not permit schema mutation.") {
          openDb.connection.createStatement().use { it.execute("CREATE TABLE read_path_probe (x TEXT)") }
        }
      }

    DriverManager.getConnection("jdbc:sqlite:$dbPath").use { connection ->
      assertEquals("running", workflowStatus(connection, "wftr-read-only"))
      assertFalse(tableExists(connection, "read_path_probe"), "The read seam must leave no schema behind.")
    }
  }

  @Test
  fun `two statements in one read block observe one snapshot across a writer commit`() {
    val tempDir = Files.createTempDirectory("skillbill-sqlite-read-snapshot")
    val dbPath = tempDir.resolve("metrics.db")
    val database = boundDatabase(tempDir, dbPath)
    val workflowId = "wftr-read-snapshot"
    database.transaction { unitOfWork ->
      unitOfWork.workflowStates.saveFeatureTaskWorkflow(workflowRecord(workflowId), RUNTIME)
    }

    val observed =
      database.read { unitOfWork ->
        val before = unitOfWork.workflowStates.getFeatureTaskWorkflowAsMode(workflowId, RUNTIME)?.workflowStatus
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { writer ->
          writer.createStatement().use { it.execute("PRAGMA busy_timeout = 5000") }
          writer.prepareStatement("UPDATE feature_task_workflows SET workflow_status = ? WHERE workflow_id = ?")
            .use { statement ->
              statement.setString(1, "complete")
              statement.setString(2, workflowId)
              assertEquals(1, statement.executeUpdate())
            }
        }
        before to unitOfWork.workflowStates.getFeatureTaskWorkflowAsMode(workflowId, RUNTIME)?.workflowStatus
      }

    assertEquals("running" to "running", observed, "A read block must not observe a writer commit landing inside it.")
    assertEquals(
      "complete",
      database.read {
        it.workflowStates.getFeatureTaskWorkflowAsMode(workflowId, RUNTIME)?.workflowStatus
      },
      "The next read block must start from a fresh snapshot carrying the committed write.",
    )
  }

  @Test
  fun `read path databases stay in wal mode so a held snapshot never blocks a writer`() {
    val tempDir = Files.createTempDirectory("skillbill-sqlite-read-journal-mode")
    val dbPath = tempDir.resolve("metrics.db")
    val database = boundDatabase(tempDir, dbPath)
    database.transaction { unitOfWork ->
      unitOfWork.workflowStates.saveFeatureTaskWorkflow(workflowRecord("wftr-journal-mode"), RUNTIME)
    }

    DatabaseRuntime.openReadDb(cliValue = dbPath.toString(), environment = emptyMap(), userHome = tempDir)
      .use { openDb -> assertEquals("wal", journalMode(openDb.connection)) }
  }

  @Test
  fun `crash reconcile write composes inside a real database transaction without nesting`() {
    val tempDir = Files.createTempDirectory("skillbill-sqlite-crash-reconcile")
    val dbPath = tempDir.resolve("metrics.db")
    val database = boundDatabase(tempDir, dbPath)
    val workflowId = "wftr-crash-reconcile"

    database.transaction { it.workflowStates.saveFeatureTaskWorkflow(runtimeRow(workflowId), RUNTIME) }
    val updatedAt =
      database.read {
        it.workflowStates.getFeatureTaskWorkflowAsMode(workflowId, RUNTIME)?.updatedAt
      }
    val ownership = expiredOwnership(workflowId)
    database.transaction {
      assertTrue(it.workflowStates.acquireFeatureTaskRuntimeWorker(ownership, updatedAt))
    }
    assertEquals(ownership, database.read { it.workflowStates.getFeatureTaskRuntimeWorkerOwnership(workflowId) })

    val reconciled =
      database.transaction {
        it.workflowStates.reconcileFeatureTaskRuntimeCrashedWorker(
          workflowId = workflowId,
          ownerToken = ownership.ownerToken,
          generation = ownership.generation,
          interruptionReason = "lease_expired: worker lease expired and process confirmed dead",
          nowInstant = "2999-01-01T00:00:00Z",
        )
      }

    assertTrue(reconciled)
    database.read {
      assertEquals("pending", it.workflowStates.getFeatureTaskWorkflowAsMode(workflowId, RUNTIME)?.workflowStatus)
      assertNull(it.workflowStates.getFeatureTaskRuntimeWorkerOwnership(workflowId))
    }
  }

  @Test
  fun `worker acquisition rolls back its workflow advance and lease with the enclosing admission transaction`() {
    val root = Files.createTempDirectory("skillbill-sqlite-worker-admission-rollback")
    val database = boundDatabase(root, root.resolve("metrics.db"))
    val workflowId = "wftr-admission-rollback"
    database.transaction {
      it.workflowStates.saveFeatureTaskWorkflow(runtimeRow(workflowId).copy(workflowStatus = "pending"), RUNTIME)
    }
    val before = database.read { requireNotNull(it.workflowStates.getFeatureTaskWorkflow(workflowId)) }
    assertFailsWith<IllegalStateException> {
      database.transaction {
        assertTrue(it.workflowStates.acquireFeatureTaskRuntimeWorker(expiredOwnership(workflowId), before.updatedAt))
        error("Admission aborted after acquisition")
      }
    }
    database.read {
      assertEquals(before, it.workflowStates.getFeatureTaskWorkflow(workflowId))
      assertNull(it.workflowStates.getFeatureTaskRuntimeWorkerOwnership(workflowId))
    }
  }

  private fun runtimeRow(workflowId: String) =
    WorkflowStateRecord(
      workflowId = workflowId,
      sessionId = "ftr-crash-reconcile",
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

  private fun expiredOwnership(workflowId: String) =
    FeatureTaskRuntimeWorkerOwnership(
      workflowId = workflowId,
      generation = 1,
      ownerToken = "owner-token-crash0001",
      hostIdentity = "host",
      bootIdentity = "boot",
      pid = 4242,
      processBirthToken = "birth-4242",
      leaseState = FeatureTaskRuntimeWorkerLeaseState.ACTIVE,
      heartbeatAt = "2000-01-01T00:00:00Z",
      expiresAt = "2000-01-01T00:00:30Z",
      phaseId = "implement",
      phaseAttempt = 1,
    )

  private fun journalMode(connection: Connection): String? =
    connection.createStatement().use { statement ->
      statement.executeQuery("PRAGMA journal_mode").use { rows ->
        if (rows.next()) rows.getString(1)?.lowercase() else null
      }
    }

  private fun boundDatabase(
    tempDir: Path,
    dbPath: Path,
  ): SQLiteDatabaseSessionFactory =
    sqliteDatabaseSessionFactory(userHome = tempDir, dbPathOverride = dbPath.toString(), environment = emptyMap())

  private fun workflowStatus(
    connection: Connection,
    workflowId: String,
  ): String? =
    connection
      .prepareStatement("SELECT workflow_status FROM feature_task_workflows WHERE workflow_id = ?")
      .use { statement ->
        statement.setString(1, workflowId)
        statement.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
      }

  private fun tableExists(
    connection: Connection,
    table: String,
  ): Boolean =
    connection
      .prepareStatement("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?")
      .use { statement ->
        statement.setString(1, table)
        statement.executeQuery().use { rows -> rows.next() }
      }

  private fun workflowRecord(workflowId: String) =
    WorkflowStateRecord(
      workflowId = workflowId,
      sessionId = "ftr-write-reservation",
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
