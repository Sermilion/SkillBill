package skillbill.ports.review.evidence

import skillbill.ports.review.model.ReviewSnapshot
import java.nio.file.Path

/**
 * Read and delete access to review-metrics snapshots.
 *
 * Retention policy: snapshots are never created, rotated, or deleted automatically. They are
 * operator artifacts with no expiry, and nothing in the runtime removes them — deletion happens
 * only when an operator runs `sona review prune-snapshots --confirm`. The live
 * `review-metrics.db` is never a snapshot and is never a deletion candidate.
 */
interface ReviewSnapshotGateway {
  /** Snapshots beside [liveDbPath], newest first. Never includes [liveDbPath] itself. */
  fun listSnapshots(liveDbPath: Path): List<ReviewSnapshot>

  fun delete(snapshot: ReviewSnapshot): Boolean
}
