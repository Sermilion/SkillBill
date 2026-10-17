package dev.skillbill.tray.core.data.store

import androidx.room3.Dao
import androidx.room3.RawQuery
import androidx.room3.RoomRawQuery
import dev.skillbill.tray.core.domain.model.WorkSection
import dev.skillbill.tray.core.domain.model.HistoryFilter
import skillbill.contracts.workflow.identity.task.FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.model.FeatureTaskRouteScope
import skillbill.idestatus.model.IdeStatusLaunchScope
import skillbill.idestatus.model.IdeStatusRepositoryIdentityFamily
import skillbill.idestatus.model.IdeStatusRepositoryIdentityPolicy
import skillbill.idestatus.model.IdeStatusRepositoryIdentitySource
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition

internal data class WorkCandidateRow(val id: String, val sort_time: String, val deadline: String?)
internal data class WorkCandidateQuery(val sql: String, val roomQuery: RoomRawQuery)

@Dao
internal interface WorkCandidateDao {
  @RawQuery
  suspend fun candidates(query: RoomRawQuery): List<WorkCandidateRow>
}

internal fun candidateQuery(section: WorkSection, cutoff: String, through: String, afterTime: String?, afterId: String?, filter: String, limit: Int, historyFilter: HistoryFilter = HistoryFilter.ALL): WorkCandidateQuery {
  val continuationParentPath = "$.${DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_GOAL_CONTINUATION.label()}" +
    ".${FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys.PARENT_WORKFLOW_ID}"
  val goalChildRouteScope = FeatureTaskRouteScope.GOAL_CHILD.wireValue
  val condition = when (section) {
    WorkSection.NEEDS_YOU -> "finished_at IS NULL"
    WorkSection.GOALS -> "family = 'goal' AND finished_at IS NULL"
    WorkSection.PHASES -> "family <> 'goal' AND finished_at IS NULL"
    WorkSection.HISTORY, WorkSection.RECENT -> "finished_at IS NOT NULL AND julianday(finished_at) >= julianday(?) AND julianday(finished_at) <= julianday(?)"
  }
  val ordering = if (section in setOf(WorkSection.HISTORY, WorkSection.RECENT)) "finished_at" else "started_at"
  val familyCondition = when (historyFilter) {
    HistoryFilter.ALL -> "1 = 1"
    HistoryFilter.GOALS -> "family = 'goal'"
    HistoryFilter.PHASES -> "family <> 'goal'"
  }
  val goalPhaseFilter = if (section in setOf(WorkSection.PHASES, WorkSection.NEEDS_YOU, WorkSection.GOALS)) """
        AND NOT EXISTS (
          SELECT 1 FROM standalone_phase_status phase JOIN goals goal ON goal.repository = phase.repository_identity
          WHERE candidate.family IN ('phase', 'goal') AND phase.invocation_id = candidate.id
            AND phase.phase_id IN ('${FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN}', '${FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN_SUBTASKS}')
            AND (goal.finished_at IS NULL OR julianday(candidate.started_at) <= julianday(goal.finished_at))
            AND goal.repository <> '' AND goal.repository = candidate.repository
            AND TRIM(candidate.issue_key) <> ''
            AND UPPER(TRIM(goal.issue_key)) = UPPER(TRIM(candidate.issue_key))
        )
  """.trimIndent() else ""
  val superseded = """
EXISTS (
        SELECT 1 FROM standalone_phase_status newer
        WHERE newer.repository_identity = s.repository_identity AND newer.phase_id = s.phase_id
          AND COALESCE(newer.monitor_pr_key, upper(newer.issue_key), newer.workflow_id, newer.branch_correlation) = COALESCE(s.monitor_pr_key, upper(s.issue_key), s.workflow_id, s.branch_correlation)
          AND (length(newer.run_sequence) > length(s.run_sequence)
            OR (length(newer.run_sequence) = length(s.run_sequence) AND newer.run_sequence > s.run_sequence)
            OR (newer.run_sequence = s.run_sequence AND newer.invocation_id > s.invocation_id))
      )
  """.trimIndent()
  val sql = """
    WITH observation(at) AS (SELECT ?), goals AS (
      SELECT g.parent_workflow_id AS id, g.first_started_at AS started_at,
             COALESCE(json_extract(c.control_state_json, '$.goal_completed_at'),
               CASE WHEN g.status IN ('completed', 'abandoned') THEN g.state_entered_at END) AS finished_at,
             COALESCE(g.status, 'running') AS state, 'goal' AS family,
             COALESCE(g.issue_key, '') AS issue_key,
             ${identityExpression(IdeStatusRepositoryIdentityFamily.GOAL, mapOf(
               IdeStatusRepositoryIdentitySource.CONTROL to "json_extract(c.control_state_json, '$.repository_identity')",
               IdeStatusRepositoryIdentitySource.WORKFLOW_REGISTRATION to "e.repository_identity",
               IdeStatusRepositoryIdentitySource.GOAL_CHILDREN to "(SELECT CASE WHEN COUNT(DISTINCT child_identity.repository_identity) = 1\n                 THEN MIN(child_identity.repository_identity) END\n                FROM feature_task_workflows child\n                JOIN feature_task_execution_identities child_identity ON child_identity.workflow_id = child.workflow_id\n                WHERE child_identity.route_scope = '$goalChildRouteScope'\n                  AND CASE WHEN json_valid(child.artifacts_json)\n                    THEN json_extract(child.artifacts_json, '$continuationParentPath') END = g.parent_workflow_id)"
             ))} AS repository,
             COALESCE(json_extract(c.control_state_json, '$.execution_lease.expires_at'), '') AS deadline,
             COALESCE(e.branch_correlation, '') AS branch
      FROM goal_issue_progress g LEFT JOIN goal_runner_controls c ON c.parent_workflow_id = g.parent_workflow_id
      LEFT JOIN ide_status_workflow_execution e ON e.execution_id = (
        SELECT x.execution_id FROM ide_status_workflow_execution x WHERE x.workflow_id = g.parent_workflow_id
        ORDER BY length(x.run_sequence) DESC, x.run_sequence DESC LIMIT 1)
    ), candidates AS (
      SELECT * FROM goals
      UNION ALL
      SELECT w.workflow_id, w.started_at,
             w.finished_at,
             w.workflow_status, 'task', COALESCE(w.issue_key, ''), COALESCE(${identityExpression(IdeStatusRepositoryIdentityFamily.TASK, mapOf(
               IdeStatusRepositoryIdentitySource.EXECUTION_IDENTITY to "i.repository_identity",
               IdeStatusRepositoryIdentitySource.WORKFLOW_REGISTRATION to "e.repository_identity",
               IdeStatusRepositoryIdentitySource.STANDALONE_REGISTRATION to "(SELECT s.repository_identity FROM standalone_phase_status s WHERE s.workflow_id = w.workflow_id\n                ORDER BY length(s.run_sequence) DESC, s.run_sequence DESC, s.invocation_id DESC LIMIT 1)"
             ))}, ''),
             l.expires_at, COALESCE(e.branch_correlation, '')
      FROM feature_task_workflows w LEFT JOIN feature_task_execution_identities i ON i.workflow_id = w.workflow_id
      LEFT JOIN feature_task_runtime_worker_leases l ON l.workflow_id = w.workflow_id
      LEFT JOIN ide_status_workflow_execution e ON e.execution_id = (
        SELECT x.execution_id FROM ide_status_workflow_execution x WHERE x.workflow_id = w.workflow_id
        ORDER BY length(x.run_sequence) DESC, x.run_sequence DESC LIMIT 1)
      WHERE COALESCE(i.route_scope, 'standalone') <> 'goal_child'
        AND NOT EXISTS (SELECT 1 FROM goal_issue_progress g WHERE g.parent_workflow_id = w.workflow_id)
        AND NOT EXISTS (SELECT 1 FROM standalone_phase_status s WHERE s.workflow_id = w.workflow_id)
      UNION ALL
      SELECT w.workflow_id, w.started_at,
             w.finished_at,
             w.workflow_status, 'verify', COALESCE(w.issue_key, ''), COALESCE(${identityExpression(IdeStatusRepositoryIdentityFamily.VERIFY, mapOf(
               IdeStatusRepositoryIdentitySource.WORKFLOW_REGISTRATION to "e.repository_identity",
               IdeStatusRepositoryIdentitySource.ISSUE_UNIQUE to """(SELECT CASE WHEN COUNT(DISTINCT issue_identity.repository_identity) = 1
                 THEN MIN(issue_identity.repository_identity) END
                FROM (
                  SELECT execution_identity.repository_identity
                  FROM feature_task_execution_identities execution_identity
                  WHERE execution_identity.normalized_issue_key = UPPER(TRIM(w.issue_key))
                  UNION
                  SELECT execution.repository_identity
                  FROM ide_status_workflow_execution execution
                  WHERE UPPER(TRIM(execution.issue_key)) = UPPER(TRIM(w.issue_key))
                  UNION
                  SELECT phase.repository_identity
                  FROM standalone_phase_status phase
                  WHERE UPPER(TRIM(phase.issue_key)) = UPPER(TRIM(w.issue_key))
                ) issue_identity
                WHERE issue_identity.repository_identity IS NOT NULL AND TRIM(issue_identity.repository_identity) <> '')"""
             ))}, ''),
             NULL, COALESCE(e.branch_correlation, '')
      FROM feature_verify_workflows w LEFT JOIN ide_status_workflow_execution e ON e.execution_id = (
        SELECT x.execution_id FROM ide_status_workflow_execution x WHERE x.workflow_id = w.workflow_id
        ORDER BY length(x.run_sequence) DESC, x.run_sequence DESC LIMIT 1)
      UNION ALL
      SELECT s.invocation_id, s.started_at, s.finished_at,
             s.lifecycle_state, CASE WHEN s.launch_scope = '${IdeStatusLaunchScope.GOAL.wireValue}' THEN 'goal' ELSE 'phase' END,
             COALESCE(s.issue_key, ''), s.repository_identity, s.lease_expires_at, s.branch_correlation
      FROM standalone_phase_status s
      WHERE NOT $superseded
    ), eligible AS (
      SELECT id, ${instantOrderingKey(ordering)} AS sort_time, NULLIF(deadline, '') AS deadline
      FROM candidates candidate
      WHERE $condition AND $familyCondition AND julianday(started_at) <= julianday(?)
        $goalPhaseFilter
        AND (? = '' OR instr(lower(repository || ' ' || issue_key || ' ' || branch), lower(?)) > 0)
    )
    SELECT id, sort_time, deadline FROM eligible
    WHERE (? IS NULL OR sort_time < ? OR (sort_time = ? AND id < ?))
    ORDER BY sort_time DESC, id DESC LIMIT ?
  """.trimIndent()
  val query = RoomRawQuery(sql) { statement ->
    var index = 1
    statement.bindText(index++, through)
    if (section in setOf(WorkSection.HISTORY, WorkSection.RECENT)) {
      statement.bindText(index++, cutoff)
      statement.bindText(index++, through)
    }
    statement.bindText(index++, through)
    statement.bindText(index++, filter)
    statement.bindText(index++, filter)
    repeat(3) { if (afterTime == null) statement.bindNull(index++) else statement.bindText(index++, afterTime) }
    if (afterId == null) statement.bindNull(index++) else statement.bindText(index++, afterId)
    statement.bindLong(index, limit.toLong())
  }
  return WorkCandidateQuery(sql, query)
}

private fun identityExpression(
  family: IdeStatusRepositoryIdentityFamily,
  expressions: Map<IdeStatusRepositoryIdentitySource, String>,
): String = IdeStatusRepositoryIdentityPolicy.precedence(family).mapNotNull(expressions::get)
  .joinToString(prefix = "COALESCE(", postfix = ")")

private fun instantOrderingKey(column: String): String =
  "substr($column, 1, 19) || '.' || CASE WHEN substr($column, 20, 1) = '.' THEN " +
    "substr(substr($column, 21, instr(substr($column, 21), 'Z') - 1) || '000000000', 1, 9) " +
    "ELSE '000000000' END"
