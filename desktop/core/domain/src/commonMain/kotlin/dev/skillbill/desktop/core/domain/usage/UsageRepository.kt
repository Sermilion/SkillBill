package dev.skillbill.desktop.core.domain.usage

import dev.skillbill.desktop.core.domain.usage.model.UsageDemand
import dev.skillbill.desktop.core.domain.usage.model.UsageDemandResult
import dev.skillbill.desktop.core.domain.usage.model.UsageProfileLookup
import dev.skillbill.desktop.core.domain.usage.model.UsageProvider
import dev.skillbill.desktop.core.domain.usage.model.RequestPermission
import dev.skillbill.desktop.core.domain.usage.model.UsageRefreshResult
import dev.skillbill.desktop.core.domain.usage.model.UsageRequest
import dev.skillbill.desktop.core.domain.usage.model.UsageResult
import dev.skillbill.desktop.core.domain.usage.model.UsageSessionStatus
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow

interface UsageRepository {
  suspend fun profileLookup(provider: UsageProvider, selectedProfile: String?): UsageProfileLookup

  fun observe(request: UsageRequest): Flow<UsageResult>

  fun acquireDemand(demand: UsageDemand): UsageDemandResult

  fun updateDemand(demand: UsageDemand): UsageDemandResult

  fun releaseDemand(ownerId: String): UsageDemandResult

  fun refresh(request: UsageRequest): UsageRefreshResult

  fun sessionStatus(request: UsageRequest): UsageSessionStatus
}

interface UsageObservationSink {
  fun bindSession(logicalSessionId: String, profile: UsageProfileLookup, processGeneration: Long)

  fun recordSessionStatus(
    logicalSessionId: String,
    processGeneration: Long,
    permission: RequestPermission,
    warning: Boolean,
    observedAt: Instant,
  )

  fun revokeSession(logicalSessionId: String, processGeneration: Long)
}
