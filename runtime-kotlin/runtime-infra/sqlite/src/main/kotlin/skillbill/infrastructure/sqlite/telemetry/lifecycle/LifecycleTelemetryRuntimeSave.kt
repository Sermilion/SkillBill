package skillbill.infrastructure.sqlite.telemetry.lifecycle

import skillbill.contracts.JsonCodec
import skillbill.contracts.telemetry.TelemetryMeasurementAvailability
import skillbill.infrastructure.sqlite.core.ops.bindAll
import skillbill.telemetry.model.FeatureTaskRuntimeFinishedRecord
import skillbill.telemetry.model.FeatureTaskRuntimeStartedRecord
import java.sql.Connection
import java.sql.PreparedStatement

internal fun saveFeatureTaskRuntimeStarted(
  connection: Connection,
  record: FeatureTaskRuntimeStartedRecord,
) {
  if (rowExists(connection, "feature_task_runtime_sessions", record.sessionId)) {
    updateFeatureTaskRuntimeStarted(connection, record)
    return
  }
  connection.prepareStatement(
    """
    INSERT INTO feature_task_runtime_sessions (
      session_id, feature_size, issue_key, feature_name,
      workflow_id, goal_parent_workflow_id, goal_subtask_id
    ) VALUES (?, ?, ?, ?, ?, ?, ?)
    """.trimIndent(),
  ).use { statement ->
    statement.bindAll(
      record.sessionId,
      record.featureSize,
      record.issueKey,
      record.featureName,
      record.workflowId,
      record.goalParentWorkflowId,
      record.goalSubtaskId,
    )
    statement.executeUpdate()
  }
}

private fun updateFeatureTaskRuntimeStarted(
  connection: Connection,
  record: FeatureTaskRuntimeStartedRecord,
) {
  connection.prepareStatement(
    """
    UPDATE feature_task_runtime_sessions SET
      feature_size = ?,
      issue_key = ?,
      feature_name = ?,
      workflow_id = ?,
      goal_parent_workflow_id = ?,
      goal_subtask_id = ?
    WHERE session_id = ?
    """.trimIndent(),
  ).use { statement ->
    statement.bindAll(
      record.featureSize,
      record.issueKey,
      record.featureName,
      record.workflowId,
      record.goalParentWorkflowId,
      record.goalSubtaskId,
      record.sessionId,
    )
    statement.executeUpdate()
  }
}

internal fun saveFeatureTaskRuntimeFinished(
  connection: Connection,
  record: FeatureTaskRuntimeFinishedRecord,
): TerminalSaveOutcome {
  val completedPhaseIdsJson = listJson(record.completedPhaseIds)
  val phaseOutcomesJson = JsonCodec.mapToJsonString(record.phaseOutcomes)
  if (rowExists(connection, "feature_task_runtime_sessions", record.sessionId)) {
    if (lifecycleAlreadyFinished(connection, "feature_task_runtime_sessions", record.sessionId)) {
      incrementDuplicateTerminalFinishedEvents(connection, "feature_task_runtime_sessions", record.sessionId)
      return TerminalSaveOutcome.DUPLICATE
    }
    updateFeatureTaskRuntimeFinished(connection, record, completedPhaseIdsJson, phaseOutcomesJson)
  } else {
    insertFeatureTaskRuntimeFinished(connection, record, completedPhaseIdsJson, phaseOutcomesJson)
  }
  return TerminalSaveOutcome.FIRST_TERMINAL
}

private fun updateFeatureTaskRuntimeFinished(
  connection: Connection,
  record: FeatureTaskRuntimeFinishedRecord,
  completedPhaseIdsJson: String,
  phaseOutcomesJson: String,
) {
  connection.prepareStatement(
    """
    UPDATE feature_task_runtime_sessions SET
      completion_status = ?,
      completed_phase_ids = ?,
      phase_outcomes = ?,
      last_incomplete_phase = ?,
      blocked_reason = ?,
      resolved_branch = ?,
      review_fix_iteration_count = ?,
      regeneration_activation_count = ?,
      regeneration_attempt_count = ?,
      regeneration_outcome_counts_json = ?,
      crash_reconciliation_count = ?,
      crash_reconciliation_reason_counts_json = ?,
      estimated_phase_tokens_json = ?,
      estimated_total_tokens = ?,
      finding_verification_verified_count = ?,
      finding_verification_rejected_count = ?,
      review_fix_cap_exhausted = ?,
      review_fix_cap_exhausted_availability = ?,
      audit_gap_iteration_count = ?,
      audit_gap_availability = ?,
      resolved_agent_ids = ?,
      launched_models = ?,
      phase_strategies = ?,
      phase_strategy_availability = ?,
      finished_at = CURRENT_TIMESTAMP
    WHERE session_id = ?
      AND (finished_event_emitted_at IS NULL OR completion_status = 'stale')
    """.trimIndent(),
  ).use { statement ->
    bindFeatureTaskRuntimeFinishedUpdate(statement, record, completedPhaseIdsJson, phaseOutcomesJson)
    statement.executeUpdate()
  }
}

private fun bindFeatureTaskRuntimeFinishedUpdate(
  statement: PreparedStatement,
  record: FeatureTaskRuntimeFinishedRecord,
  completedPhaseIdsJson: String,
  phaseOutcomesJson: String,
) {
  statement.bindAll(
    record.completionStatus,
    completedPhaseIdsJson,
    phaseOutcomesJson,
    record.lastIncompletePhase,
    record.blockedReason,
    record.resolvedBranch,
    record.reviewFixIterationCount,
    record.regenerationActivationCount,
    record.regenerationAttemptCount,
    regenerationOutcomeCountsJson(record),
    record.crashReconciliationCount,
    crashReconciliationReasonCountsJson(record),
    record.estimatedPhaseTokenBreakdownJson,
    record.estimatedTotalTokens,
    record.findingVerificationVerifiedCount,
    record.findingVerificationRejectedCount,
    record.reviewFixCapExhausted.toSqlInt(),
    record.reviewFixCapExhausted.availabilityWire(),
    record.auditGapIterationCount,
    record.auditGapIterationCount.availabilityWire(),
    record.resolvedAgentIds.namesJson(),
    record.launchedModels.namesJson(),
    record.phaseStrategies,
    record.phaseStrategyAvailability,
    record.sessionId,
  )
}

internal fun List<String>?.namesJson(): String? = this?.takeIf { it.isNotEmpty() }?.let(::listJson)

private fun Any?.availabilityWire(): String =
  if (this == null) {
    TelemetryMeasurementAvailability.UNAVAILABLE_NO_DURABLE_STATE.wireValue
  } else {
    TelemetryMeasurementAvailability.MEASURED.wireValue
  }

private fun regenerationOutcomeCountsJson(record: FeatureTaskRuntimeFinishedRecord): String? =
  record.regenerationOutcomeCounts.takeIf { it.isNotEmpty() }?.let { JsonCodec.mapToJsonString(it) }

private fun crashReconciliationReasonCountsJson(record: FeatureTaskRuntimeFinishedRecord): String? =
  record.crashReconciliationReasonCounts.takeIf { it.isNotEmpty() }?.let { JsonCodec.mapToJsonString(it) }

private fun insertFeatureTaskRuntimeFinished(
  connection: Connection,
  record: FeatureTaskRuntimeFinishedRecord,
  completedPhaseIdsJson: String,
  phaseOutcomesJson: String,
) {
  connection.prepareStatement(
    """
    INSERT INTO feature_task_runtime_sessions (
      session_id, completion_status, completed_phase_ids,
      phase_outcomes, last_incomplete_phase, blocked_reason,
      resolved_branch, review_fix_iteration_count,
      regeneration_activation_count, regeneration_attempt_count, regeneration_outcome_counts_json,
      crash_reconciliation_count, crash_reconciliation_reason_counts_json,
      estimated_phase_tokens_json, estimated_total_tokens,
      finding_verification_verified_count, finding_verification_rejected_count,
      review_fix_cap_exhausted, review_fix_cap_exhausted_availability,
      audit_gap_iteration_count, audit_gap_availability,
      resolved_agent_ids, launched_models, phase_strategies, phase_strategy_availability, finished_at
    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
    """.trimIndent(),
  ).use { statement ->
    statement.bindAll(
      record.sessionId,
      record.completionStatus,
      completedPhaseIdsJson,
      phaseOutcomesJson,
      record.lastIncompletePhase,
      record.blockedReason,
      record.resolvedBranch,
      record.reviewFixIterationCount,
      record.regenerationActivationCount,
      record.regenerationAttemptCount,
      regenerationOutcomeCountsJson(record),
      record.crashReconciliationCount,
      crashReconciliationReasonCountsJson(record),
      record.estimatedPhaseTokenBreakdownJson,
      record.estimatedTotalTokens,
      record.findingVerificationVerifiedCount,
      record.findingVerificationRejectedCount,
      record.reviewFixCapExhausted.toSqlInt(),
      record.reviewFixCapExhausted.availabilityWire(),
      record.auditGapIterationCount,
      record.auditGapIterationCount.availabilityWire(),
      record.resolvedAgentIds.namesJson(),
      record.launchedModels.namesJson(),
      record.phaseStrategies,
      record.phaseStrategyAvailability,
    )
    statement.executeUpdate()
  }
}
