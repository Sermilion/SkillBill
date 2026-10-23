package skillbill.infrastructure.workflow.git.lineage

import me.tatarka.inject.annotations.Inject
import skillbill.infrastructure.workflow.process.runGitCommand
import skillbill.ports.projectstore.RepositoryLineagePort
import skillbill.ports.projectstore.model.RepositoryLineage
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import java.nio.file.Path

@Inject
class GitRepositoryLineage : RepositoryLineagePort {
  override fun lineage(repoRoot: Path): RepositoryLineage? {
    val roots = runGitCommand(repoRoot, "rev-list", "--max-parents=0", "HEAD")
    if (roots !is WorkflowGitOperationResult.Ok) return null
    val rootCommits = roots.value.lines().map(String::trim).filter(String::isNotEmpty).distinct().sorted()
    if (rootCommits.isEmpty()) return null
    val origin = runGitCommand(repoRoot, "remote", "get-url", "origin")
    val remoteUrl = (origin as? WorkflowGitOperationResult.Ok)?.value?.trim()?.takeIf(String::isNotEmpty)
    return RepositoryLineage(rootCommits, remoteUrl)
  }
}
