package skillbill.engine.featuretask.lifecycle.checkpoint

import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeCheckpointRefPruneRequest
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitNameListResult
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.workflow.taskruntime.model.persistence.FEATURE_TASK_RUNTIME_CHECKPOINT_REF_NAMESPACE
import skillbill.workflow.taskruntime.model.persistence.FEATURE_TASK_RUNTIME_STANDALONE_SUBTASK_ID
import java.nio.file.Path

fun subtaskCommitReachableOnRemote(
  gitOperations: WorkflowGitOperations,
  repoRoot: Path,
  featureBranch: String,
  commitSha: String,
): Boolean {
  val branch = featureBranch.trim()
  val sha = commitSha.trim()
  if (branch.isBlank() || sha.isBlank()) return false
  val remoteTip = gitOperations.resolveCommit(repoRoot, "origin/$branch")
  val remoteSha =
    remoteTip.value.orEmpty().trim()
      .takeIf { remoteTip is WorkflowGitOperationResult.Ok && it.isNotBlank() }
      ?: return false
  val reachable = gitOperations.isCommitAncestor(repoRoot, sha, remoteSha)
  return reachable is WorkflowGitOperationResult.Ok &&
    reachable.value.orEmpty().trim().equals("true", ignoreCase = true)
}

fun subtaskCommitSupersededOnPublishedBranch(
  gitOperations: WorkflowGitOperations,
  repoRoot: Path,
  featureBranch: String,
  commitSha: String,
): Boolean {
  if (subtaskCommitReachableOnRemote(gitOperations, repoRoot, featureBranch, commitSha)) return false
  val branch = featureBranch.trim()
  val sha = commitSha.trim()
  if (branch.isBlank() || sha.isBlank()) return false
  val remoteTip = gitOperations.resolveCommit(repoRoot, "origin/$branch")
  if (remoteTip !is WorkflowGitOperationResult.Ok || remoteTip.value.orEmpty().isBlank()) return false
  val recordedCommit = gitOperations.resolveCommit(repoRoot, sha)
  return recordedCommit is WorkflowGitOperationResult.Ok && recordedCommit.value.orEmpty().trim().isNotBlank()
}

internal data class FeatureTaskRuntimeCheckpointRefPruneResult(
  val attempted: Boolean,
  val deletedRefCount: Int,
  val skippedReason: String? = null,
)

fun featureTaskRuntimeSubtaskCheckpointRefPrefix(
  issueKey: String,
  subtaskId: String,
): String = "${FEATURE_TASK_RUNTIME_CHECKPOINT_REF_NAMESPACE}/${issueKey.trim()}/$subtaskId/"

internal fun WorkflowGitOperations.pruneSubtaskCheckpointRefs(
  repoRoot: Path,
  request: FeatureTaskRuntimeCheckpointRefPruneRequest,
  record: (String) -> Unit,
): FeatureTaskRuntimeCheckpointRefPruneResult {
  val issueKey = request.issueKey.trim()
  val subtaskId = request.subtaskId.trim()
  if (issueKey.isBlank() || subtaskId.isBlank()) {
    return FeatureTaskRuntimeCheckpointRefPruneResult(
      attempted = false,
      deletedRefCount = 0,
      skippedReason = "issue key and subtask id are required to prune checkpoint refs",
    )
  }
  if (!request.bypassEligibilityGate) {
    pruneEligibilityResult(repoRoot, request, record)?.let { return it }
  }
  return pruneListedCheckpointRefs(
    repoRoot,
    issueKey,
    subtaskId,
    record,
  )
}

private fun WorkflowGitOperations.pruneEligibilityResult(
  repoRoot: Path,
  request: FeatureTaskRuntimeCheckpointRefPruneRequest,
  record: (String) -> Unit,
): FeatureTaskRuntimeCheckpointRefPruneResult? {
  val manifestSha = request.manifestCommitSha?.trim().orEmpty()
  if (manifestSha.isBlank()) {
    record(
      "seam=FeatureTaskRuntimeCheckpointRefPrune.pruneSubtaskCheckpointRefs " +
        "value_used='blank manifest commit_sha for ${request.issueKey}/${request.subtaskId}' " +
        "value_expected=a recorded post-push commit sha before checkpoint ref pruning " +
        "cause=pruning deferred until the goal runner records commit_sha after push",
    )
    return FeatureTaskRuntimeCheckpointRefPruneResult(
      attempted = false,
      deletedRefCount = 0,
      skippedReason = "manifest commit_sha is blank; checkpoint refs stay until the subtask commit is recorded",
    )
  }
  val featureBranch = request.featureBranch?.trim().orEmpty()
  if (featureBranch.isNotBlank() &&
    !subtaskCommitReachableOnRemote(this, repoRoot, featureBranch, manifestSha) &&
    !subtaskCommitSupersededOnPublishedBranch(this, repoRoot, featureBranch, manifestSha)
  ) {
    record(
      "seam=FeatureTaskRuntimeCheckpointRefPrune.pruneSubtaskCheckpointRefs " +
        "value_used='commit $manifestSha not reachable from origin/$featureBranch' " +
        "value_expected=the subtask commit on the published remote tip before pruning " +
        "cause=pruning deferred until push evidence exists for ${request.issueKey}/${request.subtaskId}",
    )
    return FeatureTaskRuntimeCheckpointRefPruneResult(
      attempted = false,
      deletedRefCount = 0,
      skippedReason =
        "subtask commit is not reachable on origin/$featureBranch; checkpoint refs stay until push is verified",
    )
  }
  return null
}

private fun WorkflowGitOperations.pruneListedCheckpointRefs(
  repoRoot: Path,
  issueKey: String,
  subtaskId: String,
  record: (String) -> Unit,
): FeatureTaskRuntimeCheckpointRefPruneResult {
  val prefix = "$FEATURE_TASK_RUNTIME_CHECKPOINT_REF_NAMESPACE/${issueKey.trim()}/$subtaskId/"
  val names =
    when (val listed = listCheckpointRefs(repoRoot, prefix)) {
      is WorkflowGitNameListResult.Listed -> listed.names
      is WorkflowGitNameListResult.Failed -> {
        record(
          "seam=FeatureTaskRuntimeCheckpointRefPrune.pruneSubtaskCheckpointRefs " +
            "value_used='ref listing failed for $prefix' " +
            "value_expected=the refs under the subtask namespace " +
            "cause=${listed.error}",
        )
        return FeatureTaskRuntimeCheckpointRefPruneResult(
          attempted = true,
          deletedRefCount = 0,
          skippedReason = listed.error,
        )
      }
    }
  val refs = names.distinct().sorted()
  if (refs.isEmpty()) {
    return FeatureTaskRuntimeCheckpointRefPruneResult(attempted = true, deletedRefCount = 0)
  }
  return deleteListedCheckpointRefs(repoRoot, prefix, refs, record)
}

private fun WorkflowGitOperations.deleteListedCheckpointRefs(
  repoRoot: Path,
  prefix: String,
  refs: List<String>,
  record: (String) -> Unit,
): FeatureTaskRuntimeCheckpointRefPruneResult {
  var deleted = 0
  refs.forEach { refName ->
    val removed =
      deleteCheckpointRef(
        repoRoot,
        FEATURE_TASK_RUNTIME_CHECKPOINT_REF_NAMESPACE,
        refName,
      )
    if (removed !is WorkflowGitOperationResult.Ok) {
      record(
        "seam=FeatureTaskRuntimeCheckpointRefPrune.pruneSubtaskCheckpointRefs " +
          "value_used='delete failed for $refName' " +
          "value_expected=an absent or deleted ref under $prefix " +
          "cause=${removed.error}",
      )
      return@forEach
    }
    deleted++
  }
  return FeatureTaskRuntimeCheckpointRefPruneResult(attempted = true, deletedRefCount = deleted)
}

internal fun pruneCompletedSubtaskCheckpointRefs(
  gitOperations: WorkflowGitOperations,
  repoRoot: Path,
  request: FeatureTaskRuntimeCheckpointRefPruneRequest,
  record: (String) -> Unit,
): FeatureTaskRuntimeCheckpointRefPruneResult = gitOperations.pruneSubtaskCheckpointRefs(repoRoot, request, record)

fun pruneGoalPurgeCheckpointRefs(
  gitOperations: WorkflowGitOperations,
  repoRoot: Path,
  issueKey: String,
  skipStandaloneNamespace: Boolean,
  record: (String) -> Unit,
): Int {
  val trimmedIssueKey = issueKey.trim()
  if (trimmedIssueKey.isBlank()) return 0
  val namespace = FEATURE_TASK_RUNTIME_CHECKPOINT_REF_NAMESPACE
  val issuePrefix = "$namespace/$trimmedIssueKey/"
  val names =
    when (val listed = gitOperations.listCheckpointRefs(repoRoot, issuePrefix)) {
      is WorkflowGitNameListResult.Listed -> listed.names
      is WorkflowGitNameListResult.Failed -> {
        record(
          "seam=FeatureTaskRuntimeCheckpointRefPrune.pruneGoalPurgeCheckpointRefs " +
            "value_used='ref listing failed for $issuePrefix' " +
            "value_expected=the refs under the issue namespace " +
            "cause=${listed.error}",
        )
        return 0
      }
    }
  val standaloneSegment = "/$FEATURE_TASK_RUNTIME_STANDALONE_SUBTASK_ID/"
  val refs =
    names.distinct().sorted().filter { refName ->
      !skipStandaloneNamespace || !refName.contains(standaloneSegment)
    }
  return gitOperations.deleteListedCheckpointRefsForPurge(repoRoot, refs, record)
}

private fun WorkflowGitOperations.deleteListedCheckpointRefsForPurge(
  repoRoot: Path,
  refs: List<String>,
  record: (String) -> Unit,
): Int {
  var deleted = 0
  refs.forEach { refName ->
    val removed =
      deleteCheckpointRef(
        repoRoot,
        FEATURE_TASK_RUNTIME_CHECKPOINT_REF_NAMESPACE,
        refName,
      )
    if (removed !is WorkflowGitOperationResult.Ok) {
      record(
        "seam=FeatureTaskRuntimeCheckpointRefPrune.pruneGoalPurgeCheckpointRefs " +
          "value_used='delete failed for $refName' " +
          "value_expected=an absent or deleted ref " +
          "cause=${removed.error}",
      )
      return@forEach
    }
    deleted++
  }
  return deleted
}

fun pruneResetSubtaskCheckpointRefs(
  gitOperations: WorkflowGitOperations,
  repoRoot: Path,
  issueKey: String,
  subtaskIds: Collection<Int>,
  record: (String) -> Unit,
): Int =
  subtaskIds.sumOf { subtaskId ->
    gitOperations.pruneSubtaskCheckpointRefs(
      repoRoot = repoRoot,
      request =
        FeatureTaskRuntimeCheckpointRefPruneRequest(
          issueKey = issueKey,
          subtaskId = subtaskId.toString(),
          manifestCommitSha = null,
          bypassEligibilityGate = true,
        ),
      record = record,
    ).deletedRefCount
  }
