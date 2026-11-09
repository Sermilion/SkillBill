package skillbill.infrastructure.sqlite.workflow.retained

import me.tatarka.inject.annotations.Inject
import skillbill.error.core.DatabaseAccessOperation
import skillbill.infrastructure.sqlite.DatabaseRuntime
import skillbill.infrastructure.sqlite.core.schema.databaseAccessError
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.db.RetainedWorkflowReader
import skillbill.ports.db.model.RetainedWorkflowRecord
import skillbill.ports.db.model.RetainedWorkflowSource
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.work.model.WorkItemKind
import skillbill.workflow.model.FeatureTaskRouteScope
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException

@Inject
class SQLiteRetainedWorkflowReader(
  private val database: DatabaseSessionFactory,
  private val diagnostics: RuntimeDiagnostics,
) : RetainedWorkflowReader {
  override fun read(workflowId: String?): List<RetainedWorkflowRecord> {
    if (!database.databaseExists()) return emptyList()
    val path = database.resolveDbPath()
    return try {
      DatabaseRuntime.openReadConnectionAt(path).use { opened ->
        readSources(opened.connection, workflowId, path.toString())
      }
    } catch (failure: SQLException) {
      throw databaseAccessError(path, DatabaseAccessOperation.READ, failure)
    }
  }

  private fun readSources(
    connection: Connection,
    workflowId: String?,
    path: String,
  ): List<RetainedWorkflowRecord> {
    return connection.prepareStatement(sourceQuery(connection, workflowId)).use { statement ->
      if (workflowId != null) statement.setString(1, workflowId)
      statement.executeQuery().use { rows ->
        buildList {
          while (rows.next()) add(readSource(rows, path))
        }
      }
    }
  }

  private fun sourceQuery(
    connection: Connection,
    workflowId: String?,
  ): String {
    val identityAvailable = identityAvailable(connection)
    val identityColumn = if (identityAvailable) "i.repository_identity" else "NULL"
    val identityJoin =
      if (identityAvailable) {
        "LEFT JOIN feature_task_execution_identities i ON i.workflow_id = w.workflow_id "
      } else {
        ""
      }
    val registrationIdentity =
      optionalExpression(
        connection,
        "ide_status_workflow_execution",
        "(SELECT e.repository_identity FROM ide_status_workflow_execution e " +
          "WHERE e.workflow_id = w.workflow_id ORDER BY e.started_at DESC, e.execution_id DESC LIMIT 1)",
      )
    val registrationBranch =
      optionalExpression(
        connection,
        "ide_status_workflow_execution",
        "(SELECT e.branch_correlation FROM ide_status_workflow_execution e " +
          "WHERE e.workflow_id = w.workflow_id ORDER BY e.started_at DESC, e.execution_id DESC LIMIT 1)",
      )
    val standaloneIdentity =
      optionalExpression(
        connection,
        "standalone_phase_status",
        "(SELECT s.repository_identity FROM standalone_phase_status s WHERE s.workflow_id = w.workflow_id " +
          "ORDER BY s.updated_at DESC, s.run_sequence DESC LIMIT 1)",
      )
    val controlIdentity =
      optionalExpression(
        connection,
        "goal_runner_controls",
        "(SELECT CASE WHEN json_valid(c.control_state_json) " +
          "AND json_type(c.control_state_json, '$.repository_identity') = 'text' " +
          "THEN json_extract(c.control_state_json, '$.repository_identity') END " +
          "FROM goal_runner_controls c WHERE c.parent_workflow_id = w.workflow_id)",
      )
    val goalChildrenIdentity =
      optionalExpression(
        connection,
        "feature_task_execution_identities",
        "(SELECT CASE WHEN COUNT(DISTINCT i.repository_identity) = 1 THEN MIN(i.repository_identity) END " +
          "FROM feature_task_workflows c " +
          "JOIN feature_task_execution_identities i ON i.workflow_id = c.workflow_id " +
          "WHERE c.parent_workflow_id = w.workflow_id " +
          "AND i.route_scope = '${FeatureTaskRouteScope.GOAL_CHILD.wireValue}')",
      )
    val workKind =
      optionalExpression(
        connection,
        "goal_issue_progress",
        "CASE WHEN EXISTS (SELECT 1 FROM goal_issue_progress g WHERE g.parent_workflow_id = w.workflow_id) " +
          "THEN '${WorkItemKind.FEATURE_GOAL.wireValue}' " +
          "ELSE '${WorkItemKind.FEATURE_TASK_RUNTIME.wireValue}' END",
      ).let { if (it == "NULL") "'${WorkItemKind.FEATURE_TASK_RUNTIME.wireValue}'" else it }
    val selected = if (workflowId == null) "" else "AND w.workflow_id = ?"
    return "SELECT w.workflow_id, w.workflow_name, w.issue_key, " +
      "$identityColumn AS execution_identity, w.current_step_id, " +
      "w.updated_at, w.artifacts_json, w.steps_json, " +
      "$workKind AS work_kind, " +
      "$registrationIdentity AS registration_identity, $standaloneIdentity AS standalone_identity, " +
      "$controlIdentity AS control_identity, $goalChildrenIdentity AS children_identity, " +
      "$registrationBranch AS registration_branch " +
      "FROM feature_task_workflows w " + identityJoin + "WHERE w.mode = 'runtime' $selected ORDER BY w.workflow_id"
  }

  private fun identityAvailable(connection: Connection): Boolean {
    val available =
      connection.createStatement().use { statement ->
        statement.executeQuery(
          "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'feature_task_execution_identities'",
        ).use { it.next() }
      }
    if (!available) {
      diagnostics.warning(
        "seam=retained_workflow_read identity_source=workflow_only " +
          "cause=identity_table_absent value_used=original_workflow",
      )
    }
    return available
  }

  private fun readSource(
    rows: ResultSet,
    path: String,
  ): RetainedWorkflowRecord {
    val id = rows.getString("workflow_id")
    return RetainedWorkflowRecord(
      id,
      rows.getString("workflow_name"),
      rows.getString("issue_key"),
      rows.getString("execution_identity"),
      rows.getString("current_step_id"),
      rows.getString("updated_at"),
      RetainedWorkflowSource(
        "$path#feature_task_workflows/$id/artifacts_json",
        rows.getString("artifacts_json"),
        rows.getString("steps_json"),
      ),
      workKind =
        rows.getString("work_kind")?.let { kind ->
          when (kind) {
            WorkItemKind.FEATURE_GOAL.wireValue -> WorkItemKind.FEATURE_GOAL
            else -> WorkItemKind.FEATURE_TASK_RUNTIME
          }
        },
      workflowRegistrationRepositoryIdentity = rows.getString("registration_identity"),
      standaloneRegistrationRepositoryIdentity = rows.getString("standalone_identity"),
      controlRepositoryIdentity = rows.getString("control_identity"),
      goalChildrenRepositoryIdentity = rows.getString("children_identity"),
      executionRepositoryIdentity = rows.getString("execution_identity"),
      workflowBranchCorrelation = rows.getString("registration_branch"),
    )
  }

  private fun optionalExpression(
    connection: Connection,
    table: String,
    expression: String,
  ): String =
    if (connection.createStatement().use { statement ->
        statement.executeQuery(
          "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = '$table'",
        ).use { it.next() }
      }
    ) {
      expression
    } else {
      "NULL"
    }
}
