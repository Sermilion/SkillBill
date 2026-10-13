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
}
