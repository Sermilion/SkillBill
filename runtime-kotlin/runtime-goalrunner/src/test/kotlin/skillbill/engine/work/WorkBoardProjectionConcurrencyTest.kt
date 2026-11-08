package skillbill.engine.work

import skillbill.error.core.databaseBusy
import skillbill.infrastructure.sqlite.establishTemporarySchemaReadiness
import skillbill.infrastructure.sqlite.sqliteDatabaseSessionFactory
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.idestatus.model.StandalonePhaseStatusRegistration
import skillbill.ports.idestatus.model.WorkBoardRequest
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.work.WorkListRepository
import skillbill.ports.work.model.WorkBoardAffectedCandidates
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkBoardProjectionConcurrencyTest {
  @Test
  fun `a second writer can commit during projection and its change rejects stale publication`() {
    establishTemporarySchemaReadiness("board-projection-concurrent-writer").use { fixture ->
      val database =
        sqliteDatabaseSessionFactory(fixture.tempDir, fixture.dbPath.toString(), emptyMap(), ideStatusClock)
      val writer = sqliteDatabaseSessionFactory(fixture.tempDir, fixture.dbPath.toString(), emptyMap(), ideStatusClock)
      database.transaction { register(it, "first-work", testGoalRepositoryIdentity(fixture.tempDir)) }
      var committed = false
      val observed =
        duringProjection(database) {
          writer.transaction { register(it, "second-work", testGoalRepositoryIdentity(fixture.tempDir)) }
          committed = true
        }
      val page = ideStatusService(observed).board(WorkBoardRequest(observedAt = ideStatusObservedAt))
      assertTrue(committed)
      assertEquals(2, page.items.size)
      database.read {
        assertNotNull(it.workBoardReadModel.pendingInvalidation())
        assertTrue(it.workBoardReadModel.readByWorkIds(setOf("first-work")).isEmpty())
      }
      val recovered = ideStatusService(database).board(WorkBoardRequest(observedAt = ideStatusObservedAt))
      assertEquals(page.items, recovered.items)
      database.read { assertNull(it.workBoardReadModel.pendingInvalidation()) }
    }
  }

  @Test
  fun `a failed derived projection preserves source progress and recovers on a later board read`() {
    establishTemporarySchemaReadiness("board-projection-recovery").use { fixture ->
      val database =
        sqliteDatabaseSessionFactory(fixture.tempDir, fixture.dbPath.toString(), emptyMap(), ideStatusClock)
      database.selfManagedWrite { register(it, "completed-source-write", testGoalRepositoryIdentity(fixture.tempDir)) }
      val failing =
        duringProjection(database) { throw databaseBusy(IllegalStateException("injected projection storage failure")) }
      val fallback = ideStatusService(failing).board(WorkBoardRequest(observedAt = ideStatusObservedAt))
      assertEquals(1, fallback.items.size)
      database.read { assertNotNull(it.workBoardReadModel.pendingInvalidation()) }
      database.release()
      val restarted =
        sqliteDatabaseSessionFactory(fixture.tempDir, fixture.dbPath.toString(), emptyMap(), ideStatusClock)
      val recovered = ideStatusService(restarted).board(WorkBoardRequest(observedAt = ideStatusObservedAt))
      assertEquals(fallback.items, recovered.items)
      restarted.read { assertNull(it.workBoardReadModel.pendingInvalidation()) }
    }
  }
}

private fun register(
  unit: UnitOfWork,
  id: String,
  root: String,
) {
  unit.standalonePhaseStatuses.register(
    StandalonePhaseStatusRegistration(
      root, id, null, null, id, "monitor", "execution-$id", "paused", "runner_interrupted",
      ideStatusObservedAt.minusSeconds(30), "owner", 1, ideStatusObservedAt.plusSeconds(30),
    ),
  )
}

private fun duringProjection(
  database: DatabaseSessionFactory,
  probe: () -> Unit,
): DatabaseSessionFactory =
  object : DatabaseSessionFactory by database {
    private var pending = true

    override fun <T> read(block: (UnitOfWork) -> T): T =
      database.read { unit ->
        block(
          object : UnitOfWork by unit {
            override val workList: WorkListRepository =
              object : WorkListRepository by unit.workList {
                override fun boardCandidatesAffectedBy(
                  keys: Set<String>,
                  finishedFrom: Instant,
                  observedAt: Instant,
                ): WorkBoardAffectedCandidates {
                  if (pending) {
                    pending = false
                    probe()
                  }
                  return unit.workList.boardCandidatesAffectedBy(keys, finishedFrom, observedAt)
                }
              }
          },
        )
      }
  }
