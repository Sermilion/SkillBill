package skillbill.ports.work

import skillbill.ports.work.model.WorkBoardCandidateQuery
import skillbill.ports.work.model.WorkBoardCandidateRow
import skillbill.ports.work.model.WorkStatusCandidatePage
import skillbill.ports.work.model.WorkStatusCandidateQuery

/** The keyset candidate pages the board and status reads walk, apart from the work list's item and goal reads. */
interface WorkCandidateRepository {
  fun boardCandidates(query: WorkBoardCandidateQuery): List<WorkBoardCandidateRow>

  /** One keyset page of work items, live states first then newest-started; the cursor is the last row's key. */
  fun statusCandidates(query: WorkStatusCandidateQuery): WorkStatusCandidatePage
}
