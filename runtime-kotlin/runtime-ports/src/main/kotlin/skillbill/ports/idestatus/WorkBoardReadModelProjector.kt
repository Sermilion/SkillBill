package skillbill.ports.idestatus

import skillbill.ports.UnitOfWork
import java.time.Instant

/** Updates persisted board rows from the current work state. */
interface WorkBoardReadModelProjector {
  /** Refreshes rows for the supplied work ids within the caller's transaction. */
  fun refresh(
    unitOfWork: UnitOfWork,
    workIds: Set<String>,
    now: Instant,
  )

  /** Rebuilds the complete board projection within the caller's transaction. */
  fun rebuild(
    unitOfWork: UnitOfWork,
    now: Instant,
  )
}
