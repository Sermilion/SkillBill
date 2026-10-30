package skillbill.ports.idestatus

import skillbill.idestatus.model.WorktreeEditTick
import java.time.Instant

interface WorktreeEditJournalRepository {
  fun append(
    workflowId: String,
    tick: WorktreeEditTick,
  )

  fun latestTick(workflowId: String): WorktreeEditTick?

  /** Deletes this workflow's ticks older than [recordedAt]; returns the deleted row count. */
  fun deleteTicksBefore(
    workflowId: String,
    recordedAt: Instant,
  ): Int
}
