package dev.skillbill.desktop.core.data.usage

import dev.skillbill.desktop.core.common.ApplicationCoroutineScope
import dev.skillbill.desktop.core.common.HostEnvironment
import dev.skillbill.desktop.core.data.usage.codex.CodexUsageReader
import dev.skillbill.desktop.core.domain.usage.model.RequestPermission
import dev.skillbill.desktop.core.domain.usage.model.UsageDemand
import dev.skillbill.desktop.core.domain.usage.model.UsageProvider
import dev.skillbill.desktop.core.domain.usage.model.UsageRefreshResult
import dev.skillbill.desktop.core.domain.usage.model.UsageRequest
import dev.skillbill.desktop.core.domain.usage.model.UsageResult
import dev.skillbill.desktop.core.domain.usage.model.UsageValue
import dev.skillbill.desktop.core.domain.usage.model.UsageUnavailableCode
import dev.skillbill.desktop.core.domain.usage.model.UsageUnavailableReason
import dev.skillbill.desktop.core.testing.FakeAppSettingsRepository
import dev.skillbill.desktop.core.testing.FakeLastUsageStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class ClaudeUsageRefreshTest {
  @Test
  fun `idle visible Claude cards share a five minute pull and cancel it after the last card closes`() = runTest {
    val profiles = UsageProfileResolver(HostEnvironment(emptyMap(), "/operator", ""))
    val request = UsageRequest(profiles.claude(null).lookup)
    val reads = Channel<CompletableDeferred<ClaudeUsageRead>>(Channel.UNLIMITED)
    var cancelled = false
    val clock = object : Clock { override fun now() = Instant.fromEpochMilliseconds(testScheduler.currentTime) }
    val repository = ProviderUsageRepository(
      profiles, FakeAppSettingsRepository(), unusedCodex, ApplicationCoroutineScope(backgroundScope), clock,
      claudeUsageReader = ClaudeUsageReader {
        val pending = CompletableDeferred<ClaudeUsageRead>()
        reads.send(pending)
        try { pending.await() } finally { if (!pending.isCompleted) cancelled = true }
      },
    )
    try {
      repository.acquireDemand(UsageDemand("one", request, visible = true, active = false))
      repository.acquireDemand(UsageDemand("two", request, visible = true, active = false))
      runCurrent()
      val initial = reads.tryReceive().getOrThrow()
      assertTrue(reads.tryReceive().isFailure)
      assertIs<UsageRefreshResult.Coalesced>(repository.refresh(request))
      initial.complete(reading(clock.now()))
      runCurrent()
      advanceTimeBy(5.minutes)
      runCurrent()
      reads.tryReceive().getOrThrow()
      assertTrue(reads.tryReceive().isFailure)
      repository.releaseDemand("one")
      runCurrent()
      assertEquals(false, cancelled)
      repository.releaseDemand("two")
      runCurrent()
      assertTrue(cancelled)
      advanceTimeBy(20.minutes)
      runCurrent()
      assertTrue(reads.tryReceive().isFailure)
    } finally {
      repository.closeUsage()
    }
  }

  @Test
  fun `manual failure and sign in expiry preserve Claude meters and recheck can recover without a goal`() = runTest {
    val profiles = UsageProfileResolver(HostEnvironment(emptyMap(), "/operator", ""))
    val request = UsageRequest(profiles.claude(null).lookup)
    val reads = Channel<CompletableDeferred<ClaudeUsageRead>>(Channel.UNLIMITED)
    val publications = mutableListOf<UsageResult>()
    val store = FakeLastUsageStore()
    val clock = object : Clock { override fun now() = Instant.fromEpochMilliseconds(testScheduler.currentTime) }
    val repository = ProviderUsageRepository(
      profiles, FakeAppSettingsRepository(), unusedCodex, ApplicationCoroutineScope(backgroundScope), clock,
      jitter = UsageJitter { 1.0 }, lastUsage = store,
      claudeUsageReader = ClaudeUsageReader { CompletableDeferred<ClaudeUsageRead>().also { reads.send(it) }.await() },
    )
    try {
      repository.acquireDemand(UsageDemand("card", request, visible = true, active = false))
      backgroundScope.launch { repository.observeDemand("card").collect { publications += it } }
      runCurrent()
      reads.tryReceive().getOrThrow().complete(reading(clock.now()))
      runCurrent()
      val original = assertIs<UsageResult.Snapshot>(publications.last()).value.meters
      advanceTimeBy(10_000)
      assertIs<UsageRefreshResult.Started>(repository.refresh(request))
      runCurrent()
      reads.tryReceive().getOrThrow().complete(ClaudeUsageRead.Failed)
      runCurrent()
      assertEquals(original, assertIs<UsageResult.Snapshot>(publications.last()).value.meters)
      advanceTimeBy(30_000)
      runCurrent()
      reads.tryReceive().getOrThrow().complete(ClaudeUsageRead.SignInRequired)
      runCurrent()
      assertEquals(original, assertIs<UsageResult.Snapshot>(publications.last()).value.meters)
      advanceTimeBy(5.minutes)
      runCurrent()
      assertTrue(reads.tryReceive().isFailure)
      assertIs<UsageRefreshResult.Started>(repository.recheckSignIn(request))
      runCurrent()
      val fresh = reading(clock.now())
      reads.tryReceive().getOrThrow().complete(fresh)
      runCurrent()
      assertEquals(fresh.reading.meters, assertIs<UsageResult.Snapshot>(publications.last()).value.meters)
      assertEquals(fresh.reading, store.readings[UsageProvider.Claude to "/operator/.claude"])
    } finally {
      repository.closeUsage()
    }
  }

  @Test
  fun `OAuth windows preserve zero readings and model scope but reject malformed quotas`() {
    val at = Instant.fromEpochMilliseconds(0)
    val parsed = assertIs<ClaudeUsageRead.Success>(parseClaudeUsage(
      """{"five_hour":{"utilization":0,"resets_at":null},"seven_day":null,"seven_day_sonnet":{"utilization":86,"resets_at":"2026-11-10T12:00:00Z"},"extra_usage":{"utilization":15}}""", at,
    ))
    assertEquals(listOf("claude:five_hour", "claude:seven_day_sonnet"), parsed.reading.meters.map { it.bucketIdentity })
    assertEquals(0.0, assertIs<UsageValue.PercentageUsed>(parsed.reading.meters.first().value).percent)
    assertEquals(RequestPermission.Unknown, parsed.reading.permission)
    assertEquals(ClaudeUsageRead.Failed, parseClaudeUsage("""{"five_hour":{"utilization":101}}""", at))
    assertEquals(ClaudeUsageRead.Failed, parseClaudeUsage("{}", at))
    assertEquals(ClaudeUsageRead.Failed, parseClaudeUsage("not json", at))
  }

  private fun reading(at: Instant) = assertIs<ClaudeUsageRead.Success>(parseClaudeUsage(
    """{"five_hour":{"utilization":12,"resets_at":null},"seven_day":{"utilization":86,"resets_at":null}}""", at,
  ))

  private companion object {
    val unusedCodex = CodexUsageReader { UsageResult.Unavailable(UsageUnavailableReason(UsageUnavailableCode.ProviderUnavailable)) }
  }
}
