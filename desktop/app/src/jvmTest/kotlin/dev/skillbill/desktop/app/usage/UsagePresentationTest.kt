package dev.skillbill.desktop.app.usage

import dev.skillbill.desktop.core.domain.usage.model.OmissionSemantics
import dev.skillbill.desktop.core.domain.usage.model.RequestPermission
import dev.skillbill.desktop.core.domain.usage.model.SessionUsageScope
import dev.skillbill.desktop.core.domain.usage.model.UsageCapability
import dev.skillbill.desktop.core.domain.usage.model.UsageFreshness
import dev.skillbill.desktop.core.domain.usage.model.UsageMeter
import dev.skillbill.desktop.core.domain.usage.model.UsageMeterScope
import dev.skillbill.desktop.core.domain.usage.model.UsageObservation
import dev.skillbill.desktop.core.domain.usage.model.UsageObservationSubject
import dev.skillbill.desktop.core.domain.usage.model.UsagePeriod
import dev.skillbill.desktop.core.domain.usage.model.UsageProvider
import dev.skillbill.desktop.core.domain.usage.model.UsageRefreshState
import dev.skillbill.desktop.core.domain.usage.model.UsageResult
import dev.skillbill.desktop.core.domain.usage.model.UsageSource
import dev.skillbill.desktop.core.domain.usage.model.UsageUnavailableCode
import dev.skillbill.desktop.core.domain.usage.model.UsageUnavailableReason
import dev.skillbill.desktop.core.domain.usage.model.UsageValue
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class UsagePresentationTest {
  private val now = Instant.parse("2026-11-08T10:00:00Z")

  @Test
  fun `meters keep the ranked order with their reset countdown and time`() {
    val weekly = meter("weekly", "Weekly", 7 * 24 * 3600, 43.0, now + 2.days + 3.hours)
    val fiveHour = meter("five", "5 hour", 5 * 3600, 7.25, now + 90.minutes)
    val state = UsageSidebarState(provider = UsageProvider.Codex, result = snapshot(listOf(fiveHour, weekly))).toUsageUiState(now)

    assertEquals("Codex", state.provider)
    assertEquals("codex", state.agentId)
    assertEquals(listOf("5 hour", "Weekly"), state.meters.map { it.label })
    assertEquals(listOf("7.3%", "43%"), state.meters.map { it.valueText })
    assertEquals(listOf("1h 30m", "2d 3h"), state.meters.map { it.resetIn })
    assertEquals(null, state.message)
    val utc = fiveHour.toUiMeter(now, ZoneOffset.UTC)
    assertEquals("11:30", utc.resetAt)
  }

  @Test
  fun `only reported windows show, and a model window names its model`() {
    val weekly = meter("weekly", "Weekly", 7 * 24 * 3600, 40.0, now + 1.days)
    val opus = requireNotNull(UsageMeter.create(
      "opus", "Weekly", UsageMeterScope.Model("Opus"), now, now + 1.days, requireNotNull(UsagePeriod.Rolling.create(7 * 24 * 3600)),
      requireNotNull(UsageValue.PercentageUsed.create(10.0)),
    ))

    assertEquals(listOf("Weekly"), UsageSidebarState(UsageProvider.Codex, snapshot(listOf(weekly))).toUsageUiState(now).meters.map { it.label })
    assertEquals(listOf("Weekly", "Weekly (Opus)"), UsageSidebarState(UsageProvider.Claude, snapshot(listOf(weekly, opus))).toUsageUiState(now).meters.map { it.label })
  }

  @Test
  fun `only a sign-in failure says not signed in`() {
    val signedOut = UsageSidebarState(UsageProvider.Codex, UsageResult.SignInRequired(reason)).toUsageUiState(now)
    val failed = UsageSidebarState(UsageProvider.Codex, UsageResult.TransientFailure(reason)).toUsageUiState(now)

    assertEquals("Not signed in", signedOut.message)
    assertEquals("Couldn't read usage", failed.message)
    assertTrue(failed.meters.isEmpty())
  }

  @Test
  fun `no reading yet says what will bring one instead of no data`() {
    fun messageFor(provider: UsageProvider, code: UsageUnavailableCode) =
      UsageSidebarState(provider, UsageResult.Unavailable(UsageUnavailableReason(code))).toUsageUiState(now).message

    assertEquals("Shows after the next Claude reply", messageFor(UsageProvider.Claude, UsageUnavailableCode.SessionObservationUnavailable))
    assertEquals("Loading…", messageFor(UsageProvider.Codex, UsageUnavailableCode.ProfileUnverified))
    assertEquals("Loading…", UsageSidebarState(UsageProvider.Codex).toUsageUiState(now).message)
    assertEquals("Couldn't read usage", UsageSidebarState(UsageProvider.Codex, unavailableReason = "Usage observation failed").toUsageUiState(now).message)
  }

  @Test
  fun `a Claude session warning without meters reads as near limit`() {
    val observation = UsageObservation(
      subject = UsageObservationSubject.Session(SessionUsageScope("session", 1)),
      collectionTime = now,
      sourceObservationTime = null,
      source = UsageSource(UsageProvider.Claude, UsageCapability.SessionStatusOnly),
      freshness = UsageFreshness.Current,
      refreshState = UsageRefreshState.Idle,
      meters = emptyList(),
      requestPermission = RequestPermission.Allowed,
      sessionWarning = true,
      statusObservedAt = now,
    )
    val state = UsageSidebarState(UsageProvider.Claude, UsageResult.Snapshot(observation, OmissionSemantics.Unknown)).toUsageUiState(now)

    assertEquals("Near limit", state.message)
  }

  private val reason = UsageUnavailableReason(UsageUnavailableCode.TransientFailure)

  private fun meter(id: String, label: String, seconds: Long, percent: Double, resetAt: Instant) = requireNotNull(UsageMeter.create(
    id, label, UsageMeterScope.General, now, resetAt, requireNotNull(UsagePeriod.Rolling.create(seconds)),
    requireNotNull(UsageValue.PercentageUsed.create(percent)),
  ))

  private fun snapshot(meters: List<UsageMeter>) = UsageResult.Snapshot(
    UsageObservation(
      subject = UsageObservationSubject.Session(SessionUsageScope("session", 1)),
      collectionTime = now,
      sourceObservationTime = null,
      source = UsageSource(UsageProvider.Codex, UsageCapability.NumericSnapshot),
      freshness = UsageFreshness.Current,
      refreshState = UsageRefreshState.Complete,
      meters = meters,
      requestPermission = RequestPermission.Allowed,
    ),
    OmissionSemantics.Unknown,
  )
}
