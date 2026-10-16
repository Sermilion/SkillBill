package skillbill.engine.featuretask.phaserun.monitor

import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.monitor.StandaloneMonitorResources
import skillbill.ports.monitor.model.MonitorFence
import skillbill.ports.monitor.model.MonitorRetentionReason
import skillbill.ports.monitor.model.MonitorSettlement
import skillbill.ports.process.ShutdownHookPort
import skillbill.ports.process.ShutdownHookRegistration

class MonitorCleanupOwnership internal constructor(
  private val fence: MonitorFence,
  private val resources: StandaloneMonitorResources,
  private val diagnostics: RuntimeDiagnostics,
  shutdownHooks: ShutdownHookPort,
) {
  private var settlement: MonitorSettlement? = null
  private val exitHook: ShutdownHookRegistration = shutdownHooks.register { settle() }

  // Holds the settle lock so the exit hook cannot clean up while an external worktree mutation is in flight.
  fun <T> exclusive(block: () -> T): T = synchronized(this, block)

  @Synchronized
  fun settle(): MonitorSettlement {
    settlement?.let { return it }
    val result =
      runCatching { resources.settle(fence) }.getOrElse { failure ->
        MonitorSettlement.Retained(
          MonitorRetentionReason.CLEANUP_FAILED,
          null,
          failure.message ?: failure::class.java.simpleName,
        )
      }
    settlement = result
    runCatching { exitHook.unregister() }
    report(result)
    return result
  }

  private fun report(result: MonitorSettlement) {
    when (result) {
      MonitorSettlement.Released -> Unit
      is MonitorSettlement.Retained ->
        diagnostics.warning(
          "seam=monitorCleanup retained reason=${result.reason.wireValue} path=${result.path} detail=${result.detail}",
        )
      is MonitorSettlement.FenceLost ->
        diagnostics.warning("seam=monitorCleanup fence lost detail=${result.detail}")
    }
  }
}
