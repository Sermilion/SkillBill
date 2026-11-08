package dev.skillbill.desktop.core.domain.usage

import dev.skillbill.desktop.core.domain.usage.model.RequestPermission
import dev.skillbill.desktop.core.domain.usage.model.UsageMeter
import dev.skillbill.desktop.core.domain.usage.model.UsageProvider
import kotlin.time.Instant

/** A session-reported allowance reading kept across restarts, so the usage card can show it before a new session reports. */
data class StoredUsageReading(
  val permission: RequestPermission,
  val warning: Boolean,
  val observedAt: Instant,
  val meters: List<UsageMeter>,
)

/**
 * What the usage card last showed, kept across restarts. Reads return null and writes do nothing when the store fails;
 * the card then behaves as it would on a first launch.
 */
interface LastUsageStore {
  /** The agent id the usage card last showed, or null before it showed any. */
  suspend fun lastAgent(): String?

  suspend fun setLastAgent(agentId: String)

  /** The newest reading [provider] reported for the profile at [configurationReference], or null. */
  suspend fun lastReading(provider: UsageProvider, configurationReference: String?): StoredUsageReading?

  /** Keeps [reading] unless the store already holds a newer one for the same profile. */
  suspend fun setLastReading(provider: UsageProvider, configurationReference: String?, reading: StoredUsageReading)

  /** Remembers nothing. */
  object None : LastUsageStore {
    override suspend fun lastAgent(): String? = null

    override suspend fun setLastAgent(agentId: String) = Unit

    override suspend fun lastReading(provider: UsageProvider, configurationReference: String?): StoredUsageReading? = null

    override suspend fun setLastReading(provider: UsageProvider, configurationReference: String?, reading: StoredUsageReading) = Unit
  }
}
