package dev.skillbill.desktop.core.data.usage

import dev.skillbill.desktop.core.common.ApplicationCoroutineScope
import dev.skillbill.desktop.core.common.HostEnvironment
import dev.skillbill.desktop.core.data.usage.codex.CodexUsageReader
import dev.skillbill.desktop.core.domain.usage.model.UsageDemand
import dev.skillbill.desktop.core.domain.usage.model.UsageFreshness
import dev.skillbill.desktop.core.domain.usage.model.UsageProvider
import dev.skillbill.desktop.core.domain.usage.model.UsageRefreshResult
import dev.skillbill.desktop.core.domain.usage.model.UsageRequest
import dev.skillbill.desktop.core.domain.usage.model.UsageResult
import dev.skillbill.desktop.core.domain.usage.model.UsageUnavailableCode
import dev.skillbill.desktop.core.domain.usage.model.UsageUnavailableReason
import dev.skillbill.desktop.core.domain.usage.model.UsageValue
import dev.skillbill.desktop.core.testing.FakeAppSettingsRepository
import dev.skillbill.desktop.core.testing.RecordingDiagnostics
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DevinUsageTest {
  private val at = Instant.parse("2026-11-14T12:00:00Z")

  @Test
  fun `hidden daily placeholders do not hide valid weekly usage or zero extra balance`() {
    val parsed = assertIs<UsageSnapshotRead.Success>(parseDevinUsage(quota, at))
    assertEquals(listOf("Weekly", "Extra balance"), parsed.reading.meters.map { it.label })
    assertEquals(2.0, assertIs<UsageValue.PercentageUsed>(parsed.reading.meters[0].value).percent)
    assertEquals("0", assertIs<UsageValue.CreditBalance>(parsed.reading.meters[1].value).amount.unscaledDigits)
    val daily = quota.replace("\"hide_daily_quota\":true", "\"hide_daily_quota\":false")
      .replace("\"daily_percentage\":null", "\"daily_percentage\":0")
      .replace("\"daily_reset_at\":null", "\"daily_reset_at\":\"2026-11-15T08:00:00Z\"")
    assertEquals(listOf("Daily", "Weekly", "Extra balance"), assertIs<UsageSnapshotRead.Success>(parseDevinUsage(daily, at)).reading.meters.map { it.label })
    for (bad in listOf(quota.replace("\"weekly_percentage\":2", "\"weekly_percentage\":101"),
      quota.replace("2026-11-15T08:00:00Z", "bad-date"), quota.replace("\"overage_balance\":0", "\"overage_balance\":-1"), "{}")) {
      assertEquals(UsageSnapshotRead.Failed, parseDevinUsage(bad, at))
    }
  }

  @Test
  fun `collector uses CLI credentials and XDG paths without following redirects or accepting an account changed during the request`() = runTest {
    val home = createTempDirectory("devin-usage")
    try {
      val data = Files.createDirectories(home.resolve("data/devin"))
      val config = Files.createDirectories(home.resolve("config/devin"))
      val credentials = data.resolve("credentials.toml")
      Files.writeString(credentials, "windsurf_api_key = \"test-token\"\n")
      Files.writeString(config.resolve("config.json"), """{"devin":{"org_id":"org_test"}}""")
      var changeAccount = false
      val requests = mutableListOf<URI>()
      val connections = mutableListOf<HttpURLConnection>()
      val collector = DevinUsageCollector(HostEnvironment(mapOf("XDG_DATA_HOME" to home.resolve("data").toString(),
        "XDG_CONFIG_HOME" to home.resolve("config").toString()), home.toString(), ""), object : Clock { override fun now() = at }, RecordingDiagnostics()) { uri ->
        requests += uri
        object : HttpURLConnection(uri.toURL()) {
          override fun connect() = Unit
          override fun disconnect() = Unit
          override fun usingProxy() = false
          override fun getResponseCode() = 200
          override fun getInputStream(): ByteArrayInputStream {
            if (changeAccount) Files.writeString(credentials, "windsurf_api_key = 'another-token'\n")
            return ByteArrayInputStream(quota.toByteArray())
          }
        }.also { connections += it }
      }
      val identities = mutableListOf<String?>()
      val profile = UsageProfileResolver(HostEnvironment(emptyMap(), home.toString(), "")).devin().lookup
      assertIs<UsageSnapshotRead.Success>(collector.read(profile, identities::add))
      assertEquals("https://app.devin.ai/api/org_test/billing/quota/usage", requests.single().toString())
      assertEquals("Bearer test-token", connections.single().getRequestProperty("Authorization"))
      assertFalse(connections.single().instanceFollowRedirects)
      assertFalse(identities.single()!!.contains("test-token"))
      changeAccount = true
      assertEquals(UsageSnapshotRead.Failed, collector.read(profile, identities::add))
      assertNotEquals(identities.first(), identities.last())
      Files.delete(credentials)
      assertEquals(UsageSnapshotRead.SignInRequired, collector.read(profile, identities::add))
      assertNull(identities.last())
      assertEquals(2, requests.size)
    } finally { home.toFile().deleteRecursively() }
  }

  @Test
  fun `refresh retains stale numbers only for the same account and sign in recheck recovers after logout`() = runTest {
    val profiles = UsageProfileResolver(HostEnvironment(emptyMap(), "/operator", ""))
    val request = UsageRequest(profiles.devin().lookup)
    val responses = Channel<Pair<String?, UsageSnapshotRead>>(Channel.UNLIMITED)
    val publications = mutableListOf<UsageResult>()
    val clock = object : Clock { override fun now() = Instant.fromEpochMilliseconds(testScheduler.currentTime) }
    val repository = ProviderUsageRepository(profiles, FakeAppSettingsRepository(),
      CodexUsageReader { UsageResult.Unavailable(UsageUnavailableReason(UsageUnavailableCode.ProviderUnavailable)) },
      ApplicationCoroutineScope(backgroundScope), clock, jitter = UsageJitter { 1.0 },
      devinUsageReader = DevinUsageReader { _, identity -> responses.receive().let { identity(it.first); it.second } })
    try {
      repository.acquireDemand(UsageDemand("card", request, visible = true, active = false))
      backgroundScope.launch { repository.observeDemand("card").collect { publications += it } }
      responses.send("account-a" to parseDevinUsage(quota, clock.now()))
      runCurrent()
      val original = assertIs<UsageResult.Snapshot>(publications.last()).value.meters
      advanceTimeBy(10_000)
      assertIs<UsageRefreshResult.Started>(repository.refresh(request))
      responses.send("account-a" to UsageSnapshotRead.Failed)
      runCurrent()
      val stale = assertIs<UsageResult.Snapshot>(publications.last()).value
      assertEquals(original, stale.meters)
      assertEquals(UsageFreshness.Stale, stale.freshness)
      advanceTimeBy(30_000)
      responses.send("account-b" to UsageSnapshotRead.Failed)
      runCurrent()
      assertIs<UsageResult.TransientFailure>(publications.last())
      advanceTimeBy(60_000)
      responses.send(null to UsageSnapshotRead.SignInRequired)
      runCurrent()
      assertIs<UsageResult.SignInRequired>(publications.last())
      advanceTimeBy(10_000)
      assertIs<UsageRefreshResult.Started>(repository.recheckSignIn(request))
      responses.send("account-b" to parseDevinUsage(quota, clock.now()))
      runCurrent()
      assertEquals(UsageFreshness.Current, assertIs<UsageResult.Snapshot>(publications.last()).value.freshness)
    } finally { repository.closeUsage() }
  }

  private companion object {
    val quota = """{"is_quota_plan":true,"has_quota_allocation":true,"daily_percentage":null,"weekly_percentage":2,"daily_reset_at":null,"weekly_reset_at":"2026-11-15T08:00:00Z","overage_balance":0,"hide_daily_quota":true}"""
  }
}
