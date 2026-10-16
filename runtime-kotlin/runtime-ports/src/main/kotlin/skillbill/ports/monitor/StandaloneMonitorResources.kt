package skillbill.ports.monitor

import skillbill.ports.monitor.model.MonitorAcquireRequest
import skillbill.ports.monitor.model.MonitorAcquisition
import skillbill.ports.monitor.model.MonitorContextResolution
import skillbill.ports.monitor.model.MonitorFence
import skillbill.ports.monitor.model.MonitorFetchResult
import skillbill.ports.monitor.model.MonitorFetchedRefs
import skillbill.ports.monitor.model.MonitorResolvedTarget
import skillbill.ports.monitor.model.MonitorSettlement
import skillbill.ports.monitor.model.MonitorStaleRecovery
import skillbill.ports.monitor.model.MonitorStaleRecoveryRequest
import skillbill.ports.monitor.model.MonitorWorktree
import java.nio.file.Path

/**
 * Resource effects of one standalone monitor invocation: admission lock and ownership metadata, invocation-owned
 * refs, an owned local branch and worktree, and fenced cleanup. Every operation after [acquire] carries the
 * [MonitorFence] it was admitted with and refuses to act when the stored owner token or generation differs.
 */
interface StandaloneMonitorResources {
  fun resolveContext(callerRoot: Path): MonitorContextResolution

  /**
   * Takes the cross-process admission lock for the target and writes acquiring metadata before any ref or path
   * exists. A held lock, stale metadata, retained work, or corrupt metadata is reported as a blocked result;
   * a live owner is never superseded or signalled.
   */
  fun acquire(request: MonitorAcquireRequest): MonitorAcquisition

  /**
   * Fetches the explicit head and base from their own endpoints into invocation-owned refs without writing
   * FETCH_HEAD or touching remote configuration, and verifies the fetched objects against the pinned SHAs.
   */
  fun fetchPinned(
    fence: MonitorFence,
    target: MonitorResolvedTarget,
  ): MonitorFetchResult

  /** Creates the unique owned local branch and worktree at the verified head and validates the mapping. */
  fun createWorktree(
    fence: MonitorFence,
    target: MonitorResolvedTarget,
    refs: MonitorFetchedRefs,
  ): MonitorWorktree

  fun markPrepared(
    fence: MonitorFence,
    target: MonitorResolvedTarget,
    worktree: MonitorWorktree,
  )

  /**
   * Removes only verified owned clean work whose commits are all published, otherwise retains the worktree and
   * refs with an exact reason, then releases the fence. Never forces removal and never throws for a retained
   * outcome; a failure to settle is returned as a retained cleanup failure.
   */
  fun settle(fence: MonitorFence): MonitorSettlement

  fun recoverStale(request: MonitorStaleRecoveryRequest): MonitorStaleRecovery
}
