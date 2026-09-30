package skillbill.engine.featuretask.runloop.state

import skillbill.engine.featuretask.slot.PhaseRepositoryObservations
import skillbill.ports.workflow.gitops.GoalSubtaskReviewGitOperations
import skillbill.ports.workflow.gitops.RepositoryFingerprintGitOperations
import skillbill.ports.workflow.gitops.RepositoryOwnedPathsGitOperations
import skillbill.ports.workflow.gitops.RuntimePhaseFileManifestGitOperations
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.readiness.ReadinessTreeIdentityGitOperations
import java.nio.file.Path

internal fun WorkflowGitOperations.repositoryObservations(): PhaseRepositoryObservations =
  RepositoryObservationView(this)

private class RepositoryObservationView(
  private val git: WorkflowGitOperations,
) : PhaseRepositoryObservations,
  GoalSubtaskReviewGitOperations by git,
  RepositoryFingerprintGitOperations by git,
  RepositoryOwnedPathsGitOperations by git,
  RuntimePhaseFileManifestGitOperations by git,
  ReadinessTreeIdentityGitOperations by git {
  override fun worktreeStatus(repoRoot: Path) = git.worktreeStatus(repoRoot)

  override fun headCommitSha(repoRoot: Path) = git.headCommitSha(repoRoot)

  override fun resolveCommit(
    repoRoot: Path,
    revision: String,
  ) = git.resolveCommit(repoRoot, revision)

  override fun commitCountAhead(
    repoRoot: Path,
    baseRevision: String,
  ) = git.commitCountAhead(repoRoot, baseRevision)

  override fun mergeBaseWithHead(
    repoRoot: Path,
    baseRevision: String,
  ) = git.mergeBaseWithHead(repoRoot, baseRevision)
}
