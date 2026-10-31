package skillbill.engine.featuretask.lifecycle.subtask

import skillbill.engine.featuretask.model.subtask.FeatureTaskRuntimeSubtaskCommitIdentity
import skillbill.ports.workflow.gitops.WorkflowGitRemoteOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult

fun FeatureTaskRuntimeSubtaskFinalisation.push(
  branch: String,
  identity: FeatureTaskRuntimeSubtaskCommitIdentity,
  commitSha: String,
  withLease: Boolean,
): String? {
  if (!withLease) return pushDirect(branch, commitSha)
  record(forceWithLeaseRecord(identity, branch, commitSha))
  return pushWithLease(branch, identity, commitSha)
}

private fun FeatureTaskRuntimeSubtaskFinalisation.pushDirect(
  branch: String,
  commitSha: String,
): String? {
  val refreshed = gitOperations.refreshRemoteBranch(repoRoot, branch)
  if (refreshed !is WorkflowGitOperationResult.Ok) {
    return "the finalised subtask commit '$commitSha' could not be pushed " +
      "(remote branch could not be refreshed: ${refreshed.error})"
  }
  if (!refreshed.value.equals(WorkflowGitRemoteOperations.ABSENT_REMOTE_BRANCH, ignoreCase = true) &&
    remoteDiverged(branch, commitSha)
  ) {
    return "the finalised subtask commit '$commitSha' could not be pushed (remote branch '$branch' has moved)"
  }
  val pushed = gitOperations.pushBranch(repoRoot, branch)
  if (pushed is WorkflowGitOperationResult.Ok) return null
  return "the finalised subtask commit '$commitSha' could not be pushed (${pushed.error})"
}

private fun FeatureTaskRuntimeSubtaskFinalisation.pushWithLease(
  branch: String,
  identity: FeatureTaskRuntimeSubtaskCommitIdentity,
  commitSha: String,
): String? {
  val firstError = attemptLeasedPush(branch, identity, commitSha) ?: return null
  record(leaseRetryRecord(identity, branch, firstError))
  val secondError = attemptLeasedPush(branch, identity, commitSha) ?: return null
  record(leaseAbortRecord(identity, branch, secondError))
  return "the reopened subtask's amended commit '$commitSha' could not be pushed ($secondError)"
}

private fun FeatureTaskRuntimeSubtaskFinalisation.attemptLeasedPush(
  branch: String,
  identity: FeatureTaskRuntimeSubtaskCommitIdentity,
  commitSha: String,
): String? {
  if (refreshRemote(identity, branch).namesAbsentRemote()) {
    return pushAmended(branch, commitSha)
  }
  val pushed = gitOperations.pushBranchWithLease(repoRoot, branch)
  if (pushed is WorkflowGitOperationResult.Ok) return null
  return pushed.error
}

private fun FeatureTaskRuntimeSubtaskFinalisation.pushAmended(
  branch: String,
  commitSha: String,
): String? {
  val pushed = gitOperations.pushBranch(repoRoot, branch)
  if (pushed is WorkflowGitOperationResult.Ok) return null
  return "the reopened subtask's amended commit '$commitSha' could not be pushed (${pushed.error})"
}

private fun WorkflowGitOperationResult.namesAbsentRemote(): Boolean =
  this is WorkflowGitOperationResult.Ok &&
    value.trim() == WorkflowGitRemoteOperations.ABSENT_REMOTE_BRANCH

private fun FeatureTaskRuntimeSubtaskFinalisation.refreshRemote(
  identity: FeatureTaskRuntimeSubtaskCommitIdentity,
  branch: String,
): WorkflowGitOperationResult {
  val refreshed = gitOperations.refreshRemoteBranch(repoRoot, branch)
  when {
    refreshed.namesAbsentRemote() -> record(leaseAbsentRecord(identity, branch))
    refreshed is WorkflowGitOperationResult.Ok -> record(leaseRefreshRecord(identity, branch))
    else -> record(leaseRefreshFailedRecord(identity, branch, refreshed.error))
  }
  return refreshed
}
