package skillbill.infrastructure.workflow.monitor

import me.tatarka.inject.annotations.Inject
import skillbill.error.core.SkillBillRuntimeException
import skillbill.ports.monitor.MonitorProcessInspector
import skillbill.ports.monitor.StandaloneMonitorResources
import skillbill.ports.monitor.model.MonitorAcquireRequest
import skillbill.ports.monitor.model.MonitorAcquisition
import skillbill.ports.monitor.model.MonitorAdmissionBlock
import skillbill.ports.monitor.model.MonitorContextResolution
import skillbill.ports.monitor.model.MonitorFence
import skillbill.ports.monitor.model.MonitorFetchRejection
import skillbill.ports.monitor.model.MonitorFetchResult
import skillbill.ports.monitor.model.MonitorFetchedRefs
import skillbill.ports.monitor.model.MonitorOwnerSummary
import skillbill.ports.monitor.model.MonitorOutcomeKind
import skillbill.ports.monitor.model.MonitorOwnershipState
import skillbill.ports.monitor.model.MonitorPrimaryOutcome
import skillbill.ports.monitor.model.MonitorRecoveryRefusal
import skillbill.ports.monitor.model.MonitorRepositoryContext
import skillbill.ports.monitor.model.MonitorResolvedTarget
import skillbill.ports.monitor.model.MonitorRetentionReason
import skillbill.ports.monitor.model.MonitorSettleRequest
import skillbill.ports.monitor.model.MonitorSettlement
import skillbill.ports.monitor.model.MonitorStaleRecovery
import skillbill.ports.monitor.model.MonitorStaleRecoveryRequest
import skillbill.ports.monitor.model.MonitorWorktree
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessInspection
import skillbill.ports.taskruntime.model.isConfirmedDead
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap

private val URL_USERINFO = Regex("(?i)(https?://)([^\\s/@]+)@")
private const val MAX_FAILURE_DETAIL = 1000

private class HeldOwnership(
  val admission: HeldAdmission,
  val ownerToken: String,
  val generation: Long,
) {
  @Volatile var target: MonitorResolvedTarget? = null

  fun matches(fence: MonitorFence): Boolean = ownerToken == fence.ownerToken && generation == fence.generation
}

@Inject
class GitMonitorResources(
  private val inspector: MonitorProcessInspector,
  private val clock: Clock,
) : StandaloneMonitorResources {
  private val held = ConcurrentHashMap<Path, HeldOwnership>()
  private val cleanup = MonitorOwnedCleanup(clock)

  override fun resolveContext(callerRoot: Path): MonitorContextResolution {
    val probe = monitorGit(callerRoot, "rev-parse", "--show-toplevel", "--git-common-dir")
    val lines = probe.output.lines().filter(String::isNotBlank)
    return if (probe.ok && lines.size == 2) {
      try {
        val root = Path.of(lines[0]).toRealPath()
        val common = callerRoot.toAbsolutePath().resolve(lines[1]).normalize().toRealPath()
        val primary = if (common.fileName?.toString() == ".git") common.parent else common
        MonitorContextResolution.Resolved(MonitorRepositoryContext(root, primary, common))
      } catch (failure: IOException) {
        MonitorContextResolution.Unavailable("The caller repository paths could not be resolved: ${failure.message}")
      }
    } else {
      MonitorContextResolution.Unavailable("The caller root is not a Git working tree: ${probe.output}")
    }
  }

  override fun acquire(request: MonitorAcquireRequest): MonitorAcquisition {
    val directory = MonitorAdmissionFiles.directoryFor(request.context.commonDir, request.target.pullRequest)
    return when (val attempt = MonitorAdmissionFiles.tryLock(directory)) {
      AdmissionAttempt.Contended ->
        MonitorAcquisition.Blocked(
          MonitorAdmissionBlock.LIVE_OWNER,
          directory,
          "Another invocation holds the admission lock for ${request.target.pullRequest.url}.",
          runCatching { MonitorAdmissionFiles.read(directory) }.getOrNull()?.let(::summaryOf),
        )
      is AdmissionAttempt.Held -> admit(attempt.admission, request)
    }
  }

  private fun admit(
    admission: HeldAdmission,
    request: MonitorAcquireRequest,
  ): MonitorAcquisition {
    val directory = admission.directory
    val existing =
      try {
        MonitorAdmissionFiles.read(directory)
      } catch (failure: SkillBillRuntimeException) {
        admission.close()
        return MonitorAcquisition.Blocked(
          MonitorAdmissionBlock.CORRUPT_OWNERSHIP,
          MonitorAdmissionFiles.recordFile(directory),
          failure.message.orEmpty(),
          null,
        )
      }
    val block = existing?.let(::admissionBlock)
    if (block != null) {
      admission.close()
      val path = if (block == MonitorAdmissionBlock.RETAINED_WORK) retainedPath(existing, directory) else directory
      return MonitorAcquisition.Blocked(block, path, blockDetail(existing, block), summaryOf(existing))
    }
    val generation = (existing?.generation ?: 0) + 1
    var admitted = false
    try {
      MonitorAdmissionFiles.write(directory, acquiringRecord(request, generation))
      held[directory] = HeldOwnership(admission, request.ownerToken, generation)
      admitted = true
      return MonitorAcquisition.Acquired(MonitorFence(directory, request.ownerToken, generation))
    } finally {
      if (!admitted) admission.close()
    }
  }

  override fun fetchPinned(
    fence: MonitorFence,
    target: MonitorResolvedTarget,
  ): MonitorFetchResult {
    val ownership = requireFence(fence)
    ownership.target = target
    val record = MonitorAdmissionFiles.read(fence.directory) ?: throw fenceLost(fence)
    val namespace = monitorRefNamespace(fence.ownerToken)
    val refs = MonitorFetchedRefs("$namespace/head", "$namespace/base")
    val primary = Path.of(record.primaryRoot)
    persist(fence, record.copy(ownedRefs = listOf(refs.headRef, refs.baseRef), updatedAt = monitorNow(clock, record)))
    val head = monitorFetchBranch(primary, target.head.fetchUrl, target.head.tip.ref, refs.headRef)
    val base = monitorFetchBranch(primary, target.baseFetchUrl, target.base.ref, refs.baseRef)
    val headSha = tipOf(primary, head, refs.headRef)
    val baseSha = tipOf(primary, base, refs.baseRef)
    val rejection =
      when {
        headSha == null -> MonitorFetchRejection.HEAD_UNAVAILABLE to head.output
        headSha != target.head.tip.sha ->
          MonitorFetchRejection.HEAD_MOVED to "The head moved from ${target.head.tip.sha} to $headSha."
        baseSha == null -> MonitorFetchRejection.BASE_UNAVAILABLE to base.output
        !monitorGit(primary, "merge-base", "--is-ancestor", target.base.sha, refs.baseRef).ok ->
          MonitorFetchRejection.BASE_MOVED to "The base no longer contains ${target.base.sha}."
        else -> null
      }
    if (rejection != null) return MonitorFetchResult.Rejected(rejection.first, rejection.second)
    val pinned = MonitorAdmissionFiles.read(fence.directory) ?: throw fenceLost(fence)
    persist(
      fence,
      pinned.copy(pinnedHeadSha = target.head.tip.sha, pinnedBaseSha = baseSha, updatedAt = monitorNow(clock, pinned)),
    )
    return MonitorFetchResult.Fetched(refs)
  }

  private fun tipOf(
    primary: Path,
    fetch: GitRun,
    ref: String,
  ): String? =
    if (fetch.ok) monitorGit(primary, "rev-parse", "--verify", "$ref^{commit}").takeIf(GitRun::ok)?.output else null

  override fun createWorktree(
    fence: MonitorFence,
    target: MonitorResolvedTarget,
    refs: MonitorFetchedRefs,
  ): MonitorWorktree {
    requireFence(fence)
    val record = MonitorAdmissionFiles.read(fence.directory) ?: throw fenceLost(fence)
    val branch = monitorBranchName(fence.ownerToken)
    val path = fence.directory.resolve("worktree-${fence.ownerToken}")
    persist(
      fence,
      record.copy(executionPath = path.toString(), executionBranch = branch, updatedAt = monitorNow(clock, record)),
    )
    monitorGit(Path.of(record.primaryRoot), "worktree", "add", "-b", branch, path.toString(), refs.headRef)
      .valueOrThrow("create the owned worktree at $path")
    val head = monitorGit(path, "rev-parse", "HEAD").valueOrThrow("read the owned worktree head")
    val checkedOut = monitorGit(path, "symbolic-ref", "--short", "HEAD").valueOrThrow("read the owned branch")
    if (head != target.head.tip.sha || checkedOut != branch) {
      throw SkillBillRuntimeException(
        StandaloneMonitorFailureCode.INCOHERENT_OWNERSHIP,
        "The owned worktree $path maps to $checkedOut@$head instead of $branch@${target.head.tip.sha}.",
      )
    }
    return MonitorWorktree(path, branch)
  }

  override fun markPrepared(
    fence: MonitorFence,
    target: MonitorResolvedTarget,
    worktree: MonitorWorktree,
  ) {
    requireFence(fence)
    val record = MonitorAdmissionFiles.read(fence.directory) ?: throw fenceLost(fence)
    persist(
      fence,
      record.copy(
        state = MonitorOwnershipState.PREPARED,
        executionPath = worktree.path.toString(),
        executionBranch = worktree.branch,
        lastPublishedSha = record.pinnedHeadSha,
        updatedAt = monitorNow(clock, record),
      ),
    )
  }

  override fun settle(request: MonitorSettleRequest): MonitorSettlement {
    val fence = request.fence
    val ownership = held[fence.directory]?.takeIf { it.matches(fence) }
    val record = ownership?.let { runCatching { MonitorAdmissionFiles.read(fence.directory) }.getOrNull() }
    if (ownership == null || record == null || !record.isOwnedBy(fence)) {
      ownership?.let { release(fence, it) }
      return MonitorSettlement.FenceLost("The ownership of ${fence.directory} is no longer held by this invocation.")
    }
    return try {
      cleanup.settle(fence.directory, record, ownership.target, request.outcome, request.removalAllowed)
    } catch (failure: RuntimeException) {
      cleanupFailure(fence, record, request.outcome, failure)
    } finally {
      release(fence, ownership)
    }
  }

  private fun cleanupFailure(
    fence: MonitorFence,
    record: MonitorOwnershipRecord,
    outcome: MonitorPrimaryOutcome,
    failure: RuntimeException,
  ): MonitorSettlement.Retained {
    val path = record.executionPath?.let(Path::of) ?: fence.directory
    val detail = redactedFailureDetail(failure)
    val retained = MonitorSettlement.Retained(MonitorRetentionReason.CLEANUP_FAILED, path, detail)
    try {
      val current = MonitorAdmissionFiles.read(fence.directory)
      if (current != null && current.isOwnedBy(fence)) {
        MonitorAdmissionFiles.write(
          fence.directory,
          current.copy(
            state = MonitorOwnershipState.RETAINED,
            retention = MonitorRetentionRecord(retained.reason, detail, path.toString()),
            outcome = outcome,
            updatedAt = monitorNow(clock, current),
          ),
        )
      }
    } catch (writeFailure: RuntimeException) {
      failure.addSuppressed(writeFailure)
    }
    return retained
  }

  private fun redactedFailureDetail(failure: Throwable): String =
    (failure.message?.takeIf(String::isNotBlank) ?: failure::class.java.simpleName)
      .replace(URL_USERINFO, "$1<redacted>@")
      .take(MAX_FAILURE_DETAIL)

  private fun release(
    fence: MonitorFence,
    ownership: HeldOwnership,
  ) {
    held.remove(fence.directory, ownership)
    ownership.admission.close()
  }

  override fun recoverStale(request: MonitorStaleRecoveryRequest): MonitorStaleRecovery {
    val directory = MonitorAdmissionFiles.directoryFor(request.context.commonDir, request.pullRequest)
    if (!Files.exists(MonitorAdmissionFiles.recordFile(directory))) {
      return refusal(MonitorRecoveryRefusal.NO_OWNERSHIP, directory, "No ownership record exists.")
    }
    return when (val attempt = MonitorAdmissionFiles.tryLock(directory)) {
      AdmissionAttempt.Contended ->
        refusal(MonitorRecoveryRefusal.LIVE_OWNER, directory, "The admission lock is still held.")
      is AdmissionAttempt.Held ->
        attempt.admission.use { recover(it, request) }
    }
  }

  private fun recover(
    admission: HeldAdmission,
    request: MonitorStaleRecoveryRequest,
  ): MonitorStaleRecovery {
    val directory = admission.directory
    val record =
      try {
        MonitorAdmissionFiles.read(directory)
      } catch (failure: SkillBillRuntimeException) {
        return refusal(MonitorRecoveryRefusal.CORRUPT_OWNERSHIP, directory, failure.message.orEmpty())
      }
    val refused = record?.let { recoveryRefusal(it, request, directory) }
    return when {
      record == null -> refusal(MonitorRecoveryRefusal.NO_OWNERSHIP, directory, "No ownership record exists.")
      refused != null -> refused
      else ->
        when (val settled = cleanup.settle(directory, record, null, abandonedOutcome(record))) {
          MonitorSettlement.Released -> MonitorStaleRecovery.Recovered(record.ownerToken)
          is MonitorSettlement.Retained ->
            refusal(
              MonitorRecoveryRefusal.RETAINED_UNFINISHED,
              settled.path ?: directory,
              "(${settled.reason.wireValue}) ${settled.detail}",
            )
          is MonitorSettlement.FenceLost ->
            refusal(MonitorRecoveryRefusal.RETAINED_UNFINISHED, directory, settled.detail)
        }
    }
  }

  private fun abandonedOutcome(record: MonitorOwnershipRecord): MonitorPrimaryOutcome =
    MonitorPrimaryOutcome(
      MonitorOutcomeKind.ABANDONED,
      "owner ${record.ownerToken} (pid ${record.process.pid}) ended while ${record.state.wireValue} " +
        "without settling",
    )

  private fun recoveryRefusal(
    record: MonitorOwnershipRecord,
    request: MonitorStaleRecoveryRequest,
    directory: Path,
  ): MonitorStaleRecovery.Refused? {
    val local = runCatching { inspector.currentProcess() }.getOrNull()
    val sameHost = local != null && local.hostIdentity == record.process.hostIdentity
    val inspection = if (sameHost) inspector.inspect(record.process) else null
    return when {
      record.state == MonitorOwnershipState.RELEASED ->
        MonitorStaleRecovery.Refused(
          MonitorRecoveryRefusal.NO_OWNERSHIP,
          directory,
          "The ownership was already released.",
        )
      record.ownerToken != request.expectedOwner.value ->
        MonitorStaleRecovery.Refused(
          MonitorRecoveryRefusal.TOKEN_MISMATCH,
          directory,
          "The recorded owner token differs from the expected one.",
        )
      record.state == MonitorOwnershipState.RETAINED ->
        MonitorStaleRecovery.Refused(
          MonitorRecoveryRefusal.RETAINED_UNFINISHED,
          retainedPath(record, directory),
          retainedDetail(record),
        )
      inspection == null ->
        MonitorStaleRecovery.Refused(
          MonitorRecoveryRefusal.UNKNOWN_OWNER,
          directory,
          "The owner process belongs to another host or is unreadable.",
        )
      inspection == FeatureTaskRuntimeProcessInspection.ExactLive ->
        MonitorStaleRecovery.Refused(MonitorRecoveryRefusal.LIVE_OWNER, directory, "The owner process is still alive.")
      !inspection.isConfirmedDead() ->
        MonitorStaleRecovery.Refused(
          MonitorRecoveryRefusal.UNKNOWN_OWNER,
          directory,
          "The owner process has no positive death evidence.",
        )
      else -> null
    }
  }

  private fun refusal(
    reason: MonitorRecoveryRefusal,
    path: Path,
    detail: String,
  ): MonitorStaleRecovery = MonitorStaleRecovery.Refused(reason, path, detail)

  private fun requireFence(fence: MonitorFence): HeldOwnership =
    held[fence.directory]?.takeIf { it.matches(fence) } ?: throw fenceLost(fence)

  private fun persist(
    fence: MonitorFence,
    record: MonitorOwnershipRecord,
  ) {
    val current = MonitorAdmissionFiles.read(fence.directory)
    if (current == null || !current.isOwnedBy(fence)) throw fenceLost(fence)
    MonitorAdmissionFiles.write(fence.directory, record)
  }

  private fun fenceLost(fence: MonitorFence): SkillBillRuntimeException =
    SkillBillRuntimeException(
      StandaloneMonitorFailureCode.FENCE_LOST,
      "Standalone monitor ownership of ${fence.directory} no longer belongs to owner ${fence.ownerToken} " +
        "generation ${fence.generation}.",
    )

  private fun acquiringRecord(
    request: MonitorAcquireRequest,
    generation: Long,
  ): MonitorOwnershipRecord {
    val now = clock.instant()
    return MonitorOwnershipRecord(
      target = request.target.pullRequest,
      invocationId = request.invocationId,
      ownerToken = request.ownerToken,
      generation = generation,
      process = inspector.currentProcess(),
      callerRoot = request.context.callerRoot.toString(),
      primaryRoot = request.context.primaryRoot.toString(),
      commonDir = request.context.commonDir.toString(),
      state = MonitorOwnershipState.ACQUIRING,
      executionPath = null,
      executionBranch = null,
      ownedRefs = emptyList(),
      pinnedHeadSha = null,
      pinnedBaseSha = null,
      lastPublishedSha = null,
      acquiredAt = now,
      updatedAt = now,
      retention = null,
    )
  }

  private fun admissionBlock(record: MonitorOwnershipRecord): MonitorAdmissionBlock? =
    when (record.state) {
      MonitorOwnershipState.ACQUIRING, MonitorOwnershipState.PREPARED -> MonitorAdmissionBlock.STALE_OWNER
      MonitorOwnershipState.RETAINED -> MonitorAdmissionBlock.RETAINED_WORK
      MonitorOwnershipState.RELEASED -> null
    }

  private fun blockDetail(
    record: MonitorOwnershipRecord,
    block: MonitorAdmissionBlock,
  ): String =
    when (block) {
      MonitorAdmissionBlock.RETAINED_WORK -> retainedDetail(record)
      else ->
        "A previous invocation (owner ${record.ownerToken}) ended without releasing its ownership; " +
          "recover it explicitly with that owner token."
    }

  private fun retainedPath(
    record: MonitorOwnershipRecord,
    directory: Path,
  ): Path = record.retention?.path?.let(Path::of) ?: directory

  private fun retainedDetail(record: MonitorOwnershipRecord): String =
    "Work from a previous invocation (owner ${record.ownerToken}) is retained " +
      "(${record.retention?.reason?.wireValue ?: "no retention reason recorded"})" +
      (record.executionBranch?.let { " on owned branch $it" } ?: "") +
      ": ${record.retention?.detail ?: "no detail recorded"}" +
      (record.outcome?.let { "; previous outcome ${it.kind.wireValue}: ${it.detail}" } ?: "") +
      "; resolve or publish it manually before monitoring this pull request again."

  private fun summaryOf(record: MonitorOwnershipRecord): MonitorOwnerSummary =
    MonitorOwnerSummary(
      record.ownerToken,
      record.invocationId,
      record.process.hostIdentity,
      record.process.pid,
      record.state,
    )

  private fun MonitorOwnershipRecord.isOwnedBy(fence: MonitorFence): Boolean =
    ownerToken == fence.ownerToken && generation == fence.generation
}
