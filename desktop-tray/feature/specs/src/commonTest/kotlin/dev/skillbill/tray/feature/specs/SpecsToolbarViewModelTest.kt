package dev.skillbill.tray.feature.specs

import dev.skillbill.tray.core.domain.model.AppSettings
import dev.skillbill.tray.core.domain.model.Problem
import dev.skillbill.tray.core.domain.model.ProblemCode
import dev.skillbill.tray.core.domain.model.SettingsState
import dev.skillbill.tray.core.domain.model.SpecBundleControl
import dev.skillbill.tray.core.domain.model.SpecCounts
import dev.skillbill.tray.core.domain.model.SpecGroup
import dev.skillbill.tray.core.domain.model.SpecNode
import dev.skillbill.tray.core.domain.model.SpecProject
import dev.skillbill.tray.core.domain.model.SpecRunState
import dev.skillbill.tray.core.domain.model.SpecSnapshot
import dev.skillbill.tray.core.domain.model.SpecStatus
import dev.skillbill.tray.core.domain.model.WorkIdentity
import dev.skillbill.tray.core.domain.repository.Outcome
import dev.skillbill.tray.core.testing.FakeSettingsRepository
import dev.skillbill.tray.core.testing.FakeSpecBrowserRepository
import dev.skillbill.tray.core.testing.FakeWorkControlRepository
import dev.skillbill.tray.core.testing.FakeWorkDetailRepository
import dev.skillbill.tray.core.testing.detail
import dev.skillbill.tray.core.testing.workItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SpecsToolbarViewModelTest {
  private val dispatcher = StandardTestDispatcher()
  private val specs = FakeSpecBrowserRepository()
  private val details = FakeWorkDetailRepository()
  private val controls = FakeWorkControlRepository()
  private val settings = FakeSettingsRepository(SettingsState.Ready(AppSettings()))

  private val project = SpecProject("p1", "One", "/specs/p1", "/repo")
  private val bundle = SpecNode.Bundle(
    "SKILL-9-x", "pending/SKILL-9-x", "/specs/p1/pending/SKILL-9-x", "SKILL-9", "Title", SpecStatus.PENDING, SpecGroup.PENDING, 0, 1, emptyList(),
  )
  private val folder = SpecNode.Folder("pending", "pending", "/specs/p1/pending", listOf(bundle), 1, mapOf(SpecStatus.PENDING to 1))
  private var control = control(SpecRunState.NOT_STARTED, canStart = true)

  @BeforeTest
  fun setUp() {
    Dispatchers.setMain(dispatcher)
    specs.onProjects = { Outcome.Value(listOf(project)) }
    specs.onControl = { _, _, _ -> Outcome.Value(control) }
  }

  @AfterTest
  fun tearDown() {
    Dispatchers.resetMain()
  }

  private fun control(
    runState: SpecRunState,
    canStart: Boolean = false,
    agentId: String? = null,
    work: WorkIdentity? = null,
    purgeVisible: Boolean = false,
    canPurge: Boolean = false,
  ) = SpecBundleControl(
    issueKey = "SKILL-9",
    runState = runState,
    everStarted = purgeVisible,
    work = work,
    canStart = canStart,
    startLabel = "Start",
    canPause = false,
    pauseRequested = false,
    canCancel = false,
    purgeVisible = purgeVisible,
    canPurge = canPurge,
    agentId = agentId,
    agentChoices = listOf("claude", "codex"),
    unavailableReason = null,
  )

  /** A view model with the bundle selected and its control loaded. */
  private fun TestScope.selectedBundle(): SpecsViewModel {
    val vm = SpecsViewModel(specs, details, controls, settings)
    runCurrent()
    specs.emitSnapshot(Outcome.Value(SpecSnapshot(project, listOf(folder), SpecCounts(1, 0, 1, 0), anyInProgress = false, failure = null)))
    runCurrent()
    vm.onAction(SpecsAction.Select(bundle.path))
    runCurrent()
    return vm
  }

  private fun controlReads() = specs.calls.count { it.startsWith("control:") }

  @Test
  fun `a never-started bundle starts through the spec port and a refusal is shown inline`() = runTest(dispatcher) {
    var started: Triple<String, String, String?>? = null
    specs.onStartGoal = { repoRoot, key, agent ->
      started = Triple(repoRoot, key, agent)
      Outcome.Value(Unit)
    }
    val vm = selectedBundle()

    vm.onAction(SpecsAction.ChooseAgent("codex"))
    vm.onAction(SpecsAction.Start)
    runCurrent()

    assertEquals(Triple("/repo", "SKILL-9", "codex"), started)
    assertTrue(controls.calls.isEmpty(), "A never-launched bundle has no work item to launch.")

    specs.onStartGoal = { _, _, _ -> Outcome.Failure(Problem(ProblemCode.UNKNOWN, "already running")) }
    vm.onAction(SpecsAction.Start)
    runCurrent()

    assertEquals("Couldn't start: already running", vm.state.value.toolbar.message)
  }

  @Test
  fun `resuming a paused bundle launches its work item and never starts a second goal`() = runTest(dispatcher) {
    control = control(SpecRunState.PAUSED, canStart = true, agentId = "codex", work = WorkIdentity("wf-1", "repo-id"))
    details.onDetail = { Outcome.Value(detail(workItem("wf-1"))) }
    val vm = selectedBundle()

    vm.onAction(SpecsAction.Start)
    runCurrent()

    assertEquals(listOf("launch:wf-1:codex"), controls.calls)
    assertTrue(specs.calls.none { it.startsWith("startGoal") })
  }

  @Test
  fun `a failed purge is reported and a successful one reads the control again`() = runTest(dispatcher) {
    control = control(SpecRunState.CANCELED, purgeVisible = true, canPurge = true)
    specs.onPurgeGoal = { _, _ -> Outcome.Failure(Problem(ProblemCode.UNKNOWN, "child worker still live")) }
    val vm = selectedBundle()

    vm.onAction(SpecsAction.RequestPurge)
    vm.onAction(SpecsAction.ConfirmPurge)
    runCurrent()

    assertEquals("Couldn't purge: child worker still live", vm.state.value.toolbar.message)
    assertTrue(!vm.state.value.toolbar.purgeDialogOpen)

    val readsBefore = controlReads()
    control = control(SpecRunState.NOT_STARTED, canStart = true)
    specs.onPurgeGoal = { _, _ -> Outcome.Value(Unit) }
    vm.onAction(SpecsAction.RequestPurge)
    vm.onAction(SpecsAction.ConfirmPurge)
    runCurrent()

    assertTrue(controlReads() > readsBefore)
    assertNull(vm.state.value.toolbar.message)
    assertEquals(false, (vm.state.value.toolbar.control as ControlLoad.Ready).control.purgeVisible)
  }
}
