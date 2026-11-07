package dev.skillbill.desktop.core.data.usage

import dev.skillbill.desktop.core.common.ApplicationCoroutineScope
import dev.skillbill.desktop.core.common.HostEnvironment
import dev.skillbill.desktop.core.domain.settings.AppSettings
import dev.skillbill.desktop.core.domain.usage.model.UsageProvider
import dev.skillbill.desktop.core.domain.usage.model.UsageResult
import dev.skillbill.desktop.core.domain.usage.model.UsageUnavailableCode
import dev.skillbill.desktop.core.domain.usage.model.UsageUnavailableReason
import dev.skillbill.desktop.core.testing.FakeAppSettingsRepository
import dev.skillbill.desktop.core.data.usage.codex.CodexUsageReader
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.test.runTest

class UsageProfileResolverTest {
  @Test
  fun `selected homes override inherited provider homes while Claude default removes the explicit variable`() {
    val host = HostEnvironment(
      mapOf(
        "CODEX_HOME" to "/shell/codex",
        "CURSOR_CONFIG_DIR" to "/shell/cursor",
        "CLAUDE_CONFIG_DIR" to "/shell/claude",
      ),
      "/home/operator",
      "",
    )
    val resolver = UsageProfileResolver(host)

    assertEquals("/selected/codex", resolver.codex("/selected/codex").environment["CODEX_HOME"])
    val explicitDefault = resolver.codex("/home/operator/.codex")
    assertEquals("/home/operator/.codex", explicitDefault.lookup.configurationReference)
    assertEquals("/home/operator/.codex", resolver.resolveCodex(explicitDefault.lookup).environment["CODEX_HOME"])
    assertEquals("/selected/cursor", resolver.cursor("/selected/cursor").environment["CURSOR_CONFIG_DIR"])
    assertEquals("/shell/codex", resolver.codex(null).lookup.configurationReference)
    assertEquals("/shell/cursor", resolver.cursor(null).lookup.configurationReference)
    assertEquals("/shell/claude", resolver.claude(null).environment["CLAUDE_CONFIG_DIR"])
    assertEquals("/selected/claude", resolver.claude("/selected/claude").environment["CLAUDE_CONFIG_DIR"])
    assertEquals(UsageProvider.Claude, resolver.claude("/home/operator/.claude").lookup.provider)
    assertEquals("/home/operator/.claude", resolver.claude("/home/operator/.claude").lookup.configurationReference)
    assertFalse("CLAUDE_CONFIG_DIR" in resolver.claude("/home/operator/.claude").environment)

    val dockResolver = UsageProfileResolver(HostEnvironment(emptyMap(), "/home/operator", ""))
    assertEquals("/home/operator/.claude", dockResolver.claude(null).lookup.configurationReference)
    assertFalse("CLAUDE_CONFIG_DIR" in dockResolver.claude(null).environment)
  }

  @Test
  fun `Claude profile lookup follows chat profile before the selected setting`() = runTest {
    val host = HostEnvironment(emptyMap(), "/home/operator", "")
    val settings = FakeAppSettingsRepository(AppSettings.DEFAULT.copy(claudeConfigDir = "/settings/claude"))
    val repository = ProviderUsageRepository(UsageProfileResolver(host), settings, CodexUsageReader { _ ->
      UsageResult.Unavailable(UsageUnavailableReason(UsageUnavailableCode.ProviderUnavailable))
    }, ApplicationCoroutineScope(backgroundScope), object : Clock {
      override fun now() = Instant.fromEpochMilliseconds(10_000)
    })

    assertEquals("/chat/claude", repository.profileLookup(UsageProvider.Claude, "/chat/claude").configurationReference)
    assertEquals("/settings/claude", repository.profileLookup(UsageProvider.Claude, null).configurationReference)
  }
}
