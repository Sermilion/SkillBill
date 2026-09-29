package skillbill.engine.featuretask.slot

import skillbill.ports.workflow.gitops.GoalSubtaskReviewGitOperations
import skillbill.ports.workflow.gitops.RepositoryFingerprintGitOperations
import skillbill.ports.workflow.gitops.RepositoryOwnedPathsGitOperations
import skillbill.ports.workflow.gitops.RuntimePhaseFileManifestGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.ports.workflow.gitops.readiness.ReadinessTreeIdentityGitOperations
import java.nio.file.Path

internal interface PhaseRepositoryObservations :
  GoalSubtaskReviewGitOperations,
  RepositoryFingerprintGitOperations,
  RepositoryOwnedPathsGitOperations,
  RuntimePhaseFileManifestGitOperations,
  ReadinessTreeIdentityGitOperations {
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
