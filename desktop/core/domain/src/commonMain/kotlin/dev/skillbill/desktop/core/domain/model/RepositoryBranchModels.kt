package dev.skillbill.desktop.core.domain.model

import kotlin.time.Instant

data class RepositoryBranch(val name: String, val committedAt: Instant)

data class RepositoryBranches(
  val repositoryRoot: String,
  val defaultBranch: String?,
  val local: List<RepositoryBranch>,
  val remote: List<RepositoryBranch>,
  val currentBranch: String? = null,
)

sealed interface RepositoryBranchesRead {
  data class Listed(val branches: RepositoryBranches) : RepositoryBranchesRead

  data class NotARepository(val path: String) : RepositoryBranchesRead

  data class Unavailable(val problem: ReadProblem) : RepositoryBranchesRead
}

/** A new goal or plan needs an explicit base choice when its checkout is off the default branch. */
fun RepositoryBranches.newWorkBranchProblem(baseBranch: String?): String? {
  if (!baseBranch.isNullOrBlank()) return null
  val current = currentBranch ?: return "This checkout has a detached HEAD. Switch to the default branch or choose a base branch before starting new work."
  val default = defaultBranch ?: return "Could not determine the default branch. Choose a base branch explicitly before starting new work."
  return if (current == default) null else
    "This checkout is on $current. New work normally starts from $default. Switch to $default or explicitly use $current as the base branch before starting."
}
