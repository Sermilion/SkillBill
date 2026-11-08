package skillbill.ports.workchanges.model

sealed interface WorkTreeSnapshotResult {
  data class Captured(
    val headSha: String,
    val untrackedPaths: List<String>,
    val stashSha: String?,
  ) : WorkTreeSnapshotResult

  data class NotARepository(val path: String) : WorkTreeSnapshotResult

  data class NoCommit(val reason: String) : WorkTreeSnapshotResult

  data class Failed(val message: String) : WorkTreeSnapshotResult
}
