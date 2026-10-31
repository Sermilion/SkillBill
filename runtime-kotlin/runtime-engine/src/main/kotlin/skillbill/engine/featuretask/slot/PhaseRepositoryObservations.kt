package skillbill.engine.featuretask.slot

import skillbill.ports.workflow.gitops.DefaultBranchGitOperations
import skillbill.ports.workflow.gitops.GoalSubtaskReviewGitOperations
import skillbill.ports.workflow.gitops.RepositoryFingerprintGitOperations
import skillbill.ports.workflow.gitops.RepositoryOwnedPathsGitOperations
import skillbill.ports.workflow.gitops.RuntimePhaseFileManifestGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import java.nio.file.Path

public interface PhaseRepositoryObservations :
  DefaultBranchGitOperations,
  GoalSubtaskReviewGitOperations,
  RepositoryFingerprintGitOperations,
  RepositoryOwnedPathsGitOperations,
  RuntimePhaseFileManifestGitOperations {
  public fun worktreeStatus(repoRoot: Path): WorkflowGitOperationResult

  public fun headCommitSha(repoRoot: Path): WorkflowGitOperationResult

  public fun resolveCommit(
    repoRoot: Path,
    revision: String,
  ): WorkflowGitOperationResult

  public fun commitCountAhead(
    repoRoot: Path,
    baseRevision: String,
  ): WorkflowGitOperationResult

  public fun mergeBaseWithHead(
    repoRoot: Path,
    baseRevision: String,
  ): WorkflowGitOperationResult
}
