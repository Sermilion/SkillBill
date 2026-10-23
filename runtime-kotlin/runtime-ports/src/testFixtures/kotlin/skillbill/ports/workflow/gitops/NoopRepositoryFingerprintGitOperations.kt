package skillbill.ports.workflow.gitops

import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import java.nio.file.Path

internal object NoopRepositoryFingerprintGitOperations : RepositoryFingerprintGitOperations {
  override fun repositoryFingerprint(repoRoot: Path): WorkflowGitOperationResult {
    return WorkflowGitOperationResult.Ok(value = NOOP_REPOSITORY_FINGERPRINT)
  }

  override fun scopedContentFingerprint(
    repoRoot: Path,
    paths: List<String>,
  ): WorkflowGitOperationResult = repositoryFingerprint(repoRoot)

  override fun repositoryTreeIdentity(repoRoot: Path): WorkflowGitOperationResult =
    WorkflowGitOperationResult.Ok(value = NOOP_REPOSITORY_TREE_IDENTITY)

  override fun revisionTreeIdentity(
    repoRoot: Path,
    revision: String,
  ): WorkflowGitOperationResult = repositoryTreeIdentity(repoRoot)

  override fun repositoryCheckpointFingerprint(
    repoRoot: Path,
    baseCommit: String?,
    headCommit: String,
    ownedPaths: List<String>,
  ): WorkflowGitOperationResult = repositoryFingerprint(repoRoot)
}

private const val NOOP_REPOSITORY_FINGERPRINT: String = "noop-repository-fingerprint"

private const val NOOP_REPOSITORY_TREE_IDENTITY: String = "noop-repository-tree-identity"
