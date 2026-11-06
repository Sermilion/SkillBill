package dev.skillbill.desktop.core.data.repository

import dev.skillbill.desktop.core.data.runtime.DetailRead
import dev.skillbill.desktop.core.data.runtime.DetailReads
import dev.skillbill.desktop.core.data.signal.ChangedIds
import dev.skillbill.desktop.core.domain.model.DetailList
import dev.skillbill.desktop.core.domain.model.DetailSections
import dev.skillbill.desktop.core.domain.model.DetailState
import dev.skillbill.desktop.core.domain.model.ActivityEvent
import dev.skillbill.desktop.core.domain.model.Lifecycle
import dev.skillbill.desktop.core.domain.model.LatestActivity
import dev.skillbill.desktop.core.domain.model.RunOptions
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

private val ROW = workItem("goal-1")
private val OPTIONS =
  RunOptions(canCancel = false, canLaunch = true, launchLabel = "Resume", agentId = "claude", agentChoices = emptyList(), unavailableReason = null)

@OptIn(ExperimentalCoroutinesApi::class)
class DetailRepositoryTest {
  @Test
  fun `the first emission is the row header with no read`() = runTest {
    val reads = FakeDetailReads()
    val details = DetailObservations(backgroundScope, MutableStateFlow(signal()), reads)

    val first = details.observe(ROW).first()

    assertEquals(DetailState(ROW, DetailSections.Pending), first)
    assertEquals(0, reads.firstReads)
  }

  @Test
  fun `the first load is one read that carries the run options`() = runTest {
    val reads = FakeDetailReads()
    val details = DetailObservations(backgroundScope, MutableStateFlow(signal()), reads)
    val states = mutableListOf<DetailState>()
    backgroundScope.launch { details.observe(ROW).collect { states += it } }
    runCurrent()

    assertEquals(1, reads.firstReads)
    assertEquals(OPTIONS, assertIs<DetailSections.Loaded>(states.last().sections).runOptions)
  }

  @Test
  fun `only a change listing the open id refreshes it, in place`() = runTest {
    val signals = MutableStateFlow(signal())
    val reads = FakeDetailReads()
    val details = DetailObservations(backgroundScope, signals, reads)
    val states = mutableListOf<DetailState>()
    backgroundScope.launch { details.observe(ROW).collect { states += it } }
    runCurrent()

    signals.value = signal(activity = 2, changed = mapOf("activity" to ChangedIds.Ids(base = 1, ids = listOf("other"))))
    runCurrent()
    assertEquals(0, reads.refreshes)

    signals.value = signal(activity = 3, changed = mapOf("activity" to ChangedIds.Ids(base = 2, ids = listOf(ROW.id))))
    runCurrent()
    assertEquals(1, reads.activityReads)
    assertTrue(states.drop(1).none { it.sections == DetailSections.Pending })
  }

  @Test
  fun `activity changes read only activity and preserve cached options and sections`() = runTest {
    val signals = MutableStateFlow(signal())
    val reads = FakeDetailReads()
    val details = DetailObservations(backgroundScope, signals, reads)
    val states = mutableListOf<DetailState>()
    backgroundScope.launch { details.observe(ROW).collect { states += it } }
    runCurrent()
    val before = assertIs<DetailSections.Loaded>(states.last().sections)

    signals.value = signal(activity = 2, changed = mapOf("activity" to ChangedIds.Ids(base = 1, ids = listOf(ROW.id))))
    runCurrent()

    val after = assertIs<DetailSections.Loaded>(states.last().sections)
    assertEquals(1, reads.activityReads)
    assertEquals(0, reads.refreshes)
    assertEquals(OPTIONS, after.runOptions)
    assertEquals(before.detail.subtasks, after.detail.subtasks)
    assertEquals(Instant.fromEpochMilliseconds(1), after.detail.latestActivity.event?.at)
  }

  @Test
  fun `an active standalone plan refreshes on the bounded signal and stops after becoming idle`() = runTest {
    val base = workItem("plan-1").copy(kind = dev.skillbill.desktop.core.domain.model.WorkKind.Phase)
    val plan = base.copy(standaloneExecution = true, control = base.control.copy(phaseId = "plan"))
    val signals = MutableStateFlow(signal())
    val pulse = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val reads = FakeDetailReads().apply { header = plan }
    val details = DetailObservations(backgroundScope, signals, reads, pulse)
    val states = mutableListOf<DetailState>()
    backgroundScope.launch { details.observe(plan).collect { states += it } }
    runCurrent()
    assertEquals(0, reads.refreshes)

    pulse.emit(Unit)
    runCurrent()

    assertEquals(1, reads.refreshes)
    assertEquals(Instant.fromEpochMilliseconds(1), assertIs<DetailSections.Loaded>(states.last().sections).detail.observedAt)

    reads.header = plan.copy(lifecycle = Lifecycle.Terminal, running = false)
    signals.value = signal(phases = 2, changed = mapOf("phases" to ChangedIds.Ids(base = 1, ids = listOf(plan.id))))
    runCurrent()
    pulse.emit(Unit)
    runCurrent()

    assertEquals(2, reads.refreshes)
  }

  @Test
  fun `a refresh takes the running verdict from the read, so a run that stopped after the row was captured reads as stopped`() = runTest {
    val signals = MutableStateFlow(signal())
    val reads = FakeDetailReads()
    val details = DetailObservations(backgroundScope, signals, reads)
    val states = mutableListOf<DetailState>()
    backgroundScope.launch { details.observe(ROW.copy(running = true)).collect { states += it } }
    runCurrent()
    assertTrue(states.last().header.running)

    reads.header = ROW.copy(lifecycle = Lifecycle.Blocked, running = false)
    signals.value = signal(goals = 2, changed = mapOf("goals" to ChangedIds.Ids(base = 1, ids = listOf(ROW.id))))
    runCurrent()

    assertFalse(states.last().header.running)
  }

  @Test
  fun `an explicit refresh replaces the open header and launch options without a commit signal`() = runTest {
    val reads = FakeDetailReads()
    val details = DetailObservations(backgroundScope, MutableStateFlow(signal()), reads)
    val states = mutableListOf<DetailState>()
    backgroundScope.launch { details.observe(ROW).collect { states += it } }
    runCurrent()
    reads.header = ROW.copy(lifecycle = Lifecycle.Paused)
    reads.options = OPTIONS.copy(canLaunch = false)

    details.refresh(ROW.id)
    runCurrent()

    assertEquals(1, reads.refreshes)
    assertEquals(Lifecycle.Paused, states.last().header.lifecycle)
    assertFalse(assertIs<DetailSections.Loaded>(states.last().sections).runOptions!!.canLaunch)
    assertTrue(states.drop(1).none { it.sections == DetailSections.Pending })
  }

  @Test
  fun `activity arriving during a subtask transition cannot cancel its published rows`() = runTest {
    val signals = MutableStateFlow(signal())
    val reads = FakeDetailReads()
    reads.header = ROW.copy(currentSubtaskId = "31")
    val details = DetailObservations(backgroundScope, signals, reads)
    val states = mutableListOf<DetailState>()
    backgroundScope.launch { details.observe(ROW).collect { states += it } }
    runCurrent()
    val release = CompletableDeferred<Unit>()
    reads.beforeRefresh = { release.await() }
    reads.header = ROW.copy(currentSubtaskId = "32")
    reads.completed = 31

    signals.value = signal(goals = 2)
    runCurrent()
    signals.value = signal(goals = 2, activity = 2)
    runCurrent()
    release.complete(Unit)
    runCurrent()

    assertEquals("32", states.last().header.currentSubtaskId)
    assertEquals(31, assertIs<DetailSections.Loaded>(states.last().sections).detail.completeCount)
  }

  @Test
  fun `full refresh stays pending when activity supersedes its signal during another read`() = runTest {
    val signals = MutableStateFlow(signal())
    val reads = FakeDetailReads()
    val details = DetailObservations(backgroundScope, signals, reads)
    val states = mutableListOf<DetailState>()
    backgroundScope.launch { details.observe(ROW).collect { states += it } }
    runCurrent()
    val release = CompletableDeferred<Unit>()
    reads.beforeRefresh = { release.await() }
    signals.value = signal(goals = 2)
    runCurrent()
    reads.completed = 31
    signals.value = signal(goals = 3)
    runCurrent()
    signals.value = signal(goals = 3, activity = 2)
    runCurrent()
    release.complete(Unit)
    runCurrent()

    assertEquals(31, assertIs<DetailSections.Loaded>(states.last().sections).detail.completeCount)
  }

  private class FakeDetailReads : DetailReads {
    var header = ROW
    var options = OPTIONS
    var firstReads = 0
    var refreshes = 0
    var activityReads = 0
    var completed = 0
    var beforeRefresh: (suspend () -> Unit)? = null

    override suspend fun first(id: String): DetailRead {
      firstReads++
      return loaded()
    }

    override suspend fun refresh(id: String, extended: Map<DetailList, Int>): DetailRead {
      refreshes++
      val result = loaded(observedAtMs = refreshes.toLong())
      beforeRefresh?.invoke()
      return result
    }

    override suspend fun activity(id: String): DetailRead {
      activityReads++
      return DetailRead.Activity(
        header,
        LatestActivity(ActivityEvent("review", "worker", "updated", Instant.fromEpochMilliseconds(activityReads.toLong())), null),
      )
    }

    override suspend fun list(id: String, list: DetailList, cursor: String?): DetailRead = loaded()

    private fun loaded(observedAtMs: Long = 0) = DetailRead.Loaded(
      header,
      DetailSections.Loaded(
        emptyDetail(Instant.fromEpochMilliseconds(observedAtMs)).copy(completeCount = completed), options, emptyMap(), emptySet(), stale = false,
      ),
    )
  }
}
