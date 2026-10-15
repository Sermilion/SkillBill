package dev.skillbill.tray.feature.work

import dev.skillbill.tray.core.domain.model.AppSettings
import dev.skillbill.tray.core.domain.model.Board
import dev.skillbill.tray.core.domain.model.Problem
import dev.skillbill.tray.core.domain.model.ProblemCode
import dev.skillbill.tray.core.domain.model.Retention
import dev.skillbill.tray.core.domain.model.SettingsState
import dev.skillbill.tray.core.domain.model.WorkItem
import dev.skillbill.tray.core.domain.model.WorkSection
import dev.skillbill.tray.core.domain.repository.Outcome
import dev.skillbill.tray.core.testing.FakeSettingsRepository
import dev.skillbill.tray.core.testing.FakeWorkBoardRepository
import dev.skillbill.tray.core.testing.observedBoard
import dev.skillbill.tray.core.testing.workItem
import dev.skillbill.tray.core.ui.AppendState
import dev.skillbill.tray.core.ui.PagedListState
import dev.skillbill.tray.core.ui.items
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class WorkViewModelTest {
  private val dispatcher = StandardTestDispatcher()
  private val boards = FakeWorkBoardRepository()
  private val settings = FakeSettingsRepository(SettingsState.Ready(AppSettings()))

  @BeforeTest
  fun setUp() {
    Dispatchers.setMain(dispatcher)
  }

  @AfterTest
  fun tearDown() {
    Dispatchers.resetMain()
  }

  private fun TestScope.viewModel(): WorkViewModel = WorkViewModel(boards, settings).also { runCurrent() }

  private fun live(id: String, section: WorkSection = WorkSection.GOALS): WorkItem = workItem(id).copy(liveSection = section)

  private fun emit(section: WorkSection, vararg items: WorkItem, nextCursor: String? = null, filter: String = "") =
    boards.emit(section, Outcome.Value(observedBoard(items.toList(), nextCursor = nextCursor, filter = filter)))

  private fun ids(state: PagedListState<WorkItem>) = state.items.map { it.identity.id }

  @Test
  fun `every live section starts reading and each one lands on its own`() = runTest(dispatcher) {
    val vm = viewModel()
    assertEquals(
      setOf(WorkSection.NEEDS_YOU, WorkSection.GOALS, WorkSection.PHASES),
      boards.observeCalls.map { it.section }.toSet(),
    )
    assertEquals(PagedListState.InitialLoading, vm.state.value.goals)

    emit(WorkSection.GOALS, live("g1"))
    runCurrent()

    val state = vm.state.value
    assertEquals(listOf("g1"), ids(state.goals))
    assertEquals(PagedListState.InitialLoading, state.needsYou)
    assertEquals(PagedListState.InitialLoading, state.phases)
    assertTrue(state.loaded)

    emit(WorkSection.NEEDS_YOU)
    emit(WorkSection.PHASES)
    runCurrent()

    assertEquals(PagedListState.LoadedEmpty(), vm.state.value.needsYou)
  }

  @Test
  fun `a live section drops rows the runtime no longer calls live`() = runTest(dispatcher) {
    val vm = viewModel()

    emit(WorkSection.GOALS, live("g1"), workItem("finished"))
    runCurrent()

    assertEquals(listOf("g1"), ids(vm.state.value.goals))
  }

  @Test
  fun `a refresh keeps the rows on screen and asks the store once`() = runTest(dispatcher) {
    val vm = viewModel()
    emit(WorkSection.GOALS, live("g1"))
    runCurrent()

    vm.onAction(WorkAction.Refresh)
    runCurrent()

    val refreshing = assertIs<PagedListState.Content<WorkItem>>(vm.state.value.goals)
    assertTrue(refreshing.refreshing)
    assertEquals(listOf("g1"), ids(refreshing))
    assertEquals(PagedListState.InitialLoading, vm.state.value.phases)
    assertEquals(1, boards.refreshCount)

    emit(WorkSection.GOALS, live("g1"), live("g2"))
    runCurrent()

    val loaded = assertIs<PagedListState.Content<WorkItem>>(vm.state.value.goals)
    assertTrue(!loaded.refreshing)
    assertEquals(listOf("g1", "g2"), ids(loaded))
  }

  @Test
  fun `a store change announced for one section marks only that section refreshing`() = runTest(dispatcher) {
    val vm = viewModel()
    emit(WorkSection.GOALS, live("g1"))
    emit(WorkSection.PHASES, live("p1", WorkSection.PHASES))
    runCurrent()

    boards.emitRefresh(setOf(WorkSection.PHASES))
    runCurrent()

    assertTrue(assertIs<PagedListState.Content<WorkItem>>(vm.state.value.phases).refreshing)
    assertTrue(!assertIs<PagedListState.Content<WorkItem>>(vm.state.value.goals).refreshing)
  }

  @Test
  fun `a failed append keeps the rows and a retry reads the page once`() = runTest(dispatcher) {
    val vm = viewModel()
    val problem = Problem(ProblemCode.DATABASE_ACCESS, "Timed out")
    boards.onBoard = { Outcome.Failure(problem) }
    emit(WorkSection.GOALS, live("g1"), nextCursor = "next")
    runCurrent()

    vm.onAction(WorkAction.LoadMore(WorkSection.GOALS))
    runCurrent()

    val failed = assertIs<PagedListState.Content<WorkItem>>(vm.state.value.goals)
    assertEquals(listOf("g1"), ids(failed))
    assertEquals(AppendState.Failed(problem), failed.append)

    val gate = CompletableDeferred<Outcome<Board>>()
    boards.onBoard = { gate.await() }
    vm.onAction(WorkAction.LoadMore(WorkSection.GOALS))
    vm.onAction(WorkAction.LoadMore(WorkSection.GOALS))
    runCurrent()

    assertEquals(2, boards.readCalls.size, "The failed read and exactly one retry.")
    assertEquals(AppendState.Loading, assertIs<PagedListState.Content<WorkItem>>(vm.state.value.goals).append)

    gate.complete(Outcome.Value(observedBoard(listOf(live("g2")))))
    runCurrent()

    assertEquals(listOf("g1", "g2"), ids(vm.state.value.goals))
  }

  @Test
  fun `a filter restarts the live sections with it`() = runTest(dispatcher) {
    val vm = viewModel()
    val before = boards.observeCalls.size

    vm.onAction(WorkAction.FilterChanged("skill"))
    runCurrent()

    val restarted = boards.observeCalls.drop(before)
    assertEquals(setOf(WorkSection.NEEDS_YOU, WorkSection.GOALS, WorkSection.PHASES), restarted.map { it.section }.toSet())
    assertTrue(restarted.all { it.filter == "skill" })

    vm.onAction(WorkAction.FilterChanged("skill"))
    runCurrent()
    assertEquals(before + restarted.size, boards.observeCalls.size, "The same text does not restart anything.")
  }

  @Test
  fun `unreadable settings fail every section and recovering restarts them`() = runTest(dispatcher) {
    settings.publish(SettingsState.Failed(Problem(ProblemCode.SETTINGS_CORRUPT, "Settings are corrupt")))
    val vm = viewModel()

    val state = vm.state.value
    listOf(state.needsYou, state.goals, state.phases).forEach { assertIs<PagedListState.InitialError>(it) }
    assertEquals(ProblemCode.SETTINGS_CORRUPT, state.liveProblem?.code)
    assertTrue(boards.observeCalls.isEmpty())

    settings.publish(SettingsState.Ready(AppSettings()))
    runCurrent()

    assertEquals(PagedListState.InitialLoading, vm.state.value.goals)
    assertEquals(3, boards.observeCalls.size)
  }

  @Test
  fun `a retention change keeps the rows while the sections read the new window`() = runTest(dispatcher) {
    val vm = viewModel()
    emit(WorkSection.GOALS, live("g1"))
    runCurrent()
    val before = boards.observeCalls.size

    settings.publish(SettingsState.Ready(AppSettings(retention = Retention.WEEK)))
    runCurrent()

    val goals = assertIs<PagedListState.Content<WorkItem>>(vm.state.value.goals)
    assertTrue(goals.refreshing)
    assertEquals(listOf("g1"), ids(goals))
    assertTrue(boards.observeCalls.drop(before).all { it.retention == Retention.WEEK })
  }

  @Test
  fun `a different store clears the rows instead of showing the old store's work`() = runTest(dispatcher) {
    val vm = viewModel()
    emit(WorkSection.GOALS, live("g1"))
    runCurrent()

    settings.publish(SettingsState.Ready(AppSettings(databasePath = "/other.db")))
    runCurrent()

    assertEquals(PagedListState.InitialLoading, vm.state.value.goals)
  }

  @Test
  fun `selecting work sends one navigation effect and keeps only the latest when nobody collects`() = runTest(dispatcher) {
    val vm = viewModel()
    val first = workItem("a").identity
    val second = workItem("b").identity

    vm.onAction(WorkAction.Select(first))
    vm.onAction(WorkAction.Select(second))

    assertEquals(listOf<WorkEffect>(WorkEffect.OpenDetail(second)), vm.effects.take(1).toList())
  }

  @Test
  fun `the board state carries the store the sections were read from`() = runTest(dispatcher) {
    val vm = viewModel()
    assertNull(vm.state.value.store)

    emit(WorkSection.GOALS, live("g1"))
    runCurrent()

    assertNotNull(vm.state.value.store)
  }
}
