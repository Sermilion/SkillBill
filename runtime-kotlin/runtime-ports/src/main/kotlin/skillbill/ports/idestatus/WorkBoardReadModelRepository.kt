package skillbill.ports.idestatus

import skillbill.ports.idestatus.model.WorkBoardReadModelPage
import skillbill.ports.idestatus.model.WorkBoardReadModelPageQuery
import skillbill.ports.idestatus.model.WorkBoardReadModelRow

/** Persists the board projection used by indexed board reads. */
interface WorkBoardReadModelRepository {
  /** Replaces the persisted projection for one work item. */
  fun upsert(row: WorkBoardReadModelRow)

  /** Removes one work item from the projection. */
  fun delete(workId: String)

  /** Removes every persisted board item. */
  fun deleteAll()

  /** Reports whether the projection has been initialized. */
  fun isComplete(): Boolean

  /** Stores whether the projection has been initialized. */
  fun setComplete(complete: Boolean)

  /** Returns one ordered page. The key retains the exact stored updated-at text for SQL seeking. */
  fun page(query: WorkBoardReadModelPageQuery): WorkBoardReadModelPage

  /** Reads persisted entries for the supplied authoritative candidate ids. */
  fun readByWorkIds(workIds: Set<String>): List<WorkBoardReadModelPage.Entry>
}
