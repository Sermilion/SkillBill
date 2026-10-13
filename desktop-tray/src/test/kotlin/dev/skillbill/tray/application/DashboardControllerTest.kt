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
  fun `startup selects loaded live work while another live section refreshes`() = runBlocking {
    val preferred = item("live")
    val releaseAttention = CompletableDeferred<Unit>()
    val refreshEvents = kotlinx.coroutines.flow.MutableSharedFlow<Set<WorkSection>>()
    val source = FakeSource(
      boardCall = { error("Expected a section read") },
      sectionCall = { section ->
        if (section == WorkSection.NEEDS_YOU) releaseAttention.await()
        Outcome.Value(board(Retention.DAY, if (section == WorkSection.GOALS) listOf(preferred) else emptyList()))
      },
      detailCall = { Outcome.Value(detail(preferred)) },
      refreshEvents = refreshEvents,
    )
    val model = DashboardController(source, source, source, source, MemorySettings(), QuietNative()) {}
    try {
      kotlinx.coroutines.withTimeout(3000) {
        while (WorkSection.GOALS !in model.state.value.sections || WorkSection.PHASES !in model.state.value.sections ||
          refreshEvents.subscriptionCount.value == 0) delay(10)
      }
      refreshEvents.emit(setOf(WorkSection.GOALS))
      kotlinx.coroutines.withTimeout(3000) {
        while (model.state.value.sectionLoadStates[WorkSection.GOALS] != ListLoadState.REFRESHING) delay(10)
      }
      releaseAttention.complete(Unit)
      kotlinx.coroutines.withTimeout(3000) { while (model.state.value.detail?.item?.identity != preferred.identity) delay(10) }
      assertEquals(ListLoadState.REFRESHING, model.state.value.sectionLoadStates[WorkSection.GOALS])
      assertFalse(model.state.value.detailLoading)
    } finally {
      releaseAttention.complete(Unit)
      model.closeAndJoin()
    }
  }

  @Test
  fun `idle startup waits for live sections then selects latest history and preserves an explicit selection`() = runBlocking {
    val latest = item("latest").copy(lifecycle = Lifecycle.TERMINAL, finishedAt = Instant.parse("2026-10-11T09:00:00Z"))
    val older = item("older").copy(lifecycle = Lifecycle.TERMINAL, finishedAt = latest.finishedAt!!.minusSeconds(60))
    val history = kotlinx.coroutines.flow.MutableStateFlow(listOf(latest, older))
    val releasePhases = CompletableDeferred<Unit>()
    val selections = java.util.concurrent.CopyOnWriteArrayList<WorkIdentity>()
    val source = FakeSource(
      boardCall = { error("Expected a section read") },
      sectionCall = { section ->
        if (section == WorkSection.PHASES) releasePhases.await()
        Outcome.Value(board(Retention.DAY, if (section == WorkSection.HISTORY) history.value else emptyList()))
      },
      detailCall = { identity -> Outcome.Value(detail(history.value.single { it.identity == identity })) },
    )
    val model = DashboardController(source, source, source, source, MemorySettings(), QuietNative()) { selections += it }
    try {
      kotlinx.coroutines.withTimeout(3000) { while (WorkSection.HISTORY !in model.state.value.sections) delay(10) }
      assertEquals(null, model.state.value.selected)
      releasePhases.complete(Unit)
      kotlinx.coroutines.withTimeout(3000) { while (model.state.value.detail?.item?.identity != latest.identity) delay(10) }
      assertTrue(selections.isEmpty())
      model.select(older.identity)
      kotlinx.coroutines.withTimeout(3000) { while (model.state.value.detail?.item?.identity != older.identity) delay(10) }
      val newer = latest.copy(identity = WorkIdentity("newer", latest.identity.repositoryIdentity), finishedAt = latest.finishedAt!!.plusSeconds(60))
      history.value = listOf(newer, latest, older)
      model.refresh()
      kotlinx.coroutines.withTimeout(3000) { while (model.state.value.sections[WorkSection.HISTORY]?.items?.firstOrNull()?.identity != newer.identity) delay(10) }
      assertEquals(older.identity, model.state.value.selected)
      assertEquals(listOf(older.identity), selections)
    } finally {
      releasePhases.complete(Unit)
      model.closeAndJoin()
    }
  }

  @Test
  fun `live work takes priority over history and failed live reads cannot establish an idle default`() = runBlocking {
    for (section in listOf(WorkSection.NEEDS_YOU, WorkSection.GOALS, WorkSection.PHASES, null)) {
      val historical = item("history").copy(lifecycle = Lifecycle.TERMINAL)
      val preferred = item("live")
      val source = FakeSource(
        boardCall = { error("Expected a section read") },
        sectionCall = { current ->
          if (section == null && current == WorkSection.GOALS) Outcome.Failure(Problem(ProblemCode.DATABASE_ACCESS, "Unreadable goals"))
          else Outcome.Value(board(Retention.DAY, when (current) {
            WorkSection.HISTORY -> listOf(historical)
            section -> listOf(preferred)
            else -> emptyList()
          }))
        },
        detailCall = { Outcome.Value(detail(preferred)) },
      )
      val model = DashboardController(source, source, source, source, MemorySettings(), QuietNative()) {}
      try {
        kotlinx.coroutines.withTimeout(3000) {
          while (model.state.value.sectionLoading.isNotEmpty() ||
            section != null && model.state.value.selected == null) delay(10)
        }
        assertEquals(if (section == null) null else preferred.identity, model.state.value.selected)
      } finally { model.closeAndJoin() }
    }
  }

  @Test
  fun `changing history filter clears old rows and cannot append the previous filter cursor`() = runBlocking {
    val first = item("all-history")
    val goal = item("goal-history")
    val olderGoal = item("older-goal")
    val filterStarted = CompletableDeferred<Unit>()
    val releaseFilter = CompletableDeferred<Unit>()
    val appendCursors = java.util.concurrent.CopyOnWriteArrayList<String>()
    val source = FakeSource(
      boardCall = { error("Expected a section request") },
      sectionPageCall = { section, retention, cursor, filter ->
        when {
          section != WorkSection.HISTORY -> Outcome.Value(board(retention, emptyList()))
          filter == HistoryFilter.ALL -> Outcome.Value(board(retention, listOf(first)).copy(nextCursor = "all-next"))
          cursor == null -> {
            filterStarted.complete(Unit)
            releaseFilter.await()
            Outcome.Value(board(retention, listOf(goal)).copy(nextCursor = "goals-next"))
          }
          else -> {
            appendCursors += cursor
            assertEquals("goals-next", cursor)
            Outcome.Value(board(retention, listOf(olderGoal)))
          }
        }
      },
    )
    val model = DashboardController(source, source, source, source, MemorySettings(), QuietNative()) {}
    try {
      kotlinx.coroutines.withTimeout(3000) { while (WorkSection.HISTORY !in model.state.value.sections) delay(10) }
      model.updateSettings(model.state.value.settings.copy(historyFilter = HistoryFilter.GOALS))
      kotlinx.coroutines.withTimeout(3000) { filterStarted.await() }
      assertEquals(null, model.state.value.sections[WorkSection.HISTORY])
      model.loadMore(WorkSection.HISTORY)
      assertTrue(appendCursors.isEmpty())
      releaseFilter.complete(Unit)
      kotlinx.coroutines.withTimeout(3000) { while (model.state.value.sections[WorkSection.HISTORY]?.historyFilter != HistoryFilter.GOALS) delay(10) }
      model.loadMore(WorkSection.HISTORY)
      kotlinx.coroutines.withTimeout(3000) { while (WorkSection.HISTORY in model.state.value.sectionLoading) delay(10) }
      assertEquals(listOf(goal, olderGoal), model.state.value.sections[WorkSection.HISTORY]?.items)
      assertEquals(listOf("goals-next"), appendCursors)
      assertEquals(null, model.state.value.sectionProblems[WorkSection.HISTORY])
    } finally {
      releaseFilter.complete(Unit)
      model.closeAndJoin()
    }
  }

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
      kotlinx.coroutines.withTimeout(2000) {
        while (model.state.value.boardLoading) delay(10)
      }

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
  fun `goal and history lists stop after one page and load the next page only on demand`() = runBlocking {
    for (section in listOf(WorkSection.GOALS, WorkSection.HISTORY)) {
      val releaseSecond = CompletableDeferred<Unit>()
      val secondStarted = CompletableDeferred<Unit>()
      val first = item("first")
      val second = item("second")
      val source = FakeSource(
        boardCall = { error("Expected a paginated request") },
        sectionPageCall = { current, retention, cursor, _ ->
          if (current != section) Outcome.Value(board(retention, emptyList()))
          else if (cursor == null) Outcome.Value(board(retention, listOf(first)).copy(nextCursor = "next")) else {
            assertEquals("next", cursor)
            secondStarted.complete(Unit)
            releaseSecond.await()
            Outcome.Value(board(retention, listOf(second)))
          }
        },
      )
      val model = DashboardController(source, source, source, source, MemorySettings(), QuietNative()) {}
      try {
        kotlinx.coroutines.withTimeout(2000) { while (section !in model.state.value.sections || model.state.value.boardLoading) delay(10) }
        assertTrue(!secondStarted.isCompleted)
        model.loadMore(section)
        model.loadMore(section)
        kotlinx.coroutines.withTimeout(2000) { secondStarted.await() }
        assertEquals(listOf(first), model.state.value.sections[section]?.items)
        assertTrue(section in model.state.value.sectionLoading)
        assertTrue(!model.state.value.boardLoading)
        releaseSecond.complete(Unit)
        kotlinx.coroutines.withTimeout(2000) { while (section in model.state.value.sectionLoading) delay(10) }
        assertEquals(listOf(first, second), model.state.value.sections[section]?.items)
        assertEquals(null, model.state.value.sections[section]?.nextCursor)
        assertEquals(null, model.state.value.problem)
      } finally {
        releaseSecond.complete(Unit)
        model.closeAndJoin()
      }
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
  fun `resume uses the selected detail revision and prevents duplicate actions while starting`() = runBlocking {
    val boardItem = item("paused").copy(lifecycle = Lifecycle.PAUSED, freshness = Freshness.STALE)
    val latest = boardItem.copy(updatedAt = boardItem.updatedAt.plusSeconds(1), statusRevision = "2")
    val entered = CompletableDeferred<Pair<WorkItem, String?>>()
    val release = CompletableDeferred<Unit>()
    val calls = AtomicInteger()
    var registered = false
    val active = latest.copy(lifecycle = Lifecycle.ACTIVE, updatedAt = latest.updatedAt.plusSeconds(1))
    val source = FakeSource(
      boardCall = { Outcome.Value(board(it, listOf(if (registered) active else boardItem))) },
      detailCall = { Outcome.Value(detail(latest).copy(runOptions = RunOptions(true, true, "Resume", null, listOf("codex"), null))) },
      launchAction = { item, agent ->
        calls.incrementAndGet()
        entered.complete(item to agent)
        release.await()
        Outcome.Value(Unit)
      },
    )
    val model = DashboardController(source, source, source, source, MemorySettings(), QuietNative()) {}
    try {
      kotlinx.coroutines.withTimeout(2000) { while (model.state.value.boardLoading) delay(10) }
      model.select(boardItem.identity)
      kotlinx.coroutines.withTimeout(2000) { while (model.state.value.detail?.runOptions == null) delay(10) }
      model.perform("launch:codex", boardItem.identity)
      assertEquals(latest to "codex", kotlinx.coroutines.withTimeout(2000) { entered.await() })
      assertTrue(boardItem.identity in model.state.value.actionsInProgress)
      model.perform("launch:codex", boardItem.identity)
      model.perform("cancel", boardItem.identity)
      assertEquals(1, calls.get())
      release.complete(Unit)
      delay(100)
      assertTrue(boardItem.identity in model.state.value.actionsInProgress)
      registered = true
      source.refresh()
      kotlinx.coroutines.withTimeout(2000) { while (boardItem.identity in model.state.value.actionsInProgress) delay(10) }
    } finally {
      release.complete(Unit)
      model.close()
    }
  }

  @Test
  fun `confirmed cancellation removes attention immediately and stale section replays cannot restore it`() = runBlocking {
    val blocked = item("blocked").copy(lifecycle = Lifecycle.BLOCKED)
    val source = FakeSource(boardCall = { error("Expected sections") },
      sectionCall = { section -> Outcome.Value(board(Retention.DAY,
        if (section == WorkSection.NEEDS_YOU) listOf(blocked) else emptyList())) },
      detailCall = { Outcome.Value(detail(blocked).copy(runOptions = RunOptions(true, true, "Run again", "codex", listOf("codex"), null))) })
    val model = DashboardController(source, source, source, source, MemorySettings(), QuietNative()) {}
    try {
      kotlinx.coroutines.withTimeout(2000) { while (model.state.value.detail?.runOptions == null) delay(10) }
      model.perform("cancel", blocked.identity)
      kotlinx.coroutines.withTimeout(2000) {
        while (model.state.value.sections[WorkSection.NEEDS_YOU]?.items?.isNotEmpty() != false) delay(10)
      }
      source.refresh()
      delay(100)
      assertTrue(model.state.value.sections.getValue(WorkSection.NEEDS_YOU).items.isEmpty())
    } finally { model.closeAndJoin() }
  }

  @Test
  fun `run again stays pending until a new standalone invocation appears and selects that attempt`() = runBlocking {
    val old = item("old").copy(scope = ExecutionScope.STANDALONE_PHASE, phaseId = "monitor",
      issueKey = null, lifecycle = Lifecycle.CANCELED, startedAt = Instant.parse("2026-10-11T09:00:00Z"))
    val next = old.copy(identity = WorkIdentity("new", old.identity.repositoryIdentity), lifecycle = Lifecycle.BLOCKED,
      startedAt = old.startedAt!!.plusSeconds(60), updatedAt = old.updatedAt.plusSeconds(60))
    var registered = false
    val source = FakeSource(boardCall = { error("Expected sections") },
      sectionCall = { section -> Outcome.Value(board(Retention.DAY, when {
        section == WorkSection.HISTORY -> listOf(old)
        registered && section == WorkSection.NEEDS_YOU -> listOf(next)
        else -> emptyList()
      })) },
      detailCall = { identity -> Outcome.Value(detail(if (identity == next.identity) next else old)
        .copy(runOptions = RunOptions(false, true, "Run again", "codex", listOf("codex"), null))) })
    val model = DashboardController(source, source, source, source, MemorySettings(), QuietNative(),
      java.time.Clock.fixed(old.startedAt!!.plusSeconds(30), java.time.ZoneOffset.UTC)) {}
    try {
      kotlinx.coroutines.withTimeout(2000) { while (model.state.value.detail?.runOptions == null) delay(10) }
      model.perform("launch:codex", old.identity)
      kotlinx.coroutines.withTimeout(2000) { while (old.identity !in model.state.value.actionsInProgress) delay(10) }
      delay(100)
      assertTrue(old.identity in model.state.value.actionsInProgress)
      registered = true
      source.refresh()
      kotlinx.coroutines.withTimeout(2000) { while (model.state.value.selected != next.identity) delay(10) }
    } finally { model.closeAndJoin() }
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
  private val cancelAction: suspend (WorkItem) -> Outcome<Unit> = { Outcome.Value(Unit) },
  private val launchAction: suspend (WorkItem, String?) -> Outcome<Unit> = { _, _ -> Outcome.Value(Unit) },
  private val boardPageCall: (suspend (Retention, String?) -> Outcome<Board>)? = null,
  private val detailPageCall: (suspend (WorkIdentity, String?) -> Outcome<Detail>)? = null,
  private val sectionCall: (suspend (WorkSection) -> Outcome<Board>)? = null,
  private val sectionPageCall: (suspend (WorkSection, Retention, String?, HistoryFilter) -> Outcome<Board>)? = null,
  private val boundedDetailCall: (suspend (WorkIdentity, String?, DetailList?, Int) -> Outcome<Detail>)? = null,
  private val selectAction: suspend (String?) -> Unit = {},
  override val refreshEvents: kotlinx.coroutines.flow.Flow<Set<WorkSection>> = kotlinx.coroutines.flow.emptyFlow(),
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
    val result = sectionPageCall?.invoke(section, retention, cursor, historyFilter) ?: sectionCall?.invoke(section) ?: if (section == WorkSection.GOALS) boardPageCall?.invoke(retention, cursor) ?: boardCall(retention)
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
  override suspend fun detail(identity: WorkIdentity, cursor: String?, list: DetailList?, startOffset: Int) = boundedDetailCall?.invoke(identity, cursor, list, startOffset) ?: detailPageCall?.invoke(identity, cursor) ?: detailCall(identity)
  override suspend fun refresh() { revision.value++ }
  override fun close() = Unit
  override suspend fun closeAndJoin() = Unit

  override suspend fun pause(item: WorkItem) = pauseAction(item)
  override suspend fun stop(item: WorkItem) = stopAction(item)
  override suspend fun cancel(item: WorkItem) = cancelAction(item)
  override suspend fun launch(item: WorkItem, agentId: String?) = launchAction(item, agentId)
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
