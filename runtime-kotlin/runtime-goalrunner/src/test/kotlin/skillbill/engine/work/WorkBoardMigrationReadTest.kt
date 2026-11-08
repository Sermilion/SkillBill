package skillbill.engine.work

import skillbill.infrastructure.sqlite.establishTemporarySchemaReadiness
import skillbill.infrastructure.sqlite.sqliteDatabaseSessionFactory
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.idestatus.WorkBoardReadModelRepository
import skillbill.ports.idestatus.model.StandalonePhaseStatusRegistration
import skillbill.ports.idestatus.model.StandalonePhaseStatusUpdate
import skillbill.ports.idestatus.model.StandalonePhaseStatusUpdateResult
import skillbill.ports.idestatus.model.WorkBoardReadModelPage
import skillbill.ports.idestatus.model.WorkBoardReadModelPageQuery
import skillbill.ports.idestatus.model.WorkBoardRequest
import skillbill.ports.persistence.UnitOfWork
import java.sql.Connection
import kotlin.test.Test
import kotlin.test.assertEquals

class WorkBoardMigrationReadTest {
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

private fun boardRowCount(connection: Connection): Int =
  connection.createStatement().use { statement ->
    statement.executeQuery("SELECT count(*) FROM work_board_items").use { rows ->
      rows.next()
      rows.getInt(1)
    }
  }
