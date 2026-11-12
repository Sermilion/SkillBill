package skillbill.infrastructure.sqlite.core.migration.area

import skillbill.contracts.persistence.WorkBoardReadModelKeys
import skillbill.infrastructure.sqlite.core.schema.installWorkBoardChangeTriggers
import skillbill.infrastructure.sqlite.idestatus.WorkBoardReadModelPayloadCodec
import skillbill.infrastructure.sqlite.idestatus.WorkBoardReadModelTime
import skillbill.infrastructure.sqlite.idestatus.WorkBoardRowMetadata
import skillbill.ports.diagnostics.RuntimeDiagnostics
import java.sql.Connection
import java.time.format.DateTimeParseException

internal object WorkBoardReadModelMigration {
  fun apply(connection: Connection) {
    connection.createStatement().use { statement ->
      statement.execute(
        """
        CREATE TABLE ${WorkBoardReadModelKeys.ITEMS_TABLE} (
          ${WorkBoardReadModelKeys.WORK_ID} TEXT PRIMARY KEY,
          ${WorkBoardReadModelKeys.DISPLAY_PRIORITY} INTEGER NOT NULL,
          ${WorkBoardReadModelKeys.DISPLAY_GROUP} TEXT,
          ${WorkBoardReadModelKeys.LIVE_SECTION} TEXT,
          ${WorkBoardReadModelKeys.REQUIRES_ATTENTION} INTEGER NOT NULL,
          ${WorkBoardReadModelKeys.FINISHED_AT} TEXT,
          ${WorkBoardReadModelKeys.UPDATED_AT} TEXT NOT NULL,
          ${WorkBoardReadModelKeys.FRESHNESS_DEADLINE} TEXT,
          ${WorkBoardReadModelKeys.MEMBERSHIP_DEADLINE} TEXT,
          ${WorkBoardReadModelKeys.SEARCH_TEXT} TEXT NOT NULL,
          ${WorkBoardReadModelKeys.PAYLOAD} TEXT NOT NULL,
          ${WorkBoardReadModelKeys.PROJECTED_AT} TEXT NOT NULL
        )
        """.trimIndent(),
      )
      statement.execute(
        "CREATE INDEX ${WorkBoardReadModelKeys.DISPLAY_INDEX} ON ${WorkBoardReadModelKeys.ITEMS_TABLE} " +
          "(${WorkBoardReadModelKeys.DISPLAY_PRIORITY}, ${WorkBoardReadModelKeys.UPDATED_AT} DESC, " +
          "${WorkBoardReadModelKeys.WORK_ID})",
      )
      statement.execute(
        "CREATE INDEX ${WorkBoardReadModelKeys.FINISHED_INDEX} ON ${WorkBoardReadModelKeys.ITEMS_TABLE} " +
          "(${WorkBoardReadModelKeys.FINISHED_AT})",
      )
      statement.execute(
        "CREATE TABLE ${WorkBoardReadModelKeys.STATE_TABLE} " +
          "(${WorkBoardReadModelKeys.ID} INTEGER PRIMARY KEY CHECK (${WorkBoardReadModelKeys.ID} = 1), " +
          "${WorkBoardReadModelKeys.COMPLETE} INTEGER NOT NULL)",
      )
      statement.execute(
        "INSERT INTO ${WorkBoardReadModelKeys.STATE_TABLE} (${WorkBoardReadModelKeys.ID}, " +
          "${WorkBoardReadModelKeys.COMPLETE}) VALUES (1, 0)",
      )
    }
  }
}

internal object WorkBoardReadModelDependencyMigration {
  fun apply(connection: Connection) {
    connection.createStatement().use { statement ->
      statement.executeUpdate("UPDATE ${WorkBoardReadModelKeys.STATE_TABLE} SET ${WorkBoardReadModelKeys.COMPLETE} = 0")
      statement.execute(
        "CREATE INDEX IF NOT EXISTS desktop_phase_board_group ON standalone_phase_status " +
          "(repository_identity, phase_id, " +
          "COALESCE(monitor_pr_key, upper(issue_key), workflow_id, branch_correlation), " +
          "length(run_sequence), run_sequence, invocation_id)",
      )
      statement.execute(
        "CREATE INDEX IF NOT EXISTS desktop_goal_issue_identity ON goal_issue_progress " +
          "(upper(trim(issue_key)), parent_workflow_id)",
      )
      statement.execute(
        "CREATE INDEX IF NOT EXISTS desktop_execution_identity_issue ON feature_task_execution_identities " +
          "(normalized_issue_key, route_scope, workflow_id)",
      )
      statement.execute(
        "CREATE INDEX IF NOT EXISTS desktop_verify_issue_identity ON feature_verify_workflows " +
          "(upper(trim(issue_key)), workflow_id)",
      )
      statement.execute(
        "CREATE INDEX IF NOT EXISTS desktop_standalone_workflow_identity ON standalone_phase_status " +
          "(workflow_id, invocation_id)",
      )
      statement.execute(
        "CREATE INDEX IF NOT EXISTS desktop_execution_invocation_identity ON ide_status_workflow_execution " +
          "(invocation_id, workflow_id)",
      )
    }
    installWorkBoardChangeTriggers(connection)
  }
}

internal object WorkBoardReadModelRebuildMigration {
  fun apply(connection: Connection) {
    connection.createStatement().use { statement ->
      statement.executeUpdate("UPDATE ${WorkBoardReadModelKeys.STATE_TABLE} SET ${WorkBoardReadModelKeys.COMPLETE} = 0")
    }
  }
}

internal object WorkBoardAppRunPlatformsMigration {
  private const val PAYLOAD_PARAMETER = 2
  private const val GUIDANCE_PARAMETER = 3
  private const val RECOVERY_GUIDANCE =
    "The saved board row is incompatible with this runtime. Preserve the database and contact support for recovery."

  fun apply(
    connection: Connection,
    diagnostics: RuntimeDiagnostics,
  ) {
    connection.createStatement().use { statement ->
      statement.execute(
        "CREATE TABLE IF NOT EXISTS ${WorkBoardReadModelKeys.INVALID_ITEMS_TABLE} (" +
          "${WorkBoardReadModelKeys.WORK_ID} TEXT PRIMARY KEY, " +
          "${WorkBoardReadModelKeys.PAYLOAD} TEXT NOT NULL, " +
          "${WorkBoardReadModelKeys.RECOVERY_GUIDANCE} TEXT NOT NULL)",
      )
    }
    val sql =
      "SELECT ${WorkBoardReadModelKeys.WORK_ID}, ${WorkBoardReadModelKeys.UPDATED_AT}, " +
        "${WorkBoardReadModelKeys.SEARCH_TEXT}, ${WorkBoardReadModelKeys.FRESHNESS_DEADLINE}, " +
        "${WorkBoardReadModelKeys.PROJECTED_AT}, ${WorkBoardReadModelKeys.MEMBERSHIP_DEADLINE}, " +
        "${WorkBoardReadModelKeys.PAYLOAD} FROM ${WorkBoardReadModelKeys.ITEMS_TABLE}"
    connection.createStatement().use { statement ->
      statement.executeQuery(sql).use { rows ->
        while (rows.next()) {
          val id = rows.getString(WorkBoardReadModelKeys.WORK_ID)
          val payload = rows.getString(WorkBoardReadModelKeys.PAYLOAD)
          val metadata =
            try {
              WorkBoardRowMetadata(
                id,
                WorkBoardReadModelTime.parse(rows.getString(WorkBoardReadModelKeys.UPDATED_AT)),
                rows.getString(WorkBoardReadModelKeys.SEARCH_TEXT),
                rows.getString(WorkBoardReadModelKeys.FRESHNESS_DEADLINE)?.let(WorkBoardReadModelTime::parse),
                WorkBoardReadModelTime.parse(rows.getString(WorkBoardReadModelKeys.PROJECTED_AT)),
                rows.getString(WorkBoardReadModelKeys.MEMBERSHIP_DEADLINE)?.let(WorkBoardReadModelTime::parse),
              )
            } catch (_: DateTimeParseException) {
              null
            }
          val valid = metadata?.let { WorkBoardReadModelPayloadCodec.decode(payload, it) } != null
          if (!valid) {
            connection.prepareStatement(
              "INSERT OR IGNORE INTO ${WorkBoardReadModelKeys.INVALID_ITEMS_TABLE} " +
                "(${WorkBoardReadModelKeys.WORK_ID}, ${WorkBoardReadModelKeys.PAYLOAD}, " +
                "${WorkBoardReadModelKeys.RECOVERY_GUIDANCE}) VALUES (?, ?, ?)",
            ).use { archive ->
              archive.setString(1, id)
              archive.setString(PAYLOAD_PARAMETER, payload)
              archive.setString(GUIDANCE_PARAMETER, RECOVERY_GUIDANCE)
              archive.executeUpdate()
            }
            diagnostics.warning("seam=work_board_read_model_migration work_id=$id value_used=preserved_invalid_row")
          }
        }
      }
    }
    WorkBoardReadModelRebuildMigration.apply(connection)
  }
}

internal object WorkflowStateVersionMigration {
  const val TARGET_VERSION = "0.4"

  fun apply(
    connection: Connection,
    diagnostics: RuntimeDiagnostics,
  ) {
    var migrated = 0
    listOf("feature_task_workflows", "feature_verify_workflows").forEach { table ->
      val modePredicate = if (table == "feature_task_workflows") "AND mode = 'runtime'" else ""
      connection.prepareStatement(
        "UPDATE $table SET contract_version = ? " +
          "WHERE contract_version IN ('0.1', '0.2', '0.3') $modePredicate " +
          "AND CASE WHEN json_valid(steps_json) THEN json_type(steps_json) = 'array' ELSE 0 END " +
          "AND CASE WHEN json_valid(artifacts_json) THEN json_type(artifacts_json) = 'object' ELSE 0 END",
      ).use { statement ->
        statement.setString(1, TARGET_VERSION)
        migrated += statement.executeUpdate()
      }
    }
    WorkBoardReadModelRebuildMigration.apply(connection)
    diagnostics.info(
      "seam=workflow_state_migration target_version=$TARGET_VERSION migrated_records=$migrated " +
        "value_used=preserved_payloads_and_invalidated_board",
    )
  }
}
