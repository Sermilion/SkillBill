package skillbill.infrastructure.workflow.git

import skillbill.infrastructure.workflow.git.checkpoint.GitCheckpointHistoryOperations
import skillbill.infrastructure.workflow.git.fingerprint.GitRepositoryFingerprintOperations
import skillbill.infrastructure.workflow.git.fingerprint.GitRuntimePhaseFileManifestOperations
import skillbill.infrastructure.workflow.git.fingerprint.GitSuppressionEvidenceOperations
import skillbill.infrastructure.workflow.git.goal.GitGoalSubtaskReviewOperations
import skillbill.infrastructure.workflow.git.scoped.GIT_NUL
import skillbill.infrastructure.workflow.git.scoped.GitScopedStagingOperations
import skillbill.infrastructure.workflow.git.standard.GitStandardWorkflowGitOperations
import skillbill.infrastructure.workflow.process.runGitCommand
import skillbill.ports.workflow.gitops.GoalSubtaskReviewGitOperations
import skillbill.ports.workflow.gitops.RepositoryFingerprintGitOperations
import skillbill.ports.workflow.gitops.RepositoryOwnedPathsGitOperations
import skillbill.ports.workflow.gitops.RuntimePhaseFileManifestGitOperations
import skillbill.ports.workflow.gitops.ScopedStagingGitOperations
import skillbill.ports.workflow.gitops.SuppressionEvidenceGitOperations
import skillbill.ports.workflow.gitops.WorkflowGitBranchOperations
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.WorkflowGitReleaseTagOperations
import skillbill.ports.workflow.gitops.WorkflowGitRemoteOperations
import skillbill.ports.workflow.gitops.history.CheckpointHistoryGitOperations
import skillbill.ports.workflow.gitops.history.WorkflowGitCommitHistoryOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitNameListResult
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.ports.workflow.gitops.worktree.WorkflowGitWorktreeOperations
import java.nio.file.Path

class GitWorkflowGitOperations :
  WorkflowGitOperations,
  WorkflowGitBranchOperations by GitStandardWorkflowGitOperations,
  WorkflowGitRemoteOperations by GitStandardWorkflowGitOperations,
  WorkflowGitCommitHistoryOperations by GitStandardWorkflowGitOperations,
  WorkflowGitWorktreeOperations by GitStandardWorkflowGitOperations,
  SuppressionEvidenceGitOperations by GitSuppressionEvidenceOperations,
  CheckpointHistoryGitOperations by GitCheckpointHistoryOperations,
  GoalSubtaskReviewGitOperations by GitGoalSubtaskReviewOperations,
  RepositoryFingerprintGitOperations by GitRepositoryFingerprintOperations,
  RepositoryOwnedPathsGitOperations by GitRepositoryOwnedPathsOperations,
  RuntimePhaseFileManifestGitOperations by GitRuntimePhaseFileManifestOperations,
  ScopedStagingGitOperations by GitScopedStagingOperations,
  WorkflowGitReleaseTagOperations by GitStandardWorkflowGitOperations

internal object GitRepositoryOwnedPathsOperations : RepositoryOwnedPathsGitOperations {
  override fun repositoryOwnedPaths(repoRoot: Path): WorkflowGitNameListResult {
    val untracked = runGitCommand(repoRoot, "ls-files", "--others", "--exclude-standard", "-z")
    if (untracked !is WorkflowGitOperationResult.Ok) return WorkflowGitNameListResult.Failed(untracked.error)
    val tracked = runGitCommand(repoRoot, "diff", "--name-only", "-z", "HEAD")
    if (tracked !is WorkflowGitOperationResult.Ok) return WorkflowGitNameListResult.Failed(tracked.error)
    val trackedValue = tracked.value
    return WorkflowGitNameListResult.Listed(
      (untracked.value.orEmpty() + trackedValue).split(GIT_NUL).filter(String::isNotEmpty),
    )
  }

  override fun trackedPaths(repoRoot: Path): WorkflowGitNameListResult {
    val tracked = runGitCommand(repoRoot, "ls-files", "-z")
    if (tracked !is WorkflowGitOperationResult.Ok) return WorkflowGitNameListResult.Failed(tracked.error)
    return WorkflowGitNameListResult.Listed(tracked.value.orEmpty().split(GIT_NUL).filter(String::isNotEmpty))
  }

  override fun trackedPathsAtCommit(
    repoRoot: Path,
    commitSha: String,
  ): WorkflowGitNameListResult {
    if (!isCommitSha(commitSha)) return WorkflowGitNameListResult.Failed("'$commitSha' is not a full commit SHA.")
    val verified = runGitCommand(repoRoot, "rev-parse", "--verify", "$commitSha^{commit}")
    if (verified !is WorkflowGitOperationResult.Ok) return WorkflowGitNameListResult.Failed(verified.error)
    val paths = runGitCommand(repoRoot, "ls-tree", "-r", "--name-only", "-z", verified.value.trim())
    if (paths !is WorkflowGitOperationResult.Ok) return WorkflowGitNameListResult.Failed(paths.error)
    return WorkflowGitNameListResult.Listed(
      paths.value.orEmpty().split(GIT_NUL).filter(String::isNotEmpty),
    )
  }

  override fun resolveBranchCommit(
    repoRoot: Path,
    branch: String,
  ): WorkflowGitOperationResult {
    if (branch.startsWith("-") || !runGitCommand(repoRoot, "check-ref-format", "refs/heads/$branch").ok) {
      return WorkflowGitOperationResult.Failed(error = "'$branch' is not a valid branch name.")
    }
    val local = runGitCommand(repoRoot, "rev-parse", "--verify", "refs/heads/$branch^{commit}")
    if (local is WorkflowGitOperationResult.Ok) return commitShaResult(local)
    val fetched =
      runGitCommand(
        repoRoot,
        "fetch",
        "--no-tags",
        "origin",
        "+refs/heads/$branch:refs/remotes/origin/$branch",
      )
    if (fetched !is WorkflowGitOperationResult.Ok) {
      return WorkflowGitOperationResult.Failed(
        error = "Could not fetch origin/$branch for completed-goal routing: ${fetched.error}",
      )
    }
    val remote = runGitCommand(repoRoot, "rev-parse", "--verify", "refs/remotes/origin/$branch^{commit}")
    return if (remote is WorkflowGitOperationResult.Ok) {
      commitShaResult(remote)
    } else {
      WorkflowGitOperationResult.Failed(
        error = "Branch '$branch' has no local or cached origin commit: ${remote.error}",
      )
    }
  }

  override fun indexTreeIdentity(repoRoot: Path): WorkflowGitOperationResult {
    val tree = runGitCommand(repoRoot, "write-tree")
    return if (tree is WorkflowGitOperationResult.Ok) {
      val identity = tree.value.trim()
      if (isCommitSha(identity)) {
        tree.copy(value = identity)
      } else {
        WorkflowGitOperationResult.Failed(error = "Git returned an invalid index tree identity.")
      }
    } else {
      tree
    }
  }

  private fun commitShaResult(result: WorkflowGitOperationResult.Ok): WorkflowGitOperationResult =
    result.value.trim().let { sha ->
      if (isCommitSha(sha)) {
        result.copy(value = sha)
      } else {
        WorkflowGitOperationResult.Failed(error = "Git returned an invalid commit identity.")
      }
    }

  private fun isCommitSha(value: String): Boolean = value.matches(Regex("[0-9a-fA-F]{40}|[0-9a-fA-F]{64}"))
}
