package skillbill.engine.featuretask.lifecycle.continuation

import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.decomposition.DecompositionManifestPayloadKeys
import skillbill.engine.featuretask.lifecycle.branch.Blocked
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeGoalContinuationContext
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunFacts
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunReport
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeSubtaskOutcome
import skillbill.engine.featuretask.phase.record.FeatureTaskRuntimePhaseQuery
import skillbill.engine.featuretask.phase.record.FeatureTaskRuntimePhaseRecorder
import skillbill.goalrunner.model.GoalRunnerTerminalStatus
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.model.workflowStepStatus
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerAction
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord
import java.time.Instant

const val BRANCH_SETUP_AGENT_SENTINEL = "branch-setup"
const val GOAL_PLANNING_IMPORT_AGENT_SENTINEL = "goal-planning-import"

private fun String.isRuntimeAgentId(): Boolean =
  isNotBlank() && this != BRANCH_SETUP_AGENT_SENTINEL && this != GOAL_PLANNING_IMPORT_AGENT_SENTINEL

fun completedGoalContinuationOutcome(
  recorder: FeatureTaskRuntimePhaseRecorder,
  gitOperations: WorkflowGitOperations,
  request: FeatureTaskRuntimeRunFacts,
  context: FeatureTaskRuntimeGoalContinuationContext,
  commitStepId: String,
): FeatureTaskRuntimeSubtaskOutcome {
  val payloadSha = commitShaFromPhaseRecords(recorder, request, commitStepId)
  if (!context.suppressPr) {
    return completeSubtaskOutcome(request, context, payloadSha, commitStepId)
  }
  val resolvedSha = payloadSha ?: measuredHeadSha(gitOperations, request)
  return if (resolvedSha != null) {
    completeSubtaskOutcome(request, context, resolvedSha, commitStepId)
  } else {
    FeatureTaskRuntimeSubtaskOutcome(
      issueKey = context.parentIssueKey,
      subtaskId = context.subtaskId,
      status = GoalRunnerTerminalStatus.BLOCKED,
      commitSha = null,
      workflowId = request.workflowId,
      blockedReason =
        "commit_push completed under suppress_pr but no commit SHA could be captured " +
          "from the phase payload or measured from git HEAD; the per-subtask commit invariant cannot " +
          "be satisfied.",
      lastResumableStep = commitStepId,
    )
  }
}

fun completeSubtaskOutcome(
  request: FeatureTaskRuntimeRunFacts,
  context: FeatureTaskRuntimeGoalContinuationContext,
  commitSha: String?,
  commitStepId: String,
): FeatureTaskRuntimeSubtaskOutcome =
  FeatureTaskRuntimeSubtaskOutcome(
    issueKey = context.parentIssueKey,
    subtaskId = context.subtaskId,
    status = GoalRunnerTerminalStatus.COMPLETE,
    commitSha = commitSha,
    workflowId = request.workflowId,
    blockedReason = null,
    lastResumableStep = commitStepId,
  )

fun measuredHeadSha(
  gitOperations: WorkflowGitOperations,
  request: FeatureTaskRuntimeRunFacts,
): String? {
  val result = gitOperations.headCommitSha(request.repoRoot)
  return result.value.trim().takeIf { result is WorkflowGitOperationResult.Ok && it.isNotBlank() }
}

internal data class SubtaskAgentAttribution(
  val finalizingAgentId: String?,
  val participatingAgentIds: List<String>,
)

internal fun agentAttributionFromPhaseState(
  phaseQuery: FeatureTaskRuntimePhaseQuery,
  workflowId: String,
): SubtaskAgentAttribution {
  val ledger =
    phaseQuery.loadPhaseLedger(workflowId)
      .orEmpty()
      .sortedBy { it.sequenceNumber }
  val records = phaseQuery.loadPhaseRecords(workflowId).orEmpty()

  val participating = LinkedHashSet<String>()
  ledger.forEach { entry ->
    entry.resolvedAgentId?.takeIf(String::isRuntimeAgentId)
      ?.let(participating::add)
  }
  records.values.forEach { record ->
    record.resolvedAgentId.takeIf(String::isRuntimeAgentId)
      ?.let(participating::add)
  }

  val finalizingFromLedger =
    ledger.lastOrNull { entry ->
      (
        entry.action == FeatureTaskRuntimePhaseLedgerAction.COMPLETE ||
          entry.action == FeatureTaskRuntimePhaseLedgerAction.BLOCKED
      ) &&
        entry.resolvedAgentId?.isRuntimeAgentId() == true
    }?.resolvedAgentId
  val finalizingAgentId = finalizingFromLedger ?: terminalRecordAgentId(records)

  return SubtaskAgentAttribution(
    finalizingAgentId = finalizingAgentId,
    participatingAgentIds = participating.toList(),
  )
}

private fun terminalRecordAgentId(records: Map<String, FeatureTaskRuntimePhaseRecord>): String? {
  val realRecords = records.values.filter { it.resolvedAgentId.isRuntimeAgentId() }
  realRecords.filter { it.status.workflowStepStatus() == WorkflowStepStatus.BLOCKED }
    .maxByOrNull { it.finishedAt ?: Instant.MIN }
    ?.let { return it.resolvedAgentId }
  return realRecords
    .filter { it.finishedAt != null }
    .maxByOrNull { it.finishedAt ?: Instant.MIN }
    ?.resolvedAgentId
}

fun commitShaFromPhaseRecords(
  recorder: FeatureTaskRuntimePhaseRecorder,
  request: FeatureTaskRuntimeRunFacts,
  commitStepId: String,
): String? {
  val commitOutput =
    recorder.loadPhaseRecords(request.workflowId)
      .orEmpty()[commitStepId]
      ?.outputArtifact
  val payload =
    commitOutput
      ?.let(JsonCodec::parseObjectOrNull)
      ?.let(JsonCodec::jsonElementToValue)
      ?.let(JsonCodec::anyToStringAnyMap)
  return payload?.commitShaFromPhasePayload()
}

fun Map<String, Any?>.commitShaFromPhasePayload(): String? {
  val producedOutputs = JsonCodec.anyToStringAnyMap(this[SharedPayloadKeys.PRODUCED_OUTPUTS])
  return (
    this["commit_push_result"] as? Map<
      *,
      *,
    >
  )?.get(DecompositionManifestPayloadKeys.COMMIT_SHA)?.toString()?.takeIf(String::isNotBlank)
    ?: (
      producedOutputs?.get("commit_push_result") as? Map<
        *,
        *,
      >
    )?.get(DecompositionManifestPayloadKeys.COMMIT_SHA)?.toString()
      ?.takeIf(String::isNotBlank)
    ?: producedOutputs?.get(DecompositionManifestPayloadKeys.COMMIT_SHA)?.toString()?.takeIf(String::isNotBlank)
    ?: (this[DecompositionManifestPayloadKeys.COMMIT_SHA]?.toString()?.takeIf(String::isNotBlank))
}

fun remediationBaseCoherenceBlockedReport(
  request: FeatureTaskRuntimeRunFacts,
  operatorGuidance: String,
  firstStepId: String,
): FeatureTaskRuntimeRunReport.Blocked =
  FeatureTaskRuntimeRunReport.Blocked(
    issueKey = request.issueKey,
    workflowId = request.workflowId,
    featureSize = request.runInvariants.featureSize.name,
    lastIncompletePhase = firstStepId,
    blockedReason = operatorGuidance,
    completedPhaseIds = emptyList(),
    resolvedBranch = request.goalContinuation?.goalBranch,
  )
