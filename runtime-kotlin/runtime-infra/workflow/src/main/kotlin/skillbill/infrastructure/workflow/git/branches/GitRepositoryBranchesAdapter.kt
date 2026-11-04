package skillbill.infrastructure.workflow.git.branches

import me.tatarka.inject.annotations.Inject
import skillbill.infrastructure.workflow.git.changes.captureList
import skillbill.infrastructure.workflow.git.changes.repositoryProblem
import skillbill.infrastructure.workflow.git.standard.gitDefaultBranch
import skillbill.infrastructure.workflow.process.runGitCommand
import skillbill.ports.workflow.gitops.RepositoryBranchesPort
import skillbill.ports.workflow.gitops.model.RepositoryBranchesResult
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import java.nio.file.Path

private const val REF_FIELD_COUNT = 3
private val REF_ARGS =
  listOf(
    "for-each-ref",
    "--sort=-committerdate",
    "--format=%(refname)%09%(symref)%09%(committerdate:unix)",
    "refs/heads",
    "refs/remotes",
  )

@Inject
class GitRepositoryBranchesAdapter : RepositoryBranchesPort {
  override fun branches(path: Path): RepositoryBranchesResult {
    repositoryProblem<RepositoryBranchesResult>(
      path,
      RepositoryBranchesResult::NotARepository,
      RepositoryBranchesResult::Failed,
    )?.let { return it }
    val root =
      when (val read = runGitCommand(path, "rev-parse", "--show-toplevel")) {
        is WorkflowGitOperationResult.Ok -> read.value.trim()
        is WorkflowGitOperationResult.Failed -> return RepositoryBranchesResult.Failed(read.error)
      }
    return captureList<RepositoryBranchesResult>(path, REF_ARGS, RepositoryBranchesResult::Failed) { text ->
      val defaultBranch = (gitDefaultBranch(path) as? WorkflowGitOperationResult.Ok)?.value
      val groups = groupRepositoryBranches(text.lineSequence().mapNotNull(::parseRef).toList(), defaultBranch)
      RepositoryBranchesResult.Listed(root, defaultBranch, groups.local, groups.remote)
    }
  }

  private fun parseRef(line: String): RawBranchRef? {
    val fields = line.split('\t')
    val committedAt = fields.getOrNull(2)?.toLongOrNull()
    return if (fields.size == REF_FIELD_COUNT && committedAt != null) {
      RawBranchRef(fields[0], fields[1], committedAt)
    } else {
      null
    }
  }
}
