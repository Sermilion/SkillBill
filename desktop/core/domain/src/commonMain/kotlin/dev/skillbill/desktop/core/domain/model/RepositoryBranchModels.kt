package dev.skillbill.desktop.core.domain.model

import kotlin.time.Instant

data class RepositoryBranch(val name: String, val committedAt: Instant)

data class RepositoryBranches(
  val repositoryRoot: String,
  val defaultBranch: String?,
  val local: List<RepositoryBranch>,
  val remote: List<RepositoryBranch>,
)

sealed interface RepositoryBranchesRead {
  data class Listed(val branches: RepositoryBranches) : RepositoryBranchesRead

  data class NotARepository(val path: String) : RepositoryBranchesRead

  data class Unavailable(val problem: ReadProblem) : RepositoryBranchesRead
}
