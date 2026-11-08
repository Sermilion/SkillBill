package skillbill.application.approval

import me.tatarka.inject.annotations.Inject
import skillbill.application.runtime.RuntimeSingleton
import skillbill.approval.model.ApprovalProviderDisposition
import skillbill.contracts.approval.ApprovalProviderRequestId
import skillbill.contracts.approval.ApprovalRequestRecord
import skillbill.error.core.catchingNonCancellation
import skillbill.ports.approval.ApprovalOwnerAuthority
import skillbill.ports.approval.ApprovalOwnerEffects
import skillbill.ports.approval.model.ApprovalDecisionResult
import skillbill.ports.approval.model.ApprovalDeliveryState
import skillbill.ports.approval.model.ApprovalOwnerKey
import skillbill.ports.approval.model.ApprovalOwnerOpenRequest
import skillbill.ports.approval.model.ApprovalOwnerOpenResult
import skillbill.ports.approval.model.ApprovalOwnerRegistration
import skillbill.ports.approval.model.ApprovalOwnerResult
import skillbill.ports.approval.model.ApprovalOwnerSession
import skillbill.ports.approval.model.ApprovalPendingWait
import skillbill.ports.approval.model.ApprovalProviderClearance
import skillbill.ports.approval.model.ApprovalRegistrationCommand
import skillbill.ports.approval.model.ApprovalRegistrationResult
import skillbill.ports.approval.model.ApprovalRequestIdentity
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.repository.RepositoryEnclosingRootPort
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

@RuntimeSingleton
@Inject
class BrokerApprovalOwnerAuthority(
  private val broker: ApprovalBroker,
  private val repositories: RepositoryEnclosingRootPort,
  private val diagnostics: RuntimeDiagnostics,
) : ApprovalOwnerAuthority {
  override fun open(
    request: ApprovalOwnerOpenRequest,
    effects: ApprovalOwnerEffects,
  ): ApprovalOwnerOpenResult {
    val repositoryIdentity =
      catchingNonCancellation { repositories.primaryRepositoryIdentity(request.checkoutRoot) }
        .getOrElse { failure ->
          val reason =
            "Operator approvals are unavailable: the repository identity of " +
              "${request.checkoutRoot} could not be resolved (${failure.message.orEmpty()})."
          diagnostics.warning(reason)
          return ApprovalOwnerOpenResult.Refused(reason)
        }
    val key = ApprovalOwnerKey(repositoryIdentity, request.runId, UUID.randomUUID().toString())
    val scope = OwnerScopeFacts(request.profileIdentity, checkoutRelativeDirectory(request.checkoutRoot))
    val pending = PendingApprovals()
    val registration = ApprovalOwnerRegistration(key, request.executionMode, request.enclosingDeadline)
    return when (val result = broker.registerOwner(registration, PendingTrackingEffects(effects, pending))) {
      is ApprovalOwnerResult.Registered ->
        ApprovalOwnerOpenResult.Opened(
          BrokerApprovalOwnerSession(broker, key, result.lease.nonce, request.provider, scope, pending),
        )
      ApprovalOwnerResult.AlreadyRegistered -> {
        val reason =
          "Operator approvals are unavailable: the process generation is already " +
            "registered."
        diagnostics.warning(reason)
        ApprovalOwnerOpenResult.Refused(reason)
      }
    }
  }

  private fun checkoutRelativeDirectory(checkoutRoot: Path): String =
    catchingNonCancellation {
      val root = repositories.canonicalPath(repositories.enclosingRepositoryRoot(checkoutRoot))
      val relative = root.relativize(repositories.canonicalPath(checkoutRoot))
      when {
        relative.startsWith("..") -> ""
        relative.toString().isEmpty() -> "."
        else -> relative.joinToString("/") { it.toString() }
      }
    }.getOrElse { failure ->
      diagnostics.warning("Approval working directory of $checkoutRoot is unknown: ${failure.message.orEmpty()}")
      ""
    }
}

private data class OwnerScopeFacts(val profileIdentity: String, val workingDirectory: String)

internal data class PendingApproval(
  val deadline: Instant,
  val title: String,
  val sequence: Long,
)

private class PendingApprovals {
  private val entries = HashMap<ApprovalRequestIdentity, PendingApproval>()
  private var sequence = 0L

  @Synchronized
  fun track(
    identity: ApprovalRequestIdentity,
    title: String,
  ) {
    sequence += 1
    entries[identity] = PendingApproval(Instant.MAX, title, sequence)
  }

  @Synchronized
  fun schedule(
    identity: ApprovalRequestIdentity,
    deadline: Instant,
  ) {
    entries.computeIfPresent(identity) { _, entry -> entry.copy(deadline = deadline) }
  }

  @Synchronized
  fun remove(identity: ApprovalRequestIdentity) {
    entries.remove(identity)
  }

  @Synchronized
  fun clear() {
    entries.clear()
  }

  @Synchronized
  fun waitView(): ApprovalPendingWait? {
    val earliest = entries.values.minOfOrNull { it.deadline } ?: return null
    return ApprovalPendingWait(entries.size, earliest, entries.values.maxBy { it.sequence }.title)
  }
}

private class PendingTrackingEffects(
  private val delegate: ApprovalOwnerEffects,
  private val pending: PendingApprovals,
) : ApprovalOwnerEffects {
  override fun deliver(settled: ApprovalRequestRecord): ApprovalDeliveryState {
    pending.remove(settled.identity())
    return delegate.deliver(settled)
  }

  override fun stop(reason: String) {
    delegate.stop(reason)
  }
}

private class BrokerApprovalOwnerSession(
  private val broker: ApprovalBroker,
  override val key: ApprovalOwnerKey,
  private val nonce: String,
  private val provider: String,
  private val scope: OwnerScopeFacts,
  private val pending: PendingApprovals,
) : ApprovalOwnerSession {
  private var released = false

  override fun register(command: ApprovalRegistrationCommand): ApprovalRegistrationResult {
    require(command.request.ownerKey() == key && command.request.provider == provider) {
      "An approval request must be registered under the process generation that " +
        "raised it."
    }
    val request =
      command.request.copy(
        authorityScope =
          command.request.authorityScope?.copy(
            profileIdentity = scope.profileIdentity,
            workingDirectory = scope.workingDirectory,
          ),
      )
    val identity = request.identity()
    pending.track(identity, request.title)
    val result =
      catchingNonCancellation { broker.register(command.copy(request = request)) }
        .onFailure { pending.remove(identity) }
        .getOrThrow()
    if (result is ApprovalRegistrationResult.Registered) {
      val deadline = parseInstant(result.request.deadlineAt)
      if (deadline != null) pending.schedule(identity, deadline)
    } else {
      pending.remove(identity)
    }
    return result
  }

  override fun resolveByProvider(
    approvalId: String,
    providerRequestId: ApprovalProviderRequestId,
    disposition: ApprovalProviderDisposition,
  ): ApprovalDecisionResult =
    broker.resolveByProvider(
      ApprovalRequestIdentity(
        approvalId,
        key.repositoryIdentity,
        key.runId,
        key.processGeneration,
        provider,
        providerRequestId,
      ),
      disposition,
    )

  override fun clearByProvider(
    approvalId: String,
    providerRequestId: ApprovalProviderRequestId,
    clearance: ApprovalProviderClearance,
  ): ApprovalDecisionResult {
    if (synchronized(this) { released }) return ApprovalDecisionResult.Missing
    val identity =
      ApprovalRequestIdentity(
        approvalId,
        key.repositoryIdentity,
        key.runId,
        key.processGeneration,
        provider,
        providerRequestId,
      )
    val result = broker.clearByProvider(identity, clearance, nonce)
    if (result is ApprovalDecisionResult.Settled || result is ApprovalDecisionResult.AlreadySettled) {
      pending.remove(identity)
    }
    return result
  }

  override fun pendingWait(): ApprovalPendingWait? = pending.waitView()

  override fun release() {
    val first = synchronized(this) { !released.also { released = true } }
    if (!first) return
    broker.releaseOwner(key, nonce)
    pending.clear()
  }
}
