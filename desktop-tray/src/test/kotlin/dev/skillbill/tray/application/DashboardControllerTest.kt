package dev.skillbill.tray.application

import dev.skillbill.tray.domain.*
import dev.skillbill.tray.domain.Retention
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

class DashboardControllerTest {
  @Test
  fun `empty live section stays ready during an explicit background refresh`() = runBlocking {
    val initialStarted = CompletableDeferred<Unit>()
    val releaseInitial = CompletableDeferred<Unit>()
    val refreshStarted = CompletableDeferred<Unit>()
    val releaseRefresh = CompletableDeferred<Unit>()
    val phaseReads = AtomicInteger()
    val source = FakeSource(
      boardCall = { error("Expected a section read") },
      sectionCall = { section ->
        if (section == WorkSection.PHASES) {
          when (phaseReads.incrementAndGet()) {
            1 -> { initialStarted.complete(Unit); releaseInitial.await() }
            else -> { refreshStarted.complete(Unit); releaseRefresh.await() }
          }
        }
        Outcome.Value(board(Retention.DAY, emptyList()))
      },
    )
    val model = DashboardController(source, source, source, source, MemorySettings(), QuietNative()) {}
    try {
      kotlinx.coroutines.withTimeout(2000) { initialStarted.await() }
      assertEquals(ListLoadState.INITIAL_LOADING, model.state.value.sectionLoadStates[WorkSection.PHASES])
      assertTrue(model.state.value.boardLoading)
      releaseInitial.complete(Unit)
      kotlinx.coroutines.withTimeout(2000) {
        while (model.state.value.sectionLoadStates[WorkSection.PHASES] != ListLoadState.READY_EMPTY) delay(10)
      }
      assertTrue(model.state.value.board?.items.isNullOrEmpty())

      model.refresh()
      kotlinx.coroutines.withTimeout(2000) { refreshStarted.await() }
      assertEquals(ListLoadState.REFRESHING, model.state.value.sectionLoadStates[WorkSection.PHASES])
      assertTrue(!model.state.value.boardLoading)
      assertTrue(model.state.value.board?.items.isNullOrEmpty())
    } finally {
      releaseInitial.complete(Unit)
      releaseRefresh.complete(Unit)
      model.closeAndJoin()
    }
  }

  @Test
  fun `confirmed completion notifies once while history and reconnect remain baseline`() = runBlocking {
    val active = item("live-goal")
    val completed = active.copy(lifecycle = Lifecycle.TERMINAL, finishedAt = active.updatedAt, updatedAt = active.updatedAt.plusSeconds(1))
    val historical = item("historical").copy(lifecycle = Lifecycle.TERMINAL)
    val values = java.util.concurrent.ConcurrentHashMap<WorkSection, Board>()
    WorkSection.entries.forEach { values[it] = board(Retention.DAY, emptyList()) }
    values[WorkSection.GOALS] = board(Retention.DAY, listOf(active))
    values[WorkSection.HISTORY] = board(Retention.DAY, listOf(historical))
    val notifications = java.util.concurrent.CopyOnWriteArrayList<WorkIdentity>()
    val source = FakeSource(boardCall = { error("Expected a section read") }, sectionCall = { Outcome.Value(values.getValue(it)) })
    val native = object : QuietNative() {
      override fun notify(title: String, body: String, identity: WorkIdentity, onClick: (WorkIdentity) -> Unit) { notifications += identity }
    }
    val model = DashboardController(source, source, source, source, MemorySettings(), native) {}
    try {
      kotlinx.coroutines.withTimeout(3000) { while (model.state.value.boardLoading || WorkSection.RECENT !in model.state.value.sections) delay(10) }
      model.observeHistory(true)
      kotlinx.coroutines.withTimeout(3000) { while (WorkSection.HISTORY !in model.state.value.sections) delay(10) }
      assertTrue(notifications.isEmpty())
      values[WorkSection.GOALS] = board(Retention.DAY, emptyList())
      values[WorkSection.RECENT] = board(Retention.DAY, listOf(completed))
      source.refresh()
      kotlinx.coroutines.withTimeout(3000) { while (notifications.isEmpty()) delay(10) }
      assertEquals(listOf(active.identity), notifications)
      values.replaceAll { _, value -> value.copy(observationSession = 1) }
      model.refresh()
      kotlinx.coroutines.withTimeout(3000) { while (model.state.value.sections.values.any { it.observationSession != 1L }) delay(10) }
      delay(100)
      assertEquals(listOf(active.identity), notifications)
    } finally { model.close() }
  }

  @Test
  fun `deferred completion notification is rejected after switching stores`() = runBlocking {
    val active = item("old-store-goal")
    val completed = active.copy(lifecycle = Lifecycle.TERMINAL, updatedAt = active.updatedAt.plusSeconds(1))
    val newStoreItem = item("new-store-goal")
    val boards = java.util.concurrent.ConcurrentHashMap<WorkSection, Board>()
    WorkSection.entries.forEach { boards[it] = board(Retention.DAY, emptyList()) }
    boards[WorkSection.GOALS] = board(Retention.DAY, listOf(active))
    val source = FakeSource(
      boardCall = { error("Expected a section read") },
      sectionCall = { section -> Outcome.Value(boards.getValue(section)) },
      selectAction = {
        if (it == "/second-store.db") {
          boards[WorkSection.GOALS] = board(Retention.DAY, listOf(newStoreItem))
          boards[WorkSection.RECENT] = board(Retention.DAY, emptyList())
        }
      },
    )
    val notifications = java.util.concurrent.CopyOnWriteArrayList<WorkIdentity>()
    val pending = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()
    val native = object : QuietNative() {
      override fun notifyAuthorized(
        title: String,
        body: String,
        identity: WorkIdentity,
        authorizeAndSend: (() -> Unit) -> Unit,
        onClick: (WorkIdentity) -> Unit,
      ) {
        pending += { authorizeAndSend { notifications += identity } }
      }
    }
    val model = DashboardController(source, source, source, source, MemorySettings(), native) {}
    try {
      kotlinx.coroutines.withTimeout(3000) {
        while (model.state.value.boardLoading || WorkSection.RECENT !in model.state.value.sections) delay(10)
      }
      boards[WorkSection.GOALS] = board(Retention.DAY, emptyList())
      boards[WorkSection.RECENT] = board(Retention.DAY, listOf(completed))
      source.refresh()
      kotlinx.coroutines.withTimeout(3000) { while (pending.isEmpty()) delay(10) }

      model.updateSettings(model.state.value.settings.copy(databasePath = "/second-store.db"))
      kotlinx.coroutines.withTimeout(3000) {
        while (model.state.value.sections[WorkSection.GOALS]?.items?.singleOrNull()?.identity !=
          newStoreItem.identity) delay(10)
      }
      pending.single().invoke()
      assertTrue(notifications.isEmpty())
    } finally {
      model.closeAndJoin()
    }
  }

  @Test
  fun `detail refresh retains the visible range without advancing another list`() = runBlocking {
    val selected = item("selected")
    val offsets = java.util.concurrent.CopyOnWriteArrayList<Pair<DetailList?, Int>>()
    val source = FakeSource(
      boardCall = { Outcome.Value(board(Retention.DAY, listOf(selected))) },
      boundedDetailCall = { _, cursor, list, startOffset ->
        val offset = cursor?.toInt() ?: startOffset
        offsets += list to offset
        Outcome.Value(detail(selected).copy(
          subtasks = (offset until offset + 10).map { WorkSubtask(it, "Task $it", "pending", null, null, emptyList()) },
          executionHistory = listOf(ExecutionInstance("run-$offset", "execution-$offset", "active", Instant.EPOCH, Instant.EPOCH, null, null)),
          pageOffset = offset,
          cursors = mapOf(DetailList.SUBTASKS to (offset + 10).toString(), DetailList.EXECUTIONS to (offset + 10).toString()),
        ))
      },
    )
    val model = DashboardController(source, source, source, source, MemorySettings(), QuietNative()) {}
    try {
      kotlinx.coroutines.withTimeout(3000) { while (model.state.value.boardLoading) delay(10) }
      model.select(selected.identity)
      kotlinx.coroutines.withTimeout(3000) { while (model.state.value.detailLoading) delay(10) }
      repeat(6) { index ->
        delay(20)
        model.loadMoreDetail(DetailList.SUBTASKS)
        kotlinx.coroutines.withTimeout(3000) { while (model.state.value.detail?.subtasks?.lastOrNull()?.id != (index + 2) * 10 - 1) delay(10) }
      }
      assertEquals((20..69).toList(), model.state.value.detail?.subtasks?.map { it.id })
      assertEquals(listOf("run-0"), model.state.value.detail?.executionHistory?.map { it.invocationId })
      offsets.clear()
      model.refresh()
      kotlinx.coroutines.withTimeout(3000) { while ((DetailList.SUBTASKS to 20) !in offsets || model.state.value.detailLoading) delay(10) }
      delay(100)
      assertEquals((20..69).toList(), model.state.value.detail?.subtasks?.map { it.id })
      assertTrue((DetailList.SUBTASKS to 10) !in offsets)
      assertEquals(listOf("run-0"), model.state.value.detail?.executionHistory?.map { it.invocationId })
    } finally { model.close() }
  }

  @Test
  fun `deleting detail rows shortens a retained window without reporting a failed refresh`() = runBlocking {
    val selected = item("selected")
    val count = AtomicInteger(20)
    val source = FakeSource(
      boardCall = { Outcome.Value(board(Retention.DAY, listOf(selected))) },
      boundedDetailCall = { _, cursor, _, startOffset ->
        val offset = cursor?.toInt() ?: startOffset
        val end = minOf(offset + 10, count.get())
        Outcome.Value(detail(selected).copy(
          subtasks = (offset until end).map { WorkSubtask(it, "Task $it", "pending", null, null, emptyList()) },
          pageOffset = offset,
          cursors = if (end < count.get()) mapOf(DetailList.SUBTASKS to end.toString()) else emptyMap(),
        ))
      },
    )
    val model = DashboardController(source, source, source, source, MemorySettings(), QuietNative()) {}
    try {
      kotlinx.coroutines.withTimeout(3000) { while (model.state.value.boardLoading) delay(10) }
      model.select(selected.identity)
      kotlinx.coroutines.withTimeout(3000) { while (model.state.value.detailLoading) delay(10) }
      model.loadMoreDetail(DetailList.SUBTASKS)
      kotlinx.coroutines.withTimeout(3000) { while (model.state.value.detail?.subtasks?.size != 20) delay(10) }
      count.set(7)
      model.refresh()
      kotlinx.coroutines.withTimeout(3000) { while (model.state.value.detail?.subtasks?.size != 7) delay(10) }
      assertEquals((0..6).toList(), model.state.value.detail?.subtasks?.map { it.id })
      assertEquals(null, model.state.value.detailProblem)
      assertEquals(ListLoadState.EXHAUSTED, model.state.value.detailLoadStates[DetailList.SUBTASKS])
    } finally { model.closeAndJoin() }
  }

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
        if (cursor == null) Outcome.Value(detail(selected).copy(subtasks = listOf(firstTask), cursors = mapOf(DetailList.SUBTASKS to "details-next"))) else {
          assertEquals("details-next", cursor)
          secondStarted.complete(Unit)
          releaseSecond.await()
          Outcome.Value(detail(selected).copy(subtasks = listOf(secondTask)))
        }
      },
    )
    val model = DashboardController(source, source, source, source, MemorySettings(), QuietNative()) {}
    try {
      kotlinx.coroutines.withTimeout(2000) { while (model.state.value.boardLoading) delay(10) }
      model.select(selected.identity)
      kotlinx.coroutines.withTimeout(2000) { while (model.state.value.detailLoading) delay(10) }
      delay(50)
      assertTrue(!secondStarted.isCompleted)
      model.loadMoreDetail(DetailList.SUBTASKS)
      model.loadMoreDetail(DetailList.SUBTASKS)
      kotlinx.coroutines.withTimeout(2000) { secondStarted.await() }
      assertEquals(listOf(firstTask), model.state.value.detail?.subtasks)
      assertTrue(DetailList.SUBTASKS in model.state.value.detailListLoading)
      releaseSecond.complete(Unit)
      kotlinx.coroutines.withTimeout(2000) { while (DetailList.SUBTASKS in model.state.value.detailListLoading) delay(10) }
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
    val model = DashboardController(source, source, source, source, MemorySettings(), QuietNative()) {}
    try {
      kotlinx.coroutines.withTimeout(2000) { while (model.state.value.boardLoading) delay(10) }
      delay(50)
      assertTrue(!secondStarted.isCompleted)
      model.loadMoreBoard()
      model.loadMoreBoard()
      kotlinx.coroutines.withTimeout(2000) { secondStarted.await() }
      assertEquals(listOf(first), model.state.value.board?.items)
      assertTrue(WorkSection.GOALS in model.state.value.sectionLoading)
      assertTrue(!model.state.value.boardLoading)
      releaseSecond.complete(Unit)
      kotlinx.coroutines.withTimeout(2000) { while (WorkSection.GOALS in model.state.value.sectionLoading) delay(10) }
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
        else Outcome.Failure(Problem(ProblemCode.DATABASE_ACCESS, "Timed out"))
      },
    )
    val model = DashboardController(source, source, source, source, MemorySettings(), QuietNative()) {}
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
    val model = DashboardController(source, source, source, source, MemorySettings(), QuietNative()) {}
    try {
      kotlinx.coroutines.withTimeout(2000) { while (model.state.value.boardLoading) delay(10) }
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
    val model = DashboardController(source, source, source, source, store, QuietNative()) {}
    kotlinx.coroutines.withTimeout(2000) { while (calls.get() == 0) delay(10) }
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
  fun `pending pause permits stop while repeated pause and standalone controls are refused`() = runBlocking {
    val pauseCalls = AtomicInteger()
    val stopped = CompletableDeferred<WorkIdentity>()
    val requested = item("requested").copy(pauseRequested = true, controlEligible = true)
    val phase = item("phase").copy(family = WorkFamily.FEATURE_VERIFY, scope = ExecutionScope.STANDALONE_PHASE)
    val source = FakeSource(boardCall = { Outcome.Value(board(it, listOf(requested, phase))) },
      pauseAction = { pauseCalls.incrementAndGet(); Outcome.Value(Unit) },
      stopAction = { stopped.complete(it.identity); Outcome.Value(Unit) })
    val model = DashboardController(source, source, source, source, MemorySettings(), QuietNative()) {}
    try {
      kotlinx.coroutines.withTimeout(2000) { while (model.state.value.boardLoading) delay(10) }
      model.perform("pause", requested.identity)
      model.perform("stop", phase.identity)
      assertEquals(0, pauseCalls.get())
      assertFalse(stopped.isCompleted)
      model.perform("stop", requested.identity)
      assertEquals(requested.identity, kotlinx.coroutines.withTimeout(2000) { stopped.await() })
    } finally { model.close() }
  }

  @Test
  fun `notification activation selects the identity that changed`() = runBlocking {
    val initial = item("workflow")
    val blocked = initial.copy(lifecycle = Lifecycle.BLOCKED, updatedAt = initial.updatedAt.plusSeconds(1))
    val observations = ArrayDeque(listOf(board(Retention.DAY, listOf(initial)), board(Retention.DAY, listOf(blocked))))
    val source = FakeSource(boardCall = { Outcome.Value(observations.removeFirst()) }, detailCall = { Outcome.Value(detail(blocked)) })
    val selected = CompletableDeferred<WorkIdentity>()
    val native = ClickNative()
    val model = DashboardController(source, source, source, source, MemorySettings(), native) { selected.complete(it) }
    kotlinx.coroutines.withTimeout(2000) { while (model.state.value.boardLoading) delay(10) }
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
  private val detailCall: suspend (WorkIdentity) -> Outcome<Detail> = { Outcome.Failure(Problem(ProblemCode.UNKNOWN_WORK_ITEM, "missing")) },
  private val pauseAction: suspend (WorkItem) -> Outcome<Unit> = { Outcome.Value(Unit) },
  private val stopAction: suspend (WorkItem) -> Outcome<Unit> = { Outcome.Value(Unit) },
  private val boardPageCall: (suspend (Retention, String?) -> Outcome<Board>)? = null,
  private val detailPageCall: (suspend (WorkIdentity, String?) -> Outcome<Detail>)? = null,
  private val sectionCall: (suspend (WorkSection) -> Outcome<Board>)? = null,
  private val boundedDetailCall: (suspend (WorkIdentity, String?, DetailList?, Int) -> Outcome<Detail>)? = null,
  private val selectAction: suspend (String?) -> Unit = {},
) : WorkBoardRepository, WorkDetailRepository, WorkControlRepository, WorkStoreSession {
  private val revision = kotlinx.coroutines.flow.MutableStateFlow(0L)
  var retainedPages: BoardRetention? = null
    private set
  override fun retainPages(retention: BoardRetention) { retainedPages = retention }
  override suspend fun selectDatabase(path: String?) = selectAction(path)
  override fun observe(section: WorkSection, retention: Retention, filter: String, historyFilter: HistoryFilter, anchor: String?) = kotlinx.coroutines.flow.flow {
    revision.collect { emit(board(section, retention, filter = filter, historyFilter = historyFilter)) }
  }
  override suspend fun board(section: WorkSection, retention: Retention, cursor: String?, filter: String, historyFilter: HistoryFilter): Outcome<Board> {
    val result = sectionCall?.invoke(section) ?: if (section == WorkSection.GOALS) boardPageCall?.invoke(retention, cursor) ?: boardCall(retention)
    else Outcome.Value(Board(Instant.parse("2026-10-11T10:00:00Z"), retention, emptyList()))
    return when (result) {
      is Outcome.Failure -> result
      is Outcome.Value -> Outcome.Value(result.value.copy(storeId = "test-store", revision = revision.value,
        queryEpoch = 1, filter = filter, historyFilter = historyFilter))
    }
  }
  override fun observeDetail(identity: WorkIdentity) = kotlinx.coroutines.flow.flow {
    revision.collect { emit(detail(identity)) }
  }
  override val refreshEvents = kotlinx.coroutines.flow.emptyFlow<Set<WorkSection>>()
  override suspend fun detail(identity: WorkIdentity, cursor: String?, list: DetailList?, startOffset: Int) = boundedDetailCall?.invoke(identity, cursor, list, startOffset) ?: detailPageCall?.invoke(identity, cursor) ?: detailCall(identity)
  override suspend fun refresh() { revision.value++ }
  override fun close() = Unit
  override suspend fun closeAndJoin() = Unit

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
  private val flow = kotlinx.coroutines.flow.MutableStateFlow<Outcome<AppSettings>>(Outcome.Value(value))
  override val settings = flow
  override suspend fun update(transform: (AppSettings) -> AppSettings): Outcome<Unit> {
    value = transform((flow.value as Outcome.Value).value)
    flow.value = Outcome.Value(value)
    return Outcome.Value(Unit)
  }
  override fun close() = Unit
  override suspend fun closeAndJoin() = Unit
}
