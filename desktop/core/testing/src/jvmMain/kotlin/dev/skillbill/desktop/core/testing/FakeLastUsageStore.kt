package dev.skillbill.desktop.core.testing

import dev.skillbill.desktop.core.domain.usage.LastUsageStore
import dev.skillbill.desktop.core.domain.usage.StoredUsageReading
import dev.skillbill.desktop.core.domain.usage.model.UsageProvider

/** In-memory [LastUsageStore] that keeps the newest reading per profile, like the real store. */
class FakeLastUsageStore(var agent: String? = null) : LastUsageStore {
  val readings = mutableMapOf<Pair<UsageProvider, String?>, StoredUsageReading>()

  override suspend fun lastAgent(): String? = agent

  override suspend fun setLastAgent(agentId: String) {
    agent = agentId
  }

  override suspend fun lastReading(provider: UsageProvider, configurationReference: String?): StoredUsageReading? =
    readings[provider to configurationReference]

  override suspend fun setLastReading(provider: UsageProvider, configurationReference: String?, reading: StoredUsageReading) {
    val key = provider to configurationReference
    val known = readings[key]
    if (known == null || known.observedAt <= reading.observedAt) readings[key] = reading
  }
}
