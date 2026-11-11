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
    val currentBranch =
      when (val read = runGitCommand(path, "branch", "--show-current")) {
        is WorkflowGitOperationResult.Ok -> read.value.trim().takeIf(String::isNotBlank)
        is WorkflowGitOperationResult.Failed -> return RepositoryBranchesResult.Failed(read.error)
      }
    return captureList<RepositoryBranchesResult>(path, REF_ARGS, RepositoryBranchesResult::Failed) { text ->
      val defaultBranch = (gitDefaultBranch(path) as? WorkflowGitOperationResult.Ok)?.value
      val groups = groupRepositoryBranches(text.lineSequence().mapNotNull(::parseRef).toList(), defaultBranch)
      RepositoryBranchesResult.Listed(root, defaultBranch, groups.local, groups.remote, currentBranch)
    }
  }

  override fun switchToDefaultBranch(
    path: Path,
    expectedCurrentBranch: String?,
  ): WorkflowGitOperationResult =
    when (val read = branches(path)) {
      is RepositoryBranchesResult.Listed -> {
        val defaultBranch = read.defaultBranch
        when {
          read.currentBranch != expectedCurrentBranch ->
            WorkflowGitOperationResult.Failed(
              error = "The checkout branch changed. Review the current branch and try again.",
            )
          defaultBranch == null ->
            WorkflowGitOperationResult.Failed(
              error = "Could not determine the default branch. Set origin/HEAD and try again.",
            )
          else -> runGitCommand(path, "switch", defaultBranch)
        }
      }
      is RepositoryBranchesResult.NotARepository ->
        WorkflowGitOperationResult.Failed(
          error = "${read.path} is not a git repository.",
        )
      is RepositoryBranchesResult.Failed -> WorkflowGitOperationResult.Failed(error = read.message)
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
