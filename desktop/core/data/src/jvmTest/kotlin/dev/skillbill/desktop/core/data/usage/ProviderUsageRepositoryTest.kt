package dev.skillbill.desktop.core.data.usage

import dev.skillbill.desktop.core.common.ApplicationCoroutineScope
import dev.skillbill.desktop.core.common.HostEnvironment
import dev.skillbill.desktop.core.testing.FakeAppSettingsRepository
import dev.skillbill.desktop.core.testing.FakeLastUsageStore
import dev.skillbill.desktop.core.domain.usage.StoredUsageReading
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
import dev.skillbill.desktop.core.domain.usage.model.UsageDemand
import dev.skillbill.desktop.core.domain.usage.model.UsageDemandResult
import dev.skillbill.desktop.core.domain.usage.model.UsageRefreshCapability
import dev.skillbill.desktop.core.domain.usage.model.UsageRefreshFamily
import dev.skillbill.desktop.core.domain.usage.model.UsageRefreshResult
import dev.skillbill.desktop.core.domain.usage.model.UsageRefreshAvailability
import dev.skillbill.desktop.core.domain.usage.model.VerifiedUsageWorkspaceScope
import dev.skillbill.desktop.core.data.usage.codex.CodexAcquisitionContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.time.Clock
import kotlin.time.Instant

class ProviderUsageRepositoryTest {
  @Test
  fun `verified sign in recovery clears cached rejection and resumes periodic refresh`() = runTest {
    val profiles = UsageProfileResolver(HostEnvironment(emptyMap(), "/home/operator", ""))
    val request = UsageRequest(profiles.codex(null).lookup)
    val pendingReads = Channel<Pair<CodexAcquisitionContext, CompletableDeferred<UsageResult>>>(Channel.UNLIMITED)
    val reader = object : CodexUsageReader {
      override val refreshCapability = UsageRefreshCapability.Pull(UsageRefreshFamily.CodexSnapshot)

      override suspend fun read(context: CodexAcquisitionContext): UsageResult {
        val result = CompletableDeferred<UsageResult>()
        pendingReads.send(context to result)
        return result.await()
      }
    }
    val clock = object : Clock {
      override fun now() = Instant.fromEpochMilliseconds(50_000) + testScheduler.currentTime.milliseconds
    }
    val repository = ProviderUsageRepository(profiles, FakeAppSettingsRepository(), reader, ApplicationCoroutineScope(backgroundScope), clock)
    val publications = mutableListOf<UsageResult>()
    repository.acquireDemand(UsageDemand("window", request, visible = true, active = true))
    val observer = backgroundScope.launch { repository.observeDemand("window").collect { publications += it } }
    try {
      runCurrent()
      pendingReads.tryReceive().getOrThrow().second.complete(
        UsageResult.SignInRequired(UsageUnavailableReason(UsageUnavailableCode.ProfileUnverified)),
      )
      runCurrent()
      assertIs<UsageResult.SignInRequired>(publications.last())
      advanceTimeBy(10_000)
      runCurrent()
      assertIs<UsageRefreshResult.Started>(repository.recheckSignIn(request))
      runCurrent()
      val recheck = pendingReads.tryReceive().getOrThrow()
      repository.acquireDemand(UsageDemand("second-window", request, visible = true, active = true))
      val subject = VerifiedUsageSubject(
        request.profile, VerifiedUsageIdentity("account", VerifiedUsageWorkspaceScope.VerifiedNonWorkspace), UsageAuthMode.Account,
      )
      recheck.second.complete(UsageResult.Snapshot(
        UsageObservation(
          subject = UsageObservationSubject.Account(subject),
          collectionTime = clock.now(),
          sourceObservationTime = null,
          source = UsageSource(UsageProvider.Codex, UsageCapability.NumericSnapshot),
          freshness = UsageFreshness.Current,
          refreshState = UsageRefreshState.Complete,
          meters = listOf(UsageMeter.create(
            "standard", "Codex", UsageMeterScope.General, clock.now(), null, UsagePeriod.Unknown,
            UsageValue.PercentageUsed.create(20.0)!!,
          )!!),
          requestPermission = RequestPermission.Allowed,
        ),
        OmissionSemantics.OmittedFieldsAreRemoved,
        recheck.first.reservationId,
      ))
      runCurrent()
      assertIs<UsageResult.Snapshot>(publications.last())
      advanceTimeBy(10_000)
      runCurrent()
      assertIs<UsageRefreshAvailability.Ready>(repository.refreshAvailability(request).first())
      assertIs<UsageRefreshResult.Started>(repository.refresh(request))
      runCurrent()
      val manual = pendingReads.tryReceive().getOrThrow()
      manual.second.complete(UsageResult.EmptySuccess(
        subject, UsageSource(UsageProvider.Codex, UsageCapability.NumericSnapshot), clock.now(),
        OmissionSemantics.OmittedFieldsAreUnchanged, manual.first.reservationId,
      ))
      runCurrent()
      advanceTimeBy(60_000)
      runCurrent()
      val periodic = pendingReads.tryReceive().getOrThrow()
      assertTrue(periodic.second.isActive)
      assertEquals(RequestPermission.Allowed, assertIs<UsageResult.Snapshot>(publications.last()).value.requestPermission)
      repository.releaseDemand("window")
      repository.releaseDemand("second-window")
      runCurrent()
      assertIs<UsageDemandResult.Rejected>(repository.updateDemand(UsageDemand("window", request, visible = true, active = true)))
      advanceTimeBy(60_000)
      runCurrent()
      assertIs<UsageRefreshAvailability.Ready>(repository.refreshAvailability(request).first())
    } finally {
      observer.cancelAndJoin()
      assertTrue(observer.isCancelled)
      repository.releaseDemand("window")
      repository.releaseDemand("second-window")
      repository.closeUsage()
      pendingReads.close()
    }
  }

  @Test
  fun `Codex capability path blocks numeric collector snapshots without a verified identity contract`() = runTest {
    val host = HostEnvironment(emptyMap(), "/home/operator", "")
    val profiles = UsageProfileResolver(host)
    val profile = profiles.codex(null).lookup
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
    val sourceReached = CompletableDeferred<Unit>()
    val reader = CodexUsageReader { context ->
      sourceReached.complete(Unit)
      snapshot.copy(reservationId = context.reservationId)
    }
    val repository = ProviderUsageRepository(profiles, FakeAppSettingsRepository(), reader, ApplicationCoroutineScope(backgroundScope), fixedClock)
    val publications = mutableListOf<UsageResult>()
    repository.acquireDemand(UsageDemand("window", UsageRequest(profile), visible = true, active = true))
    val observer = backgroundScope.launch { repository.observeDemand("window").collect { publications += it } }
    try {
      runCurrent()
      assertTrue(sourceReached.isCompleted)
      assertTrue(publications.none { it is UsageResult.Snapshot })
      assertEquals(UsageResult.Unavailable(UsageUnavailableReason(UsageUnavailableCode.ProfileUnverified)), publications.last())
    } finally {
      observer.cancelAndJoin()
      assertTrue(observer.isCancelled)
      repository.releaseDemand("window")
      repository.closeUsage()
    }
  }

  @Test
  fun `late status and close from a replaced Claude process cannot clear or replace its status`() = runTest {
    val host = HostEnvironment(emptyMap(), "/home/operator", "")
    val profiles = UsageProfileResolver(host)
    val repository = ProviderUsageRepository(profiles, FakeAppSettingsRepository(), unusedCodexReader, ApplicationCoroutineScope(backgroundScope), fixedClock)
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

  @Test
  fun `Claude meters reach the session card and stay visible to a new draft on the same profile`() = runTest {
    val repository = ProviderUsageRepository(
      UsageProfileResolver(HostEnvironment(emptyMap(), "/home/operator", "")),
      FakeAppSettingsRepository(),
      unusedCodexReader,
      ApplicationCoroutineScope(backgroundScope),
      fixedClock,
    )
    val profile = UsageProfileLookup(UsageProvider.Claude, "/home/operator/.claude", 1)
    val other = UsageProfileLookup(UsageProvider.Claude, "/home/operator/.claude-work", 2)
    fun meter(id: String, percent: Double) = UsageMeter.create(
      id, id, UsageMeterScope.General, fixedClock.now(), null, UsagePeriod.Unknown, UsageValue.PercentageUsed.create(percent)!!,
    )!!
    val fiveHour = meter("claude:five_hour", 7.0)
    val weekly = meter("claude:seven_day", 81.0)
    val session = mutableListOf<UsageResult>()
    val draft = mutableListOf<UsageResult>()
    val otherDraft = mutableListOf<UsageResult>()
    val observers = listOf(
      backgroundScope.launch { repository.observe(UsageRequest(profile, "chat")).collect { session += it } },
      backgroundScope.launch { repository.observe(UsageRequest(profile, "new-draft")).collect { draft += it } },
      backgroundScope.launch { repository.observe(UsageRequest(other)).collect { otherDraft += it } },
    )
    try {
      runCurrent()
      assertIs<UsageResult.Unavailable>(draft.last())

      repository.bindSession("chat", profile, 10)
      repository.recordSessionStatus("chat", 10, RequestPermission.Allowed, true, fixedClock.now(), listOf(fiveHour, weekly))
      repository.revokeSession("chat", 10)
      runCurrent()

      val live = assertIs<UsageResult.Snapshot>(session.first { it is UsageResult.Snapshot }).value
      assertEquals(listOf(weekly, fiveHour), live.cardCandidates)
      assertEquals(true, live.sessionWarning)
      val carried = assertIs<UsageResult.Snapshot>(draft.last()).value
      assertEquals(listOf(weekly, fiveHour), carried.popupMeters)
      assertEquals(RequestPermission.Allowed, carried.requestPermission)
      assertEquals(UsageResult.Unavailable(UsageUnavailableReason(UsageUnavailableCode.SessionObservationUnavailable)), otherDraft.last())
    } finally {
      observers.forEach { it.cancelAndJoin() }
      repository.closeUsage()
    }
  }

  @Test
  fun `a Claude reading from an earlier run shows until a session reports, and a live reading is stored`() = runTest {
    val stored = FakeLastUsageStore()
    val repository = ProviderUsageRepository(
      UsageProfileResolver(HostEnvironment(emptyMap(), "/home/operator", "")),
      FakeAppSettingsRepository(),
      unusedCodexReader,
      ApplicationCoroutineScope(backgroundScope),
      fixedClock,
      lastUsage = stored,
    )
    // The generation differs from the earlier run's; only the configuration directory has to match.
    val profile = UsageProfileLookup(UsageProvider.Claude, "/home/operator/.claude", 7)
    fun meter(id: String, percent: Double, at: Instant) = UsageMeter.create(
      id, id, UsageMeterScope.General, at, null, UsagePeriod.Unknown, UsageValue.PercentageUsed.create(percent)!!,
    )!!
    val earlierAt = Instant.fromEpochMilliseconds(1_000)
    val earlier = meter("claude:seven_day", 40.0, earlierAt)
    stored.readings[UsageProvider.Claude to "/home/operator/.claude"] =
      StoredUsageReading(RequestPermission.Allowed, false, earlierAt, listOf(earlier))
    val draft = mutableListOf<UsageResult>()
    val other = mutableListOf<UsageResult>()
    val observers = listOf(
      backgroundScope.launch { repository.observe(UsageRequest(profile, "draft")).collect { draft += it } },
      backgroundScope.launch {
        repository.observe(UsageRequest(UsageProfileLookup(UsageProvider.Claude, "/home/operator/.claude-work", 8))).collect { other += it }
      },
    )
    try {
      runCurrent()
      val restored = assertIs<UsageResult.Snapshot>(draft.last()).value
      assertEquals(listOf(earlier), restored.popupMeters)
      assertEquals(UsageFreshness.Stale, restored.freshness)
      assertIs<UsageResult.Unavailable>(other.last())

      val live = meter("claude:seven_day", 55.0, fixedClock.now())
      repository.bindSession("chat", profile, 10)
      repository.recordSessionStatus("chat", 10, RequestPermission.Allowed, false, fixedClock.now(), listOf(live))
      runCurrent()

      val current = assertIs<UsageResult.Snapshot>(draft.last()).value
      assertEquals(listOf(live), current.popupMeters)
      assertEquals(UsageFreshness.Current, current.freshness)
      assertEquals(listOf(live), stored.readings[UsageProvider.Claude to "/home/operator/.claude"]?.meters)

      // A status without windows keeps the stored numbers.
      repository.recordSessionStatus("chat", 10, RequestPermission.Allowed, true, fixedClock.now())
      runCurrent()
      assertEquals(listOf(live), stored.readings[UsageProvider.Claude to "/home/operator/.claude"]?.meters)
    } finally {
      observers.forEach { it.cancelAndJoin() }
      repository.closeUsage()
    }
  }

  @Test
  fun `an older Claude chat uses a newer profile reading including zero after reset`() = runTest {
    val repository = ProviderUsageRepository(
      UsageProfileResolver(HostEnvironment(emptyMap(), "/home/operator", "")),
      FakeAppSettingsRepository(), unusedCodexReader, ApplicationCoroutineScope(backgroundScope), fixedClock,
    )
    val profile = UsageProfileLookup(UsageProvider.Claude, "/home/operator/.claude", 1)
    fun reading(percent: Double, at: Instant) = listOf(UsageMeter.create(
      "claude:five_hour", "5 hour", UsageMeterScope.General, at, null, UsagePeriod.Unknown,
      UsageValue.PercentageUsed.create(percent)!!,
    )!!)
    val oldAt = Instant.fromEpochMilliseconds(1_000)
    val old = reading(59.0, oldAt)
    val fresh = reading(0.0, fixedClock.now())
    val publications = mutableListOf<UsageResult>()
    repository.bindSession("older-chat", profile, 1)
    repository.bindSession("newer-chat", profile, 2)
    val observer = backgroundScope.launch {
      repository.observe(UsageRequest(profile, "older-chat")).collect { publications += it }
    }
    try {
      repository.recordSessionStatus("older-chat", 1, RequestPermission.Allowed, false, oldAt, old)
      runCurrent()
      assertEquals(old, assertIs<UsageResult.Snapshot>(publications.last()).value.popupMeters)
      repository.recordSessionStatus("newer-chat", 2, RequestPermission.Allowed, false, fixedClock.now(), fresh)
      runCurrent()
      assertEquals(fresh, assertIs<UsageResult.Snapshot>(publications.last()).value.popupMeters)

      repository.recordSessionStatus("older-chat", 1, RequestPermission.Rejected, true, fixedClock.now())
      runCurrent()
      val statusOnly = assertIs<UsageResult.Snapshot>(publications.last()).value
      assertEquals(fresh, statusOnly.popupMeters)
      assertEquals(RequestPermission.Rejected, statusOnly.requestPermission)
      assertEquals(true, statusOnly.sessionWarning)
      repository.recordSessionStatus("older-chat", 1, RequestPermission.Allowed, false, oldAt, old)
      runCurrent()
      assertEquals(fresh, assertIs<UsageResult.Snapshot>(publications.last()).value.popupMeters)
    } finally {
      observer.cancelAndJoin()
      repository.closeUsage()
    }
  }

  @Test
  fun `profile observation follows bindings and falls back to current settings`() = runTest {
    val profiles = UsageProfileResolver(HostEnvironment(emptyMap(), "/home/operator", ""))
    val settings = FakeAppSettingsRepository()
    settings.setClaudeConfigDir("/settings/a")
    val repository = ProviderUsageRepository(profiles, settings, unusedCodexReader, ApplicationCoroutineScope(backgroundScope), fixedClock)
    val observed = mutableListOf<UsageProfileLookup>()
    val observer = backgroundScope.launch {
      repository.observeProfileLookup(UsageProvider.Claude, null, "logical-session").collect { observed += it }
    }
    try {
      runCurrent()
      assertEquals("/settings/a", observed.last().configurationReference)

      repository.bindSession("logical-session", UsageProfileLookup(UsageProvider.Claude, "/process/a", 1), 10)
      runCurrent()
      assertEquals("/process/a", observed.last().configurationReference)

      settings.setClaudeConfigDir("/settings/b")
      runCurrent()
      assertEquals("/process/a", observed.last().configurationReference)

      repository.bindSession("logical-session", UsageProfileLookup(UsageProvider.Claude, "/process/b", 2), 11)
      repository.revokeSession("logical-session", 10)
      runCurrent()
      assertEquals("/process/b", observed.last().configurationReference)

      repository.closeUsage()
      runCurrent()
      assertEquals("/settings/b", observed.last().configurationReference)
    } finally {
      observer.cancelAndJoin()
      repository.closeUsage()
    }
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
