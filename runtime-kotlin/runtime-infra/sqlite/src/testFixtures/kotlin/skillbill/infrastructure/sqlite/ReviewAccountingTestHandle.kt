package skillbill.infrastructure.sqlite

import skillbill.infrastructure.sqlite.review.accounting.loadReviewAccounting
import skillbill.infrastructure.sqlite.review.accounting.upsertReviewAccounting
import skillbill.ports.review.model.ReviewAccountingRecord
import java.sql.Connection

class ReviewAccountingTestHandle internal constructor(
  private val connection: Connection,
) {
  fun load(reviewId: String) = loadReviewAccounting(connection, reviewId, "test-runtime-version", SqliteTestDiagnostics)

  fun upsert(record: ReviewAccountingRecord) {
    upsertReviewAccounting(connection, record)
  }
}

fun reviewAccountingOnConnection(connection: Connection): ReviewAccountingTestHandle =
  ReviewAccountingTestHandle(connection)
