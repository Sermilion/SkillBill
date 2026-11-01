package skillbill.engine.featuretask.phaserun.monitor

import skillbill.engine.featuretask.model.execution.MonitorExecutionContext
import java.time.Instant

public data class StandalonePendingRepair(
  public val evidence: String,
  public val baselineSha: String,
  public val completed: Boolean = false,
  public val pullRequestFieldsDigest: String? = null,
  public val startedAt: Instant? = null,
)

internal data class StandalonePublishedRepair(
  val commitSha: String,
  val pullRequestEdit: StandalonePullRequestEdit? = null,
) {
  val repairKey: String
    get() = pullRequestEdit?.let { "$commitSha+pull-request-edit:${it.repairNumber}" } ?: commitSha
}

internal data class StandalonePullRequestEdit(val repairNumber: Int, val refreshAfter: Instant?)

/** Run records that carry the invocation's monitor ledger; only the prepared standalone entry supplies one. */
public interface StandaloneMonitorRunRecords {
  public val standaloneMonitorLedger: StandaloneMonitorLedger?
}

public class StandaloneMonitorLedger internal constructor(
  public val execution: MonitorExecutionContext,
  public val cleanup: MonitorCleanupOwnership,
) {
  private val receipts = mutableListOf<StandalonePublishedRepair>()
  private val heads = mutableListOf(execution.target.head.tip.sha)

  internal val acknowledgedHeadSha: String get() = heads.last()

  internal val supersededHeadShas: Set<String> get() = heads.dropLast(1).toSet()

  internal var pendingRepair: StandalonePendingRepair? = null
    private set

  internal val publishedRepairs: List<StandalonePublishedRepair> get() = receipts.toList()

  internal val pullRequestEditRefreshAfter: Instant?
    get() = receipts.lastOrNull()?.takeIf { it.commitSha == acknowledgedHeadSha }?.pullRequestEdit?.refreshAfter

  internal fun <T> exclusive(block: () -> T): T = cleanup.exclusive(block)

  internal fun beginRepair(repair: StandalonePendingRepair) {
    pendingRepair = repair
  }

  internal fun completePendingRepair() {
    pendingRepair = pendingRepair?.copy(completed = true)
  }

  internal fun adopt(headSha: String): String? =
    if (pendingRepair != null) {
      "A repair is pending on $acknowledgedHeadSha."
    } else {
      cleanup.adoptHead(headSha) ?: run {
        heads += headSha
        null
      }
    }

  internal fun acknowledge(commitSha: String) {
    receipts += StandalonePublishedRepair(commitSha)
    heads += commitSha
    pendingRepair = null
  }

  internal fun acknowledgePullRequestEdit() {
    val edit = StandalonePullRequestEdit(receipts.size + 1, pendingRepair?.startedAt)
    receipts += StandalonePublishedRepair(acknowledgedHeadSha, edit)
    pendingRepair = null
  }
}
