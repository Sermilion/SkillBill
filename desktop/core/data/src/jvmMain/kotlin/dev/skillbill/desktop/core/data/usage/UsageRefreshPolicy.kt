package dev.skillbill.desktop.core.data.usage

import dev.skillbill.desktop.core.domain.usage.model.UsageCapability
import dev.skillbill.desktop.core.domain.usage.model.UsageProvider
import dev.skillbill.desktop.core.domain.usage.model.UsageRefreshCapability
import dev.skillbill.desktop.core.domain.usage.model.UsageRefreshFamily
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

internal object UsageRefreshPolicy {
  val manualCooldown = 10.seconds

  fun expectedInterval(provider: UsageProvider, capability: UsageCapability, active: Boolean, sourceFloor: Duration): Duration? {
    if (capability != UsageCapability.NumericSnapshot) return null
    val productInterval = when (provider) {
      UsageProvider.Codex -> familyInterval(UsageRefreshFamily.CodexSnapshot, active)
      UsageProvider.Claude -> familyInterval(UsageRefreshFamily.ClaudeSnapshot, active)
    }
    return maxOf(productInterval, sourceFloor)
  }

  fun effectiveSourceFloor(capability: UsageRefreshCapability): Duration? = when (capability) {
    is UsageRefreshCapability.Pull -> maxOf(
      if (capability.family == UsageRefreshFamily.CodexSnapshot || capability.family == UsageRefreshFamily.ClaudeSnapshot) Duration.ZERO else familyInterval(capability.family, active = false),
      capability.minimumInterval,
    )
    UsageRefreshCapability.EventOnly, UsageRefreshCapability.ProbeOnly, UsageRefreshCapability.Unsupported -> null
  }

  fun expectedInterval(capability: UsageRefreshCapability, active: Boolean): Duration? = when (capability) {
    is UsageRefreshCapability.Pull -> maxOf(familyInterval(capability.family, active), effectiveSourceFloor(capability)!!)
    UsageRefreshCapability.EventOnly, UsageRefreshCapability.ProbeOnly, UsageRefreshCapability.Unsupported -> null
  }

  private fun familyInterval(family: UsageRefreshFamily, active: Boolean): Duration = when (family) {
    UsageRefreshFamily.CodexSnapshot -> if (active) 60.seconds else 5.minutes
    UsageRefreshFamily.ClaudeSnapshot -> 5.minutes
    UsageRefreshFamily.TeamSpend -> 5.minutes
    UsageRefreshFamily.AggregatedActivity -> 1.hours
  }

  fun eligibleAt(lastSuccess: Instant?, interval: Duration): Instant? = lastSuccess?.plus(interval)

  fun selectionDue(lastSuccessfulObservation: Instant?, now: Instant): Boolean =
    lastSuccessfulObservation == null || now - lastSuccessfulObservation >= 60.seconds

  fun staleAt(observedAt: Instant, capturedExpectedInterval: Duration): Instant =
    observedAt + capturedExpectedInterval * 2

  fun retryDelay(consecutiveFailures: Int, jitter: Double): Duration {
    val baseSeconds = when {
      consecutiveFailures <= 1 -> 30L
      consecutiveFailures >= 6 -> 900L
      else -> 30L shl (consecutiveFailures - 1)
    }
    val boundedJitter = jitter.takeIf { it.isFinite() }?.coerceIn(0.8, 1.2) ?: 1.0
    return (baseSeconds * 1_000.0 * boundedJitter).toLong().milliseconds.coerceIn(30.seconds, 900.seconds)
  }
}
