package skillbill.infrastructure.sqlite.review.stage

import skillbill.contracts.persistence.WorkBoardReadModelKeys
import skillbill.infrastructure.sqlite.core.ops.bindAll
import skillbill.ports.review.repository.ReviewFindingActionRepository
import skillbill.review.model.ReviewFindingAction
import skillbill.review.model.ReviewFindingActionKind
import java.sql.Connection
import java.time.Instant

internal class SQLiteReviewFindingActions(private val connection: Connection) : ReviewFindingActionRepository {
  override fun startFindingAction(
    runId: String,
    kind: ReviewFindingActionKind,
    at: Instant,
  ): Long {
    connection.prepareStatement(
      "INSERT INTO review_finding_actions (review_run_id, kind, started_at) VALUES (?, ?, ?)",
    ).use { statement ->
      statement.bindAll(runId, kind.wireValue, at.toString())
      statement.executeUpdate()
    }
    return connection.createStatement().use { statement ->
      statement.executeQuery("SELECT last_insert_rowid()").use { rows ->
        rows.next()
        rows.getLong(1)
      }
    }
  }

  override fun finishFindingAction(
    runId: String,
    action: ReviewFindingAction,
  ): Boolean =
    connection.prepareStatement(
      """
      UPDATE review_finding_actions SET finished_at = ?, completed = ?, fixed = ?, open = ?, other = ?
      WHERE review_run_id = ? AND id = ? AND finished_at IS NULL
      """.trimIndent(),
    ).use { statement ->
      statement.bindAll(
        action.finishedAt?.toString(),
        action.completed,
        action.fixed,
        action.open,
        action.other,
        runId,
        action.id,
      )
      statement.executeUpdate() == 1
    }

  override fun findingActions(runId: String): List<ReviewFindingAction> =
    connection.prepareStatement("SELECT * FROM review_finding_actions WHERE review_run_id = ? ORDER BY id").use {
        statement ->
      statement.bindAll(runId)
      statement.executeQuery().use { rows ->
        buildList {
          while (rows.next()) add(
            ReviewFindingAction(
              rows.getLong("id"),
              ReviewFindingActionKind.fromWire(rows.getString("kind")),
              Instant.parse(rows.getString("started_at")),
              rows.getString(WorkBoardReadModelKeys.FINISHED_AT)?.let(Instant::parse),
              rows.getBoolean("completed"),
              rows.getInt("fixed"),
              rows.getInt("open"),
              rows.getInt("other"),
            ),
          )
        }
      }
    }
}
