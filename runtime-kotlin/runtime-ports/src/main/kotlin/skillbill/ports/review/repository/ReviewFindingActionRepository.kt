package skillbill.ports.review.repository

import skillbill.review.model.ReviewFindingAction
import skillbill.review.model.ReviewFindingActionKind
import java.time.Instant

interface ReviewFindingActionRepository {
  fun startFindingAction(
    runId: String,
    kind: ReviewFindingActionKind,
    at: Instant,
  ): Long

  fun finishFindingAction(
    runId: String,
    action: ReviewFindingAction,
  ): Boolean

  fun findingActions(runId: String): List<ReviewFindingAction>
}
