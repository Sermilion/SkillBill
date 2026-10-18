package skillbill.ports.workflow.gitops

import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import java.nio.file.Path

internal object NoopRepositoryFingerprintGitOperations : RepositoryFingerprintGitOperations {
  override fun repositoryFingerprint(repoRoot: Path): WorkflowGitOperationResult {
    return WorkflowGitOperationResult.Ok(value = NOOP_REPOSITORY_FINGERPRINT)
  }

  override fun repositoryTreeIdentity(repoRoot: Path): WorkflowGitOperationResult =
    WorkflowGitOperationResult.Ok(value = NOOP_REPOSITORY_TREE_IDENTITY)

  override fun repositoryCheckpointFingerprint(
    repoRoot: Path,
    baseCommit: String?,
    headCommit: String,
    ownedPaths: List<String>,
  ): WorkflowGitOperationResult = repositoryFingerprint(repoRoot)
}

private const val NOOP_REPOSITORY_FINGERPRINT: String = "noop-repository-fingerprint"

private const val NOOP_REPOSITORY_TREE_IDENTITY: String = "noop-repository-tree-identity"
