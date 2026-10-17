package skillbill.infrastructure.sqlite

import skillbill.infrastructure.sqlite.core.schema.DatabaseRuntime
import skillbill.infrastructure.sqlite.worklist.SQLiteWorkListRepository
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.verify.FeatureVerifyWorkflowDefinition
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals

class SQLiteWorkListRepositoryTest {
  @Test
  fun `work list sorts parsed instants exactly before applying its limit and excludes child goal rows`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-work-list").resolve("metrics.db")

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      insertFeatureVerify(connection, "wfv-mid", "2026-05-01T12:00:00.000002+00:00")
      insertGoal(connection, "goal-new", "SKILL-117", "2026-05-01 12:00:00.000003")
      insertFeatureTask(connection, "wftr-tie-a", "runtime", "2026-05-01T12:00:00.000004Z")
      insertFeatureVerify(connection, "wfv-tie-z", "2026-05-01T12:00:00.000004Z")
      insertGoalChildRows(connection)

      val repository = SQLiteWorkListRepository(connection, NoopRuntimeDiagnostics)
      val all = repository.list()

      assertEquals(
        listOf("wfv-tie-z", "wftr-tie-a", "goal-new", "wfv-mid"),
        all.items.map { it.workflowId },
      )
      assertEquals(
        listOf("feature-verify", "feature-task-runtime", "feature-goal", "feature-verify"),
        all.items.map { it.workflowKind.wireValue },
      )
      assertEquals(
        listOf("wfv-tie-z", "wftr-tie-a", "goal-new"),
        repository.list(limit = 3).items.map { it.workflowId },
      )
    }
  }

  @Test
  fun `work list accepts every workflow status the workflow definitions declare`() {
    val declaredStatuses =
      FeatureTaskRuntimePhaseWorkflowDefinition.definition.workflowStatuses +
        FeatureVerifyWorkflowDefinition.definition.workflowStatuses

    DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
      createWorkListTables(connection)
      declaredStatuses.forEachIndexed { index, status ->
        connection.createStatement().use { statement ->
          statement.executeUpdate(
            featureTaskWorkflowRow(
              FeatureTaskWorkflowRow(workflowId = "'wftr-$index'", workflowStatus = status),
            ),
          )
        }
      }

      val states = SQLiteWorkListRepository(connection, NoopRuntimeDiagnostics).list().items.map { it.currentState }.toSet()

      assertEquals(declaredStatuses, states)
    }
  }

  @Test
  fun `work list rejects malformed persisted rows at every read boundary`() {
    assertMalformedWorkListRow(
      featureTaskWorkflowRow(FeatureTaskWorkflowRow(workflowId = "NULL")),
    )
    assertMalformedWorkListRow(
      featureTaskWorkflowRow(FeatureTaskWorkflowRow(mode = "unknown", workflowId = "'wf-kind'")),
    )
    assertMalformedWorkListRow(
      featureTaskWorkflowRow(FeatureTaskWorkflowRow(workflowId = "'wf-state'", workflowStatus = "unknown")),
    )
    assertMalformedWorkListRow(
      featureTaskWorkflowRow(FeatureTaskWorkflowRow(workflowId = "'wf-estimated'", estimated = 2)),
    )
    assertMalformedWorkListRow(
      featureTaskWorkflowRow(FeatureTaskWorkflowRow(workflowId = "'wf-started'", startedAt = "invalid")),
    )
    assertMalformedWorkListRow(
      featureTaskWorkflowRow(FeatureTaskWorkflowRow(workflowId = "'wf-since'", stateEnteredAt = "invalid")),
    )
    assertMalformedWorkListRow(
      goalIssueProgressRow(parentWorkflowId = " goal-parent "),
    )
    assertMalformedWorkListRow(
      goalIssueProgressRow(parentWorkflowId = "goal-state", status = " running "),
    )
  }

  private fun featureTaskWorkflowRow(row: FeatureTaskWorkflowRow): String =
    "INSERT INTO feature_task_workflows VALUES " +
      "('SKILL-117', '${row.mode}', ${row.workflowId}, '${row.startedAt}', " +
      "'${row.workflowStatus}', '${row.stateEnteredAt}', ${row.estimated})"

  private fun goalIssueProgressRow(
    parentWorkflowId: String,
    status: String = "running",
  ): String =
    "INSERT INTO goal_issue_progress VALUES " +
      "('SKILL-117', '$parentWorkflowId', '2026-05-01T12:00:00Z', '$status', '2026-05-01T12:00:00Z', 0)"

  private fun assertMalformedWorkListRow(insert: String) {
    DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
      createWorkListTables(connection)
      connection.createStatement().use { it.executeUpdate(insert) }

      assertEquals(emptyList(), SQLiteWorkListRepository(connection, NoopRuntimeDiagnostics).list().items)
    }
  }

  @Test
  fun `list retains healthy rows when an earlier row is malformed`() {
    DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
      createWorkListTables(connection)
      connection.createStatement().use {
        it.executeUpdate(
          goalIssueProgressRow(parentWorkflowId = " goal-parent "),
        )
        insertFeatureTask(connection, "wf-readable", "runtime", "2026-05-01T12:00:00Z")
      }

      val items = SQLiteWorkListRepository(connection, NoopRuntimeDiagnostics).list(limit = 10)

      assertEquals(listOf("wf-readable"), items.items.map { it.workflowId })
    }
  }

  private fun createWorkListTables(connection: Connection) {
    connection.createStatement().use { statement ->
      statement.executeUpdate(
        "CREATE TABLE feature_task_workflows (" +
          "issue_key TEXT, mode TEXT, workflow_id TEXT, started_at TEXT, workflow_status TEXT, " +
          "state_entered_at TEXT, state_entered_at_estimated INTEGER)",
      )
      statement.executeUpdate(
        "CREATE TABLE feature_verify_workflows (" +
          "issue_key TEXT, workflow_id TEXT, started_at TEXT, workflow_status TEXT, " +
          "state_entered_at TEXT, state_entered_at_estimated INTEGER)",
      )
      statement.executeUpdate(
        "CREATE TABLE goal_issue_progress (" +
          "issue_key TEXT, parent_workflow_id TEXT, first_started_at TEXT, status TEXT, " +
          "state_entered_at TEXT, state_entered_at_estimated INTEGER)",
      )
    }
  }

  private fun insertFeatureTask(
    connection: Connection,
    workflowId: String,
    mode: String,
    startedAt: String,
  ) {
    connection.prepareStatement(
      """
      INSERT INTO feature_task_workflows (
        workflow_id, mode, contract_version, workflow_status, issue_key, started_at, state_entered_at
      ) VALUES (?, ?, '1.0.0', 'running', 'SKILL-117', ?, ?)
      """.trimIndent(),
    ).use { statement ->
      statement.setString(1, workflowId)
      statement.setString(2, mode)
      statement.setString(3, startedAt)
      statement.setString(4, startedAt)
      statement.executeUpdate()
    }
  }

  private fun insertFeatureVerify(
    connection: Connection,
    workflowId: String,
    startedAt: String,
  ) {
    connection.prepareStatement(
      """
      INSERT INTO feature_verify_workflows (
        workflow_id, contract_version, workflow_status, issue_key, started_at, state_entered_at
      ) VALUES (?, '1.0.0', 'running', 'SKILL-117', ?, ?)
      """.trimIndent(),
    ).use { statement ->
      statement.setString(1, workflowId)
      statement.setString(2, startedAt)
      statement.setString(3, startedAt)
      statement.executeUpdate()
    }
  }

  private fun insertGoal(
    connection: Connection,
    workflowId: String,
    issueKey: String,
    startedAt: String,
  ) {
    connection.prepareStatement(
      """
      INSERT INTO goal_issue_progress (
        parent_workflow_id, issue_key, first_started_at, status, state_entered_at
      ) VALUES (?, ?, ?, 'running', ?)
      """.trimIndent(),
    ).use { statement ->
      statement.setString(1, workflowId)
      statement.setString(2, issueKey)
      statement.setString(3, startedAt)
      statement.setString(4, startedAt)
      statement.executeUpdate()
    }
  }

  private fun insertGoalChildRows(connection: Connection) {
    connection.createStatement().use { statement ->
      statement.executeUpdate(
        """
        INSERT INTO goal_run_sessions (workflow_id, issue_key, started_at, mode)
        VALUES ('goal-child-session', 'SKILL-117', '2026-05-02T12:00:00Z', 'runtime')
        """.trimIndent(),
      )
      statement.executeUpdate(
        """
        INSERT INTO goal_subtask_events (
          issue_key, workflow_id, subtask_id, status, started_at, finished_at, duration_ms, attempt_count
        ) VALUES ('SKILL-117', 'goal-child-session', 1, 'complete', '2026-05-02T12:00:00Z',
                  '2026-05-02T12:01:00Z', 60000, 1)
        """.trimIndent(),
      )
    }
  }

  private data class FeatureTaskWorkflowRow(
    val workflowId: String,
    val mode: String = "runtime",
    val startedAt: String = "2026-05-01T12:00:00Z",
    val workflowStatus: String = "running",
    val stateEnteredAt: String = "2026-05-01T12:00:00Z",
    val estimated: Int = 0,
  )
}
