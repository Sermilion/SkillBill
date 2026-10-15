package skillbill.infrastructure.sqlite.worklist

import skillbill.infrastructure.sqlite.core.ops.bindAll
import skillbill.ports.work.model.WorkItem
import skillbill.ports.work.model.WorkStatusCandidatePage
import skillbill.ports.work.model.WorkStatusCandidateQuery
import java.sql.Connection

private const val MAX_STATUS_CANDIDATE_BATCH_SIZE = 400

internal fun startedAtKeySql(column: String): String =
  """
  strftime('%Y-%m-%dT%H:%M:%S', $column) || '.' ||
    CASE WHEN substr($column, 20, 1) = '.' THEN
      substr(substr($column, 21, instr(substr($column, 21), 'Z') - 1) || '000000000', 1, 9)
    ELSE '000000000' END
  """.trimIndent()

internal fun readWorkStatusCandidates(
  connection: Connection,
  query: WorkStatusCandidateQuery,
): WorkStatusCandidatePage {
  require(query.limit in 1..MAX_STATUS_CANDIDATE_BATCH_SIZE)
  val liveStates = query.liveStates.toList()
  val liveMarks = liveStates.joinToString { "?" }.ifEmpty { "NULL" }
  val rows =
    connection.prepareStatement(
      """
      WITH work_items AS ($WORK_ITEM_UNION_SQL), keyed AS (
        SELECT issue_key, workflow_kind, workflow_id, started_at, current_state, state_entered_at,
          state_entered_at_estimated,
          CASE WHEN current_state IN ($liveMarks) THEN '1' ELSE '0' END || char(10) ||
            ${startedAtKeySql("started_at")} || char(10) || workflow_id AS paging_key
        FROM work_items
      )
      SELECT issue_key, workflow_kind, workflow_id, started_at, current_state, state_entered_at,
        state_entered_at_estimated, paging_key
      FROM keyed WHERE (? IS NULL OR paging_key < ?)
      ORDER BY paging_key DESC LIMIT ?
      """.trimIndent(),
    ).use { statement ->
      statement.bindAll(liveStates + listOf(query.afterKey, query.afterKey, query.limit))
      statement.executeQuery().use { resultSet ->
        buildList<Pair<WorkItem, String>> {
          while (resultSet.next()) add(resultSet.toWorkItem() to resultSet.getString("paging_key"))
        }
      }
    }
  return WorkStatusCandidatePage(rows.map { it.first }, rows.lastOrNull()?.second)
}
