package dev.skillbill.desktop.core.data.usage

import dev.skillbill.desktop.core.common.HostEnvironment
import dev.skillbill.desktop.core.testing.FakeAppSettingsRepository
import dev.skillbill.desktop.core.domain.usage.model.RequestPermission
import dev.skillbill.desktop.core.domain.usage.model.UsageProfileLookup
import dev.skillbill.desktop.core.domain.usage.model.UsageProvider
import dev.skillbill.desktop.core.domain.usage.model.UsageRequest
import dev.skillbill.desktop.core.domain.usage.model.UsageSessionStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Instant

class ProviderUsageRepositoryTest {
  @Test
  fun `late status and close from a replaced Claude process cannot clear or replace its status`() {
    val host = HostEnvironment(emptyMap(), "/home/operator", "")
    val profiles = UsageProfileResolver(host)
    val repository = ProviderUsageRepository(profiles, FakeAppSettingsRepository())
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
    val fixedClock = object : Clock {
      override fun now() = Instant.fromEpochMilliseconds(50_000)
    }
  }
}
