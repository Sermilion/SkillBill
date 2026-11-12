package skillbill.ports.workflow.gitops

import skillbill.ports.workflow.gitops.model.WorkflowGitNameListResult
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import java.nio.file.Path

interface RepositoryOwnedPathsGitOperations {
  fun repositoryOwnedPaths(repoRoot: Path): WorkflowGitNameListResult

  fun trackedPaths(repoRoot: Path): WorkflowGitNameListResult

  fun trackedPathsAtCommit(
    repoRoot: Path,
    commitSha: String,
  ): WorkflowGitNameListResult

  fun resolveBranchCommit(
    repoRoot: Path,
    branch: String,
  ): WorkflowGitOperationResult

  fun indexTreeIdentity(repoRoot: Path): WorkflowGitOperationResult
}
