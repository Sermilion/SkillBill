package dev.skillbill.intellij.application

import dev.skillbill.intellij.composition.SkillBillStatusCompositionRoot
import dev.skillbill.intellij.domain.NO_MATCHING_WORK_REASON_CODE
import dev.skillbill.intellij.domain.POLL_FAILED_REASON_CODE
import dev.skillbill.intellij.domain.StatusExecutionMetadata
import dev.skillbill.intellij.domain.SkillBillStatusOutcome
import dev.skillbill.intellij.domain.StatusDiagnostic
import dev.skillbill.intellij.domain.UnavailableReason
import dev.skillbill.intellij.fakes.FakePreferenceCache
import dev.skillbill.intellij.fakes.FakeStatusRepository
import dev.skillbill.intellij.fakes.awaitCallCount
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import dev.skillbill.intellij.domain.CachedDisplaySnapshot
import dev.skillbill.intellij.domain.LastKnownDisplayCache

class StatusRefreshCoordinatorTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `newer active execution replaces the previous terminal execution`() = runBlocking {
        val now = Instant.parse("2026-10-09T10:00:00Z")
        val execution = StatusExecutionMetadata(
            executionScope = "standalone_phase", executionId = "run-1", statusStoreId = "store",
            branchCorrelation = "main", runSequence = "1", statusRevision = "2",
            invocationId = "invocation-1", phaseId = "review",
        )
        var response: SkillBillStatusOutcome = SkillBillStatusOutcome.Done(
            observedAt = now, summary = "finished", repositoryIdentity = "repo", issueKey = null,
            progressCompleted = null, progressTotal = null, startedAt = now, updatedAt = now,
            execution = execution,
        )
        val coordinator = StatusRefreshCoordinator(
            FakeStatusRepository { response }, FakePreferenceCache(), this, Path.of("/tmp/a"),
        )
        try {
            coordinator.requestRefresh()
            withTimeout(2_000) { coordinator.outcomes.first { it is SkillBillStatusOutcome.Done } }
            response = SkillBillStatusOutcome.Active(
                observedAt = now, summary = "running", repositoryIdentity = "repo", issueKey = null,
                workflowId = null, workflowFamily = null, currentStepId = "review", currentStepLabel = "Review",
                progressCompleted = null, progressTotal = null, startedAt = now,
                currentSubtaskId = null, subtaskStartedAt = null, updatedAt = now,
                execution = execution.copy(executionId = "run-2", runSequence = "2", statusRevision = "1"),
            )
            coordinator.requestRefresh()
            val updated = withTimeout(2_000) {
                coordinator.outcomes.first { it is SkillBillStatusOutcome.Active }
            } as SkillBillStatusOutcome.Active
            assertEquals("run-2", updated.execution?.executionId)
        } finally {
            coordinator.dispose()
        }
    }

    @Test
    fun `a lower-sequence live execution replaces a displayed done snapshot`() = runBlocking {
        val now = Instant.parse("2026-10-09T10:00:00Z")
        val doneExecution = StatusExecutionMetadata(
            executionScope = "standalone_phase", executionId = "run-1", statusStoreId = "store",
            branchCorrelation = "main", runSequence = "11", statusRevision = "2",
            invocationId = "invocation-1", phaseId = "review",
        )
        var response: SkillBillStatusOutcome = SkillBillStatusOutcome.Done(
            observedAt = now, summary = "finished", repositoryIdentity = "repo", issueKey = null,
            progressCompleted = null, progressTotal = null, startedAt = now, updatedAt = now,
            execution = doneExecution,
        )
        val coordinator = StatusRefreshCoordinator(
            FakeStatusRepository { response }, FakePreferenceCache(), this, Path.of("/tmp/a"),
        )
        try {
            coordinator.requestRefresh()
            withTimeout(2_000) { coordinator.outcomes.first { it is SkillBillStatusOutcome.Done } }
            response = SkillBillStatusOutcome.Active(
                observedAt = now, summary = "running", repositoryIdentity = "repo", issueKey = null,
                workflowId = null, workflowFamily = null, currentStepId = "review", currentStepLabel = "Review",
                progressCompleted = null, progressTotal = null, startedAt = now,
                currentSubtaskId = null, subtaskStartedAt = null, updatedAt = now,
                execution = doneExecution.copy(executionId = "run-2", runSequence = "10", statusRevision = "1"),
            )
            coordinator.requestRefresh()
            val updated = withTimeout(2_000) {
                coordinator.outcomes.first { it is SkillBillStatusOutcome.Active }
            } as SkillBillStatusOutcome.Active
            assertEquals("run-2", updated.execution?.executionId)
            assertEquals("10", updated.execution?.runSequence)
        } finally {
            coordinator.dispose()
        }
    }

    @Test
    fun `a live execution with no run sequence replaces a displayed done snapshot`() = runBlocking {
        val now = Instant.parse("2026-10-09T10:00:00Z")
        val doneExecution = StatusExecutionMetadata(
            executionScope = "standalone_phase", executionId = "run-1", statusStoreId = "store",
            branchCorrelation = "main", runSequence = "11", statusRevision = "2",
            invocationId = "invocation-1", phaseId = "review",
        )
        var response: SkillBillStatusOutcome = SkillBillStatusOutcome.Done(
            observedAt = now, summary = "finished", repositoryIdentity = "repo", issueKey = null,
            progressCompleted = null, progressTotal = null, startedAt = now, updatedAt = now,
            execution = doneExecution,
        )
        val coordinator = StatusRefreshCoordinator(
            FakeStatusRepository { response }, FakePreferenceCache(), this, Path.of("/tmp/a"),
        )
        try {
            coordinator.requestRefresh()
            withTimeout(2_000) { coordinator.outcomes.first { it is SkillBillStatusOutcome.Done } }
            response = SkillBillStatusOutcome.Active(
                observedAt = now, summary = "running", repositoryIdentity = "repo", issueKey = null,
                workflowId = null, workflowFamily = null, currentStepId = "review", currentStepLabel = "Review",
                progressCompleted = null, progressTotal = null, startedAt = now,
                currentSubtaskId = null, subtaskStartedAt = null, updatedAt = now,
                execution = doneExecution.copy(executionId = "run-2", runSequence = null, statusRevision = "1"),
            )
            coordinator.requestRefresh()
            val updated = withTimeout(2_000) {
                coordinator.outcomes.first { it is SkillBillStatusOutcome.Active }
            } as SkillBillStatusOutcome.Active
            assertEquals("run-2", updated.execution?.executionId)
            assertEquals(null, updated.execution?.runSequence)
        } finally {
            coordinator.dispose()
        }
    }

    @Test
    fun `an older live execution does not replace a newer live execution`() = runBlocking {
        val now = Instant.parse("2026-10-09T10:00:00Z")
        val newerExecution = StatusExecutionMetadata(
            executionScope = "standalone_phase", executionId = "run-1", statusStoreId = "store",
            branchCorrelation = "main", runSequence = "11", statusRevision = "2",
            invocationId = "invocation-1", phaseId = "review",
        )
        var response: SkillBillStatusOutcome = SkillBillStatusOutcome.Active(
            observedAt = now, summary = "running", repositoryIdentity = "repo", issueKey = null,
            workflowId = null, workflowFamily = null, currentStepId = "review", currentStepLabel = "Review",
            progressCompleted = null, progressTotal = null, startedAt = now,
            currentSubtaskId = null, subtaskStartedAt = null, updatedAt = now,
            execution = newerExecution,
        )
        val repo = FakeStatusRepository { response }
        val coordinator = StatusRefreshCoordinator(
            repo, FakePreferenceCache(), this, Path.of("/tmp/a"),
        )
        try {
            coordinator.requestRefresh()
            withTimeout(2_000) { coordinator.outcomes.first { it is SkillBillStatusOutcome.Active } }
            response = SkillBillStatusOutcome.Active(
                observedAt = now, summary = "older", repositoryIdentity = "repo", issueKey = null,
                workflowId = null, workflowFamily = null, currentStepId = "review", currentStepLabel = "Review",
                progressCompleted = null, progressTotal = null, startedAt = now,
                currentSubtaskId = null, subtaskStartedAt = null, updatedAt = now,
                execution = newerExecution.copy(executionId = "run-2", runSequence = "10", statusRevision = "1"),
            )
            coordinator.requestRefresh()
            awaitCallCount(repo, 2)
            delay(50)
            val held = coordinator.outcomes.value as SkillBillStatusOutcome.Active
            assertEquals("run-1", held.execution?.executionId)
            assertEquals("11", held.execution?.runSequence)
        } finally {
            coordinator.dispose()
        }
    }

    @Test
    fun `the same execution does not regress from done to active`() = runBlocking {
        val now = Instant.parse("2026-10-09T10:00:00Z")
        val execution = StatusExecutionMetadata(
            executionScope = "standalone_phase", executionId = "run-1", statusStoreId = "store",
            branchCorrelation = "main", runSequence = "11", statusRevision = "2",
            invocationId = "invocation-1", phaseId = "review",
        )
        var response: SkillBillStatusOutcome = SkillBillStatusOutcome.Done(
            observedAt = now, summary = "finished", repositoryIdentity = "repo", issueKey = null,
            progressCompleted = null, progressTotal = null, startedAt = now, updatedAt = now,
            execution = execution,
        )
        val repo = FakeStatusRepository { response }
        val coordinator = StatusRefreshCoordinator(
            repo, FakePreferenceCache(), this, Path.of("/tmp/a"),
        )
        try {
            coordinator.requestRefresh()
            withTimeout(2_000) { coordinator.outcomes.first { it is SkillBillStatusOutcome.Done } }
            response = SkillBillStatusOutcome.Active(
                observedAt = now, summary = "running", repositoryIdentity = "repo", issueKey = null,
                workflowId = null, workflowFamily = null, currentStepId = "review", currentStepLabel = "Review",
                progressCompleted = null, progressTotal = null, startedAt = now,
                currentSubtaskId = null, subtaskStartedAt = null, updatedAt = now,
                execution = execution,
            )
            coordinator.requestRefresh()
            awaitCallCount(repo, 2)
            delay(50)
            assertTrue(coordinator.outcomes.value is SkillBillStatusOutcome.Done)
            assertEquals("run-1", (coordinator.outcomes.value as SkillBillStatusOutcome.Done).execution?.executionId)
        } finally {
            coordinator.dispose()
        }
    }

    @Test
    fun `polling starts only while a consumer is active and does not overlap`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val repo = FakeStatusRepository {
            gate.await()
            idle()
        }
        val prefs = FakePreferenceCache(refreshIntervalSeconds = 60)
        val coordinator = StatusRefreshCoordinator(repo, prefs, scope, Path.of("/tmp/a"))
        assertEquals(0, repo.callCount.get())
        coordinator.addConsumer()
        awaitCallCount(repo, 1)

        coordinator.requestRefresh()
        delay(50)
        assertEquals(1, repo.maxInFlight.get())
        gate.complete(Unit)
        delay(50)
        coordinator.removeConsumer()
        val callsAfterStop = repo.callCount.get()
        delay(100)
        assertEquals(callsAfterStop, repo.callCount.get())
        coordinator.dispose()
    }

    @Test
    fun `dispose cancels polling and invokes process cancellation`() = runBlocking {
        val cancelled = AtomicBoolean(false)
        val repo = FakeStatusRepository {
            delay(10_000)
            idle()
        }
        val prefs = FakePreferenceCache(refreshIntervalSeconds = 1)
        val coordinator = StatusRefreshCoordinator(
            statusRepository = repo,
            preferences = prefs,
            scope = scope,
            projectRoot = Path.of("/tmp/b"),
            onCancelProcesses = { cancelled.set(true) },
        )
        coordinator.addConsumer()
        awaitCallCount(repo, 1)
        coordinator.dispose()
        assertTrue(cancelled.get())
        val calls = repo.callCount.get()
        delay(200)
        assertEquals(calls, repo.callCount.get())
    }

    @Test
    fun `unavailable falls back to stale cache only`() = runBlocking {
        val observed = Instant.parse("2026-08-06T09:00:00Z")
        val prefs = FakePreferenceCache(
            refreshIntervalSeconds = 60,
            cache = LastKnownDisplayCache(
                display = CachedDisplaySnapshot(
                    summary = "cached implement",
                    currentStepLabel = "Implement",
                ),
                observedAt = observed,
            ),
        )
        val repo = FakeStatusRepository {
            SkillBillStatusOutcome.Unavailable(
                observedAt = Instant.parse("2026-08-06T10:00:00Z"),
                summary = "cli missing",
                reasonCode = UnavailableReason.MISSING_EXECUTABLE,
            )
        }
        val coordinator = StatusRefreshCoordinator(repo, prefs, scope, Path.of("/tmp/c"))
        coordinator.addConsumer()
        awaitCallCount(repo, 1)
        delay(50)
        val outcome = coordinator.outcomes.value
        assertTrue(outcome is SkillBillStatusOutcome.Stale)
        assertTrue((outcome as SkillBillStatusOutcome.Stale).fromCache)
        coordinator.dispose()
    }

    @Test
    fun `two project graphs remain isolated through composition root`() = runBlocking {
        val repoA = FakeStatusRepository { idle(summary = "A") }
        val repoB = FakeStatusRepository { idle(summary = "B") }
        val rootA = SkillBillStatusCompositionRoot.createForTest(
            projectRoot = Path.of("/tmp/project-a"),
            preferences = FakePreferenceCache(refreshIntervalSeconds = 60),
            statusRepository = repoA,
        )
        val rootB = SkillBillStatusCompositionRoot.createForTest(
            projectRoot = Path.of("/tmp/project-b"),
            preferences = FakePreferenceCache(refreshIntervalSeconds = 60),
            statusRepository = repoB,
        )
        rootA.coordinator.addConsumer()
        rootB.coordinator.addConsumer()
        awaitCallCount(repoA, 1)
        awaitCallCount(repoB, 1)
        delay(50)
        assertEquals("A", (rootA.coordinator.outcomes.value as SkillBillStatusOutcome.Idle).summary)
        assertEquals("B", (rootB.coordinator.outcomes.value as SkillBillStatusOutcome.Idle).summary)
        rootA.dispose()
        delay(100)

        val bCalls = repoB.callCount.get()
        assertTrue(bCalls >= 1)
        rootB.viewModel.onWidgetActivated()
        rootB.dispose()
    }

    @Test
    fun `a single unconfirmed idle holds the previously emitted live outcome`() = runBlocking {
        val prefs = FakePreferenceCache(refreshIntervalSeconds = 60)
        val repo = scripted(active(), noMatchingWork())
        val coordinator = StatusRefreshCoordinator(repo, prefs, scope, Path.of("/tmp/hold"))
        pollTimes(coordinator, repo, 2)
        assertEquals(active(), coordinator.outcomes.value)
        coordinator.dispose()
    }

    @Test
    fun `two consecutive unconfirmed idles settle to idle`() = runBlocking {
        val prefs = FakePreferenceCache(refreshIntervalSeconds = 60)
        val repo = scripted(active(), noMatchingWork(), noMatchingWork())
        val coordinator = StatusRefreshCoordinator(repo, prefs, scope, Path.of("/tmp/settle"))
        pollTimes(coordinator, repo, 3)
        assertEquals(noMatchingWork(), coordinator.outcomes.value)
        coordinator.dispose()
    }

    @Test
    fun `an unconfirmed idle with no prior outcome emits idle immediately`() = runBlocking {
        val prefs = FakePreferenceCache(refreshIntervalSeconds = 60)
        val repo = scripted(noMatchingWork())
        val coordinator = StatusRefreshCoordinator(repo, prefs, scope, Path.of("/tmp/first"))
        pollTimes(coordinator, repo, 1)
        assertEquals(noMatchingWork(), coordinator.outcomes.value)
        coordinator.dispose()
    }

    @Test
    fun `a non-live prior outcome never holds`() = runBlocking {
        val priors = listOf(
            idle(),
            SkillBillStatusOutcome.Unavailable(
                observedAt = Instant.parse("2026-08-06T10:00:00Z"),
                summary = "cli missing",
                reasonCode = UnavailableReason.MISSING_EXECUTABLE,
            ),
            SkillBillStatusOutcome.Incompatible(
                observedAt = Instant.parse("2026-08-06T10:00:00Z"),
                summary = "incompatible",
                foundContractVersion = "0.0",
            ),
        )
        for (prior in priors) {
            val prefs = FakePreferenceCache(refreshIntervalSeconds = 60)
            val repo = scripted(prior, noMatchingWork())
            val coordinator = StatusRefreshCoordinator(repo, prefs, scope, Path.of("/tmp/non-live"))
            pollTimes(coordinator, repo, 2)
            assertEquals("prior $prior must not hold", noMatchingWork(), coordinator.outcomes.value)
            coordinator.dispose()
        }
    }

    @Test
    fun `a good sample between two unconfirmed idles resets the counter`() = runBlocking {
        val prefs = FakePreferenceCache(refreshIntervalSeconds = 60)
        val repo = scripted(active(), noMatchingWork(), active(), noMatchingWork())
        val coordinator = StatusRefreshCoordinator(repo, prefs, scope, Path.of("/tmp/reset"))
        pollTimes(coordinator, repo, 4)
        assertEquals(active(), coordinator.outcomes.value)
        coordinator.dispose()
    }

    @Test
    fun `a paused goal counts as live and is held`() = runBlocking {
        val prefs = FakePreferenceCache(refreshIntervalSeconds = 60)
        val repo = scripted(paused(), noMatchingWork())
        val coordinator = StatusRefreshCoordinator(repo, prefs, scope, Path.of("/tmp/paused"))
        pollTimes(coordinator, repo, 2)
        assertEquals(paused(), coordinator.outcomes.value)
        coordinator.dispose()
    }

    @Test
    fun `the unavailable cache fallback still works after a held poll`() = runBlocking {
        val prefs = FakePreferenceCache(refreshIntervalSeconds = 60)
        val repo = scripted(
            active(),
            noMatchingWork(),
            SkillBillStatusOutcome.Unavailable(
                observedAt = Instant.parse("2026-08-06T11:00:00Z"),
                summary = "cli missing",
                reasonCode = UnavailableReason.MISSING_EXECUTABLE,
            ),
        )
        val coordinator = StatusRefreshCoordinator(repo, prefs, scope, Path.of("/tmp/fallback"))
        pollTimes(coordinator, repo, 3)
        val outcome = coordinator.outcomes.value
        assertTrue(outcome is SkillBillStatusOutcome.Stale)
        assertTrue((outcome as SkillBillStatusOutcome.Stale).fromCache)
        assertEquals(active().summary, outcome.summary)
        coordinator.dispose()
    }

    @Test
    fun `a held poll neither writes nor advances the persisted cache`() = runBlocking {
        val prefs = FakePreferenceCache(refreshIntervalSeconds = 60)
        val repo = scripted(active(), noMatchingWork())
        val coordinator = StatusRefreshCoordinator(repo, prefs, scope, Path.of("/tmp/cache"))
        pollTimes(coordinator, repo, 1)
        val writesAfterLive = prefs.cacheWriteAttempts.get()
        val cacheAfterLive = prefs.getLastKnownDisplayCache()
        pollTimes(coordinator, repo, 1)
        assertEquals(writesAfterLive, prefs.cacheWriteAttempts.get())
        assertEquals(cacheAfterLive, prefs.getLastKnownDisplayCache())

        assertEquals(active(), coordinator.outcomes.value)
        coordinator.dispose()
    }

    @Test
    fun `a transport-failure poll after a live active retains the live display`() = runBlocking {
        val transportFailures = listOf(
            UnavailableReason.TIMEOUT,
            UnavailableReason.CANCELLED,
            UnavailableReason.PROCESS_FAILURE,
        )
        for (reason in transportFailures) {
            val prefs = FakePreferenceCache(refreshIntervalSeconds = 60)
            val repo = scripted(
                active(),
                SkillBillStatusOutcome.Unavailable(
                    observedAt = Instant.parse("2026-08-06T10:00:15Z"),
                    summary = "poll failed",
                    reasonCode = reason,
                ),
            )
            val coordinator = StatusRefreshCoordinator(repo, prefs, scope, Path.of("/tmp/transport"))
            pollTimes(coordinator, repo, 2)
            val outcome = coordinator.outcomes.value
            assertTrue("$reason must retain Active", outcome is SkillBillStatusOutcome.Active)
            outcome as SkillBillStatusOutcome.Active
            assertEquals("implement", outcome.currentStepId)
            assertEquals(POLL_FAILED_REASON_CODE, outcome.diagnostic?.reasonCode)
            coordinator.dispose()
        }
    }

    @Test
    fun `a timeout with no live display and no cache stays unavailable`() = runBlocking {
        val prefs = FakePreferenceCache(refreshIntervalSeconds = 60)
        val repo = scripted(
            SkillBillStatusOutcome.Unavailable(
                observedAt = Instant.parse("2026-08-06T10:00:00Z"),
                summary = "poll timed out",
                reasonCode = UnavailableReason.TIMEOUT,
            ),
        )
        val coordinator = StatusRefreshCoordinator(repo, prefs, scope, Path.of("/tmp/first-timeout"))
        pollTimes(coordinator, repo, 1)
        val outcome = coordinator.outcomes.value
        assertTrue(outcome is SkillBillStatusOutcome.Unavailable)
        assertEquals(
            UnavailableReason.TIMEOUT,
            (outcome as SkillBillStatusOutcome.Unavailable).reasonCode,
        )
        coordinator.dispose()
    }

    private suspend fun pollTimes(
        coordinator: StatusRefreshCoordinator,
        repo: FakeStatusRepository,
        times: Int,
    ) {
        repeat(times) {
            val target = repo.callCount.get() + 1
            coordinator.requestRefresh()
            awaitCallCount(repo, target)
            delay(50)
        }
    }

    private fun scripted(vararg outcomes: SkillBillStatusOutcome): FakeStatusRepository {
        val index = AtomicInteger(0)
        return FakeStatusRepository { outcomes[minOf(index.getAndIncrement(), outcomes.size - 1)] }
    }

    private fun noMatchingWork() =
        SkillBillStatusOutcome.Idle(
            observedAt = Instant.parse("2026-08-06T10:00:00Z"),
            summary = "No matching Skill Bill work for this repository.",
            repositoryIdentity = "repo",
            diagnostic = StatusDiagnostic(reasonCode = NO_MATCHING_WORK_REASON_CODE),
        )

    private fun active() =
        SkillBillStatusOutcome.Active(
            observedAt = Instant.parse("2026-08-06T10:00:00Z"),
            summary = "implementing",
            repositoryIdentity = "repo",
            issueKey = "SKILL-168",
            workflowId = "wf",
            workflowFamily = "feature-task",
            currentStepId = "implement",
            currentStepLabel = "Implement",
            progressCompleted = 1,
            progressTotal = 4,
            startedAt = Instant.parse("2026-08-06T09:00:00Z"),
            currentSubtaskId = "subtask-1",
            subtaskStartedAt = Instant.parse("2026-08-06T09:30:00Z"),
            updatedAt = Instant.parse("2026-08-06T10:00:00Z"),
        )

    private fun paused() =
        SkillBillStatusOutcome.Paused(
            observedAt = Instant.parse("2026-08-06T10:00:00Z"),
            summary = "paused",
            repositoryIdentity = "repo",
            issueKey = "SKILL-168",
            workflowId = "wf",
            workflowFamily = "feature-task",
            currentStepId = "implement",
            currentStepLabel = "Implement",
            progressCompleted = 1,
            progressTotal = 4,
            startedAt = Instant.parse("2026-08-06T09:00:00Z"),
            currentSubtaskId = "subtask-1",
            subtaskStartedAt = Instant.parse("2026-08-06T09:30:00Z"),
            updatedAt = Instant.parse("2026-08-06T10:00:00Z"),
        )

    private fun idle(summary: String = "idle") =
        SkillBillStatusOutcome.Idle(
            observedAt = Instant.parse("2026-08-06T10:00:00Z"),
            summary = summary,
        )
}
