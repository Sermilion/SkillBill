package skillbill.ports.workchanges.model

data class WorkBranchCommit(
  val sha: String,
  val parentSha: String,
  val subject: String,
  val message: String,
)

sealed interface WorkCommitTimelineResult {
  data class Listed(
    val mergeBaseSha: String,
    val headSha: String,
    val currentBranch: String?,
    val commits: List<WorkBranchCommit>,
    val reachableRecordedShas: Set<String>,
  ) : WorkCommitTimelineResult

  data class BaseUnavailable(val reason: String) : WorkCommitTimelineResult

  data class NotARepository(val worktreeRoot: String) : WorkCommitTimelineResult

  data class Failed(val message: String) : WorkCommitTimelineResult
}
