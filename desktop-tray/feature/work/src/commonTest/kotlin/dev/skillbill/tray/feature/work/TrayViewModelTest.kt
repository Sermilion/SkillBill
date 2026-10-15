package dev.skillbill.tray.feature.work

import dev.skillbill.tray.core.domain.model.AppSettings
import dev.skillbill.tray.core.domain.model.Problem
import dev.skillbill.tray.core.domain.model.ProblemCode
import dev.skillbill.tray.core.domain.model.SettingsState
import dev.skillbill.tray.core.domain.model.WorkItem
import dev.skillbill.tray.core.domain.model.WorkSection
import dev.skillbill.tray.core.domain.repository.Outcome
import dev.skillbill.tray.core.testing.FakeSettingsRepository
import dev.skillbill.tray.core.testing.FakeWorkBoardRepository
import dev.skillbill.tray.core.testing.FakeWorkPolicyRepository
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
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TrayViewModelTest {
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

  private fun TestScope.viewModel(): TrayViewModel = TrayViewModel(boards, FakeWorkPolicyRepository(), settings).also { runCurrent() }

  private fun live(id: String, section: WorkSection = WorkSection.GOALS): WorkItem = workItem(id).copy(liveSection = section)

  private fun emit(section: WorkSection, vararg items: WorkItem, nextCursor: String? = null, filter: String = "") =
    boards.emit(section, Outcome.Value(observedBoard(items.toList(), nextCursor = nextCursor, filter = filter)))

  private fun ids(state: PagedListState<WorkItem>) = state.items.map { it.identity.id }

  @Test
  fun `every section starts reading and each one lands on its own`() = runTest(dispatcher) {
    val vm = viewModel()
    assertEquals(
      setOf(WorkSection.NEEDS_YOU, WorkSection.GOALS, WorkSection.PHASES, WorkSection.RECENT),
      boards.observeCalls.map { it.section }.toSet(),
    )
    assertEquals(PagedListState.InitialLoading, vm.state.value.sections.goals)

    emit(WorkSection.GOALS, live("g1"))
    runCurrent()

    val state = vm.state.value
    assertEquals(listOf("g1"), ids(state.sections.goals))
    assertEquals(PagedListState.InitialLoading, state.sections.needsYou)
    assertEquals(PagedListState.InitialLoading, state.sections.phases)
    assertEquals(PagedListState.InitialLoading, state.sections.recent)
    assertTrue(state.loaded)
    assertTrue(!state.sections.settled)
    assertEquals(listOf("g1"), state.popoverGoals.map { it.identity.id })

    emit(WorkSection.NEEDS_YOU)
    emit(WorkSection.PHASES)
    emit(WorkSection.RECENT, workItem("r1"))
    runCurrent()

    assertTrue(vm.state.value.sections.settled)
    assertEquals(PagedListState.LoadedEmpty(), vm.state.value.sections.needsYou)
    assertEquals(listOf("g1", "r1"), vm.state.value.sections.trackedItems.map { it.identity.id })
  }

  @Test
  fun `the popover asks every live section for more but never the recent one`() = runTest(dispatcher) {
    val vm = viewModel()
    boards.onBoard = { Outcome.Value(observedBoard(emptyList())) }
    emit(WorkSection.NEEDS_YOU, live("n1", WorkSection.NEEDS_YOU), nextCursor = "n")
    emit(WorkSection.GOALS, live("g1"), nextCursor = "g")
    emit(WorkSection.PHASES, live("p1", WorkSection.PHASES), nextCursor = "p")
    emit(WorkSection.RECENT, workItem("r1"), nextCursor = "r")
    runCurrent()

    vm.onAction(TrayAction.LoadMoreLive)
    runCurrent()

    assertEquals(setOf(WorkSection.NEEDS_YOU, WorkSection.GOALS, WorkSection.PHASES), boards.readCalls.map { it.section }.toSet())
  }

  @Test
  fun `choosing a tab saves it and a failed save becomes feedback until acknowledged`() = runTest(dispatcher) {
    val vm = viewModel()

    vm.onAction(TrayAction.ChooseTab(PopoverTab.PHASES))
    runCurrent()
    assertEquals("Phases", settings.updates.single().lastPopoverTab)
    assertEquals(PopoverTab.PHASES, vm.state.value.popoverTab)

    settings.onUpdate = { Outcome.Failure(Problem(ProblemCode.SETTINGS_UPDATE_FAILED, "disk full")) }
    vm.onAction(TrayAction.ChooseTab(PopoverTab.GOALS))
    runCurrent()

    val feedback = vm.state.value.pendingFeedback.single()
    assertEquals(FeedbackTone.PROBLEM, feedback.tone)
    assertEquals("disk full", feedback.message)

    vm.onAction(TrayAction.AcknowledgeFeedback(feedback.id))
    runCurrent()
    assertTrue(vm.state.value.pendingFeedback.isEmpty())
  }

  @Test
  fun `pending feedback keeps the newest entries with increasing ids`() = runTest(dispatcher) {
    val vm = viewModel()
    settings.onUpdate = { Outcome.Failure(Problem(ProblemCode.SETTINGS_UPDATE_FAILED, "disk full")) }

    repeat(10) {
      vm.onAction(TrayAction.ChooseTab(if (it % 2 == 0) PopoverTab.PHASES else PopoverTab.GOALS))
      runCurrent()
    }

    assertEquals((3L..10L).toList(), vm.state.value.pendingFeedback.map { it.id })
  }

  @Test
  fun `selecting work sends one navigation effect and keeps only the latest when nobody collects`() = runTest(dispatcher) {
    val vm = viewModel()
    val first = workItem("a").identity
    val second = workItem("b").identity

    vm.onAction(TrayAction.Select(first))
    vm.onAction(TrayAction.Select(second))

    assertEquals(listOf<TrayEffect>(TrayEffect.OpenDetail(second)), vm.effects.take(1).toList())
  }
}
