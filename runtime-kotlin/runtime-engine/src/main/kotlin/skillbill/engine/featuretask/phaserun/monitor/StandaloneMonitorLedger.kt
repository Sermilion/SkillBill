package skillbill.engine.featuretask.phaserun.monitor

import skillbill.engine.featuretask.model.execution.MonitorExecutionContext

internal data class StandalonePendingRepair(
  val evidence: String,
  val baselineSha: String,
  val completed: Boolean = false,
)

internal data class StandalonePublishedRepair(
  val commitSha: String,
)

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
}
