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

  private companion object {
    val unusedCodexReader = CodexUsageReader { _ ->
      UsageResult.Unavailable(UsageUnavailableReason(UsageUnavailableCode.ProviderUnavailable))
    }
    val fixedClock = object : Clock {
      override fun now() = Instant.fromEpochMilliseconds(50_000)
    }
  }
}
