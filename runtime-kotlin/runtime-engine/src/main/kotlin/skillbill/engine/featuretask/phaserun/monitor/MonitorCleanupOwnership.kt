package skillbill.engine.featuretask.phaserun.monitor

import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
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
        RuntimeDiagnosticsBestEffortWarning.record(
          diagnostics,
          "seam=monitorCleanup retained reason=${result.reason.wireValue} path=${result.path} detail=${result.detail}",
        )
      is MonitorSettlement.FenceLost ->
        RuntimeDiagnosticsBestEffortWarning.record(
          diagnostics,
          "seam=monitorCleanup fence lost detail=${result.detail}",
        )
    }
  }
}
