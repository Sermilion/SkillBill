package skillbill.ports.workflow.gitops.model

data class RepositoryBranchRef(
  val name: String,
  val committedAtEpochSeconds: Long,
)

sealed interface RepositoryBranchesResult {
  data class Listed(
    val repositoryRoot: String,
    val defaultBranch: String?,
    val local: List<RepositoryBranchRef>,
    val remote: List<RepositoryBranchRef>,
  ) : RepositoryBranchesResult

  data class NotARepository(val path: String) : RepositoryBranchesResult

  data class Failed(val message: String) : RepositoryBranchesResult
}
