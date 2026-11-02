package skillbill.application.review.service

import me.tatarka.inject.annotations.Inject
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.review.model.ReviewFindingAction
import skillbill.review.model.ReviewFindingActionKind
import skillbill.review.model.ReviewFindingStatus
import java.time.Clock

@Inject
class ReviewFindingActionService(private val database: DatabaseSessionFactory, private val clock: Clock) {
  val actionKinds: List<String> = ReviewFindingActionKind.entries.map { it.wireValue }

  fun start(
    runId: String,
    kind: String,
  ): Long? =
    database.transaction { unit ->
      val statuses = unit.reviews.fetchFindingStatuses(runId).values
      if (statuses.isEmpty() || statuses.all { it == ReviewFindingStatus.FIXED }) return@transaction null
      unit.reviews.startFindingAction(runId, ReviewFindingActionKind.fromWire(kind), clock.instant())
    }

  fun finish(
    runId: String,
    id: Long,
    completed: Boolean,
  ): ReviewFindingAction? =
    database.transaction { unit ->
      val action = unit.reviews.findingActions(runId).firstOrNull { it.id == id } ?: return@transaction null
      if (action.finishedAt != null) return@transaction action
      val statuses = unit.reviews.fetchFindingStatuses(runId).values
      val fixed = statuses.count { it == ReviewFindingStatus.FIXED }
      val open = statuses.count { it == ReviewFindingStatus.OPEN || it == ReviewFindingStatus.ACCEPTED }
      val finished =
        action.copy(
          finishedAt = clock.instant(),
          completed = completed,
          fixed = fixed,
          open = open,
          other = statuses.size - fixed - open,
        )
      if (unit.reviews.finishFindingAction(runId, finished)) finished else null
    }
}
