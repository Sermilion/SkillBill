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

  override fun switchToBranch(
    path: Path,
    branch: String,
    expectedCurrentBranch: String?,
  ): WorkflowGitOperationResult =
    when (val read = branches(path)) {
      is RepositoryBranchesResult.Listed ->
        when {
          read.currentBranch != expectedCurrentBranch ->
            WorkflowGitOperationResult.Failed(
              error = "The checkout branch changed. Review the current branch and try again.",
            )
          read.currentBranch == branch -> WorkflowGitOperationResult.Ok(read.repositoryRoot)
          else -> selectCheckout(path, read.repositoryRoot, branch)
        }
      is RepositoryBranchesResult.NotARepository ->
        WorkflowGitOperationResult.Failed(
          error = "${read.path} is not a git repository.",
        )
      is RepositoryBranchesResult.Failed -> WorkflowGitOperationResult.Failed(error = read.message)
    }

  private fun selectCheckout(
    path: Path,
    root: String,
    branch: String,
  ): WorkflowGitOperationResult {
    val switchArgs =
      when {
        hasRef(path, "refs/heads/$branch") -> listOf("switch", branch)
        hasRef(path, "refs/remotes/$branch") -> listOf("switch", "--track", branch)
        else -> return WorkflowGitOperationResult.Failed(error = "Branch $branch was not found. Refresh and try again.")
      }
    val listed = runGitCommand(path, "worktree", "list", "--porcelain", "-z")
    if (listed is WorkflowGitOperationResult.Failed) return listed
    val checkout =
      listed.value.split("\u0000\u0000")
        .map { it.split('\u0000') }
        .firstOrNull { "branch refs/heads/$branch" in it }
        ?.firstOrNull { it.startsWith("worktree ") }?.removePrefix("worktree ")
    if (checkout != null) {
      return when (val target = branches(Path.of(checkout))) {
        is RepositoryBranchesResult.Listed ->
          if (target.currentBranch == branch) {
            WorkflowGitOperationResult.Ok(target.repositoryRoot)
          } else {
            WorkflowGitOperationResult.Failed(error = "The $branch checkout changed. Refresh and try again.")
          }
        is RepositoryBranchesResult.NotARepository ->
          WorkflowGitOperationResult.Failed(error = "The $branch worktree is unavailable: $checkout")
        is RepositoryBranchesResult.Failed -> WorkflowGitOperationResult.Failed(error = target.message)
      }
    }
    return when (val switched = runGitCommand(path, switchArgs)) {
      is WorkflowGitOperationResult.Failed -> switched
      is WorkflowGitOperationResult.Ok -> WorkflowGitOperationResult.Ok(root)
    }
  }

  private fun hasRef(
    path: Path,
    ref: String,
  ): Boolean = runGitCommand(path, "show-ref", "--verify", "--quiet", ref) is WorkflowGitOperationResult.Ok

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
