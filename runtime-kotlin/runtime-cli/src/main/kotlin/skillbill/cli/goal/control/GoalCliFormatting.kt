package skillbill.cli.goal.control

import skillbill.cli.kernel.payload.CliPayloadStatus
import skillbill.contracts.SharedPayloadKeys
import skillbill.engine.goalrunner.model.GoalRunnerAppliedRepair
import skillbill.engine.goalrunner.model.GoalRunnerChildWedgeDiagnosis
import skillbill.engine.goalrunner.model.GoalRunnerOperatorDecisionResult
import skillbill.engine.goalrunner.model.GoalRunnerRepairResult
import skillbill.engine.goalrunner.model.GoalRunnerResetSubtaskSnapshot
import skillbill.engine.goalrunner.model.GoalRunnerWedgeFinding

internal fun appendGoalResetSubtaskLines(
  builder: StringBuilder,
  subtasks: List<GoalRunnerResetSubtaskSnapshot>,
) {
  subtasks.forEach { subtask ->
    builder.append("  - ")
    builder.append("id=")
    builder.append(subtask.id)
    builder.append("; status=")
    builder.append(subtask.status)
    builder.append("; workflow_id=")
    builder.append(subtask.workflowId ?: "none")
    builder.append("; commit_sha=")
    builder.append(subtask.commitSha ?: "none")
    builder.append("; blocked_reason=")
    builder.append(subtask.blockedReason ?: "none")
    builder.append("; last_resumable_step=")
    builder.append(subtask.lastResumableStep ?: "none")
    builder.append('\n')
  }
}

internal fun GoalRunnerRepairResult.toGoalRepairCliMap(): Map<String, Any?> =
  linkedMapOf(
    SharedPayloadKeys.STATUS to status.wireValue,
    SharedPayloadKeys.ISSUE_KEY to issueKey,
    "parent_workflow_id" to parentWorkflowId,
    "refusal_reason" to refusalReason,
    "live_lease_workflow_id" to liveLeaseWorkflowId,
    "parent_passed_checks" to parentPassedChecks,
    "parent_wedges" to
      parentWedges.map { wedge ->
        linkedMapOf(
          "wedge_class" to wedge.wedgeClass.wireValue,
          "field" to wedge.field,
          "current_value" to wedge.currentValue,
        )
      },
    "diagnoses" to
      diagnoses.map { diagnosis ->
        linkedMapOf(
          SharedPayloadKeys.SUBTASK_ID to diagnosis.subtaskId,
          SharedPayloadKeys.WORKFLOW_ID to diagnosis.workflowId,
          "healthy" to diagnosis.isHealthy,
          "passed_checks" to diagnosis.passedChecks,
          "wedges" to
            diagnosis.wedges.map { wedge ->
              linkedMapOf(
                "wedge_class" to wedge.wedgeClass.wireValue,
                "field" to wedge.field,
                "current_value" to wedge.currentValue,
              )
            },
        )
      },
    "applied_repairs" to
      appliedRepairs.map { repair ->
        linkedMapOf(
          SharedPayloadKeys.SUBTASK_ID to repair.subtaskId,
          SharedPayloadKeys.WORKFLOW_ID to repair.workflowId,
          "wedge_class" to repair.wedgeClass.wireValue,
          "field" to repair.field,
          "prior_value" to repair.priorValue,
          "new_value" to repair.newValue,
        )
      },
  )

internal fun goalRepairText(result: GoalRunnerRepairResult): String =
  buildString {
    appendLine("goal: ${result.issueKey}")
    appendLine("status: ${result.status.wireValue}")
    result.parentWorkflowId?.let { appendLine("parent_workflow_id: $it") }
    result.refusalReason?.let { appendLine("refusal_reason: $it") }
    result.liveLeaseWorkflowId?.let { appendLine("live_lease_workflow_id: $it") }
    appendGoalRepairParentWedges(this, result.parentWedges)
    result.parentPassedChecks.takeIf { it.isNotEmpty() }?.let { checks ->
      appendLine("parent_passed_checks: ${checks.joinToString(",")}")
    }
    appendLine("diagnoses:")
    appendGoalRepairDiagnoses(this, result.diagnoses)
    appendGoalRepairAppliedRepairs(this, result.appliedRepairs)
  }

private fun appendGoalRepairParentWedges(
  builder: StringBuilder,
  wedges: List<GoalRunnerWedgeFinding>,
) {
  wedges.forEach { wedge ->
    builder.appendLine(
      "parent_wedge: class=${wedge.wedgeClass.wireValue}; field=${wedge.field}; " +
        "current_value=${wedge.currentValue ?: "absent"}",
    )
  }
}

private fun appendGoalRepairDiagnoses(
  builder: StringBuilder,
  diagnoses: List<GoalRunnerChildWedgeDiagnosis>,
) {
  diagnoses.forEach { diagnosis ->
    builder.appendLine(
      "  - subtask=${diagnosis.subtaskId}; " +
        "workflow_id=${diagnosis.workflowId ?: "none"}; " +
        "healthy=${diagnosis.isHealthy}",
    )
    diagnosis.passedChecks.takeIf { it.isNotEmpty() }?.let { checks ->
      builder.appendLine("    passed_checks: ${checks.joinToString(",")}")
    }
    diagnosis.wedges.forEach { wedge ->
      builder.appendLine(
        "    wedge: class=${wedge.wedgeClass.wireValue}; field=${wedge.field}; " +
          "current_value=${wedge.currentValue ?: "absent"}",
      )
    }
  }
}

private fun appendGoalRepairAppliedRepairs(
  builder: StringBuilder,
  repairs: List<GoalRunnerAppliedRepair>,
) {
  if (repairs.isEmpty()) return
  builder.appendLine("applied_repairs:")
  repairs.forEach { repair ->
    builder.appendLine(
      "  - subtask=${repair.subtaskId}; field=${repair.field}; " +
        "wedge_class=${repair.wedgeClass.wireValue}; prior=${repair.priorValue ?: "absent"}; " +
        "new=${repair.newValue ?: "absent"}",
    )
  }
}

internal fun GoalRunnerOperatorDecisionResult.toGoalOperatorDecisionCliMap(): Map<String, Any?> =
  when (this) {
    is GoalRunnerOperatorDecisionResult.Recorded ->
      linkedMapOf(
        SharedPayloadKeys.STATUS to CliPayloadStatus.OK,
        SharedPayloadKeys.ISSUE_KEY to issueKey,
        "parent_workflow_id" to parentWorkflowId,
        SharedPayloadKeys.SUBTASK_ID to subtaskId,
        SharedPayloadKeys.WORKFLOW_ID to workflowId,
        "decision" to decision,
      )
    is GoalRunnerOperatorDecisionResult.Rejected ->
      linkedMapOf(
        SharedPayloadKeys.STATUS to "rejected",
        SharedPayloadKeys.ISSUE_KEY to issueKey,
        "reason" to reason,
      )
  }

internal fun goalOperatorDecisionText(result: GoalRunnerOperatorDecisionResult): String =
  buildString {
    when (result) {
      is GoalRunnerOperatorDecisionResult.Recorded -> {
        appendLine("goal: ${result.issueKey}")
        appendLine("status: ok")
        appendLine("parent_workflow_id: ${result.parentWorkflowId}")
        appendLine("subtask_id: ${result.subtaskId}")
        appendLine("workflow_id: ${result.workflowId}")
        appendLine("decision: ${result.decision}")
      }
      is GoalRunnerOperatorDecisionResult.Rejected -> {
        appendLine("goal: ${result.issueKey}")
        appendLine("status: rejected")
        appendLine("reason: ${result.reason}")
      }
    }
    if (result is GoalRunnerOperatorDecisionResult.Recorded) {
      appendLine(
        "next: skill-bill goal resume ${result.issueKey} (consumes the recorded decision)",
      )
    }
  }
