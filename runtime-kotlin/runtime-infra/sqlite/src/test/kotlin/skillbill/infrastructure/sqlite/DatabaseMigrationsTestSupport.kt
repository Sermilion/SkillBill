package skillbill.infrastructure.sqlite

import org.junit.jupiter.api.Assumptions
import skillbill.contracts.JsonCodec
import skillbill.error.shellcontent.InvalidWorkListRowError
import skillbill.infrastructure.sqlite.core.migration.DatabaseMigrations
import skillbill.infrastructure.sqlite.core.schema.DatabaseRuntime
import skillbill.infrastructure.sqlite.core.schema.DatabaseSchema
import skillbill.infrastructure.sqlite.worklist.SQLiteWorkListRepository
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Statement
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private val GOAL_OUT_OF_BAND_ACCEPTANCE_ARTIFACT_KEY =
  DurableWorkflowArtifactFamily.GOAL_OUT_OF_BAND_ACCEPTANCE.label()
private val GOAL_REVIEW_POLICY_ARTIFACT_KEY =
  DurableWorkflowArtifactFamily.GOAL_REVIEW_POLICY.label()

internal fun seedVersionKeyedLedger(dbPath: Path) {
  DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
    connection.createStatement().use { statement ->
      statement.executeUpdate("DROP TABLE schema_migrations")
      statement.executeUpdate(VERSION_KEYED_SCHEMA_MIGRATIONS_SQL)
      DatabaseMigrations.migrations.forEach { migration ->
        statement.executeUpdate(
          "INSERT INTO schema_migrations (version, name) VALUES (${migration.version}, '${migration.name}')",
        )
      }
    }
  }
}

internal val VALID_LEGACY_GOAL_ACCEPTANCE: Map<String, Any?> =
  mapOf(
    "subtask_id" to 2,
    "commit_sha" to "legacy-commit",
    "reason" to "accepted outside the normal review path",
    "accepted_at" to "2026-09-17T10:00:00Z",
  )

internal fun seedLegacyGoalRunnerControlsMigrationFixture(
  dbPath: Path,
  acceptances: List<Map<String, Any?>> = listOf(VALID_LEGACY_GOAL_ACCEPTANCE),
) {
  DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
    connection.createStatement().use { statement ->
      statement.executeUpdate(
        "DELETE FROM schema_migrations WHERE name = 'migrate-legacy-goal-runner-controls'",
      )
    }
    val artifactsJson =
      JsonCodec.mapToJsonString(
        mapOf(
          GOAL_REVIEW_POLICY_ARTIFACT_KEY to
            mapOf(
              "code_review_mode" to CodeReviewExecutionMode.INLINE.wireValue,
            ),
          GOAL_OUT_OF_BAND_ACCEPTANCE_ARTIFACT_KEY to acceptances,
        ),
      )
    connection.prepareStatement(
      """
      INSERT INTO feature_task_workflows (
        workflow_id, mode, contract_version, workflow_status, current_step_id, steps_json, artifacts_json, issue_key
      ) VALUES (?, 'runtime', '0.1', 'paused', 'plan', '[]', ?, 'SKILL-356')
      """.trimIndent(),
    ).use { statement ->
      statement.setString(1, "wftr-legacy-goal-parent")
      statement.setString(2, artifactsJson)
      statement.executeUpdate()
    }
  }
}

internal fun rejectedDiagnosticIndexNames(connection: Connection): List<String> =
  connection.createStatement().use { statement ->
    statement.executeQuery(
      """
      SELECT name FROM sqlite_master
      WHERE type = 'index' AND tbl_name = 'rejected_output_diagnostics' AND name NOT LIKE 'sqlite_%'
      ORDER BY name
      """.trimIndent(),
    ).use { rows -> buildList { while (rows.next()) add(rows.getString("name")) } }
  }

internal fun seedPreRepairTurnDiagnosticsForMigration29(
  dbPath: Path,
  payload: ByteArray,
  sha: String,
  identity: String,
) {
  DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
    connection.createStatement().use { statement ->
      statement.executeUpdate(
        "DELETE FROM schema_migrations WHERE name = 'rekey-diagnostic-evidence-by-repair-turn'",
      )
      statement.executeUpdate("DROP TABLE producer_output_evidence")
      statement.executeUpdate(PRE_REPAIR_TURN_PRODUCER_OUTPUT_EVIDENCE_SQL)
      statement.executeUpdate("DROP TABLE rejected_output_diagnostics")
      statement.executeUpdate(PRE_REPAIR_TURN_REJECTED_OUTPUT_DIAGNOSTICS_SQL)

      statement.executeUpdate(
        """
        CREATE INDEX idx_rejected_output_diagnostics_selector
          ON rejected_output_diagnostics(workflow_id, phase_id, attempt)
        """.trimIndent(),
      )
    }
    connection.prepareStatement(
      """
      INSERT INTO producer_output_evidence
      (workflow_id, phase_id, generation, attempt, agent_id, model, recorded_at, byte_size, sha256, payload)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      """.trimIndent(),
    ).use { statement ->
      statement.setString(1, "wftr-20260811-201509-n7hz")
      statement.setString(2, "validate")
      statement.setInt(3, 0)
      statement.setInt(4, 1)
      statement.setString(5, "cursor")
      statement.setString(6, "gpt")
      statement.setString(7, "2026-08-11T21:07:48Z")
      statement.setLong(8, payload.size.toLong())
      statement.setString(9, sha)
      statement.setBytes(10, payload)
      statement.executeUpdate()
    }
    seedPreRepairTurnDiagnosticRow(connection, payload, sha, identity)
  }
}

internal fun seedPreRepairTurnDiagnosticRow(
  connection: Connection,
  payload: ByteArray,
  sha: String,
  identity: String,
) {
  connection.prepareStatement(
    """
    INSERT INTO rejected_output_diagnostics
    (identity, workflow_id, phase_id, attempt, rule, rejection_path, reason, agent_id, model,
     recorded_at, byte_size, sha256, lifecycle, payload)
    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'stored', ?)
    """.trimIndent(),
  ).use { statement ->
    statement.setString(1, identity)
    statement.setString(2, "wftr-20260811-201509-n7hz")
    statement.setString(3, "audit")
    statement.setInt(4, 2)
    statement.setString(5, "phase-output-schema")
    statement.setString(6, "/status")
    statement.setString(7, "rejected")
    statement.setString(8, "cursor")
    statement.setString(9, "gpt")
    statement.setString(10, "2026-08-11T20:55:00Z")
    statement.setLong(11, payload.size.toLong())
    statement.setString(12, sha)
    statement.setBytes(13, payload)
    statement.executeUpdate()
  }
}

internal fun producerEvidenceRepairTurns(connection: Connection): List<Int> =
  connection.createStatement().use { statement ->
    statement.executeQuery("SELECT repair_turn FROM producer_output_evidence ORDER BY repair_turn").use { rows ->
      buildList { while (rows.next()) add(rows.getInt("repair_turn")) }
    }
  }

internal fun producerEvidenceByteSizes(connection: Connection): List<Long> =
  connection.createStatement().use { statement ->
    statement.executeQuery("SELECT byte_size FROM producer_output_evidence").use { rows ->
      buildList { while (rows.next()) add(rows.getLong("byte_size")) }
    }
  }

internal fun rejectedDiagnosticIdentitiesAndTurns(connection: Connection): List<Pair<String, Int>> =
  connection.createStatement().use { statement ->
    statement.executeQuery("SELECT identity, repair_turn FROM rejected_output_diagnostics").use { rows ->
      buildList { while (rows.next()) add(rows.getString("identity") to rows.getInt("repair_turn")) }
    }
  }

internal fun clearProducerOutputEvidenceMigrationRecords(connection: Connection) {
  connection.createStatement().use { statement ->
    statement.executeUpdate(
      """
      DELETE FROM schema_migrations
      WHERE name IN (
        'rekey-producer-output-evidence-by-generation',
        'rekey-producer-output-evidence-by-agent',
        'rekey-diagnostic-evidence-by-repair-turn'
      )
      """.trimIndent(),
    )
  }
}

internal fun seedPreAgentProducerEvidenceForMigration28(
  dbPath: Path,
  payload: ByteArray,
  sha: String,
) {
  DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
    connection.createStatement().use { statement ->
      clearProducerOutputEvidenceMigrationRecords(connection)
      statement.executeUpdate("DROP TABLE producer_output_evidence")
      statement.executeUpdate(PRE_AGENT_PRODUCER_OUTPUT_EVIDENCE_SQL)
    }
    connection.prepareStatement(
      """
      INSERT INTO producer_output_evidence
      (workflow_id, phase_id, generation, attempt, agent_id, model, recorded_at, byte_size, sha256, payload)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      """.trimIndent(),
    ).use { statement ->
      statement.setString(1, "wftr-20260808-175505-c5po")
      statement.setString(2, "review")
      statement.setInt(3, 0)
      statement.setInt(4, 2)
      statement.setString(5, "claude")
      statement.setString(6, "claude-opus")
      statement.setString(7, "2026-08-08T18:49:48Z")
      statement.setLong(8, payload.size.toLong())
      statement.setString(9, sha)
      statement.setBytes(10, payload)
      statement.executeUpdate()
    }
  }
}

internal fun assertMigration28Applied(connection: Connection) {
  assertNotNull(
    migrationRows(connection).singleOrNull { row ->
      row.version == 28 && row.name == "rekey-producer-output-evidence-by-agent"
    },
  )
}

internal fun assertProducerEvidenceRowSurvivedMigration28(
  connection: Connection,
  payload: ByteArray,
  sha: String,
) {
  connection.prepareStatement(
    """
    SELECT generation, attempt, agent_id, recorded_at, sha256, payload
    FROM producer_output_evidence
    WHERE workflow_id = ? AND phase_id = ? AND generation = ? AND attempt = ?
    """.trimIndent(),
  ).use { statement ->
    statement.setString(1, "wftr-20260808-175505-c5po")
    statement.setString(2, "review")
    statement.setInt(3, 0)
    statement.setInt(4, 2)
    statement.executeQuery().use { rows ->
      check(rows.next()) { "SKILL-15-shaped producer evidence row must survive migration 28." }
      assertEquals(0, rows.getInt("generation"))
      assertEquals(2, rows.getInt("attempt"))
      assertEquals("claude", rows.getString("agent_id"))
      assertEquals("2026-08-08T18:49:48Z", rows.getString("recorded_at"))
      assertEquals(sha, rows.getString("sha256"))
      assertContentEquals(payload, rows.getBytes("payload"))
      assertFalse(rows.next())
    }
  }
}

internal fun assertProducerEvidenceMigrationDdlParity(
  migratedDdl: String,
  baseSchemaDirPrefix: String,
) {
  val baseSchemaPath = Files.createTempDirectory(baseSchemaDirPrefix).resolve("base.db")
  DriverManager.getConnection("jdbc:sqlite:$baseSchemaPath").use { connection ->
    DatabaseSchema.createBaseSchema(connection)
    assertEquals(
      producerEvidenceDdl(connection),
      migratedDdl,
      "the migration DDL and the base-schema DDL must not drift",
    )
  }
}

internal fun seedPartiallyHealedLegacyWorkflow(dbPath: Path) {
  DriverManager.getConnection("jdbc:sqlite:$dbPath").use { connection ->
    connection.createStatement().use { statement ->
      statement.execute(
        """
        CREATE TABLE feature_task_workflows (
          workflow_id TEXT PRIMARY KEY,
          session_id TEXT NOT NULL DEFAULT '',
          workflow_name TEXT NOT NULL DEFAULT 'bill-feature-task',
          mode TEXT NOT NULL,
          implementation_skill TEXT NOT NULL DEFAULT '',
          contract_version TEXT NOT NULL,
          workflow_status TEXT NOT NULL DEFAULT 'pending',
          current_step_id TEXT NOT NULL DEFAULT '',
          steps_json TEXT NOT NULL DEFAULT '',
          artifacts_json TEXT NOT NULL DEFAULT '',
          issue_key TEXT,
          started_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
          updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
          state_entered_at TEXT,
          state_entered_at_estimated INTEGER,
          finished_at TEXT
        )
        """.trimIndent(),
      )
      statement.executeUpdate(
        """
        INSERT INTO feature_task_workflows (
          workflow_id, mode, contract_version, workflow_status, started_at, state_entered_at,
          state_entered_at_estimated
        ) VALUES ('wfl-partial-heal', 'prose', '0.1', 'running', '2026-05-01T10:00:00Z',
                  '2026-05-02T11:00:00Z', NULL)
        """.trimIndent(),
      )
    }
  }
}

internal fun assertPartiallyHealedStateEntry(connection: Connection) {
  assertEquals(
    "2026-05-02T11:00:00Z",
    tableColumnValue(connection, "feature_task_workflows", "workflow_id", "wfl-partial-heal", "state_entered_at"),
  )
  assertEquals(
    1,
    tableColumnValue(
      connection,
      "feature_task_workflows",
      "workflow_id",
      "wfl-partial-heal",
      "state_entered_at_estimated",
    ),
  )
}

internal fun seedLegacyStateEntryFallbacks(dbPath: Path) {
  DriverManager.getConnection("jdbc:sqlite:$dbPath").use { connection ->
    connection.createStatement().use { statement ->
      createLegacyStateEntryTables(statement)
      insertLegacyWorkflowStateEntryRows(statement)
      insertLegacyGoalStateEntryRows(statement)
    }
  }
}

internal fun createLegacyStateEntryTables(statement: Statement) {
  statement.execute(
    """
    CREATE TABLE feature_task_workflows (
      workflow_id TEXT PRIMARY KEY, session_id TEXT NOT NULL DEFAULT '',
      workflow_name TEXT NOT NULL DEFAULT 'bill-feature-task', mode TEXT NOT NULL,
      implementation_skill TEXT NOT NULL DEFAULT '', contract_version TEXT NOT NULL,
      workflow_status TEXT NOT NULL DEFAULT 'pending', current_step_id TEXT NOT NULL DEFAULT '',
      steps_json TEXT NOT NULL DEFAULT '', artifacts_json TEXT NOT NULL DEFAULT '',
      started_at TEXT NOT NULL, updated_at TEXT NOT NULL, finished_at TEXT
    )
    """.trimIndent(),
  )
  statement.execute(
    """
    CREATE TABLE goal_issue_progress (
      parent_workflow_id TEXT NOT NULL, issue_key TEXT NOT NULL,
      total_invocations INTEGER NOT NULL DEFAULT 0, total_blocks INTEGER NOT NULL DEFAULT 0,
      total_resumes INTEGER NOT NULL DEFAULT 0, first_started_at TEXT, last_activity_at TEXT,
      last_blocked_at TEXT, latest_segment_workflow_id TEXT, last_blocked_segment_workflow_id TEXT,
      finished_at TEXT, status TEXT, subtasks_complete INTEGER, subtasks_blocked INTEGER,
      subtasks_skipped INTEGER, mode TEXT NOT NULL DEFAULT 'runtime', finished_event_emitted_at TEXT,
      PRIMARY KEY (parent_workflow_id, issue_key)
    )
    """.trimIndent(),
  )
}

internal fun insertLegacyWorkflowStateEntryRows(statement: Statement) {
  statement.executeUpdate(
    """
    INSERT INTO feature_task_workflows (workflow_id, mode, contract_version, started_at, updated_at, finished_at)
    VALUES
      ('wfl-finished', 'prose', '0.1', '2026-05-01T10:00:00Z', '2026-05-02T10:00:00Z', '2026-05-03T10:00:00Z'),
      ('wfl-updated', 'prose', '0.1', '2026-05-01T10:00:00Z', '2026-05-02T10:00:00Z', NULL),
      ('wfl-started', 'prose', '0.1', '2026-05-01T10:00:00Z', '', NULL),
      ('wfl-no-time', 'prose', '0.1', '', '', NULL)
    """.trimIndent(),
  )
}

internal fun insertLegacyGoalStateEntryRows(statement: Statement) {
  statement.executeUpdate(
    """
    INSERT INTO goal_issue_progress (parent_workflow_id, issue_key, first_started_at, last_activity_at, finished_at)
    VALUES
      ('goal-finished', 'SKILL-117', '2026-05-01T10:00:00Z', '2026-05-02T10:00:00Z', '2026-05-03T10:00:00Z'),
      ('goal-activity', 'SKILL-117', '2026-05-01T10:00:00Z', '2026-05-02T10:00:00Z', NULL),
      ('goal-started', 'SKILL-117', '2026-05-01T10:00:00Z', '', NULL),
      ('goal-no-time', 'SKILL-117', '', '', NULL)
    """.trimIndent(),
  )
}

internal fun assertLegacyStateEntryFallbacks(connection: Connection) {
  assertStateEntryFallbacks(
    connection,
    "feature_task_workflows",
    "workflow_id",
    "wfl",
    listOf("finished", "updated", "started"),
  )
  assertStateEntryFallbacks(
    connection,
    "goal_issue_progress",
    "parent_workflow_id",
    "goal",
    listOf("finished", "activity", "started"),
  )
  assertEstimatedMissingStateEntries(connection, "feature_task_workflows", "workflow_id", "wfl-no-time")
  assertEstimatedMissingStateEntries(connection, "goal_issue_progress", "parent_workflow_id", "goal-no-time")
  assertFailsWith<InvalidWorkListRowError> { SQLiteWorkListRepository(connection).list() }
}

internal fun assertStateEntryFallbacks(
  connection: Connection,
  table: String,
  keyColumn: String,
  prefix: String,
  suffixes: List<String>,
) {
  val expected = listOf("2026-05-03T10:00:00Z", "2026-05-02T10:00:00Z", "2026-05-01T10:00:00Z")
  suffixes.zip(expected).forEach { (suffix, timestamp) ->
    assertEquals(timestamp, tableColumnValue(connection, table, keyColumn, "$prefix-$suffix", "state_entered_at"))
  }
}

internal fun assertEstimatedMissingStateEntries(
  connection: Connection,
  table: String,
  keyColumn: String,
  rowKey: String,
) {
  assertEquals(null, nullableTableColumnValue(connection, table, keyColumn, rowKey, "state_entered_at"))
  assertEquals(1, tableColumnValue(connection, table, keyColumn, rowKey, "state_entered_at_estimated"))
}

internal fun assertMissingTimestampRowsRemainUnchanged(connection: Connection) {
  connection.createStatement().use { statement ->
    statement.execute(
      """
      CREATE TRIGGER reject_missing_timestamp_rewrite
      BEFORE UPDATE ON feature_task_workflows
      BEGIN
        SELECT RAISE(ABORT, 'missing legacy timestamp must not be rewritten');
      END
      """.trimIndent(),
    )
  }
}

internal fun createLegacyReviewRunsDatabase(dbPath: Path) {
  DriverManager.getConnection("jdbc:sqlite:$dbPath").use { connection ->
    connection.createStatement().use { statement ->
      statement.execute(CREATE_LEGACY_REVIEW_RUNS_SQL)
    }
    connection.prepareStatement(
      """
      INSERT INTO review_runs (review_run_id, routed_skill, raw_text)
      VALUES (?, ?, ?)
      """.trimIndent(),
    ).use { statement ->
      statement.setString(1, "rvw-legacy-001")
      statement.setString(2, "bill-kotlin-code-review")
      statement.setString(3, "legacy review")
      statement.executeUpdate()
    }
  }
}

internal fun createLegacyFeatureImplementSessionsDatabase(dbPath: Path) {
  DriverManager.getConnection("jdbc:sqlite:$dbPath").use { connection ->
    connection.createStatement().use { statement ->
      statement.execute(CREATE_LEGACY_FEATURE_IMPLEMENT_SESSIONS_SQL)
      statement.execute(
        """
        CREATE TABLE schema_migrations (
          version INTEGER PRIMARY KEY,
          name TEXT NOT NULL UNIQUE,
          applied_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
        )
        """.trimIndent(),
      )
    }
    connection.prepareStatement(
      """
      INSERT INTO schema_migrations (version, name)
      VALUES (?, ?)
      """.trimIndent(),
    ).use { statement ->
      DatabaseMigrations.migrations.filterNot { migration ->
        migration.name == "ensure-schema-columns-and-heals"
      }.forEach { migration ->
        statement.setInt(1, migration.version)
        statement.setString(2, migration.name)
        statement.executeUpdate()
      }
    }
  }
}

internal fun createLegacyFeatureTaskRuntimeSessionsDatabase(dbPath: Path) {
  DriverManager.getConnection("jdbc:sqlite:$dbPath").use { connection ->
    connection.createStatement().use { statement ->
      statement.execute(CREATE_LEGACY_FEATURE_TASK_RUNTIME_SESSIONS_SQL)
      statement.execute(
        """
        CREATE TABLE schema_migrations (
          version INTEGER PRIMARY KEY,
          name TEXT NOT NULL UNIQUE,
          applied_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
        )
        """.trimIndent(),
      )
    }
    connection.prepareStatement("INSERT INTO schema_migrations (version, name) VALUES (?, ?)").use { statement ->
      DatabaseMigrations.migrations.filterNot { migration ->
        migration.name == "ensure-schema-columns-and-heals"
      }.forEach { migration ->
        statement.setInt(1, migration.version)
        statement.setString(2, migration.name)
        statement.executeUpdate()
      }
    }
    connection.createStatement().use { statement ->
      statement.executeUpdate("INSERT INTO feature_task_runtime_sessions (session_id) VALUES ('ftr-pre-91')")
    }
  }
}

internal fun createLegacyLifecycleSessionsWithoutStartsDatabase(dbPath: Path) {
  DriverManager.getConnection("jdbc:sqlite:$dbPath").use { connection ->
    connection.createStatement().use { statement ->
      statement.execute(CREATE_LEGACY_FEATURE_IMPLEMENT_SESSIONS_WITHOUT_START_SQL)
      statement.execute(CREATE_LEGACY_FEATURE_TASK_WORKFLOWS_SQL)
      statement.execute(CREATE_LEGACY_FEATURE_VERIFY_SESSIONS_WITHOUT_START_SQL)
      statement.execute(CREATE_LEGACY_QUALITY_CHECK_SESSIONS_WITHOUT_START_SQL)
      statement.execute(
        """
        CREATE TABLE schema_migrations (
          version INTEGER PRIMARY KEY,
          name TEXT NOT NULL UNIQUE,
          applied_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
        )
        """.trimIndent(),
      )
    }
    connection.prepareStatement("INSERT INTO schema_migrations (version, name) VALUES (?, ?)").use { statement ->
      DatabaseMigrations.migrations.filterNot { migration ->
        migration.name == "ensure-schema-columns-and-heals"
      }.forEach { migration ->
        statement.setInt(1, migration.version)
        statement.setString(2, migration.name)
        statement.executeUpdate()
      }
    }
    connection.createStatement().use { statement ->
      statement.executeUpdate("INSERT INTO feature_implement_sessions (session_id) VALUES ('fis-legacy-duration')")
      statement.executeUpdate(
        """
        INSERT INTO feature_task_workflows (
          workflow_id, session_id, mode, implementation_skill, contract_version,
          workflow_status, current_step_id, steps_json, artifacts_json, started_at, updated_at
        ) VALUES (
          'wf-legacy-duration', 'fis-legacy-duration', 'runtime', 'bill-feature', '0.1',
          'running', 'implement', '[]', '{}', '$LEGACY_FEATURE_TASK_WORKFLOW_STARTED_AT',
          '$LEGACY_FEATURE_TASK_WORKFLOW_STARTED_AT'
        )
        """.trimIndent(),
      )
      statement.executeUpdate("INSERT INTO feature_verify_sessions (session_id) VALUES ('fvs-legacy-start')")
      statement.executeUpdate("INSERT INTO quality_check_sessions (session_id) VALUES ('qcs-legacy-start')")
    }
  }
}

internal fun createLegacyGoalSubtaskEventsDatabase(dbPath: Path) {
  DriverManager.getConnection("jdbc:sqlite:$dbPath").use { connection ->
    connection.createStatement().use { statement ->
      statement.execute(CREATE_LEGACY_GOAL_SUBTASK_EVENTS_SQL)
      statement.execute(
        """
        CREATE TABLE schema_migrations (
          version INTEGER PRIMARY KEY,
          name TEXT NOT NULL UNIQUE,
          applied_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
        )
        """.trimIndent(),
      )
    }
    connection.prepareStatement("INSERT INTO schema_migrations (version, name) VALUES (?, ?)").use { statement ->
      DatabaseMigrations.migrations.filterNot { migration ->
        migration.name == "ensure-schema-columns-and-heals"
      }.forEach { migration ->
        statement.setInt(1, migration.version)
        statement.setString(2, migration.name)
        statement.executeUpdate()
      }
    }
  }
}

internal fun createLegacyFeedbackEventsDatabase(dbPath: Path) {
  DriverManager.getConnection("jdbc:sqlite:$dbPath").use { connection ->
    connection.createStatement().use { statement ->
      statement.execute("PRAGMA foreign_keys = ON")
      statement.execute(CREATE_LEGACY_VERIFY_REVIEW_RUNS_SQL)
      statement.execute(CREATE_LEGACY_FINDINGS_SQL)
      statement.execute(CREATE_LEGACY_FEEDBACK_EVENTS_SQL)
    }
    connection.prepareStatement(
      """
      INSERT INTO review_runs (review_run_id, raw_text)
      VALUES (?, ?)
      """.trimIndent(),
    ).use { statement ->
      statement.setString(1, "rvw-legacy-002")
      statement.setString(2, "legacy review")
      statement.executeUpdate()
    }
    connection.prepareStatement(
      """
      INSERT INTO findings (review_run_id, finding_id, severity, confidence, location, description, finding_text)
      VALUES (?, ?, ?, ?, ?, ?, ?)
      """.trimIndent(),
    ).use { statement ->
      statement.setString(1, "rvw-legacy-002")
      statement.setString(2, "F-001")
      statement.setString(3, "Major")
      statement.setString(4, "High")
      statement.setString(5, "README.md:1")
      statement.setString(6, "legacy finding")
      statement.setString(7, "legacy finding text")
      statement.executeUpdate()
    }
    connection.prepareStatement(
      """
      INSERT INTO feedback_events (review_run_id, finding_id, event_type, note, created_at)
      VALUES (?, ?, ?, ?, ?)
      """.trimIndent(),
    ).use { statement ->
      statement.setString(1, "rvw-legacy-002")
      statement.setString(2, "F-001")
      statement.setString(3, "dismissed")
      statement.setString(4, "legacy note")
      statement.setString(5, "2026-04-23 00:00:00")
      statement.executeUpdate()
    }
  }
}

internal fun reviewSessionId(
  connection: Connection,
  reviewRunId: String,
): String =
  connection.prepareStatement(
    """
    SELECT review_session_id
    FROM review_runs
    WHERE review_run_id = ?
    """.trimIndent(),
  ).use { statement ->
    statement.setString(1, reviewRunId)
    statement.executeQuery().use { resultSet ->
      resultSet.next()
      resultSet.getString(1)
    }
  }

internal fun feedbackEventsSchemaSql(connection: Connection): String =
  connection.prepareStatement(
    """
    SELECT sql
    FROM sqlite_master
    WHERE type = 'table' AND name = 'feedback_events'
    """.trimIndent(),
  ).use { statement ->
    statement.executeQuery().use { resultSet ->
      resultSet.next()
      resultSet.getString(1)
    }
  }

internal fun reviewRunsSchemaSql(connection: Connection): String =
  connection.prepareStatement(
    """
    SELECT sql
    FROM sqlite_master
    WHERE type = 'table' AND name = 'review_runs'
    """.trimIndent(),
  ).use { statement ->
    statement.executeQuery().use { resultSet ->
      resultSet.next()
      resultSet.getString(1)
    }
  }

internal fun feedbackEventType(
  connection: Connection,
  reviewRunId: String,
  findingId: String,
): String =
  connection.prepareStatement(
    """
    SELECT event_type
    FROM feedback_events
    WHERE review_run_id = ? AND finding_id = ?
    """.trimIndent(),
  ).use { statement ->
    statement.setString(1, reviewRunId)
    statement.setString(2, findingId)
    statement.executeQuery().use { resultSet ->
      resultSet.next()
      resultSet.getString(1)
    }
  }

internal fun findingIssueCategory(
  connection: Connection,
  reviewRunId: String,
  findingId: String,
): String =
  connection.prepareStatement(
    """
    SELECT issue_category
    FROM findings
    WHERE review_run_id = ? AND finding_id = ?
    """.trimIndent(),
  ).use { statement ->
    statement.setString(1, reviewRunId)
    statement.setString(2, findingId)
    statement.executeQuery().use { resultSet ->
      resultSet.next()
      resultSet.getString(1)
    }
  }

internal fun seedLegacyReviewRunAttributionVariants(dbPath: Path): Int {
  DriverManager.getConnection("jdbc:sqlite:$dbPath").use { connection ->
    connection.createStatement().use { statement ->
      statement.execute(
        """
        CREATE TABLE review_runs (
          review_run_id TEXT PRIMARY KEY,
          routed_skill TEXT,
          detected_scope TEXT,
          detected_stack TEXT,
          execution_mode TEXT,
          raw_text TEXT NOT NULL
        )
        """.trimIndent(),
      )
    }
    connection.prepareStatement(
      "INSERT INTO review_runs (review_run_id, routed_skill, detected_scope, detected_stack, raw_text) " +
        "VALUES (?, ?, ?, ?, 'raw')",
    ).use { statement ->
      LEGACY_ROUTED_SKILL_VARIANTS.forEachIndexed { index, routedSkill ->
        statement.setString(1, "rvw-skill-$index")
        statement.setString(2, routedSkill)
        statement.setString(3, "commit range (main..HEAD)")
        statement.setString(4, "kotlin")
        statement.addBatch()
      }
      LEGACY_STACK_VARIANTS.forEachIndexed { index, stack ->
        statement.setString(1, "rvw-stack-$index")
        statement.setString(2, "bill-kmp-code-review")
        statement.setString(3, "pull request (#204)")
        statement.setString(4, stack)
        statement.addBatch()
      }
      statement.setString(1, "rvw-ambiguous")
      statement.setString(2, AMBIGUOUS_ROUTED_SKILL)
      statement.setString(3, "whatever the agent felt like")
      statement.setString(4, "kotlin, ios")
      statement.addBatch()
      statement.executeBatch()
    }
  }
  return LEGACY_ROUTED_SKILL_VARIANTS.size + LEGACY_STACK_VARIANTS.size + 1
}

internal fun requireGatedStore(gate: String): Path {
  val configured = System.getenv(gate)?.takeIf { it.isNotBlank() }
  Assumptions.assumeTrue(
    configured != null,
    "$gate is unset; point it at a review-metrics.db copy to run this harness.",
  )
  val source = Path.of(configured)
  assertTrue(Files.isRegularFile(source), "$gate must point at an existing database file, but was '$configured'.")
  return source
}

internal fun requireRealStore(): Path = requireGatedStore(REAL_STORE_ENV)

internal fun groupCount(
  connection: Connection,
  column: String,
): Map<String, Int> =
  connection.createStatement().use { statement ->
    statement.executeQuery("SELECT $column, COUNT(*) FROM review_runs GROUP BY $column").use { resultSet ->
      buildMap {
        while (resultSet.next()) {
          put(resultSet.getString(1), resultSet.getInt(2))
        }
      }
    }
  }

internal fun reviewRunColumn(
  connection: Connection,
  reviewRunId: String,
  column: String,
): String? =
  connection.prepareStatement("SELECT $column FROM review_runs WHERE review_run_id = ?").use { statement ->
    statement.setString(1, reviewRunId)
    statement.executeQuery().use { resultSet ->
      check(resultSet.next())
      resultSet.getString(1)
    }
  }

internal fun executionModeGaps(connection: Connection): Int =
  connection.createStatement().use { statement ->
    statement.executeQuery("SELECT COUNT(*) FROM review_runs WHERE execution_mode IS NULL OR execution_mode = ''")
      .use { resultSet ->
        check(resultSet.next())
        resultSet.getInt(1)
      }
  }

internal fun featureImplementColumnValue(
  connection: Connection,
  columnName: String,
): Any =
  connection.prepareStatement(
    """
    SELECT $columnName
    FROM feature_implement_sessions
    WHERE session_id = 'fis-defaults'
    """.trimIndent(),
  ).use { statement ->
    statement.executeQuery().use { resultSet ->
      resultSet.next()
      resultSet.getObject(1)
    }
  }

internal fun columnNames(
  connection: Connection,
  table: String,
): Set<String> =
  connection.prepareStatement("SELECT name FROM pragma_table_info(?)").use { statement ->
    statement.setString(1, table)
    statement.executeQuery().use { resultSet ->
      buildSet {
        while (resultSet.next()) {
          add(resultSet.getString("name"))
        }
      }
    }
  }

internal fun migrationRows(connection: Connection): List<MigrationRow> =
  connection.prepareStatement(
    """
    SELECT version, name, applied_at
    FROM schema_migrations
    ORDER BY version
    """.trimIndent(),
  ).use { statement ->
    statement.executeQuery().use { resultSet ->
      buildList {
        while (resultSet.next()) {
          add(
            MigrationRow(
              version = resultSet.getInt("version"),
              name = resultSet.getString("name"),
              appliedAt = resultSet.getString("applied_at"),
            ),
          )
        }
      }
    }
  }

internal fun goalSubtaskColumnValue(
  connection: Connection,
  columnName: String,
): Any =
  connection.prepareStatement("SELECT $columnName FROM goal_subtask_events LIMIT 1").use { statement ->
    statement.executeQuery().use { resultSet ->
      check(resultSet.next()) { "Expected a seeded goal_subtask_events row." }
      resultSet.getObject(1)
    }
  }

internal fun nullableTableColumnValue(
  connection: Connection,
  tableName: String,
  pkColumnName: String,
  pkValue: String,
  columnName: String,
): Any? =
  connection.prepareStatement(
    "SELECT $columnName FROM $tableName WHERE $pkColumnName = ?",
  ).use { statement ->
    statement.setString(1, pkValue)
    statement.executeQuery().use { resultSet ->
      check(resultSet.next()) { "Expected a row with $pkColumnName = '$pkValue' in $tableName." }
      resultSet.getObject(1)
    }
  }

internal fun tableColumnValue(
  connection: Connection,
  tableName: String,
  pkColumnName: String,
  pkValue: String,
  columnName: String,
): Any? =
  connection.prepareStatement(
    "SELECT $columnName FROM $tableName WHERE $pkColumnName = ?",
  ).use { statement ->
    statement.setString(1, pkValue)
    statement.executeQuery().use { resultSet ->
      check(resultSet.next()) { "Expected a row with $pkColumnName = '$pkValue' in $tableName." }
      resultSet.getObject(1)
    }
  }

internal fun producerEvidenceDdl(connection: Connection): String =
  connection.createStatement().use { statement ->
    statement.executeQuery(
      "SELECT sql FROM sqlite_master WHERE type = 'table' AND name = 'producer_output_evidence'",
    ).use { rows ->
      check(rows.next()) { "producer_output_evidence is absent." }
      rows.getString("sql")
    }
  }

internal fun producerEvidenceRows(connection: Connection): List<ProducerEvidenceRow> =
  connection.createStatement().use { statement ->
    statement.executeQuery(
      "SELECT generation, attempt, recorded_at, sha256, payload FROM producer_output_evidence ORDER BY attempt",
    ).use { rows ->
      buildList {
        while (rows.next()) {
          add(
            ProducerEvidenceRow(
              generation = rows.getInt("generation"),
              attempt = rows.getInt("attempt"),
              recordedAt = rows.getString("recorded_at"),
              sha256 = rows.getString("sha256"),
              payload = rows.getBytes("payload"),
            ),
          )
        }
      }
    }
  }

internal fun tableColumns(
  connection: Connection,
  tableName: String,
): Set<String> =
  connection.createStatement().use { statement ->
    statement.executeQuery("PRAGMA table_info($tableName)").use { resultSet ->
      buildSet {
        while (resultSet.next()) {
          add(resultSet.getString("name"))
        }
      }
    }
  }

internal fun tableColumnTypes(
  connection: Connection,
  tableName: String,
): Map<String, String> =
  connection.createStatement().use { statement ->
    statement.executeQuery("PRAGMA table_info($tableName)").use { resultSet ->
      buildMap {
        while (resultSet.next()) {
          put(resultSet.getString("name"), resultSet.getString("type"))
        }
      }
    }
  }

internal fun legacyGoalSubtaskRows(connection: Connection): List<Map<String, Any?>> =
  connection.createStatement().use { statement ->
    statement.executeQuery(
      """
      SELECT issue_key, workflow_id, subtask_id, subtask_name, status,
             started_at, finished_at, duration_ms, attempt_count, blocked_reason,
             subtask_event_emitted_at
      FROM goal_subtask_events
      ORDER BY issue_key, workflow_id, subtask_id
      """.trimIndent(),
    ).use { resultSet ->
      val metadata = resultSet.metaData
      buildList {
        while (resultSet.next()) {
          add(
            buildMap {
              for (index in 1..metadata.columnCount) {
                put(metadata.getColumnName(index), resultSet.getObject(index))
              }
            },
          )
        }
      }
    }
  }

internal fun tableNames(connection: Connection): Set<String> =
  connection.createStatement().use { statement ->
    statement.executeQuery("SELECT name FROM sqlite_master WHERE type = 'table'").use { resultSet ->
      buildSet {
        while (resultSet.next()) {
          add(resultSet.getString("name"))
        }
      }
    }
  }

internal fun allTableRowCounts(connection: Connection): Map<String, Int> =
  tableNames(connection)
    .filterNot { it.startsWith("sqlite_") }
    .associateWith { table -> rowCount(connection, table) }

internal fun findingRows(connection: Connection): List<List<Any?>> =
  connection.createStatement().use { statement ->
    statement.executeQuery(
      """
      SELECT review_run_id, finding_id, severity, confidence, location, description, finding_text
      FROM findings
      ORDER BY review_run_id, finding_id
      """.trimIndent(),
    ).use { resultSet ->
      buildList {
        while (resultSet.next()) {
          add((1..resultSet.metaData.columnCount).map(resultSet::getObject))
        }
      }
    }
  }

internal fun rowCount(
  connection: Connection,
  tableName: String,
): Int =
  connection.createStatement().use { statement ->
    statement.executeQuery("SELECT COUNT(*) FROM $tableName").use { resultSet ->
      check(resultSet.next())
      resultSet.getInt(1)
    }
  }

internal fun scalarInt(
  connection: Connection,
  sql: String,
): Int =
  connection.createStatement().use { statement ->
    statement.executeQuery(sql).use { resultSet ->
      check(resultSet.next())
      resultSet.getInt(1)
    }
  }

internal fun scalarString(
  connection: Connection,
  sql: String,
): String? =
  connection.createStatement().use { statement ->
    statement.executeQuery(sql).use { resultSet ->
      if (resultSet.next()) resultSet.getString(1) else null
    }
  }

internal fun tableIndexNames(connection: Connection): Set<String> =
  connection.createStatement().use { statement ->
    statement.executeQuery("SELECT name FROM sqlite_master WHERE type = 'index'").use { resultSet ->
      buildSet {
        while (resultSet.next()) {
          add(resultSet.getString("name"))
        }
      }
    }
  }

internal fun tableInfo(
  connection: Connection,
  tableName: String,
): List<TableColumnInfo> =
  connection.prepareStatement("PRAGMA table_info($tableName)").use { statement ->
    statement.executeQuery().use { resultSet ->
      buildList {
        while (resultSet.next()) {
          add(
            TableColumnInfo(
              name = resultSet.getString("name"),
              notNull = resultSet.getInt("notnull") == 1,
            ),
          )
        }
      }
    }
  }

internal fun telemetryOutboxRows(connection: Connection): List<List<Any?>> =
  connection.createStatement().use {
    it.executeQuery("SELECT id, event_name, synced_at, last_error FROM telemetry_outbox ORDER BY id").use { rows ->
      buildList {
        while (rows.next()) {
          add((1..rows.metaData.columnCount).map(rows::getObject))
        }
      }
    }
  }

internal fun createLegacyTelemetryOutboxDatabase(dbPath: Path) {
  DriverManager.getConnection("jdbc:sqlite:$dbPath").use { connection ->
    connection.createStatement().use { statement ->
      statement.execute(
        """
        CREATE TABLE telemetry_outbox (
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          event_name TEXT NOT NULL,
          payload_json TEXT NOT NULL,
          created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
          synced_at TEXT,
          last_error TEXT NOT NULL DEFAULT ''
        )
        """.trimIndent(),
      )
      statement.executeUpdate(
        """
        INSERT INTO telemetry_outbox (id, event_name, payload_json, synced_at, last_error) VALUES
          (1, 'review_finished', '{}', '2026-01-01T00:00:00Z', ''),
          (2, 'review_finished', '{}', NULL, 'boom'),
          (3, 'goal_finished', '{}', NULL, '')
        """.trimIndent(),
      )
    }
  }
}

internal data class TableColumnInfo(val name: String, val notNull: Boolean)

internal data class MigrationRow(
  val version: Int,
  val name: String,
  val appliedAt: String,
)

internal data class ProducerEvidenceRow(
  val generation: Int,
  val attempt: Int,
  val recordedAt: String,
  val sha256: String,
  val payload: ByteArray?,
)

const val SCHEMA_MIGRATIONS_TABLE: String = "schema_migrations"
const val REAL_STORE_ENV: String = "SKILL_BILL_REAL_STORE_DB"
const val MIGRATION_FIXTURE_ENV: String = "SKILL_BILL_MIGRATION_FIXTURE_DB"

val LEGACY_ROUTED_SKILL_VARIANTS: List<String> =
  listOf(
    "bill-kmp-code-review",
    "bill-kmp-code-review (parallel)",
    "bill-kmp-code-review-persistence",
    "skillbill:bill-kmp-code-review",
    "`bill-kmp-code-review`",
    "Routed to bill-kmp-code-review for the KMP pack",
  )
val LEGACY_STACK_VARIANTS: List<String> =
  listOf("kotlin", "Kotlin", "Kotlin/JVM", "kotlin (jvm backend)", "  KOTLIN  ")
const val AMBIGUOUS_ROUTED_SKILL: String = "bill-kmp-code-review, bill-ios-code-review"
val EXPERIMENT_TABLE_NAMES: List<String> =
  listOf(
    "experiment_reports",
    "experiment_pair_leases",
    "experiment_arm_outcomes",
    "experiment_observations",
    "experiment_pairs",
  )
val LEGACY_EXPERIMENT_ROWS: List<String> =
  listOf(
    """
    INSERT INTO experiment_pairs(
      pair_id, contract_version, execution_mode, selected_experiment_names_json,
      arm_order_json, delivery_arm, pair_status, frozen_input_identity_json, delivery_status
    ) VALUES ('pair-legacy', '0.1', 'goal_pair', '{}', '{}', 'control', 'completed', '{}', 'published')
    """.trimIndent(),
    """
    INSERT INTO experiment_observations(
      observation_id, pair_id, arm_id, event_identity_json, recorded_at, measurements_json
    ) VALUES ('obs-legacy', 'pair-legacy', 'control', '{}', '2026-09-21', '{}')
    """.trimIndent(),
    """
    INSERT INTO experiment_arm_outcomes(pair_id, arm_id, workflow_id, terminal_status, outcome_json)
    VALUES ('pair-legacy', 'control', 'wfl-kept', 'completed', '{}')
    """.trimIndent(),
    "INSERT INTO experiment_pair_leases(pair_id, owner_token, generation, expires_at) " +
      "VALUES ('pair-legacy', 'owner', 1, 0)",
    "INSERT INTO experiment_reports(pair_id, report_json) VALUES ('pair-legacy', '{}')",
  )

internal fun goalRunSessionInsert(workflowId: String): String =
  """
  INSERT INTO goal_run_sessions(
    workflow_id, issue_key, started_at, finished_at, status,
    finished_duration_ms, subtasks_complete, subtasks_blocked, subtasks_skipped
  ) VALUES ('$workflowId', 'SKILL-378', '2026-09-21', '2026-09-21', 'completed', 1, 1, 0, 0)
  """.trimIndent()

const val VERSION_KEYED_SCHEMA_MIGRATIONS_SQL: String =
  """
    CREATE TABLE schema_migrations (
      version INTEGER PRIMARY KEY,
      name TEXT NOT NULL UNIQUE,
      applied_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
    )
    """
const val PRE_GENERATION_PRODUCER_OUTPUT_EVIDENCE_SQL: String =
  """
    CREATE TABLE producer_output_evidence (
      workflow_id TEXT NOT NULL, phase_id TEXT NOT NULL,
      attempt INTEGER NOT NULL CHECK (attempt > 0),
      agent_id TEXT NOT NULL, model TEXT NOT NULL, recorded_at TEXT NOT NULL,
      byte_size INTEGER NOT NULL CHECK (byte_size >= 0), sha256 TEXT NOT NULL, payload BLOB,
      PRIMARY KEY (workflow_id, phase_id, attempt)
    )
    """
const val PRE_AGENT_PRODUCER_OUTPUT_EVIDENCE_SQL: String =
  """
    CREATE TABLE producer_output_evidence (
      workflow_id TEXT NOT NULL, phase_id TEXT NOT NULL,
      generation INTEGER NOT NULL DEFAULT 0 CHECK (generation >= 0),
      attempt INTEGER NOT NULL CHECK (attempt > 0),
      agent_id TEXT NOT NULL, model TEXT NOT NULL, recorded_at TEXT NOT NULL,
      byte_size INTEGER NOT NULL CHECK (byte_size >= 0), sha256 TEXT NOT NULL, payload BLOB,
      PRIMARY KEY (workflow_id, phase_id, generation, attempt)
    )
    """
const val PRE_REPAIR_TURN_PRODUCER_OUTPUT_EVIDENCE_SQL: String =
  """
    CREATE TABLE producer_output_evidence (
      workflow_id TEXT NOT NULL, phase_id TEXT NOT NULL,
      generation INTEGER NOT NULL DEFAULT 0 CHECK (generation >= 0),
      attempt INTEGER NOT NULL CHECK (attempt > 0),
      agent_id TEXT NOT NULL, model TEXT NOT NULL, recorded_at TEXT NOT NULL,
      byte_size INTEGER NOT NULL CHECK (byte_size >= 0), sha256 TEXT NOT NULL, payload BLOB,
      PRIMARY KEY (workflow_id, phase_id, generation, attempt, agent_id)
    )
    """
const val PRE_REPAIR_TURN_REJECTED_OUTPUT_DIAGNOSTICS_SQL: String =
  """
    CREATE TABLE rejected_output_diagnostics (
      identity TEXT PRIMARY KEY,
      workflow_id TEXT NOT NULL,
      phase_id TEXT NOT NULL,
      attempt INTEGER NOT NULL CHECK (attempt > 0),
      rule TEXT NOT NULL,
      rejection_path TEXT NOT NULL,
      reason TEXT NOT NULL,
      agent_id TEXT NOT NULL,
      model TEXT NOT NULL,
      recorded_at TEXT NOT NULL,
      byte_size INTEGER NOT NULL CHECK (byte_size >= 0),
      sha256 TEXT NOT NULL,
      lifecycle TEXT NOT NULL CHECK (lifecycle IN ('stored', 'oversized', 'expired')),
      payload BLOB,
      UNIQUE (workflow_id, phase_id, attempt),
      CHECK (
        (lifecycle = 'stored' AND payload IS NOT NULL) OR
        (lifecycle IN ('oversized', 'expired') AND payload IS NULL)
      )
    )
    """
const val CREATE_LEGACY_FEATURE_TASK_RUNTIME_SESSIONS_SQL: String =
  """
    CREATE TABLE feature_task_runtime_sessions (
      session_id TEXT PRIMARY KEY,
      feature_size TEXT NOT NULL DEFAULT 'MEDIUM',
      issue_key TEXT NOT NULL DEFAULT '',
      feature_name TEXT NOT NULL DEFAULT '',
      started_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
      completion_status TEXT NOT NULL DEFAULT '',
      completed_phase_ids TEXT NOT NULL DEFAULT '',
      phase_outcomes TEXT NOT NULL DEFAULT '',
      last_incomplete_phase TEXT NOT NULL DEFAULT '',
      blocked_reason TEXT NOT NULL DEFAULT '',
      resolved_branch TEXT NOT NULL DEFAULT '',
      review_fix_iteration_count INTEGER NOT NULL DEFAULT 0,
      audit_gap_iteration_count INTEGER NOT NULL DEFAULT 0,
      finished_at TEXT
    )
    """
const val CREATE_LEGACY_FEATURE_IMPLEMENT_SESSIONS_SQL: String =
  """
    CREATE TABLE feature_implement_sessions (
      session_id TEXT PRIMARY KEY,
      completion_status TEXT,
      started_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
    )
    """
const val CREATE_LEGACY_FEATURE_IMPLEMENT_SESSIONS_WITHOUT_START_SQL: String =
  """
    CREATE TABLE feature_implement_sessions (
      session_id TEXT PRIMARY KEY,
      completion_status TEXT
    )
    """
const val CREATE_LEGACY_FEATURE_TASK_WORKFLOWS_SQL: String =
  """
    CREATE TABLE feature_task_workflows (
      workflow_id TEXT PRIMARY KEY,
      session_id TEXT NOT NULL DEFAULT '',
      workflow_name TEXT NOT NULL DEFAULT 'bill-feature-task',
      mode TEXT NOT NULL,
      implementation_skill TEXT NOT NULL DEFAULT '',
      contract_version TEXT NOT NULL,
      workflow_status TEXT NOT NULL DEFAULT 'pending',
      current_step_id TEXT NOT NULL DEFAULT '',
      steps_json TEXT NOT NULL DEFAULT '',
      artifacts_json TEXT NOT NULL DEFAULT '',
      started_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
      updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
      finished_at TEXT
    )
    """
const val LEGACY_FEATURE_TASK_WORKFLOW_STARTED_AT: String = "2026-06-04 10:00:00"
const val CREATE_LEGACY_FEATURE_VERIFY_SESSIONS_WITHOUT_START_SQL: String =
  """
    CREATE TABLE feature_verify_sessions (
      session_id TEXT PRIMARY KEY
    )
    """
const val CREATE_LEGACY_QUALITY_CHECK_SESSIONS_WITHOUT_START_SQL: String =
  """
    CREATE TABLE quality_check_sessions (
      session_id TEXT PRIMARY KEY
    )
    """
const val CREATE_LEGACY_GOAL_SUBTASK_EVENTS_SQL: String =
  """
    CREATE TABLE goal_subtask_events (
      issue_key TEXT NOT NULL,
      workflow_id TEXT NOT NULL,
      subtask_id INTEGER NOT NULL,
      subtask_name TEXT NOT NULL DEFAULT '',
      status TEXT NOT NULL,
      started_at TEXT NOT NULL,
      finished_at TEXT NOT NULL,
      duration_ms INTEGER NOT NULL,
      attempt_count INTEGER NOT NULL,
      blocked_reason TEXT,
      subtask_event_emitted_at TEXT,
      PRIMARY KEY (issue_key, subtask_id, workflow_id)
    )
    """
const val CREATE_LEGACY_REVIEW_RUNS_SQL: String =
  """
    CREATE TABLE review_runs (
      review_run_id TEXT PRIMARY KEY,
      routed_skill TEXT,
      raw_text TEXT NOT NULL,
      imported_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
    )
    """
const val CREATE_LEGACY_VERIFY_REVIEW_RUNS_SQL: String =
  """
    CREATE TABLE review_runs (
      review_run_id TEXT PRIMARY KEY,
      raw_text TEXT NOT NULL,
      imported_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
    )
    """
const val CREATE_LEGACY_FINDINGS_SQL: String =
  """
    CREATE TABLE findings (
      review_run_id TEXT NOT NULL,
      finding_id TEXT NOT NULL,
      severity TEXT NOT NULL,
      confidence TEXT NOT NULL,
      location TEXT NOT NULL,
      description TEXT NOT NULL,
      finding_text TEXT NOT NULL,
      PRIMARY KEY (review_run_id, finding_id),
      FOREIGN KEY (review_run_id) REFERENCES review_runs(review_run_id) ON DELETE CASCADE
    )
    """
const val CREATE_LEGACY_FEEDBACK_EVENTS_SQL: String =
  """
    CREATE TABLE feedback_events (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      review_run_id TEXT NOT NULL,
      finding_id TEXT NOT NULL,
      event_type TEXT NOT NULL CHECK (
        event_type IN ('accepted', 'dismissed', 'fix_requested')
      ),
      note TEXT NOT NULL DEFAULT '',
      created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
      FOREIGN KEY (review_run_id, finding_id) REFERENCES findings(review_run_id, finding_id) ON DELETE CASCADE
    )
    """

internal fun versionIsPrimaryKey(connection: Connection): Boolean =
  connection.prepareStatement("SELECT pk FROM pragma_table_info('schema_migrations') WHERE name = 'version'")
    .use { statement ->
      statement.executeQuery().use { resultSet -> resultSet.next() && resultSet.getInt("pk") > 0 }
    }

internal fun tableExists(
  connection: Connection,
  table: String,
): Boolean =
  connection.prepareStatement("SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?").use { statement ->
    statement.setString(1, table)
    statement.executeQuery().use { resultSet -> resultSet.next() }
  }
