package skillbill.engine.featuretask.phaserun.monitor

import skillbill.engine.featuretask.model.execution.MonitorExecutionContext
import skillbill.ports.monitor.model.MonitorExpectedOwnerToken
import skillbill.ports.monitor.model.MonitorPullRequestIdentity
import skillbill.ports.monitor.model.MonitorSettlement
import java.nio.file.Path

data class MonitorPreparationRequest(
  val callerRoot: Path,
  val intake: String?,
  val currentBranch: String?,
  val expectedStaleOwner: MonitorExpectedOwnerToken? = null,
)

enum class MonitorBlockKind {
  CLOSED_UNMERGED,
  LIVE_OWNER,
  STALE_OWNER,
  RETAINED_WORK,
  CORRUPT_OWNERSHIP,
  RECOVERY_REFUSED,
  HEAD_UNAVAILABLE,
  HEAD_MOVED,
  BASE_UNAVAILABLE,
  BASE_MOVED,
}

sealed interface MonitorPreparation {
  data class Prepared(
    val context: MonitorExecutionContext,
    val cleanup: MonitorCleanupOwnership,
  ) : MonitorPreparation

  data class Completed(val pullRequest: MonitorPullRequestIdentity?) : MonitorPreparation

  data class Blocked(
    val kind: MonitorBlockKind,
    val detail: String,
    val pullRequest: MonitorPullRequestIdentity?,
    val path: Path? = null,
    val settlement: MonitorSettlement? = null,
  ) : MonitorPreparation

  data class Refused(
    val reason: MonitorTargetRefusal,
    val detail: String,
    val candidateUrls: List<String> = emptyList(),
  ) : MonitorPreparation

  data class Failed(
    val reason: String,
    val pullRequest: MonitorPullRequestIdentity? = null,
    val settlement: MonitorSettlement? = null,
  ) : MonitorPreparation
}
