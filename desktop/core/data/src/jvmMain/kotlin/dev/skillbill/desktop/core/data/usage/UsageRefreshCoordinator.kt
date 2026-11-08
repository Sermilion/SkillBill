package dev.skillbill.desktop.core.data.usage

import dev.skillbill.desktop.core.domain.usage.model.UsageDemand
import dev.skillbill.desktop.core.domain.usage.model.UsageProfileLookup
import dev.skillbill.desktop.core.domain.usage.model.UsageRefreshCapability
import dev.skillbill.desktop.core.domain.usage.model.UsageRefreshAvailability
import dev.skillbill.desktop.core.domain.usage.model.UsageRefreshDeferredReason
import dev.skillbill.desktop.core.domain.usage.model.UsageRequest
import dev.skillbill.desktop.core.domain.usage.model.UsageRefreshResult
import dev.skillbill.desktop.core.domain.usage.model.UsageObservation
import dev.skillbill.desktop.core.domain.usage.model.UsageUnavailableReason
import dev.skillbill.desktop.core.domain.usage.model.VerifiedUsageSubject
import dev.skillbill.desktop.core.common.Diagnostics
import dev.skillbill.desktop.core.domain.usage.model.UsageSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

internal data class UsageRefreshWake(
  val request: UsageRequest,
  val resetDue: Boolean,
  val freshnessDue: Boolean,
  val refreshDue: Boolean,
  val availability: UsageRefreshAvailability?,
)

fun interface UsageScheduler {
  fun schedule(scope: CoroutineScope, delay: Duration, action: () -> Unit): Job
}

internal object CoroutineUsageScheduler : UsageScheduler {
  override fun schedule(scope: CoroutineScope, delay: Duration, action: () -> Unit): Job = scope.launch {
    delay(delay)
    action()
  }
}

fun interface UsageJitter {
  fun next(): Double
}

internal object RandomUsageJitter : UsageJitter {
  override fun next(): Double = kotlin.random.Random.nextDouble(0.8, 1.2)
}

internal class UsageRefreshCoordinator(
  private val scope: CoroutineScope,
  private val clock: Clock,
  private val scheduler: UsageScheduler,
  private val jitter: UsageJitter,
  private val diagnostics: Diagnostics,
  private val onDue: (UsageRefreshWake) -> UsageRefreshResult?,
) {
  sealed interface Admission {
    data class Start(val eligibleAt: Instant) : Admission
    data object Join : Admission
    data class Deferred(val eligibleAt: Instant) : Admission
    data object Suspended : Admission
    data object Unsupported : Admission
  }

  private data class ResetKey(val subject: VerifiedUsageSubject, val source: UsageSource?, val bucket: String, val resetAt: Instant)
  private data class Lease(val profile: UsageProfileLookup, val demand: UsageDemand)
  private data class Entry(
    var capability: UsageRefreshCapability,
    var request: UsageRequest,
    var lastSuccess: Instant? = null,
    var retryAt: Instant? = null,
    var providerRetryAt: Instant? = null,
    var failureCount: Int = 0,
    var authSuspended: Boolean = false,
    var signInRecheckAllowed: Boolean = false,
    var revalidationPending: Boolean = false,
    var pendingAt: Instant? = null,
    val resetKeys: MutableMap<ResetKey, Unit> = mutableMapOf(),
    val consumedResets: MutableSet<ResetKey> = mutableSetOf(),
    val freshnessBoundaries: MutableSet<Instant> = mutableSetOf(),
    var lastRequestStart: Instant? = null,
    var inFlightAt: Instant? = null,
    var selectionDueAt: Instant? = null,
    var operation: Job? = null,
    var operationId: String? = null,
    var scheduled: Job? = null,
  )

  private val lock = Any()
  private val leases = mutableMapOf<String, Lease>()
  private val entries = mutableMapOf<UsageProfileLookup, Entry>()
  private var closed = false

  fun admit(
    profile: UsageProfileLookup,
    request: UsageRequest,
    capability: UsageRefreshCapability,
    operationId: String,
    explicitRecheck: Boolean = false,
    requested: Boolean = false,
  ): Admission = synchronized(lock) {
    if (closed) return@synchronized Admission.Unsupported
    val entry = entries.getOrPut(profile) { Entry(capability, request) }
    entry.request = request
    entry.capability = capability
    if (entry.operationId != null) return@synchronized Admission.Join
    if (capability == UsageRefreshCapability.Unsupported || capability == UsageRefreshCapability.EventOnly) {
      return@synchronized Admission.Unsupported
    }
    if (entry.authSuspended && !explicitRecheck) return@synchronized Admission.Suspended
    if (explicitRecheck && !entry.signInRecheckAllowed) return@synchronized Admission.Unsupported
    val visible = leases.values.filter { it.profile == profile && it.demand.visible }
    val active = visible.any { it.demand.active }
    val sourceFloor = UsageRefreshPolicy.effectiveSourceFloor(capability)
    val now = clock.now()
    val trigger = when {
      explicitRecheck || requested -> listOfNotNull(now, entry.retryAt).maxOrNull()
      entry.retryAt != null -> entry.retryAt
      capability == UsageRefreshCapability.ProbeOnly -> entry.selectionDueAt
      else -> ordinaryDue(entry, active)
    }
    val eligibleAt = listOfNotNull(
      trigger,
      entry.providerRetryAt,
      entry.lastRequestStart?.plus(UsageRefreshPolicy.manualCooldown),
      sourceFloor?.takeIf { it > Duration.ZERO }?.let { entry.lastRequestStart?.plus(it) },
    ).maxOrNull() ?: now
    if (eligibleAt > now) {
      if (visible.isNotEmpty() && !explicitRecheck) reschedule(profile)
      return@synchronized Admission.Deferred(eligibleAt)
    }
    entry.operationId = operationId
    entry.lastRequestStart = now
    entry.pendingAt = null
    entry.selectionDueAt = null
    if (capability is UsageRefreshCapability.Pull) {
      entry.resetKeys.keys.filter { it.resetAt <= now }.forEach { entry.consumedResets += it }
    }
    diagnostics.recordRequest(UsageDiagnosticCategories.REQUEST_STARTED, mapOf(
      UsageDiagnosticFields.FAILURE_COUNT to entry.failureCount,
      UsageDiagnosticFields.COOLDOWN_REMAINING_MS to 0,
      UsageDiagnosticFields.SOURCE_FLOOR_MS to (sourceFloor?.inWholeMilliseconds ?: 0L),
    ))
    reschedule(profile)
    Admission.Start(now)
  }

  fun preview(
    profile: UsageProfileLookup,
    capability: UsageRefreshCapability,
    explicitRecheck: Boolean = false,
    requested: Boolean = false,
  ): Admission = synchronized(lock) {
    val entry = entries[profile] ?: return@synchronized Admission.Start(clock.now())
    if (entry.operationId != null) return@synchronized Admission.Join
    if (capability == UsageRefreshCapability.Unsupported || capability == UsageRefreshCapability.EventOnly) return@synchronized Admission.Unsupported
    if (entry.authSuspended && !explicitRecheck) return@synchronized Admission.Suspended
    if (explicitRecheck && !entry.signInRecheckAllowed) return@synchronized Admission.Unsupported
    val visible = leases.values.filter { it.profile == profile && it.demand.visible }
    val trigger = when {
      explicitRecheck || requested -> listOfNotNull(clock.now(), entry.retryAt).maxOrNull()
      entry.retryAt != null -> entry.retryAt
      capability == UsageRefreshCapability.ProbeOnly -> entry.selectionDueAt
      else -> ordinaryDue(entry, visible.any { it.demand.active })
    }
    val eligibleAt = listOfNotNull(
      trigger,
      entry.providerRetryAt,
      entry.lastRequestStart?.plus(UsageRefreshPolicy.manualCooldown),
      UsageRefreshPolicy.effectiveSourceFloor(capability)?.takeIf { it > Duration.ZERO }
        ?.let { entry.lastRequestStart?.plus(it) },
    ).maxOrNull() ?: clock.now()
    if (eligibleAt > clock.now()) Admission.Deferred(eligibleAt) else Admission.Start(eligibleAt)
  }

  fun attachOperation(profile: UsageProfileLookup, operationId: String, operation: Job): Boolean = synchronized(lock) {
    val entry = entries[profile] ?: return@synchronized false
    if (entry.operationId != operationId) return@synchronized false
    entry.operation = operation
    entry.inFlightAt = entry.lastRequestStart
    true
  }

  fun authenticationFailed(profile: UsageProfileLookup, operationId: String, signInRequired: Boolean) {
    synchronized(lock) {
      val entry = entries[profile] ?: return
      if (entry.operationId != operationId) return
      if (!entry.authSuspended) {
        entry.authSuspended = true
        diagnostics.recordRequest(UsageDiagnosticCategories.AUTH_SUSPENDED, mapOf(
          UsageDiagnosticFields.FAILURE_COUNT to entry.failureCount,
        ))
      }
      entry.signInRecheckAllowed = signInRequired
      reschedule(profile)
    }
  }

  fun accountChanged(profile: UsageProfileLookup, operationId: String) {
    synchronized(lock) {
      val entry = entries[profile] ?: return
      if (entry.operationId != operationId) return
      if (entry.revalidationPending) return
      if (entry.authSuspended) diagnostics.recordRequest(UsageDiagnosticCategories.AUTH_RELEASED, mapOf(
        UsageDiagnosticFields.FAILURE_COUNT to entry.failureCount,
      ))
      entry.authSuspended = false
      entry.signInRecheckAllowed = false
      entry.revalidationPending = true
    }
  }

  fun transitionUnsupported(profile: UsageProfileLookup, operationId: String, reasonCode: Int = 0) {
    synchronized(lock) {
      val entry = entries[profile] ?: return
      if (entry.operationId != operationId) return
      if (entry.capability != UsageRefreshCapability.Unsupported) diagnostics.recordRequest(
        UsageDiagnosticCategories.UNSUPPORTED,
        mapOf(UsageDiagnosticFields.REASON_CODE to reasonCode),
      )
      entry.capability = UsageRefreshCapability.Unsupported
      entry.pendingAt = null
      reschedule(profile)
    }
  }

  fun providerThrottled(profile: UsageProfileLookup, operationId: String, retryAt: Instant?) {
    synchronized(lock) {
      val entry = entries[profile] ?: return
      if (entry.operationId != operationId) return
      if (retryAt != null) entry.providerRetryAt = maxOf(entry.providerRetryAt ?: retryAt, retryAt)
      entry.failureCount = (entry.failureCount + 1).coerceAtMost(1_000_000)
      val retry = clock.now() + UsageRefreshPolicy.retryDelay(entry.failureCount, jitter.next())
      entry.retryAt = maxOf(entry.retryAt ?: retry, retry, entry.providerRetryAt ?: retry)
      finishOperation(entry)
      diagnostics.recordRequest(UsageDiagnosticCategories.THROTTLED, mapOf(
        UsageDiagnosticFields.FAILURE_COUNT to entry.failureCount,
        UsageDiagnosticFields.RETRY_DELAY_MS to (entry.retryAt!! - clock.now()).inWholeMilliseconds.coerceAtLeast(0),
        UsageDiagnosticFields.SOURCE_FLOOR_MS to (UsageRefreshPolicy.effectiveSourceFloor(entry.capability)?.inWholeMilliseconds ?: 0L),
      ))
      reschedule(profile)
    }
  }

  fun transientFailure(profile: UsageProfileLookup, operationId: String) {
    synchronized(lock) {
      val entry = entries[profile] ?: return
      if (entry.operationId != operationId) return
      entry.failureCount = (entry.failureCount + 1).coerceAtMost(1_000_000)
      val retry = clock.now() + UsageRefreshPolicy.retryDelay(entry.failureCount, jitter.next())
      entry.retryAt = maxOf(retry, entry.providerRetryAt ?: retry)
      finishOperation(entry)
      diagnostics.recordRequest(UsageDiagnosticCategories.RETRY_DEFERRED, mapOf(
        UsageDiagnosticFields.FAILURE_COUNT to entry.failureCount,
        UsageDiagnosticFields.RETRY_DELAY_MS to (entry.retryAt!! - clock.now()).inWholeMilliseconds.coerceAtLeast(0),
        UsageDiagnosticFields.SOURCE_FLOOR_MS to (UsageRefreshPolicy.effectiveSourceFloor(entry.capability)?.inWholeMilliseconds ?: 0L),
      ))
      reschedule(profile)
    }
  }

  fun sourceSucceeded(profile: UsageProfileLookup, operationId: String, at: Instant) {
    synchronized(lock) {
      val entry = entries[profile] ?: return
      if (entry.operationId != operationId) return
      entry.lastSuccess = at
      entry.selectionDueAt = null
      if (entry.authSuspended) diagnostics.recordRequest(UsageDiagnosticCategories.AUTH_RELEASED, mapOf(
        UsageDiagnosticFields.FAILURE_COUNT to entry.failureCount,
      ))
      entry.authSuspended = false
      entry.signInRecheckAllowed = false
      entry.revalidationPending = false
      entry.failureCount = 0
      entry.retryAt = null
      if (entry.providerRetryAt?.let { it <= at } == true) entry.providerRetryAt = null
      finishOperation(entry)
      reschedule(profile)
    }
  }

  fun settle(profile: UsageProfileLookup, operationId: String) {
    synchronized(lock) {
      val entry = entries[profile] ?: return
      if (entry.operationId != operationId) return
      finishOperation(entry)
      if (entry.revalidationPending) {
        entry.revalidationPending = false
        entry.selectionDueAt = clock.now()
      }
      reschedule(profile)
    }
  }

  fun signInAvailability(profile: UsageProfileLookup): UsageRefreshAvailability = synchronized(lock) {
    val entry = entries[profile] ?: return@synchronized UsageRefreshAvailability.Disabled(
      UsageUnavailableReason(dev.skillbill.desktop.core.domain.usage.model.UsageUnavailableCode.ProfileUnverified),
    )
    signInAvailabilityLocked(entry)
  }

  private fun signInAvailabilityLocked(entry: Entry): UsageRefreshAvailability = when {
      entry.operationId != null -> UsageRefreshAvailability.Checking
      entry.capability == UsageRefreshCapability.Unsupported || entry.capability == UsageRefreshCapability.EventOnly ->
        UsageRefreshAvailability.Disabled(UsageUnavailableReason(
          dev.skillbill.desktop.core.domain.usage.model.UsageUnavailableCode.UnsupportedPersonalSnapshot,
        ))
      entry.authSuspended && entry.signInRecheckAllowed -> {
        val floor = listOfNotNull(entry.retryAt, entry.providerRetryAt,
          entry.lastRequestStart?.plus(UsageRefreshPolicy.manualCooldown),
          UsageRefreshPolicy.effectiveSourceFloor(entry.capability)?.let { entry.lastRequestStart?.plus(it) })
          .maxOrNull() ?: clock.now()
        if (floor > clock.now()) UsageRefreshAvailability.Deferred(UsageRefreshDeferredReason.Retry, floor)
        else UsageRefreshAvailability.Ready
      }
      else -> UsageRefreshAvailability.Disabled(
        UsageUnavailableReason(dev.skillbill.desktop.core.domain.usage.model.UsageUnavailableCode.ProfileUnverified),
      )
    }

  private fun finishOperation(entry: Entry) {
    entry.inFlightAt = null
    entry.operation = null
    entry.operationId = null
  }

  fun register(ownerId: String, demand: UsageDemand, capability: UsageRefreshCapability) {
    var cancel: Job? = null
    synchronized(lock) {
      if (closed) return
      val previous = leases[ownerId]
      if (previous?.demand == demand && entries[demand.request.profile]?.capability == capability) return
      if (previous != null && (previous.profile != demand.request.profile || previous.demand.visible != demand.visible)) {
        leases.remove(ownerId)
        cancel = cancelIfUnobserved(previous.profile)
        reschedule(previous.profile)
      }
      leases[ownerId] = Lease(demand.request.profile, demand)
      val previousCapability = entries[demand.request.profile]?.capability
      val entry = entries.getOrPut(demand.request.profile) { Entry(capability, demand.request) }
      entry.capability = capability
      entry.request = demand.request
      if (capability == UsageRefreshCapability.Unsupported && previousCapability != UsageRefreshCapability.Unsupported) {
        diagnostics.recordRequest(UsageDiagnosticCategories.UNSUPPORTED, mapOf(
          UsageDiagnosticFields.REASON_CODE to demand.request.profile.provider.ordinal,
        ))
      }
      reschedule(demand.request.profile)
    }
    cancel?.cancel()
  }

  fun release(ownerId: String) {
    var cancel: Job? = null
    synchronized(lock) {
      leases.remove(ownerId)?.let {
        cancel = cancelIfUnobserved(it.profile)
        reschedule(it.profile)
      }
    }
    cancel?.cancel()
  }

  fun ensure(profile: UsageProfileLookup, request: UsageRequest, capability: UsageRefreshCapability) {
    synchronized(lock) {
      if (closed) return
      val entry = entries.getOrPut(profile) { Entry(capability, request) }
      entry.request = request
      entry.capability = capability
    }
  }

  fun selected(
    profile: UsageProfileLookup,
    request: UsageRequest,
    capability: UsageRefreshCapability,
    hasObservation: Boolean = true,
  ) {
    synchronized(lock) {
      if (closed) return
      val entry = entries.getOrPut(profile) { Entry(capability, request) }
      entry.capability = capability
      entry.request = request
      val now = clock.now()
      if (!hasObservation || UsageRefreshPolicy.selectionDue(entry.lastSuccess, now)) entry.selectionDueAt = now
      reschedule(profile)
    }
  }

  fun requestEligibleAt(profile: UsageProfileLookup): Instant? = synchronized(lock) {
    val entry = entries[profile] ?: return@synchronized null
    val visible = leases.values.filter { it.profile == profile && it.demand.visible }
    if (visible.isEmpty()) return@synchronized null
    requestEligibleAt(entry, visible.any { it.demand.active })
  }

  fun nextEligibilityAt(profile: UsageProfileLookup): Instant? = synchronized(lock) {
    val entry = entries[profile] ?: return@synchronized null
    val trigger = entry.retryAt ?: entry.selectionDueAt ?: entry.lastSuccess?.plus(
      UsageRefreshPolicy.expectedInterval(entry.capability, active = false) ?: Duration.ZERO,
    )
    listOfNotNull(
      trigger,
      entry.providerRetryAt,
      entry.lastRequestStart?.plus(UsageRefreshPolicy.manualCooldown),
      UsageRefreshPolicy.effectiveSourceFloor(entry.capability)?.let { entry.lastRequestStart?.plus(it) },
    ).maxOrNull()
  }

  fun disable(profile: UsageProfileLookup) {
    synchronized(lock) {
      entries[profile]?.let { entry ->
        entry.capability = UsageRefreshCapability.Unsupported
        entry.pendingAt = null
        entry.scheduled?.cancel()
        entry.scheduled = null
      }
    }
  }

  fun defer(profile: UsageProfileLookup, eligibleAt: Instant) {
    synchronized(lock) {
      entries[profile]?.let { entry ->
        entry.pendingAt = eligibleAt
        reschedule(profile)
      }
    }
  }

  fun observation(profile: UsageProfileLookup, observation: UsageObservation) {
    synchronized(lock) {
      val entry = entries[profile] ?: return
      val subject = (observation.subject as? dev.skillbill.desktop.core.domain.usage.model.UsageObservationSubject.Account)?.value ?: return
      val current = observation.meters.mapNotNull { meter ->
        meter.resetAt?.let { reset -> ResetKey(subject, meter.sourceOwner, meter.bucketIdentity, reset) }
      }.associateWith { Unit }
      entry.resetKeys.keys.filter { it.subject == subject && it !in current }.forEach {
        entry.resetKeys.remove(it)
      }
      entry.resetKeys.putAll(current)
      entry.freshnessBoundaries.clear()
      observation.meters.mapNotNullTo(entry.freshnessBoundaries) { meter ->
        meter.freshnessBasis?.let { UsageRefreshPolicy.staleAt(meter.observedAt, it) }
      }
      observation.sourceFreshnessBasis?.let { basis ->
        entry.freshnessBoundaries += UsageRefreshPolicy.staleAt(observation.sourceObservationTime ?: observation.collectionTime, basis)
      }
      entry.freshnessBoundaries.removeAll { it <= clock.now() }
      reschedule(profile)
    }
  }

  fun joined(profile: UsageProfileLookup, operation: Job?, startedAt: Instant? = null) {
    synchronized(lock) {
      entries[profile]?.let { entry ->
        if (entry.operation == null && operation?.isActive == true) {
          entry.operation = operation
          entry.inFlightAt = startedAt ?: clock.now()
          entry.scheduled?.cancel()
          entry.scheduled = null
        }
      }
    }
  }

  fun clearSubject(subject: VerifiedUsageSubject) {
    synchronized(lock) {
      entries[subject.profile]?.let { entry ->
        entry.freshnessBoundaries.clear()
        entry.resetKeys.keys.filter { it.subject == subject }.forEach {
          entry.resetKeys.remove(it)
        }
        entry.consumedResets.removeAll { it.subject == subject }
        reschedule(subject.profile)
      }
    }
  }

  fun close() {
    val jobs = synchronized(lock) {
      if (closed) return
      closed = true
      val running = entries.values.flatMap { listOfNotNull(it.scheduled, it.operation) }
      entries.clear()
      leases.clear()
      running
    }
    jobs.forEach { it.cancel() }
    diagnostics.record(UsageDiagnosticCategories.DISPOSED)
  }

  private fun reschedule(profile: UsageProfileLookup) {
    val entry = entries[profile] ?: return
    entry.scheduled?.cancel()
    entry.scheduled = null
    val visible = leases.values.filter { it.profile == profile && it.demand.visible }
    if (visible.isEmpty()) return
    val active = visible.any { it.demand.active }
    val interval = UsageRefreshPolicy.expectedInterval(entry.capability, active)
    val probeRetry = entry.capability == UsageRefreshCapability.ProbeOnly &&
      (entry.retryAt != null || entry.selectionDueAt != null)
    val now = clock.now()
    val inFlight = entry.operationId != null || entry.inFlightAt != null || entry.operation != null
    val periodicDue = interval?.let { UsageRefreshPolicy.eligibleAt(entry.lastSuccess, it) }
    val ordinaryDue = if (interval == null || probeRetry) null else listOfNotNull(entry.selectionDueAt, periodicDue)
      .plus(entry.resetKeys.keys.asSequence()
        .filter { it !in entry.consumedResets }
        .map { it.resetAt }
        .toList())
      .minOrNull()
    val requestDue = if (!inFlight && (interval != null || probeRetry)) {
      requestEligibleAt(entry, active, ordinaryDue)
    } else null
    val resetProjectionDue = entry.resetKeys.keys.asSequence()
      .filter { it !in entry.consumedResets && it.resetAt > now }
      .minOfOrNull { it.resetAt }
    val freshnessProjectionDue = entry.freshnessBoundaries.filter { it > now }.minOrNull()
    val availabilityProjectionDue = signInRecheckAt(entry)?.takeIf { it > now }
    val due = listOfNotNull(requestDue, resetProjectionDue, freshnessProjectionDue, availabilityProjectionDue).minOrNull()
    if (due == null) return
    entry.scheduled = scheduler.schedule(scope, (due - now).coerceAtLeast(Duration.ZERO)) {
      val wake = synchronized(lock) {
        val current = entries[profile] ?: return@schedule
        if (closed || leases.values.none { it.profile == profile && it.demand.visible }) return@schedule
        current.scheduled = null
        val at = clock.now()
        val operationInFlight = current.operationId != null || current.inFlightAt != null || current.operation != null
        val resetDue = current.resetKeys.keys.any { it !in current.consumedResets && it.resetAt <= at }
        val freshnessDue = current.freshnessBoundaries.removeAll { it <= at }
        val currentActive = leases.values.any { it.profile == profile && it.demand.visible && it.demand.active }
        val pullDue = current.capability is UsageRefreshCapability.Pull &&
          requestEligibleAt(current, currentActive)?.let { it <= at } == true
        val probeRetryDue = current.capability == UsageRefreshCapability.ProbeOnly &&
          requestEligibleAt(current, currentActive)?.let { it <= at } == true
        val refreshDue = !operationInFlight && (pullDue || probeRetryDue)
        val availability = signInRecheckAt(current)?.takeIf { it <= at }?.let { signInAvailabilityLocked(current) }
        UsageRefreshWake(current.request, resetDue, freshnessDue, refreshDue, availability)
      }
      when (val result = onDue(wake)) {
        null -> synchronized(lock) { reschedule(profile) }
        is UsageRefreshResult.Deferred -> synchronized(lock) { entries[profile]?.let { it.pendingAt = result.eligibleAt; reschedule(profile) } }
        is UsageRefreshResult.NotDue -> synchronized(lock) { entries[profile]?.let { it.pendingAt = result.eligibleAt; reschedule(profile) } }
        is UsageRefreshResult.Coalesced -> synchronized(lock) {
          entries[profile]?.let { current ->
            current.pendingAt = null
            reschedule(profile)
          }
        }
        is UsageRefreshResult.Disabled -> disable(profile)
        is UsageRefreshResult.Failed -> synchronized(lock) { entries[profile]?.let { it.pendingAt = null; it.inFlightAt = null; reschedule(profile) } }
        is UsageRefreshResult.Started -> Unit
      }
    }
  }

  private fun requestEligibleAt(entry: Entry, active: Boolean, ordinaryDue: Instant? = ordinaryDue(entry, active)): Instant? {
    if (entry.authSuspended) return null
    if (entry.operationId != null) return null
    val pull = entry.capability is UsageRefreshCapability.Pull
    val probeRetry = entry.capability == UsageRefreshCapability.ProbeOnly &&
      (entry.retryAt != null || entry.selectionDueAt != null)
    if (!pull && !probeRetry) return null
    val trigger = when {
      pull && entry.retryAt != null -> entry.retryAt
      probeRetry -> entry.retryAt ?: entry.selectionDueAt
      else -> listOfNotNull(entry.pendingAt, ordinaryDue).minOrNull()
    }
    trigger ?: return null
    val floors = mutableListOf(trigger)
    entry.providerRetryAt?.let { floors += it }
    entry.lastRequestStart?.let { startedAt ->
      UsageRefreshPolicy.effectiveSourceFloor(entry.capability)?.takeIf { it > Duration.ZERO }?.let { floors += startedAt + it }
      floors += startedAt + UsageRefreshPolicy.manualCooldown
    }
    return floors.maxOrNull()
  }

  private fun ordinaryDue(entry: Entry, active: Boolean): Instant? {
    val interval = UsageRefreshPolicy.expectedInterval(entry.capability, active) ?: return null
    val periodicDue = UsageRefreshPolicy.eligibleAt(entry.lastSuccess, interval)
    val selectionDue = entry.selectionDueAt ?: clock.now().takeIf { entry.lastSuccess == null }
    return listOfNotNull(
      entry.pendingAt,
      selectionDue,
      periodicDue,
      entry.resetKeys.keys.asSequence()
        .filter { it !in entry.consumedResets }
        .map { it.resetAt }
        .minOrNull(),
    ).minOrNull()
  }

  private fun signInRecheckAt(entry: Entry): Instant? {
    if (!entry.authSuspended || !entry.signInRecheckAllowed ||
      entry.capability == UsageRefreshCapability.Unsupported || entry.capability == UsageRefreshCapability.EventOnly) return null
    return listOfNotNull(
      entry.retryAt,
      entry.providerRetryAt,
      entry.lastRequestStart?.plus(UsageRefreshPolicy.manualCooldown),
      UsageRefreshPolicy.effectiveSourceFloor(entry.capability)?.let { entry.lastRequestStart?.plus(it) },
    ).maxOrNull() ?: clock.now()
  }

  private fun cancelIfUnobserved(profile: UsageProfileLookup): Job? {
    if (leases.values.any { it.profile == profile && it.demand.visible }) return null
    return entries[profile]?.operation
  }
}
