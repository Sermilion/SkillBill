package skillbill.ports.idestatus

import skillbill.ports.idestatus.model.StandalonePhaseStatusRecord
import skillbill.ports.idestatus.model.StandalonePhaseStatusUpdateResult
import java.time.Instant

interface StandalonePhaseCancellationRepository {
  fun requestCancellation(
    record: StandalonePhaseStatusRecord,
    at: Instant,
  ): StandalonePhaseStatusUpdateResult

  fun cancel(
    record: StandalonePhaseStatusRecord,
    at: Instant,
  ): StandalonePhaseStatusUpdateResult

  /**
   * Settles a non-terminal execution as blocked after the caller proved its lease expired and its owner process is
   * gone. Guarded on the exact revision, lease owner and generation of [record]: a changed revision is
   * STALE_REVISION, a changed or renewed lease is STALE_LEASE, and an already terminal execution is IDEMPOTENT.
   */
  fun releaseExpiredLease(
    record: StandalonePhaseStatusRecord,
    at: Instant,
  ): StandalonePhaseStatusUpdateResult
}
