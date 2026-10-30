package skillbill.infrastructure.sqlite.idestatus

import skillbill.contracts.JsonCodec
import skillbill.contracts.persistence.WorkBoardReadModelKeys
import skillbill.infrastructure.sqlite.core.ops.bindAll
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.idestatus.WorkBoardReadModelRepository
import skillbill.ports.idestatus.model.WorkBoardReadModelKey
import skillbill.ports.idestatus.model.WorkBoardReadModelPage
import skillbill.ports.idestatus.model.WorkBoardReadModelPageQuery
import skillbill.ports.idestatus.model.WorkBoardReadModelRow
import java.sql.Connection
import java.sql.ResultSet

internal class SqliteWorkBoardReadModelStore(
  private val connection: Connection,
  private val diagnostics: RuntimeDiagnostics,
) : WorkBoardReadModelRepository {
  override fun upsert(row: WorkBoardReadModelRow) {
    connection.prepareStatement(
      """
      INSERT INTO ${WorkBoardReadModelKeys.ITEMS_TABLE} (
        ${WorkBoardReadModelKeys.WORK_ID}, ${WorkBoardReadModelKeys.DISPLAY_PRIORITY},
        ${WorkBoardReadModelKeys.DISPLAY_GROUP}, ${WorkBoardReadModelKeys.LIVE_SECTION},
        ${WorkBoardReadModelKeys.REQUIRES_ATTENTION}, ${WorkBoardReadModelKeys.FINISHED_AT},
        ${WorkBoardReadModelKeys.UPDATED_AT}, ${WorkBoardReadModelKeys.FRESHNESS_DEADLINE},
        ${WorkBoardReadModelKeys.MEMBERSHIP_DEADLINE}, ${WorkBoardReadModelKeys.SEARCH_TEXT},
        ${WorkBoardReadModelKeys.PAYLOAD}, ${WorkBoardReadModelKeys.PROJECTED_AT}
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      ON CONFLICT(${WorkBoardReadModelKeys.WORK_ID}) DO UPDATE SET
        ${WorkBoardReadModelKeys.DISPLAY_PRIORITY} = excluded.${WorkBoardReadModelKeys.DISPLAY_PRIORITY},
        ${WorkBoardReadModelKeys.DISPLAY_GROUP} = excluded.${WorkBoardReadModelKeys.DISPLAY_GROUP},
        ${WorkBoardReadModelKeys.LIVE_SECTION} = excluded.${WorkBoardReadModelKeys.LIVE_SECTION},
        ${WorkBoardReadModelKeys.REQUIRES_ATTENTION} = excluded.${WorkBoardReadModelKeys.REQUIRES_ATTENTION},
        ${WorkBoardReadModelKeys.FINISHED_AT} = excluded.${WorkBoardReadModelKeys.FINISHED_AT},
        ${WorkBoardReadModelKeys.UPDATED_AT} = excluded.${WorkBoardReadModelKeys.UPDATED_AT},
        ${WorkBoardReadModelKeys.FRESHNESS_DEADLINE} = excluded.${WorkBoardReadModelKeys.FRESHNESS_DEADLINE},
        ${WorkBoardReadModelKeys.MEMBERSHIP_DEADLINE} = excluded.${WorkBoardReadModelKeys.MEMBERSHIP_DEADLINE},
        ${WorkBoardReadModelKeys.SEARCH_TEXT} = excluded.${WorkBoardReadModelKeys.SEARCH_TEXT},
        ${WorkBoardReadModelKeys.PAYLOAD} = excluded.${WorkBoardReadModelKeys.PAYLOAD},
        ${WorkBoardReadModelKeys.PROJECTED_AT} = excluded.${WorkBoardReadModelKeys.PROJECTED_AT}
      """.trimIndent(),
    ).use { statement ->
      statement.bindAll(
        row.workId,
        row.item.displayPriority,
        row.item.displayGroup?.name,
        row.item.liveSection?.name,
        row.item.requiresAttention,
        row.item.finishedAt?.let(WorkBoardReadModelTime::format),
        WorkBoardReadModelTime.format(row.updatedAt),
        row.reprojectAt?.let(WorkBoardReadModelTime::format),
        row.membershipDeadline?.let(WorkBoardReadModelTime::format),
        row.searchText,
        WorkBoardReadModelPayloadCodec.encode(row),
        WorkBoardReadModelTime.format(row.projectedAt),
      )
      statement.executeUpdate()
    }
  }

  override fun delete(workId: String) {
    connection.prepareStatement(
      "DELETE FROM ${WorkBoardReadModelKeys.ITEMS_TABLE} WHERE ${WorkBoardReadModelKeys.WORK_ID} = ?",
    )
      .use { statement ->
        statement.bindAll(workId)
        statement.executeUpdate()
      }
  }

  override fun deleteAll() {
    connection.createStatement().use { it.executeUpdate("DELETE FROM ${WorkBoardReadModelKeys.ITEMS_TABLE}") }
  }

  override fun isComplete(): Boolean =
    connection.createStatement().use { statement ->
      statement.executeQuery(
        "SELECT ${WorkBoardReadModelKeys.COMPLETE} FROM ${WorkBoardReadModelKeys.STATE_TABLE} " +
          "WHERE ${WorkBoardReadModelKeys.ID} = 1",
      )
        .use { rows -> rows.next() && rows.getInt(1) == 1 }
    }

  override fun setComplete(complete: Boolean) {
    connection.prepareStatement(
      "UPDATE ${WorkBoardReadModelKeys.STATE_TABLE} SET ${WorkBoardReadModelKeys.COMPLETE} = ? " +
        "WHERE ${WorkBoardReadModelKeys.ID} = 1",
    )
      .use { statement ->
        statement.bindAll(complete)
        statement.executeUpdate()
      }
  }

  override fun page(query: WorkBoardReadModelPageQuery): WorkBoardReadModelPage {
    require(query.limit > 0)
    val escaped = query.filter?.replace("\\", "\\\\")?.replace("%", "\\%")?.replace("_", "\\_")
    val after = query.afterKey
    val seek =
      if (after == null) {
        "1 = 1"
      } else {
        "(${WorkBoardReadModelKeys.DISPLAY_PRIORITY} > ? OR (${WorkBoardReadModelKeys.DISPLAY_PRIORITY} = ? AND " +
          "(${WorkBoardReadModelKeys.UPDATED_AT} < ? OR (${WorkBoardReadModelKeys.UPDATED_AT} = ? AND " +
          "${WorkBoardReadModelKeys.WORK_ID} > ?))))"
      }
    val sql =
      "SELECT ${WorkBoardReadModelKeys.WORK_ID}, ${WorkBoardReadModelKeys.DISPLAY_PRIORITY}, " +
        "${WorkBoardReadModelKeys.UPDATED_AT}, ${WorkBoardReadModelKeys.SEARCH_TEXT}, " +
        "${WorkBoardReadModelKeys.FRESHNESS_DEADLINE}, " +
        "${WorkBoardReadModelKeys.MEMBERSHIP_DEADLINE}, " +
        "${WorkBoardReadModelKeys.PAYLOAD}, ${WorkBoardReadModelKeys.PROJECTED_AT} " +
        "FROM ${WorkBoardReadModelKeys.ITEMS_TABLE} " +
        "WHERE (${WorkBoardReadModelKeys.FINISHED_AT} IS NULL OR ${WorkBoardReadModelKeys.FINISHED_AT} >= ?) " +
        "AND (? IS NULL OR ${WorkBoardReadModelKeys.SEARCH_TEXT} LIKE '%' || ? || '%' ESCAPE '\\') AND $seek " +
        "ORDER BY ${WorkBoardReadModelKeys.DISPLAY_PRIORITY}, ${WorkBoardReadModelKeys.UPDATED_AT} DESC, " +
        "${WorkBoardReadModelKeys.WORK_ID} LIMIT ?"
    return connection.prepareStatement(sql).use { statement ->
      var index = 1
      statement.setString(index++, WorkBoardReadModelTime.format(query.finishedFrom))
      statement.setString(index++, escaped)
      statement.setString(index++, escaped)
      if (after != null) {
        statement.setInt(index++, after.displayPriority)
        statement.setInt(index++, after.displayPriority)
        statement.setString(index++, after.updatedAtText)
        statement.setString(index++, after.updatedAtText)
        statement.setString(index++, after.workId)
      }
      statement.setInt(index, query.limit)
      statement.executeQuery().use { rows -> WorkBoardReadModelPage(decodeRows(rows)) }
    }
  }

  override fun readByWorkIds(workIds: Set<String>): List<WorkBoardReadModelPage.Entry> {
    if (workIds.isEmpty()) return emptyList()
    return connection.prepareStatement(
      "SELECT ${WorkBoardReadModelKeys.WORK_ID}, ${WorkBoardReadModelKeys.DISPLAY_PRIORITY}, " +
        "${WorkBoardReadModelKeys.UPDATED_AT}, ${WorkBoardReadModelKeys.SEARCH_TEXT}, " +
        "${WorkBoardReadModelKeys.FRESHNESS_DEADLINE}, ${WorkBoardReadModelKeys.MEMBERSHIP_DEADLINE}, " +
        "${WorkBoardReadModelKeys.PAYLOAD}, ${WorkBoardReadModelKeys.PROJECTED_AT} " +
        "FROM ${WorkBoardReadModelKeys.ITEMS_TABLE} " +
        "WHERE ${WorkBoardReadModelKeys.WORK_ID} IN (SELECT value FROM json_each(?))",
    ).use { statement ->
      statement.bindAll(JsonCodec.valueToJsonString(workIds.sorted()))
      statement.executeQuery().use(::decodeRows)
    }
  }

  private fun decodeRows(rows: ResultSet): List<WorkBoardReadModelPage.Entry> =
    buildList {
      while (rows.next()) {
        val id = rows.getString(WorkBoardReadModelKeys.WORK_ID)
        val priority = rows.getInt(WorkBoardReadModelKeys.DISPLAY_PRIORITY)
        val updatedAtText = rows.getString(WorkBoardReadModelKeys.UPDATED_AT)
        val key = WorkBoardReadModelKey(priority, updatedAtText, id)
        val rawFreshness = rows.getString(WorkBoardReadModelKeys.FRESHNESS_DEADLINE)
        val rawMembership = rows.getString(WorkBoardReadModelKeys.MEMBERSHIP_DEADLINE)
        val search = rows.getString(WorkBoardReadModelKeys.SEARCH_TEXT)
        val rawProjected = rows.getString(WorkBoardReadModelKeys.PROJECTED_AT)
        val updatedAt = updatedAtText?.let(WorkBoardReadModelTime::parseOrNull)
        val freshness = rawFreshness?.let(WorkBoardReadModelTime::parseOrNull)
        val membership = rawMembership?.let(WorkBoardReadModelTime::parseOrNull)
        val projected = rawProjected?.let(WorkBoardReadModelTime::parseOrNull)
        val failedField =
          when {
            updatedAt == null -> "updated_at"
            rawFreshness != null && freshness == null -> "freshness_deadline"
            rawMembership != null && membership == null -> "membership_deadline"
            projected == null -> "projected_at"
            else -> null
          }
        val decoded =
          if (failedField == null) {
            updatedAt?.let { validUpdatedAt ->
              projected?.let { validProjectedAt ->
                WorkBoardReadModelPayloadCodec.decode(
                  rows.getString(WorkBoardReadModelKeys.PAYLOAD),
                  WorkBoardRowMetadata(id, validUpdatedAt, search, freshness, validProjectedAt, membership),
                )
              }
            }
          } else {
            null
          }
        if (decoded == null) {
          val category = failedField ?: "payload"
          diagnostics.warning(
            "seam=work_board_read_model_decode work_id=$id value_expected=decoded_row " +
              "value_used=in_memory_reprojection failure_field=$category",
          )
          add(WorkBoardReadModelPage.Entry.Undecodable(id, key))
        } else {
          add(WorkBoardReadModelPage.Entry.Decoded(decoded, key))
        }
      }
    }
}
