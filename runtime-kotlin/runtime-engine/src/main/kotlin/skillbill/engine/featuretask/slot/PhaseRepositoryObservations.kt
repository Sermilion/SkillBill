package skillbill.engine.featuretask.slot

import skillbill.ports.workflow.gitops.DefaultBranchGitOperations
import skillbill.ports.workflow.gitops.GoalSubtaskReviewGitOperations
import skillbill.ports.workflow.gitops.RepositoryFingerprintGitOperations
import skillbill.ports.workflow.gitops.RepositoryOwnedPathsGitOperations
import skillbill.ports.workflow.gitops.RuntimePhaseFileManifestGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import java.nio.file.Path

internal interface PhaseRepositoryObservations :
  DefaultBranchGitOperations,
  GoalSubtaskReviewGitOperations,
  RepositoryFingerprintGitOperations,
  RepositoryOwnedPathsGitOperations,
  RuntimePhaseFileManifestGitOperations {
  fun worktreeStatus(repoRoot: Path): WorkflowGitOperationResult

  fun headCommitSha(repoRoot: Path): WorkflowGitOperationResult

  fun resolveCommit(
    repoRoot: Path,
    revision: String,
  ): WorkflowGitOperationResult

  fun commitCountAhead(
    repoRoot: Path,
    baseRevision: String,
  ): WorkflowGitOperationResult

  fun mergeBaseWithHead(
    repoRoot: Path,
    baseRevision: String,
  ): WorkflowGitOperationResult
}
