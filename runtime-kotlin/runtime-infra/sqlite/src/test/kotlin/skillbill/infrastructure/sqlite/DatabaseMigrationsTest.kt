package skillbill.infrastructure.sqlite

import skillbill.error.shellcontent.InvalidWorkflowStateSchemaError
import skillbill.infrastructure.sqlite.core.migration.DatabaseColumnMigrations
import skillbill.infrastructure.sqlite.core.migration.DatabaseMigrations
import skillbill.infrastructure.sqlite.core.migration.area.GoalTelemetryMigration
import skillbill.infrastructure.sqlite.core.ops.inNestedWriteTransaction
import skillbill.infrastructure.sqlite.core.schema.DatabaseIdentity
import skillbill.infrastructure.sqlite.core.schema.DatabaseRuntime
import skillbill.infrastructure.sqlite.core.schema.DatabaseSchema
import skillbill.infrastructure.sqlite.review.stats.loadGoalRows
import skillbill.infrastructure.sqlite.telemetry.outbox.TelemetryOutboxStore
import skillbill.infrastructure.sqlite.workflow.goalrunner.runner.GoalRunnerControlStore
import skillbill.ports.goalrunner.runner.model.GoalRunnerOutOfBandAcceptance
import skillbill.ports.goalrunner.runner.model.GoalRunnerReviewPolicy
import skillbill.ports.telemetry.model.TelemetryOutboxRecord
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.sql.SQLException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
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

class DatabaseMigrationsTest {
  @Test
  fun `immediate transaction rolls back non SQL failures and remains reusable`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-db-immediate-rollback").resolve("rollback.db")

    DriverManager.getConnection("jdbc:sqlite:$dbPath").use { connection ->
      connection.createStatement().use { it.execute("CREATE TABLE rollback_probe (value TEXT NOT NULL)") }

      assertFailsWith<IllegalStateException> {
        connection.inNestedWriteTransaction {
          createStatement().use { it.executeUpdate("INSERT INTO rollback_probe VALUES ('partial')") }
          error("non-SQL migration failure")
        }
      }

      assertEquals(0, rowCount(connection, "rollback_probe"))
      connection.inNestedWriteTransaction {
        createStatement().use { it.executeUpdate("INSERT INTO rollback_probe VALUES ('committed')") }
      }
      assertEquals(1, rowCount(connection, "rollback_probe"))
    }
  }

  @Test
  fun `migration definitions are append-only and deterministic`() {
    val migrationDefinitions = DatabaseMigrations.migrations.map { migration -> migration.version to migration.name }

    assertEquals(
      listOf(
        1 to "add-review-workflow-session-columns",
        2 to "normalize-feedback-event-outcomes",
        3 to "add-goal-telemetry-tables",
        4 to "add-work-list-state-metadata",
        5 to "recover-work-list-issue-keys",
        6 to "add-feature-task-execution-identities",
        7 to "add-feature-task-runtime-worker-leases",
        8 to "add-goal-planning-preparations",
        9 to "normalize-goal-planning-preparations",
        10 to "rebuild-goal-planning-plans-for-phase-output-0-2",
        11 to "require-goal-planning-phase-output-0-2",
        12 to "add-bounded-review-accounting",
        13 to "allow-goal-planning-phase-output-0-3",
        14 to "add-rejected-output-diagnostics",
        15 to "add-private-producer-output-evidence",
        16 to "rekey-producer-output-evidence-by-generation",
        17 to "persist-goal-planning-repair-evidence",
        18 to "persist-legacy-goal-planning-repair-evidence",
        19 to "add-goal-runner-controls",
        20 to "add-goal-runner-control-state",
        21 to "add-delegated-review-lifecycle-projection",
        22 to "drop-delegated-review-lifecycle-tables",
        24 to "backfill-review-attribution-canonicals",
        25 to "add-review-run-lane-attribution",
        26 to "relax-telemetry-outbox-last-error",
        27 to "add-review-finding-outcome-key",
        28 to "rekey-producer-output-evidence-by-agent",
        29 to "rekey-diagnostic-evidence-by-repair-turn",
        30 to "add-review-run-stage-state",
        31 to "add-review-run-pass-claims",
        32 to "allow-goal-planning-phase-output-0-4",
        33 to "allow-goal-planning-phase-output-0-5",
        34 to "allow-goal-planning-phase-output-0-6",
        35 to "add-feature-task-phase-settlements",
        36 to "add-agent-activity-stamps",
        37 to "add-telemetry-outbox-delivery-identity",
        38 to "add-worktree-edit-journal",
        39 to "ensure-schema-columns-and-heals",
        40 to "migrate-legacy-goal-runner-controls",
        41 to "migrate-legacy-telemetry-outbox",
        42 to "skill-366-add-experiment-pair-tables",
        43 to "skill-366-add-experiment-pair-leases",
        44 to "skill-366-preserve-experiment-arm-outcomes",
        45 to "skill-378-drop-experiment-tables",
        46 to "allow-goal-planning-phase-output-0-7",
        47 to "add-operation-proposals",
      ),
      migrationDefinitions,
    )
    assertEquals(migrationDefinitions.sortedBy { (version, _) -> version }, migrationDefinitions)
    assertEquals(migrationDefinitions.map { (version, _) -> version }.toSet().size, migrationDefinitions.size)
    assertEquals(migrationDefinitions.map { (_, name) -> name }.toSet().size, migrationDefinitions.size)
  }

  @Test
  fun `opening legacy audit storage preserves its rows without requiring or updating them`() {
    val root = Files.createTempDirectory("runtime-kotlin-stateless-audit-storage")
    val dbPath = root.resolve("legacy.db")
    try {
      DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
        assertFalse(tableExists(connection, "feature_task_runtime_audit_generations"))
        assertTrue(migrationRows(connection).none { it.version == 23 })
        connection.createStatement().use { statement ->
          statement.executeUpdate(
            "CREATE TABLE feature_task_runtime_audit_generations " +
              "(workflow_id TEXT PRIMARY KEY, generation_json TEXT NOT NULL)",
          )
          statement.executeUpdate(
            "INSERT INTO feature_task_runtime_audit_generations VALUES ('legacy-workflow', 'unreadable old state')",
          )
          statement.executeUpdate(
            "INSERT INTO schema_migrations (version, name) VALUES (23, 'add-feature-task-runtime-audit-generations')",
          )
          statement.executeUpdate(
            "CREATE TRIGGER reject_audit_updates BEFORE UPDATE ON feature_task_runtime_audit_generations " +
              "BEGIN SELECT RAISE(FAIL, 'retired audit storage must not be updated'); END",
          )
        }
      }
      DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
        assertEquals(1, rowCount(connection, "feature_task_runtime_audit_generations"))
        connection.createStatement().use { statement ->
          statement.executeQuery("SELECT generation_json FROM feature_task_runtime_audit_generations").use { rows ->
            assertTrue(rows.next())
            assertEquals("unreadable old state", rows.getString(1))
          }
        }
        assertEquals(23, migrationRows(connection).single { it.version == 23 }.version)
        assertTrue(tableExists(connection, "feature_task_workflows"))
      }
    } finally {
      root.toFile().deleteRecursively()
    }
  }

  @Test
  fun `a fresh database carries no delegated review lifecycle tables`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-db-v22-fresh").resolve("fresh.db")

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      assertFalse(tableExists(connection, "review_lifecycle_events"))
      assertFalse(tableExists(connection, "review_delegated_lifecycle"))
      assertNotNull(
        migrationRows(connection).singleOrNull { row ->
          row.version == 22 && row.name == "drop-delegated-review-lifecycle-tables"
        },
        "Migration version 22 drop-delegated-review-lifecycle-tables should be recorded.",
      )
    }
  }

  @Test
  fun `migration v22 drops the lifecycle tables an existing database still carries`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-db-v22-existing").resolve("legacy.db")

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      connection.createStatement().use { statement ->
        statement.executeUpdate("DELETE FROM schema_migrations WHERE version = 22")
        statement.executeUpdate(
          "CREATE TABLE IF NOT EXISTS review_lifecycle_events (review_id TEXT NOT NULL, payload TEXT NOT NULL)",
        )
        statement.executeUpdate(
          "CREATE TABLE IF NOT EXISTS review_delegated_lifecycle (review_id TEXT PRIMARY KEY, payload TEXT NOT NULL)",
        )
        statement.executeUpdate(
          "CREATE INDEX IF NOT EXISTS idx_review_lifecycle_events_review ON review_lifecycle_events(review_id)",
        )
        statement.executeUpdate(
          "CREATE INDEX IF NOT EXISTS idx_review_delegated_lifecycle_review " +
            "ON review_delegated_lifecycle(review_id)",
        )
        statement.executeUpdate("INSERT INTO review_lifecycle_events VALUES ('rvw-1', '{}')")
      }
    }

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      assertFalse(tableExists(connection, "review_lifecycle_events"))
      assertFalse(tableExists(connection, "review_delegated_lifecycle"))
    }
  }

  @Test
  fun `migration v45 drops the experiment tables a legacy database still carries and keeps its other rows`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-db-v45-experiments").resolve("legacy.db")

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      DatabaseMigrations.migrations
        .filter { migration -> migration.version in 42..44 }
        .forEach { migration -> migration.apply(connection, SqliteTestDiagnostics) }
      connection.createStatement().use { statement ->
        statement.executeUpdate("DELETE FROM schema_migrations WHERE version = 45")
        LEGACY_EXPERIMENT_ROWS.forEach { sql -> statement.executeUpdate(sql) }
        statement.executeUpdate(goalRunSessionInsert("wfl-kept"))
      }
      EXPERIMENT_TABLE_NAMES.forEach { table -> assertEquals(1, rowCount(connection, table), table) }
    }

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      EXPERIMENT_TABLE_NAMES.forEach { table ->
        assertFalse(tableExists(connection, table), "$table must not survive the drop migration.")
      }
      assertEquals(
        listOf("wfl-kept"),
        loadGoalRows(connection, "goal_run_sessions").map { row -> row["workflow_id"] },
        "Unrelated goal telemetry rows must survive and stay readable by goal stats.",
      )
      connection.createStatement().use { statement -> statement.executeUpdate(goalRunSessionInsert("wfl-new")) }
      assertEquals(2, rowCount(connection, "goal_run_sessions"))
      assertNotNull(
        migrationRows(connection).singleOrNull { row ->
          row.version == 45 && row.name == "skill-378-drop-experiment-tables"
        },
      )
    }

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      assertEquals(DatabaseMigrations.migrations.size, migrationRows(connection).size)
    }
  }

  @Test
  fun `migration v8 records and recreates goal planning preparations on an existing database`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-db-v8-goal-planning").resolve("metrics.db")

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      connection.createStatement().use { statement ->
        statement.executeUpdate("DELETE FROM schema_migrations WHERE version = 8")
        statement.executeUpdate("DROP INDEX IF EXISTS idx_goal_planning_preparations_lookup")
        statement.executeUpdate("DROP TABLE IF EXISTS goal_planning_preparations")
      }
    }

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val columns = tableColumns(connection = connection, tableName = "goal_planning_preparations")
      assertTrue("parent_goal_workflow_id" in columns)
      assertTrue("subtask_id" in columns)
      assertTrue("preparation_status" in columns)
      val migration =
        migrationRows(connection).singleOrNull { row ->
          row.version == 8 && row.name == "add-goal-planning-preparations"
        }
      assertNotNull(migration, "Migration version 8 add-goal-planning-preparations should be recorded.")
    }
  }

  @Test
  fun `a version recorded under a foreign name does not skip this build's migration`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-db-version-collision").resolve("metrics.db")

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      connection.createStatement().use { statement ->
        statement.executeUpdate("DROP TABLE schema_migrations")
        statement.executeUpdate(VERSION_KEYED_SCHEMA_MIGRATIONS_SQL)
        DatabaseMigrations.migrations.forEach { migration ->
          val name = if (migration.version == 16) "add-feature-task-convergence-state" else migration.name
          statement.executeUpdate(
            "INSERT INTO schema_migrations (version, name) VALUES (${migration.version}, '$name')",
          )
        }
        statement.executeUpdate("DROP TABLE producer_output_evidence")
        statement.executeUpdate(PRE_GENERATION_PRODUCER_OUTPUT_EVIDENCE_SQL)
      }

      DatabaseMigrations.apply(connection)

      assertTrue(
        columnNames(connection, "producer_output_evidence").contains("generation"),
        "The branch-numbered ledger row must not mask this build's version 16 migration.",
      )
      val names = migrationRows(connection).map { row -> row.name }
      assertTrue(
        names.contains("add-feature-task-convergence-state"),
        "The already-applied foreign migration must stay recorded.",
      )
      assertTrue(
        names.contains("rekey-producer-output-evidence-by-generation"),
        "This build's version 16 migration must be recorded once it runs.",
      )
    }
  }

  @Test
  fun `applying twice against a name keyed ledger is a no-op`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-db-idempotent-apply").resolve("metrics.db")

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val before = migrationRows(connection).map { row -> row.name }

      DatabaseMigrations.apply(connection)

      assertEquals(before, migrationRows(connection).map { row -> row.name })
      assertTrue(columnNames(connection, "producer_output_evidence").contains("generation"))
    }
  }

  @Test
  fun `an empty database with no ledger table migrates through the gated path`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-db-gate-empty").resolve("empty.db")
    DriverManager.getConnection("jdbc:sqlite:$dbPath").use { connection ->
      assertFalse(tableExists(connection, "schema_migrations"), "The seeded database must start with no ledger.")
    }

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      assertEquals(
        DatabaseMigrations.migrations.map { migration -> migration.version to migration.name },
        migrationRows(connection).map { row -> row.version to row.name },
      )
    }
  }

  @Test
  fun `a version keyed ledger is rebuilt under the write lock`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-db-gate-version-keyed").resolve("metrics.db")
    seedVersionKeyedLedger(dbPath)

    DriverManager.getConnection("jdbc:sqlite:$dbPath").use { writer ->
      writer.createStatement().use { it.execute("BEGIN IMMEDIATE") }
      try {
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { migrator ->
          migrator.createStatement().use { it.execute("PRAGMA busy_timeout = 0") }
          assertFailsWith<SQLException>(
            "A version-keyed ledger must take the write lock so ensureNameKeyed runs inside it.",
          ) { DatabaseMigrations.apply(migrator) }
        }
      } finally {
        writer.createStatement().use { it.execute("ROLLBACK") }
      }
    }

    DriverManager.getConnection("jdbc:sqlite:$dbPath").use { connection ->
      DatabaseMigrations.apply(connection)
      assertFalse(
        versionIsPrimaryKey(connection),
        "ensureNameKeyed must rebuild the ledger as name-keyed once it holds the write lock.",
      )
      assertEquals(
        DatabaseMigrations.migrations.map { migration -> migration.name },
        migrationRows(connection).map { row -> row.name },
        "The rebuild must carry every recorded migration name across.",
      )
    }
  }

  @Test
  fun `an already current database opens no write transaction while another connection holds the writer lock`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-db-gate-current").resolve("metrics.db")
    DatabaseRuntime.ensureDatabase(dbPath).close()
    val expected =
      DriverManager.getConnection("jdbc:sqlite:$dbPath").use { connection ->
        migrationRows(connection).map { row -> row.name }
      }

    DriverManager.getConnection("jdbc:sqlite:$dbPath").use { writer ->
      writer.createStatement().use { it.execute("BEGIN IMMEDIATE") }
      try {
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { reader ->
          reader.createStatement().use { it.execute("PRAGMA busy_timeout = 0") }
          val startedAt = System.nanoTime()
          DatabaseMigrations.apply(reader)
          val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000
          assertTrue(elapsedMillis < 1_000, "A no-op apply must return promptly, took ${elapsedMillis}ms.")
          assertEquals(expected, migrationRows(reader).map { row -> row.name })
        }
      } finally {
        writer.createStatement().use { it.execute("ROLLBACK") }
      }
    }
  }

  @Test
  fun `racing applies re-derive in lock and apply each pending migration exactly once`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-db-gate-race").resolve("metrics.db")
    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      connection.createStatement().use { it.executeUpdate("DELETE FROM schema_migrations WHERE version = 13") }
    }
    val ready = CountDownLatch(2)
    val start = CountDownLatch(1)
    val executor = Executors.newFixedThreadPool(2)

    try {
      val races =
        (1..2).map {
          executor.submit {
            DriverManager.getConnection("jdbc:sqlite:$dbPath").use { connection ->
              connection.createStatement().use { it.execute("PRAGMA busy_timeout = 5000") }
              ready.countDown()
              check(start.await(5, TimeUnit.SECONDS))
              DatabaseMigrations.apply(connection)
            }
          }
        }
      assertTrue(ready.await(5, TimeUnit.SECONDS))
      start.countDown()
      races.forEach { it.get(10, TimeUnit.SECONDS) }
    } finally {
      executor.shutdownNow()
    }

    DriverManager.getConnection("jdbc:sqlite:$dbPath").use { connection ->
      assertEquals(
        DatabaseMigrations.migrations.map { migration -> migration.version to migration.name },
        migrationRows(connection).map { row -> row.version to row.name },
        "The migration that lost the race must be re-derived away, not applied twice.",
      )
      assertTrue("payload_sha256" in tableColumns(connection, "goal_subtask_plans"))
    }
  }

  @Test
  fun `column heal runs from its ledger migration on a legacy open`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-db-gate-column-heal").resolve("metrics.db")
    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      connection.createStatement().use {
        it.executeUpdate("DELETE FROM schema_migrations WHERE name = 'ensure-schema-columns-and-heals'")
        it.executeUpdate("ALTER TABLE feature_task_workflows DROP COLUMN interruption_reason")
      }
      assertFalse("interruption_reason" in tableColumns(connection, "feature_task_workflows"))
      assertFalse(versionIsPrimaryKey(connection), "The ledger must already be name-keyed for this to gate.")
      assertEquals(DatabaseMigrations.migrations.size - 1, migrationRows(connection).size)
      connection.createStatement().use {
        it.executeUpdate(
          """
          INSERT INTO feature_task_workflows (
            workflow_id, mode, contract_version, workflow_status, started_at, updated_at, state_entered_at
          ) VALUES ('wfl-gate-heal', 'prose', '0.1', 'running', '2026-05-01T10:00:00Z', '', '')
          """.trimIndent(),
        )
      }
    }

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      assertTrue(
        "interruption_reason" in tableColumns(connection, "feature_task_workflows"),
        "DatabaseColumnMigrations.apply must heal the column when its ledger migration is pending.",
      )
      assertEquals(
        "2026-05-01T10:00:00Z",
        tableColumnValue(connection, "feature_task_workflows", "workflow_id", "wfl-gate-heal", "state_entered_at"),
        "healWorkListMetadata must run within the pending ledger migration.",
      )
    }
  }

  @Test
  fun `migration v16 rekeys producer output evidence without losing a row`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-db-v16-producer-evidence").resolve("metrics.db")

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      connection.createStatement().use { statement ->
        statement.executeUpdate("DELETE FROM schema_migrations WHERE version = 16")
        clearProducerOutputEvidenceMigrationRecords(connection)
        statement.executeUpdate("DROP TABLE producer_output_evidence")
        statement.executeUpdate(PRE_GENERATION_PRODUCER_OUTPUT_EVIDENCE_SQL)
      }
      connection.prepareStatement(
        """
        INSERT INTO producer_output_evidence
        (workflow_id, phase_id, attempt, agent_id, model, recorded_at, byte_size, sha256, payload)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent(),
      ).use { statement ->
        listOf(1 to byteArrayOf(7, 8), 2 to null).forEach { (attempt, payload) ->
          statement.setString(1, "wfl-legacy")
          statement.setString(2, "review")
          statement.setInt(3, attempt)
          statement.setString(4, "codex")
          statement.setString(5, "gpt")
          statement.setString(6, "2026-07-28T10:00:0${attempt}Z")
          statement.setLong(7, payload?.size?.toLong() ?: 0L)
          statement.setString(8, attempt.toString().repeat(64))
          statement.setBytes(9, payload)
          statement.executeUpdate()
        }
      }
    }

    val migratedDdl =
      DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
        assertNotNull(
          migrationRows(connection).singleOrNull { row ->
            row.version == 16 && row.name == "rekey-producer-output-evidence-by-generation"
          },
        )
        val rows = producerEvidenceRows(connection)
        assertEquals(2, rows.size)
        assertEquals(listOf(0, 0), rows.map { it.generation })
        assertEquals(listOf(1, 2), rows.map { it.attempt })
        assertEquals(listOf("2026-07-28T10:00:01Z", "2026-07-28T10:00:02Z"), rows.map { it.recordedAt })
        assertEquals(listOf("1".repeat(64), "2".repeat(64)), rows.map { it.sha256 })
        assertContentEquals(byteArrayOf(7, 8), rows[0].payload)
        assertEquals(null, rows[1].payload)
        producerEvidenceDdl(connection)
      }

    val baseSchemaPath = Files.createTempDirectory("runtime-kotlin-db-v16-base-schema").resolve("base.db")
    DriverManager.getConnection("jdbc:sqlite:$baseSchemaPath").use { connection ->
      DatabaseSchema.createBaseSchema(connection)
      assertEquals(
        producerEvidenceDdl(connection),
        migratedDdl,
        "the migration DDL and the base-schema DDL must not drift",
      )
    }
  }

  @Test
  fun `migration v28 rekeys producer output evidence by agent without losing a row`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-db-v28-producer-evidence").resolve("metrics.db")
    val payload = "skill-15-review-0-2".encodeToByteArray()
    val sha = "8a5dfb56fd3d".padEnd(64, '0')

    seedPreAgentProducerEvidenceForMigration28(dbPath, payload, sha)

    val migratedDdl =
      DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
        assertMigration28Applied(connection)
        assertProducerEvidenceRowSurvivedMigration28(connection, payload, sha)
        producerEvidenceDdl(connection)
      }

    assertProducerEvidenceMigrationDdlParity(migratedDdl, "runtime-kotlin-db-v28-base-schema")
  }

  @Test
  fun `migration v29 rekeys both diagnostic stores by repair turn without losing a row`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-db-v29-repair-turn").resolve("metrics.db")
    val payload = "skill-185-validate-turn-1".encodeToByteArray()
    val sha = "8f6ac8b0d9da".padEnd(64, '0')
    val identity = "rod_${"e".repeat(64)}"

    seedPreRepairTurnDiagnosticsForMigration29(dbPath, payload, sha, identity)

    val migratedDdl =
      DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
        assertNotNull(
          migrationRows(connection).singleOrNull { row ->
            row.version == 29 && row.name == "rekey-diagnostic-evidence-by-repair-turn"
          },
        )

        assertEquals(listOf(0), producerEvidenceRepairTurns(connection))
        assertEquals(payload.size.toLong(), producerEvidenceByteSizes(connection).single())
        assertEquals(listOf(identity to 0), rejectedDiagnosticIdentitiesAndTurns(connection))

        assertEquals(
          listOf(
            "idx_rejected_output_diagnostic_retention",
            "idx_rejected_output_diagnostic_selection",
            "idx_rejected_output_diagnostics_selector",
          ),
          rejectedDiagnosticIndexNames(connection),
        )
        producerEvidenceDdl(connection)
      }

    assertProducerEvidenceMigrationDdlParity(migratedDdl, "runtime-kotlin-db-v29-base-schema")
  }

  @Test
  fun `migration v9 upgrades a v8 database with normalized planning tables`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-db-v9-goal-planning").resolve("metrics.db")

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      connection.createStatement().use { statement ->
        statement.executeUpdate("DELETE FROM schema_migrations WHERE version = 9")
        statement.executeUpdate("DROP TABLE goal_subtask_plans")
        statement.executeUpdate("DROP TABLE goal_shared_preplans")
      }
      assertTrue("parent_goal_workflow_id" in tableColumns(connection, "goal_planning_preparations"))
    }

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      assertTrue("payload_sha256" in tableColumns(connection, "goal_shared_preplans"))
      assertTrue("manifest_order" in tableColumns(connection, "goal_subtask_plans"))
      assertNotNull(
        migrationRows(connection).singleOrNull { row ->
          row.version == 9 && row.name == "normalize-goal-planning-preparations"
        },
      )
    }
  }

  @Test
  fun `ensureDatabase records all migrations for new databases`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-db-migrations").resolve("new.db")

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val rows = migrationRows(connection)

      assertEquals(
        DatabaseMigrations.migrations.map { migration -> migration.version to migration.name },
        rows.map { row -> row.version to row.name },
      )
      rows.forEach { row -> assertTrue(row.appliedAt.isNotBlank()) }
    }
  }
}

class DatabaseMigrationsEnsureDatabaseTest {
  @Test
  fun `historical goal telemetry migration v3 remains unchanged while v4 owns work state metadata`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-db-v3-contract").resolve("metrics.db")

    DriverManager.getConnection("jdbc:sqlite:$dbPath").use { connection ->
      GoalTelemetryMigration.apply(connection)
      val v3Columns = tableColumns(connection, "goal_issue_progress")

      assertFalse("state_entered_at" in v3Columns)
      assertFalse("state_entered_at_estimated" in v3Columns)
    }

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val v4Columns = tableColumns(connection, "goal_issue_progress")

      assertTrue("state_entered_at" in v4Columns)
      assertTrue("state_entered_at_estimated" in v4Columns)
      assertNotNull(migrationRows(connection).singleOrNull { it.version == 4 })
    }
  }

  @Test
  fun `ensureDatabase creates goal telemetry tables and records version 3`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-db-migrations").resolve("goal-telemetry.db")

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val tables = tableColumns(connection = connection, tableName = "goal_run_sessions")
      val subtaskColumns = tableColumns(connection = connection, tableName = "goal_subtask_events")
      val issueColumns = tableColumns(connection = connection, tableName = "goal_issue_progress")

      assertTrue("workflow_id" in tables, "goal_run_sessions should be created with its workflow_id key.")
      assertTrue("subtask_id" in subtaskColumns, "goal_subtask_events should be created with its subtask_id column.")
      assertTrue("parent_workflow_id" in issueColumns, "goal_issue_progress should be created with its parent key.")
      assertTrue("last_activity_at" in issueColumns, "goal_issue_progress should track latest issue activity.")
      assertTrue("last_blocked_at" in issueColumns, "goal_issue_progress should track latest blocked segment.")
      assertNotNull(
        migrationRows(connection).singleOrNull { row -> row.version == 3 && row.name == "add-goal-telemetry-tables" },
        "Migration version 3 add-goal-telemetry-tables should be recorded.",
      )
    }
  }

  @Test
  fun `ensureDatabase does not duplicate recorded migrations on repeated opens`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-db-migrations").resolve("repeat.db")

    DatabaseRuntime.ensureDatabase(dbPath).close()
    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      assertEquals(DatabaseMigrations.migrations.size, migrationRows(connection).size)
    }
  }

  @Test
  fun `concurrent migration opens serialize applicability checks`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-db-concurrent-migrations").resolve("metrics.db")
    DriverManager.getConnection("jdbc:sqlite:$dbPath").use { connection ->
      DatabaseSchema.createBaseSchema(connection)
    }
    val ready = CountDownLatch(2)
    val start = CountDownLatch(1)
    val executor = Executors.newFixedThreadPool(2)

    try {
      val opens =
        (1..2).map {
          executor.submit {
            DriverManager.getConnection("jdbc:sqlite:$dbPath").use { connection ->
              connection.createStatement().use { it.execute("PRAGMA busy_timeout = 5000") }
              ready.countDown()
              check(start.await(5, TimeUnit.SECONDS))
              DatabaseMigrations.apply(connection)
            }
          }
        }
      assertTrue(ready.await(5, TimeUnit.SECONDS))
      start.countDown()
      opens.forEach { it.get(10, TimeUnit.SECONDS) }
    } finally {
      executor.shutdownNow()
    }

    DriverManager.getConnection("jdbc:sqlite:$dbPath").use { connection ->
      assertEquals(
        DatabaseMigrations.migrations.map { migration -> migration.version },
        migrationRows(connection).map { row -> row.version },
      )
    }
  }

  @Test
  fun `reopening a healthy goal row does not rewrite healed state metadata`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-db-goal-healing").resolve("metrics.db")

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      connection.createStatement().use { statement ->
        statement.executeUpdate(
          """
          INSERT INTO goal_issue_progress (
            parent_workflow_id, issue_key, first_started_at, status, state_entered_at,
            state_entered_at_estimated
          ) VALUES ('goal-healthy', 'SKILL-117', '2026-05-01T12:00:00Z', 'running',
                    '2026-05-01T12:00:00Z', 0)
          """.trimIndent(),
        )
        statement.execute(
          """
          CREATE TRIGGER reject_healthy_goal_rewrite
          BEFORE UPDATE ON goal_issue_progress
          BEGIN
            SELECT RAISE(ABORT, 'healthy goal metadata must not be rewritten');
          END
          """.trimIndent(),
        )
      }
    }

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      assertEquals(
        "2026-05-01T12:00:00Z",
        tableColumnValue(
          connection = connection,
          tableName = "goal_issue_progress",
          pkColumnName = "parent_workflow_id",
          pkValue = "goal-healthy",
          columnName = "state_entered_at",
        ),
      )
    }
  }

  @Test
  fun `opening a legacy workflow with a partial state entry heal fills its missing estimated flag`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-db-workflow-partial-healing").resolve("metrics.db")
    seedPartiallyHealedLegacyWorkflow(dbPath)

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      assertPartiallyHealedStateEntry(connection)
    }
  }

  @Test
  fun `legacy workflow and goal state entries use their documented timestamp fallbacks`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-db-state-entry-fallbacks").resolve("metrics.db")

    seedLegacyStateEntryFallbacks(dbPath)

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      assertLegacyStateEntryFallbacks(connection)
      assertMissingTimestampRowsRemainUnchanged(connection)
    }

    DatabaseRuntime.ensureDatabase(dbPath).close()
  }

  @Test
  fun `goal continuation recovery accepts the runtime and prose continuation contracts`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-db-goal-continuation-issue-key").resolve("metrics.db")

    val runtimeGoalArtifactsJson =
      buildString {
        append("""{"goal_continuation":{"issue_key":" SKILL-117 ","subtask_id":1,"""")
        append("""suppress_pr":true,"goal_branch":"feature/117"}}""")
      }
    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      connection.createStatement().use { statement ->
        statement.executeUpdate(
          """
          INSERT INTO feature_task_workflows (
            workflow_id, mode, contract_version, workflow_status, artifacts_json, started_at, state_entered_at
          ) VALUES (
            'wftr-text-key', 'runtime', '0.1', 'running',
            '$runtimeGoalArtifactsJson',
            '2026-05-01T10:00:00Z', '2026-05-01T10:00:00Z'
          )
          """.trimIndent(),
        )
        statement.executeUpdate(
          """
          INSERT INTO feature_task_workflows (
            workflow_id, mode, contract_version, workflow_status, artifacts_json, started_at, state_entered_at
          ) VALUES (
            'wfl-prose-key', 'prose', '0.1', 'running',
            '{"goal_continuation":{"enabled":true,"issue_key":"SKILL-118","subtask_id":2,"suppress_pr":true}}',
            '2026-05-01T10:00:00Z', '2026-05-01T10:00:00Z'
          )
          """.trimIndent(),
        )
        statement.executeUpdate(
          """
          INSERT INTO feature_task_workflows (
            workflow_id, mode, contract_version, workflow_status, artifacts_json, started_at, state_entered_at
          ) VALUES (
            'wftr-number-key', 'runtime', '0.1', 'running',
            '{"goal_continuation":{"issue_key":117,"subtask_id":1,"suppress_pr":true,"goal_branch":"feature/117"}}',
            '2026-05-01T10:00:00Z', '2026-05-01T10:00:00Z'
          )
          """.trimIndent(),
        )
      }
    }

    DriverManager.getConnection("jdbc:sqlite:$dbPath").use { connection ->
      DatabaseColumnMigrations.applyWorkListMetadata(connection)
      assertEquals(
        "SKILL-117",
        tableColumnValue(connection, "feature_task_workflows", "workflow_id", "wftr-text-key", "issue_key"),
      )
      assertEquals(
        "SKILL-118",
        tableColumnValue(connection, "feature_task_workflows", "workflow_id", "wfl-prose-key", "issue_key"),
      )
      assertEquals(
        null,
        nullableTableColumnValue(connection, "feature_task_workflows", "workflow_id", "wftr-number-key", "issue_key"),
      )
    }
  }

  @Test
  fun `migration after v4 recovers imported decomposition parent issue keys`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-db-v5-issue-key-recovery").resolve("metrics.db")

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      connection.createStatement().use { statement ->
        statement.executeUpdate("DELETE FROM schema_migrations WHERE version = 5")
        statement.executeUpdate(
          """
          INSERT INTO feature_task_workflows (
            workflow_id, mode, contract_version, workflow_status, artifacts_json,
            started_at, state_entered_at, state_entered_at_estimated
          ) VALUES (
            'wfl-imported-parent', 'prose', '0.1', 'abandoned',
            '{"decomposition_runtime":{"issue_key":" SKILL-117 ","status":"in_progress"}}',
            '2026-05-01T10:00:00Z', '2026-05-01T10:00:00Z', 0
          )
          """.trimIndent(),
        )
      }
    }

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      assertEquals(
        "SKILL-117",
        tableColumnValue(connection, "feature_task_workflows", "workflow_id", "wfl-imported-parent", "issue_key"),
      )
      assertNotNull(migrationRows(connection).singleOrNull { row -> row.version == 5 })
    }
  }

  @Test
  fun `healthy database reopens do not retry unrecoverable issue key recovery`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-db-work-list-recovery").resolve("metrics.db")

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      connection.createStatement().use { statement ->
        statement.executeUpdate(
          """
          INSERT INTO feature_task_workflows (
            workflow_id, mode, contract_version, workflow_status, artifacts_json,
            started_at, state_entered_at, state_entered_at_estimated
          ) VALUES (
            'wftr-unrecoverable', 'runtime', '0.1', 'running', '{not json}',
            '2026-05-01T10:00:00Z', '2026-05-01T10:00:00Z', 0
          )
          """.trimIndent(),
        )
        statement.execute(
          """
          CREATE TRIGGER reject_recovery_retry
          BEFORE UPDATE ON feature_task_workflows
          BEGIN
            SELECT RAISE(ABORT, 'unrecoverable issue key must not be retried');
          END
          """.trimIndent(),
        )
      }
    }

    DatabaseRuntime.ensureDatabase(dbPath).close()
  }

  @Test
  fun `ensureDatabase adds missing review session column and backfills it`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-db-migrations").resolve("legacy-review-runs.db")
    createLegacyReviewRunsDatabase(dbPath)

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val columns = tableColumns(connection = connection, tableName = "review_runs")
      val reviewSessionId = reviewSessionId(connection = connection, reviewRunId = "rvw-legacy-001")

      assertTrue("review_session_id" in columns)
      assertEquals("rvw-legacy-001", reviewSessionId)
      assertEquals(DatabaseMigrations.migrations.size, migrationRows(connection).size)
    }
  }

  @Test
  fun `review routed skill column is unconstrained text`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-db-migrations").resolve("review-routed-skill.db")

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val columns = tableColumnTypes(connection = connection, tableName = "review_runs")
      val schemaSql = reviewRunsSchemaSql(connection)

      assertEquals("TEXT", columns["routed_skill"])
      assertTrue("routed_skill TEXT" in schemaSql)
      assertTrue("routed_skill TEXT CHECK" !in schemaSql)
      assertTrue("routed_skill TEXT NOT NULL CHECK" !in schemaSql)
    }
  }

  @Test
  fun `ensureDatabase adds missing finding issue category column with default`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-db-migrations").resolve("legacy-findings.db")
    createLegacyFeedbackEventsDatabase(dbPath)

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val columns = tableColumns(connection = connection, tableName = "findings")

      assertTrue("issue_category" in columns)
      assertEquals("other", findingIssueCategory(connection, "rvw-legacy-002", "F-001"))
    }
  }

  @Test
  fun `ensureDatabase adds review run lane attribution to a legacy store without losing rows`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-db-migrations").resolve("legacy-review-lanes.db")
    createLegacyFeedbackEventsDatabase(dbPath)
    val before =
      DriverManager.getConnection("jdbc:sqlite:$dbPath").use { connection ->
        Triple(rowCount(connection, "review_runs"), rowCount(connection, "findings"), findingRows(connection))
      }

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      assertTrue("review_run_lanes" in tableNames(connection))
      assertTrue("review_run_finding_lanes" in tableNames(connection))
      assertTrue(
        tableColumns(connection, "findings").containsAll(setOf("lane_skill_name", "lane_area", "lane_pack_slug")),
      )
      assertEquals(before.first, rowCount(connection, "review_runs"))
      assertEquals(before.second, rowCount(connection, "findings"))
      assertEquals(before.third, findingRows(connection), "Existing finding rows must survive unchanged.")
    }

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      assertEquals(before.first, rowCount(connection, "review_runs"), "Re-applying migrations must be a no-op.")
      assertEquals(before.second, rowCount(connection, "findings"))
      assertEquals(before.third, findingRows(connection))
      assertEquals(DatabaseMigrations.migrations.size, migrationRows(connection).size)
    }
  }

  @Test
  fun `relaxing telemetry outbox last_error backfills empty strings and preserves error text`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-outbox-migration").resolve("legacy-outbox.db")
    createLegacyTelemetryOutboxDatabase(dbPath)
    val before =
      DriverManager.getConnection("jdbc:sqlite:$dbPath").use { connection ->
        rowCount(connection, "telemetry_outbox")
      }

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      assertEquals(before, rowCount(connection, "telemetry_outbox"), "The rebuild must not drop a row.")
      assertEquals(
        0,
        scalarInt(connection, "SELECT COUNT(*) FROM telemetry_outbox WHERE last_error = ''"),
        "Every legacy empty-string last_error must be backfilled to NULL.",
      )
      assertEquals(
        2,
        scalarInt(connection, "SELECT COUNT(*) FROM telemetry_outbox WHERE last_error IS NULL"),
        "Both healthy rows must read as NULL.",
      )
      assertEquals(
        "boom",
        scalarString(connection, "SELECT last_error FROM telemetry_outbox WHERE id = 2"),
        "A genuine delivery failure must survive verbatim.",
      )
      assertFalse(
        tableInfo(connection, "telemetry_outbox").single { it.name == "last_error" }.notNull,
        "last_error must be nullable after the rebuild.",
      )
      assertTrue("idx_telemetry_outbox_pending" in tableIndexNames(connection))
    }
  }

  @Test
  fun `opening a legacy telemetry outbox adds skill_bill_version and preserves version-less rows`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-outbox-version").resolve("legacy-outbox.db")
    createLegacyTelemetryOutboxDatabase(dbPath)
    val before =
      DriverManager.getConnection("jdbc:sqlite:$dbPath").use { connection ->
        rowCount(connection, "telemetry_outbox")
      }

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      assertTrue(
        "skill_bill_version" in tableColumns(connection, "telemetry_outbox"),
        "An existing store must gain the column, not only a freshly created one.",
      )
      assertEquals(before, rowCount(connection, "telemetry_outbox"), "No pre-migration row may be dropped.")
      assertEquals(
        before,
        scalarInt(connection, "SELECT COUNT(*) FROM telemetry_outbox WHERE skill_bill_version IS NULL"),
        "Pre-migration rows must stay version-absent rather than being given a fabricated version.",
      )
    }
  }

  @Test
  fun `re-applying the telemetry outbox relaxation is a no-op with a single ledger entry`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-outbox-idempotent").resolve("legacy-outbox.db")
    createLegacyTelemetryOutboxDatabase(dbPath)

    DatabaseRuntime.ensureDatabase(dbPath).close()
    val afterFirst =
      DriverManager.getConnection("jdbc:sqlite:$dbPath").use { connection ->
        telemetryOutboxRows(connection)
      }

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      assertEquals(afterFirst, telemetryOutboxRows(connection), "Re-application must not alter a single row.")
      assertEquals(
        1,
        migrationRows(connection).count { it.name == "relax-telemetry-outbox-last-error" },
        "The ledger must record the relaxation exactly once.",
      )
    }
  }

  @Test
  fun `adding the review finding outcome key preserves existing ledger rows as unresolved`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-outcome-migration").resolve("legacy-ledger.db")
    DriverManager.getConnection("jdbc:sqlite:$dbPath").use { connection ->
      connection.createStatement().use { statement ->
        statement.execute(
          """
          CREATE TABLE unaddressed_findings (
            issue_key TEXT NOT NULL, workflow_id TEXT NOT NULL, subtask_id INTEGER NOT NULL,
            review_pass_number INTEGER NOT NULL, finding_ordinal INTEGER NOT NULL,
            severity TEXT NOT NULL, issue_category TEXT NOT NULL DEFAULT 'other',
            location TEXT NOT NULL, summary TEXT NOT NULL,
            recorded_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
            PRIMARY KEY (workflow_id, review_pass_number, finding_ordinal)
          )
          """.trimIndent(),
        )
        statement.executeUpdate(
          """
          INSERT INTO unaddressed_findings
            (issue_key, workflow_id, subtask_id, review_pass_number, finding_ordinal,
             severity, location, summary)
          VALUES ('SKILL-1', 'wf-legacy', 1, 1, 1, 'blocker', 'a.kt:1', 'pre-existing')
          """.trimIndent(),
        )
      }
    }

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      assertEquals(1, rowCount(connection, "unaddressed_findings"), "The pre-existing row must survive.")
      assertTrue(tableColumns(connection, "unaddressed_findings").containsAll(setOf("review_run_id", "finding_id")))
      assertEquals(
        1,
        scalarInt(
          connection,
          "SELECT COUNT(*) FROM unaddressed_findings WHERE review_run_id IS NULL AND finding_id IS NULL",
        ),
        "A row that predates the key must stay unresolved, never bucketed to a guessed review run.",
      )
      assertTrue("review_finding_outcomes" in tableNames(connection))
    }
  }

  @Test
  fun `migrating a copy of a real review metrics store preserves every table row count`() {
    val realStore = requireRealStore()
    val copy = Files.createTempDirectory("runtime-kotlin-real-store").resolve("metrics.db")
    Files.copy(realStore, copy)

    val before = DriverManager.getConnection("jdbc:sqlite:$copy").use(::allTableRowCounts)
    DatabaseRuntime.ensureDatabase(copy).close()
    val after = DriverManager.getConnection("jdbc:sqlite:$copy").use(::allTableRowCounts)

    before.filterKeys { it != SCHEMA_MIGRATIONS_TABLE }.forEach { (table, count) ->
      assertEquals(count, after[table], "Migration must preserve every row of '$table'.")
    }
    assertTrue(
      (after[SCHEMA_MIGRATIONS_TABLE] ?: 0) >= (before[SCHEMA_MIGRATIONS_TABLE] ?: 0),
      "Migration must never drop an applied-migration record.",
    )
  }
}

class DatabaseMigrationsReviewAttributionTest {
  @Test
  fun `migrating a copy of a real review metrics store leaves referential integrity sound`() {
    val realStore = requireRealStore()
    val copy = Files.createTempDirectory("runtime-kotlin-real-store-integrity").resolve("metrics.db")
    Files.copy(realStore, copy)

    val trackedTables =
      listOf(
        "telemetry_outbox",
        "review_runs",
        "findings",
        "feedback_events",
        "learnings",
        "session_learnings",
        "unaddressed_findings",
      )
    val before =
      DriverManager.getConnection("jdbc:sqlite:$copy").use { connection ->
        val present = tableNames(connection)
        trackedTables.filter(present::contains).associateWith { table -> rowCount(connection, table) }
      }

    DatabaseRuntime.ensureDatabase(copy).use { connection ->
      before.forEach { (table, count) ->
        assertEquals(count, rowCount(connection, table), "Migration must preserve every row of '$table'.")
      }
      assertEquals(
        0,
        scalarInt(connection, "SELECT COUNT(*) FROM telemetry_outbox WHERE last_error = ''"),
        "AC-001: no empty-string last_error may survive the backfill.",
      )
      assertEquals(
        0,
        scalarInt(
          connection,
          "SELECT COUNT(*) FROM findings f " +
            "WHERE NOT EXISTS (SELECT 1 FROM review_runs r WHERE r.review_run_id = f.review_run_id)",
        ),
        "AC-009: no finding may be left without its review_runs parent.",
      )

      val store = TelemetryOutboxStore(connection, version = "test-runtime-version")
      val pendingIds = store.listPending(null).map(TelemetryOutboxRecord::id)
      pendingIds.forEach { id -> store.markSynced(id = id, syncedAt = "2026-09-01 00:00:00") }
      assertTrue(store.listPending(null).isEmpty(), "The outbox must drain fully after marking every row synced.")
      assertEquals(null, store.latestError(), "A fully drained outbox must report no delivery error.")
    }
  }

  @Test
  fun `ensureDatabase creates feature implement telemetry health columns with defaults`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-db-migrations").resolve("feature-task-health.db")

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val columns = tableColumns(connection = connection, tableName = "feature_implement_sessions")
      connection.createStatement().use { statement ->
        statement.executeUpdate("INSERT INTO feature_implement_sessions (session_id) VALUES ('fis-defaults')")
      }

      assertTrue("source" in columns)
      assertTrue("child_steps_json" in columns)
      assertTrue("duplicate_terminal_finished_events" in columns)
      assertEquals("production", featureImplementColumnValue(connection, "source"))
      assertEquals("", featureImplementColumnValue(connection, "child_steps_json"))
      assertEquals(0, featureImplementColumnValue(connection, "duplicate_terminal_finished_events"))
    }
  }

  @Test
  fun `ensureDatabase heals columns missing from a fully version-recorded legacy database`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-db-migrations").resolve("legacy-recorded-implement.db")
    createLegacyFeatureImplementSessionsDatabase(dbPath)

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val columns = tableColumns(connection = connection, tableName = "feature_implement_sessions")
      connection.createStatement().use { statement ->
        statement.executeUpdate("INSERT INTO feature_implement_sessions (session_id) VALUES ('fis-defaults')")
      }

      assertTrue("source" in columns, "source must be healed even when every migration version is already recorded.")
      assertEquals("production", featureImplementColumnValue(connection, "source"))
      assertEquals(DatabaseMigrations.migrations.size, migrationRows(connection).size)
    }
  }

  @Test
  fun `ensureDatabase heals legacy lifecycle starts before finished duration telemetry`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-db-migrations").resolve("legacy-lifecycle-starts.db")
    createLegacyLifecycleSessionsWithoutStartsDatabase(dbPath)

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      assertTrue("started_at" in tableColumns(connection, "feature_implement_sessions"))
      assertTrue("started_at" in tableColumns(connection, "feature_verify_sessions"))
      assertTrue("started_at" in tableColumns(connection, "quality_check_sessions"))
      assertTrue("fallback" in tableColumns(connection, "quality_check_sessions"))
      assertTrue("fallback_reason" in tableColumns(connection, "quality_check_sessions"))
      assertEquals(
        0,
        tableColumnValue(
          connection = connection,
          tableName = "quality_check_sessions",
          pkColumnName = "session_id",
          pkValue = "qcs-legacy-start",
          columnName = "fallback",
        ),
      )
      assertEquals(
        LEGACY_FEATURE_TASK_WORKFLOW_STARTED_AT,
        tableColumnValue(
          connection = connection,
          tableName = "feature_implement_sessions",
          pkColumnName = "session_id",
          pkValue = "fis-legacy-duration",
          columnName = "started_at",
        ),
        "Feature implement started_at must be recovered from the matching legacy workflow row.",
      )
    }
  }

  @Test
  fun `ensureDatabase adds token estimation columns to feature_task_runtime_sessions`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-db-migrations").resolve("legacy-ftr-tokens.db")
    createLegacyFeatureTaskRuntimeSessionsDatabase(dbPath)

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val columns = tableColumns(connection = connection, tableName = "feature_task_runtime_sessions")

      assertTrue("estimated_phase_tokens_json" in columns, "estimated_phase_tokens_json must be healed.")
      assertTrue("estimated_total_tokens" in columns, "estimated_total_tokens must be healed.")
      assertEquals(
        null,
        nullableTableColumnValue(
          connection,
          "feature_task_runtime_sessions",
          "session_id",
          "ftr-pre-91",
          "estimated_phase_tokens_json",
        ),
        "Pre-feature row must read null for estimated_phase_tokens_json.",
      )
      assertEquals(
        null,
        nullableTableColumnValue(
          connection,
          "feature_task_runtime_sessions",
          "session_id",
          "ftr-pre-91",
          "estimated_total_tokens",
        ),
        "Pre-feature row must read null for estimated_total_tokens.",
      )
    }
  }

  @Test
  fun `ensureDatabase adds token estimation columns to feature_implement_sessions`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-db-migrations").resolve("legacy-fis-tokens.db")
    createLegacyFeatureImplementSessionsDatabase(dbPath)
    DriverManager.getConnection("jdbc:sqlite:$dbPath").use { connection ->
      connection.createStatement().use { statement ->
        statement.executeUpdate("INSERT INTO feature_implement_sessions (session_id) VALUES ('fis-pre-91')")
      }
    }

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val columns = tableColumns(connection = connection, tableName = "feature_implement_sessions")

      assertTrue("estimated_phase_tokens_json" in columns, "estimated_phase_tokens_json must be healed.")
      assertTrue("estimated_total_tokens" in columns, "estimated_total_tokens must be healed.")
      assertEquals(
        null,
        nullableTableColumnValue(
          connection,
          "feature_implement_sessions",
          "session_id",
          "fis-pre-91",
          "estimated_phase_tokens_json",
        ),
        "Pre-feature row must read null for estimated_phase_tokens_json.",
      )
      assertEquals(
        null,
        nullableTableColumnValue(
          connection,
          "feature_implement_sessions",
          "session_id",
          "fis-pre-91",
          "estimated_total_tokens",
        ),
        "Pre-feature row must read null for estimated_total_tokens.",
      )
    }
  }

  @Test
  fun `ensureDatabase heals goal subtask agent attribution columns on a fully version-recorded legacy database`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-db-migrations").resolve("legacy-goal-subtask.db")
    createLegacyGoalSubtaskEventsDatabase(dbPath)

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val columns = tableColumns(connection = connection, tableName = "goal_subtask_events")

      assertTrue("finalizing_agent_id" in columns, "finalizing_agent_id must be healed onto a legacy table.")
      assertTrue("participating_agent_ids" in columns, "participating_agent_ids must be healed onto a legacy table.")
      connection.createStatement().use { statement ->
        statement.executeUpdate(
          """
          INSERT INTO goal_subtask_events (
            issue_key, workflow_id, subtask_id, subtask_name, status,
            started_at, finished_at, duration_ms, attempt_count
          ) VALUES ('SKILL-89', 'wf-legacy', 1, 'heal', 'complete', 't0', 't1', 1000, 1)
          """.trimIndent(),
        )
      }
      assertEquals("[]", goalSubtaskColumnValue(connection, "participating_agent_ids"))
      assertEquals(DatabaseMigrations.migrations.size, migrationRows(connection).size)
    }
  }

  @Test
  fun `ensureDatabase creates goal issue progress without altering legacy goal rows`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-db-migrations").resolve("legacy-goal-issue.db")
    createLegacyGoalSubtaskEventsDatabase(dbPath)
    DriverManager.getConnection("jdbc:sqlite:$dbPath").use { connection ->
      connection.createStatement().use { statement ->
        statement.executeUpdate(
          """
          INSERT INTO goal_subtask_events (
            issue_key, workflow_id, subtask_id, subtask_name, status,
            started_at, finished_at, duration_ms, attempt_count
          ) VALUES ('SKILL-109', 'wf-existing', 1, 'implement', 'blocked', 't0', 't1', 1000, 2)
          """.trimIndent(),
        )
      }
    }
    val before =
      DriverManager.getConnection("jdbc:sqlite:$dbPath").use { connection ->
        legacyGoalSubtaskRows(connection)
      }

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val columns = tableColumns(connection = connection, tableName = "goal_issue_progress")
      val after = legacyGoalSubtaskRows(connection)

      assertTrue("parent_workflow_id" in columns, "goal_issue_progress must exist after startup.")
      assertTrue("last_activity_at" in columns, "goal_issue_progress must heal last_activity_at after startup.")
      assertTrue("last_blocked_at" in columns, "goal_issue_progress must heal last_blocked_at after startup.")
      assertTrue("latest_segment_workflow_id" in columns)
      assertTrue("last_blocked_segment_workflow_id" in columns)
      assertEquals(before, after, "Adding goal_issue_progress must not rewrite existing subtask rows.")
    }
  }

  @Test
  fun `ensureDatabase migrates legacy feedback event values to current schema`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-db-migrations").resolve("legacy-feedback-events.db")
    createLegacyFeedbackEventsDatabase(dbPath)

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val schemaSql = feedbackEventsSchemaSql(connection)
      val migratedEventType =
        feedbackEventType(connection = connection, reviewRunId = "rvw-legacy-002", findingId = "F-001")

      assertTrue("'fix_rejected'" in schemaSql)
      assertEquals("fix_rejected", migratedEventType)
      assertEquals(DatabaseMigrations.migrations.size, migrationRows(connection).size)
    }
  }

  @Test
  fun `ensureDatabase resumes from recorded migration version`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-db-migrations").resolve("partial.db")
    createLegacyFeedbackEventsDatabase(dbPath)

    DriverManager.getConnection("jdbc:sqlite:$dbPath").use { connection ->
      connection.createStatement().use { statement ->
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
        statement.setInt(1, 1)
        statement.setString(2, "add-review-workflow-session-columns")
        statement.executeUpdate()
      }
    }

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      assertEquals("fix_rejected", feedbackEventType(connection, "rvw-legacy-002", "F-001"))
      assertNotNull(migrationRows(connection).singleOrNull { row -> row.version == 2 })
    }
  }

  @Test
  fun `legacy review runs gain canonical attribution columns and an unambiguous backfill`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-review-canonical-backfill").resolve("metrics.db")
    val expectedRowCount = seedLegacyReviewRunAttributionVariants(dbPath)

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      assertTrue(
        columnNames(connection, "review_runs").containsAll(
          setOf(
            "routed_skill_canonical",
            "detected_stack_canonical",
            "detected_scope_canonical",
            "detected_scope_detail",
          ),
        ),
      )
      assertEquals(expectedRowCount, rowCount(connection, "review_runs"))

      assertEquals(
        mapOf("bill-kmp-code-review" to 11, "unresolved" to 1),
        groupCount(connection, "routed_skill_canonical"),
      )
      assertEquals(mapOf("kotlin" to 11, "unresolved" to 1), groupCount(connection, "detected_stack_canonical"))
      assertEquals(
        mapOf("commit_range" to 6, "pull_request" to 5, "unresolved" to 1),
        groupCount(connection, "detected_scope_canonical"),
      )
      assertEquals("main..HEAD", reviewRunColumn(connection, "rvw-skill-0", "detected_scope_detail"))

      LEGACY_ROUTED_SKILL_VARIANTS.forEachIndexed { index, routedSkill ->
        assertEquals(routedSkill, reviewRunColumn(connection, "rvw-skill-$index", "routed_skill"))
      }
      assertEquals(AMBIGUOUS_ROUTED_SKILL, reviewRunColumn(connection, "rvw-ambiguous", "routed_skill"))
      assertEquals("unresolved", reviewRunColumn(connection, "rvw-ambiguous", "execution_mode"))
    }
  }

  @Test
  fun `the review attribution backfill is idempotent across repeated opens`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-review-canonical-reopen").resolve("metrics.db")
    val expectedRowCount = seedLegacyReviewRunAttributionVariants(dbPath)

    DatabaseRuntime.ensureDatabase(dbPath).close()

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      assertEquals(expectedRowCount, rowCount(connection, "review_runs"))
      assertEquals(
        mapOf("bill-kmp-code-review" to 11, "unresolved" to 1),
        groupCount(connection, "routed_skill_canonical"),
      )
      assertEquals(mapOf("kotlin" to 11, "unresolved" to 1), groupCount(connection, "detected_stack_canonical"))
      assertEquals(AMBIGUOUS_ROUTED_SKILL, reviewRunColumn(connection, "rvw-ambiguous", "routed_skill"))
    }
  }

  @Test
  fun `review attribution backfill runs once and never overwrites ingestion canonicals`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-review-canonical-converge").resolve("metrics.db")

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
        statement.execute(
          "INSERT INTO review_runs (review_run_id, routed_skill, detected_scope, detected_stack, raw_text) " +
            "VALUES ('rvw-unresolvable', 'bill-kmp-code-review, bill-ios-code-review', " +
            "'whatever the agent felt like', 'kotlin, ios', 'raw')",
        )
      }
    }

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      assertEquals("unresolved", reviewRunColumn(connection, "rvw-unresolvable", "routed_skill_canonical"))

      connection.createStatement().use { statement ->
        statement.execute(
          "UPDATE review_runs SET routed_skill_canonical = 'bill-acme-code-review' " +
            "WHERE review_run_id = 'rvw-unresolvable'",
        )
      }
    }

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      assertEquals("bill-acme-code-review", reviewRunColumn(connection, "rvw-unresolvable", "routed_skill_canonical"))
      assertEquals(
        1,
        connection.createStatement().use { statement ->
          statement.executeQuery(
            "SELECT COUNT(*) FROM schema_migrations WHERE name = 'backfill-review-attribution-canonicals'",
          ).use { resultSet ->
            resultSet.next()
            resultSet.getInt(1)
          }
        },
        "The canonical backfill must be recorded once as a one-shot ledger migration.",
      )
    }
  }

  @Test
  fun `migrating a copy of a real review metrics store preserves every row`() {
    val source = requireGatedStore(MIGRATION_FIXTURE_ENV)
    val copy = Files.createTempDirectory("runtime-kotlin-real-store-migration").resolve("metrics.db")
    Files.copy(source, copy)

    val before =
      DriverManager.getConnection("jdbc:sqlite:$copy").use { connection ->
        rowCount(connection, "review_runs") to rowCount(connection, "findings")
      }

    DatabaseRuntime.ensureDatabase(copy).use { connection ->
      assertEquals(before.first, rowCount(connection, "review_runs"), "review_runs lost rows during migration.")
      assertEquals(before.second, rowCount(connection, "findings"), "findings lost rows during migration.")

      val routedSkills = groupCount(connection, "routed_skill_canonical")
      val stacks = groupCount(connection, "detected_stack_canonical")
      assertTrue(routedSkills.size < before.first, "Canonical routed skills must collapse the raw variants.")
      assertTrue(stacks.size < before.first, "Canonical stacks must collapse the raw variants.")
      assertEquals(0, executionModeGaps(connection), "Every run must carry an execution_mode after migration.")
    }
  }

  @Test
  fun `legacy goal runner artifacts move once into controls with migration diagnostics`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-legacy-goal-controls").resolve("metrics.db")
    seedLegacyGoalRunnerControlsMigrationFixture(dbPath)
    SqliteTestDiagnostics.reset()

    DatabaseRuntime.establishSchemaReadiness(dbPath, SqliteTestDiagnostics)
    DriverManager.getConnection("jdbc:sqlite:$dbPath").use { connection ->
      val store = GoalRunnerControlStore(connection)
      assertEquals(
        GoalRunnerReviewPolicy(CodeReviewExecutionMode.INLINE),
        store.reviewPolicy("wftr-legacy-goal-parent"),
      )
      assertEquals(
        mapOf(
          2 to
            GoalRunnerOutOfBandAcceptance(
              subtaskId = 2,
              commitSha = "legacy-commit",
              reason = "accepted outside the normal review path",
              acceptedAt = "2026-09-17T10:00:00Z",
            ),
        ),
        store.outOfBandAcceptances("wftr-legacy-goal-parent"),
      )
    }

    val migrationWarnings =
      SqliteTestDiagnostics.recordedWarnings()
        .filter { warning -> warning.contains("record_kind=migration") }
    assertEquals(1, migrationWarnings.size)
    assertTrue(migrationWarnings.single().contains("parent_workflow_id=wftr-legacy-goal-parent"))
    assertTrue(migrationWarnings.single().contains(GOAL_REVIEW_POLICY_ARTIFACT_KEY))
    assertTrue(migrationWarnings.single().contains(GOAL_OUT_OF_BAND_ACCEPTANCE_ARTIFACT_KEY))

    DatabaseRuntime.establishSchemaReadiness(dbPath)
    DriverManager.getConnection("jdbc:sqlite:$dbPath").use { connection ->
      val store = GoalRunnerControlStore(connection)
      assertEquals(CodeReviewExecutionMode.INLINE, store.reviewPolicy("wftr-legacy-goal-parent")?.codeReviewMode)
      assertEquals(
        "legacy-commit",
        store.outOfBandAcceptances("wftr-legacy-goal-parent").getValue(2).commitSha,
      )
    }
    assertEquals(
      1,
      SqliteTestDiagnostics.recordedWarnings().count { warning ->
        warning.contains("record_kind=migration")
      },
    )
  }

  @Test
  fun `legacy goal runner migration rejects a fractional acceptance identity and persists nothing`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-legacy-goal-controls-invalid").resolve("metrics.db")
    seedLegacyGoalRunnerControlsMigrationFixture(
      dbPath,
      acceptances = listOf(VALID_LEGACY_GOAL_ACCEPTANCE + ("subtask_id" to 2.5)),
    )
    val artifactsBefore = legacyGoalParentArtifactsJson(dbPath)

    assertFailsWith<InvalidWorkflowStateSchemaError> {
      DatabaseRuntime.establishSchemaReadiness(dbPath)
    }

    DriverManager.getConnection("jdbc:sqlite:$dbPath").use { connection ->
      assertEquals(
        0,
        scalarInt(
          connection,
          """
          SELECT COUNT(*) FROM goal_runner_controls
          WHERE parent_workflow_id = 'wftr-legacy-goal-parent'
            AND (review_policy_json IS NOT NULL OR out_of_band_acceptances_json IS NOT NULL)
          """.trimIndent(),
        ),
        "A rejected legacy acceptance must not leave a review policy or acceptance control row behind.",
      )
      assertEquals(artifactsBefore, legacyGoalParentArtifactsJson(dbPath))
      assertEquals(
        0,
        scalarInt(
          connection,
          "SELECT COUNT(*) FROM schema_migrations WHERE name = 'migrate-legacy-goal-runner-controls'",
        ),
        "The failed migration must not be recorded as applied.",
      )
    }
  }

  private fun legacyGoalParentArtifactsJson(dbPath: Path): String? =
    DriverManager.getConnection("jdbc:sqlite:$dbPath").use { connection ->
      scalarString(
        connection,
        "SELECT artifacts_json FROM feature_task_workflows WHERE workflow_id = 'wftr-legacy-goal-parent'",
      )
    }

  @Test
  fun `establishment stamps user_version to the highest ledger migration version`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-db-user-version").resolve("metrics.db")
    DatabaseRuntime.ensureDatabase(dbPath).close()
    val expectedVersion = DatabaseMigrations.migrations.maxOf { migration -> migration.version }
    val identity = DatabaseIdentity.read(dbPath)
    assertNotNull(identity)
    assertEquals(expectedVersion, identity.userVersion)
    DriverManager.getConnection("jdbc:sqlite:$dbPath").use { connection ->
      connection.createStatement().use { statement ->
        statement.executeQuery("PRAGMA user_version").use { rows ->
          assertTrue(rows.next())
          assertEquals(expectedVersion, rows.getInt(1))
        }
      }
    }
  }
}
