package dev.skillbill.tray.application

import dev.skillbill.tray.domain.*
import dev.skillbill.tray.domain.Retention
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

class DashboardControllerTest {
  @Test
  fun `detail lists request the next page only when asked`() = runBlocking {
    val selected = item("selected")
    val releaseSecond = CompletableDeferred<Unit>()
    val secondStarted = CompletableDeferred<Unit>()
    val firstTask = WorkSubtask(1, "First", "pending", null, null, emptyList())
    val secondTask = WorkSubtask(2, "Second", "pending", null, null, emptyList())
    val source = FakeSource(
      boardCall = { Outcome.Value(board(Retention.DAY, listOf(selected))) },
      detailPageCall = { _, cursor ->
        if (cursor == null) Outcome.Value(detail(selected).copy(subtasks = listOf(firstTask), nextCursor = "details-next")) else {
          assertEquals("details-next", cursor)
          secondStarted.complete(Unit)
          releaseSecond.await()
          Outcome.Value(detail(selected).copy(subtasks = listOf(secondTask)))
        }
      },
    )
    val model = DashboardController(source, MemorySettings(), QuietNative()) {}
    try {
      kotlinx.coroutines.withTimeout(2000) { while (model.state.value.boardLoading) delay(10) }
      model.select(selected.identity)
      kotlinx.coroutines.withTimeout(2000) { while (model.state.value.detailLoading) delay(10) }
      delay(50)
      assertTrue(!secondStarted.isCompleted)
      model.loadMoreDetail()
      model.loadMoreDetail()
      kotlinx.coroutines.withTimeout(2000) { secondStarted.await() }
      assertEquals(listOf(firstTask), model.state.value.detail?.subtasks)
      assertTrue(model.state.value.detailLoading)
      releaseSecond.complete(Unit)
      kotlinx.coroutines.withTimeout(2000) { while (model.state.value.detailLoading) delay(10) }
      assertEquals(listOf(firstTask, secondTask), model.state.value.detail?.subtasks)
    } finally {
      releaseSecond.complete(Unit)
      model.close()
    }
  }

  @Test
  fun `board stops after one page and loads the next page only on demand`() = runBlocking {
    val releaseSecond = CompletableDeferred<Unit>()
    val secondStarted = CompletableDeferred<Unit>()
    val first = item("first")
    val second = item("second")
    val source = FakeSource(
      boardCall = { error("Expected a paginated request") },
      boardPageCall = { retention, cursor ->
        if (cursor == null) Outcome.Value(board(retention, listOf(first)).copy(nextCursor = "next")) else {
          assertEquals("next", cursor)
          secondStarted.complete(Unit)
          releaseSecond.await()
          Outcome.Value(board(retention, listOf(second)))
        }
      },
    )
    val model = DashboardController(source, MemorySettings(), QuietNative()) {}
    try {
      kotlinx.coroutines.withTimeout(2000) { while (model.state.value.boardLoading) delay(10) }
      delay(50)
      assertTrue(!secondStarted.isCompleted)
      model.loadMoreBoard()
      model.loadMoreBoard()
      kotlinx.coroutines.withTimeout(2000) { secondStarted.await() }
      assertEquals(listOf(first), model.state.value.board?.items)
      assertTrue(model.state.value.boardLoading)
      releaseSecond.complete(Unit)
      kotlinx.coroutines.withTimeout(2000) { while (model.state.value.boardLoading) delay(10) }
      assertEquals(listOf(first, second), model.state.value.board?.items)
      assertEquals(null, model.state.value.problem)
    } finally {
      releaseSecond.complete(Unit)
      model.close()
    }
  }

  @Test
  fun `a later page failure retains received work and ends the loading state`() = runBlocking {
    val first = item("first")
    val source = FakeSource(
      boardCall = { error("Expected a paginated request") },
      boardPageCall = { retention, cursor ->
        if (cursor == null) Outcome.Value(board(retention, listOf(first)).copy(nextCursor = "next"))
        else Outcome.Failure(Problem("timeout", "Timed out"))
      },
    )
    val model = DashboardController(source, MemorySettings(), QuietNative()) {}
    try {
      kotlinx.coroutines.withTimeout(2000) { while (model.state.value.boardLoading) delay(10) }
      model.loadMoreBoard()
      kotlinx.coroutines.withTimeout(2000) { while (model.state.value.problem == null) delay(10) }
      assertEquals(listOf(first), model.state.value.board?.items)
      assertTrue(model.state.value.stale)
      assertTrue(!model.state.value.boardLoading)
    } finally {
      model.close()
    }
  }

  @Test
  fun `refresh and reselection wait for an in flight detail without overlapping requests`() = runBlocking {
    val selected = item("workflow")
    val firstStarted = CompletableDeferred<Unit>()
    val releaseFirst = CompletableDeferred<Unit>()
    val retried = CompletableDeferred<Unit>()
    val calls = AtomicInteger()
    val active = AtomicInteger()
    val maximum = AtomicInteger()
    val source = FakeSource(
      boardCall = { Outcome.Value(board(Retention.DAY, listOf(selected))) },
      detailCall = {
        val concurrent = active.incrementAndGet()
        maximum.updateAndGet { previous -> maxOf(previous, concurrent) }
        try {
          if (calls.incrementAndGet() == 1) {
            firstStarted.complete(Unit)
            releaseFirst.await()
          } else retried.complete(Unit)
          Outcome.Value(detail(selected))
        } finally {
          active.decrementAndGet()
        }
      },
    )
    val model = DashboardController(source, MemorySettings(), QuietNative()) {}
    try {
      model.select(selected.identity)
      kotlinx.coroutines.withTimeout(2000) { firstStarted.await() }
      model.select(selected.identity)
      model.refresh()
      releaseFirst.complete(Unit)
      kotlinx.coroutines.withTimeout(2000) {
        retried.await()
        while (model.state.value.detail == null) delay(10)
      }
      assertEquals(1, maximum.get())
      assertEquals(selected.identity, model.state.value.detail?.item?.identity)
      assertEquals(null, model.state.value.detailProblem)
    } finally {
      releaseFirst.complete(Unit)
      model.close()
    }
  }

  @Test
  fun `selection generation keeps the latest detail and changed retention rejects an old board`() = runBlocking {
    val oldBoard = CompletableDeferred<Outcome<Board>>()
    val calls = AtomicInteger()
    val source = FakeSource(
      boardCall = { retention ->
        if (calls.getAndIncrement() == 0) oldBoard.await() else Outcome.Value(board(retention, emptyList()))
      },
      detailCall = { identity ->
        if (identity.id == "A") delay(120)
        Outcome.Value(detail(item(identity.id)))
      },
    )
    val store = MemorySettings()
    val model = DashboardController(source, store, QuietNative()) {}
    model.select(WorkIdentity("A", "repo-A"))
    model.select(WorkIdentity("B", "repo-B"))
    model.updateSettings(store.value.copy(retention = Retention.WEEK))
    oldBoard.complete(Outcome.Value(board(Retention.DAY, emptyList())))
    repeat(50) {
      if (model.state.value.board?.finishedWithin == Retention.WEEK && model.state.value.detail?.item?.identity?.id == "B") return@repeat
      delay(20)
    }
    assertEquals(Retention.WEEK, model.state.value.board?.finishedWithin)
    assertEquals("B", model.state.value.detail?.item?.identity?.id)
    model.close()
  }

  @Test
  fun `pause requested and standalone items never dispatch goal controls`() = runBlocking {
    val pauseCalls = AtomicInteger()
    val stopCalls = AtomicInteger()
    val source = FakeSource(boardCall = { Outcome.Failure(Problem("cli_not_found", "missing")) }, pauseAction = { pauseCalls.incrementAndGet(); Outcome.Value(Unit) }, stopAction = { stopCalls.incrementAndGet(); Outcome.Value(Unit) })
    val model = DashboardController(source, MemorySettings(), QuietNative()) {}
    model.perform("pause", item("requested").copy(pauseRequested = true))
    model.perform("stop", item("phase").copy(family = WorkFamily.FEATURE_VERIFY, scope = ExecutionScope.STANDALONE_PHASE))
    delay(50)
    assertEquals(0, pauseCalls.get())
    assertEquals(0, stopCalls.get())
    model.close()
  }

  @Test
  fun `notification activation selects the identity that changed`() = runBlocking {
    val initial = item("workflow")
    val blocked = initial.copy(lifecycle = Lifecycle.BLOCKED)
    val observations = ArrayDeque(listOf(board(Retention.DAY, listOf(initial)), board(Retention.DAY, listOf(blocked))))
    val source = FakeSource(boardCall = { Outcome.Value(observations.removeFirst()) }, detailCall = { Outcome.Value(detail(blocked)) })
    val selected = CompletableDeferred<WorkIdentity>()
    val native = ClickNative()
    val model = DashboardController(source, MemorySettings(), native) { selected.complete(it) }
    model.refresh()
    val clicked = kotlinx.coroutines.withTimeout(2000) { selected.await() }
    assertEquals(blocked.identity, clicked)
    model.close()
  }

  private fun board(retention: Retention, items: List<WorkItem>) = Board(Instant.parse("2026-10-11T10:00:00Z"), retention, items)
  private fun item(id: String) = WorkItem(
    identity = WorkIdentity(id, "repo-$id"),
    workflowId = id,
    invocationId = null,
    repositoryRoot = "/repo/$id",
    branch = "main",
    issueKey = "SKILL-418",
    family = WorkFamily.FEATURE_GOAL,
    scope = ExecutionScope.WORKFLOW,
    phaseId = null,
    activity = null,
    lifecycle = Lifecycle.ACTIVE,
    freshness = Freshness.FRESH,
    summary = "summary",
    currentStepId = "implement",
    currentStepLabel = "Implement",
    progressCompleted = null,
    progressTotal = null,
    activeDurationMs = null,
    activeDurationAsOf = null,
    subtaskActiveDurationMs = null,
    subtaskActiveDurationAsOf = null,
    pauseRequested = false,
    pauseReasonCode = null,
    pauseReason = null,
    planningState = null,
    plannedSubtasks = null,
    totalSubtasks = null,
    planningWaveSubtaskIds = emptyList(),
    executionCount = null,
    executionTotal = null,
    updatedAt = Instant.parse("2026-10-11T10:00:00Z"),
    finishedAt = null,
    finishedAtSource = null,
  )
  private fun detail(item: WorkItem) = Detail(item, listOf("implement"), listOf("pr", "monitor"), "Feature title", "implement", 0, 1, 0, null, null, emptyMap())
}

private class FakeSource(
  private val boardCall: suspend (Retention) -> Outcome<Board>,
  private val detailCall: suspend (WorkIdentity) -> Outcome<Detail> = { Outcome.Failure(Problem("unknown_work_item", "missing")) },
  private val pauseAction: suspend (WorkItem) -> Outcome<Unit> = { Outcome.Value(Unit) },
  private val stopAction: suspend (WorkItem) -> Outcome<Unit> = { Outcome.Value(Unit) },
  private val boardPageCall: (suspend (Retention, String?) -> Outcome<Board>)? = null,
  private val detailPageCall: (suspend (WorkIdentity, String?) -> Outcome<Detail>)? = null,
) : BoardSource {
  private val refresh = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)
  override fun setExecutableOverride(path: String?) = Unit
  override suspend fun board(retention: Retention, cursor: String?) = boardPageCall?.invoke(retention, cursor) ?: boardCall(retention)
  override suspend fun detail(identity: WorkIdentity, cursor: String?) = detailPageCall?.invoke(identity, cursor) ?: detailCall(identity)
  override suspend fun refresh() { refresh.trySend(Unit) }
  override suspend fun nextRefresh() { refresh.receive() }
  override suspend fun pause(item: WorkItem) = pauseAction(item)
  override suspend fun stop(item: WorkItem) = stopAction(item)
}

private open class QuietNative : NativeServices {
  override fun notify(title: String, body: String, identity: WorkIdentity, onClick: (WorkIdentity) -> Unit) = Unit
  override fun setStartAtLogin(enabled: Boolean) = true
}

private class ClickNative : QuietNative() {
  override fun notify(title: String, body: String, identity: WorkIdentity, onClick: (WorkIdentity) -> Unit) = onClick(identity)
}

private class MemorySettings(var value: AppSettings = AppSettings()) : SettingsStore {
  override fun load() = value
  override fun save(settings: AppSettings) { value = settings }
}
