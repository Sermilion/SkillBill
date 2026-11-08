package skillbill.application.approval

import me.tatarka.inject.annotations.Inject
import skillbill.application.runtime.RuntimeSingleton
import skillbill.approval.model.ApprovalGrantFacts
import skillbill.approval.model.ApprovalGrantRefusal
import skillbill.approval.model.ApprovalProviderDisposition
import skillbill.approval.model.ApprovalTerminalCause
import skillbill.approval.model.DENY_OPTION_KIND
import skillbill.approval.model.ONE_TIME_ALLOW_OPTION_KIND
import skillbill.approval.model.durableRefusal
import skillbill.approval.model.matches
import skillbill.approval.model.onceRefusal
import skillbill.approval.model.settleFor
import skillbill.approval.model.toRule
import skillbill.contracts.approval.ApprovalDecisionSource
import skillbill.contracts.approval.ApprovalOption
import skillbill.contracts.approval.ApprovalRequestRecord
import skillbill.contracts.approval.ApprovalRuleRecord
import skillbill.contracts.approval.ApprovalState
import skillbill.contracts.approval.OPERATOR_APPROVAL_CONTRACT_VERSION
import skillbill.error.approval.ApprovalFailureCode
import skillbill.error.core.SkillBillRuntimeException
import skillbill.ports.UnitOfWork
import skillbill.ports.approval.ApprovalDeadlineScheduler
import skillbill.ports.approval.ApprovalOwnerEffects
import skillbill.ports.approval.model.ApprovalConsumerLease
import skillbill.ports.approval.model.ApprovalConsumerResult
import skillbill.ports.approval.model.ApprovalDecisionResult
import skillbill.ports.approval.model.ApprovalOwnerKey
import skillbill.ports.approval.model.ApprovalOwnerLease
import skillbill.ports.approval.model.ApprovalOwnerRegistration
import skillbill.ports.approval.model.ApprovalOwnerResult
import skillbill.ports.approval.model.ApprovalProviderClearance
import skillbill.ports.approval.model.ApprovalRegistration
import skillbill.ports.approval.model.ApprovalRegistrationCommand
import skillbill.ports.approval.model.ApprovalRegistrationResult
import skillbill.ports.approval.model.ApprovalRequestIdentity
import skillbill.ports.approval.model.ApprovalStoredRequest
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.RuntimeDiagnostics
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

@RuntimeSingleton
@Inject
class ApprovalBroker(
  private val database: DatabaseSessionFactory,
  private val clock: Clock,
  private val diagnostics: RuntimeDiagnostics,
  scheduler: ApprovalDeadlineScheduler,
) {
  private val lifecycle = ApprovalLifecycle(database, clock, diagnostics, scheduler)

  fun registerOwner(
    registration: ApprovalOwnerRegistration,
    effects: ApprovalOwnerEffects,
  ): ApprovalOwnerResult {
    val now = clock.instant()
    val lease =
      ApprovalOwnerLease(
        key = registration.key,
        nonce = UUID.randomUUID().toString(),
        executionMode = registration.executionMode,
        enclosingDeadline = registration.enclosingDeadline?.toString(),
        createdAt = now.toString(),
        expiresAt = now.plus(APPROVAL_OWNER_LEASE).toString(),
        endedAt = null,
      )
    val stored =
      database.transaction { unit ->
        (unit.approvals.owner(registration.key) == null).also { absent -> if (absent) unit.approvals.saveOwner(lease) }
      }
    if (!stored) return ApprovalOwnerResult.AlreadyRegistered
    lifecycle.attach(lease.key, lease.nonce, effects)
    return ApprovalOwnerResult.Registered(lease)
  }

  fun releaseOwner(
    key: ApprovalOwnerKey,
    nonce: String,
  ): Boolean {
    val lease = database.read { unit -> unit.approvals.owner(key) }
    if (lease == null || lease.nonce != nonce) return false
    return lifecycle.endOwner(key, ApprovalTerminalCause.PROCESS_END, deliverCancels = true, stopReason = null)
  }

  fun registerConsumer(
    repositoryIdentity: String,
    consumerId: String,
  ): ApprovalConsumerResult.Registered {
    val now = clock.instant()
    val lease =
      ApprovalConsumerLease(
        repositoryIdentity = repositoryIdentity,
        consumerId = consumerId,
        nonce = UUID.randomUUID().toString(),
        createdAt = now.toString(),
        expiresAt = now.plus(CONSUMER_LEASE).toString(),
        endedAt = null,
      )
    database.transaction { unit -> unit.approvals.saveConsumer(lease) }
    return ApprovalConsumerResult.Registered(lease)
  }

  fun renewConsumer(
    repositoryIdentity: String,
    nonce: String,
  ): ApprovalConsumerResult {
    val now = clock.instant()
    return database.transaction { unit ->
      val lease = unit.approvals.consumer(repositoryIdentity)?.takeIf { it.isLive(now, nonce) }
      if (lease == null) {
        ApprovalConsumerResult.Stale
      } else {
        val renewed = lease.copy(expiresAt = now.plus(CONSUMER_LEASE).toString())
        unit.approvals.saveConsumer(renewed)
        ApprovalConsumerResult.Registered(renewed)
      }
    }
  }

  fun releaseConsumer(
    repositoryIdentity: String,
    nonce: String,
  ): Boolean {
    val now = clock.instant()
    val ended =
      database.transaction { unit ->
        val lease = unit.approvals.consumer(repositoryIdentity)?.takeIf { it.endedAt == null && it.nonce == nonce }
        if (lease != null) unit.approvals.saveConsumer(lease.copy(endedAt = now.toString()))
        lease != null
      }
    if (ended) lifecycle.cancelRepository(repositoryIdentity)
    return ended
  }

  fun register(command: ApprovalRegistrationCommand): ApprovalRegistrationResult {
    val request = command.request
    if (request.contractVersion != OPERATOR_APPROVAL_CONTRACT_VERSION) {
      diagnostics.warning("Unsupported operator approval contract version ${request.contractVersion}.")
      throw SkillBillRuntimeException(
        ApprovalFailureCode.APPROVAL_VERSION_MISMATCH,
        "Unsupported operator approval contract version ${request.contractVersion}.",
      )
    }
    val now = clock.instant()
    return when (val outcome = database.transaction { unit -> registerLocked(unit, command, now) }) {
      is RegistrationOutcome.Pending -> {
        lifecycle.schedule(outcome.request.identity(), outcome.deadline)
        ApprovalRegistrationResult.Registered(outcome.request)
      }
      is RegistrationOutcome.Existing -> outcome.result
      is RegistrationOutcome.AutoAllowed -> {
        lifecycle.afterSettled(outcome.request, deliver = true)
        ApprovalRegistrationResult.AutoAllowed(outcome.request)
      }
      is RegistrationOutcome.Terminal -> {
        diagnostics.warning("Approval request settled as ${outcome.request.state.wireValue} at registration.")
        lifecycle.afterSettled(outcome.request, deliver = true)
        ApprovalRegistrationResult.Refused(outcome.request)
      }
      RegistrationOutcome.Conflict -> {
        diagnostics.warning("Approval request identity was reused with changed provider content.")
        lifecycle.endOwner(
          request.ownerKey(),
          ApprovalTerminalCause.PROCESS_END,
          deliverCancels = true,
          stopReason = CONFLICT_STOP_REASON,
        )
        ApprovalRegistrationResult.IdentityConflict
      }
    }
  }

  fun decide(
    identity: ApprovalRequestIdentity,
    consumerNonce: String,
    selectedOptionId: String,
    alwaysAllow: Boolean,
  ): ApprovalDecisionResult {
    val now = clock.instant()
    val result: ApprovalDecisionResult =
      database.transaction { unit ->
        val stored = unit.approvals.find(identity)
        val owner = lifecycle.durableOwner(unit, identity.ownerKey(), now)
        val consumer = unit.approvals.consumer(identity.repositoryIdentity)
        when {
          stored == null -> ApprovalDecisionResult.Missing
          stored.record.state != ApprovalState.PENDING -> ApprovalDecisionResult.AlreadySettled(stored.record)
          owner == null || consumer?.isLive(now, consumerNonce) != true -> ApprovalDecisionResult.NotLive(stored.record)
          else -> decideLocked(unit, stored, owner, DecisionInput(selectedOptionId, alwaysAllow), now)
        }
      }
    if (result is ApprovalDecisionResult.Settled) lifecycle.afterSettled(result.request, deliver = true)
    return result
  }

  fun expire(identity: ApprovalRequestIdentity): ApprovalDecisionResult =
    lifecycle.settleTerminal(identity, ApprovalTerminalCause.EXPIRY, deliver = true)

  fun dismiss(identity: ApprovalRequestIdentity): ApprovalDecisionResult =
    lifecycle.settleTerminal(identity, ApprovalTerminalCause.DISMISSAL, deliver = true)

  fun resolveByProvider(
    identity: ApprovalRequestIdentity,
    disposition: ApprovalProviderDisposition,
  ): ApprovalDecisionResult {
    val cause =
      when (disposition) {
        ApprovalProviderDisposition.DENIED -> ApprovalTerminalCause.PROVIDER_DENIAL
        ApprovalProviderDisposition.INTERACTION_REQUIRED -> ApprovalTerminalCause.UNSUPPORTED_OWNER
        ApprovalProviderDisposition.NONE -> error("A provider resolution needs a denial or required interaction.")
      }
    return lifecycle.settleTerminal(identity, cause, deliver = true)
  }

  fun clearByProvider(
    identity: ApprovalRequestIdentity,
    clearance: ApprovalProviderClearance,
    nonce: String,
  ): ApprovalDecisionResult = lifecycle.clearByProvider(identity, clearance, nonce)

  fun listRules(repositoryIdentity: String): List<ApprovalRuleRecord> =
    database.read { unit -> unit.approvals.activeRules(repositoryIdentity) }

  fun revokeRule(
    ruleId: String,
    repositoryIdentity: String,
  ): Boolean {
    val revokedAt = clock.instant().toString()
    return database.transaction { unit -> unit.approvals.revokeRule(ruleId, repositoryIdentity, revokedAt) }
  }

  private fun registerLocked(
    unit: UnitOfWork,
    command: ApprovalRegistrationCommand,
    now: Instant,
  ): RegistrationOutcome {
    val owner = lifecycle.localOwner(unit, command.request.ownerKey(), now)
    val consumerLive = unit.approvals.consumer(command.request.repositoryIdentity)?.isLive(now, null) == true
    val deadline = requestDeadline(command, now, owner)
    val pending = pendingRecord(command, now, deadline)
    val facts =
      ApprovalGrantFacts(
        command.actionClass,
        command.writeCapable,
        inputComplete = command.canonicalInput != null,
        providerDisposition = command.providerDisposition,
      )
    val live = owner != null && consumerLive
    return when (val registration = unit.approvals.register(pending, facts)) {
      ApprovalRegistration.IdentityConflict -> RegistrationOutcome.Conflict
      is ApprovalRegistration.Existing -> RegistrationOutcome.Existing(existingResult(registration.stored, live))
      ApprovalRegistration.Created -> createdOutcome(unit, CreatedRequest(pending, facts, deadline, owner, live), now)
    }
  }

  private fun createdOutcome(
    unit: UnitOfWork,
    created: CreatedRequest,
    now: Instant,
  ): RegistrationOutcome {
    val denied = created.facts.providerDisposition == ApprovalProviderDisposition.DENIED
    val cause =
      when {
        !created.live -> ApprovalTerminalCause.UNSUPPORTED_OWNER
        created.deadline <= now -> ApprovalTerminalCause.EXPIRY
        denied -> ApprovalTerminalCause.PROVIDER_DENIAL
        else -> null
      }
    val pending = created.pending
    val terminal = cause?.let { pending.settleFor(it, created.deadline, now) }
    val auto = if (cause == null) created.owner?.let { storedRuleSettlement(unit, created, it, now) } else null
    return when {
      terminal != null && unit.approvals.settleAndClaim(pending.identity(), terminal, null, now.toString()) ->
        RegistrationOutcome.Terminal(terminal)
      auto != null -> RegistrationOutcome.AutoAllowed(auto)
      else -> RegistrationOutcome.Pending(pending, created.deadline)
    }
  }

  private fun storedRuleSettlement(
    unit: UnitOfWork,
    created: CreatedRequest,
    owner: ApprovalOwnerLease,
    now: Instant,
  ): ApprovalRequestRecord? {
    val pending = created.pending
    val option = pending.options.firstOrNull { it.kind == ONE_TIME_ALLOW_OPTION_KIND } ?: return null
    if (created.facts.durableRefusal(owner.executionMode, option, pending) != null) return null
    val rules = unit.approvals.activeRules(pending.repositoryIdentity)
    val rule = rules.firstOrNull { it.matches(pending) } ?: return null
    val settled =
      pending.copy(
        state = ApprovalState.ALLOWED,
        decisionSource = ApprovalDecisionSource.STORED_RULE,
        selectedOptionId = option.optionId,
        ruleId = rule.ruleId,
      )
    return settled.takeIf { unit.approvals.settleAndClaim(pending.identity(), it, null, now.toString()) }
  }

  private fun existingResult(
    stored: ApprovalStoredRequest,
    live: Boolean,
  ): ApprovalRegistrationResult =
    if (stored.record.state == ApprovalState.PENDING && live) {
      ApprovalRegistrationResult.Registered(stored.record)
    } else {
      ApprovalRegistrationResult.Refused(stored.record)
    }

  private fun decideLocked(
    unit: UnitOfWork,
    stored: ApprovalStoredRequest,
    owner: ApprovalOwnerLease,
    input: DecisionInput,
    now: Instant,
  ): ApprovalDecisionResult {
    val request = stored.record
    val deadline = parseInstant(request.deadlineAt)
    return when {
      deadline == null -> ApprovalDecisionResult.InvalidRecord(request)
      now >= deadline ->
        settleOrReport(unit, request, request.settleFor(ApprovalTerminalCause.EXPIRY, deadline, now), null, now)
      else -> decideOpen(unit, stored, owner, input, now)
    }
  }

  private fun decideOpen(
    unit: UnitOfWork,
    stored: ApprovalStoredRequest,
    owner: ApprovalOwnerLease,
    input: DecisionInput,
    now: Instant,
  ): ApprovalDecisionResult {
    val request = stored.record
    val option = request.options.firstOrNull { it.optionId == input.optionId }
    val target = option?.targetState()
    if (option == null || target == null) return ApprovalDecisionResult.OptionUnavailable(request)
    val refusal = grantRefusal(stored, owner, option, target, input.alwaysAllow)
    if (refusal != null) return ApprovalDecisionResult.GrantRefused(request, refusal)
    val rule =
      if (input.alwaysAllow) {
        request.authorityScope?.toRule(request, UUID.randomUUID().toString(), now.toString())
      } else {
        null
      }
    val source = if (input.alwaysAllow) ApprovalDecisionSource.OPERATOR_RULE else ApprovalDecisionSource.OPERATOR_ONCE
    val settled =
      request.copy(
        state = target,
        decisionSource = source,
        selectedOptionId = input.optionId,
        ruleId = rule?.ruleId,
      )
    return settleOrReport(unit, request, settled, rule, now)
  }

  private fun grantRefusal(
    stored: ApprovalStoredRequest,
    owner: ApprovalOwnerLease,
    option: ApprovalOption,
    target: ApprovalState,
    alwaysAllow: Boolean,
  ): ApprovalGrantRefusal? =
    when {
      target == ApprovalState.DENIED && !alwaysAllow -> null
      target != ApprovalState.ALLOWED -> ApprovalGrantRefusal.NO_ONE_TIME_GRANT
      alwaysAllow -> stored.facts.durableRefusal(owner.executionMode, option, stored.record)
      else -> stored.facts.onceRefusal(owner.executionMode, option)
    }

  private fun settleOrReport(
    unit: UnitOfWork,
    existing: ApprovalRequestRecord,
    settled: ApprovalRequestRecord?,
    rule: ApprovalRuleRecord?,
    now: Instant,
  ): ApprovalDecisionResult =
    if (settled != null && unit.approvals.settleAndClaim(existing.identity(), settled, rule, now.toString())) {
      ApprovalDecisionResult.Settled(settled)
    } else {
      ApprovalDecisionResult.AlreadySettled(unit.approvals.find(existing.identity())?.record ?: existing)
    }

  private fun pendingRecord(
    command: ApprovalRegistrationCommand,
    now: Instant,
    deadline: Instant,
  ): ApprovalRequestRecord {
    val fingerprint = command.canonicalInput
    val scope = fingerprint?.let { command.request.authorityScope?.copy(actionFingerprint = it) }
    return command.request.copy(
      actionFingerprint = fingerprint,
      authorityScope = scope,
      createdAt = now.toString(),
      deadlineAt = deadline.toString(),
      state = ApprovalState.PENDING,
      decisionSource = null,
      selectedOptionId = null,
      ruleId = null,
    )
  }

  private fun requestDeadline(
    command: ApprovalRegistrationCommand,
    now: Instant,
    owner: ApprovalOwnerLease?,
  ): Instant {
    val supplied =
      command.request.deadlineAt.takeIf { it.isNotBlank() }?.let { raw ->
        parseInstant(raw) ?: run {
          diagnostics.warning("Approval request refused because its deadline is invalid.")
          throw SkillBillRuntimeException(
            ApprovalFailureCode.INVALID_APPROVAL_CONTRACT,
            "Approval deadline is not a valid instant.",
          )
        }
      }
    val enclosing = listOfNotNull(command.enclosingDeadline, owner?.enclosingDeadline?.let(::parseInstant))
    return (listOfNotNull(now.plus(APPROVAL_BUDGET), supplied) + enclosing).min()
  }

  private fun ApprovalOption.targetState(): ApprovalState? =
    when (kind) {
      ONE_TIME_ALLOW_OPTION_KIND -> ApprovalState.ALLOWED
      DENY_OPTION_KIND -> ApprovalState.DENIED
      else -> null
    }

  private fun ApprovalConsumerLease.isLive(
    now: Instant,
    nonce: String?,
  ): Boolean {
    val unexpired = parseInstant(expiresAt)?.isAfter(now) == true
    return endedAt == null && unexpired && (nonce == null || this.nonce == nonce)
  }

  private class DecisionInput(val optionId: String, val alwaysAllow: Boolean)

  private class CreatedRequest(
    val pending: ApprovalRequestRecord,
    val facts: ApprovalGrantFacts,
    val deadline: Instant,
    val owner: ApprovalOwnerLease?,
    val live: Boolean,
  )

  private sealed interface RegistrationOutcome {
    data class Pending(val request: ApprovalRequestRecord, val deadline: Instant) : RegistrationOutcome

    data class Existing(val result: ApprovalRegistrationResult) : RegistrationOutcome

    data class AutoAllowed(val request: ApprovalRequestRecord) : RegistrationOutcome

    data class Terminal(val request: ApprovalRequestRecord) : RegistrationOutcome

    data object Conflict : RegistrationOutcome
  }

  private companion object {
    val APPROVAL_BUDGET: Duration = Duration.ofMinutes(5)
    val CONSUMER_LEASE: Duration = Duration.ofSeconds(60)
    const val CONFLICT_STOP_REASON =
      "An approval request identity was reused with changed provider content, so " +
        "the owning process is unsafe."
  }
}
