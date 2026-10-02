package skillbill.engine.featuretask.lifecycle.remediation

import skillbill.engine.featuretask.lifecycle.continuation.GoalReviewBaseField
import skillbill.engine.featuretask.model.subtask.RemediationReconcileSnapshot
import skillbill.engine.featuretask.model.subtask.RemediationReconciliationBlocked
import skillbill.engine.featuretask.model.subtask.RemediationReconciliationCoherent
import skillbill.engine.featuretask.model.subtask.RemediationReconciliationDecision
import skillbill.engine.featuretask.model.subtask.RemediationReconciliationHeal
import skillbill.engine.featuretask.model.subtask.ResolvedReviewFixCheckpoint
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.workflow.taskruntime.model.persistence.FEATURE_TASK_RUNTIME_CHECKPOINT_REF_NAMESPACE
import skillbill.workflow.taskruntime.model.persistence.FeatureTaskRuntimeCheckpointIdentity
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import java.nio.file.Path

internal fun latestResolvedReviewFixCheckpointCommit(
  checkpoints: List<FeatureTaskRuntimeCheckpointIdentity>,
  gitOperations: WorkflowGitOperations,
  repoRoot: Path,
): ResolvedReviewFixCheckpoint? =
  checkpoints
    .asReversed()
    .firstNotNullOfOrNull { identity ->
      if (identity.loopId != FeatureTaskRuntimePhaseWorkflowDefinition.REVIEW_FIX_LOOP_ID) {
        return@firstNotNullOfOrNull null
      }
      resolveCheckpointRefCommit(gitOperations, repoRoot, identity.checkpointRef)
        ?.let { ResolvedReviewFixCheckpoint(identity, it) }
    }

fun resolvesCommit(
  gitOperations: WorkflowGitOperations,
  repoRoot: Path,
  sha: String,
): Boolean {
  val resolved = gitOperations.resolveCommit(repoRoot, sha.trim())
  return resolved is WorkflowGitOperationResult.Ok && resolved.value.orEmpty().trim().isNotBlank()
}

fun resolveCheckpointRefCommit(
  gitOperations: WorkflowGitOperations,
  repoRoot: Path,
  checkpointRef: String,
): String? {
  val resolved =
    gitOperations.resolveCheckpointRef(
      repoRoot,
      FEATURE_TASK_RUNTIME_CHECKPOINT_REF_NAMESPACE,
      checkpointRef,
    )
  if (resolved !is WorkflowGitOperationResult.Ok) return null
  return resolved.value.orEmpty().trim().takeIf(String::isNotBlank)
}

val remediationBlockedCause: (String?, Boolean, String?) -> String = { stored, storedResolves, failedRef ->
  when {
    stored != null && !storedResolves ->
      "stored remediation_base_sha '$stored' did not resolve to a commit"
    failedRef != null ->
      "checkpoint ref '$failedRef' did not resolve to a commit"
    else -> "no review_fix checkpoint ref resolved to a commit"
  }
}

val latestReviewFixCheckpointRef: (List<FeatureTaskRuntimeCheckpointIdentity>) -> String? = { checkpoints ->
  checkpoints
    .asReversed()
    .firstOrNull { it.loopId == FeatureTaskRuntimePhaseWorkflowDefinition.REVIEW_FIX_LOOP_ID }
    ?.checkpointRef
}

internal fun remediationBaseRecoveryEvidenceEntry(
  recovery: RemediationBaseRecovery,
  signal: RemediationDegradationSignal = RemediationDegradationSignal(),
): LinkedHashMap<String, Any?> {
  val failureMessage =
    recovery.failureMessageOverride ?: run {
      val headDetail = recovery.headSha?.takeIf(String::isNotBlank)?.let { " at HEAD '$it'" }.orEmpty()
      "Resume reconciled remediation_base_sha (${recovery.reason}) so the recorded base stays reachable " +
        "from branch '${recovery.goalBranch}'$headDetail."
    }
  return linkedMapOf<String, Any?>(
    "original_sha" to recovery.originalSha,
    "replacement_sha" to recovery.replacementSha,
    "repointed_field" to GoalReviewBaseField.REMEDIATION_BASE.wireValue,
    "failure_reason" to recovery.reason,
    "failure_message" to failureMessage,
    "goal_branch" to recovery.goalBranch,
  ).also { entry ->
    signal.seam?.let { entry["seam"] = it }
    signal.valueUsed?.let { entry["value_used"] = it }
    signal.valueExpected?.let { entry["value_expected"] = it }
    signal.cause?.let { entry["cause"] = it }
  }
}

internal fun decideRemediationReconciliation(
  snapshot: RemediationReconcileSnapshot,
  latestRemediationResolved: ResolvedReviewFixCheckpoint?,
  gitOperations: WorkflowGitOperations,
  repoRoot: Path,
): RemediationReconciliationDecision {
  val stored = snapshot.state.remediationBaseSha
  val storedResolves = stored?.let { resolvesCommit(gitOperations, repoRoot, it) } == true
  return when {
    latestRemediationResolved != null &&
      (stored == null || isStrictAncestor(gitOperations, repoRoot, stored, latestRemediationResolved.sha)) ->
      RemediationReconciliationHeal(latestRemediationResolved.sha)
    stored != null && storedResolves ->
      storedBranchReconciliation(gitOperations, repoRoot, stored, latestRemediationResolved)
    latestRemediationResolved != null -> RemediationReconciliationHeal(latestRemediationResolved.sha)
    stored != null && !storedResolves -> RemediationReconciliationBlocked
    else -> RemediationReconciliationBlocked
  }
}

private fun storedBranchReconciliation(
  gitOperations: WorkflowGitOperations,
  repoRoot: Path,
  stored: String,
  latestRemediationResolved: ResolvedReviewFixCheckpoint?,
): RemediationReconciliationDecision {
  val head = gitOperations.headCommitSha(repoRoot)
  if (head !is WorkflowGitOperationResult.Ok || head.value.isBlank()) return RemediationReconciliationCoherent
  val headSha = head.value.trim()
  val onBranch = gitOperations.isCommitAncestor(repoRoot, stored, headSha)
  return when {
    onBranch !is WorkflowGitOperationResult.Ok -> RemediationReconciliationCoherent
    onBranch.value == "true" -> RemediationReconciliationCoherent
    latestRemediationResolved != null -> RemediationReconciliationHeal(latestRemediationResolved.sha)
    else -> RemediationReconciliationBlocked
  }
}

private fun isStrictAncestor(
  gitOperations: WorkflowGitOperations,
  repoRoot: Path,
  ancestor: String,
  descendant: String,
): Boolean {
  if (ancestor == descendant) return false
  val ancestry = gitOperations.isCommitAncestor(repoRoot, ancestor, descendant)
  return ancestry is WorkflowGitOperationResult.Ok && ancestry.value == "true"
}
