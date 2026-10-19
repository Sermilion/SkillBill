package skillbill.infrastructure.sqlite.telemetry.lifecycle.session

import skillbill.infrastructure.sqlite.core.ops.bindAll
import skillbill.infrastructure.sqlite.telemetry.goal.GoalFinishedSaveOutcome
import skillbill.infrastructure.sqlite.telemetry.goal.GoalIssueIdentity
import skillbill.infrastructure.sqlite.telemetry.goal.GoalIssueSegmentStart
import skillbill.infrastructure.sqlite.telemetry.goal.GoalStartedSaveOutcome
import skillbill.infrastructure.sqlite.telemetry.goal.emitGoalFinished
import skillbill.infrastructure.sqlite.telemetry.goal.emitGoalIssueFinished
import skillbill.infrastructure.sqlite.telemetry.goal.emitGoalStarted
import skillbill.infrastructure.sqlite.telemetry.goal.emitGoalSubtaskFinished
import skillbill.infrastructure.sqlite.telemetry.goal.recordGoalIssueSegmentEnd
import skillbill.infrastructure.sqlite.telemetry.goal.recordGoalIssueSegmentStarted
import skillbill.infrastructure.sqlite.telemetry.goal.saveGoalFinished
import skillbill.infrastructure.sqlite.telemetry.goal.saveGoalIssueFinished
import skillbill.infrastructure.sqlite.telemetry.goal.saveGoalStarted
import skillbill.infrastructure.sqlite.telemetry.goal.saveGoalSubtaskFinished
import skillbill.infrastructure.sqlite.telemetry.goal.supersedeEligibleSegments
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.telemetry.lifecycle.GoalChildSubtaskIdentity
import skillbill.ports.telemetry.lifecycle.GoalLifecycleTelemetryRepository
import skillbill.ports.telemetry.lifecycle.GoalStartedCandidate
import skillbill.ports.telemetry.lifecycle.GoalStartedOwnershipEvidence
import skillbill.ports.telemetry.lifecycle.GoalStartedPreparation
import skillbill.ports.telemetry.lifecycle.GoalStartedSupersession
import skillbill.telemetry.model.GoalFinishedRecord
import skillbill.telemetry.model.GoalIssueFinishedRecord
import skillbill.telemetry.model.GoalStartedRecord
import skillbill.telemetry.model.GoalSubtaskFinishedRecord
import java.sql.Connection
import java.time.Instant

internal class LifecycleTelemetryGoalSessionAdapter(
  private val connection: Connection,
  private val runtimeVersion: String,
  private val diagnostics: RuntimeDiagnostics,
) : GoalLifecycleTelemetryRepository {
  override fun cancelGoalIssue(
    parentWorkflowId: String,
    issueKey: String,
    canceledAt: Instant,
  ): Boolean {
    val changed =
      connection.prepareStatement(
        "UPDATE goal_issue_progress SET status = 'abandoned', finished_at = ?, state_entered_at = ?, " +
          "state_entered_at_estimated = 0 WHERE parent_workflow_id = ? AND issue_key = ? " +
          "AND COALESCE(status, '') NOT IN ('completed', 'abandoned')",
      ).use { statement ->
        statement.bindAll(canceledAt.toString(), canceledAt.toString(), parentWorkflowId, issueKey)
        statement.executeUpdate() == 1
      }
    diagnostics.info(
      "seam=goal-cancellation outcome=${if (changed) "canceled" else "refused"} workflow=$parentWorkflowId",
    )
    return changed
  }

  override fun restartCanceledGoalIssue(
    parentWorkflowId: String,
    issueKey: String,
    restartedAt: Instant,
  ): Boolean {
    val changed =
      connection.prepareStatement(
        "UPDATE goal_issue_progress SET status = 'running', finished_at = NULL, state_entered_at = ?, " +
          "state_entered_at_estimated = 0 WHERE parent_workflow_id = ? AND issue_key = ? AND status = 'abandoned'",
      ).use { statement ->
        statement.bindAll(restartedAt.toString(), parentWorkflowId, issueKey)
        statement.executeUpdate() == 1
      }
    diagnostics.info(
      "seam=goal-cancellation outcome=${if (changed) "restarted" else "refused"} workflow=$parentWorkflowId",
    )
    return changed
  }

  override fun prepareGoalStarted(record: GoalStartedRecord): GoalStartedPreparation {
    val inserted = saveGoalStarted(connection, record) == GoalStartedSaveOutcome.INSERTED
    val snapshot = if (inserted) sameKeyUnfinishedGoalSegments(record) else emptyList()
    val startedAt = Instant.parse(record.startedAt)
    val candidates = snapshot.mapNotNull { (evidence, started, mode) ->
      if (evidence.status != null || evidence.finishedAt != null || !Instant.parse(started).isBefore(startedAt)) null
      else GoalStartedCandidate(
        workflowId = evidence.workflowId,
        parentWorkflowId = evidence.parentWorkflowId,
        startedAt = started,
        latestSegmentParentWorkflowId = evidence.latestSegmentParentWorkflowId,
        mode = mode,
      )
    }.distinctBy { it.workflowId to it.parentWorkflowId to it.latestSegmentParentWorkflowId }
    return GoalStartedPreparation(inserted, candidates, snapshot.map { it.first }.distinct())
  }

  override fun goalStarted(record: GoalStartedRecord, level: String) {
    val prepared = prepareGoalStarted(record)
    val evidenceByWorkflow = prepared.ownershipEvidence.groupBy { it.workflowId }
    val sameParent = prepared.candidates.groupBy { it.workflowId }.mapNotNull { (workflowId, joinedCandidates) ->
      val owners = evidenceByWorkflow[workflowId].orEmpty().flatMap {
        listOfNotNull(it.parentWorkflowId, it.latestSegmentParentWorkflowId)
      }.distinct()
      if (record.parentWorkflowId.isNullOrBlank() || owners != listOf(record.parentWorkflowId)) return@mapNotNull null
      val candidate = joinedCandidates.first().copy(
        parentWorkflowId = evidenceByWorkflow[workflowId].orEmpty().firstNotNullOfOrNull { it.parentWorkflowId }
          ?: record.parentWorkflowId,
        latestSegmentParentWorkflowId = evidenceByWorkflow[workflowId].orEmpty()
          .none { it.parentWorkflowId == record.parentWorkflowId }.takeIf { it }?.let { record.parentWorkflowId },
      )
      GoalStartedSupersession(candidate, true, 0, 0, 0)
    }
    reconcileGoalStarted(record, prepared.inserted, sameParent, level)
  }

  override fun persistedChildSubtaskStatuses(
    issueKey: String,
    children: List<GoalChildSubtaskIdentity>,
  ): Map<Int, String> = children.mapNotNull { child ->
    connection.prepareStatement(
      """
      SELECT status FROM goal_subtask_events
      WHERE UPPER(TRIM(issue_key)) = UPPER(TRIM(?)) AND subtask_id = ? AND workflow_id = ?
      ORDER BY finished_at DESC LIMIT 1
      """.trimIndent(),
    ).use { statement ->
      statement.bindAll(issueKey, child.subtaskId, child.childWorkflowId)
      statement.executeQuery().use { rows ->
        if (rows.next()) child.subtaskId to rows.getString("status") else null
      }
    }
  }.toMap()

  override fun reconcileGoalStarted(
    record: GoalStartedRecord,
    inserted: Boolean,
    candidates: List<GoalStartedSupersession>,
    level: String,
  ) {
    if (inserted) {
      record.parentWorkflowId?.takeIf(String::isNotBlank)?.let { parentWorkflowId ->
        val recorded = recordGoalIssueSegmentStarted(
          connection = connection,
          segment =
            GoalIssueSegmentStart(
              parentWorkflowId = parentWorkflowId,
              issueKey = record.issueKey,
              workflowId = record.workflowId,
              startedAt = record.startedAt,
              resumed = record.resumed,
              mode = record.mode,
            ),
        )
        if (!recorded) diagnostics.warning(
          "seam=goal-issue-identity outcome=deferred workflow=$parentWorkflowId reason=case-variant-ambiguous",
          null,
        )
      }
      val result = supersedeEligibleSegments(connection, record, candidates)
      result.invocationWorkflowIds.forEach { emitGoalFinished(connection, runtimeVersion, it, level) }
      result.abandonedIssues.forEach { issue ->
        val outcome = saveGoalIssueFinished(connection, issue, diagnostics)
        if (outcome.persisted) {
          emitGoalIssueFinished(
            connection,
            runtimeVersion,
            GoalIssueIdentity(issue.parentWorkflowId, issue.issueKey),
            level,
            diagnostics,
          )
        }
      }
    }
    emitGoalStarted(connection, runtimeVersion, record.workflowId, level)
  }

  private fun sameKeyUnfinishedGoalSegments(
    record: GoalStartedRecord,
  ): List<Triple<GoalStartedOwnershipEvidence, String, String>> {
    return connection.prepareStatement(
      """
      SELECT old.workflow_id, old.parent_workflow_id, old.started_at, old.mode, old.status, old.finished_at,
        progress.parent_workflow_id AS latest_parent_workflow_id
      FROM goal_run_sessions old
      LEFT JOIN goal_issue_progress progress
        ON progress.latest_segment_workflow_id = old.workflow_id
      WHERE UPPER(TRIM(old.issue_key)) = UPPER(TRIM(?))
        AND (old.status IS NULL OR old.finished_at IS NULL) AND old.workflow_id != ?
      """.trimIndent(),
    ).use { statement ->
      statement.bindAll(record.issueKey, record.workflowId)
      statement.executeQuery().use { rows ->
        buildList {
          while (rows.next()) {
            val oldStartedAt = rows.getString("started_at")
            add(
              Triple(
                GoalStartedOwnershipEvidence(
                  workflowId = rows.getString("workflow_id"),
                  parentWorkflowId = rows.getString("parent_workflow_id")?.takeIf(String::isNotBlank),
                  latestSegmentParentWorkflowId = rows.getString("latest_parent_workflow_id")?.takeIf(String::isNotBlank),
                  status = rows.getString("status"),
                  finishedAt = rows.getString("finished_at"),
                ),
                oldStartedAt,
                rows.getString("mode") ?: "runtime",
              )
            )
          }
        }
      }
    }
  }

  override fun goalSubtaskFinished(
    record: GoalSubtaskFinishedRecord,
    level: String,
  ) {
    saveGoalSubtaskFinished(connection, record)
    emitGoalSubtaskFinished(connection, runtimeVersion, record, level, diagnostics)
  }

  override fun goalFinished(
    record: GoalFinishedRecord,
    level: String,
  ) {
    val outcome = saveGoalFinished(connection, record)
    if (outcome == GoalFinishedSaveOutcome.FIRST_TERMINAL && record.status != "completed") {
      record.parentWorkflowId?.takeIf(String::isNotBlank)?.let { parentWorkflowId ->
        recordGoalIssueSegmentEnd(
          connection,
          parentWorkflowId,
          record.issueKey,
          record.workflowId,
          record.status,
        )
      }
    }
    emitGoalFinished(connection, runtimeVersion, record.workflowId, level)
  }

  override fun goalIssueFinished(
    record: GoalIssueFinishedRecord,
    level: String,
  ) {
    val persistedIssueKey = skillbill.infrastructure.sqlite.telemetry.goal.resolveOpenGoalIssueKey(
      connection,
      record.parentWorkflowId,
      record.issueKey,
    ) ?: run {
      diagnostics.warning(
        "seam=goal-issue-identity outcome=deferred workflow=${record.parentWorkflowId} reason=case-variant-ambiguous",
        null,
      )
      return
    }
    val persistedRecord = record.copy(issueKey = persistedIssueKey)
    saveGoalIssueFinished(connection, persistedRecord, diagnostics)
    val goal = GoalIssueIdentity(persistedRecord.parentWorkflowId, persistedRecord.issueKey)
    emitGoalIssueFinished(connection, runtimeVersion, goal, level, diagnostics)
  }
}
