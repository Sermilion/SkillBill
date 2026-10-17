package skillbill.ports.monitor.model

data class MonitorPublicationRequest(
  val worktree: MonitorWorktree,
  val head: MonitorPullRequestHead,
  val expectedRemoteSha: String,
  val repairSha: String,
)

enum class MonitorPublicationRefusal { GUARD_REFUSED, REMOTE_REJECTED, LOCAL_STATE_MISMATCH }

sealed interface MonitorPublicationResult {
  data class Acknowledged(val remoteSha: String) : MonitorPublicationResult

  data class Rejected(
    val reason: MonitorPublicationRefusal,
    val detail: String,
  ) : MonitorPublicationResult

  data class Uncertain(val detail: String) : MonitorPublicationResult
}
