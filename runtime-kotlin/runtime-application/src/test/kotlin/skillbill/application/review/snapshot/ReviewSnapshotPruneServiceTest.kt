package skillbill.application.review.snapshot

import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.db.model.OwningTransactionResult
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.review.evidence.ReviewSnapshotGateway
import skillbill.ports.review.model.ReviewSnapshot
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReviewSnapshotPruneServiceTest {
  @Test
  fun `the default invocation lists candidates and deletes nothing`() {
    val gateway = RecordingSnapshotGateway()
    val result =
      ReviewSnapshotPruneService(StubSessionFactory, gateway, NoopRuntimeDiagnostics)
        .prune(confirmed = false)

    assertEquals(2, result.candidates.size, "Every snapshot must be listed for the operator to review.")
    assertEquals(emptyList(), result.deleted)
    assertEquals(0, result.reclaimedBytes)
    assertEquals(emptyList(), gateway.deleted, "A dry run must never reach the deletion seam at all.")
    assertEquals(300, result.candidateBytes)
  }

  @Test
  fun `confirmation deletes exactly the listed snapshots and never the live database`() {
    val gateway = RecordingSnapshotGateway()
    val result = ReviewSnapshotPruneService(StubSessionFactory, gateway, NoopRuntimeDiagnostics).prune(confirmed = true)

    assertEquals(gateway.snapshots, gateway.deleted, "Confirmation deletes precisely the listed candidates.")
    assertEquals(300, result.reclaimedBytes)
    assertTrue(
      gateway.deleted.none { it.path.fileName.toString() == "review-metrics.db" },
      "The live database is never a candidate and so can never be deleted.",
    )
  }

  private class RecordingSnapshotGateway : ReviewSnapshotGateway {
    val snapshots =
      listOf(
        ReviewSnapshot(Path.of("/home/u/.skill-bill/review-metrics.a.db"), "a", 100, "2026-07-02T00:00:00Z"),
        ReviewSnapshot(Path.of("/home/u/.skill-bill/review-metrics.b.db"), "b", 200, "2026-07-01T00:00:00Z"),
      )
    val deleted = mutableListOf<ReviewSnapshot>()

    override fun listSnapshots(liveDbPath: Path): List<ReviewSnapshot> = snapshots

    override fun delete(snapshot: ReviewSnapshot): Boolean {
      deleted += snapshot
      return true
    }
  }

  private object StubSessionFactory : DatabaseSessionFactory {
    override fun <T> owningTransaction(block: (UnitOfWork) -> T) =
      OwningTransactionResult.Refused(OwningTransactionResult.Refusal.ACTIVE_SESSION)

    override fun resolveDbPath(): Path = Path.of("/home/u/.skill-bill/review-metrics.db")

    override fun databaseExists(): Boolean = true

    override fun <T> read(block: (UnitOfWork) -> T): T = error("Pruning must not open the database.")

    override fun <T> transaction(block: (UnitOfWork) -> T): T = error("Pruning must not open the database.")

    override fun <T> selfManagedWrite(block: (UnitOfWork) -> T): T = error("Pruning must not open the database.")
  }
}
