package skillbill.ports.workflow.gitops

import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationStatus
import skillbill.ports.workflow.gitops.model.WorkflowWorktreeNumstatResult
import skillbill.ports.workflow.gitops.readiness.ReadinessTreeIdentityGitOperations
import java.nio.file.Path

abstract class WorkflowGitOperationsTestBase :
  WorkflowGitRemoteOperationsDefaults(),
  WorkflowGitOperations,
  CheckpointHistoryGitOperations by UnavailableCheckpointHistoryGitOperations,
  GoalSubtaskReviewGitOperations by NoopGoalSubtaskReviewGitOperations,
  RepositoryFingerprintGitOperations by UnavailableRepositoryFingerprintGitOperations,
  ReadinessTreeIdentityGitOperations by UnavailableReadinessTreeIdentityGitOperations,
  RepositoryOwnedPathsGitOperations by UnavailableRepositoryOwnedPathsGitOperations,
  RuntimePhaseFileManifestGitOperations by NoopRuntimePhaseFileManifestGitOperations,
  ScopedStagingGitOperations by UnavailableScopedStagingGitOperations,
  SuppressionEvidenceGitOperations by NoopSuppressionEvidenceGitOperations {
  override fun worktreeNumstat(repoRoot: Path): WorkflowWorktreeNumstatResult =
    WorkflowWorktreeNumstatResult(status = WorkflowGitOperationStatus.OK, files = emptyList())

  override fun stageAll(repoRoot: Path): WorkflowGitOperationResult = WorkflowGitOperationResult.Ok(value = "")

  override fun commitCountAhead(
    repoRoot: Path,
    baseRevision: String,
  ): WorkflowGitOperationResult =
    WorkflowGitOperationResult.Failed(
      error = "This git operations implementation cannot count commits ahead of '$baseRevision'.",
    )

  override fun mergeBaseWithHead(
    repoRoot: Path,
    baseRevision: String,
  ): WorkflowGitOperationResult =
    WorkflowGitOperationResult.Failed(
      error = "This git operations implementation cannot find the merge base of '$baseRevision' and HEAD.",
    )

  override fun resetSoftToCommit(
    repoRoot: Path,
    commitSha: String,
  ): WorkflowGitOperationResult =
    WorkflowGitOperationResult.Failed(
      error = "This git operations implementation cannot soft-reset HEAD to '$commitSha'.",
    )

  override fun resetHardToCommit(
    repoRoot: Path,
    commitSha: String,
  ): WorkflowGitOperationResult =
    WorkflowGitOperationResult.Failed(
      error = "This git operations implementation cannot hard-reset HEAD to '$commitSha'.",
    )

  override fun isCommitAncestor(
    repoRoot: Path,
    ancestorSha: String,
    descendantSha: String,
  ): WorkflowGitOperationResult =
    WorkflowGitOperationResult.Failed(
      error = "This git operations implementation cannot test commit ancestry.",
    )

  override fun resolveCommit(
    repoRoot: Path,
    revision: String,
  ): WorkflowGitOperationResult =
    WorkflowGitOperationResult.Failed(
      error = "This git operations implementation cannot resolve commit '$revision'.",
    )

  override fun readHeadTrackedFile(
    repoRoot: Path,
    repoRelativePath: String,
  ): WorkflowGitOperationResult =
    WorkflowGitOperationResult.Failed(
      error = "This git operations implementation cannot read tracked file '$repoRelativePath' at HEAD.",
    )
}
