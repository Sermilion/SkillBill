package skillbill.infrastructure.sqlite

import skillbill.contracts.persistence.DesktopReadSchema
import java.nio.file.Files
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val DIAGNOSIS_TABLES =
  listOf(
    "block_incident_diagnosis_evidence",
    "block_incident_diagnosis_prerequisites",
    "block_incident_diagnosis_quarantine",
    "block_incident_diagnoses",
  )

class BlockIncidentMigrationsTest {
  @Test
  fun `product report migration preserves incidents and starts with empty report tables`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-product-reports-upgrade").resolve("upgrade.db")
    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      connection.createStatement().use { statement ->
        statement.executeUpdate(
          "INSERT INTO block_incidents (occurrence_token, contract_version, " +
            "generation, origin, reason, " +
            "observed_at, evidence_availability, deadline, exit_behavior, " +
            "identity_availability) VALUES " +
            "('bi_${"a".repeat(64)}', '0.1', 1, 'accepted_phase_block', 'kept incident', " +
            "'2026-10-21T12:00:00Z', 'database', '2026-10-21T12:05:00Z', " +
            "'unknown_historical', " +
            "'unknown_historical')",
        )
        statement.execute("DROP TABLE block_incident_report_links")
        statement.execute("DROP TABLE block_incident_reports")
        revertDesktopChangeSignals(connection)
        listOf("model_id", "phase_strategy_id", "provider_id", "reproduction_steps").forEach { column ->
          statement.execute("ALTER TABLE block_incident_diagnoses DROP COLUMN $column")
        }
        revertWorkBoardReadModel(connection)
        statement.executeUpdate("DELETE FROM schema_migrations WHERE version >= 80")
        statement.execute("PRAGMA user_version = 79")
        statement.executeUpdate(
          "UPDATE room_master_table SET identity_hash = " +
            "'7915a1b532fab60d83518e19b12c4aa0' WHERE id = 42",
        )
      }
    }

    DatabaseRuntime.migrateExistingDatabase(dbPath, SqliteTestDiagnostics)
    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      assertEquals(DesktopReadSchema.VERSION, scalarInt(connection, "PRAGMA user_version"))
      assertEquals(1, scalarInt(connection, "SELECT count(*) FROM block_incidents WHERE reason = 'kept incident'"))
      assertEquals(0, scalarInt(connection, "SELECT count(*) FROM block_incident_reports"))
      assertEquals(0, scalarInt(connection, "SELECT count(*) FROM block_incident_report_links"))
      connection.createStatement().use { statement ->
        statement.executeQuery(
          "SELECT create_unresolved, attempt_open, last_remote_failure FROM " +
            "block_incident_reports",
        ).use { rows -> assertFalse(rows.next()) }
        statement.executeQuery(
          "SELECT pending_comment_body, pending_comment_sha256, " +
            "pending_comment_recovery_attempts " +
            "FROM block_incident_report_links",
        ).use { rows -> assertFalse(rows.next()) }
      }
      assertTrue(
        scalarInt(
          connection,
          "SELECT count(*) FROM sqlite_master WHERE type = 'trigger' AND tbl_name " +
            "LIKE 'block_incident_report%'",
        ) > 0,
      )
    }
  }

  @Test
  fun `block incident migration upgrades version 65 without changing existing workflows`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-block-incident-upgrade").resolve("upgrade.db")
    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      connection.createStatement().use { statement ->
        statement.executeUpdate(
          "INSERT INTO feature_task_workflows(workflow_id, mode, contract_version) " +
            "VALUES ('wftr-existing', 'prose', '1.0')",
        )
        val blockIncidentTables =
          statement.executeQuery(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name LIKE " +
              "'block\\_incident%' ESCAPE '\\' " +
              "ORDER BY name = 'block_incidents'",
          ).use { rows -> generateSequence { if (rows.next()) rows.getString(1) else null }.toList() }
        blockIncidentTables.forEach { table -> statement.execute("DROP TABLE $table") }
        statement.execute("DROP TABLE feature_task_runtime_worker_generations")
        revertWorkBoardReadModel(connection)
        statement.executeUpdate("DELETE FROM schema_migrations WHERE version >= 66")
        statement.execute("PRAGMA user_version = 65")
      }
    }

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      assertEquals(
        1,
        scalarInt(
          connection,
          "SELECT count(*) FROM feature_task_workflows WHERE workflow_id = " +
            "'wftr-existing'",
        ),
      )
      assertEquals(DesktopReadSchema.VERSION, scalarInt(connection, "PRAGMA user_version"))
      assertTrue(
        scalarInt(
          connection,
          "SELECT count(*) FROM sqlite_master WHERE type = 'trigger' AND tbl_name = " +
            "'block_incidents'",
        ) > 0,
      )
    }
  }

  @Test
  fun `block incident migration preserves immutable occurrence rows and publishes revisions`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-block-incidents").resolve("incidents.db")
    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      connection.createStatement().use { statement ->
        statement.executeUpdate(
          "INSERT INTO block_incidents (occurrence_token, contract_version, " +
            "generation, origin, reason, " +
            "observed_at, evidence_availability, deadline, exit_behavior, " +
            "identity_availability) VALUES " +
            "('bi_${"a".repeat(64)}', '0.1', 1, 'accepted_phase_block', 'original failure', " +
            "'2026-10-21T12:00:00Z', 'database', '2026-10-21T12:05:00Z', " +
            "'unknown_historical', " +
            "'unknown_historical')",
        )
        assertFailsWith<SQLException> {
          statement.executeUpdate(
            "UPDATE block_incidents SET reason = 'rewritten failure' WHERE occurrence_token = 'bi_${"a".repeat(64)}'",
          )
        }
      }
      assertEquals(1, scalarInt(connection, "SELECT revision FROM desktop_change_revisions WHERE topic = 'goals'"))
      assertEquals(1, scalarInt(connection, "SELECT revision FROM desktop_change_revisions WHERE topic = 'phases'"))
      assertEquals(1, scalarInt(connection, "SELECT count(*) FROM block_incidents"))
    }
  }

  @Test
  fun `block incident diagnosis migration upgrades version 76 and keeps diagnosis history immutable`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-block-diagnosis-upgrade").resolve("upgrade.db")
    val token = "bi_${"a".repeat(64)}"
    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      connection.createStatement().use { statement ->
        statement.executeUpdate(
          "INSERT INTO block_incidents (occurrence_token, contract_version, " +
            "generation, origin, reason, " +
            "observed_at, evidence_availability, deadline, exit_behavior, " +
            "identity_availability) VALUES " +
            "('$token', '0.1', 1, 'accepted_phase_block', 'original failure', " +
            "'2026-10-21T12:00:00Z', 'database', '2026-10-21T12:05:00Z', " +
            "'unknown_historical', " +
            "'unknown_historical')",
        )
        DIAGNOSIS_TABLES.forEach { table -> statement.execute("DROP TABLE $table") }
        statement.execute("DROP TABLE IF EXISTS block_incident_recovery_attempts")
        statement.execute("DROP TABLE IF EXISTS feature_task_runtime_worker_generations")
        statement.execute("DROP TABLE block_incident_report_links")
        statement.execute("DROP TABLE block_incident_reports")
        revertWorkBoardReadModel(connection)
        statement.executeUpdate("DELETE FROM schema_migrations WHERE version >= 77")
        statement.execute("PRAGMA user_version = 76")
      }
    }

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      assertEquals(1, scalarInt(connection, "SELECT count(*) FROM block_incidents WHERE occurrence_token = '$token'"))
      DIAGNOSIS_TABLES.forEach { table ->
        assertEquals(1, scalarInt(connection, "SELECT count(*) FROM sqlite_master WHERE name = '$table'"))
      }
      connection.createStatement().use { statement ->
        statement.executeUpdate(
          "INSERT INTO block_incident_diagnoses (occurrence_token, revision, " +
            "summary, what_happened, certainty, " +
            "responsible_category, proposed_recovery_action, no_workaround_known, " +
            "degradations, " +
            "investigator_kind, completed_at) VALUES ('$token', 2, 'summary', 'what " +
            "happened', 'unknown', " +
            "'unresolved', 'none', 1, '', 'deterministic', '2026-10-21T12:01:00Z')",
        )
        assertFailsWith<SQLException> {
          statement.executeUpdate("UPDATE block_incident_diagnoses SET summary = 'rewritten'")
        }
        assertFailsWith<SQLException> { statement.executeUpdate("DELETE FROM block_incident_diagnoses") }
      }
    }
  }

  @Test
  fun `block incident recovery migration preserves existing incidents and adds a unique attempt key`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-block-recovery-upgrade").resolve("upgrade.db")
    val token = "bi_${"b".repeat(64)}"
    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      connection.createStatement().use { statement ->
        statement.executeUpdate(
          "INSERT INTO feature_task_workflows(workflow_id, mode, contract_version) " +
            "VALUES ('wftr-recovery-existing', 'prose', '1.0')",
        )
        statement.executeUpdate(
          "INSERT INTO block_incidents (occurrence_token, contract_version, " +
            "generation, origin, reason, " +
            "observed_at, evidence_availability, deadline, exit_behavior, " +
            "identity_availability) VALUES " +
            "('$token', '0.1', 1, 'accepted_phase_block', 'original failure', " +
            "'2026-10-21T12:00:00Z', 'database', '2026-10-21T12:05:00Z', " +
            "'unknown_historical', " +
            "'unknown_historical')",
        )
        statement.execute("DROP TABLE block_incident_recovery_attempts")
        statement.execute("DROP TABLE feature_task_runtime_worker_generations")
        statement.execute("DROP TABLE block_incident_report_links")
        statement.execute("DROP TABLE block_incident_reports")
        revertDesktopChangeSignals(connection)
        listOf("model_id", "phase_strategy_id", "provider_id", "reproduction_steps").forEach { column ->
          statement.execute("ALTER TABLE block_incident_diagnoses DROP COLUMN $column")
        }
        revertWorkBoardReadModel(connection)
        statement.executeUpdate("DELETE FROM schema_migrations WHERE version >= 78")
        statement.execute("PRAGMA user_version = 77")
      }
    }

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      assertEquals(1, scalarInt(connection, "SELECT count(*) FROM block_incidents WHERE occurrence_token = '$token'"))
      assertEquals(
        1,
        scalarInt(
          connection,
          "SELECT count(*) FROM feature_task_workflows WHERE workflow_id = " +
            "'wftr-recovery-existing'",
        ),
      )
      assertEquals(
        1,
        scalarInt(
          connection,
          "SELECT count(*) FROM sqlite_master " +
            "WHERE name = 'block_incident_recovery_attempts'",
        ),
      )
      assertEquals(
        2,
        scalarInt(
          connection,
          "SELECT count(*) FROM pragma_index_list('block_incident_recovery_attempts') " +
            "WHERE \"unique\" = 1",
        ),
      )
      assertEquals(
        3,
        scalarInt(
          connection,
          "SELECT count(*) FROM sqlite_master WHERE type = 'trigger' " +
            "AND tbl_name = 'block_incident_recovery_attempts'",
        ),
      )
    }
  }
}
