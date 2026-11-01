package skillbill.ports.workchanges.model

data class WorkCommitRange(val fromSha: String, val toSha: String?)

data class WorkOutsideCommit(
  val sha: String,
  val parentSha: String,
  val subject: String,
  val files: List<WorkChangedFile>,
  val insertions: Int,
  val deletions: Int,
)

sealed interface WorkOutsideCommitsResult {
  data class Listed(val mergeBaseSha: String, val commits: List<WorkOutsideCommit>) : WorkOutsideCommitsResult

  data class BaseUnavailable(val reason: String) : WorkOutsideCommitsResult

  data class NotARepository(val worktreeRoot: String) : WorkOutsideCommitsResult

  data class TooMany(val limit: Int) : WorkOutsideCommitsResult

  data class Failed(val message: String) : WorkOutsideCommitsResult
}
