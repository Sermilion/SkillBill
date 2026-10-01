package skillbill.engine.goalrunner.telemetry

import skillbill.application.telemetry.lifecycle.GoalLifecycleTelemetryEmitter
import skillbill.application.telemetry.model.GoalFinishedRequest
import skillbill.application.telemetry.model.GoalIssueFinishedRequest
import skillbill.application.telemetry.model.GoalStartedRequest
import skillbill.application.telemetry.model.GoalSubtaskFinishedRequest
import skillbill.engine.goalrunner.model.GoalRunnerManifestState
import skillbill.goalrunner.model.GoalRunnerRunReport
import skillbill.goalrunner.model.GoalRunnerStopReason
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.decomposition.model.DecompositionSubtask
import skillbill.workflow.decomposition.runtime.normalizedBlockedReason
import skillbill.workflow.model.DecompositionStatus
import skillbill.workflow.model.decompositionStatus
import java.time.Clock
import java.time.Duration
import java.time.Instant

class GoalRunnerTelemetryEmitter(
  private val telemetry: GoalLifecycleTelemetryEmitter,
  private val clock: Clock,
  private val state: GoalRunnerManifestState,
) {
  private val segmentStartedAt: Instant = clock.instant()
  private val segmentWorkflowId: String = "${state.parentWorkflowId}:seg:$segmentStartedAt"
  private val resumed: Boolean = state.manifest.subtasks.any { it.hasStarted() }

  private val subtasksTerminalAtSegmentStart: Set<Int> =
    state.manifest.subtasks
      .filter { it.status.decompositionStatus() in TERMINAL_STATUSES }
      .map { it.id }
      .toSet()
  private val subtasksEmittedThisSegment: MutableSet<Int> = mutableSetOf()
  private val subtaskStartedAt: MutableMap<Int, Instant> = mutableMapOf()

  fun goalStarted() {
    telemetry.goalStarted(
      GoalStartedRequest(
        issueKey = state.manifest.issueKey,
        featureName = state.manifest.featureName,
        workflowId = segmentWorkflowId,
        subtaskTotal = state.manifest.subtasks.size,
        resumed = resumed,
        startedAt = segmentStartedAt.toString(),
        status = "running",
        mode = "runtime",
        parentWorkflowId = state.parentWorkflowId,
      ),
    )
  }

  fun markSubtaskStarted(subtaskId: Int) {
    subtaskStartedAt.putIfAbsent(subtaskId, clock.instant())
  }

  fun emitNewlyTerminalSubtasks(
    manifest: DecompositionManifest,
    attempted: List<Int>,
  ) {
    val finishedAtInstant = clock.instant()
    val finishedAt = finishedAtInstant.toString()
    manifest.subtasks
      .filter { it.status.decompositionStatus() in TERMINAL_STATUSES }
      .filter { it.id !in subtasksTerminalAtSegmentStart && it.id !in subtasksEmittedThisSegment }
      .forEach { subtask ->
        subtasksEmittedThisSegment += subtask.id
        val startedAt = subtaskStartedAt[subtask.id] ?: finishedAtInstant
        telemetry.goalSubtaskFinished(
          GoalSubtaskFinishedRequest(
            issueKey = manifest.issueKey,
            workflowId =
              subtask.workflowId?.takeIf(String::isNotBlank)
                ?: "${manifest.issueKey}:subtask:${subtask.id}",
            subtaskId = subtask.id,
            subtaskName = subtask.name,
            status = subtask.status,
            startedAt = startedAt.toString(),
            finishedAt = finishedAt,
            durationMs = durationMs(startedAt, finishedAtInstant),
            attemptCount = attempted.count { it == subtask.id }.coerceAtLeast(1),
            blockedReason = subtask.blockedReasonForTelemetry(),
            finalizingAgentId = subtask.finalizingAgentId,
            participatingAgentIds = subtask.participatingAgentIds,
          ),
        )
      }
  }

  private fun DecompositionSubtask.blockedReasonForTelemetry(): String? =
    if (
      status.decompositionStatus() == DecompositionStatus.BLOCKED
    ) {
      normalizedBlockedReason(
        reason = blockedReason,
        category = "runtime",
        fallback = "Goal subtask $id is blocked.",
      )
    } else {
      null
    }

  fun goalFinished(
    manifest: DecompositionManifest,
    report: GoalRunnerRunReport,
  ) {
    val finishedAtInstant = clock.instant()
    val stopReason = (report as? GoalRunnerRunReport.Stopped)?.stop?.reason?.name
    telemetry.goalFinished(
      GoalFinishedRequest(
        issueKey = manifest.issueKey,
        workflowId = segmentWorkflowId,
        status = goalFinishedStatus(report),
        startedAt = segmentStartedAt.toString(),
        finishedAt = finishedAtInstant.toString(),
        durationMs = durationMs(segmentStartedAt, finishedAtInstant),
        subtasksComplete =
          manifest.subtasks.count {
            it.status.decompositionStatus() == DecompositionStatus.COMPLETE
          },
        subtasksBlocked =
          manifest.subtasks.count {
            it.status.decompositionStatus() == DecompositionStatus.BLOCKED
          },
        subtasksSkipped =
          manifest.subtasks.count {
            it.status.decompositionStatus() == DecompositionStatus.SKIPPED
          },
        mode = "runtime",
        stopReason = stopReason,
        parentWorkflowId = state.parentWorkflowId,
      ),
    )
  }

  private fun goalFinishedStatus(report: GoalRunnerRunReport): String =
    when {
      report is GoalRunnerRunReport.Completed -> "completed"
      (report as? GoalRunnerRunReport.Stopped)?.stop?.reason == GoalRunnerStopReason.PAUSED -> "paused"
      else -> "blocked"
    }

  fun goalIssueFinished(
    manifest: DecompositionManifest,
    report: GoalRunnerRunReport.Completed,
  ) {
    telemetry.goalIssueFinished(
      GoalIssueFinishedRequest(
        issueKey = manifest.issueKey,
        parentWorkflowId = state.parentWorkflowId,
        status = "completed",
        subtasksComplete =
          manifest.subtasks.count {
            it.status.decompositionStatus() == DecompositionStatus.COMPLETE
          },
        subtasksBlocked = report.subtasksBlocked,
        subtasksSkipped =
          manifest.subtasks.count {
            it.status.decompositionStatus() == DecompositionStatus.SKIPPED
          },
        finishedAt = clock.instant().toString(),
        mode = "runtime",
      ),
    )
  }

  private fun durationMs(
    startedAt: Instant,
    finishedAt: Instant,
  ): Long = Duration.between(startedAt, finishedAt).toMillis().coerceAtLeast(0)

  private companion object {
    val TERMINAL_STATUSES =
      setOf(
        DecompositionStatus.COMPLETE,
        DecompositionStatus.BLOCKED,
        DecompositionStatus.SKIPPED,
      )
  }
}
