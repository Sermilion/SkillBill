package dev.skillbill.desktop.core.data.usage

import dev.skillbill.desktop.core.common.ApplicationCoroutineScope
import dev.skillbill.desktop.core.common.HostEnvironment
import dev.skillbill.desktop.core.data.usage.codex.CodexAcquisitionContext
import dev.skillbill.desktop.core.data.usage.codex.CodexAcquisitionEvent
import dev.skillbill.desktop.core.data.usage.codex.CodexUsageReader
import dev.skillbill.desktop.core.data.usage.codex.model.CodexVerifiedSubjectConfirmation
import dev.skillbill.desktop.core.data.usage.codex.model.CodexVerifiedUsageObservation
import dev.skillbill.desktop.core.domain.usage.model.OmissionSemantics
import dev.skillbill.desktop.core.domain.usage.model.PartialUsageField
import dev.skillbill.desktop.core.domain.usage.model.RequestPermission
import dev.skillbill.desktop.core.domain.usage.model.UsageAuthMode
import dev.skillbill.desktop.core.domain.usage.model.UsageCapability
import dev.skillbill.desktop.core.domain.usage.model.UsageCoverage
import dev.skillbill.desktop.core.domain.usage.model.UsageDemand
import dev.skillbill.desktop.core.domain.usage.model.UsageFreshness
import dev.skillbill.desktop.core.domain.usage.model.UsageMeter
import dev.skillbill.desktop.core.domain.usage.model.UsageMeterScope
import dev.skillbill.desktop.core.domain.usage.model.UsageObservation
import dev.skillbill.desktop.core.domain.usage.model.UsageObservationSubject
import dev.skillbill.desktop.core.domain.usage.model.UsagePeriod
import dev.skillbill.desktop.core.domain.usage.model.UsageProvider
import dev.skillbill.desktop.core.domain.usage.model.UsageRefreshState
import dev.skillbill.desktop.core.domain.usage.model.UsageRefreshCapability
import dev.skillbill.desktop.core.domain.usage.model.UsageRefreshFamily
import dev.skillbill.desktop.core.domain.usage.model.UsageRequest
import dev.skillbill.desktop.core.domain.usage.model.UsageResult
import dev.skillbill.desktop.core.domain.usage.model.UsageSource
import dev.skillbill.desktop.core.domain.usage.model.UsageValue
import dev.skillbill.desktop.core.domain.usage.model.VerifiedUsageIdentity
import dev.skillbill.desktop.core.domain.usage.model.VerifiedUsageSubject
import dev.skillbill.desktop.core.domain.usage.model.VerifiedUsageWorkspaceScope
import dev.skillbill.desktop.core.testing.FakeAppSettingsRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class ProviderUsageOrderingTest {
  @Test
  fun `first pull cannot overwrite newer event values clears status or coverage`() = runTest {
    for (completion in Completion.entries) {
      for (viaDemand in listOf(false, true)) {
        checkDelayedCompletion(completion, replaceAccount = false, viaDemand = viaDemand)
      }
    }
  }

  @Test
  fun `replacement account pull cannot borrow the previous account revision to overwrite events`() = runTest {
    for (completion in Completion.entries) {
      for (viaDemand in listOf(false, true)) {
        checkDelayedCompletion(completion, replaceAccount = true, viaDemand = viaDemand)
      }
    }
  }

  @Test
  fun `first numeric event captures the pull boundary without a separate subject confirmation`() = runTest {
    for (completion in Completion.entries) {
      checkDelayedCompletion(completion, replaceAccount = false, confirmBeforeEvents = false)
    }
  }

  private suspend fun TestScope.checkDelayedCompletion(
    completion: Completion,
    replaceAccount: Boolean,
    viaDemand: Boolean = false,
    confirmBeforeEvents: Boolean = true,
  ) {
    val reads = Channel<PendingRead>(Channel.UNLIMITED)
    val profiles = UsageProfileResolver(HostEnvironment(emptyMap(), "/home/operator", ""))
    val profile = profiles.codex(null).lookup
    val request = UsageRequest(profile)
    val reader = object : CodexUsageReader {
      override val refreshCapability = UsageRefreshCapability.Pull(UsageRefreshFamily.CodexSnapshot)

      override suspend fun read(context: CodexAcquisitionContext): UsageResult {
        val pending = PendingRead(context, CompletableDeferred())
        reads.send(pending)
        return pending.result.await()
      }
    }
    val repository = ProviderUsageRepository(
      profiles,
      FakeAppSettingsRepository(),
      reader,
      ApplicationCoroutineScope(backgroundScope),
      object : Clock {
        override fun now() = at(5_000) + testScheduler.currentTime.milliseconds
      },
    )
    val subject = VerifiedUsageSubject(
      profile,
      VerifiedUsageIdentity("current-account", VerifiedUsageWorkspaceScope.VerifiedNonWorkspace),
      UsageAuthMode.Account,
    )
    val publications = mutableListOf<UsageResult.Snapshot>()
    repository.acquireDemand(UsageDemand("window", request, visible = true, active = true))
    runCurrent()
    var observer: Job? = null
    try {
      if (replaceAccount) {
        val previous = subject.copy(identity = subject.identity.copy(value = "previous-account"))
        val pending = reads.receive()
        pending.confirm(previous)
        repeat(4) { index ->
          pending.event(snapshot(previous, pending.context, (index + 1) * 100L, 10.0))
        }
        pending.result.complete(snapshot(previous, pending.context, 500, 10.0))
        runCurrent()
        advanceTimeBy(60_000)
        runCurrent()
      }
      observer = backgroundScope.launch {
        val results = if (viaDemand) repository.observeDemand("window") else repository.observe(request)
        results.collect { result -> if (result is UsageResult.Snapshot) publications += result }
      }
      runCurrent()
      val pending = reads.receive()
      if (confirmBeforeEvents) pending.confirm(subject)
      pending.event(snapshot(subject, pending.context, 2_000, 20.0))
      runCurrent()
      assertEquals(at(2_000), publications.last().value.collectionTime)
      pending.event(UsageResult.Partial(
        subject = subject,
        source = source,
        collectionTime = at(3_000),
        reservationId = pending.context.reservationId,
        fields = mapOf(
          "updated" to PartialUsageField.Present(meter("updated", 3_000, 80.0)),
          "cleared" to PartialUsageField.Cleared(at(3_000)),
          "omitted" to PartialUsageField.Present(meter("omitted", 3_000, 70.0)),
        ),
        requestPermission = RequestPermission.Rejected,
        sessionWarning = true,
      ))
      runCurrent()
      assertEquals(at(3_000), publications.last().value.collectionTime)
      val delayed = when (completion) {
        Completion.Snapshot -> snapshot(subject, pending.context, 4_000, 10.0)
        Completion.Partial -> UsageResult.Partial(
          subject = subject,
          source = source,
          collectionTime = at(4_000),
          reservationId = pending.context.reservationId,
          fields = mapOf(
            "updated" to PartialUsageField.Cleared(at(4_000)),
            "cleared" to PartialUsageField.Present(meter("cleared", 4_000, 10.0)),
            "omitted" to PartialUsageField.Present(meter("omitted", 4_000, 10.0)),
          ),
          requestPermission = RequestPermission.Allowed,
          sessionWarning = false,
        )
        Completion.Empty -> UsageResult.EmptySuccess(
          subject, source, at(4_000), OmissionSemantics.OmittedFieldsAreRemoved, pending.context.reservationId,
        )
      }
      pending.result.complete(delayed)
      runCurrent()

      val result = publications.last().value
      val message = "completion=$completion, replacement=$replaceAccount, demand=$viaDemand"
      assertEquals(UsageObservationSubject.Account(subject), result.subject, message)
      assertEquals(setOf("updated", "omitted"), result.meters.map { it.bucketIdentity }.toSet(), message)
      assertEquals(80.0, assertIs<UsageValue.PercentageUsed>(result.meters.single { it.bucketIdentity == "updated" }.value).percent, message)
      assertEquals(70.0, assertIs<UsageValue.PercentageUsed>(result.meters.single { it.bucketIdentity == "omitted" }.value).percent, message)
      assertEquals(listOf(at(3_000), at(3_000)), result.meters.map { it.observedAt }, message)
      assertEquals(at(3_000), result.collectionTime, message)
      assertEquals(RequestPermission.Rejected, result.requestPermission, message)
      assertEquals(true, result.sessionWarning, message)
      assertEquals(at(3_000), result.statusObservedAt, message)
      assertEquals(UsageCoverage.Partial, result.coverage, message)
      assertFalse(result.emptySuccess, message)
      assertTrue(observer.isActive)
    } finally {
      observer?.cancelAndJoin()
      assertTrue(observer == null || observer.isCancelled)
      repository.releaseDemand("window")
      repository.closeUsage()
      reads.close()
    }
  }

  private data class PendingRead(val context: CodexAcquisitionContext, val result: CompletableDeferred<UsageResult>) {
    suspend fun confirm(subject: VerifiedUsageSubject) = context.emit(
      CodexAcquisitionEvent.SubjectConfirmed(CodexVerifiedSubjectConfirmation(context.reservationId, subject)),
    )

    suspend fun event(result: UsageResult) = context.emit(
      CodexAcquisitionEvent.VerifiedObservation(CodexVerifiedUsageObservation(context.reservationId, result)),
    )
  }

  private enum class Completion { Snapshot, Partial, Empty }

  private companion object {
    val source = UsageSource(UsageProvider.Codex, UsageCapability.NumericSnapshot)

    fun at(milliseconds: Long) = Instant.fromEpochMilliseconds(milliseconds)

    fun meter(bucket: String, milliseconds: Long, percent: Double) = UsageMeter.create(
      bucket, bucket, UsageMeterScope.General, at(milliseconds), null, UsagePeriod.Unknown,
      UsageValue.PercentageUsed.create(percent)!!,
    )!!

    fun snapshot(subject: VerifiedUsageSubject, context: CodexAcquisitionContext, milliseconds: Long, percent: Double) = UsageResult.Snapshot(
      UsageObservation(
        subject = UsageObservationSubject.Account(subject),
        collectionTime = at(milliseconds),
        sourceObservationTime = null,
        source = source,
        freshness = UsageFreshness.Current,
        refreshState = UsageRefreshState.Complete,
        meters = listOf(meter("updated", milliseconds, percent), meter("cleared", milliseconds, percent)),
        requestPermission = RequestPermission.Allowed,
        sessionWarning = false,
      ),
      OmissionSemantics.OmittedFieldsAreRemoved,
      context.reservationId,
    )
  }
}
