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
) : BoardSource {
  private val refresh = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)
  override fun setExecutableOverride(path: String?) = Unit
  override suspend fun board(retention: Retention) = boardCall(retention)
  override suspend fun detail(identity: WorkIdentity) = detailCall(identity)
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
