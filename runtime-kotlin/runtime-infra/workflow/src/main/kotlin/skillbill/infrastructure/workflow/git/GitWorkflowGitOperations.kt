package skillbill.infrastructure.workflow.git

import skillbill.infrastructure.workflow.git.checkpoint.GitCheckpointHistoryOperations
import skillbill.infrastructure.workflow.git.goal.GitGoalSubtaskReviewOperations
import skillbill.infrastructure.workflow.git.scoped.GIT_NUL
import skillbill.infrastructure.workflow.git.scoped.GitScopedStagingOperations
import skillbill.infrastructure.workflow.git.standard.GitStandardWorkflowGitOperations
import skillbill.infrastructure.workflow.process.runGitCommand
import skillbill.ports.workflow.gitops.CheckpointHistoryGitOperations
import skillbill.ports.workflow.gitops.GoalSubtaskReviewGitOperations
import skillbill.ports.workflow.gitops.RepositoryFingerprintGitOperations
import skillbill.ports.workflow.gitops.RepositoryOwnedPathsGitOperations
import skillbill.ports.workflow.gitops.RuntimePhaseFileManifestGitOperations
import skillbill.ports.workflow.gitops.ScopedStagingGitOperations
import skillbill.ports.workflow.gitops.SuppressionEvidenceGitOperations
import skillbill.ports.workflow.gitops.WorkflowGitBranchOperations
import skillbill.ports.workflow.gitops.WorkflowGitCommitHistoryOperations
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.WorkflowGitReleaseTagOperations
import skillbill.ports.workflow.gitops.WorkflowGitRemoteOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitNameListResult
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.ports.workflow.gitops.readiness.ReadinessTreeIdentityGitOperations
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
  ReadinessTreeIdentityGitOperations by GitReadinessTreeIdentityOperations,
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
}
