package skillbill.cli.goal.control

import skillbill.cli.kernel.payload.CliPayloadStatus
import skillbill.contracts.SharedPayloadKeys
import skillbill.engine.goalrunner.model.GoalRunnerAcceptResult
import skillbill.engine.goalrunner.model.GoalRunnerReplanResult
import skillbill.engine.goalrunner.model.GoalRunnerReplanSnapshot
import skillbill.engine.goalrunner.model.GoalRunnerResetResult
import skillbill.engine.goalrunner.model.GoalRunnerResetSnapshot
import skillbill.goalrunner.model.GoalRunnerAcceptedSubtask

internal fun GoalRunnerResetResult?.toGoalResetCliMap(
  issueKey: String,
  hard: Boolean,
): Map<String, Any?> =
  this?.let {
    val status =
      when {
        it.refusalReason != null -> "refused"
        it.recovery?.recoveryCommand != null -> "recovery_required"
        else -> CliPayloadStatus.OK
      }
    linkedMapOf(
      SharedPayloadKeys.STATUS to status,
      SharedPayloadKeys.ISSUE_KEY to it.issueKey,
      "mode" to it.mode,
      "parent_workflow_id" to it.parentWorkflowId,
      "before" to resetSnapshotMap(it.before),
      "after" to resetSnapshotMap(it.after),
      GoalRunnerResetPayloadKeys.BRANCH_ACTION_TAKEN to it.branchActionTaken,
      GoalRunnerResetPayloadKeys.REFUSAL_REASON to it.refusalReason,
      GoalRunnerResetPayloadKeys.REMEDY_COMMAND to it.remedyCommand,
      "recovery" to
        it.recovery?.let { recovery ->
          linkedMapOf(
            SharedPayloadKeys.SUBTASK_ID to recovery.subtaskId,
            SharedPayloadKeys.WORKFLOW_ID to recovery.workflowId,
            "classification" to recovery.classification,
            "command" to recovery.recoveryCommand,
          )
        },
    )
  } ?: linkedMapOf(
    SharedPayloadKeys.STATUS to CliPayloadStatus.NOT_FOUND,
    SharedPayloadKeys.ISSUE_KEY to issueKey,
    "mode" to if (hard) "hard" else "soft",
  )

internal fun GoalRunnerReplanResult?.toGoalReplanCliMap(issueKey: String): Map<String, Any?> =
  this?.let {
    linkedMapOf(
      SharedPayloadKeys.STATUS to CliPayloadStatus.OK,
      SharedPayloadKeys.ISSUE_KEY to it.issueKey,
      "mode" to "scoped_replan",
      "parent_workflow_id" to it.parentWorkflowId,
      SharedPayloadKeys.SUBTASK_ID to it.subtaskId,
      "discarded_plan" to it.discardedPlan,
      "discarded_shared_preplan" to it.discardedSharedPreplan,
      "cascaded_plan_subtask_ids" to it.cascadedPlanSubtaskIds,
      "cleared_child_subtask_ids" to it.clearedChildSubtaskIds,
      "before" to replanSnapshotMap(it.before),
      "after" to replanSnapshotMap(it.after),
    )
  } ?: linkedMapOf(
    SharedPayloadKeys.STATUS to CliPayloadStatus.NOT_FOUND,
    SharedPayloadKeys.ISSUE_KEY to issueKey,
    "mode" to "scoped_replan",
  )

internal fun resetSnapshotMap(snapshot: GoalRunnerResetSnapshot): Map<String, Any?> =
  linkedMapOf(
    SharedPayloadKeys.STATUS to snapshot.status,
    "current_subtask" to snapshot.currentSubtaskId,
    "current_action" to snapshot.currentAction,
    "subtasks" to
      snapshot.subtasks.map { subtask ->
        linkedMapOf(
          "id" to subtask.id,
          SharedPayloadKeys.STATUS to subtask.status,
          "branch" to subtask.branch,
          SharedPayloadKeys.WORKFLOW_ID to subtask.workflowId,
          "commit_sha" to subtask.commitSha,
          "blocked_reason" to subtask.blockedReason,
          "last_resumable_step" to subtask.lastResumableStep,
        )
      },
  )

internal fun replanSnapshotMap(snapshot: GoalRunnerReplanSnapshot): Map<String, Any?> =
  linkedMapOf(
    SharedPayloadKeys.STATUS to snapshot.status,
    "current_subtask" to snapshot.currentSubtaskId,
    "current_action" to snapshot.currentAction,
    "shared_preplan_prepared" to snapshot.sharedPreplanPrepared,
    "planned_subtask_ids" to snapshot.plannedSubtaskIds,
    "subtasks" to
      snapshot.subtasks.map { subtask ->
        linkedMapOf(
          "id" to subtask.id,
          SharedPayloadKeys.STATUS to subtask.status,
          "branch" to subtask.branch,
          SharedPayloadKeys.WORKFLOW_ID to subtask.workflowId,
          "commit_sha" to subtask.commitSha,
          "blocked_reason" to subtask.blockedReason,
          "last_resumable_step" to subtask.lastResumableStep,
        )
      },
  )

internal fun GoalRunnerAcceptResult.toGoalAcceptCliMap(): Map<String, Any?> =
  when (this) {
    is GoalRunnerAcceptResult.Accepted ->
      linkedMapOf(
        SharedPayloadKeys.STATUS to CliPayloadStatus.OK,
        SharedPayloadKeys.ISSUE_KEY to issueKey,
        "parent_workflow_id" to parentWorkflowId,
        SharedPayloadKeys.SUBTASK_ID to subtaskId,
        "commit_sha" to commitSha,
        "reason" to reason,
        "accepted_at" to acceptedAt,
        "after" to resetSnapshotMap(after),
      )
    is GoalRunnerAcceptResult.Rejected ->
      linkedMapOf(
        SharedPayloadKeys.STATUS to "rejected",
        SharedPayloadKeys.ISSUE_KEY to issueKey,
        "reason" to reason,
      )
  }

internal fun goalAcceptText(result: GoalRunnerAcceptResult): String =
  buildString {
    when (result) {
      is GoalRunnerAcceptResult.Accepted -> {
        appendLine("goal: ${result.issueKey}")
        appendLine("status: ok")
        appendLine("reason: ${result.reason}")
        appendLine("accepted_subtask: ${result.subtaskId}")
        appendLine("commit_sha: ${result.commitSha}")
        appendLine("parent_workflow_id: ${result.parentWorkflowId}")
        appendLine(
          "after: status=${result.after.status}; " +
            "current_subtask=${result.after.currentSubtaskId ?: "none"}",
        )
        appendLine("after_subtasks:")
        appendGoalResetSubtaskLines(this, result.after.subtasks)
      }
      is GoalRunnerAcceptResult.Rejected -> {
        appendLine("goal: ${result.issueKey}")
        appendLine("status: rejected")
        appendLine("reason: ${result.reason}")
      }
    }
  }

internal fun hardResetAcceptanceWarning(
  issueKey: String,
  records: List<GoalRunnerAcceptedSubtask>,
): String =
  buildString {
    appendLine("hard_reset_acceptances_to_discard:")
    records.forEach { record ->
      val command =
        listOf(
          "skill-bill",
          "goal",
          "accept",
          issueKey,
          "--subtask",
          record.subtaskId.toString(),
          "--commit",
          record.commitSha,
          "--reason",
          record.reason,
          "--restore-after-hard-reset",
        ).joinToString(" ", transform = String::shellWord)
      appendLine(
        "acceptance: subtask=${record.subtaskId}; commit=${record.commitSha}; reason=${record.reason}",
      )
      appendLine("restore_command: $command")
    }
  }

internal fun String.shellWord(): String =
  if (isNotEmpty() && all { it.isLetterOrDigit() || it in "-._/:@" }) {
    this
  } else {
    "'${replace("'", "'\"'\"'")}'"
  }

internal fun goalResetText(
  result: GoalRunnerResetResult?,
  issueKey: String,
  hard: Boolean,
): String =
  buildString {
    appendLine("goal: ${result?.issueKey ?: issueKey}")
    appendLine("status: ${result.goalResetStatus()}")
    appendLine("mode: ${result?.mode ?: if (hard) "hard" else "soft"}")
    result?.parentWorkflowId?.let { appendLine("parent_workflow_id: $it") }
    result?.let {
      appendGoalResetSnapshots(it)
      appendGoalResetRecovery(it)
      it.branchActionTaken?.let { action -> appendLine("branch_action_taken: $action") }
      it.refusalReason?.let { reason -> appendLine("refusal_reason: $reason") }
      it.remedyCommand?.let { command -> appendLine("remedy_command: $command") }
    }
  }

private fun GoalRunnerResetResult?.goalResetStatus(): String =
  when {
    this == null -> CliPayloadStatus.NOT_FOUND
    this.refusalReason != null -> "refused"
    this.recovery?.recoveryCommand != null -> "recovery_required"
    else -> CliPayloadStatus.OK
  }

private fun StringBuilder.appendGoalResetSnapshots(result: GoalRunnerResetResult) {
  appendLine(
    "before: status=${result.before.status}; " +
      "current_subtask=${result.before.currentSubtaskId ?: "none"}",
  )
  appendLine(
    "after: status=${result.after.status}; " +
      "current_subtask=${result.after.currentSubtaskId ?: "none"}",
  )
  appendLine("before_subtasks:")
  appendGoalResetSubtaskLines(this, result.before.subtasks)
  appendLine("after_subtasks:")
  appendGoalResetSubtaskLines(this, result.after.subtasks)
}

private fun StringBuilder.appendGoalResetRecovery(result: GoalRunnerResetResult) {
  result.recovery?.let { recovery ->
    appendLine(
      "recovery: subtask=${recovery.subtaskId}; " +
        "workflow_id=${recovery.workflowId}; " +
        "classification=${recovery.classification}",
    )
    recovery.recoveryCommand?.let { appendLine("recovery_command: $it") }
  }
}

internal fun goalReplanText(
  result: GoalRunnerReplanResult?,
  issueKey: String,
): String =
  buildString {
    appendLine("goal: ${result?.issueKey ?: issueKey}")
    appendLine("status: ${if (result == null) CliPayloadStatus.NOT_FOUND else CliPayloadStatus.OK}")
    appendLine("mode: scoped_replan")
    result?.parentWorkflowId?.let { appendLine("parent_workflow_id: $it") }
    result?.let {
      appendLine("discarded_plan: subtask=${it.subtaskId}; existed=${it.discardedPlan}")
    }
    val discardedShared = result?.discardedSharedPreplan == true
    val cascaded = result?.cascadedPlanSubtaskIds.orEmpty()
    if (discardedShared || cascaded.isNotEmpty()) {
      appendLine(
        "discarded_shared_preplan: $discardedShared; " +
          "cascaded_plans=${cascaded.joinToString(",").ifEmpty { "none" }}",
      )
    }
    result?.let {
      appendLine(
        "preserved: shared_preplan=${it.after.sharedPreplanPrepared}; " +
          "planned_before=${it.before.plannedSubtaskIds.joinToString(",").ifEmpty { "none" }}; " +
          "planned_after=${it.after.plannedSubtaskIds.joinToString(",").ifEmpty { "none" }}",
      )
      appendLine(
        "before: status=${it.before.status}; " +
          "current_subtask=${it.before.currentSubtaskId ?: "none"}",
      )
      appendLine(
        "after: status=${it.after.status}; " +
          "current_subtask=${it.after.currentSubtaskId ?: "none"}",
      )
      appendLine("before_subtasks:")
      appendGoalResetSubtaskLines(this, it.before.subtasks)
      appendLine("after_subtasks:")
      appendGoalResetSubtaskLines(this, it.after.subtasks)
    }
  }
