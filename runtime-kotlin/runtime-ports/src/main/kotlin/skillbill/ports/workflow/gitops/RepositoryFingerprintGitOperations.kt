package skillbill.ports.workflow.gitops

import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import java.nio.file.Path

interface RepositoryFingerprintGitOperations {
  fun repositoryFingerprint(repoRoot: Path): WorkflowGitOperationResult

  /** Digests current contents of the supplied paths independently of HEAD and index state. */
  fun scopedContentFingerprint(
    repoRoot: Path,
    paths: List<String>,
  ): WorkflowGitOperationResult

  fun repositoryTreeIdentity(repoRoot: Path): WorkflowGitOperationResult

  fun revisionTreeIdentity(
    repoRoot: Path,
    revision: String,
  ): WorkflowGitOperationResult

  fun repositoryCheckpointFingerprint(
    repoRoot: Path,
    baseCommit: String?,
    headCommit: String,
    ownedPaths: List<String>,
  ): WorkflowGitOperationResult
}
