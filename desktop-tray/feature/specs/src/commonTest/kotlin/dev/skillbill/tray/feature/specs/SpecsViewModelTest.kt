package dev.skillbill.tray.feature.specs

import dev.skillbill.tray.core.domain.model.AppSettings
import dev.skillbill.tray.core.domain.model.SettingsState
import dev.skillbill.tray.core.domain.model.SpecCounts
import dev.skillbill.tray.core.domain.model.SpecFileKind
import dev.skillbill.tray.core.domain.model.SpecGroup
import dev.skillbill.tray.core.domain.model.SpecNode
import dev.skillbill.tray.core.domain.model.SpecProject
import dev.skillbill.tray.core.domain.model.SpecSnapshot
import dev.skillbill.tray.core.domain.model.SpecStatus
import dev.skillbill.tray.core.domain.repository.Outcome
import dev.skillbill.tray.core.testing.FakeSettingsRepository
import dev.skillbill.tray.core.testing.FakeSpecBrowserRepository
import dev.skillbill.tray.core.testing.FakeWorkControlRepository
import dev.skillbill.tray.core.testing.FakeWorkDetailRepository
import dev.skillbill.tray.core.testing.detail
import dev.skillbill.tray.core.testing.workItem
import dev.skillbill.tray.core.testing.FakeWorkBoardRepository
import dev.skillbill.tray.core.testing.TestClock
import dev.skillbill.tray.core.testing.fixtureInstant
import dev.skillbill.tray.core.ui.LaunchRequests
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class SpecsViewModelTest {
  private val dispatcher = StandardTestDispatcher()
  private val specs = FakeSpecBrowserRepository()
  private val details = FakeWorkDetailRepository()
  private val settings = FakeSettingsRepository(SettingsState.Ready(AppSettings()))

  private val p1 = SpecProject("p1", "One", "/specs/p1", "/repo/a")
  private val p2 = SpecProject("p2", "Two", "/specs/p2", "/repo/b")

  private val subtask = SpecNode.File(
    "spec_subtask_1_foo.md", "done/SKILL-1-x/spec_subtask_1_foo.md", "/specs/p1/done/SKILL-1-x/spec_subtask_1_foo.md",
    SpecFileKind.SUBTASK_SPEC, 1, "foo", SpecStatus.COMPLETE,
  )
  private val bundle = SpecNode.Bundle(
    "SKILL-1-x", "done/SKILL-1-x", "/specs/p1/done/SKILL-1-x", "SKILL-1", "Plain title", SpecStatus.COMPLETE, SpecGroup.DONE, 1, 1, listOf(subtask),
  )
  private val folder = SpecNode.Folder("done", "done", "/specs/p1/done", listOf(bundle), 1, mapOf(SpecStatus.COMPLETE to 1))

  @BeforeTest
  fun setUp() {
    Dispatchers.setMain(dispatcher)
    specs.onProjects = { Outcome.Value(listOf(p1, p2)) }
    specs.onProjectFor = { Outcome.Value(p1) }
    details.onDetail = { Outcome.Value(detail(workItem("a"))) }
  }

  @AfterTest
  fun tearDown() {
    Dispatchers.resetMain()
  }

  private fun TestScope.viewModel(): SpecsViewModel = SpecsViewModel(specs, details, FakeWorkControlRepository(), settings, LaunchRequests(backgroundScope, FakeWorkControlRepository(), FakeWorkBoardRepository(), TestClock(fixtureInstant))).also { runCurrent() }

  @Test
  fun `a filter match inside a collapsed bundle is shown without touching the saved expansion`() = runTest(dispatcher) {
    val vm = viewModel()
    specs.emitSnapshot(Outcome.Value(SpecSnapshot(p1, listOf(folder), SpecCounts(1, 0, 0, 1), anyInProgress = false, failure = null)))
    runCurrent()
    assertEquals(listOf("done"), vm.state.value.rows.map { it.node.path })

    vm.onAction(SpecsAction.FilterChanged("foo"))
    runCurrent()
    assertEquals(listOf("done", "done/SKILL-1-x", "done/SKILL-1-x/spec_subtask_1_foo.md"), vm.state.value.rows.map { it.node.path })

    vm.onAction(SpecsAction.ClearFilters)
    runCurrent()
    assertEquals(listOf("done"), vm.state.value.rows.map { it.node.path })
    assertTrue(settings.updates.isEmpty(), "Filtering must not write the saved expansion.")
  }

  @Test
  fun `a row click selects a folder without expanding it and its arrow expands it without selecting`() = runTest(dispatcher) {
    val vm = viewModel()
    specs.emitSnapshot(Outcome.Value(SpecSnapshot(p1, listOf(folder), SpecCounts(1, 0, 0, 1), anyInProgress = false, failure = null)))
    runCurrent()

    vm.onAction(SpecsAction.Click("done"))
    runCurrent()
    assertEquals("done", vm.state.value.selectedPath)
    assertEquals(listOf("done"), vm.state.value.rows.map { it.node.path })

    vm.onAction(SpecsAction.Toggle("done/SKILL-1-x"))
    vm.onAction(SpecsAction.Toggle("done"))
    runCurrent()
    assertEquals("done", vm.state.value.selectedPath)
    assertEquals(listOf("done", "done/SKILL-1-x", "done/SKILL-1-x/spec_subtask_1_foo.md"), vm.state.value.rows.map { it.node.path })
  }

  @Test
  fun `a picked project survives a later sidebar selection and nothing navigates`() = runTest(dispatcher) {
    val vm = viewModel()
    val effects = mutableListOf<SpecsEffect>()
    backgroundScope.launch { vm.effects.toList(effects) }

    vm.onAction(SpecsAction.PickProject("p2"))
    vm.onAction(SpecsAction.SelectedWorkChanged(workItem("a").identity))
    runCurrent()

    assertEquals("p2", vm.state.value.project?.projectId)
    assertTrue(specs.calls.none { it.startsWith("projectFor:") }, "A picked project must not be re-resolved from the selection.")
    assertTrue(effects.isEmpty())
  }

  @Test
  fun `every row pick is announced with the checkout the pane follows, a re-pick of the selected row included`() = runTest(dispatcher) {
    val worktree = workItem("wt")
    details.onDetail = { Outcome.Value(detail(worktree)) }
    val vm = viewModel()
    vm.onAction(SpecsAction.Select("done"))
    runCurrent()
    val picks = mutableListOf<SpecPick>()
    backgroundScope.launch { vm.picks.toList(picks) }
    vm.onAction(SpecsAction.SelectedWorkChanged(worktree.identity))
    specs.emitSnapshot(Outcome.Value(SpecSnapshot(p1, listOf(folder), SpecCounts(1, 0, 0, 1), anyInProgress = false, failure = null)))
    runCurrent()

    vm.onAction(SpecsAction.Click("done"))
    runCurrent()
    vm.onAction(SpecsAction.Click("done"))
    runCurrent()

    assertEquals(listOf(SpecPick("done", "/repo/wt"), SpecPick("done", "/repo/wt")), picks, "A pick made before collecting is never replayed.")
  }
}
