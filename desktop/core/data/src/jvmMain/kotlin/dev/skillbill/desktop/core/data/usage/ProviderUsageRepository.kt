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
import dev.skillbill.desktop.core.common.di.AppScope
import dev.skillbill.desktop.core.domain.settings.AppSettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import kotlin.time.Instant

@Inject
@SingleIn(AppScope::class)
class ProviderUsageRepository(
  private val profiles: UsageProfileResolver,
  private val settings: AppSettingsRepository,
) : UsageRepository, UsageObservationSink {
  private data class LiveBinding(val profile: UsageProfileLookup, val processGeneration: Long)

  private val sessionLock = Any()
  private val demandLock = Any()
  private val bindings = mutableMapOf<String, LiveBinding>()
  private val statuses = MutableStateFlow<Map<String, UsageSessionStatus.Observed>>(emptyMap())
  private val demands = mutableMapOf<String, UsageDemand>()

  override suspend fun profileLookup(provider: UsageProvider, selectedProfile: String?) =
    profileFor(provider, selectedProfile ?: if (provider == UsageProvider.Claude) settings.settings.first().claudeConfigDir else null).lookup

  override fun observe(request: UsageRequest): Flow<UsageResult> = flow {
    val sessionId = request.logicalSessionId
    if (request.profile.provider == UsageProvider.Claude && sessionId != null) {
      emitAll(statuses.map { current -> resultFor(sessionId, current[sessionId]) })
    } else {
      emit(baseline(request))
    }
  }

  override fun acquireDemand(demand: UsageDemand): UsageDemandResult {
    synchronized(demandLock) { demands[demand.ownerId] = demand }
    return UsageDemandResult.Acquired(demand)
  }

  override fun updateDemand(demand: UsageDemand): UsageDemandResult = synchronized(demandLock) {
    if (demand.ownerId !in demands) {
      UsageDemandResult.Rejected(UsageUnavailableReason(UsageUnavailableCode.ProviderUnavailable))
    } else {
      demands[demand.ownerId] = demand
      UsageDemandResult.Acquired(demand)
    }
  }

  override fun releaseDemand(ownerId: String): UsageDemandResult {
    synchronized(demandLock) { demands.remove(ownerId) }
    return UsageDemandResult.Released
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
}
