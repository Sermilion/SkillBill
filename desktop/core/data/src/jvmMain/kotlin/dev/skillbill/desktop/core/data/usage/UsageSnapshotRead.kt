package dev.skillbill.desktop.core.data.usage

import dev.skillbill.desktop.core.domain.usage.StoredUsageReading
import kotlin.time.Instant

sealed interface UsageSnapshotRead {
  data class Success(val reading: StoredUsageReading) : UsageSnapshotRead
  data object SignInRequired : UsageSnapshotRead
  data class Throttled(val retryAt: Instant?) : UsageSnapshotRead
  data object Failed : UsageSnapshotRead
}
