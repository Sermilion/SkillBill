package skillbill.engine.featuretask.phaserun.monitor

import me.tatarka.inject.annotations.Inject
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.monitor.StandaloneMonitorResources
import skillbill.ports.monitor.model.MonitorAcquireRequest
import skillbill.ports.monitor.model.MonitorAcquisition
import skillbill.ports.monitor.model.MonitorAdmissionBlock
import skillbill.ports.monitor.model.MonitorContextResolution
import skillbill.ports.monitor.model.MonitorFence
import skillbill.ports.monitor.model.MonitorFetchRejection
import skillbill.ports.monitor.model.MonitorFetchResult
import skillbill.ports.monitor.model.MonitorRepositoryContext
import skillbill.ports.monitor.model.MonitorResolvedTarget
import skillbill.ports.monitor.model.MonitorStaleRecovery
import skillbill.ports.monitor.model.MonitorStaleRecoveryRequest
import skillbill.ports.process.ShutdownHookPort
import java.util.UUID

@Inject
class StandaloneMonitorExecutionPreparer(
  private val resolver: MonitorTargetResolver,
  private val resources: StandaloneMonitorResources,
  private val diagnostics: RuntimeDiagnostics,
  private val shutdownHooks: ShutdownHookPort,
) {
  fun prepare(request: MonitorPreparationRequest): MonitorPreparation =
    when (val parsed = parseMonitorIntake(request.intake, request.currentBranch)) {
      is MonitorIntakeParse.Refused -> MonitorPreparation.Refused(parsed.reason, parsed.detail)
      is MonitorIntakeParse.Selected ->
        when (val context = resources.resolveContext(request.callerRoot)) {
          is MonitorContextResolution.Unavailable ->
            MonitorPreparation.Refused(MonitorTargetRefusal.CALLER_REPOSITORY_UNAVAILABLE, context.reason)
          is MonitorContextResolution.Resolved ->
            resolveAndAdmit(context.context, parsed.selector, request)
        }
    }

  private fun resolveAndAdmit(
    context: MonitorRepositoryContext,
    selector: MonitorTargetSelector,
    request: MonitorPreparationRequest,
  ): MonitorPreparation =
    when (val resolution = resolver.resolve(request.callerRoot, selector)) {
      is MonitorTargetResolution.Open -> admit(context, resolution.target, request)
      is MonitorTargetResolution.Merged -> MonitorPreparation.Completed(resolution.pullRequest)
      MonitorTargetResolution.NoOpenPullRequest -> MonitorPreparation.Completed(null)
      is MonitorTargetResolution.ClosedUnmerged ->
        MonitorPreparation.Blocked(
          MonitorBlockKind.CLOSED_UNMERGED,
          "Pull request ${resolution.pullRequest.url} was closed without merging.",
          resolution.pullRequest,
        )
      is MonitorTargetResolution.Refused ->
        MonitorPreparation.Refused(resolution.reason, resolution.detail, resolution.candidateUrls)
      is MonitorTargetResolution.Failed -> MonitorPreparation.Failed(resolution.reason)
    }

  private fun admit(
    context: MonitorRepositoryContext,
    target: MonitorResolvedTarget,
    request: MonitorPreparationRequest,
  ): MonitorPreparation {
    request.expectedStaleOwner?.let { expected ->
      val recovery =
        resources.recoverStale(MonitorStaleRecoveryRequest(context, target.pullRequest, expected))
      if (recovery is MonitorStaleRecovery.Refused) {
        return MonitorPreparation.Blocked(
          MonitorBlockKind.RECOVERY_REFUSED,
          "Stale recovery was refused (${recovery.reason}): ${recovery.detail}",
          target.pullRequest,
          recovery.path,
        )
      }
    }
    val acquire = MonitorAcquireRequest(context, target, UUID.randomUUID().toString(), UUID.randomUUID().toString())
    return when (val acquisition = resources.acquire(acquire)) {
      is MonitorAcquisition.Blocked ->
        MonitorPreparation.Blocked(
          blockKind(acquisition.reason),
          acquisition.detail,
          target.pullRequest,
          acquisition.path,
        )
      is MonitorAcquisition.Acquired -> prepareOwned(acquisition.fence, context, target)
    }
  }

  private fun prepareOwned(
    fence: MonitorFence,
    context: MonitorRepositoryContext,
    target: MonitorResolvedTarget,
  ): MonitorPreparation {
    val cleanup = MonitorCleanupOwnership(fence, resources, diagnostics, shutdownHooks)
    var handedOff = false
    try {
      val prepared = createWorkspace(fence, context, target, cleanup)
      handedOff = prepared is MonitorPreparation.Prepared
      return prepared
    } finally {
      if (!handedOff) cleanup.settle()
    }
  }

  private fun createWorkspace(
    fence: MonitorFence,
    context: MonitorRepositoryContext,
    target: MonitorResolvedTarget,
    cleanup: MonitorCleanupOwnership,
  ): MonitorPreparation =
    when (val fetched = resources.fetchPinned(fence, target)) {
      is MonitorFetchResult.Rejected ->
        MonitorPreparation.Blocked(fetchBlockKind(fetched.reason), fetched.detail, target.pullRequest)
      is MonitorFetchResult.Fetched -> {
        val worktree =
          cleanup.exclusive {
            resources.createWorktree(fence, target, fetched.refs).also { resources.markPrepared(fence, target, it) }
          }
        MonitorPreparation.Prepared(
          MonitorExecutionContext(target, context.callerRoot, worktree, fence.ownerToken, fence.generation),
          cleanup,
        )
      }
    }

  private fun blockKind(reason: MonitorAdmissionBlock): MonitorBlockKind =
    when (reason) {
      MonitorAdmissionBlock.LIVE_OWNER -> MonitorBlockKind.LIVE_OWNER
      MonitorAdmissionBlock.STALE_OWNER -> MonitorBlockKind.STALE_OWNER
      MonitorAdmissionBlock.RETAINED_WORK -> MonitorBlockKind.RETAINED_WORK
      MonitorAdmissionBlock.CORRUPT_OWNERSHIP -> MonitorBlockKind.CORRUPT_OWNERSHIP
    }

  private fun fetchBlockKind(reason: MonitorFetchRejection): MonitorBlockKind =
    when (reason) {
      MonitorFetchRejection.HEAD_UNAVAILABLE -> MonitorBlockKind.HEAD_UNAVAILABLE
      MonitorFetchRejection.HEAD_MOVED -> MonitorBlockKind.HEAD_MOVED
      MonitorFetchRejection.BASE_UNAVAILABLE -> MonitorBlockKind.BASE_UNAVAILABLE
      MonitorFetchRejection.BASE_MOVED -> MonitorBlockKind.BASE_MOVED
    }
}
