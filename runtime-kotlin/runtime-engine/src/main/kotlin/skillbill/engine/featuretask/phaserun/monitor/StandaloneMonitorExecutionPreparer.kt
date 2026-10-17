package skillbill.engine.featuretask.phaserun.monitor

import me.tatarka.inject.annotations.Inject
import skillbill.engine.featuretask.model.execution.MonitorExecutionContext
import skillbill.error.core.SkillBillRuntimeException
import skillbill.ports.agentrun.AgentRunChildDrain
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.monitor.StandaloneMonitorResources
import skillbill.ports.monitor.model.MonitorAcquireRequest
import skillbill.ports.monitor.model.MonitorAcquisition
import skillbill.ports.monitor.model.MonitorAdmissionBlock
import skillbill.ports.monitor.model.MonitorContextResolution
import skillbill.ports.monitor.model.MonitorFence
import skillbill.ports.monitor.model.MonitorFetchRejection
import skillbill.ports.monitor.model.MonitorFetchResult
import skillbill.ports.monitor.model.MonitorOutcomeKind
import skillbill.ports.monitor.model.MonitorPrimaryOutcome
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
  private val childDrain: AgentRunChildDrain,
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
    val cleanup = MonitorCleanupOwnership(fence, resources, diagnostics, shutdownHooks, childDrain)
    val prepared =
      runCatching { createWorkspace(fence, context, target, cleanup) }
        .getOrElse { failure -> setupFailure(failure, cleanup, target) }
    if (prepared !is MonitorPreparation.Prepared) {
      cleanup.settle(
        MonitorPrimaryOutcome(MonitorOutcomeKind.SETUP_FAILED, "Monitor preparation ended before handing off."),
      )
    }
    return prepared
  }

  private fun setupFailure(
    failure: Throwable,
    cleanup: MonitorCleanupOwnership,
    target: MonitorResolvedTarget,
  ): MonitorPreparation.Failed {
    val primary =
      if (failure is InterruptedException) {
        MonitorPrimaryOutcome(MonitorOutcomeKind.CANCELLED, "Monitor preparation was interrupted.")
      } else {
        MonitorPrimaryOutcome(
          MonitorOutcomeKind.SETUP_FAILED,
          monitorOutcomeDetail(failure.message, failure::class.java.simpleName),
        )
      }
    val settlement = cleanup.settle(primary)
    if (failure is InterruptedException) Thread.currentThread().interrupt()
    if (failure !is SkillBillRuntimeException) throw failure
    return MonitorPreparation.Failed(primary.detail, target.pullRequest, settlement)
  }

  private fun createWorkspace(
    fence: MonitorFence,
    context: MonitorRepositoryContext,
    target: MonitorResolvedTarget,
    cleanup: MonitorCleanupOwnership,
  ): MonitorPreparation =
    when (val fetched = resources.fetchPinned(fence, target)) {
      is MonitorFetchResult.Rejected -> {
        val kind = fetchBlockKind(fetched.reason)
        val settlement =
          cleanup.settle(MonitorPrimaryOutcome(MonitorOutcomeKind.SETUP_FAILED, "$kind: ${fetched.detail}"))
        MonitorPreparation.Blocked(kind, fetched.detail, target.pullRequest, settlement = settlement)
      }
      is MonitorFetchResult.Fetched -> {
        val worktree =
          cleanup.exclusive {
            resources.createWorktree(fence, target, fetched.refs).also {
              cleanup.recordWorktree(it.path)
              resources.markPrepared(fence, target, it)
            }
          }
        MonitorPreparation.Prepared(
          MonitorExecutionContext(
            target,
            context.callerRoot,
            context.primaryRoot,
            worktree,
            fence.ownerToken,
            fence.generation,
          ),
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
