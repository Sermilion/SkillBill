package skillbill.ports.review.repository

import skillbill.review.model.ReviewFindingStatus

/** Reads where each stored review finding stands, for display. */
interface ReviewFindingStatusRepository {
  /** Each finding's [ReviewFindingStatus] by finding id; empty for an unknown run. */
  fun fetchFindingStatuses(runId: String): Map<String, ReviewFindingStatus>
}
