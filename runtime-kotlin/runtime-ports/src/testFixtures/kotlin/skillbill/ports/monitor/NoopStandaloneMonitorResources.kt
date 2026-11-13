package skillbill.ports.monitor

import skillbill.ports.monitor.model.MonitorAcquireRequest
import skillbill.ports.monitor.model.MonitorAcquisition
import skillbill.ports.monitor.model.MonitorContextResolution
import skillbill.ports.monitor.model.MonitorFence
import skillbill.ports.monitor.model.MonitorFetchResult
import skillbill.ports.monitor.model.MonitorFetchedRefs
import skillbill.ports.monitor.model.MonitorResolvedTarget
import skillbill.ports.monitor.model.MonitorSettleRequest
import skillbill.ports.monitor.model.MonitorSettlement
import skillbill.ports.monitor.model.MonitorStaleRecovery
import skillbill.ports.monitor.model.MonitorStaleRecoveryRequest
import skillbill.ports.monitor.model.MonitorWorktree
import java.nio.file.Path

object NoopStandaloneMonitorResources : StandaloneMonitorResources {
  override fun resolveContext(callerRoot: Path): MonitorContextResolution = MonitorContextResolution.Unavailable("noop")

  override fun acquire(request: MonitorAcquireRequest): MonitorAcquisition = error("noop monitor resources")

  override fun fetchPinned(
    fence: MonitorFence,
    target: MonitorResolvedTarget,
  ): MonitorFetchResult = error("noop monitor resources")

  override fun createWorktree(
    fence: MonitorFence,
    target: MonitorResolvedTarget,
    refs: MonitorFetchedRefs,
  ): MonitorWorktree = error("noop monitor resources")

  override fun markPrepared(
    fence: MonitorFence,
    target: MonitorResolvedTarget,
    worktree: MonitorWorktree,
  ) = Unit

  override fun adoptHead(
    fence: MonitorFence,
    headSha: String,
  ): String? = null

  override fun settle(request: MonitorSettleRequest): MonitorSettlement = MonitorSettlement.Released

  override fun deferUntilFinalize(request: MonitorSettleRequest): MonitorSettlement = MonitorSettlement.Released

  override fun finalizeDeferredRemoval(admissionDirectory: Path): MonitorSettlement = MonitorSettlement.Released

  override fun recoverStale(request: MonitorStaleRecoveryRequest): MonitorStaleRecovery =
    error("noop monitor resources")
}
