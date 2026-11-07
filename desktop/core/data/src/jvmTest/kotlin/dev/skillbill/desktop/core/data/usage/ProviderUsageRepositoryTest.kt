package dev.skillbill.desktop.core.data.usage

import dev.skillbill.desktop.core.common.ApplicationCoroutineScope
import dev.skillbill.desktop.core.common.HostEnvironment
import dev.skillbill.desktop.core.testing.FakeAppSettingsRepository
import dev.skillbill.desktop.core.domain.usage.model.RequestPermission
import dev.skillbill.desktop.core.domain.usage.model.UsageProfileLookup
import dev.skillbill.desktop.core.domain.usage.model.UsageProvider
import dev.skillbill.desktop.core.domain.usage.model.UsageRequest
import dev.skillbill.desktop.core.domain.usage.model.UsageResult
import dev.skillbill.desktop.core.domain.usage.model.UsageUnavailableCode
import dev.skillbill.desktop.core.domain.usage.model.UsageUnavailableReason
import dev.skillbill.desktop.core.domain.usage.model.UsageSessionStatus
import dev.skillbill.desktop.core.data.usage.codex.CodexUsageReader
import dev.skillbill.desktop.core.domain.usage.model.OmissionSemantics
import dev.skillbill.desktop.core.domain.usage.model.UsageCapability
import dev.skillbill.desktop.core.domain.usage.model.UsageFreshness
import dev.skillbill.desktop.core.domain.usage.model.UsageMeter
import dev.skillbill.desktop.core.domain.usage.model.UsageMeterScope
import dev.skillbill.desktop.core.domain.usage.model.UsageObservation
import dev.skillbill.desktop.core.domain.usage.model.UsageObservationSubject
import dev.skillbill.desktop.core.domain.usage.model.UsagePeriod
import dev.skillbill.desktop.core.domain.usage.model.UsageRefreshState
import dev.skillbill.desktop.core.domain.usage.model.UsageSource
import dev.skillbill.desktop.core.domain.usage.model.UsageValue
import dev.skillbill.desktop.core.domain.usage.model.VerifiedUsageIdentity
import dev.skillbill.desktop.core.domain.usage.model.VerifiedUsageSubject
import dev.skillbill.desktop.core.domain.usage.model.UsageAuthMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.time.Clock
import kotlin.time.Instant

class ProviderUsageRepositoryTest {
  @Test
  fun `Codex capability path blocks numeric collector snapshots without a verified identity contract`() = runTest {
    val host = HostEnvironment(emptyMap(), "/home/operator", "")
    val profile = UsageProfileLookup(UsageProvider.Codex, null, 4)
    val observedAt = Instant.fromEpochMilliseconds(1000)
    val numeric = UsageMeter.create(
      "standard",
      "Codex",
      UsageMeterScope.General,
      observedAt,
      null,
      UsagePeriod.Rolling.create(3600)!!,
      UsageValue.PercentageUsed.create(20.0)!!,
    )!!
    val snapshot = UsageResult.Snapshot(
      UsageObservation(
        UsageObservationSubject.Account(VerifiedUsageSubject(profile, VerifiedUsageIdentity("observed"), UsageAuthMode.Account)),
        observedAt,
        null,
        UsageSource(UsageProvider.Codex, UsageCapability.NumericSnapshot),
        UsageFreshness.Current,
        UsageRefreshState.Complete,
        listOf(numeric),
        RequestPermission.Unknown,
      ),
      OmissionSemantics.OmittedFieldsAreUnchanged,
    )
    val reader = CodexUsageReader { _ -> snapshot }
    val repository = ProviderUsageRepository(UsageProfileResolver(host), FakeAppSettingsRepository(), reader, ApplicationCoroutineScope(backgroundScope))

    val result = repository.observe(UsageRequest(profile)).first()

    assertEquals(UsageResult.Unavailable(UsageUnavailableReason(UsageUnavailableCode.ProfileUnverified)), result)
  }

  @Test
  fun `late status and close from a replaced Claude process cannot clear or replace its status`() = runTest {
    val host = HostEnvironment(emptyMap(), "/home/operator", "")
    val profiles = UsageProfileResolver(host)
    val repository = ProviderUsageRepository(profiles, FakeAppSettingsRepository(), unusedCodexReader, ApplicationCoroutineScope(backgroundScope))
    val original = UsageProfileLookup(UsageProvider.Claude, "/profile/one", 1)
    val replacement = UsageProfileLookup(UsageProvider.Claude, "/profile/two", 2)
    val request = UsageRequest(replacement, "logical-session")

    repository.bindSession("logical-session", original, 10)
    repository.recordSessionStatus("logical-session", 10, RequestPermission.Rejected, false, fixedClock.now())
    repository.bindSession("logical-session", replacement, 11)
    repository.recordSessionStatus("logical-session", 10, RequestPermission.Allowed, false, fixedClock.now())
    repository.revokeSession("logical-session", 10)
    assertEquals(UsageSessionStatus.Unavailable, repository.sessionStatus(request))

    repository.recordSessionStatus("logical-session", 11, RequestPermission.Allowed, true, fixedClock.now())
    repository.revokeSession("logical-session", 10)
    val status = repository.sessionStatus(request) as UsageSessionStatus.Observed
    assertEquals(11, status.scope.processGeneration)
    assertEquals("/profile/two", status.profile.configurationReference)
    assertEquals(RequestPermission.Allowed, status.permission)
    assertEquals(true, status.warning)
  }

  private companion object {
    val unusedCodexReader = CodexUsageReader { _ ->
      UsageResult.Unavailable(UsageUnavailableReason(UsageUnavailableCode.ProviderUnavailable))
    }
    val fixedClock = object : Clock {
      override fun now() = Instant.fromEpochMilliseconds(50_000)
    }
  }
}
