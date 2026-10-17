package skillbill.engine.featuretask.phaserun.monitor

import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.ports.agentrun.AgentRunChildDrain
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.monitor.StandaloneMonitorResources
import skillbill.ports.monitor.model.MonitorFence
import skillbill.ports.monitor.model.MonitorOutcomeKind
import skillbill.ports.monitor.model.MonitorPrimaryOutcome
import skillbill.ports.monitor.model.MonitorRetentionReason
import skillbill.ports.monitor.model.MonitorSettleRequest
import skillbill.ports.monitor.model.MonitorSettlement
import skillbill.ports.process.ShutdownHookPort
import skillbill.ports.process.ShutdownHookRegistration
import java.nio.file.Path
import java.time.Duration

private val SHUTDOWN_CHILD_DRAIN: Duration = Duration.ofSeconds(2)
private const val SHUTDOWN_OUTCOME_DETAIL = "The monitor process received a termination request"
private val URL_USERINFO = Regex("(?i)(https?://)([^\\s/@]+)@")
private const val MAX_OUTCOME_DETAIL = 1000

internal fun monitorOutcomeDetail(
  text: String?,
  fallback: String,
): String =
  (text?.trim()?.takeIf(String::isNotEmpty) ?: fallback)
    .replace(URL_USERINFO, "$1<redacted>@")
    .take(MAX_OUTCOME_DETAIL)

class MonitorCleanupOwnership internal constructor(
  private val fence: MonitorFence,
  private val resources: StandaloneMonitorResources,
  private val diagnostics: RuntimeDiagnostics,
  shutdownHooks: ShutdownHookPort,
  private val childDrain: AgentRunChildDrain,
) {
  private var settlement: MonitorSettlement? = null
  private var listener: ((MonitorSettlement) -> Unit)? = null

  @Volatile private var knownPath: Path = fence.directory
  private val exitHook: ShutdownHookRegistration = shutdownHooks.register { settleOnShutdown() }

  fun <T> exclusive(block: () -> T): T = synchronized(this, block)

  fun recordWorktree(path: Path) {
    knownPath = path
  }

  @Synchronized
  fun onSettled(listener: (MonitorSettlement) -> Unit) {
    this.listener = listener
  }

  @Synchronized
  fun settle(
    outcome: MonitorPrimaryOutcome,
    removalAllowed: Boolean = true,
  ): MonitorSettlement {
    settlement?.let { return it }
    val recorded = outcome.copy(detail = monitorOutcomeDetail(outcome.detail, outcome.kind.wireValue))
    val result =
      runCatching { resources.settle(MonitorSettleRequest(fence, recorded, removalAllowed)) }.getOrElse { failure ->
        MonitorSettlement.Retained(
          MonitorRetentionReason.CLEANUP_FAILED,
          knownPath,
          monitorOutcomeDetail(failure.message, failure::class.java.simpleName),
        )
      }
    settlement = result
    runCatching { exitHook.unregister() }
    report(recorded, result)
    listener?.let { notify -> runCatching { notify(result) } }
    return result
  }

  private fun settleOnShutdown() {
    val drained = runCatching { childDrain.drain(SHUTDOWN_CHILD_DRAIN) }.getOrDefault(false)
    val detail = SHUTDOWN_OUTCOME_DETAIL + if (drained) "" else "; an owned repair child was still running"
    settle(MonitorPrimaryOutcome(MonitorOutcomeKind.CANCELLED, detail), removalAllowed = drained)
  }

  private fun report(
    outcome: MonitorPrimaryOutcome,
    result: MonitorSettlement,
  ) {
    if (outcome.kind == MonitorOutcomeKind.COMPLETED && result == MonitorSettlement.Released) return
    val disposition =
      when (result) {
        MonitorSettlement.Released -> "released"
        is MonitorSettlement.Retained ->
          "retained reason=${result.reason.wireValue} path=${result.path} detail=${result.detail}"
        is MonitorSettlement.FenceLost -> "fence_lost detail=${result.detail}"
      }
    RuntimeDiagnosticsBestEffortWarning.record(
      diagnostics,
      "seam=monitorCleanup outcome=${outcome.kind.wireValue} outcome_detail=${outcome.detail} settlement=$disposition",
    )
  }
}
