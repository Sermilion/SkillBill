package skillbill.cli.featuretask

import skillbill.application.decomposition.decompositionManifestPath
import skillbill.application.decomposition.parentSpecPath
import skillbill.contracts.SharedPayloadKeys
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunReport
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeSubtaskOutcome

internal fun FeatureTaskRuntimeRunReport.toRuntimeRunCliMap(): Map<String, Any?> =
  when (this) {
    is FeatureTaskRuntimeRunReport.Completed ->
      linkedMapOf(
        SharedPayloadKeys.STATUS to "complete",
        SharedPayloadKeys.ISSUE_KEY to issueKey,
        SharedPayloadKeys.WORKFLOW_ID to workflowId,
        "feature_size" to featureSize,
        "resolved_branch" to resolvedBranch,
        "completed_phases" to completedPhaseIds,
      ).withSubtaskOutcome(subtaskOutcome)
    is FeatureTaskRuntimeRunReport.Blocked ->
      linkedMapOf(
        SharedPayloadKeys.STATUS to "blocked",
        SharedPayloadKeys.ISSUE_KEY to issueKey,
        SharedPayloadKeys.WORKFLOW_ID to workflowId,
        "feature_size" to featureSize,
        "resolved_branch" to resolvedBranch,
        "last_incomplete_phase" to lastIncompletePhase,
        "blocked_reason" to blockedReason,
        "completed_phases" to completedPhaseIds,
      ).withSubtaskOutcome(subtaskOutcome)
    is FeatureTaskRuntimeRunReport.Paused ->
      linkedMapOf(
        SharedPayloadKeys.STATUS to "paused",
        SharedPayloadKeys.ISSUE_KEY to issueKey,
        SharedPayloadKeys.WORKFLOW_ID to workflowId,
        "feature_size" to featureSize,
        "resolved_branch" to resolvedBranch,
        "paused_phase" to pausedPhase,
        "pause_reason" to pauseReason,
        "resumable_step" to resumableStep,
        "completed_phases" to completedPhaseIds,
      ).withSubtaskOutcome(subtaskOutcome)
    is FeatureTaskRuntimeRunReport.Decomposed ->
      linkedMapOf(
        SharedPayloadKeys.STATUS to "decomposed",
        SharedPayloadKeys.ISSUE_KEY to issueKey,
        SharedPayloadKeys.WORKFLOW_ID to workflowId,
        "feature_size" to featureSize,
        "resolved_branch" to resolvedBranch,
        "reason" to reason,
        "completed_phases" to completedPhaseIds,
        "parent_spec_path" to parentSpecPath,
        "decomposition_manifest_path" to decompositionManifestPath,
        "subtask_spec_paths" to subtaskSpecPaths,
        "subtask_count" to subtaskSpecPaths.size,
        "guidance" to DECOMPOSE_GUIDANCE,
      )
  }

internal fun Map<String, Any?>.withSubtaskOutcome(outcome: FeatureTaskRuntimeSubtaskOutcome?): Map<String, Any?> =
  if (outcome == null) {
    this
  } else {
    LinkedHashMap(this).apply {
      put(
        "subtask_outcome",
        linkedMapOf(
          SharedPayloadKeys.ISSUE_KEY to outcome.issueKey,
          SharedPayloadKeys.SUBTASK_ID to outcome.subtaskId,
          SharedPayloadKeys.STATUS to outcome.status.wireValue,
          "commit_sha" to outcome.commitSha,
          SharedPayloadKeys.WORKFLOW_ID to outcome.workflowId,
          "blocked_reason" to outcome.blockedReason,
          "last_resumable_step" to outcome.lastResumableStep,
          "finalizing_agent_id" to outcome.finalizingAgentId,
          "participating_agent_ids" to outcome.participatingAgentIds,
        ),
      )
    }
  }

internal fun FeatureTaskRuntimeRunReport.runtimeRunExitCode(): Int =
  when (this) {
    is FeatureTaskRuntimeRunReport.Completed,
    is FeatureTaskRuntimeRunReport.Decomposed,
    -> 0
    is FeatureTaskRuntimeRunReport.Blocked,
    is FeatureTaskRuntimeRunReport.Paused,
    -> 1
  }

internal fun runtimeRunText(
  report: FeatureTaskRuntimeRunReport,
  incidentLines: List<String> = emptyList(),
): String =
  buildString {
    appendLine("feature-task-runtime: ${report.issueKey}")
    appendLine("workflow_id: ${report.workflowId}")
    appendLine("status: ${report.statusWireValue()}")
    appendLine("feature_size: ${report.featureSize}")
    appendLine("resolved_branch: ${report.resolvedBranch ?: "none"}")
    appendLine("completed_phases: ${report.completedPhaseIds().joinToString()}")
    when (report) {
      is FeatureTaskRuntimeRunReport.Blocked -> {
        appendLine("last_incomplete_phase: ${report.lastIncompletePhase}")
        appendLine("blocked_reason: ${report.blockedReason}")
        report.subtaskOutcome?.let { appendSubtaskOutcome(it) }
      }
      is FeatureTaskRuntimeRunReport.Paused -> {
        appendLine("paused_phase: ${report.pausedPhase}")
        appendLine("pause_reason: ${report.pauseReason}")
        report.subtaskOutcome?.let { appendSubtaskOutcome(it) }
      }
      is FeatureTaskRuntimeRunReport.Decomposed -> {
        appendLine("decomposition_reason: ${report.reason}")
        appendLine("subtask_count: ${report.subtaskSpecPaths.size}")
        appendLine("parent_spec_path: ${report.parentSpecPath}")
        appendLine("decomposition_manifest_path: ${report.decompositionManifestPath}")
        report.subtaskSpecPaths.forEach { appendLine("subtask_spec_path: $it") }
        appendLine("guidance: $DECOMPOSE_GUIDANCE")
      }
      is FeatureTaskRuntimeRunReport.Completed -> {
        report.subtaskOutcome?.let { appendSubtaskOutcome(it) }
      }
    }
    incidentLines.forEach(::appendLine)
  }

private fun FeatureTaskRuntimeRunReport.statusWireValue(): String =
  when (this) {
    is FeatureTaskRuntimeRunReport.Completed -> "complete"
    is FeatureTaskRuntimeRunReport.Blocked -> "blocked"
    is FeatureTaskRuntimeRunReport.Paused -> "paused"
    is FeatureTaskRuntimeRunReport.Decomposed -> "decomposed"
  }

private fun FeatureTaskRuntimeRunReport.completedPhaseIds(): List<String> =
  when (this) {
    is FeatureTaskRuntimeRunReport.Completed -> completedPhaseIds
    is FeatureTaskRuntimeRunReport.Blocked -> completedPhaseIds
    is FeatureTaskRuntimeRunReport.Paused -> completedPhaseIds
    is FeatureTaskRuntimeRunReport.Decomposed -> completedPhaseIds
  }

private fun StringBuilder.appendSubtaskOutcome(outcome: FeatureTaskRuntimeSubtaskOutcome) {
  appendLine("subtask_outcome:")
  appendLine("  issue_key: ${outcome.issueKey}")
  appendLine("  subtask_id: ${outcome.subtaskId}")
  appendLine("  status: ${outcome.status.wireValue}")
  appendLine("  commit_sha: ${outcome.commitSha ?: "none"}")
  appendLine("  workflow_id: ${outcome.workflowId}")
  appendLine("  last_resumable_step: ${outcome.lastResumableStep}")
  outcome.finalizingAgentId?.let { appendLine("  finalizing_agent_id: $it") }
  outcome.participatingAgentIds.takeIf { it.isNotEmpty() }
    ?.let { appendLine("  participating_agent_ids: ${it.joinToString()}") }
  outcome.blockedReason?.let { appendLine("  blocked_reason: $it") }
}

internal const val DECOMPOSE_GUIDANCE: String =
  "Work the first subtask first, then continue through the ordered spec_subtask_*.md files."
