package skillbill.ports.workflow.gitops

import skillbill.ports.workflow.gitops.model.WorkflowGitNameListResult
import java.nio.file.Path

interface RepositoryOwnedPathsGitOperations {
  fun repositoryOwnedPaths(repoRoot: Path): WorkflowGitNameListResult

  fun trackedPaths(repoRoot: Path): WorkflowGitNameListResult
}
