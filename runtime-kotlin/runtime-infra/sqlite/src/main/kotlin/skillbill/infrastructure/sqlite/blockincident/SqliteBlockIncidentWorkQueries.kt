package skillbill.infrastructure.sqlite.blockincident

import skillbill.infrastructure.sqlite.core.ops.bindAll
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentAliasKind
import skillbill.workflow.taskruntime.model.blockincident.StandalonePhaseIncidentAlias
import java.sql.Connection

internal class SqliteBlockIncidentWorkQueries(
  private val connection: Connection,
) {
  fun listForWork(
    workflowId: String?,
    invocationId: String?,
    limit: Int,
  ): List<String> {
    if (workflowId == null && invocationId == null) return emptyList()
    val predicates =
      buildList {
        if (workflowId != null) add("i.workflow_id = ? OR i.parent_workflow_id = ?")
        if (invocationId != null) {
          add(
            "i.invocation_id = ? OR EXISTS (" +
              "SELECT 1 FROM block_incident_aliases a " +
              "WHERE a.occurrence_token = i.occurrence_token AND a.alias_kind = ? " +
              "AND substr(a.alias_value, 1, length(?)) = ? " +
              "AND length(a.alias_value) > length(?) " +
              "AND instr(substr(a.alias_value, length(?) + 1), ':') = 0)",
          )
        }
      }
    val arguments =
      buildList<Any> {
        workflowId?.let {
          add(it)
          add(it)
        }
        invocationId?.let {
          val prefix = StandalonePhaseIncidentAlias.invocationPrefix(it)
          add(it)
          add(BlockIncidentAliasKind.PHASE_TRANSITION.wireValue)
          add(prefix)
          add(prefix)
          add(prefix)
          add(prefix)
        }
        add(limit)
      }
    return connection.prepareStatement(
      "SELECT DISTINCT i.occurrence_token FROM block_incidents i " +
        "WHERE (${predicates.joinToString(") OR (")}) " +
        "ORDER BY i.observed_at DESC, i.occurrence_token DESC LIMIT ?",
    ).use { statement ->
      statement.bindAll(arguments)
      statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
    }
  }
}
