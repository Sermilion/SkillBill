package skillbill.infrastructure.sqlite.telemetry

import skillbill.contracts.telemetry.LifecycleTelemetryPayloadKeys
import skillbill.infrastructure.sqlite.core.ops.bindAll
import skillbill.infrastructure.sqlite.telemetry.goal.GoalIssueIdentity
import skillbill.infrastructure.sqlite.telemetry.goal.emitGoalIssueFinished
import skillbill.infrastructure.sqlite.telemetry.goal.nextGoalStateEnteredAtSql
import skillbill.infrastructure.sqlite.telemetry.lifecycle.emitFeatureTaskRuntimeFinished
import skillbill.infrastructure.sqlite.telemetry.lifecycle.emitFeatureVerifyFinished
import skillbill.infrastructure.sqlite.telemetry.lifecycle.emitQualityCheckFinished
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.telemetry.model.TelemetryReconciliationRequest
import skillbill.ports.telemetry.model.TelemetryReconciliationResult
import java.sql.Connection
import java.time.temporal.ChronoUnit

internal const val STALE_SESSION_THRESHOLD_SECONDS: Long = 28_800L
internal const val STALE_GOAL_ISSUE_ABANDONMENT_DAYS: Long = 14L
internal const val TELEMETRY_RECONCILIATION_CADENCE_SECONDS: Long = 300L
internal const val TELEMETRY_RECONCILIATION_MAXIMUM_BATCH_SIZE: Int = 100

internal data class StaleSessionReconciliationPolicy(
  val sessionThresholdSeconds: Long = STALE_SESSION_THRESHOLD_SECONDS,
  val goalIssueAbandonmentDays: Long = STALE_GOAL_ISSUE_ABANDONMENT_DAYS,
)

private data class LifecycleReconciliationTarget(
  internal val family: String,
  internal val tableName: String,
  internal val terminalColumn: String,
  internal val terminalValue: String,
  internal val workflowTableName: String? = null,
  internal val emitFinished: (Connection, String, String, String, RuntimeDiagnostics) -> Unit,
)

internal data class ReconciliationCandidate(
  internal val family: String,
  internal val primaryIdentity: String,
  internal val secondaryIdentity: String?,
)

private val lifecycleTargets =
  listOf(
    LifecycleReconciliationTarget(
      family = "feature_task_runtime",
      tableName = "feature_task_runtime_sessions",
      terminalColumn = "completion_status",
      terminalValue = "stale",
      workflowTableName = "feature_task_workflows",
    ) { connection, runtimeVersion, sessionId, level, diagnostics ->
      emitFeatureTaskRuntimeFinished(connection, runtimeVersion, sessionId, level, diagnostics)
    },
    LifecycleReconciliationTarget(
      family = "feature_verify",
      tableName = "feature_verify_sessions",
      terminalColumn = "completion_status",
      terminalValue = "stale",
      workflowTableName = "feature_verify_workflows",
    ) { connection, runtimeVersion, sessionId, level, diagnostics ->
      emitFeatureVerifyFinished(connection, runtimeVersion, sessionId, level, diagnostics)
    },
    LifecycleReconciliationTarget(
      family = "quality_check",
      tableName = "quality_check_sessions",
      terminalColumn = "result",
      terminalValue = "stale",
    ) { connection, runtimeVersion, sessionId, level, diagnostics ->
      emitQualityCheckFinished(connection, runtimeVersion, sessionId, level, diagnostics)
    },
  )

internal fun reconcileStaleTelemetrySessions(
  connection: Connection,
  request: TelemetryReconciliationRequest,
  diagnostics: RuntimeDiagnostics,
  runtimeVersion: String = "test-runtime-version",
): TelemetryReconciliationResult {
  if (!claimReconciliationCadence(connection, request)) {
    return TelemetryReconciliationResult.Empty.copy(skippedByCadence = true)
  }
  val candidates = reconciliationCandidates(connection, request)
  val counts = mutableMapOf<String, Int>()
  candidates.forEach { candidate ->
    val emitted =
      if (candidate.family == GOAL_ISSUE_FAMILY) {
        val issueKey = requireNotNull(candidate.secondaryIdentity)
        val goal = GoalIssueIdentity(candidate.primaryIdentity, issueKey)
        markGoalIssueAbandoned(connection, goal) &&
          emitGoalIssueFinished(connection, runtimeVersion, goal, request.level, diagnostics).let { true }
      } else {
        val target = requireNotNull(lifecycleTargets.firstOrNull { it.family == candidate.family })
        markLifecycleSessionStale(connection, target, candidate.primaryIdentity).also { marked ->
          if (marked) {
            target.emitFinished(connection, runtimeVersion, candidate.primaryIdentity, request.level, diagnostics)
          }
        }
      }
    if (emitted) counts[candidate.family] = counts.getOrDefault(candidate.family, 0) + 1
  }
  val processed = counts.values.sum()
  return TelemetryReconciliationResult(
    featureImplementSessions = counts.getOrDefault("feature_implement", 0),
    featureTaskRuntimeSessions = counts.getOrDefault("feature_task_runtime", 0),
    featureVerifySessions = counts.getOrDefault("feature_verify", 0),
    qualityCheckSessions = counts.getOrDefault("quality_check", 0),
    goalIssueAbandonedSessions = counts.getOrDefault(GOAL_ISSUE_FAMILY, 0),
    emittedTerminalEvents = processed,
    processedCandidates = processed,
  )
}

internal fun reconcileStaleFeatureTaskRuntimeSessions(
  connection: Connection,
  diagnostics: RuntimeDiagnostics,
  runtimeVersion: String = "test-runtime-version",
  thresholdSeconds: Long = STALE_SESSION_THRESHOLD_SECONDS,
): Int {
  val target = lifecycleTargets[0]
  return staleSessionIds(connection, target, thresholdSeconds).count { sessionId ->
    markLifecycleSessionStale(connection, target, sessionId).also { marked ->
      if (marked) target.emitFinished(connection, runtimeVersion, sessionId, "anonymous", diagnostics)
    }
  }
}

private fun claimReconciliationCadence(
  connection: Connection,
  request: TelemetryReconciliationRequest,
): Boolean {
  val completedAt = request.now.toString()
  val eligibleBefore = request.now.minus(request.cadenceSeconds, ChronoUnit.SECONDS).toString()
  return connection.prepareStatement(
    """
    INSERT INTO telemetry_reconciliation_state (state_key, last_completed_at)
    VALUES (?, ?)
    ON CONFLICT(state_key) DO UPDATE SET last_completed_at = excluded.last_completed_at
    WHERE datetime(telemetry_reconciliation_state.last_completed_at) <= datetime(?)
    """.trimIndent(),
  ).use { statement ->
    statement.bindAll(RECONCILIATION_STATE_KEY, completedAt, eligibleBefore)
    statement.executeUpdate() > 0
  }
}

private fun staleSessionIds(
  connection: Connection,
  target: LifecycleReconciliationTarget,
  thresholdSeconds: Long,
): List<String> {
  val workflowActivityGuard =
    target.workflowTableName?.let { workflowTableName ->
      """
      AND NOT EXISTS (
        SELECT 1 FROM $workflowTableName workflow
        WHERE workflow.session_id = ${target.tableName}.session_id
          AND workflow.workflow_status NOT IN ('completed', 'failed', 'abandoned')
          AND datetime(workflow.updated_at) > datetime('now', '-' || ? || ' seconds')
      )
      """.trimIndent()
    }.orEmpty()
  val parameters =
    if (target.workflowTableName == null) {
      listOf(thresholdSeconds)
    } else {
      listOf(thresholdSeconds, thresholdSeconds)
    }
  return connection.prepareStatement(
    """
    SELECT session_id FROM ${target.tableName}
    WHERE finished_at IS NULL AND finished_event_emitted_at IS NULL
      AND started_at <= datetime('now', '-' || ? || ' seconds')
      $workflowActivityGuard
    ORDER BY started_at, session_id
    """.trimIndent(),
  ).use { statement ->
    statement.bindAll(parameters)
    statement.executeQuery().use { resultSet ->
      buildList {
        while (resultSet.next()) {
          add(resultSet.getString(LifecycleTelemetryPayloadKeys.SESSION_ID))
        }
      }
    }
  }
}

private fun markLifecycleSessionStale(
  connection: Connection,
  target: LifecycleReconciliationTarget,
  sessionId: String,
): Boolean =
  connection.prepareStatement(
    """
    UPDATE ${target.tableName}
    SET ${target.terminalColumn} = ?, stale_reason = ?, finished_at = CURRENT_TIMESTAMP
    WHERE session_id = ? AND finished_at IS NULL AND finished_event_emitted_at IS NULL
    """.trimIndent(),
  ).use { statement ->
    statement.bindAll(target.terminalValue, LifecycleStaleReason.NO_TERMINAL_BEFORE_THRESHOLD.wireValue, sessionId)
    statement.executeUpdate() > 0
  }

internal enum class LifecycleStaleReason(val wireValue: String) {
  NO_TERMINAL_BEFORE_THRESHOLD("no_terminal_before_threshold"),
}

private fun markGoalIssueAbandoned(
  connection: Connection,
  goal: GoalIssueIdentity,
): Boolean =
  connection.prepareStatement(
    """
    UPDATE goal_issue_progress
    SET status = 'abandoned',
        subtasks_complete = COALESCE((${latestSegmentCountSelect("subtasks_complete")}), 0),
        subtasks_blocked = COALESCE((${latestSegmentCountSelect("subtasks_blocked")}), 0),
        subtasks_skipped = COALESCE((${latestSegmentCountSelect("subtasks_skipped")}), 0),
        finished_at = strftime('%Y-%m-%dT%H:%M:%SZ', 'now'),
        state_entered_at = ${nextGoalStateEnteredAtSql("strftime('%Y-%m-%dT%H:%M:%fZ', 'now')")},
        state_entered_at_estimated = 0
    WHERE parent_workflow_id = ? AND issue_key = ?
      AND finished_at IS NULL AND finished_event_emitted_at IS NULL
      AND latest_segment_workflow_id = last_blocked_segment_workflow_id
      AND COALESCE(status, '') != 'abandoned'
    """.trimIndent(),
  ).use { statement ->
    statement.bindAll(goal.parentWorkflowId, goal.issueKey)
    statement.executeUpdate() > 0
  }

private fun latestSegmentCountSelect(columnName: String): String =
  "SELECT segment.$columnName FROM goal_run_sessions segment " +
    "WHERE segment.workflow_id = goal_issue_progress.latest_segment_workflow_id " +
    "AND segment.$columnName IS NOT NULL LIMIT 1"

private const val GOAL_ISSUE_FAMILY = "goal_issue"
private const val RECONCILIATION_STATE_KEY = "stale_session_reconciliation"
