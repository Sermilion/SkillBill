package dev.skillbill.desktop.app.usage

import dev.skillbill.desktop.core.domain.usage.model.DecimalQuantity
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
import dev.skillbill.desktop.core.domain.usage.model.UsageValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class UsagePresentationTest {
  private val now = Instant.parse("2026-11-08T10:00:00Z")

  @Test
  fun `rounding a nearly full track does not report an exhausted allowance`() {
    val amounts = requireNotNull(UsageValue.AmountUsed.create(
      requireNotNull(DecimalQuantity.create("999999999", 9, "credits")),
      requireNotNull(DecimalQuantity.create("1000000000", 9, "credits")),
    ))
    val values = listOf(requireNotNull(UsageValue.PercentageUsed.create(99.9999999)), amounts)

    values.forEach { value ->
      val meter = requireNotNull(UsageMeter.create("pool", "Pool", UsageMeterScope.General, now, null, UsagePeriod.Unknown, value))
      val ui = meter.toUiMeter(now)

      assertEquals(1f, ui.progress)
      assertTrue(ui.warning)
      assertFalse(ui.exhausted)
      assertEquals("Near limit", ui.stateText)
    }
  }

  @Test
  fun `a Claude session warning remains visible even when requests are allowed`() {
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
    val state = UsageSidebarState(
      provider = UsageProvider.Claude,
      accountLabel = "Current local account",
      result = UsageResult.Snapshot(observation, OmissionSemantics.Unknown),
    ).toUsageUiState(now, null)

    assertEquals("Session usage warning", state.status)
    assertEquals("Allowed", state.requestPermission)
    assertEquals("", state.account)
    assertEquals("Status updated 0 seconds ago", state.observationAge)
    assertTrue(state.sessionOnly)
    assertTrue(state.detailMeters.isEmpty())
  }
}
