package dev.skillbill.tray.ui

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.skillbill.tray.core.domain.model.AppSettings
import dev.skillbill.tray.core.domain.model.Lifecycle
import dev.skillbill.tray.core.domain.model.Problem
import dev.skillbill.tray.core.domain.model.ProblemCode
import dev.skillbill.tray.core.domain.model.RunOptions
import dev.skillbill.tray.core.domain.model.SettingsState
import dev.skillbill.tray.core.domain.model.SpecBundleControl
import dev.skillbill.tray.core.domain.model.SpecCounts
import dev.skillbill.tray.core.domain.model.SpecGroup
import dev.skillbill.tray.core.domain.model.SpecNode
import dev.skillbill.tray.core.domain.model.SpecProject
import dev.skillbill.tray.core.domain.model.SpecRunState
import dev.skillbill.tray.core.domain.model.SpecSnapshot
import dev.skillbill.tray.core.domain.model.SpecStatus
import dev.skillbill.tray.core.domain.model.WorkSection
import dev.skillbill.tray.core.domain.repository.Outcome
import dev.skillbill.tray.core.testing.FakeSettingsRepository
import dev.skillbill.tray.core.testing.FakeSpecBrowserRepository
import dev.skillbill.tray.core.testing.FakeWorkBoardRepository
import dev.skillbill.tray.core.testing.FakeWorkControlRepository
import dev.skillbill.tray.core.testing.FakeWorkDetailRepository
import dev.skillbill.tray.core.testing.TestClock
import dev.skillbill.tray.core.testing.board
import dev.skillbill.tray.core.testing.detail
import dev.skillbill.tray.core.testing.fixtureInstant
import dev.skillbill.tray.core.testing.workItem
import dev.skillbill.tray.core.ui.LaunchRequests
import dev.skillbill.tray.feature.detail.DetailAction
import dev.skillbill.tray.feature.detail.DetailViewModel
import dev.skillbill.tray.feature.specs.SpecsAction
import dev.skillbill.tray.feature.specs.SpecsViewModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class SharedLaunchControlsTest {
  @Test
  fun `resuming from either pane disables both until registration and prevents duplicate launches`() {
    for (fromSpecs in listOf(true, false)) for (refused in listOf(true, false)) scenario(fromSpecs, refused, Disposal.NEVER)
  }

  @Test
  fun `disposing either launching entry before or after acceptance keeps the other pane locked until registration`() {
    for (fromSpecs in listOf(true, false)) for (disposal in listOf(Disposal.BEFORE_RESULT, Disposal.AFTER_ACCEPTANCE)) {
      scenario(fromSpecs, false, disposal)
    }
  }

  private enum class Disposal { NEVER, BEFORE_RESULT, AFTER_ACCEPTANCE }

  private fun scenario(fromSpecs: Boolean, refused: Boolean, disposal: Disposal) = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val mainStore = ViewModelStore()
    val paneStore = ViewModelStore()
    try {
      val specs = FakeSpecBrowserRepository()
      val details = FakeWorkDetailRepository()
      val controls = FakeWorkControlRepository()
      val boards = FakeWorkBoardRepository()
      val launches = LaunchRequests(backgroundScope, controls, boards, TestClock(fixtureInstant))
      val item = workItem("goal", Lifecycle.BLOCKED)
      val stopped = detail(item).copy(runOptions = RunOptions(true, true, "Resume", "codex", listOf("codex"), null))
      val project = SpecProject("project", "Project", "/specs", "/repo")
      val bundle = SpecNode.Bundle("goal", "goal", "/specs/goal", "SKILL-9", "Goal", SpecStatus.BLOCKED, SpecGroup.ACTIVE, 0, 1, emptyList())
      var registered = false
      specs.onProjects = { Outcome.Value(listOf(project)) }
      specs.onControl = { _, _, _ ->
        Outcome.Value(SpecBundleControl("SKILL-9", SpecRunState.BLOCKED, true, item.identity, !registered, "Resume", false, false, true, true, true, "codex", listOf("codex"), null))
      }
      details.onDetail = { Outcome.Value(stopped) }
      val response = CompletableDeferred<Outcome<Unit>>()
      controls.onLaunch = { _, _ -> response.await() }
      val main = DetailViewModel(item.identity, details, controls, boards, TestClock(fixtureInstant), launches)
      val pane = SpecsViewModel(specs, details, controls, FakeSettingsRepository(SettingsState.Ready(AppSettings())), launches)
      val mainOwner = object : ViewModelStoreOwner { override val viewModelStore = mainStore }
      val paneOwner = object : ViewModelStoreOwner { override val viewModelStore = paneStore }
      ViewModelProvider.create(mainOwner, viewModelFactory { initializer { main } })[DetailViewModel::class]
      ViewModelProvider.create(paneOwner, viewModelFactory { initializer { pane } })[SpecsViewModel::class]
      runCurrent()
      details.emit(Outcome.Value(stopped))
      specs.emitSnapshot(Outcome.Value(SpecSnapshot(project, listOf(bundle), SpecCounts(1, 1, 0, 0), false, null)))
      WorkSection.entries.forEach { boards.emit(it, Outcome.Value(board(emptyList()))) }
      runCurrent()
      pane.onAction(SpecsAction.Select(bundle.path))
      runCurrent()

      if (fromSpecs) pane.onAction(SpecsAction.Start) else main.onAction(DetailAction.Launch("codex"))
      runCurrent()
      WorkSection.entries.forEach { boards.emit(it, Outcome.Value(board(emptyList()))) }
      runCurrent()
      assertTrue(main.state.value.launchPending)
      assertTrue(pane.state.value.toolbar.inFlight)
      if (fromSpecs) main.onAction(DetailAction.Launch("codex")) else pane.onAction(SpecsAction.Start)
      runCurrent()
      assertEquals(1, controls.calls.count { it.startsWith("launch:") })

      fun disposeOrigin() {
        if (fromSpecs) paneStore.clear() else mainStore.clear()
      }
      if (disposal == Disposal.BEFORE_RESULT) {
        disposeOrigin()
        runCurrent()
        assertTrue(item.identity in launches.pending.value)
      }
      response.complete(if (refused) Outcome.Failure(Problem(ProblemCode.UNKNOWN, "Launch refused")) else Outcome.Value(Unit))
      runCurrent()
      if (!refused) {
        assertTrue(item.identity in launches.pending.value, "Acceptance must keep Resume disabled until the runtime registers the run")
        if (disposal == Disposal.AFTER_ACCEPTANCE) disposeOrigin()
        runCurrent()
        if (disposal != Disposal.NEVER) {
          if (fromSpecs) {
            assertTrue(main.state.value.launchPending)
            main.onAction(DetailAction.Launch("codex"))
          } else {
            assertTrue(pane.state.value.toolbar.inFlight)
            pane.onAction(SpecsAction.Start)
          }
          runCurrent()
          assertEquals(1, controls.calls.count { it.startsWith("launch:") }, "Leaving the launch entry must not admit a second launch")
        }
        registered = true
        val running = stopped.copy(item = item.copy(lifecycle = Lifecycle.ACTIVE, liveSection = WorkSection.GOALS, running = true, updatedAt = item.updatedAt + 1.seconds), runOptions = stopped.runOptions?.copy(canLaunch = false))
        details.emit(Outcome.Value(running))
        WorkSection.entries.forEach { section -> boards.emit(section, Outcome.Value(board(if (section == WorkSection.GOALS) listOf(running.item) else emptyList()))) }
        runCurrent()
      }
      assertFalse(item.identity in launches.pending.value)
      if (disposal == Disposal.NEVER || fromSpecs) assertFalse(main.state.value.launchPending)
      if (disposal == Disposal.NEVER || !fromSpecs) assertFalse(pane.state.value.toolbar.inFlight)
      if (refused) {
        if (fromSpecs) main.onAction(DetailAction.Launch("codex")) else pane.onAction(SpecsAction.Start)
        runCurrent()
        WorkSection.entries.forEach { boards.emit(it, Outcome.Value(board(emptyList()))) }
        runCurrent()
        assertEquals(2, controls.calls.count { it.startsWith("launch:") })
      }
    } finally {
      mainStore.clear()
      paneStore.clear()
      Dispatchers.resetMain()
    }
  }
}
