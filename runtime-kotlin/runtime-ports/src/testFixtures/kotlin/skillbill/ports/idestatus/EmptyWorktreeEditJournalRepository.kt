package skillbill.ports.idestatus

import skillbill.idestatus.model.WorktreeEditTick
import java.time.Instant

object EmptyWorktreeEditJournalRepository : WorktreeEditJournalRepository {
  override fun append(
    workflowId: String,
    tick: WorktreeEditTick,
  ) = Unit

  override fun latestTick(workflowId: String): WorktreeEditTick? = null

  override fun deleteTicksBefore(
    workflowId: String,
    recordedAt: Instant,
  ): Int = 0
}
