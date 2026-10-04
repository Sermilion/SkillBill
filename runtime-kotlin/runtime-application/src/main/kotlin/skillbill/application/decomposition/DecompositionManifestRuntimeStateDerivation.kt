package skillbill.application.decomposition

import skillbill.application.decomposition.model.DecompositionManifestRuntimeUpdate
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.decomposition.DecompositionManifestPayloadKeys
import skillbill.contracts.decomposition.DecompositionPlanningPayloadKeys
import skillbill.goalrunner.commitPushResultArtifact
import skillbill.goalrunner.goalContinuationOutcomeArtifact
import skillbill.workflow.decomposition.model.DecompositionExecutionModel
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.decomposition.model.DecompositionSubtask
import skillbill.workflow.decomposition.runtime.normalizedBlockedReason
import skillbill.workflow.engine.model.DurableWorkflowArtifacts
import skillbill.workflow.model.DecompositionStatus
import skillbill.workflow.model.WorkflowStatus
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.model.decompositionStatus
import skillbill.workflow.model.workflowStatus
import skillbill.workflow.model.workflowStepStatus
import skillbill.workflow.taskruntime.model.persistence.goalContinuationArtifact
import java.nio.file.Path

private val statusTrackedSteps =
  setOf("implement", "simplify", "review", "audit", "validate", "pr", "pr_description", "finish")
private val completionSteps = setOf("pr", "pr_description", "finish")
private val terminalSkippedSteps = setOf("pr", "pr_description", "finish")

internal fun DecompositionSubtask.withRuntimeFields(
  manifest: DecompositionManifest,
  update: DecompositionManifestRuntimeUpdate,
  status: String?,
): DecompositionSubtask {
  val artifacts = mergedArtifacts(update)
  val durableArtifacts = DurableWorkflowArtifacts.fromMap(artifacts)
  val nextStatus = status ?: this.status

  val terminalOutcome =
    durableArtifacts.goalContinuationOutcomeArtifact()
      ?.takeIf { nextStatus.decompositionStatus() in setOf(DecompositionStatus.COMPLETE, DecompositionStatus.BLOCKED) }
  return copy(
    status = nextStatus,
    branch =
      branchName(artifacts[DecompositionPlanningPayloadKeys.BRANCH]).ifBlank {
        when (manifest.executionModel) {
          DecompositionExecutionModel.SAME_BRANCH_COMMIT_PER_SUBTASK -> manifest.featureBranch
          DecompositionExecutionModel.STACKED_BRANCHES ->
            manifest.stackBranches.firstOrNull { it.subtaskId == id }?.branch
        } ?: branch
      },
    workflowId = update.workflowId.ifBlank { workflowId },
    commitSha = commitShaFrom(artifacts) ?: commitSha,
    blockedReason =
      blockedReasonFrom(update, nextStatus) ?: blockedReason.takeUnless {
        nextStatus.decompositionStatus() != DecompositionStatus.BLOCKED
      },
    lastResumableStep = update.currentStepId.takeIf(String::isNotBlank) ?: lastResumableStep,
    finalizingAgentId = terminalOutcome?.finalizingAgentId ?: finalizingAgentId,
    participatingAgentIds =
      terminalOutcome?.participatingAgentIds?.takeIf { it.isNotEmpty() }
        ?: participatingAgentIds,
  )
}

internal fun DecompositionManifest.currentSubtaskIdForUpdate(
  repoRoot: Path,
  update: DecompositionManifestRuntimeUpdate,
): Int? {
  val assessment = mergedArtifacts(update)["assessment"] as? Map<*, *>
  val specPath = assessment?.get(DecompositionPlanningPayloadKeys.SPEC_PATH)?.toString()?.takeIf(String::isNotBlank)
  val matchedId = specPath?.let { matchingSubtaskId(repoRoot, it) }
  return if (specPath != null) {
    matchedId
  } else {
    currentSubtaskIntent.subtaskId.takeIf { it != 0 }
  }
}

internal fun statusFromUpdate(update: DecompositionManifestRuntimeUpdate): String? {
  val stepUpdates = update.stepUpdates?.asEntries().orEmpty()
  val workflowStatus = update.workflowStatus.workflowStatus()
  return when {
    workflowStatus == WorkflowStatus.BLOCKED ||
      stepUpdates.any { it[SharedPayloadKeys.STATUS].workflowStepStatus() == WorkflowStepStatus.BLOCKED } ->
      DecompositionStatus.BLOCKED.wireValue
    prSuppressedCommitStatus(update) == DecompositionStatus.COMPLETE -> DecompositionStatus.COMPLETE.wireValue
    prSuppressedCommitStatus(update) == DecompositionStatus.BLOCKED -> DecompositionStatus.BLOCKED.wireValue
    stepUpdates.any {
      it[SharedPayloadKeys.STATUS].workflowStepStatus() == WorkflowStepStatus.SKIPPED &&
        it[SharedPayloadKeys.STEP_ID] in terminalSkippedSteps
    } -> DecompositionStatus.SKIPPED.wireValue
    workflowStatus == WorkflowStatus.COMPLETED ||
      stepUpdates.any {
        it[SharedPayloadKeys.STATUS].workflowStepStatus() == WorkflowStepStatus.COMPLETED &&
          it[SharedPayloadKeys.STEP_ID] in completionSteps
      } -> DecompositionStatus.COMPLETE.wireValue
    update.currentStepId in statusTrackedSteps ||
      stepUpdates.any { it[SharedPayloadKeys.STEP_ID] in statusTrackedSteps } ->
      DecompositionStatus.IN_PROGRESS.wireValue
    else -> null
  }
}

private fun DecompositionManifest.matchingSubtaskId(
  repoRoot: Path,
  specPath: String,
): Int? {
  val absoluteSpecPath = resolvedParentSpecPath(repoRoot, Path.of(specPath)).normalize()
  return subtasks.firstOrNull { subtask ->
    resolvedParentSpecPath(repoRoot, Path.of(subtask.specPath)).normalize() == absoluteSpecPath
  }?.id
}

internal fun mergedArtifacts(update: DecompositionManifestRuntimeUpdate): Map<String, Any?> =
  LinkedHashMap(update.existingArtifacts).apply { update.artifactsPatch?.let(::putAll) }

private fun blockedReasonFrom(
  update: DecompositionManifestRuntimeUpdate,
  status: String,
): String? =
  if (status.decompositionStatus() == DecompositionStatus.BLOCKED) {
    val artifacts = mergedArtifacts(update)
    val rawReason = artifacts[DecompositionManifestPayloadKeys.BLOCKED_REASON]?.toString()?.takeIf(String::isNotBlank)
    when {
      rawReason != null ->
        normalizedBlockedReason(
          reason = rawReason,
          category = "runtime",
          fallback = "Workflow step '${update.currentStepId.ifBlank { "unknown" }}' is blocked.",
        )
      prSuppressedCommitStatus(update) == DecompositionStatus.BLOCKED ->
        normalizedBlockedReason(
          reason = null,
          category = "git",
          fallback = "Goal-continuation commit_push completed without commit_push_result.commit_sha.",
        )
      else ->
        normalizedBlockedReason(
          reason = null,
          category = "runtime",
          fallback = "Workflow step '${update.currentStepId.ifBlank { "unknown" }}' is blocked.",
        )
    }
  } else {
    null
  }

private fun prSuppressedCommitStatus(update: DecompositionManifestRuntimeUpdate): DecompositionStatus? {
  val artifacts = mergedArtifacts(update)
  val durableArtifacts = DurableWorkflowArtifacts.fromMap(artifacts)
  val suppressPr = durableArtifacts.goalContinuationArtifact()?.suppressPr ?: return null
  val commitPushResult = durableArtifacts.commitPushResultArtifact()
  val commitPushActive =
    update.currentStepId == "commit_push" ||
      update.stepUpdates?.asEntries().orEmpty().any { it[SharedPayloadKeys.STEP_ID] == "commit_push" }
  val preCommitProjection =
    commitPushActive &&
      commitPushResult?.preCommitProjection == true &&
      commitShaFrom(artifacts) == null
  val commitPushCompleted =
    update.stepUpdates?.asEntries().orEmpty().any {
      it[SharedPayloadKeys.STEP_ID] == "commit_push" &&
        it[SharedPayloadKeys.STATUS].workflowStepStatus() == WorkflowStepStatus.COMPLETED
    }
  return when {
    !suppressPr -> null
    preCommitProjection -> DecompositionStatus.COMPLETE
    !commitPushCompleted -> null
    commitShaFrom(artifacts) != null -> DecompositionStatus.COMPLETE
    else -> DecompositionStatus.BLOCKED
  }
}

private fun commitShaFrom(artifacts: Map<String, Any?>): String? {
  val durableArtifacts = DurableWorkflowArtifacts.fromMap(artifacts)
  val fromCommitPush = durableArtifacts.commitPushResultArtifact()?.commitSha
  val fromOutcome = durableArtifacts.goalContinuationOutcomeArtifact()?.commitSha
  if (fromCommitPush != null && fromOutcome != null && fromCommitPush != fromOutcome) {
    val subtaskId =
      durableArtifacts.goalContinuationOutcomeArtifact()?.subtaskId
        ?: durableArtifacts.goalContinuationArtifact()?.subtaskId
    error(
      "Conflicting completing commit SHAs for subtask $subtaskId: " +
        "commit_push_result.commit_sha=$fromCommitPush vs goal_continuation_outcome.commit_sha=$fromOutcome.",
    )
  }
  return fromCommitPush ?: fromOutcome
}
