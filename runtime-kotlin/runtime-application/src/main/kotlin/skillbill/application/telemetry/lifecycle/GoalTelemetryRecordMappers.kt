package skillbill.application.telemetry.lifecycle

import skillbill.application.telemetry.model.GoalFinishedRequest
import skillbill.application.telemetry.model.GoalIssueFinishedRequest
import skillbill.application.telemetry.model.GoalStartedRequest
import skillbill.application.telemetry.model.GoalSubtaskFinishedRequest
import skillbill.contracts.issuekey.normalizeRequiredIssueKey
import skillbill.telemetry.model.GoalFinishedRecord
import skillbill.telemetry.model.GoalIssueFinishedRecord
import skillbill.telemetry.model.GoalStartedRecord
import skillbill.telemetry.model.GoalSubtaskFinishedRecord

fun GoalStartedRequest.toRecord(): GoalStartedRecord =
  GoalStartedRecord(
    issueKey = normalizeRequiredIssueKey(issueKey),
    featureName = featureName,
    workflowId = workflowId,
    subtaskTotal = subtaskTotal,
    resumed = resumed,
    startedAt = startedAt,
    status = status,
    mode = mode,
    parentWorkflowId = parentWorkflowId,
  )

fun GoalSubtaskFinishedRequest.toRecord(): GoalSubtaskFinishedRecord =
  GoalSubtaskFinishedRecord(
    issueKey = normalizeRequiredIssueKey(issueKey),
    workflowId = workflowId,
    subtaskId = subtaskId,
    subtaskName = subtaskName,
    status = status,
    startedAt = startedAt,
    finishedAt = finishedAt,
    durationMs = durationMs,
    attemptCount = attemptCount,
    blockedReason = blockedReason,
    finalizingAgentId = finalizingAgentId,
    participatingAgentIds = participatingAgentIds,
  )

fun GoalFinishedRequest.toRecord(): GoalFinishedRecord =
  GoalFinishedRecord(
    issueKey = normalizeRequiredIssueKey(issueKey),
    workflowId = workflowId,
    status = status,
    startedAt = startedAt,
    finishedAt = finishedAt,
    durationMs = durationMs,
    subtasksComplete = subtasksComplete,
    subtasksBlocked = subtasksBlocked,
    subtasksSkipped = subtasksSkipped,
    mode = mode,
    stopReason = stopReason,
    noChangeReason = noChangeReason,
    parentWorkflowId = parentWorkflowId,
  )

fun GoalIssueFinishedRequest.toRecord(): GoalIssueFinishedRecord =
  GoalIssueFinishedRecord(
    issueKey = normalizeRequiredIssueKey(issueKey),
    parentWorkflowId = parentWorkflowId,
    status = status,
    subtasksComplete = subtasksComplete,
    subtasksBlocked = subtasksBlocked,
    subtasksSkipped = subtasksSkipped,
    finishedAt = finishedAt,
    mode = mode,
  )
