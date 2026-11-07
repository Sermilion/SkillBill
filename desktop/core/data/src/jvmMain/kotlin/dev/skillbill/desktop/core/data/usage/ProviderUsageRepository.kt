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
import dev.skillbill.desktop.core.domain.usage.model.OmissionSemantics
import dev.skillbill.desktop.core.domain.usage.model.UsageProvider
import dev.skillbill.desktop.core.domain.usage.model.UsageProfileLookup
import dev.skillbill.desktop.core.domain.usage.model.UsageRefreshResult
import dev.skillbill.desktop.core.domain.usage.model.UsageRefreshState
import dev.skillbill.desktop.core.domain.usage.model.UsageRequest
import dev.skillbill.desktop.core.domain.usage.model.UsageResult
import dev.skillbill.desktop.core.domain.usage.model.UsageSessionStatus
import dev.skillbill.desktop.core.domain.usage.model.UsageSource
import dev.skillbill.desktop.core.domain.usage.model.UsageUnavailableCode
import dev.skillbill.desktop.core.domain.usage.model.UsageUnavailableReason
import dev.skillbill.desktop.core.data.usage.codex.CodexUsageReader
import dev.skillbill.desktop.core.data.usage.codex.CodexAcquisitionContext
import dev.skillbill.desktop.core.data.usage.codex.CodexAcquisitionEvent
import dev.skillbill.desktop.core.data.usage.codex.CodexRateLimitsCandidate
import dev.skillbill.desktop.core.data.usage.codex.CodexQuotaBucket
import dev.skillbill.desktop.core.common.di.AppScope
import dev.skillbill.desktop.core.common.ApplicationCoroutineScope
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import java.util.UUID
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import kotlin.time.Instant

@Inject
@SingleIn(AppScope::class)
class ProviderUsageRepository(
  private val profiles: UsageProfileResolver,
  private val settings: AppSettingsRepository,
  private val codexUsageCollector: CodexUsageReader,
  private val applicationScope: ApplicationCoroutineScope,
) : UsageRepository, UsageObservationSink {
  private data class LiveBinding(val profile: UsageProfileLookup, val processGeneration: Long)
  private data class AcquisitionKey(val profile: UsageProfileLookup, val environment: Map<String, String>)
  private class Acquisition(
    val key: AcquisitionKey,
    val reservationId: String,
    val references: MutableSet<String>,
    var eligible: Boolean,
    var partialCandidates: List<CodexQuotaBucket>?,
    val result: CompletableDeferred<UsageResult>,
    var source: Deferred<UsageResult>?,
  )

  private val sessionLock = Any()
  private val demandLock = Any()
  private val acquisitionLock = Any()
  private val acquisitions = mutableMapOf<AcquisitionKey, Acquisition>()
  private val leaseReferences = mutableMapOf<String, Pair<Acquisition, String>>()
  private val bindings = mutableMapOf<String, LiveBinding>()
  private val statuses = MutableStateFlow<Map<String, UsageSessionStatus.Observed>>(emptyMap())
  private val demands = mutableMapOf<String, UsageDemand>()
  private val demandSnapshots = MutableStateFlow<Map<String, UsageDemand>>(emptyMap())

  override suspend fun profileLookup(provider: UsageProvider, selectedProfile: String?) =
    profileFor(provider, selectedProfile ?: if (provider == UsageProvider.Claude) settings.settings.first().claudeConfigDir else null).lookup

  override fun observe(request: UsageRequest): Flow<UsageResult> = observeRequest(request, null)

  private fun observeRequest(request: UsageRequest, ownerId: String?): Flow<UsageResult> = flow {
    val sessionId = request.logicalSessionId
    if (request.profile.provider == UsageProvider.Codex) {
      val profile = profiles.resolveCodex(request.profile)
      emit(acquireCodex(profile.lookup, profile.environment, ownerId))
    } else if (request.profile.provider == UsageProvider.Claude && sessionId != null) {
      emitAll(statuses.map { current -> resultFor(sessionId, current[sessionId]) })
    } else {
      emit(baseline(request))
    }
  }

  private suspend fun acquireCodex(profile: UsageProfileLookup, environment: Map<String, String>, ownerId: String?): UsageResult {
    val key = AcquisitionKey(profile, environment)
    val reference = UUID.randomUUID().toString()
    fun reserve() = synchronized(acquisitionLock) {
      val existing = acquisitions[key]
      if (existing != null && !existing.eligible) return@synchronized null
      val active = existing?.takeIf { it.eligible }
      if (active != null) {
        active.references += reference
        if (ownerId != null) leaseReferences[ownerId] = active to reference
        active to false
      } else {
        val next = Acquisition(key, UUID.randomUUID().toString(), mutableSetOf(reference), true, null, CompletableDeferred(), null)
        acquisitions[key] = next
        if (ownerId != null) leaseReferences[ownerId] = next to reference
        next to true
      }
    }
    val reservation = (if (ownerId == null) reserve() else synchronized(demandLock) {
      if (demands[ownerId]?.let { it.visible && it.request.profile == profile } != true) null else reserve()
    }) ?: return transientCodex()
    val (acquisition, created) = reservation
    if (created) {
      val context = CodexAcquisitionContext(profile, environment, acquisition.reservationId) { event ->
        synchronized(acquisitionLock) {
          if (acquisitions[key] === acquisition && acquisition.eligible && event.reservationId == acquisition.reservationId) {
            when (event) {
              is CodexAcquisitionEvent.AccountInvalidated -> acquisition.eligible = false
              is CodexRateLimitsCandidate -> acquisition.partialCandidates = event.buckets
            }
          }
        }
      }
      val source = applicationScope.scope.async(start = CoroutineStart.LAZY) {
        val result = codexUsageCollector.read(context)
        acquisition.result.complete(result)
        result
      }
      source.invokeOnCompletion { cause ->
        if (cause != null) acquisition.result.completeExceptionally(cause)
        synchronized(acquisitionLock) {
          if (acquisitions[key] === acquisition && acquisition.references.isEmpty()) acquisitions.remove(key)
        }
      }
      synchronized(acquisitionLock) {
        acquisition.source = source
        if (acquisition.eligible && acquisition.references.isNotEmpty()) source.start() else source.cancel()
      }
    }
    try {
      val result = acquisition.result.await()
      return synchronized(acquisitionLock) {
        if (acquisitions[key] === acquisition && acquisition.eligible) codexCapabilityGate(result) else unavailableCodex()
      }
    } finally {
      synchronized(acquisitionLock) {
        acquisition.references.remove(reference)
        if (ownerId != null && leaseReferences[ownerId] == (acquisition to reference)) leaseReferences.remove(ownerId)
        if (acquisition.references.isEmpty()) {
          acquisition.eligible = false
          if (acquisition.result.isCompleted && acquisitions[key] === acquisition) acquisitions.remove(key)
          else acquisition.source?.cancel()
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
    synchronized(demandLock) {
      val previous = demands[demand.ownerId]
      if (previous == null || previous.request != demand.request || previous.visible != demand.visible) {
        revokeLeaseReference(demand.ownerId)
      }
      demands[demand.ownerId] = demand
      demandSnapshots.value = demands.toMap()
    }
    return UsageDemandResult.Acquired(demand)
  }

  override fun updateDemand(demand: UsageDemand): UsageDemandResult = synchronized(demandLock) {
    if (demand.ownerId !in demands) {
      UsageDemandResult.Rejected(UsageUnavailableReason(UsageUnavailableCode.ProviderUnavailable))
    } else {
      val previous = demands.getValue(demand.ownerId)
      if (previous.request != demand.request || previous.visible != demand.visible) revokeLeaseReference(demand.ownerId)
      demands[demand.ownerId] = demand
      demandSnapshots.value = demands.toMap()
      UsageDemandResult.Acquired(demand)
    }
  }

  override fun releaseDemand(ownerId: String): UsageDemandResult {
    synchronized(demandLock) {
      revokeLeaseReference(ownerId)
      demands.remove(ownerId)
      demandSnapshots.value = demands.toMap()
    }
    return UsageDemandResult.Released
  }

  private fun revokeLeaseReference(ownerId: String) {
    synchronized(acquisitionLock) {
      val (acquisition, reference) = leaseReferences.remove(ownerId) ?: return
      acquisition.references.remove(reference)
      if (acquisition.references.isEmpty()) {
        acquisition.eligible = false
        if (acquisition.result.isCompleted) {
          if (acquisitions[acquisition.key] === acquisition) acquisitions.remove(acquisition.key)
        } else acquisition.source?.cancel()
      }
    }
  }

  override fun refresh(request: UsageRequest): UsageRefreshResult = when (request.profile.provider) {
    UsageProvider.Codex -> UsageRefreshResult.Disabled(UsageUnavailableReason(UsageUnavailableCode.AwaitingCapabilityReader))
    UsageProvider.Claude -> UsageRefreshResult.Disabled(UsageUnavailableReason(UsageUnavailableCode.SessionObservationUnavailable))
    UsageProvider.Cursor -> UsageRefreshResult.Disabled(UsageUnavailableReason(UsageUnavailableCode.UnsupportedPersonalSnapshot))
  }

  override fun sessionStatus(request: UsageRequest): UsageSessionStatus {
    val sessionId = request.logicalSessionId ?: return UsageSessionStatus.Unavailable
    return synchronized(sessionLock) {
      val binding = bindings[sessionId]
      if (binding == null || binding.profile.provider != request.profile.provider || request.profile.provider != UsageProvider.Claude) {
        UsageSessionStatus.Unavailable
      } else {
        statuses.value[sessionId]
          ?.takeIf { it.scope.processGeneration == binding.processGeneration }
          ?: UsageSessionStatus.Unavailable
      }
    }
  }

  override fun bindSession(logicalSessionId: String, profile: UsageProfileLookup, processGeneration: Long) {
    synchronized(sessionLock) {
      bindings[logicalSessionId] = LiveBinding(profile, processGeneration)
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
      statuses.value = statuses.value - logicalSessionId
    }
  }

  private fun baseline(request: UsageRequest): UsageResult = when (request.profile.provider) {
    UsageProvider.Codex -> UsageResult.Unavailable(UsageUnavailableReason(UsageUnavailableCode.AwaitingCapabilityReader))
    UsageProvider.Claude -> UsageResult.Unavailable(UsageUnavailableReason(UsageUnavailableCode.SessionObservationUnavailable))
    UsageProvider.Cursor -> UsageResult.Unsupported(UsageUnavailableReason(UsageUnavailableCode.UnsupportedPersonalSnapshot))
  }

  private fun resultFor(sessionId: String, status: UsageSessionStatus.Observed?): UsageResult {
    val active = status?.takeIf {
      it.scope.logicalSessionId == sessionId
    } ?: return UsageResult.Unavailable(UsageUnavailableReason(UsageUnavailableCode.SessionObservationUnavailable))
    return UsageResult.Snapshot(
      UsageObservation(
        subject = UsageObservationSubject.Session(active.scope),
        collectionTime = active.observedAt,
        sourceObservationTime = null,
        source = UsageSource(active.profile.provider, UsageCapability.SessionStatusOnly),
        freshness = UsageFreshness.Current,
        refreshState = UsageRefreshState.Idle,
        meters = emptyList(),
        requestPermission = active.permission,
        sessionWarning = active.warning,
      ),
      OmissionSemantics.OmittedFieldsAreUnchanged,
    )
  }

  private fun profileFor(provider: UsageProvider, selectedProfile: String?): EffectiveUsageProfile = when (provider) {
    UsageProvider.Codex -> profiles.codex(selectedProfile)
    UsageProvider.Claude -> profiles.claude(selectedProfile)
    UsageProvider.Cursor -> profiles.cursor(selectedProfile)
  }

  private fun codexCapabilityGate(result: UsageResult): UsageResult = when (result) {
    is UsageResult.Unavailable,
    is UsageResult.Unsupported,
    is UsageResult.SignInRequired,
    is UsageResult.TransientFailure -> result
    else -> UsageResult.Unavailable(UsageUnavailableReason(UsageUnavailableCode.ProfileUnverified))
  }

  private fun unavailableCodex() = UsageResult.Unavailable(UsageUnavailableReason(UsageUnavailableCode.ProfileUnverified))

  private fun transientCodex() = UsageResult.TransientFailure(UsageUnavailableReason(UsageUnavailableCode.TransientFailure))
}
