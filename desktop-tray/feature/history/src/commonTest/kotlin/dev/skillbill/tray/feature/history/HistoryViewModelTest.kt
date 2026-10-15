package dev.skillbill.tray.feature.history

import dev.skillbill.tray.core.domain.model.AppSettings
import dev.skillbill.tray.core.domain.model.HistoryFilter
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
import dev.skillbill.tray.core.ui.FeedbackTone
import dev.skillbill.tray.core.ui.PagedListState
import dev.skillbill.tray.core.ui.items
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
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModelTest {
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

  private fun TestScope.viewModel(): HistoryViewModel = HistoryViewModel(boards, settings).also { runCurrent() }

  private fun emit(vararg items: WorkItem, nextCursor: String? = null, historyFilter: HistoryFilter = HistoryFilter.ALL) =
    boards.emit(
      WorkSection.HISTORY,
      Outcome.Value(observedBoard(items.toList(), nextCursor = nextCursor, historyFilter = historyFilter)),
    )

  private fun ids(state: PagedListState<WorkItem>) = state.items.map { it.identity.id }

  @Test
  fun `history reads only its own section and shows what it read`() = runTest(dispatcher) {
    val vm = viewModel()
    assertEquals(listOf(WorkSection.HISTORY), boards.observeCalls.map { it.section })
    assertEquals(PagedListState.InitialLoading, vm.state.value.list)

    emit(workItem("done-1"), workItem("done-2"))
    runCurrent()

    assertEquals(listOf("done-1", "done-2"), ids(vm.state.value.list))
  }

  @Test
  fun `a changed history filter clears the list and the old cursor can never be appended`() = runTest(dispatcher) {
    val vm = viewModel()
    emit(workItem("all"), nextCursor = "all-next")
    runCurrent()

    vm.onAction(HistoryAction.ChooseFilter(HistoryFilter.GOALS))
    runCurrent()
    assertEquals(PagedListState.InitialLoading, vm.state.value.list)
    assertEquals(HistoryFilter.GOALS, vm.state.value.historyFilter)

    vm.onAction(HistoryAction.LoadMore)
    runCurrent()
    assertTrue(boards.readCalls.isEmpty(), "No page may be requested with the previous filter's cursor.")

    emit(workItem("goal"), nextCursor = "goals-next", historyFilter = HistoryFilter.GOALS)
    runCurrent()
    boards.onBoard = { Outcome.Value(observedBoard(listOf(workItem("older")), historyFilter = HistoryFilter.GOALS)) }
    vm.onAction(HistoryAction.LoadMore)
    runCurrent()

    assertEquals(listOf("goals-next"), boards.readCalls.map { it.cursor })
    assertEquals(HistoryFilter.GOALS, boards.readCalls.single().historyFilter)
    assertEquals(listOf("goal", "older"), ids(vm.state.value.list))
  }

  @Test
  fun `typing a filter restarts the read and cannot append the previous query's cursor`() = runTest(dispatcher) {
    val vm = viewModel()
    emit(workItem("a"), nextCursor = "old-next")
    runCurrent()

    vm.onAction(HistoryAction.FilterChanged("skill"))
    vm.onAction(HistoryAction.LoadMore)
    runCurrent()

    assertTrue(boards.readCalls.isEmpty())
    assertEquals("skill", boards.observeCalls.last().filter)
    val refreshing = assertIs<PagedListState.Content<WorkItem>>(vm.state.value.list)
    assertTrue(refreshing.refreshing, "The rows stay while the filtered read is under way.")
  }

  @Test
  fun `a failed first read can be retried`() = runTest(dispatcher) {
    val vm = viewModel()
    val problem = Problem(ProblemCode.DATABASE_ACCESS, "Unreadable")
    boards.emit(WorkSection.HISTORY, Outcome.Failure(problem))
    runCurrent()
    assertEquals(PagedListState.InitialError(problem), vm.state.value.list)

    vm.onAction(HistoryAction.Retry)
    runCurrent()
    assertEquals(PagedListState.InitialLoading, vm.state.value.list)
    assertEquals(2, boards.observeCalls.size)
  }

  @Test
  fun `unreadable settings leave history with the error and not an empty list`() = runTest(dispatcher) {
    settings.publish(SettingsState.Failed(Problem(ProblemCode.SETTINGS_CORRUPT, "Settings are corrupt")))
    val vm = viewModel()

    assertEquals(ProblemCode.SETTINGS_CORRUPT, assertIs<PagedListState.InitialError>(vm.state.value.list).problem.code)
    assertTrue(boards.observeCalls.isEmpty())
  }

  @Test
  fun `a retention change keeps the rows while the new window is read`() = runTest(dispatcher) {
    val vm = viewModel()
    emit(workItem("a"))
    runCurrent()

    settings.publish(SettingsState.Ready(AppSettings(retention = Retention.WEEK)))
    runCurrent()

    val content = assertIs<PagedListState.Content<WorkItem>>(vm.state.value.list)
    assertTrue(content.refreshing)
    assertEquals(Retention.WEEK, boards.observeCalls.last().retention)
  }

  @Test
  fun `a failed filter save becomes feedback that is acknowledged by id`() = runTest(dispatcher) {
    val vm = viewModel()
    settings.onUpdate = { Outcome.Failure(Problem(ProblemCode.SETTINGS_UPDATE_FAILED, "disk full")) }

    vm.onAction(HistoryAction.ChooseFilter(HistoryFilter.PHASES))
    runCurrent()

    val feedback = vm.state.value.pendingFeedback.single()
    assertEquals(FeedbackTone.PROBLEM, feedback.tone)
    assertEquals("disk full", feedback.message)
    assertEquals(HistoryFilter.ALL, vm.state.value.historyFilter)

    vm.onAction(HistoryAction.AcknowledgeFeedback(feedback.id))
    assertTrue(vm.state.value.pendingFeedback.isEmpty())
  }

  @Test
  fun `selecting a row sends one navigation effect`() = runTest(dispatcher) {
    val vm = viewModel()
    val identity = workItem("a").identity

    vm.onAction(HistoryAction.Select(identity))

    assertEquals(listOf<HistoryEffect>(HistoryEffect.OpenDetail(identity)), vm.effects.take(1).toList())
  }
}
