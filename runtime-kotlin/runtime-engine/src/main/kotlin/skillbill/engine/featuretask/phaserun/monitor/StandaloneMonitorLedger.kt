package skillbill.engine.featuretask.phaserun.monitor

import skillbill.engine.featuretask.model.execution.MonitorExecutionContext
import java.time.Instant

internal data class StandalonePendingRepair(
  val evidence: String,
  val baselineSha: String,
  val completed: Boolean = false,
  val pullRequestFieldsDigest: String? = null,
  val startedAt: Instant? = null,
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
internal interface StandaloneMonitorRunRecords {
  val standaloneMonitorLedger: StandaloneMonitorLedger?
}

internal class StandaloneMonitorLedger(
  val execution: MonitorExecutionContext,
  private val cleanup: MonitorCleanupOwnership,
) {
  private val receipts = mutableListOf<StandalonePublishedRepair>()
  private val heads = mutableListOf(execution.target.head.tip.sha)

  val acknowledgedHeadSha: String get() = heads.last()

  val supersededHeadShas: Set<String> get() = heads.dropLast(1).toSet()

  var pendingRepair: StandalonePendingRepair? = null
    private set

  val publishedRepairs: List<StandalonePublishedRepair> get() = receipts.toList()

  val pullRequestEditRefreshAfter: Instant?
    get() = receipts.lastOrNull()?.takeIf { it.commitSha == acknowledgedHeadSha }?.pullRequestEdit?.refreshAfter

  fun <T> exclusive(block: () -> T): T = cleanup.exclusive(block)

  fun beginRepair(repair: StandalonePendingRepair) {
    pendingRepair = repair
  }

  fun completePendingRepair() {
    pendingRepair = pendingRepair?.copy(completed = true)
  }

  fun adopt(headSha: String): String? =
    if (pendingRepair != null) {
      "A repair is pending on $acknowledgedHeadSha."
    } else {
      cleanup.adoptHead(headSha) ?: run {
        heads += headSha
        null
      }
    }

  fun acknowledge(commitSha: String) {
    receipts += StandalonePublishedRepair(commitSha)
    heads += commitSha
    pendingRepair = null
  }

  fun acknowledgePullRequestEdit() {
    val edit = StandalonePullRequestEdit(receipts.size + 1, pendingRepair?.startedAt)
    receipts += StandalonePublishedRepair(acknowledgedHeadSha, edit)
    pendingRepair = null
  }
}
