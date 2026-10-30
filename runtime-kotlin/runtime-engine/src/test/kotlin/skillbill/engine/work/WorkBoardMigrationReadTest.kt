package skillbill.engine.work

import skillbill.infrastructure.sqlite.establishTemporarySchemaReadiness
import skillbill.infrastructure.sqlite.sqliteDatabaseSessionFactory
import skillbill.ports.WorkBoardReadModelProjector
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.idestatus.WorkBoardReadModelRepository
import skillbill.ports.idestatus.model.StandalonePhaseStatusRegistration
import skillbill.ports.idestatus.model.StandalonePhaseStatusUpdate
import skillbill.ports.idestatus.model.StandalonePhaseStatusUpdateResult
import skillbill.ports.idestatus.model.WorkBoardReadModelPage
import skillbill.ports.idestatus.model.WorkBoardReadModelPageQuery
import skillbill.ports.idestatus.model.WorkBoardRequest
import skillbill.ports.persistence.UnitOfWork
import kotlin.test.Test
import kotlin.test.assertEquals
import java.sql.Connection
import java.time.Instant

class WorkBoardMigrationReadTest {
  @Test
  fun `first write rebuilds an incomplete board and preserves post heartbeat projection order`() {
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
      val tracker = TrackingBoardProjector()
      val database =
        sqliteDatabaseSessionFactory(
          fixture.tempDir,
          fixture.dbPath.toString(),
          emptyMap(),
          ideStatusClock,
          boardReadModelProjector = tracker,
        )
      val pageTracker = PageTrackingDatabase(database)
      val capturedDatabase =
        object : DatabaseSessionFactory by pageTracker, BoardProjectionCapture {
          override fun accept(projection: WorkBoardItemProjection) {
            tracker.delegate = WorkBoardReadModelProjection(projection, NoopRuntimeDiagnostics)
          }
        }
      val service = ideStatusService(capturedDatabase)
      val request = WorkBoardRequest(observedAt = ideStatusObservedAt, pageSize = 10)
      val before = service.board(request)
      assertEquals(10, before.items.size)
      assertEquals(0, pageTracker.pageReads)
      assertEquals(0, tracker.rebuildCount)
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
      assertEquals(1, tracker.rebuildCount)
      assertEquals(100, boardRowCount(fixture.connection))
      val indexed = service.board(request)
      assertEquals(1, pageTracker.pageReads)
      assertEquals(11, indexed.candidatesExamined)
      fixture.connection.createStatement().use {
        it.executeUpdate(
          "UPDATE work_board_read_model_state SET complete = 0",
        )
      }
      val fallback = service.board(request)
      assertEquals(fallback.items, indexed.items)
      assertEquals(1, tracker.rebuildCount)
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

private fun boardRowCount(connection: Connection): Int =
  connection.createStatement().use { statement ->
    statement.executeQuery("SELECT count(*) FROM work_board_items").use { rows ->
      rows.next()
      rows.getInt(1)
    }
  }

private class TrackingBoardProjector : WorkBoardReadModelProjector {
  lateinit var delegate: WorkBoardReadModelProjector
  var rebuildCount = 0

  override fun refresh(
    unitOfWork: UnitOfWork,
    workIds: Set<String>,
    now: Instant,
  ) = delegate.refresh(unitOfWork, workIds, now)

  override fun rebuild(
    unitOfWork: UnitOfWork,
    now: Instant,
  ) {
    rebuildCount++
    delegate.rebuild(unitOfWork, now)
  }
}
