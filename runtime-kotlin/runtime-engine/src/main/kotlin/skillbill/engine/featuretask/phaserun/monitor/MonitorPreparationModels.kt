package skillbill.engine.featuretask.phaserun.monitor

import skillbill.engine.featuretask.model.execution.MonitorExecutionContext
import skillbill.ports.monitor.model.MonitorExpectedOwnerToken
import skillbill.ports.monitor.model.MonitorPullRequestIdentity
import skillbill.ports.monitor.model.MonitorResolvedTarget
import skillbill.ports.monitor.model.MonitorSettlement
import java.nio.file.Path

public data class MonitorPreparationRequest(
  public val callerRoot: Path,
  public val intake: String?,
  public val currentBranch: String?,
  public val expectedStaleOwner: MonitorExpectedOwnerToken? = null,
)

public enum class MonitorBlockKind {
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

public data class MonitorAttemptTarget(
  val target: MonitorResolvedTarget,
  val callerRoot: Path,
  val primaryRoot: Path,
  val worktreePath: Path? = null,
)

public sealed interface MonitorPreparation {
  public data class Prepared(
    public val context: MonitorExecutionContext,
    public val cleanup: MonitorCleanupOwnership,
  ) : MonitorPreparation

  public data class Completed(val pullRequest: MonitorPullRequestIdentity?) : MonitorPreparation

  public data class Blocked(
    public val kind: MonitorBlockKind,
    public val detail: String,
    public val pullRequest: MonitorPullRequestIdentity?,
    public val path: Path? = null,
    public val settlement: MonitorSettlement? = null,
    public val attempt: MonitorAttemptTarget? = null,
  ) : MonitorPreparation

  public data class Refused(
    public val reason: MonitorTargetRefusal,
    public val detail: String,
    public val candidateUrls: List<String> = emptyList(),
  ) : MonitorPreparation

  public data class Failed(
    public val reason: String,
    public val pullRequest: MonitorPullRequestIdentity? = null,
    public val settlement: MonitorSettlement? = null,
    public val attempt: MonitorAttemptTarget? = null,
  ) : MonitorPreparation
}
