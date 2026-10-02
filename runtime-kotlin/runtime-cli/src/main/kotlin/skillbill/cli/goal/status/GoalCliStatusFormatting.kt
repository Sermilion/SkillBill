package skillbill.cli.goal.status

import skillbill.cli.goal.run.GOAL_STATUS_DATABASE_UNAVAILABLE
import skillbill.cli.goal.run.singleLineBounded
import skillbill.cli.goal.run.toGoalDiffStatCliMap
import skillbill.cli.goal.run.toGoalSelectedDiffHunksCliMap
import skillbill.cli.kernel.agent.detectInvokingAgentId
import skillbill.cli.kernel.payload.CliPayloadStatus
import skillbill.cli.model.CliRunInputs
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.workflow.identity.evidence.ValidationEvidencePayloadKeys
import skillbill.contracts.workflow.payload.WorktreeEditJournalPayloadKeys
import skillbill.engine.goalrunner.model.GoalRunnerStatusRequest
import skillbill.goalrunner.model.ExecutionLiveness
import skillbill.goalrunner.model.GoalRunnerAcceptedSubtask
import skillbill.goalrunner.model.GoalRunnerStatusProjection
import skillbill.idestatus.model.WorktreeEditSource
import skillbill.ports.repository.RepositoryEnclosingRootPort
import skillbill.ports.workflow.gitops.model.DEFAULT_SELECTED_DIFF_MAX_BYTES
import skillbill.ports.workflow.gitops.model.DEFAULT_SELECTED_DIFF_MAX_HUNKS
import skillbill.ports.workflow.gitops.model.DEFAULT_SELECTED_DIFF_MAX_LINES
import java.nio.file.Path

internal data class GoalStatusCliRequestOptions(
  val issueKey: String,
  val monitorOnly: Boolean = false,
  val agent: String?,
  val agentOverride: String?,
  val repoRoot: String?,
  val diff: GoalStatusCliDiffOptions = GoalStatusCliDiffOptions(),
)

internal data class GoalStatusCliDiffOptions(
  val includeDiffStat: Boolean = false,
  val selectedDiffHunkPaths: List<String> = emptyList(),
  val selectedDiffMaxHunks: Int = DEFAULT_SELECTED_DIFF_MAX_HUNKS,
  val selectedDiffMaxLines: Int = DEFAULT_SELECTED_DIFF_MAX_LINES,
  val selectedDiffMaxBytes: Int = DEFAULT_SELECTED_DIFF_MAX_BYTES,
)

internal fun CliRunInputs.goalStatusRequest(
  options: GoalStatusCliRequestOptions,
  repositoryEnclosingRootPort: RepositoryEnclosingRootPort,
): GoalRunnerStatusRequest =
  GoalRunnerStatusRequest(
    issueKey = options.issueKey,
    invokedAgentId = detectInvokingAgentId(options.agent, environment),
    configuredAgentOverrideId = options.agentOverride,
    repoRoot =
      options.repoRoot?.let(Path::of)
        ?.let { root ->
          if (options.monitorOnly) {
            repositoryEnclosingRootPort.enclosingRepositoryRoot(root)
          } else {
            root.toAbsolutePath().normalize()
          }
        }
        ?: repositoryRoot,
    includeDiffStat = options.diff.includeDiffStat,
    selectedDiffHunkPaths = options.diff.selectedDiffHunkPaths,
    selectedDiffMaxHunks = options.diff.selectedDiffMaxHunks,
    selectedDiffMaxLines = options.diff.selectedDiffMaxLines,
    selectedDiffMaxBytes = options.diff.selectedDiffMaxBytes,
  )

internal fun GoalRunnerStatusProjection?.toGoalStatusCliMap(issueKey: String): Map<String, Any?> =
  this?.let {
    linkedMapOf<String, Any?>(
      SharedPayloadKeys.STATUS to CliPayloadStatus.OK,
      SharedPayloadKeys.ISSUE_KEY to it.issueKey,
      "complete_count" to it.completeCount,
      "pending_count" to it.pendingCount,
      "blocked_count" to it.blockedCount,
      "current_subtask" to it.currentSubtaskId,
      "current_step" to it.currentStep,
      "active_agent" to it.activeAgent,
      "execution_liveness" to it.executionLiveness.wireValue,
      "degraded_durable_read" to it.degradedDurableRead,
      "latest_liveness_signal" to it.latestLivenessSignal,
      "paused" to it.paused,
      "pause_requested" to it.pauseRequested,
      "pause_reason" to it.pauseReason,
      "stop_after_subtask" to it.stopAfterSubtaskId,
    ).apply { putGoalStatusDetails(it) }
  } ?: linkedMapOf(
    SharedPayloadKeys.STATUS to CliPayloadStatus.NOT_FOUND,
    SharedPayloadKeys.ISSUE_KEY to issueKey,
    "complete_count" to 0,
    "pending_count" to 0,
    "blocked_count" to 0,
    "current_subtask" to null,
    "current_step" to null,
    "active_agent" to null,
    "execution_liveness" to ExecutionLiveness.UNKNOWN.wireValue,
    "latest_liveness_signal" to null,
    "paused" to false,
    "pause_requested" to false,
    "pause_reason" to null,
    "stop_after_subtask" to null,
  )

private fun MutableMap<String, Any?>.putGoalStatusDetails(projection: GoalRunnerStatusProjection) {
  projection.planning?.let { planning ->
    put(
      "planning",
      linkedMapOf(
        "state" to planning.state.wireValue,
        "shared_preplan_prepared" to planning.sharedPreplanPrepared,
        "planned_subtask_count" to planning.plannedSubtaskCount,
        "total_subtask_count" to planning.totalSubtaskCount,
        "current_planning_subtask" to planning.currentPlanningSubtaskId,
        "planning_wave_subtasks" to planning.planningWaveSubtaskIds,
        "reason" to planning.reason,
      ),
    )
  }
  projection.latestObservabilityEvent?.let { event ->
    put("latest_observability_event", event.toCompactSummaryWire())
  }
  projection.requestedDiffStat?.let { stat -> put("diff_stat", stat.toGoalDiffStatCliMap()) }
  projection.selectedDiffHunks?.let { hunks -> put("selected_diff_hunks", hunks.toGoalSelectedDiffHunksCliMap()) }
  putGoalLedgerCliEntries(projection)
  projection.outOfBandAcceptances.toGoalAcceptanceCliList()?.let { list ->
    put("out_of_band_acceptances", list)
  }
  if (projection.completedSubtaskValidation.isNotEmpty()) {
    put(
      ValidationEvidencePayloadKeys.COMPLETED_SUBTASK_VALIDATION,
      projection.completedSubtaskValidation.map { evidence -> evidence.toStatusWire() },
    )
  }
  projection.latestWorktreeEdit?.let { edit ->
    put(
      WorktreeEditJournalPayloadKeys.WORKTREE_EDITS,
      linkedMapOf(
        WorktreeEditJournalPayloadKeys.RECORDED_AT to edit.recordedAt.toString(),
        WorktreeEditJournalPayloadKeys.PHASE_ID to edit.phaseId,
        WorktreeEditJournalPayloadKeys.PATH_SAMPLE to edit.pathSample,
        WorktreeEditJournalPayloadKeys.NET_INSERTIONS to edit.netInsertions,
        WorktreeEditJournalPayloadKeys.NET_DELETIONS to edit.netDeletions,
        WorktreeEditJournalPayloadKeys.SOURCE to WorktreeEditSource.WORKTREE_PROBE.wireValue,
      ),
    )
  }
  projection.auditAcRetryCount?.let { count ->
    put(WorktreeEditJournalPayloadKeys.AUDIT_AC_RETRY_COUNT, count)
  }
}

internal fun GoalRunnerStatusProjection?.toBoundedGoalStatusCliMap(issueKey: String): Map<String, Any?> =
  this?.let {
    linkedMapOf<String, Any?>(
      "complete_count" to it.completeCount,
      "pending_count" to it.pendingCount,
      "blocked_count" to it.blockedCount,
      "current_subtask" to it.currentSubtaskId,
      "current_step" to it.currentStep?.let(::singleLineBounded),
      "execution_liveness" to it.executionLiveness.wireValue,
      "resumable_state" to it.monitorResumableState(),
    ).apply {
      it.latestWorktreeEdit?.let { edit ->
        put(
          WorktreeEditJournalPayloadKeys.WORKTREE_EDITS,
          linkedMapOf(
            WorktreeEditJournalPayloadKeys.RECORDED_AT to singleLineBounded(edit.recordedAt.toString()),
            WorktreeEditJournalPayloadKeys.PHASE_ID to edit.phaseId,
            WorktreeEditJournalPayloadKeys.PATH_SAMPLE to
              singleLineBounded(edit.pathSample.joinToString(",")),
            WorktreeEditJournalPayloadKeys.NET_INSERTIONS to edit.netInsertions,
            WorktreeEditJournalPayloadKeys.NET_DELETIONS to edit.netDeletions,
            WorktreeEditJournalPayloadKeys.SOURCE to WorktreeEditSource.WORKTREE_PROBE.wireValue,
          ),
        )
      }
      it.auditAcRetryCount?.let { count ->
        put(WorktreeEditJournalPayloadKeys.AUDIT_AC_RETRY_COUNT, count)
      }
    }
  } ?: linkedMapOf(
    SharedPayloadKeys.STATUS to CliPayloadStatus.NOT_FOUND,
    SharedPayloadKeys.ISSUE_KEY to singleLineBounded(issueKey),
    "resumable_state" to CliPayloadStatus.NOT_FOUND,
  )

internal fun databaseUnavailableGoalStatusCliMap(
  issueKey: String,
  reason: String,
): Map<String, Any?> =
  linkedMapOf(
    SharedPayloadKeys.STATUS to GOAL_STATUS_DATABASE_UNAVAILABLE,
    SharedPayloadKeys.ISSUE_KEY to singleLineBounded(issueKey),
    "resumable_state" to GOAL_STATUS_DATABASE_UNAVAILABLE,
    "reason" to singleLineBounded(reason),
  )

internal fun GoalRunnerStatusProjection.monitorResumableState(): String =
  currentStep.let { step ->
    when {
      paused -> "paused"
      pauseRequested -> "pause_requested"
      currentSubtaskId == null && pendingCount == 0 && blockedCount == 0 -> "complete"
      step.isNullOrBlank() -> "resumable"
      else -> "resumable_at:${singleLineBounded(step)}"
    }
  }

internal fun MutableMap<String, Any?>.putGoalLedgerCliEntries(projection: GoalRunnerStatusProjection) {
  if (projection.blockedAttemptCount > 0) put("blocked_attempt_count", projection.blockedAttemptCount)
  if (projection.supervisorKillCount > 0) put("supervisor_kill_count", projection.supervisorKillCount)
  if (projection.phaseAttemptCounts.isNotEmpty()) put("phase_attempt_counts", projection.phaseAttemptCounts)
  if (projection.cumulativeFixIterations.isNotEmpty()) {
    put("cumulative_fix_iterations", projection.cumulativeFixIterations)
  }
  if (projection.reAttemptCauseCounts.isNotEmpty()) put("re_attempt_causes", projection.reAttemptCauseCounts)
  projection.findingsInScope?.let { count -> put("findings_in_scope", count) }
}

internal fun List<GoalRunnerAcceptedSubtask>.toGoalAcceptanceCliList(): List<Map<String, Any?>>? =
  takeIf {
    it.isNotEmpty()
  }?.map { acceptance ->
    linkedMapOf(
      SharedPayloadKeys.SUBTASK_ID to acceptance.subtaskId,
      "commit_sha" to acceptance.commitSha,
      "reason" to acceptance.reason,
      "accepted_at" to acceptance.acceptedAt,
    )
  }

internal fun goalStatusText(
  issueKey: String,
  projection: GoalRunnerStatusProjection?,
): String =
  buildString {
    appendGoalStatusSummary(issueKey, projection)
    projection?.let {
      appendPlanningStatusLines(it)
      appendObservabilityStatusLines(it)
      appendWorktreeEditLines(it)
      appendOperatorSurfaceLines(it)
      appendValidationStatusLines(it)
      appendDiffStatusLines(it)
    }
  }

private fun StringBuilder.appendGoalStatusSummary(
  issueKey: String,
  projection: GoalRunnerStatusProjection?,
) {
  appendLine("goal: ${projection?.issueKey ?: issueKey}")
  appendLine("status: ${if (projection == null) CliPayloadStatus.NOT_FOUND else CliPayloadStatus.OK}")
  appendLine("complete: ${projection?.completeCount ?: 0}")
  appendLine("pending: ${projection?.pendingCount ?: 0}")
  appendLine("blocked: ${projection?.blockedCount ?: 0}")
  appendGoalStatusExecutionSummary(projection)
}

private fun StringBuilder.appendGoalStatusExecutionSummary(projection: GoalRunnerStatusProjection?) {
  appendLine("current_subtask: ${projection?.currentSubtaskId ?: "none"}")
  appendLine("current_step: ${projection?.currentStep ?: "none"}")
  appendLine("active_agent: ${projection?.activeAgent ?: "none"}")
  appendLine("execution_liveness: ${projection?.executionLiveness?.wireValue ?: "unknown"}")
  appendLine("latest_liveness_signal: ${projection?.latestLivenessSignal ?: "none"}")
  appendLine("paused: ${projection?.paused ?: false}")
  appendLine("pause_requested: ${projection?.pauseRequested ?: false}")
  appendLine("pause_reason: ${projection?.pauseReason ?: "none"}")
  appendLine("stop_after_subtask: ${projection?.stopAfterSubtaskId ?: "none"}")
}

private fun StringBuilder.appendPlanningStatusLines(projection: GoalRunnerStatusProjection) {
  projection.planning?.let { planning ->
    appendLine(
      "planning: state=${planning.state.wireValue} shared_preplan=${planning.sharedPreplanPrepared} " +
        "planned=${planning.plannedSubtaskCount}/${planning.totalSubtaskCount} " +
        "current=${planning.currentPlanningSubtaskId ?: "none"}" +
        planningWaveText(planning.planningWaveSubtaskIds.size),
    )
    planning.reason?.let { appendLine("planning_reason: $it") }
  }
}

private fun StringBuilder.appendObservabilityStatusLines(projection: GoalRunnerStatusProjection) {
  projection.latestObservabilityEvent?.let { event ->
    appendLine(
      "latest_observability: phase=${event.workflowPhase} role=${event.workerRole} " +
        "liveness=${event.livenessClass} sequence=${event.sequenceNumber}",
    )
  }
}

private fun StringBuilder.appendValidationStatusLines(projection: GoalRunnerStatusProjection) {
  projection.completedSubtaskValidation.forEach { evidence ->
    when {
      evidence.integrityProblem != null ->
        appendLine("validation_integrity: subtask=${evidence.subtaskId} problem=${evidence.integrityProblem}")
      else ->
        evidence.evidence?.results.orEmpty().forEach { result ->
          appendLine(
            "validation: subtask=${evidence.subtaskId} command=${result.command} " +
              "exit_code=${result.exitCode}",
          )
        }
    }
  }
}

private fun planningWaveText(waveSize: Int): String =
  when (waveSize) {
    0 -> ""
    1 -> " wave=1 subtask"
    else -> " wave=$waveSize subtasks"
  }

internal fun goalMonitorStatusText(
  issueKey: String,
  projection: GoalRunnerStatusProjection?,
  databaseUnavailableReason: String? = null,
): String =
  if (databaseUnavailableReason != null) {
    buildString {
      appendLine("goal: $issueKey")
      appendLine("status: $GOAL_STATUS_DATABASE_UNAVAILABLE")
      appendLine("resumable_state: $GOAL_STATUS_DATABASE_UNAVAILABLE")
      appendLine("reason: $databaseUnavailableReason")
    }
  } else if (projection == null) {
    buildString {
      appendLine("goal: $issueKey")
      appendLine("status: not_found")
      appendLine("resumable_state: not_found")
    }
  } else {
    buildString {
      appendLine("complete: ${projection.completeCount}")
      appendLine("pending: ${projection.pendingCount}")
      appendLine("blocked: ${projection.blockedCount}")
      appendLine("current_subtask: ${projection.currentSubtaskId ?: "none"}")
      appendLine("current_step: ${projection.currentStep ?: "none"}")
      appendLine("execution_liveness: ${projection.executionLiveness.wireValue}")
      appendLine("resumable_state: ${projection.monitorResumableState()}")
      appendWorktreeEditLines(projection)
    }
  }

private fun StringBuilder.appendWorktreeEditLines(projection: GoalRunnerStatusProjection) {
  projection.latestWorktreeEdit?.let { edit ->
    appendLine(
      "worktree_edits: at=${edit.recordedAt} phase=${edit.phaseId} " +
        "+${edit.netInsertions} -${edit.netDeletions} paths=${edit.pathSample.joinToString(",")}",
    )
  }
  projection.auditAcRetryCount?.let { count -> appendLine("audit_ac_retry_count: $count") }
}
