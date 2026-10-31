package skillbill.ports.workchanges.model

enum class WorkChangeStatus { ADDED, MODIFIED, DELETED, RENAMED, UNTRACKED }

data class WorkChangedFile(
  val path: String,
  val oldPath: String?,
  val status: WorkChangeStatus,
  val insertions: Int?,
  val deletions: Int?,
)

sealed interface WorkChangeListResult {
  data class Listed(
    val baseSha: String,
    val files: List<WorkChangedFile>,
    val insertions: Int,
    val deletions: Int,
  ) : WorkChangeListResult

  data class BaseUnavailable(val reason: String) : WorkChangeListResult

  data class NotARepository(val worktreeRoot: String) : WorkChangeListResult

  data class Failed(val message: String) : WorkChangeListResult
}

sealed interface WorkFileDiffResult {
  data class Patch(val text: String) : WorkFileDiffResult

  data object Binary : WorkFileDiffResult

  data class TooLarge(val limitBytes: Int) : WorkFileDiffResult

  data class BaseUnavailable(val reason: String) : WorkFileDiffResult

  data class Failed(val message: String) : WorkFileDiffResult
}
