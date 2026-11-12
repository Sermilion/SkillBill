package skillbill.infrastructure.sqlite.idestatus

import skillbill.contracts.JsonCodec
import skillbill.contracts.persistence.WorkBoardReadModelKeys
import skillbill.infrastructure.sqlite.core.ops.bindAll
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.idestatus.WorkBoardReadModelRepository
import skillbill.ports.idestatus.model.WorkBoardInvalidation
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
  override fun pendingInvalidation(): WorkBoardInvalidation? =
    connection.createStatement().use { statement ->
      statement.executeQuery(
        "SELECT work_id, revision FROM ${WorkBoardInvalidationKeys.TABLE} ORDER BY revision LIMIT 1",
      ).use { rows -> if (rows.next()) WorkBoardInvalidation(rows.getString(1), rows.getLong(2)) else null }
    }

  override fun invalidationRevision(): Long =
    connection.prepareStatement("SELECT seq FROM sqlite_sequence WHERE name = ?").use { statement ->
      statement.setString(1, WorkBoardInvalidationKeys.TABLE)
      statement.executeQuery().use { rows -> if (rows.next()) rows.getLong(1) else 0 }
    }

  override fun acknowledge(invalidation: WorkBoardInvalidation) {
    connection.prepareStatement(
      "DELETE FROM ${WorkBoardInvalidationKeys.TABLE} WHERE work_id = ? AND revision = ?",
    ).use { statement ->
      statement.bindAll(invalidation.workId, invalidation.revision)
      statement.executeUpdate()
    }
  }

  override fun upsert(row: WorkBoardReadModelRow) {
    preserveInvalidItems(setOf(row.workId))
    if (hasRecovery(row.workId)) return
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
    preserveInvalidItems(setOf(workId))
    if (hasRecovery(workId)) return
    connection.prepareStatement(
      "DELETE FROM ${WorkBoardReadModelKeys.ITEMS_TABLE} WHERE ${WorkBoardReadModelKeys.WORK_ID} = ?",
    )
      .use { statement ->
        statement.bindAll(workId)
        statement.executeUpdate()
      }
  }

  override fun deleteAll() {
    val workIds =
      connection.createStatement().use { statement ->
        val sql = "SELECT ${WorkBoardReadModelKeys.WORK_ID} FROM ${WorkBoardReadModelKeys.ITEMS_TABLE}"
        statement.executeQuery(sql).use { rows -> buildSet { while (rows.next()) add(rows.getString(1)) } }
      }
    preserveInvalidItems(workIds)
    connection.createStatement().use { statement ->
      statement.executeUpdate(
        "DELETE FROM ${WorkBoardReadModelKeys.ITEMS_TABLE} WHERE ${WorkBoardReadModelKeys.WORK_ID} NOT IN " +
          "(SELECT ${WorkBoardReadModelKeys.WORK_ID} FROM ${WorkBoardReadModelKeys.INVALID_ITEMS_TABLE})",
      )
    }
  }

  private fun preserveInvalidItems(workIds: Set<String>) {
    if (workIds.isEmpty()) return
    readByWorkIds(workIds)
      .filterIsInstance<WorkBoardReadModelPage.Entry.Recovery>()
      .forEach(::preserveRecovery)
  }

  private fun preserveRecovery(recovery: WorkBoardReadModelPage.Entry.Recovery) {
    connection.prepareStatement(
      "INSERT OR IGNORE INTO ${WorkBoardReadModelKeys.INVALID_ITEMS_TABLE} " +
        "(${WorkBoardReadModelKeys.WORK_ID}, ${WorkBoardReadModelKeys.PAYLOAD}, " +
        "${WorkBoardReadModelKeys.RECOVERY_GUIDANCE}) VALUES (?, ?, ?)",
    ).use { archive ->
      archive.bindAll(recovery.workId, recovery.originalPayload, recovery.guidance)
      archive.executeUpdate()
    }
  }

  private fun hasRecovery(workId: String): Boolean =
    connection.prepareStatement(
      "SELECT 1 FROM ${WorkBoardReadModelKeys.INVALID_ITEMS_TABLE} WHERE ${WorkBoardReadModelKeys.WORK_ID} = ?",
    ).use { statement ->
      statement.bindAll(workId)
      statement.executeQuery().use { rows -> rows.next() }
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
        "AND ${WorkBoardReadModelKeys.WORK_ID} NOT IN " +
        "(SELECT ${WorkBoardReadModelKeys.WORK_ID} FROM ${WorkBoardReadModelKeys.INVALID_ITEMS_TABLE}) " +
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
      statement.executeQuery().use { rows ->
        val entries = (recoveryEntries() + decodeRows(rows)).distinctBy { it.workId }
        WorkBoardReadModelPage(
          entries
            .filter { after == null || compareKeys(it.key, after) > 0 }
            .sortedWith { left, right -> compareKeys(left.key, right.key) }
            .take(query.limit),
        )
      }
    }
  }

  private fun compareKeys(
    left: WorkBoardReadModelKey,
    right: WorkBoardReadModelKey,
  ): Int =
    compareValues(left.displayPriority, right.displayPriority).takeIf { it != 0 }
      ?: compareValues(right.updatedAtText, left.updatedAtText).takeIf { it != 0 }
      ?: compareValues(left.workId, right.workId)

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
      statement.executeQuery().use { rows ->
        (recoveryEntries(workIds) + decodeRows(rows)).distinctBy { it.workId }
      }
    }
  }

  private fun recoveryEntries(workIds: Set<String>? = null): List<WorkBoardReadModelPage.Entry> {
    val selection =
      if (workIds == null) {
        "SELECT ${WorkBoardReadModelKeys.WORK_ID}, ${WorkBoardReadModelKeys.PAYLOAD}, " +
          "${WorkBoardReadModelKeys.RECOVERY_GUIDANCE} FROM ${WorkBoardReadModelKeys.INVALID_ITEMS_TABLE}"
      } else {
        "SELECT ${WorkBoardReadModelKeys.WORK_ID}, ${WorkBoardReadModelKeys.PAYLOAD}, " +
          "${WorkBoardReadModelKeys.RECOVERY_GUIDANCE} FROM ${WorkBoardReadModelKeys.INVALID_ITEMS_TABLE} " +
          "WHERE ${WorkBoardReadModelKeys.WORK_ID} IN (SELECT value FROM json_each(?))"
      }
    return connection.prepareStatement(selection).use { statement ->
      if (workIds != null) statement.bindAll(JsonCodec.valueToJsonString(workIds.sorted()))
      statement.executeQuery().use { rows ->
        buildList {
          while (rows.next()) {
            val id = rows.getString(1)
            add(
              WorkBoardReadModelPage.Entry.Recovery(
                id,
                WorkBoardReadModelKey(Int.MIN_VALUE, "", id),
                rows.getString(2),
                "invalid_or_unsupported_payload",
                rows.getString(WorkBoardReadModelKeys.RECOVERY_GUIDANCE),
              ),
            )
          }
        }
      }
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
          val payload = rows.getString(WorkBoardReadModelKeys.PAYLOAD)
          val guidance =
            "The saved board row is incompatible with this runtime. " +
              "Preserve the database and contact support for recovery."
          diagnostics.warning(
            "seam=work_board_read_model_decode work_id=$id value_expected=decoded_row " +
              "value_used=recovery_entry failure_field=$category",
          )
          add(
            WorkBoardReadModelPage.Entry.Recovery(
              id,
              key,
              payload,
              category,
              guidance,
            ),
          )
        } else {
          add(WorkBoardReadModelPage.Entry.Decoded(decoded, key))
        }
      }
    }
}

internal object WorkBoardInvalidationKeys {
  const val TABLE = "work_board_invalidations"
}
