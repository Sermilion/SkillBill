package skillbill.infrastructure.sqlite.worklist

import skillbill.infrastructure.sqlite.core.ops.bindAll
import skillbill.ports.work.model.WorkItem
import skillbill.ports.work.model.WorkStatusCandidatePage
import skillbill.ports.work.model.WorkStatusCandidateQuery
import java.sql.Connection

private const val MAX_STATUS_CANDIDATE_BATCH_SIZE = 400

/** Started-at normalised to second precision plus nine fraction digits, so text order is time order. */
internal fun startedAtKeySql(column: String): String =
  """
  strftime('%Y-%m-%dT%H:%M:%S', $column) || '.' ||
    CASE WHEN substr($column, 20, 1) = '.' THEN
      substr(substr($column, 21, instr(substr($column, 21), 'Z') - 1) || '000000000', 1, 9)
    ELSE '000000000' END
  """.trimIndent()

/**
 * One keyset page of work items ordered live-first, then newest-started, then id. A live row (a state in
 * [WorkStatusCandidateQuery.liveStates]) always sorts ahead of every settled row, so a bounded walk from the head reaches
 * every live workflow before it reaches history, whatever the age of the live row.
 */
internal fun readWorkStatusCandidates(
  connection: Connection,
  query: WorkStatusCandidateQuery,
): WorkStatusCandidatePage {
  require(query.limit in 1..MAX_STATUS_CANDIDATE_BATCH_SIZE)
  val liveStates = query.liveStates.toList()
  val liveMarks = liveStates.joinToString { "?" }.ifEmpty { "NULL" }
  // shortcut: every page re-keys and sorts the whole work-item union, since a computed key over a union has no
  // usable index; the walk is capped by the status candidate budget. Persist the key in an indexed column if the
  // work list outgrows that.
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
