package dev.skillbill.desktop.core.data.usage

import dev.skillbill.desktop.core.domain.usage.UsageObservationSink
import dev.skillbill.desktop.core.domain.usage.UsageRepository
import dev.skillbill.desktop.core.domain.usage.model.RequestPermission
import dev.skillbill.desktop.core.domain.usage.model.SessionUsageScope
import dev.skillbill.desktop.core.domain.usage.model.UsageCapability
import dev.skillbill.desktop.core.domain.usage.model.UsageDemand
import dev.skillbill.desktop.core.domain.usage.model.UsageDemandResult
import dev.skillbill.desktop.core.domain.usage.model.UsageFreshness
import dev.skillbill.desktop.core.domain.usage.model.UsageObservation
import dev.skillbill.desktop.core.domain.usage.model.UsageObservationSubject
import dev.skillbill.desktop.core.domain.usage.model.VerifiedUsageSubject
import dev.skillbill.desktop.core.domain.usage.model.OmissionSemantics
import dev.skillbill.desktop.core.domain.usage.model.UsageProvider
import dev.skillbill.desktop.core.domain.usage.model.UsageProfileLookup
import dev.skillbill.desktop.core.domain.usage.model.UsageRefreshResult
import dev.skillbill.desktop.core.domain.usage.model.UsageRefreshState
import dev.skillbill.desktop.core.domain.usage.model.UsageRefreshCapability
import dev.skillbill.desktop.core.domain.usage.model.UsageRefreshAvailability
import dev.skillbill.desktop.core.domain.usage.model.UsageRefreshDeferredReason
import dev.skillbill.desktop.core.domain.usage.model.UsageRequest
import dev.skillbill.desktop.core.domain.usage.model.UsageResult
import dev.skillbill.desktop.core.domain.usage.model.ModelSelectionProvenance
import dev.skillbill.desktop.core.domain.usage.model.UsageSessionStatus
import dev.skillbill.desktop.core.domain.usage.model.UsageSource
import dev.skillbill.desktop.core.domain.usage.model.UsageUnavailableCode
import dev.skillbill.desktop.core.domain.usage.model.UsageUnavailableReason
import dev.skillbill.desktop.core.data.usage.codex.CodexUsageReader
import dev.skillbill.desktop.core.data.usage.codex.CodexAcquisitionContext
import dev.skillbill.desktop.core.data.usage.codex.CodexAcquisitionEvent
import dev.skillbill.desktop.core.data.usage.codex.CodexRateLimitsCandidate
import dev.skillbill.desktop.core.common.di.AppScope
import dev.skillbill.desktop.core.common.ApplicationCoroutineScope
import dev.skillbill.desktop.core.common.Diagnostics
import dev.skillbill.desktop.core.domain.settings.AppSettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.CompletableDeferred
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import kotlin.time.Instant
import kotlin.time.Clock
import kotlin.time.Duration

@Inject
@SingleIn(AppScope::class)
class ProviderUsageRepository(
  private val profiles: UsageProfileResolver,
  private val settings: AppSettingsRepository,
  private val codexUsageCollector: CodexUsageReader,
  private val applicationScope: ApplicationCoroutineScope,
  private val clock: Clock,
  private val scheduler: UsageScheduler = CoroutineUsageScheduler,
  private val jitter: UsageJitter = RandomUsageJitter,
  private val diagnostics: Diagnostics = NoopUsageDiagnostics,
) : UsageRepository, UsageObservationSink {
  private data class LiveBinding(val profile: UsageProfileLookup, val processGeneration: Long)
  private data class AcquisitionKey(val profile: UsageProfileLookup, val environment: Map<String, String>)
  private data class SubjectRevision(val subject: VerifiedUsageSubject, val revision: Long)
  private class Acquisition(
    val key: AcquisitionKey,
    val reservationId: String,
    val generation: Long,
    val baseRevision: Long,
    val references: MutableSet<String>,
    var eligible: Boolean,
    var admitted: Boolean,
    val retainedSubject: VerifiedUsageSubject?,
    var confirmedSubject: VerifiedUsageSubject?,
    val result: CompletableDeferred<UsageResult>,
    var source: Deferred<UsageResult>?,
  ) {
    var pullRevision: SubjectRevision? = null
  }

  private val sessionLock = Any()
  private val demandLock = Any()
  private val acquisitionLock = Any()
  private val acquisitions = mutableMapOf<AcquisitionKey, Acquisition>()
  private val subjectObservations = LinkedHashMap<VerifiedUsageSubject, UsageObservation>(16, 0.75f, true)
  private val mergeStates = mutableMapOf<VerifiedUsageSubject, UsageObservationMerge>()
  private val lookupSubjects = mutableMapOf<UsageProfileLookup, VerifiedUsageSubject>()
  private val directReferences = mutableMapOf<String, UsageProfileLookup>()
  private val subjectSnapshots = MutableStateFlow<Map<UsageProfileLookup, UsageObservation>>(emptyMap())
  private val profileResults = MutableStateFlow<Map<UsageProfileLookup, UsageResult>>(emptyMap())
  private val leaseReferences = mutableMapOf<String, MutableSet<Pair<Acquisition, String>>>()
  private val bindings = mutableMapOf<String, LiveBinding>()
  private val bindingProfiles = MutableStateFlow<Map<String, UsageProfileLookup>>(emptyMap())
  private val statuses = MutableStateFlow<Map<String, UsageSessionStatus.Observed>>(emptyMap())
  private val demands = mutableMapOf<String, UsageDemand>()
  private val demandSnapshots = MutableStateFlow<Map<String, UsageDemand>>(emptyMap())
  private val closed = AtomicBoolean(false)
  private val requestGeneration = AtomicLong()
  private val usageJob = SupervisorJob(applicationScope.scope.coroutineContext[Job])
  private val usageScope = CoroutineScope(applicationScope.scope.coroutineContext + usageJob)
  private val refreshCoordinator = UsageRefreshCoordinator(usageScope, clock, scheduler, jitter, diagnostics) { wake ->
    if (wake.resetDue || wake.freshnessDue) publishExpiry(wake.request.profile)
    wake.availability?.let { updateAvailability(wake.request.profile, it) }
    if (!wake.refreshDue) null
    else if (refreshCapability(wake.request.profile) == UsageRefreshCapability.ProbeOnly) retryProbe(wake.request)
    else refresh(wake.request)
  }
  private val capabilityResults = LinkedHashMap<UsageProfileLookup, UsageResult>(16, 0.75f, true)
  private val availability = MutableStateFlow<Map<UsageProfileLookup, UsageRefreshAvailability>>(emptyMap())

  fun closeUsage() {
    if (!closed.compareAndSet(false, true)) return
    synchronized(demandLock) {
      demands.clear()
      demandSnapshots.value = emptyMap()
    }
    refreshCoordinator.close()
    synchronized(acquisitionLock) {
      leaseReferences.clear()
      acquisitions.values.forEach { acquisition ->
        acquisition.eligible = false
        acquisition.references.clear()
      }
      acquisitions.clear()
      subjectObservations.clear()
      mergeStates.clear()
      lookupSubjects.clear()
      capabilityResults.clear()
      availability.value = emptyMap()
      directReferences.clear()
      subjectSnapshots.value = emptyMap()
      profileResults.value = emptyMap()
    }
    synchronized(sessionLock) {
      bindings.clear()
      bindingProfiles.value = emptyMap()
      statuses.value = emptyMap()
    }
    usageJob.cancel()
  }

  override suspend fun profileLookup(provider: UsageProvider, selectedProfile: String?, logicalSessionId: String?): UsageProfileLookup {
    if (provider == UsageProvider.Claude && selectedProfile == null && logicalSessionId != null) {
      synchronized(sessionLock) { bindings[logicalSessionId]?.profile }?.let { return it }
    }
    return profileFor(provider, selectedProfile ?: if (provider == UsageProvider.Claude) settings.settings.first().claudeConfigDir else null).lookup
  }

  override fun observeProfileLookup(
    provider: UsageProvider,
    selectedProfile: String?,
    logicalSessionId: String?,
  ): Flow<UsageProfileLookup> = when {
    provider == UsageProvider.Claude && selectedProfile == null && logicalSessionId != null ->
      combine(settings.settings, bindingProfiles) { currentSettings, currentBindings ->
        currentBindings[logicalSessionId]
          ?: profileFor(provider, currentSettings.claudeConfigDir).lookup
      }.distinctUntilChanged()
    provider == UsageProvider.Claude && selectedProfile == null ->
      settings.settings.map { profileFor(provider, it.claudeConfigDir).lookup }.distinctUntilChanged()
    else -> flow { emit(profileFor(provider, selectedProfile).lookup) }
  }

  override fun observe(request: UsageRequest): Flow<UsageResult> = observeRequest(request, null)

  private fun observeRequest(request: UsageRequest, ownerId: String?): Flow<UsageResult> = flow {
    val sessionId = request.logicalSessionId
    if (request.profile.provider == UsageProvider.Codex) {
      val profile = profiles.resolveCodex(request.profile)
      if (profile.lookup != request.profile) {
        emit(unavailableCodex())
        return@flow
      }
      val selectedModel = request.modelSelection?.takeIf { it.provenance == ModelSelectionProvenance.ActualExecution }?.name
      val reference = UUID.randomUUID().toString()
      val coordinatorOwner = ownerId ?: reference
      val capability = refreshCapability(profile.lookup)
      if (ownerId == null) synchronized(acquisitionLock) {
        directReferences[reference] = profile.lookup
      }
      val demand = synchronized(demandLock) {
        demands[ownerId]?.takeIf { it.visible && it.request == request }
          ?: UsageDemand(coordinatorOwner, request, visible = true, active = false)
      }
      refreshCoordinator.register(coordinatorOwner, demand, capability)
      if (ownerId == null) refreshCoordinator.selected(
        profile.lookup,
        request,
        capability,
        hasRetainedObservation(profile.lookup),
      )
      try {
        val storedInitial = synchronized(acquisitionLock) { profileResults.value[profile.lookup] }
        val capabilityState = refreshCapability(profile.lookup)
        val shouldProbe = capabilityState == UsageRefreshCapability.ProbeOnly
        val emittedUnavailable = storedInitial == null && shouldProbe
        if (emittedUnavailable) emit(unavailableCodex())
        val acquired = when {
          capabilityState is UsageRefreshCapability.Pull -> storedInitial ?: unavailableCodex()
          capabilityState == UsageRefreshCapability.Unsupported -> storedInitial ?: UsageResult.Unsupported(
            UsageUnavailableReason(UsageUnavailableCode.UnsupportedPersonalSnapshot),
          )
          capabilityState == UsageRefreshCapability.EventOnly -> storedInitial ?: unavailableCodex()
          else -> acquireCodex(profile.lookup, profile.environment, ownerId, selectedModel)
        }
        val initial = if (acquired is UsageResult.Snapshot) currentPublication(profile.lookup, acquired.value, selectedModel) else acquired
        if (!emittedUnavailable || initial != unavailableCodex()) emit(initial)
        emitAll(profileResults.mapNotNull { it[profile.lookup] }
          .distinctUntilChanged()
          .dropWhile { it == storedInitial }
          .map { result ->
            if (result is UsageResult.Snapshot) currentPublication(profile.lookup, result.value, selectedModel) else result
          })
      } finally {
        synchronized(acquisitionLock) {
          if (ownerId == null) directReferences.remove(reference)
          trimSubjectCache()
          trimCapabilityResults()
          trimPublishedResults()
        }
        if (ownerId == null) refreshCoordinator.release(coordinatorOwner)
      }
    } else if (request.profile.provider == UsageProvider.Claude && sessionId != null) {
      emitAll(statuses.map { current -> sessionResult(request, sessionId, current[sessionId]) })
    } else {
      emit(baseline(request))
    }
  }

  private suspend fun acquireCodex(
    profile: UsageProfileLookup,
    environment: Map<String, String>,
    ownerId: String?,
    selectedModel: String?,
    force: Boolean = false,
    explicitRecheck: Boolean = false,
  ): UsageResult {
    if (closed.get()) return unavailableCodex()
    if (profiles.codex(profile.configurationReference).lookup != profile) return unavailableCodex()
    synchronized(acquisitionLock) { capabilityResults[profile] }?.let {
      if (!explicitRecheck || it !is UsageResult.SignInRequired) return it
    }
    if (!force) synchronized(acquisitionLock) {
      lookupSubjects[profile]?.let(subjectObservations::get)?.let { cached ->
        return UsageResult.Snapshot(project(cached, selectedModel), OmissionSemantics.OmittedFieldsAreUnchanged)
      }
    }
    val key = AcquisitionKey(profile, environment)
    val reference = UUID.randomUUID().toString()
    var denied: UsageRefreshCoordinator.Admission? = null
    fun reserve() = synchronized(acquisitionLock) {
      if (closed.get()) return@synchronized null
      val existing = acquisitions[key]
      if (existing != null && !existing.eligible) return@synchronized null
      val active = existing?.takeIf { it.eligible }
      if (active != null) {
        refreshCoordinator.admit(profile, UsageRequest(profile), refreshCapability(profile), active.reservationId, explicitRecheck)
        active.references += reference
        if (ownerId != null) leaseReferences.getOrPut(ownerId) { mutableSetOf() } += active to reference
        active to false
      } else {
        val reservationId = UUID.randomUUID().toString()
        val admission = refreshCoordinator.admit(
          profile,
          UsageRequest(profile),
          refreshCapability(profile),
          reservationId,
          explicitRecheck,
          requested = force,
        )
        if (admission !is UsageRefreshCoordinator.Admission.Start) {
          denied = admission
          return@synchronized null
        }
        val subject = lookupSubjects[profile]
        val baseRevision = subject?.let(mergeStates::get)?.currentRevision() ?: 0L
        val next = Acquisition(key, reservationId, requestGeneration.incrementAndGet(), baseRevision, mutableSetOf(reference), true, false, subject, null, CompletableDeferred(), null)
        acquisitions[key] = next
        if (ownerId != null) leaseReferences.getOrPut(ownerId) { mutableSetOf() } += next to reference
        next to true
      }
    }
    val reservation = (if (ownerId == null) reserve() else synchronized(demandLock) {
      if (demands[ownerId]?.let { it.visible && it.request.profile == profile } != true) null else reserve()
    }) ?: return when (denied) {
      is UsageRefreshCoordinator.Admission.Deferred -> transientCodex()
      UsageRefreshCoordinator.Admission.Suspended -> synchronized(acquisitionLock) { capabilityResults[profile] } ?: unavailableCodex()
      else -> synchronized(acquisitionLock) { capabilityResults[profile] } ?: transientCodex()
    }
    val (acquisition, created) = reservation
    if (created) {
      val context = CodexAcquisitionContext(profile, environment, acquisition.reservationId) { event ->
        synchronized(acquisitionLock) {
          if (isCurrentAcquisition(acquisition) && event.reservationId == acquisition.reservationId) {
            when (event) {
              is CodexAcquisitionEvent.AccountInvalidated -> {
                revokeProfile(profile)
                capabilityResults.remove(profile)
                publishResult(profile, unavailableCodex())
                refreshCoordinator.accountChanged(profile, acquisition.reservationId)
                updateAvailability(profile, UsageRefreshAvailability.Ready)
              }
              is CodexAcquisitionEvent.SubjectConfirmed -> {
                val subject = event.evidence.subject
                if (subject.profile == profile && isVerified(subject)) {
                  confirmSubject(acquisition, subject)
                } else rejectVerification(profile, acquisition.reservationId)
              }
              is CodexAcquisitionEvent.VerifiedObservation -> {
                if (event.evidence.reservationId == acquisition.reservationId) {
                  val admitted = admitCodexResult(acquisition, event.evidence.result, null, event = true)
                  if (admitted is UsageResult.Snapshot) publish(profile, (admitted.value.subject as? UsageObservationSubject.Account)?.value?.let(subjectObservations::get))
                }
              }
              is CodexRateLimitsCandidate -> Unit
            }
          }
        }
      }
      val source = usageScope.async(start = CoroutineStart.LAZY) {
        val result = try {
          codexUsageCollector.read(context)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
          throw cancelled
        } catch (_: Exception) {
          transientCodex()
        }
        acquisition.result.complete(result)
        result
      }
      source.invokeOnCompletion { cause ->
        if (cause != null) acquisition.result.completeExceptionally(cause)
        val unownedCompletion = synchronized(acquisitionLock) {
          val unowned = acquisition.references.isEmpty() && !hasReference(profile)
          if (acquisitions[key] === acquisition && (cause != null || unowned)) {
            acquisition.eligible = false
            acquisitions.remove(key)
          }
          unowned
        }
        if (cause != null || unownedCompletion) {
          refreshCoordinator.settle(profile, acquisition.reservationId)
          if (!closed.get()) updateAvailability(profile, UsageRefreshAvailability.Ready)
        }
      }
      var startSource = false
      synchronized(acquisitionLock) {
        acquisition.source = source
        if (acquisition.eligible && acquisition.references.isNotEmpty()) {
          if (refreshCoordinator.attachOperation(profile, acquisition.reservationId, source)) {
            updateAvailability(profile, UsageRefreshAvailability.Checking)
            startSource = true
          } else {
            acquisition.eligible = false
            acquisition.result.complete(transientCodex())
          }
        }
      }
      if (startSource) source.start() else source.cancel()
    }
    try {
      val result = acquisition.result.await()
      return synchronized(acquisitionLock) {
        if (!ownsReservation(acquisition)) unavailableCodex()
        else if (!isCurrentAcquisition(acquisition)) {
          acquisition.admitted = true
          if (acquisitions[key] === acquisition) acquisitions.remove(key)
          refreshCoordinator.settle(profile, acquisition.reservationId)
          unavailableCodex()
        }
        else if (!acquisition.admitted) {
          acquisition.admitted = true
          val retainedBefore = lookupSubjects[profile]?.let(subjectObservations::get)
          admitCodexResult(acquisition, result, selectedModel).also { admitted ->
            val subject = lookupSubjects[profile]
            publish(profile, subject?.let(subjectObservations::get))
            acquisition.eligible = false
            if (acquisitions[key] === acquisition) acquisitions.remove(key)
            if (result is UsageResult.Snapshot || result is UsageResult.Partial || result is UsageResult.EmptySuccess) {
              if (admitted is UsageResult.Snapshot) {
                capabilityResults.remove(profile)
                val observedAt = clock.now()
                refreshCoordinator.sourceSucceeded(profile, acquisition.reservationId, observedAt)
                updateAvailability(profile, UsageRefreshAvailability.Ready)
              } else if (result is UsageResult.Snapshot || result is UsageResult.Partial || result is UsageResult.EmptySuccess) {
                refreshCoordinator.settle(profile, acquisition.reservationId)
              }
              if (admitted is UsageResult.Snapshot && retainedBefore?.refreshFailure != null) {
                diagnostics.recordRequest(UsageDiagnosticCategories.RETAINED_RECOVERED, mapOf(
                  UsageDiagnosticFields.RETAINED_METER_COUNT to admitted.value.meters.size,
                ))
              }
            } else if (result is UsageResult.TransientFailure) {
              val wasRetainedFailure = retainedBefore?.refreshFailure != null
              refreshCoordinator.transientFailure(profile, acquisition.reservationId)
              val eligibleAt = refreshCoordinator.nextEligibilityAt(profile) ?: clock.now()
              updateAvailability(profile, UsageRefreshAvailability.Deferred(UsageRefreshDeferredReason.Retry, eligibleAt))
              val retainedNow = lookupSubjects[profile]?.let(subjectObservations::get)
              if (retainedNow != null && !wasRetainedFailure && retainedNow.freshness != UsageFreshness.Stale) {
                diagnostics.recordRequest(UsageDiagnosticCategories.RETAINED_STALE, mapOf(
                  UsageDiagnosticFields.RETAINED_METER_COUNT to retainedNow.meters.size,
                ))
              }
            } else if (result is UsageResult.Throttled) {
              refreshCoordinator.providerThrottled(profile, acquisition.reservationId, result.retryAt?.value)
              val eligibleAt = refreshCoordinator.nextEligibilityAt(profile) ?: clock.now()
              updateAvailability(profile, UsageRefreshAvailability.Deferred(UsageRefreshDeferredReason.SourceFloor, eligibleAt))
            }
            if (memoizedCapabilityOutcome(admitted)) {
              capabilityResults[profile] = admitted
              when (admitted) {
                is UsageResult.SignInRequired -> refreshCoordinator.authenticationFailed(profile, acquisition.reservationId, signInRequired = true)
                is UsageResult.Unavailable -> if (admitted.reason.code == UsageUnavailableCode.ProfileUnverified) {
                  refreshCoordinator.authenticationFailed(profile, acquisition.reservationId, signInRequired = false)
                } else refreshCoordinator.transitionUnsupported(profile, acquisition.reservationId, admitted.reason.code.ordinal)
                else -> refreshCoordinator.transitionUnsupported(profile, acquisition.reservationId, unavailableReason(admitted).code.ordinal)
              }
              updateAvailability(profile, UsageRefreshAvailability.Disabled(unavailableReason(admitted)))
              trimCapabilityResults()
            }
            publishResult(profile, admitted)
            if (result !is UsageResult.TransientFailure && result !is UsageResult.Throttled &&
              result !is UsageResult.Snapshot && result !is UsageResult.Partial && result !is UsageResult.EmptySuccess) {
              refreshCoordinator.settle(profile, acquisition.reservationId)
            }
          }
        } else lookupSubjects[profile]?.let(subjectObservations::get)?.let { UsageResult.Snapshot(project(it, selectedModel), OmissionSemantics.OmittedFieldsAreUnchanged) } ?: result
      }
    } finally {
      synchronized(acquisitionLock) {
        acquisition.references.remove(reference)
        if (ownerId != null) {
          leaseReferences[ownerId]?.let { refs ->
            refs.remove(acquisition to reference)
            if (refs.isEmpty()) leaseReferences.remove(ownerId)
          }
        }
        if (acquisition.references.isEmpty() && acquisition.result.isCompleted && !hasReference(profile)) {
          acquisition.eligible = false
          if (acquisitions[key] === acquisition) acquisitions.remove(key)
        } else if (acquisition.references.isEmpty() && !hasReference(profile)) {
          acquisition.eligible = false
        }
      }
    }
  }

  override fun observeDemand(ownerId: String): Flow<UsageResult> = channelFlow {
    demandSnapshots
      .map { it[ownerId]?.takeIf { demand -> demand.visible }?.request }
      .distinctUntilChanged()
      .collectLatest { request ->
        if (request == null) send(unavailableCodex())
        else observeRequest(request, ownerId).collect { result ->
          if (synchronized(demandLock) { demands[ownerId]?.let { it.visible && it.request == request } == true }) send(result)
        }
      }
  }.buffer(0)

  override fun acquireDemand(demand: UsageDemand): UsageDemandResult {
    var selection = false
    var replaceLease = false
    val result = synchronized(demandLock) {
      if (closed.get()) UsageDemandResult.Rejected(UsageUnavailableReason(UsageUnavailableCode.ProviderUnavailable))
      else {
        val previous = demands[demand.ownerId]
        selection = previous == null || previous.request.profile != demand.request.profile || !previous.visible && demand.visible
        replaceLease = previous == null || previous.request.profile != demand.request.profile || previous.visible != demand.visible
        demands[demand.ownerId] = demand
        demandSnapshots.value = demands.toMap()
        UsageDemandResult.Acquired(demand)
      }
    }
    if (result is UsageDemandResult.Rejected) return result
    if (replaceLease) revokeLeaseReference(demand.ownerId)
    refreshCoordinator.register(demand.ownerId, demand, refreshCapability(demand.request.profile))
    if (selection && demand.visible) refreshCoordinator.selected(
      demand.request.profile,
      demand.request,
      refreshCapability(demand.request.profile),
      hasRetainedObservation(demand.request.profile),
    )
    if (selection && demand.visible) probeIfNeeded(demand)
    synchronized(acquisitionLock) { trimSubjectCache(); trimCapabilityResults() }
    return result
  }

  override fun updateDemand(demand: UsageDemand): UsageDemandResult {
    var selection = false
    var replaceLease = false
    val result = synchronized(demandLock) {
      if (closed.get() || demand.ownerId !in demands) {
      UsageDemandResult.Rejected(UsageUnavailableReason(UsageUnavailableCode.ProviderUnavailable))
      } else {
        val previous = demands.getValue(demand.ownerId)
        selection = previous.request.profile != demand.request.profile || !previous.visible && demand.visible
        replaceLease = previous.request.profile != demand.request.profile || previous.visible != demand.visible
        demands[demand.ownerId] = demand
        demandSnapshots.value = demands.toMap()
        UsageDemandResult.Acquired(demand)
      }
    }
    if (result is UsageDemandResult.Rejected) return result
    if (replaceLease) revokeLeaseReference(demand.ownerId)
    refreshCoordinator.register(demand.ownerId, demand, refreshCapability(demand.request.profile))
    if (selection && demand.visible) refreshCoordinator.selected(
      demand.request.profile,
      demand.request,
      refreshCapability(demand.request.profile),
      hasRetainedObservation(demand.request.profile),
    )
    if (selection && demand.visible) probeIfNeeded(demand)
    synchronized(acquisitionLock) { trimSubjectCache(); trimCapabilityResults() }
    return result
  }

  override fun releaseDemand(ownerId: String): UsageDemandResult {
    val previousProfile = synchronized(demandLock) { demands[ownerId]?.request?.profile }
    synchronized(demandLock) {
      demands.remove(ownerId)
      demandSnapshots.value = demands.toMap()
    }
    revokeLeaseReference(ownerId)
    previousProfile?.let(::releaseUnownedProfile)
    synchronized(acquisitionLock) { trimSubjectCache(); trimCapabilityResults(); trimPublishedResults() }
    return UsageDemandResult.Released
  }

  private fun revokeLeaseReference(ownerId: String) {
    synchronized(acquisitionLock) {
      val references = leaseReferences.remove(ownerId).orEmpty()
      references.forEach { (acquisition, reference) -> acquisition.references.remove(reference) }
      references.map { it.first }.distinct().forEach { acquisition ->
        if (acquisition.references.isEmpty() && !hasReference(acquisition.key.profile)) {
          acquisition.eligible = false
          if (acquisition.result.isCompleted) {
            if (acquisitions[acquisition.key] === acquisition) acquisitions.remove(acquisition.key)
          }
        }
      }
    }
    refreshCoordinator.release(ownerId)
  }

  private fun releaseUnownedProfile(profile: UsageProfileLookup) {
    synchronized(acquisitionLock) {
      if (hasReference(profile)) return
      acquisitions.values.filter { it.key.profile == profile }.forEach { acquisition ->
        acquisition.eligible = false
        if (acquisition.result.isCompleted) {
          if (acquisitions[acquisition.key] === acquisition) acquisitions.remove(acquisition.key)
        }
      }
    }
  }

  override fun refresh(request: UsageRequest): UsageRefreshResult {
    val profile = request.profile
    synchronized(acquisitionLock) {
      capabilityResults[profile]?.let { return UsageRefreshResult.Disabled(unavailableReason(it)) }
    }
    val capability = refreshCapability(profile)
    if (capability !is UsageRefreshCapability.Pull) return UsageRefreshResult.Disabled(refreshDisabledReason(profile.provider))
    val effective = if (profile.provider == UsageProvider.Codex) profiles.resolveCodex(profile) else return UsageRefreshResult.Disabled(refreshDisabledReason(profile.provider))
    if (effective.lookup != profile) return UsageRefreshResult.Disabled(UsageUnavailableReason(UsageUnavailableCode.ProfileUnverified))
    synchronized(acquisitionLock) {
      val active = acquisitions.values.firstOrNull { it.key.profile == profile && it.eligible }
      if (active != null) {
        refreshCoordinator.joined(profile, active.source)
        return UsageRefreshResult.Coalesced(request)
      }
    }
    val visible = synchronized(demandLock) { demands.values.filter { it.visible && it.request.profile == profile } }
    val active = visible.any { it.active }
    UsageRefreshPolicy.expectedInterval(capability, active) ?: return UsageRefreshResult.Disabled(refreshDisabledReason(profile.provider))
    val admission = refreshCoordinator.preview(profile, capability, requested = true)
    if (admission is UsageRefreshCoordinator.Admission.Deferred) {
      updateAvailability(profile, UsageRefreshAvailability.Deferred(UsageRefreshDeferredReason.Retry, admission.eligibleAt))
      if (visible.isNotEmpty()) refreshCoordinator.defer(profile, admission.eligibleAt)
      return UsageRefreshResult.Deferred(admission.eligibleAt)
    }
    if (admission is UsageRefreshCoordinator.Admission.Join) return UsageRefreshResult.Coalesced(request)
    if (admission is UsageRefreshCoordinator.Admission.Suspended) return UsageRefreshResult.Disabled(unavailableReason(capabilityResults[profile] ?: unavailableCodex()))
    if (admission is UsageRefreshCoordinator.Admission.Unsupported) return UsageRefreshResult.Disabled(refreshDisabledReason(profile.provider))
    refreshCoordinator.ensure(
      profile,
      request,
      capability,
    )
    updateAvailability(profile, UsageRefreshAvailability.Checking)
    usageScope.launch(start = CoroutineStart.UNDISPATCHED) {
      val ownerId = visible.firstOrNull()?.ownerId
      acquireCodex(effective.lookup, effective.environment, ownerId, request.modelSelection?.name, force = true)
    }
    return UsageRefreshResult.Started(request)
  }

  override fun recheckSignIn(request: UsageRequest): UsageRefreshResult {
    val profile = request.profile
    val stored = synchronized(acquisitionLock) { capabilityResults[profile] }
    if (stored !is UsageResult.SignInRequired) {
      return UsageRefreshResult.Disabled(stored?.let(::unavailableReason) ?: refreshDisabledReason(profile.provider))
    }
    if (closed.get() || profile.provider != UsageProvider.Codex) {
      return UsageRefreshResult.Disabled(UsageUnavailableReason(UsageUnavailableCode.ProviderUnavailable))
    }
    val effective = profiles.resolveCodex(profile)
    if (effective.lookup != profile) {
      return UsageRefreshResult.Disabled(UsageUnavailableReason(UsageUnavailableCode.ProfileUnverified))
    }
    when (val admission = refreshCoordinator.preview(profile, refreshCapability(profile), explicitRecheck = true)) {
      is UsageRefreshCoordinator.Admission.Deferred -> {
        updateAvailability(profile, UsageRefreshAvailability.Deferred(UsageRefreshDeferredReason.Retry, admission.eligibleAt))
        return UsageRefreshResult.Deferred(admission.eligibleAt)
      }
      UsageRefreshCoordinator.Admission.Join -> return UsageRefreshResult.Coalesced(request)
      UsageRefreshCoordinator.Admission.Unsupported,
      UsageRefreshCoordinator.Admission.Suspended -> return UsageRefreshResult.Disabled(unavailableReason(stored))
      is UsageRefreshCoordinator.Admission.Start -> Unit
    }
    val visible = synchronized(demandLock) { demands.values.firstOrNull { it.visible && it.request.profile == profile } }
    updateAvailability(profile, UsageRefreshAvailability.Checking)
    usageScope.launch(start = CoroutineStart.UNDISPATCHED) {
      val selectedModel = request.modelSelection
        ?.takeIf { it.provenance == ModelSelectionProvenance.ActualExecution }
        ?.name
      acquireCodex(profile, effective.environment, visible?.ownerId, selectedModel, force = true, explicitRecheck = true)
    }
    return UsageRefreshResult.Started(request)
  }

  private fun retryProbe(request: UsageRequest): UsageRefreshResult {
    val profile = request.profile
    if (refreshCapability(profile) != UsageRefreshCapability.ProbeOnly) return refresh(request)
    val effective = profiles.resolveCodex(profile)
    if (effective.lookup != profile) return UsageRefreshResult.Disabled(UsageUnavailableReason(UsageUnavailableCode.ProfileUnverified))
    if (!hasReference(profile)) return UsageRefreshResult.Disabled(refreshDisabledReason(profile.provider))
    synchronized(acquisitionLock) {
      val active = acquisitions.values.firstOrNull { it.key.profile == profile && it.eligible }
      if (active != null) {
        refreshCoordinator.joined(profile, active.source)
        return UsageRefreshResult.Coalesced(request)
      }
    }
    val admission = refreshCoordinator.preview(profile, UsageRefreshCapability.ProbeOnly)
    if (admission is UsageRefreshCoordinator.Admission.Deferred) return UsageRefreshResult.Deferred(admission.eligibleAt)
    if (admission is UsageRefreshCoordinator.Admission.Suspended) return UsageRefreshResult.Disabled(unavailableReason(capabilityResults[profile] ?: unavailableCodex()))
    if (admission is UsageRefreshCoordinator.Admission.Unsupported) return UsageRefreshResult.Disabled(refreshDisabledReason(profile.provider))
    refreshCoordinator.ensure(profile, request, UsageRefreshCapability.ProbeOnly)
    updateAvailability(profile, UsageRefreshAvailability.Checking)
    val model = request.modelSelection?.takeIf { it.provenance == ModelSelectionProvenance.ActualExecution }?.name
    usageScope.launch(start = CoroutineStart.UNDISPATCHED) {
      acquireCodex(effective.lookup, effective.environment, null, model, force = true)
    }
    return UsageRefreshResult.Started(request)
  }

  override fun checkDue(ownerId: String): UsageRefreshResult {
    val demand = synchronized(demandLock) { demands[ownerId]?.takeIf { it.visible } }
      ?: return UsageRefreshResult.Disabled(UsageUnavailableReason(UsageUnavailableCode.ProviderUnavailable))
    val profile = demand.request.profile
    synchronized(acquisitionLock) {
      capabilityResults[profile]?.let { return UsageRefreshResult.Disabled(unavailableReason(it)) }
    }
    val capability = refreshCapability(profile)
    if (capability !is UsageRefreshCapability.Pull) return UsageRefreshResult.Disabled(refreshDisabledReason(profile.provider))
    refreshCoordinator.ensure(
      profile,
      demand.request,
      capability,
    )
    val eligibleAt = refreshCoordinator.requestEligibleAt(profile)
    if (eligibleAt != null && clock.now() < eligibleAt) {
      return UsageRefreshResult.NotDue(eligibleAt)
    }
    if (eligibleAt == null) return UsageRefreshResult.Disabled(refreshDisabledReason(profile.provider))
    return refresh(demand.request)
  }

  override fun refreshAvailability(request: UsageRequest): Flow<UsageRefreshAvailability> =
    availability.map { states ->
      states[request.profile] ?: synchronized(acquisitionLock) { capabilityResults[request.profile] }?.let {
        UsageRefreshAvailability.Disabled(unavailableReason(it))
      } ?: if (refreshCapability(request.profile) !is UsageRefreshCapability.Pull) {
        disabledAvailability(request.profile.provider)
      } else UsageRefreshAvailability.Ready
    }.distinctUntilChanged()

  override fun recheckSignInAvailability(request: UsageRequest): Flow<UsageRefreshAvailability> =
    availability.map { refreshCoordinator.signInAvailability(request.profile) }.distinctUntilChanged()

  override fun sessionStatus(request: UsageRequest): UsageSessionStatus {
    val sessionId = request.logicalSessionId ?: return UsageSessionStatus.Unavailable
    return synchronized(sessionLock) {
      val binding = bindings[sessionId]
      if (binding == null || !sameProfile(binding.profile, request.profile) || request.profile.provider != UsageProvider.Claude) {
        UsageSessionStatus.Unavailable
      } else statuses.value[sessionId]?.takeIf {
        it.scope.processGeneration == binding.processGeneration && sameProfile(it.profile, request.profile)
      } ?: UsageSessionStatus.Unavailable
    }
  }

  override fun bindSession(logicalSessionId: String, profile: UsageProfileLookup, processGeneration: Long) {
    synchronized(sessionLock) {
      if (closed.get()) return
      bindings[logicalSessionId] = LiveBinding(profile, processGeneration)
      bindingProfiles.value = bindingProfiles.value + (logicalSessionId to profile)
      statuses.value = statuses.value - logicalSessionId
    }
  }

  override fun recordSessionStatus(
    logicalSessionId: String,
    processGeneration: Long,
    permission: RequestPermission,
    warning: Boolean,
    observedAt: Instant,
  ) {
    synchronized(sessionLock) {
      val binding = bindings[logicalSessionId] ?: return
      if (binding.processGeneration != processGeneration) return
      val observation = UsageSessionStatus.Observed(
        SessionUsageScope(logicalSessionId, processGeneration),
        binding.profile,
        permission,
        warning,
        observedAt,
      )
      statuses.value = statuses.value + (logicalSessionId to observation)
    }
  }

  override fun revokeSession(logicalSessionId: String, processGeneration: Long) {
    synchronized(sessionLock) {
      val binding = bindings[logicalSessionId] ?: return
      if (binding.processGeneration != processGeneration) return
      bindings.remove(logicalSessionId)
      bindingProfiles.value = bindingProfiles.value - logicalSessionId
      statuses.value = statuses.value - logicalSessionId
    }
  }

  private fun baseline(request: UsageRequest): UsageResult = when (request.profile.provider) {
    UsageProvider.Codex -> UsageResult.Unavailable(UsageUnavailableReason(UsageUnavailableCode.AwaitingCapabilityReader))
    UsageProvider.Claude -> UsageResult.Unavailable(UsageUnavailableReason(UsageUnavailableCode.SessionObservationUnavailable))
    UsageProvider.Cursor -> UsageResult.Unsupported(UsageUnavailableReason(UsageUnavailableCode.UnsupportedPersonalSnapshot))
  }

  private fun sessionResult(request: UsageRequest, sessionId: String, status: UsageSessionStatus.Observed?): UsageResult =
    synchronized(sessionLock) {
      val binding = bindings[sessionId]
      val active = status?.takeIf {
        request.profile.provider == UsageProvider.Claude &&
          it.scope.logicalSessionId == sessionId && binding?.processGeneration == it.scope.processGeneration &&
          sameProfile(binding.profile, request.profile) && sameProfile(it.profile, request.profile)
      } ?: return@synchronized UsageResult.Unavailable(UsageUnavailableReason(UsageUnavailableCode.SessionObservationUnavailable))
      UsageResult.Snapshot(
        UsageObservation(
          subject = UsageObservationSubject.Session(active.scope),
          collectionTime = active.observedAt,
          sourceObservationTime = null,
          statusObservedAt = active.observedAt,
          source = UsageSource(active.profile.provider, UsageCapability.SessionStatusOnly),
          freshness = UsageFreshness.Current,
          refreshState = UsageRefreshState.Idle,
          meters = emptyList(),
          requestPermission = active.permission,
          sessionWarning = active.warning,
          coverage = dev.skillbill.desktop.core.domain.usage.model.UsageCoverage.Empty,
        ),
        OmissionSemantics.OmittedFieldsAreUnchanged,
      )
    }

  private fun sameProfile(left: UsageProfileLookup, right: UsageProfileLookup): Boolean =
    left.provider == right.provider && left.configurationReference == right.configurationReference && left.generation == right.generation

  private fun profileFor(provider: UsageProvider, selectedProfile: String?): EffectiveUsageProfile = when (provider) {
    UsageProvider.Codex -> profiles.codex(selectedProfile)
    UsageProvider.Claude -> profiles.claude(selectedProfile)
    UsageProvider.Cursor -> profiles.cursor(selectedProfile)
  }

  private fun refreshCapability(provider: UsageProvider): UsageRefreshCapability = when (provider) {
    UsageProvider.Codex -> codexUsageCollector.refreshCapability
    UsageProvider.Claude -> UsageRefreshCapability.EventOnly
    UsageProvider.Cursor -> UsageRefreshCapability.Unsupported
  }

  private fun refreshCapability(profile: UsageProfileLookup): UsageRefreshCapability =
    when (val result = synchronized(acquisitionLock) { capabilityResults[profile] }) {
      is UsageResult.Unsupported -> UsageRefreshCapability.Unsupported
      is UsageResult.Unavailable -> if (result.reason.code in setOf(
        UsageUnavailableCode.AwaitingCapabilityReader,
        UsageUnavailableCode.UnsupportedPersonalSnapshot,
      )) UsageRefreshCapability.Unsupported else refreshCapability(profile.provider)
      else -> refreshCapability(profile.provider)
    }

  private fun expectedPullInterval(profile: UsageProfileLookup): Duration? {
    val active = demandSnapshots.value.values.any { it.visible && it.active && it.request.profile == profile }
    return UsageRefreshPolicy.expectedInterval(refreshCapability(profile), active)
  }

  private fun refreshDisabledReason(provider: UsageProvider): UsageUnavailableReason = when (provider) {
    UsageProvider.Codex -> UsageUnavailableReason(
      if (codexUsageCollector.refreshCapability == UsageRefreshCapability.Unsupported) {
        UsageUnavailableCode.UnsupportedPersonalSnapshot
      } else {
        UsageUnavailableCode.AwaitingCapabilityReader
      },
    )
    UsageProvider.Claude -> UsageUnavailableReason(UsageUnavailableCode.SessionObservationUnavailable)
    UsageProvider.Cursor -> UsageUnavailableReason(UsageUnavailableCode.UnsupportedPersonalSnapshot)
  }

  private fun disabledAvailability(provider: UsageProvider) = UsageRefreshAvailability.Disabled(refreshDisabledReason(provider))

  private fun probeIfNeeded(demand: UsageDemand) {
    val profile = demand.request.profile
    if (refreshCapability(profile) != UsageRefreshCapability.ProbeOnly || hasRetainedObservation(profile)) return
    val effective = profiles.resolveCodex(profile)
    if (effective.lookup != profile) return
    val model = demand.request.modelSelection
      ?.takeIf { it.provenance == ModelSelectionProvenance.ActualExecution }
      ?.name
    usageScope.launch(start = CoroutineStart.UNDISPATCHED) {
      acquireCodex(effective.lookup, effective.environment, demand.ownerId, model)
    }
  }

  private fun hasRetainedObservation(profile: UsageProfileLookup): Boolean = synchronized(acquisitionLock) {
    lookupSubjects[profile]?.let(subjectObservations::containsKey) == true
  }

  private fun memoizedCapabilityOutcome(result: UsageResult): Boolean = when (result) {
    is UsageResult.SignInRequired, is UsageResult.Unsupported -> true
    is UsageResult.Unavailable -> result.reason.code in setOf(
      UsageUnavailableCode.ProfileUnverified,
      UsageUnavailableCode.AwaitingCapabilityReader,
      UsageUnavailableCode.UnsupportedPersonalSnapshot,
    )
    else -> false
  }

  private fun unavailableReason(result: UsageResult): UsageUnavailableReason = when (result) {
    is UsageResult.Unavailable -> result.reason
    is UsageResult.Unsupported -> result.reason
    is UsageResult.SignInRequired -> result.reason
    else -> UsageUnavailableReason(UsageUnavailableCode.ProfileUnverified)
  }

  private fun updateAvailability(profile: UsageProfileLookup, state: UsageRefreshAvailability) = synchronized(acquisitionLock) {
    if (!closed.get()) availability.value = availability.value + (profile to state)
  }

  private fun isCurrentAcquisition(acquisition: Acquisition): Boolean =
    !closed.get() && acquisitions[acquisition.key] === acquisition && acquisition.eligible &&
      (acquisition.references.isNotEmpty() || hasReference(acquisition.key.profile)) &&
      profiles.codex(acquisition.key.profile.configurationReference).lookup == acquisition.key.profile &&
      profiles.resolveCodex(acquisition.key.profile).environment == acquisition.key.environment

  private fun ownsReservation(acquisition: Acquisition): Boolean =
    !closed.get() && acquisitions[acquisition.key] === acquisition

  private fun confirmSubject(acquisition: Acquisition, subject: VerifiedUsageSubject) {
    val profile = acquisition.key.profile
    val confirmed = acquisition.confirmedSubject
    if (confirmed != null && confirmed != subject) {
      rejectVerification(profile, acquisition.reservationId)
      return
    }
    acquisition.confirmedSubject = subject
    replaceSubjectBinding(profile, subject, acquisition)
    lookupSubjects[profile] = subject
    mergeStates.putIfAbsent(subject, UsageObservationMerge(subjectObservations[subject]))
    eventBaseRevision(acquisition, subject, event = false)
  }

  private fun eventBaseRevision(acquisition: Acquisition, subject: VerifiedUsageSubject, event: Boolean): Long {
    val currentRevision = mergeStates[subject]?.currentRevision() ?: 0L
    val pullRevision = acquisition.pullRevision?.takeIf { it.subject == subject }
      ?: SubjectRevision(subject, if (acquisition.retainedSubject == subject) acquisition.baseRevision else currentRevision)
        .also { acquisition.pullRevision = it }
    return if (event) currentRevision else pullRevision.revision
  }

  private fun admitCodexResult(acquisition: Acquisition, result: UsageResult, selectedModel: String?, event: Boolean = false): UsageResult {
    if (!isCurrentAcquisition(acquisition)) return unavailableCodex()
    val profile = acquisition.key.profile
    val reservationId = acquisition.reservationId
    val generation = acquisition.generation
    return when (result) {
    is UsageResult.Snapshot -> {
      val subject = (result.value.subject as? UsageObservationSubject.Account)?.value
      if (subject != null && subject.profile == profile && !isVerified(subject)) {
        rejectVerification(profile, reservationId)
        return unavailableCodex()
      }
      if (result.reservationId == reservationId && (subject == null || subject.profile != profile ||
          subject.profile.provider != UsageProvider.Codex || acquisition.confirmedSubject?.let { it != subject } == true)) {
        rejectVerification(profile, reservationId)
        return unavailableCodex()
      }
      if (subject == null || result.reservationId != reservationId ||
        !isVerified(subject) || subject.profile != profile || subject.profile.provider != UsageProvider.Codex ||
        acquisition.confirmedSubject?.let { it != subject } == true ||
        result.value.source.capability != UsageCapability.NumericSnapshot || (result.value.meters.isEmpty() && !result.value.emptySuccess)
      ) unavailableCodex()
      else {
        val state = mergeStates.getOrPut(subject) { UsageObservationMerge(subjectObservations[subject]) }
        val subjectBase = eventBaseRevision(acquisition, subject, event)
        val interval = expectedPullInterval(profile)
        val incoming = result.value.copy(
          meters = result.value.meters.map { meter -> if (interval == null) meter else meter.copy(freshnessBasis = interval) },
          freshness = UsageFreshness.Current,
          sourceFreshnessBasis = interval,
          requestGeneration = generation,
        )
        val merged = state.admit(incoming, result.omission, subjectBase) ?: return unavailableCodex()
        if (acquisition.confirmedSubject == null) acquisition.confirmedSubject = subject
        replaceSubjectBinding(profile, subject, acquisition)
        lookupSubjects[profile] = subject
        subjectObservations[subject] = merged
        trimSubjectCache()
        UsageResult.Snapshot(project(merged, selectedModel), result.omission)
      }
    }
    is UsageResult.Partial -> {
      if (result.subject.profile == profile && !isVerified(result.subject)) {
        rejectVerification(profile, reservationId)
        return unavailableCodex()
      }
      if (result.reservationId == reservationId && (result.subject.profile != profile ||
          result.subject.profile.provider != UsageProvider.Codex || acquisition.confirmedSubject?.let { it != result.subject } == true)) {
        rejectVerification(profile, reservationId)
        return unavailableCodex()
      }
      if (!isVerified(result.subject) || result.subject.profile != profile || result.subject.profile.provider != UsageProvider.Codex ||
        acquisition.confirmedSubject?.let { it != result.subject } == true || result.source.provider != UsageProvider.Codex ||
        result.source.capability != UsageCapability.NumericSnapshot || result.reservationId != reservationId) return unavailableCodex()
      val subject = result.subject
      val state = mergeStates.getOrPut(subject) { UsageObservationMerge(subjectObservations[subject]) }
      val subjectBase = eventBaseRevision(acquisition, subject, event)
      val interval = expectedPullInterval(profile)
      val incoming = if (interval == null) result else result.copy(fields = result.fields.mapValues { (_, field) ->
        if (field is dev.skillbill.desktop.core.domain.usage.model.PartialUsageField.Present) {
          field.copy(value = field.value.copy(freshnessBasis = interval))
        } else field
      })
      val merged = state.partial(incoming, generation, subjectBase, interval) ?: return unavailableCodex()
      if (acquisition.confirmedSubject == null) acquisition.confirmedSubject = subject
      replaceSubjectBinding(profile, subject, acquisition)
      lookupSubjects[profile] = subject
      subjectObservations[subject] = merged
      trimSubjectCache()
      UsageResult.Snapshot(project(merged, selectedModel), OmissionSemantics.OmittedFieldsAreUnchanged)
    }
    is UsageResult.EmptySuccess -> {
      if (result.subject.profile == profile && !isVerified(result.subject)) {
        rejectVerification(profile, reservationId)
        return unavailableCodex()
      }
      if (result.reservationId == reservationId && (result.subject.profile != profile ||
          result.subject.profile.provider != UsageProvider.Codex || acquisition.confirmedSubject?.let { it != result.subject } == true)) {
        rejectVerification(profile, reservationId)
        return unavailableCodex()
      }
      if (!isVerified(result.subject) || result.subject.profile != profile || result.subject.profile.provider != UsageProvider.Codex ||
        acquisition.confirmedSubject?.let { it != result.subject } == true || result.source.provider != UsageProvider.Codex ||
        result.source.capability != UsageCapability.NumericSnapshot || result.reservationId != reservationId) return unavailableCodex()
      val old = subjectObservations[result.subject]
      val incoming = if (old == null) UsageObservation(
        subject = UsageObservationSubject.Account(result.subject),
        collectionTime = result.collectionTime,
        sourceObservationTime = result.sourceObservationTime,
        source = result.source,
        freshness = UsageFreshness.Unknown,
        refreshState = UsageRefreshState.Complete,
        meters = emptyList(),
        requestPermission = RequestPermission.Unknown,
        coverage = if (result.omission == OmissionSemantics.OmittedFieldsAreRemoved) dev.skillbill.desktop.core.domain.usage.model.UsageCoverage.Empty else dev.skillbill.desktop.core.domain.usage.model.UsageCoverage.Unknown,
        emptySuccess = true,
        requestGeneration = generation,
        sourceFreshnessBasis = expectedPullInterval(profile),
      ) else old.copy(
        collectionTime = result.collectionTime,
        sourceObservationTime = result.sourceObservationTime,
        source = result.source,
        meters = emptyList(),
        emptySuccess = true,
        requestGeneration = generation,
        sourceFreshnessBasis = expectedPullInterval(profile),
      )
      val state = mergeStates.getOrPut(result.subject) { UsageObservationMerge(old) }
      val subjectBase = eventBaseRevision(acquisition, result.subject, event)
      val explicitEmpty = state.admit(incoming, result.omission, subjectBase) ?: return unavailableCodex()
      if (acquisition.confirmedSubject == null) acquisition.confirmedSubject = result.subject
      replaceSubjectBinding(profile, result.subject, acquisition)
      lookupSubjects[profile] = result.subject
      subjectObservations[result.subject] = explicitEmpty
      mergeStates[result.subject] = state
      trimSubjectCache()
      UsageResult.Snapshot(project(explicitEmpty, selectedModel), result.omission)
    }
    is UsageResult.TransientFailure -> {
      val subject = acquisition.confirmedSubject ?: acquisition.retainedSubject?.takeIf {
        acquisition.eligible && it.profile == profile && lookupSubjects[profile] == it && subjectObservations[it] != null
      }
      val retained = subject?.let(subjectObservations::get)
      if (retained == null) result else {
        val failed = mergeStates[subject]?.failed(result.reason) ?: retained.copy(refreshState = UsageRefreshState.Failed, refreshFailure = result.reason)
        subject?.let { subjectObservations[it] = failed }
        publish(profile, failed)
        UsageResult.Snapshot(project(failed, selectedModel), OmissionSemantics.OmittedFieldsAreUnchanged)
      }
    }
    is UsageResult.SignInRequired,
    is UsageResult.Unavailable,
    is UsageResult.Unsupported -> {
      revokeProfile(profile)
      result
    }
    is UsageResult.Throttled -> result
    }
  }

  private fun trimSubjectCache() = synchronized(acquisitionLock) {
    val pinned = demandSnapshots.value.values.asSequence().filter { it.visible }.map { it.request.profile }.toSet() + directReferences.values
    while (subjectObservations.size > 64) {
      val evict = subjectObservations.keys.firstOrNull { it.profile !in pinned } ?: break
      subjectObservations.remove(evict)
      mergeStates.remove(evict)
      refreshCoordinator.clearSubject(evict)
      lookupSubjects.entries.removeAll { it.value == evict }
      subjectSnapshots.value = subjectSnapshots.value - evict.profile
      publishResult(evict.profile, capabilityResults[evict.profile] ?: unavailableCodex())
    }
  }

  private fun trimCapabilityResults() = synchronized(acquisitionLock) {
    val pinned = demandSnapshots.value.values.filter { it.visible }.map { it.request.profile }.toSet() + directReferences.values
    while (capabilityResults.size > 64) {
      val evict = capabilityResults.keys.firstOrNull { it !in pinned } ?: break
      capabilityResults.remove(evict)
    }
  }

  private fun trimPublishedResults() = synchronized(acquisitionLock) {
    val pinned = demandSnapshots.value.values.filter { it.visible }.map { it.request.profile }.toSet() + directReferences.values
    val results = LinkedHashMap(profileResults.value)
    while (results.size > 64) {
      val evict = results.keys.firstOrNull { it !in pinned } ?: break
      results.remove(evict)
    }
    profileResults.value = results
  }

  private fun replaceSubjectBinding(profile: UsageProfileLookup, subject: VerifiedUsageSubject, owner: Acquisition) {
    acquisitions.values.filter { acquisition ->
      acquisition !== owner && acquisition.key.profile == profile &&
        (acquisition.confirmedSubject ?: acquisition.retainedSubject) != subject
    }.forEach { acquisition ->
      acquisition.eligible = false
      acquisition.confirmedSubject = null
    }
    lookupSubjects[profile]?.takeIf { it != subject }?.let { previous ->
      subjectObservations.remove(previous)
      mergeStates.remove(previous)
      refreshCoordinator.clearSubject(previous)
      publish(profile, null)
    }
  }

  private fun revokeProfile(profile: UsageProfileLookup) {
    acquisitions.values.filter { it.key.profile == profile }.forEach {
      it.eligible = false
      it.confirmedSubject = null
    }
    lookupSubjects.remove(profile)?.let { subject ->
      subjectObservations.remove(subject)
      mergeStates.remove(subject)
      refreshCoordinator.clearSubject(subject)
    }
    publish(profile, null)
  }

  private fun rejectVerification(profile: UsageProfileLookup, operationId: String) {
    revokeProfile(profile)
    val result = unavailableCodex()
    capabilityResults[profile] = result
    refreshCoordinator.authenticationFailed(profile, operationId, signInRequired = false)
    updateAvailability(profile, UsageRefreshAvailability.Disabled(unavailableReason(result)))
    publishResult(profile, result)
  }

  private fun publish(profile: UsageProfileLookup, observation: UsageObservation?) {
    if (observation == null) {
      subjectSnapshots.value = subjectSnapshots.value - profile
      publishResult(profile, capabilityResults[profile] ?: unavailableCodex())
    } else {
      if (lookupSubjects[profile] != (observation.subject as? UsageObservationSubject.Account)?.value) return
      val previous = subjectSnapshots.value[profile]
      val projected = project(observation, null)
      recordStaleTransition(previous, projected)
      subjectSnapshots.value = subjectSnapshots.value + (profile to projected)
      publishResult(profile, UsageResult.Snapshot(projected, OmissionSemantics.OmittedFieldsAreUnchanged))
      scheduleExpiry(profile, observation)
    }
  }

  private fun publishResult(profile: UsageProfileLookup, result: UsageResult) {
    val results = LinkedHashMap(profileResults.value)
    results.remove(profile)
    results[profile] = result
    profileResults.value = results
    trimPublishedResults()
  }

  private fun currentPublication(profile: UsageProfileLookup, observation: UsageObservation, selectedModel: String?): UsageResult =
    synchronized(acquisitionLock) {
      val subject = (observation.subject as? UsageObservationSubject.Account)?.value
      if (subject == null || lookupSubjects[profile] != subject || subjectSnapshots.value[profile] == null) unavailableCodex()
      else UsageResult.Snapshot(project(subjectSnapshots.value.getValue(profile), selectedModel), OmissionSemantics.OmittedFieldsAreUnchanged)
    }

  private fun scheduleExpiry(profile: UsageProfileLookup, observation: UsageObservation) {
    if (observation.subject !is UsageObservationSubject.Account) return
    refreshCoordinator.observation(profile, observation)
  }

  private fun publishExpiry(profile: UsageProfileLookup) {
    synchronized(acquisitionLock) {
      val subject = lookupSubjects[profile] ?: return
      val current = subjectObservations[subject] ?: return
      if (closed.get() || !hasReference(profile)) return
      val projected = project(current, null)
      if (projected.meters != current.meters || projected.freshness != current.freshness) {
        recordStaleTransition(current, projected)
        subjectObservations[subject] = projected
        subjectSnapshots.value = subjectSnapshots.value + (profile to projected)
        publishResult(profile, UsageResult.Snapshot(projected, OmissionSemantics.OmittedFieldsAreUnchanged))
      }
    }
  }

  private fun recordStaleTransition(previous: UsageObservation?, current: UsageObservation) {
    if (current.freshness == UsageFreshness.Stale && previous?.freshness != UsageFreshness.Stale) {
      diagnostics.recordRequest(UsageDiagnosticCategories.RETAINED_STALE, mapOf(
        UsageDiagnosticFields.RETAINED_METER_COUNT to current.meters.size,
      ))
    }
  }

  private fun hasReference(profile: UsageProfileLookup): Boolean = synchronized(acquisitionLock) {
    hasVisibleDemand(profile) || directReferences.values.any { it == profile }
  }

  private fun hasVisibleDemand(profile: UsageProfileLookup): Boolean =
    demandSnapshots.value.values.any { it.visible && it.request.profile == profile }

  private fun isVerified(subject: VerifiedUsageSubject): Boolean =
    subject.identity.value.isNotBlank() && when (val workspace = subject.identity.workspace) {
      is dev.skillbill.desktop.core.domain.usage.model.VerifiedUsageWorkspaceScope.Workspace -> workspace.id.isNotBlank()
      dev.skillbill.desktop.core.domain.usage.model.VerifiedUsageWorkspaceScope.VerifiedNonWorkspace -> true
      null -> false
    }

  private fun project(observation: UsageObservation, selectedModel: String?): UsageObservation {
    val now = clock.now()
    val meters = observation.meters.map { meter ->
      meter.copy(
        expired = meter.resetAt?.let { reset -> now >= reset } == true,
        stale = meter.freshnessBasis?.let { now >= meter.observedAt + it * 2 } == true,
      )
    }
    val ranked = UsageMeterRanking.rank(meters, selectedModel)
    val sourceFreshnessBasis = observation.sourceFreshnessBasis
    val sourceStale = sourceFreshnessBasis?.let {
      now >= (observation.sourceObservationTime ?: observation.collectionTime) + it * 2
    } == true
    return observation.copy(
      meters = meters,
      cardCandidates = ranked.cardCandidates,
      popupMeters = ranked.popupMeters,
      freshness = when {
        meters.any { it.stale } || sourceStale -> UsageFreshness.Stale
        sourceFreshnessBasis != null && now < (observation.sourceObservationTime ?: observation.collectionTime) + sourceFreshnessBasis * 2 -> UsageFreshness.Current
        meters.any { it.freshnessBasis != null } -> UsageFreshness.Current
        else -> observation.freshness
      },
    )
  }

  private fun unavailableCodex() = UsageResult.Unavailable(UsageUnavailableReason(UsageUnavailableCode.ProfileUnverified))

  private fun transientCodex() = UsageResult.TransientFailure(UsageUnavailableReason(UsageUnavailableCode.TransientFailure))
}
