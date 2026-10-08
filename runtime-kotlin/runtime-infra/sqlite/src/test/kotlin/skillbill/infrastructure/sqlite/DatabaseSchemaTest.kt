package skillbill.infrastructure.sqlite

import skillbill.infrastructure.sqlite.core.schema.DatabaseRuntime
import skillbill.infrastructure.sqlite.core.schema.DatabaseSchema
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DatabaseSchemaTest {
  @Test
  fun `ensureDatabase creates parent directories enables foreign keys and bootstraps schema`() {
    val tempDir = Files.createTempDirectory("runtime-kotlin-db-schema")
    val dbPath = tempDir.resolve("nested").resolve("metrics.db")

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      assertTrue(Files.isDirectory(dbPath.parent))
      assertTrue(Files.exists(dbPath))
      assertPragmas(connection)
      assertSchemaObjects(connection)
      assertGoalIssueProgressSchema(connection)
    }
  }

  @Test
  fun `ensureDatabase creates the goal planning preparations table and lookup index`() {
    val tempDir = Files.createTempDirectory("runtime-kotlin-db-schema-goal-planning")
    val dbPath = tempDir.resolve("metrics.db")

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val tables = sqliteObjects(connection = connection, type = "table")
      val indexes = sqliteObjects(connection = connection, type = "index")

      assertTrue("goal_planning_preparations" in DatabaseSchema.tableNames)
      assertTrue("goal_planning_preparations" in tables)
      assertTrue("idx_goal_planning_preparations_lookup" in DatabaseSchema.indexNames)
      assertTrue("idx_goal_planning_preparations_lookup" in indexes)
    }
  }

  @Test
  fun `ensureDatabase creates unaddressed findings ledger and issue index`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-ledger-schema").resolve("metrics.db")

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      assertTrue("unaddressed_findings" in sqliteObjects(connection, "table"))
      assertTrue("idx_unaddressed_findings_issue" in sqliteObjects(connection, "index"))
    }
  }

  @Test
  fun `a freshly created telemetry outbox declares last_error nullable`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-outbox-schema").resolve("metrics.db")

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val lastError = tableInfo(connection, "telemetry_outbox").single { it.name == "last_error" }
      assertFalse(lastError.notNull, "last_error must be nullable so NULL can mean 'no delivery failure'.")
      assertEquals(null, lastError.defaultValue, "last_error must not default to '' on a fresh database.")
    }
  }

  @Test
  fun `ensureDatabase creates the review finding outcome key on a fresh database`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-outcome-schema").resolve("metrics.db")

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      assertTrue("review_finding_outcomes" in DatabaseSchema.tableNames)
      assertTrue("review_finding_outcomes" in sqliteObjects(connection, "table"))
      assertTrue("idx_review_finding_outcomes_run" in DatabaseSchema.indexNames)
      assertTrue("idx_review_finding_outcomes_run" in sqliteObjects(connection, "index"))
      assertTrue("idx_unaddressed_findings_run" in DatabaseSchema.indexNames)
      assertTrue("idx_unaddressed_findings_run" in sqliteObjects(connection, "index"))

      val ledgerColumns = tableInfo(connection, "unaddressed_findings").associateBy { it.name }
      setOf("review_run_id", "finding_id").forEach { column ->
        val info = assertNotNull(ledgerColumns[column], "unaddressed_findings must carry '$column'.")
        assertFalse(info.notNull, "$column must be nullable: an unimported pass has no key to record.")
      }
    }
  }

  @Test
  fun `review finding outcomes reject a half-populated key`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-outcome-check").resolve("metrics.db")

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      connection.createStatement().use { statement ->
        statement.executeUpdate(
          """
          INSERT INTO review_finding_outcomes
            (workflow_id, review_pass_number, finding_ordinal, key_state, outcome)
          VALUES ('wf-1', 1, 1, 'unresolved', 'carried')
          """.trimIndent(),
        )
      }
      assertFailsWith<SQLException>("A resolved key state without both key columns must be rejected.") {
        connection.createStatement().use { statement ->
          statement.executeUpdate(
            """
            INSERT INTO review_finding_outcomes
              (workflow_id, review_pass_number, finding_ordinal, review_run_id, key_state, outcome)
            VALUES ('wf-1', 1, 2, 'rvw-1', 'resolved', 'carried')
            """.trimIndent(),
          )
        }
      }
      assertFailsWith<SQLException>("An unknown outcome must be rejected rather than silently stored.") {
        connection.createStatement().use { statement ->
          statement.executeUpdate(
            """
            INSERT INTO review_finding_outcomes
              (workflow_id, review_pass_number, finding_ordinal, key_state, outcome)
            VALUES ('wf-1', 1, 3, 'unresolved', 'invented')
            """.trimIndent(),
          )
        }
      }
    }
  }

  @Test
  fun `ensureDatabase heals a partially created unaddressed findings ledger`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-partial-ledger").resolve("metrics.db")
    DriverManager.getConnection("jdbc:sqlite:$dbPath").use { connection ->
      connection.createStatement().use { statement ->
        statement.execute(
          """
          CREATE TABLE unaddressed_findings (
            workflow_id TEXT NOT NULL, review_pass_number INTEGER NOT NULL,
            finding_ordinal INTEGER NOT NULL, PRIMARY KEY (workflow_id, review_pass_number, finding_ordinal)
          )
          """.trimIndent(),
        )
      }
    }

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val columnNames = tableInfo(connection, "unaddressed_findings").map { it.name }.toSet()
      assertTrue(
        columnNames.containsAll(
          setOf("issue_key", "subtask_id", "severity", "issue_category", "location", "summary"),
        ),
      )
      assertTrue("idx_unaddressed_findings_issue" in sqliteObjects(connection, "index"))

      assertTrue(columnNames.containsAll(setOf("review_run_id", "finding_id")))
      assertTrue("idx_unaddressed_findings_run" in sqliteObjects(connection, "index"))
    }
  }

  @Test
  fun `ensureDatabase creates review run canonical attribution columns`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-review-canonical-schema").resolve("metrics.db")

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val columns = tableInfo(connection, "review_runs").associateBy { it.name }

      setOf("routed_skill_canonical", "detected_stack_canonical", "detected_scope_canonical").forEach { name ->
        val column = requireNotNull(columns[name]) { "review_runs is missing '$name'." }
        assertEquals("TEXT", column.type.uppercase())
        assertTrue(column.notNull, "'$name' must be NOT NULL.")
        assertEquals("'unresolved'", column.defaultValue)
      }

      val detail = requireNotNull(columns["detected_scope_detail"]) { "review_runs is missing scope detail." }
      assertEquals("TEXT", detail.type.uppercase())
      assertFalse(detail.notNull, "detected_scope_detail holds free-form text and stays nullable.")
    }
  }

  @Test
  fun `ensureDatabase creates the review run lanes table and finding lane columns`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-review-lane-schema").resolve("metrics.db")

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      assertTrue("review_run_lanes" in DatabaseSchema.tableNames)
      assertTrue("review_run_lanes" in sqliteObjects(connection, "table"))
      assertTrue("idx_review_run_lanes_pack_area" in sqliteObjects(connection, "index"))
      assertTrue("idx_findings_lane" in sqliteObjects(connection, "index"))
      assertTrue("idx_review_runs_routed_skill_canonical" in sqliteObjects(connection, "index"))

      assertTrue("review_run_finding_lanes" in DatabaseSchema.tableNames)
      assertEquals(
        setOf("review_run_id", "finding_id", "lane_skill_name", "recorded_at"),
        tableInfo(connection, "review_run_finding_lanes").map { it.name }.toSet(),
      )

      val laneColumns = tableInfo(connection, "review_run_lanes").associateBy { it.name }
      assertEquals(
        setOf(
          "review_run_id",
          "lane_skill_name",
          "pack_slug",
          "area",
          "depth",
          "required",
          "order_index",
          "origin_layer_chain",
          "resolution_state",
          "review_disposition",
          "bundle_composition_digest",
          "segment_accounting_json",
          "unreviewed_segment_ids",
          "budget_dimension",
          "recorded_at",
        ),
        laneColumns.keys,
      )
      assertEquals(1, laneColumns.getValue("review_run_id").primaryKeyPosition)
      assertEquals(2, laneColumns.getValue("lane_skill_name").primaryKeyPosition)

      val findingColumns = tableInfo(connection, "findings").map { it.name }.toSet()
      assertTrue(findingColumns.containsAll(setOf("lane_skill_name", "lane_area", "lane_pack_slug")))
    }
  }

  private fun assertPragmas(connection: Connection) {
    assertEquals(1, pragmaInt(connection, "foreign_keys"))
    assertEquals(5000, pragmaInt(connection, "busy_timeout"))
    assertEquals("wal", pragmaString(connection, "journal_mode").lowercase())
  }

  private fun assertSchemaObjects(connection: Connection) {
    val tables = sqliteObjects(connection = connection, type = "table")
    val indexes = sqliteObjects(connection = connection, type = "index")
    assertTrue(
      DatabaseSchema.tableNames.all { it in tables },
      "Missing tables: ${DatabaseSchema.tableNames - tables}",
    )
    assertTrue(
      DatabaseSchema.indexNames.all { it in indexes },
      "Missing indexes: ${DatabaseSchema.indexNames - indexes}",
    )
    assertTrue(
      "feature_task_workflows" in DatabaseSchema.tableNames,
      "feature_task_workflows must be registered in DatabaseSchema.tableNames.",
    )
    assertTrue(
      "feature_task_workflows" in tables,
      "feature_task_workflows must be created by the base schema.",
    )
    assertTrue(
      "feature_implement_workflows" !in tables && "feature_task_runtime_workflows" !in tables,
      "old feature-task workflow tables must not be authoritative stores in fresh schema.",
    )
  }

  private fun assertGoalIssueProgressSchema(connection: Connection) {
    val goalIssueColumns = tableInfo(connection, "goal_issue_progress")
    assertEquals(
      listOf(
        "parent_workflow_id",
        "issue_key",
        "total_invocations",
        "total_blocks",
        "total_resumes",
        "first_started_at",
        "last_activity_at",
        "last_blocked_at",
        "latest_segment_workflow_id",
        "last_blocked_segment_workflow_id",
        "finished_at",
        "status",
        "state_entered_at",
        "state_entered_at_estimated",
        "subtasks_complete",
        "subtasks_blocked",
        "subtasks_skipped",
        "mode",
        "finished_event_emitted_at",
        "no_change_reason",
      ),
      goalIssueColumns.map { it.name },
    )
    assertEquals(1, goalIssueColumns.single { it.name == "parent_workflow_id" }.primaryKeyPosition)
    assertEquals(2, goalIssueColumns.single { it.name == "issue_key" }.primaryKeyPosition)
  }

  private fun pragmaInt(
    connection: Connection,
    name: String,
  ): Int =
    connection.createStatement().use { statement ->
      statement.executeQuery("PRAGMA $name").use { resultSet ->
        resultSet.next()
        resultSet.getInt(1)
      }
    }

  private fun pragmaString(
    connection: Connection,
    name: String,
  ): String =
    connection.createStatement().use { statement ->
      statement.executeQuery("PRAGMA $name").use { resultSet ->
        resultSet.next()
        resultSet.getString(1)
      }
    }

  private fun sqliteObjects(
    connection: Connection,
    type: String,
  ): Set<String> =
    connection.prepareStatement(
      """
      SELECT name
      FROM sqlite_master
      WHERE type = ?
      """.trimIndent(),
    ).use { statement ->
      statement.setString(1, type)
      statement.executeQuery().use { resultSet ->
        buildSet {
          while (resultSet.next()) {
            add(resultSet.getString("name"))
          }
        }
      }
    }

  private fun tableInfo(
    connection: Connection,
    tableName: String,
  ): List<TableColumn> =
    connection.prepareStatement("PRAGMA table_info($tableName)").use { statement ->
      statement.executeQuery().use { resultSet ->
        buildList {
          while (resultSet.next()) {
            add(
              TableColumn(
                name = resultSet.getString("name"),
                primaryKeyPosition = resultSet.getInt("pk"),
                type = resultSet.getString("type"),
                notNull = resultSet.getInt("notnull") == 1,
                defaultValue = resultSet.getString("dflt_value"),
              ),
            )
          }
        }
      }
    }

  private data class TableColumn(
    val name: String,
    val primaryKeyPosition: Int,
    val type: String = "",
    val notNull: Boolean = false,
    val defaultValue: String? = null,
  )
}
