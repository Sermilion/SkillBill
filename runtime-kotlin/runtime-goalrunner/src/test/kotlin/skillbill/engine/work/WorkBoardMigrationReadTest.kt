package skillbill.engine.work

import skillbill.engine.work.model.IdeStatusRequest
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.invalidWorkListRowError
import skillbill.idestatus.model.IdeStatusLifecycleState
import skillbill.infrastructure.sqlite.SQLiteDatabaseSchema
import skillbill.infrastructure.sqlite.establishTemporarySchemaReadiness
import skillbill.infrastructure.sqlite.sqliteDatabaseSessionFactory
import skillbill.infrastructure.sqlite.workflow.retained.SQLiteRetainedWorkflowReader
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.db.ReadyRuntimeDatabaseReadiness
import skillbill.ports.db.RetainedWorkflowReader
import skillbill.ports.db.RuntimeDatabaseReadiness
import skillbill.ports.db.model.RetainedWorkflowRecord
import skillbill.ports.db.model.RetainedWorkflowSource
import skillbill.ports.db.model.RuntimeDatabaseReadinessResult
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.idestatus.WorkBoardReadModelRepository
import skillbill.ports.idestatus.model.IdeStatusProblemCode
import skillbill.ports.idestatus.model.StandalonePhaseStatusRegistration
import skillbill.ports.idestatus.model.StandalonePhaseStatusUpdate
import skillbill.ports.idestatus.model.StandalonePhaseStatusUpdateResult
import skillbill.ports.idestatus.model.WorkBoardReadModelPage
import skillbill.ports.idestatus.model.WorkBoardReadModelPageQuery
import skillbill.ports.idestatus.model.WorkBoardRequest
import skillbill.ports.idestatus.model.WorkDetailRequest
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.work.model.WorkItemKind
import skillbill.ports.workflow.WorkflowStateRepository
import skillbill.workflow.model.FeatureTaskWorkflowMode
import java.sql.Connection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WorkBoardMigrationReadTest {
  @Test
  fun `status and selection retain corrupt diagnostics without nested readiness`() {
    val repo = gitRepoFixture("criterion-diagnostic-projection")
    val identity = testGoalRepositoryIdentity(repo)
    val workflows = IdeStatusWorkflowStates()
    val row = corruptRuntimeRecord()
    workflows.saveFeatureTaskWorkflow(row, FeatureTaskWorkflowMode.RUNTIME)
    workflows.saveFeatureTaskExecutionIdentity(identityFor(row.workflowId, identity))
    val tracking =
      TrackingDatabase(
        listOf(workItem(row.workflowId, WorkItemKind.FEATURE_TASK_RUNTIME, "running", requireNotNull(row.updatedAt))),
        workflows,
      )
    var readDepth = 0
    var preparations = 0
    val database =
      object : DatabaseSessionFactory by tracking {
        override fun <T> read(block: (UnitOfWork) -> T): T {
          readDepth++
          return try {
            tracking.read(block)
          } finally {
            readDepth--
          }
        }
      }
    val retained =
      RetainedWorkflowRecord(
        row.workflowId,
        row.workflowName,
        row.issueKey,
        identity,
        row.currentStepId,
        requireNotNull(row.updatedAt),
        RetainedWorkflowSource("scratch-store#${row.workflowId}/artifacts_json", row.artifactsJson, row.stepsJson),
      )
    val service =
      ideStatusService(
        database,
        ports =
          IdeStatusServiceTestPorts(
            databaseReadiness =
              object : RuntimeDatabaseReadiness {
                override fun initializeRead(): RuntimeDatabaseReadinessResult = prepareExisting()

                override fun observeRead(): RuntimeDatabaseReadinessResult = RuntimeDatabaseReadinessResult.Ready(true)

                override fun prepareExisting(): RuntimeDatabaseReadinessResult {
                  check(readDepth == 0) { "Readiness entered an active read callback" }
                  preparations++
                  return RuntimeDatabaseReadinessResult.Ready(true)
                }
              },
            retainedReader = singleRetainedReader(retained),
          ),
      )
    val status = service.status(IdeStatusRequest(repo.toString(), observedAt = ideStatusObservedAt)).snapshot
    assertEquals(row.workflowId, status.workflowId)
    assertEquals(IdeStatusLifecycleState.BLOCKED, status.lifecycleState)
    assertEquals(IdeStatusProblemCode.INCOMPATIBLE_RECORD, status.problem?.code)
    assertNotNull(status.problem?.details)
    val board = service.board(WorkBoardRequest(observedAt = ideStatusObservedAt))
    val selected =
      service.projectBoardSelection(
        listOf(row.workflowId),
        WorkBoardRequest(observedAt = ideStatusObservedAt),
      )
    listOf(board, selected).forEach { result ->
      val item = result.items.single()
      assertEquals(row.workflowId, item.snapshot.workflowId)
      assertEquals(IdeStatusLifecycleState.BLOCKED, item.snapshot.lifecycleState)
      assertEquals(retained, assertNotNull(item.migrationDiagnostic).record)
      assertEquals(status.problem?.details, item.snapshot.problem?.details)
      assertFalse(item.controlEligible)
      assertFalse(item.running)
      assertTrue(item.requiresAttention)
    }
    val detail = assertNotNull(service.detail(WorkDetailRequest(row.workflowId, observedAt = ideStatusObservedAt)).item)
    assertEquals(row.workflowId, detail.snapshot.workflowId)
    assertEquals(status.problem?.details, detail.snapshot.problem?.details)
    assertFalse(detail.controlEligible)
    assertEquals(4, preparations)
    assertEquals(0, tracking.writeCalls)
    assertEquals(row, workflows.getFeatureTaskWorkflow(row.workflowId))
  }

  @Test
  fun `status and selected board item retain original source when candidate resolution fails`() {
    val repo = gitRepoFixture("criterion-selection-refusal")
    val identity = testGoalRepositoryIdentity(repo)
    val row = runtimeRecord("retained-selection", "2026-08-06T11:00:00Z")
    val failure = invalidWorkListRowError("Retained identity cannot be projected")
    val workflows =
      object : WorkflowStateRepository by IdeStatusWorkflowStates() {
        override fun getFeatureTaskExecutionIdentity(workflowId: String) = throw failure
      }
    val database =
      TrackingDatabase(
        listOf(workItem(row.workflowId, WorkItemKind.FEATURE_TASK_RUNTIME, "running", requireNotNull(row.updatedAt))),
        workflows,
      )
    val retained =
      RetainedWorkflowRecord(
        row.workflowId,
        row.workflowName,
        row.issueKey,
        identity,
        row.currentStepId,
        requireNotNull(row.updatedAt),
        RetainedWorkflowSource("scratch-store#${row.workflowId}/artifacts_json", row.artifactsJson, row.stepsJson),
        workKind = WorkItemKind.FEATURE_TASK_RUNTIME,
      )
    val service =
      ideStatusService(
        database,
        ports =
          IdeStatusServiceTestPorts(
            databaseReadiness = ReadyRuntimeDatabaseReadiness,
            retainedReader =
              object : RetainedWorkflowReader {
                override fun read(workflowId: String?) = listOf(retained)
              },
          ),
      )
    val selected =
      service.projectBoardSelection(
        listOf(row.workflowId),
        WorkBoardRequest(observedAt = ideStatusObservedAt),
      )
    val item = selected.items.single()
    assertEquals(row.workflowId, item.snapshot.workflowId)
    assertEquals(IdeStatusLifecycleState.BLOCKED, item.snapshot.lifecycleState)
    assertEquals(retained, assertNotNull(item.migrationDiagnostic).record)
    assertEquals(failure.code, item.migrationDiagnostic?.failureCode)
    assertEquals(row.workflowId, selected.diagnostics.single().workflowId)
    assertFalse(item.controlEligible)
    val status = service.status(IdeStatusRequest(repo.toString(), observedAt = ideStatusObservedAt)).snapshot
    assertEquals(row.workflowId, status.workflowId)
    assertEquals(IdeStatusLifecycleState.BLOCKED, status.lifecycleState)
    assertEquals(IdeStatusProblemCode.INCOMPATIBLE_RECORD, status.problem?.code)
    assertEquals(item.snapshot.problem?.details, status.problem?.details)
    assertEquals(0, database.writeCalls)
  }

  @Test
  fun `failed conversion retains blocked board items without cache publication or worker acquisition`() {
    establishTemporarySchemaReadiness("board-criterion-refusal").use { fixture ->
      val source = "  {\"feature_task_runtime_acceptance_criterion_repair\":{\"contract_version\":\"99.0\"}}\n"
      fixture.connection.prepareStatement(
        "INSERT INTO feature_task_workflows (workflow_id, mode, contract_version, artifacts_json, issue_key) " +
          "VALUES (?, 'runtime', '0.4', ?, '0AC-103')",
      ).use { statement ->
        listOf("retained-work", "retained-work-next").forEach { id ->
          statement.setString(1, id)
          statement.setString(2, source)
          statement.executeUpdate()
        }
      }
      fixture.connection.createStatement().use {
        it.executeUpdate("DELETE FROM schema_migrations WHERE version = 118")
        it.execute("PRAGMA user_version = 117")
      }
      val database =
        sqliteDatabaseSessionFactory(
          fixture.tempDir,
          fixture.dbPath.toString(),
          emptyMap(),
          ideStatusClock,
        )
      val schema = SQLiteDatabaseSchema(database, NoopRuntimeDiagnostics)
      val readiness =
        object : RuntimeDatabaseReadiness {
          override fun initializeRead(): RuntimeDatabaseReadinessResult = prepareExisting()

          override fun observeRead(): RuntimeDatabaseReadinessResult =
            error("Failed preparation must return retained records before prepared reads")

          override fun prepareExisting(): RuntimeDatabaseReadinessResult =
            try {
              RuntimeDatabaseReadinessResult.Ready(schema.migrateExistingDatabase())
            } catch (failure: SkillBillRuntimeException) {
              RuntimeDatabaseReadinessResult.Refused(failure)
            }
        }
      val service =
        ideStatusService(
          database,
          ports =
            IdeStatusServiceTestPorts(
              databaseReadiness = readiness,
              retainedReader = SQLiteRetainedWorkflowReader(database, NoopRuntimeDiagnostics),
            ),
        )
      val request = WorkBoardRequest(observedAt = ideStatusObservedAt, pageSize = 1)
      val board = service.board(request)
      val item = board.items.single()
      assertEquals("retained-work", item.snapshot.workflowId)
      assertEquals(IdeStatusLifecycleState.BLOCKED, item.snapshot.lifecycleState)
      assertEquals(source, item.migrationDiagnostic?.record?.source?.artifactsJson)
      assertEquals(
        "ARTIFACT_MIGRATION_REFUSED",
        item.snapshot.problem?.details?.asWirePayload()?.toPayload()?.get("failure_code"),
      )
      assertEquals(false, item.controlEligible)
      val second = service.board(request.copy(cursor = assertNotNull(board.nextCursor)))
      assertEquals("retained-work-next", second.items.single().snapshot.workflowId)
      assertEquals(null, second.nextCursor)
      assertEquals(
        "retained-work-next",
        service.board(request.copy(filter = "NEXT")).items.single().snapshot.workflowId,
      )
      val detail =
        assertNotNull(service.detail(WorkDetailRequest("retained-work", observedAt = ideStatusObservedAt)).item)
      assertEquals("retained-work", detail.snapshot.workflowId)
      assertEquals(item.snapshot.problem?.details, detail.snapshot.problem?.details)
      assertFalse(detail.controlEligible)
      assertEquals(0, boardRowCount(fixture.connection))
      assertRetainedSource(fixture.connection, source)
    }
  }

  @Test
  fun `board reads recover pending projections and preserve post heartbeat order`() {
    establishTemporarySchemaReadiness("board-migration-read").use { fixture ->
      val seed = sqliteDatabaseSessionFactory(fixture.tempDir, fixture.dbPath.toString(), emptyMap(), ideStatusClock)
      val records =
        seed.transaction { unit ->
          (0 until 100).map { index ->
            val id = "work-$index"
            unit.standalonePhaseStatuses.register(
              StandalonePhaseStatusRegistration(
                testGoalRepositoryIdentity(fixture.tempDir), id, null, null, id, "monitor", "execution-$id",
                "paused", "runner_interrupted", ideStatusObservedAt.minusSeconds(30), "owner", 1,
                ideStatusObservedAt.plusSeconds(30),
              ),
            )
          }
        }
      val database =
        sqliteDatabaseSessionFactory(
          fixture.tempDir,
          fixture.dbPath.toString(),
          emptyMap(),
          ideStatusClock,
        )
      val pageTracker = PageTrackingDatabase(database)
      val service = ideStatusService(pageTracker)
      val request = WorkBoardRequest(observedAt = ideStatusObservedAt, pageSize = 10)
      val before = service.board(request)
      assertEquals(10, before.items.size)
      assertEquals(1, pageTracker.pageReads)
      database.transaction { unit ->
        val record = records.first()
        assertEquals(
          StandalonePhaseStatusUpdateResult.ACCEPTED,
          unit.standalonePhaseStatuses.update(
            StandalonePhaseStatusUpdate(
              record.executionId,
              record.statusRevision,
              record.leaseOwner,
              record.leaseGeneration,
              "paused",
              "monitor",
              null,
              ideStatusObservedAt.minusSeconds(1),
            ),
          ),
        )
      }
      assertEquals(100, boardRowCount(fixture.connection))
      val indexed = service.board(request)
      assertEquals(2, pageTracker.pageReads)
      assertEquals(11, indexed.candidatesExamined)
      fixture.connection.createStatement().use {
        it.executeUpdate(
          "UPDATE work_board_read_model_state SET complete = 0",
        )
      }
      val fallback = service.board(request)
      assertEquals(fallback.items, indexed.items)
    }
  }
}

private class PageTrackingDatabase(private val delegate: DatabaseSessionFactory) : DatabaseSessionFactory by delegate {
  var pageReads = 0

  override fun <T> read(block: (UnitOfWork) -> T): T =
    delegate.read { unit ->
      block(
        object : UnitOfWork by unit {
          override val workBoardReadModel: WorkBoardReadModelRepository =
            object : WorkBoardReadModelRepository by unit.workBoardReadModel {
              override fun page(query: WorkBoardReadModelPageQuery): WorkBoardReadModelPage {
                pageReads++
                return unit.workBoardReadModel.page(query)
              }
            }
        },
      )
    }
}

private fun singleRetainedReader(record: RetainedWorkflowRecord): RetainedWorkflowReader =
  object : RetainedWorkflowReader {
    override fun read(workflowId: String?) = listOf(record).filter { workflowId == null || it.workflowId == workflowId }
  }

private fun assertRetainedSource(
  connection: Connection,
  expected: String,
) {
  connection.createStatement().use {
    it.executeQuery("SELECT artifacts_json FROM feature_task_workflows WHERE workflow_id = 'retained-work'").use {
        rows ->
      check(rows.next())
      assertEquals(expected, rows.getString(1))
    }
  }
}

private fun corruptRuntimeRecord() =
  runtimeRecord("corrupt-runtime", "2026-08-06T11:00:00Z").copy(
    artifactsJson = "{\"feature_task_runtime_acceptance_criterion_repair\":{\"contract_version\":\"99.0\"}}",
  )

private fun boardRowCount(connection: Connection): Int =
  connection.createStatement().use { statement ->
    statement.executeQuery("SELECT count(*) FROM work_board_items").use { rows ->
      rows.next()
      rows.getInt(1)
    }
  }
