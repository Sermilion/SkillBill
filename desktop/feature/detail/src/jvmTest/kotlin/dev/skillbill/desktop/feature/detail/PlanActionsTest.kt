package dev.skillbill.desktop.feature.detail

import androidx.compose.ui.focus.FocusRequester
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.domain.model.ControlOutcome
import dev.skillbill.desktop.core.domain.model.ExecutionInstance
import dev.skillbill.desktop.core.domain.model.Lifecycle
import dev.skillbill.desktop.core.domain.model.PlanRunReport
import dev.skillbill.desktop.core.domain.model.PlannedSubtask
import dev.skillbill.desktop.core.domain.model.RunOptions
import dev.skillbill.desktop.core.domain.model.StandalonePlanProgress
import dev.skillbill.desktop.core.domain.model.StandalonePlanStage
import dev.skillbill.desktop.core.domain.model.StandalonePlanUnit
import dev.skillbill.desktop.core.domain.model.IncidentProgress
import dev.skillbill.desktop.core.domain.model.WorkItem
import dev.skillbill.desktop.core.domain.model.WorkKind
import dev.skillbill.desktop.core.domain.repository.WorkControlRepository
import dev.skillbill.desktop.core.testing.FakeWorkControlRepository
import dev.skillbill.desktop.core.testing.workItem
import dev.skillbill.desktop.core.ui.feedback.ToastQueue
import dev.skillbill.desktop.core.ui.input.LocalShellInput
import dev.skillbill.desktop.core.ui.input.ShellInput
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class PlanActionsTest {
  @Test
  fun `goal detail keeps its existing document instead of selecting the plan document`() = runComposeUiTest {
    val item = workItem("goal-1").copy(planningPrepared = false)
    setContent { SkillBillTheme(reducedMotion = true) {
      CompositionLocalProvider(LocalShellInput provides remember { ShellInput() }) {
        DetailScreen(DetailUiState(item, SectionsUi.Loaded(emptyDetail, null, emptyMap(), emptySet()), false, epoch), DetailScreenHost(false, "Ctrl I", {}, {})) {}
      }
    } }

    onNodeWithText("Goal stage").assertExists()
    onNodeWithText("Reading the repo").assertDoesNotExist()
  }

  @Test
  fun `saved plan unit opens its projected spec path`() = runComposeUiTest {
    val report = completedReport()
    val detail = emptyDetail.copy(executionHistory = listOf(planExecution(report)), standalonePlan = completedPlan(report))
    val base = workItem("plan-invocation", kind = WorkKind.Phase)
    val item = base.copy(issueKey = "OAC-86", standaloneExecution = true,
      control = base.control.copy(phaseId = "plan", invocationId = "plan-invocation"))
    val actions = mutableListOf<DetailAction>()
    setContent { SkillBillTheme(reducedMotion = true) {
      CompositionLocalProvider(LocalShellInput provides remember { ShellInput() }) {
        DetailScreen(DetailUiState(item, SectionsUi.Loaded(detail, null, emptyMap(), emptySet()), false, epoch), DetailScreenHost(false, "Ctrl I", {}, {}), actions::add)
      }
    } }

    onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("Detail view"))
    onNodeWithText("Detail view").performClick()

    runOnIdle { assertEquals(listOf<DetailAction>(DetailAction.OpenSpecFile("/specs/OAC-86/spec_subtask_1.md")), actions) }
  }

  @Test
  fun `blocked plan row keeps its reason and routes through the existing incident decision`() = runComposeUiTest {
    val report = completedReport()
    val plan = completedPlan(report).copy(
      blockedSubtaskId = 1,
      blockedReason = "The subtask spec could not be reconciled",
      stalledSince = epoch,
      units = listOf(StandalonePlanUnit(1, "Detail view", "/specs/OAC-86/spec_subtask_1.md", emptyList(), "blocked", epoch, null, "The subtask spec could not be reconciled", null)),
    )
    val detail = emptyDetail.copy(standalonePlan = plan, incident = incident(IncidentProgress.DiagnosisComplete))
    val base = workItem("plan-invocation", kind = WorkKind.Phase)
    val item = base.copy(issueKey = "OAC-86", standaloneExecution = true, awaitsOperatorDecision = true, currentSubtaskId = "1",
      control = base.control.copy(phaseId = "plan", invocationId = "plan-invocation"))
    setContent { SkillBillTheme(reducedMotion = true) {
      CompositionLocalProvider(LocalShellInput provides ShellInput()) {
        DetailScreen(DetailUiState(item, SectionsUi.Loaded(detail, null, emptyMap(), emptySet()), false, epoch), DetailScreenHost(false, "Ctrl I", {}, {})) {}
      }
    } }

    onNodeWithText("Planning stopped at subtask 1", substring = true).assertExists()
    onNodeWithText("Subtask 1 needs your decision").assertExists()
    onNodeWithText("Blocked").assertExists()
    onNodeWithText("Planning stopped at subtask 1. The subtask spec could not be reconciled").assertExists()
    onNodeWithText("No output for ", substring = true).assertDoesNotExist()
    onNodeWithText("Retry").assertDoesNotExist()
    onNodeWithText("Retry subtask 1").assertDoesNotExist()
    onNodeWithText(
      "Subtask 1 finished with no code changes and is waiting for your decision.",
      substring = true,
    ).assertExists()
    onNodeWithText("sona goal operator-decision OAC-86 --subtask 1", substring = true).assertExists()
  }

  @Test
  fun `blocked plan exposes recovery only when the incident offers it`() = runComposeUiTest {
    val report = completedReport()
    val plan = completedPlan(report).copy(
      blockedSubtaskId = 1,
      blockedReason = "Retry after setup",
      units = listOf(StandalonePlanUnit(1, "Detail view", "/specs/OAC-86/spec_subtask_1.md", emptyList(), "blocked", epoch, null, "Retry after setup", null)),
    )
    val detail = emptyDetail.copy(standalonePlan = plan, incident = incident(IncidentProgress.RecoveryAvailable))
    val base = workItem("plan-invocation", kind = WorkKind.Phase)
    val item = base.copy(issueKey = "OAC-86", standaloneExecution = true,
      control = base.control.copy(phaseId = "plan", invocationId = "plan-invocation"))
    val actions = mutableListOf<DetailAction>()
    setContent { SkillBillTheme(reducedMotion = true) {
      CompositionLocalProvider(LocalShellInput provides remember { ShellInput() }) {
        DetailScreen(DetailUiState(item, SectionsUi.Loaded(detail, null, emptyMap(), emptySet()), false, epoch), DetailScreenHost(false, "Ctrl I", {}, {}), actions::add)
      }
    } }

    onNodeWithText("Retry subtask 1").assertIsEnabled().performClick()
    runOnIdle { assertEquals(listOf<DetailAction>(ControlsAction.Recover), actions) }
  }

  @Test
  fun `Implement stays disabled when a seeded spec has not been saved by its plan unit`() {
    val base = workItem("plan-invocation", kind = WorkKind.Phase)
    val item = base.copy(issueKey = "OAC-86", standaloneExecution = true, running = false, lifecycle = Lifecycle.Terminal,
      control = base.control.copy(phaseId = "plan", invocationId = "plan-invocation"))
    val report = completedReport()
    val run = planExecution(report)
    val plan = completedPlan(report).let { it.copy(units = it.units.map { unit -> unit.copy(state = "queued") }) }

    assertFalse(planImplementationEnabled(item, emptyDetail.copy(executionHistory = listOf(run), standalonePlan = plan)))
  }

  @Test
  fun `a goal's own plan step shows its live stages without offering Implement`() = runComposeUiTest {
    val report = completedReport()
    val base = workItem("plan-invocation", kind = WorkKind.Goal)
    val item = base.copy(issueKey = "OAC-86", standaloneExecution = true, running = false, lifecycle = Lifecycle.Terminal,
      finishedAt = epoch, control = base.control.copy(phaseId = "plan", invocationId = "plan-invocation"))
    val plan = completedPlan(report).copy(stages = listOf(StandalonePlanStage("preplan", null, null, null, "completed")))
    val detail = emptyDetail.copy(executionHistory = listOf(planExecution(report)), standalonePlan = plan)
    val state = DetailUiState(item, SectionsUi.Loaded(detail, null, emptyMap(), emptySet()), false, epoch)
    setContent { SkillBillTheme(reducedMotion = true) {
      CompositionLocalProvider(LocalShellInput provides remember { ShellInput() }) {
        DetailScreen(state, DetailScreenHost(false, "Ctrl I", {}, {})) {}
      }
    } }

    onAllNodesWithText("Reading the repo").assertCountEquals(2)
    onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("Detail view"))
    onNodeWithText("Detail view").assertExists()
    onNodeWithText("Implement").assertDoesNotExist()
    onNodeWithText("Implement turns on when every spec is saved").assertDoesNotExist()
    runOnIdle { assertFalse(planImplementationEnabled(item, detail)) }
  }

  @Test
  fun `Implement is visible during planning and enables after every saved unit arrives`() = runComposeUiTest {
    val base = workItem("plan-invocation", kind = WorkKind.Phase)
    val item = base.copy(issueKey = "OAC-86", standaloneExecution = true,
      control = base.control.copy(phaseId = "plan", invocationId = "plan-invocation"))
    val report = completedReport()
    val run = planExecution(report)
    val detail = emptyDetail.copy(executionHistory = listOf(run), standalonePlan = completedPlan(report))
    val current = mutableStateOf(DetailUiState(item, SectionsUi.Loaded(emptyDetail, null, emptyMap(), emptySet()), false, epoch))
    val actions = mutableListOf<DetailAction>()
    setContent {
      SkillBillTheme(reducedMotion = true) {
        val focus = remember { FocusRequester() }
        RunActions(runActionsView(current.value), emptyList(), false, focus, actions::add)
      }
    }
    onNodeWithText("Implement").assertIsNotEnabled()
    runOnIdle {
      current.value = current.value.copy(
        header = item.copy(running = false, lifecycle = Lifecycle.Terminal, finishedAt = epoch),
        sections = SectionsUi.Loaded(detail, null, emptyMap(), emptySet()),
      )
    }
    onNodeWithText("Implement").assertIsEnabled().performClick()
    runOnIdle { assertEquals(listOf<DetailAction>(ControlsAction.ImplementPlan), actions) }
  }

  @Test
  fun `Implement waits for this plan's completed report then starts its goal once`() = runTest {
    val base = workItem("plan-invocation", kind = WorkKind.Phase)
    val plan = base.copy(issueKey = "OAC-86", standaloneExecution = true,
      control = base.control.copy(phaseId = "plan", invocationId = "plan-invocation"))
    val report = completedReport()
    val run = planExecution(report)
    val detail = emptyDetail.copy(executionHistory = listOf(run), standalonePlan = completedPlan(report))
    var current = DetailUiState(plan, SectionsUi.Loaded(detail, null, emptyMap(), emptySet()), false, epoch)
    val requests = mutableListOf<Pair<String, String>>()
    val finish = CompletableDeferred<Unit>()
    val repository = object : WorkControlRepository by FakeWorkControlRepository() {
      override suspend fun startGoal(repoRoot: String, issueKey: String, agentId: String?): ControlOutcome {
        requests += repoRoot to issueKey
        finish.await()
        return ControlOutcome.RequestAccepted
      }
    }
    val controls = DetailControls(backgroundScope, repository, ToastQueue(), {}, {}) { current }
    assertFalse(controls.onAction(ControlsAction.ImplementPlan))
    current = current.copy(header = plan.copy(running = false, lifecycle = Lifecycle.Terminal, finishedAt = epoch))
    assertTrue(controls.onAction(ControlsAction.ImplementPlan))
    assertFalse(controls.onAction(ControlsAction.ImplementPlan))
    runCurrent()
    assertEquals(listOf(plan.repositoryRoot to "OAC-86"), requests)
    finish.complete(Unit)
    runCurrent()
    assertFalse(runActionsView(current.copy(controls = controls.state.value)).implement!!.enabled)
    assertFalse(controls.onAction(ControlsAction.ImplementPlan))
    assertEquals(1, requests.size)
    assertFalse(planImplementationEnabled(current.header, detail.copy(executionHistory = emptyList())))
  }

  @Test
  fun `Implement can retry when another launch reservation clears`() = runTest {
    val base = workItem("plan-invocation", kind = WorkKind.Phase)
    val plan = base.copy(issueKey = "OAC-86", standaloneExecution = true, running = false,
      lifecycle = Lifecycle.Terminal, finishedAt = epoch,
      control = base.control.copy(phaseId = "plan", invocationId = "plan-invocation"))
    val report = completedReport()
    val run = ExecutionInstance("plan-invocation", "execution", "terminal", epoch, epoch, epoch,
      null, null, null, null, report)
    val current = DetailUiState(plan, SectionsUi.Loaded(emptyDetail.copy(executionHistory = listOf(run), standalonePlan = completedPlan(report)), null, emptyMap(), emptySet()), false, epoch)
    val fake = FakeWorkControlRepository()
    var requests = 0
    val repository = object : WorkControlRepository by fake {
      override suspend fun startGoal(repoRoot: String, issueKey: String, agentId: String?): ControlOutcome {
        requests++
        fake.reservations.value = setOf(issueKey)
        return ControlOutcome.AlreadyLaunching
      }
    }
    val controls = DetailControls(backgroundScope, repository, ToastQueue(), {}, {}) { current }
    runCurrent()
    assertTrue(controls.onAction(ControlsAction.ImplementPlan))
    runCurrent()
    assertFalse(runActionsView(current.copy(controls = controls.state.value)).implement!!.enabled)
    fake.reservations.value = emptySet()
    runCurrent()
    assertTrue(controls.onAction(ControlsAction.ImplementPlan))
    runCurrent()
    assertEquals(2, requests)
  }

  @Test
  fun `Implement disables and explains itself once a goal started for the plan`() = runComposeUiTest {
    val state = finishedPlanState(planOptions(goalStarted = true))
    setContent {
      SkillBillTheme(reducedMotion = true) {
        RunActions(runActionsView(state), listOf("claude", "codex"), false, remember { FocusRequester() }) {}
      }
    }

    onNodeWithText("Implement").assertIsNotEnabled()
    onNodeWithText("Agent: Claude").assertIsNotEnabled()
    onNodeWithText("$GOAL_STARTED. Open the goal to follow or resume it.").assertExists()
    onNodeWithText("Implement turns on when every spec is saved").assertDoesNotExist()
  }

  @Test
  fun `a run without a recorded agent offers the default agent when the run allows it`() {
    val unrecorded = RunOptions(false, false, "Run again", null, listOf("claude", "codex"), null, false)
    val state = finishedPlanState(unrecorded)

    assertEquals("Choose agent", runActionsView(state).agent?.label)
    assertEquals("Agent: Codex", runActionsView(state.copy(defaultAgentId = "codex")).agent?.label)
    assertEquals("Choose agent", runActionsView(state.copy(defaultAgentId = "retired-agent")).agent?.label)
    assertEquals("Agent: Claude", runActionsView(finishedPlanState(planOptions(goalStarted = false)).copy(defaultAgentId = "codex")).agent?.label)
  }

  @Test
  fun `Resume requires a supported choice even when Implement has a configured default`() = runTest {
    val options = RunOptions(true, true, "Resume", "retired-agent", listOf("claude", "codex"), null)
    val finished = finishedPlanState(options)
    var current = finished.copy(
      header = finished.header.copy(lifecycle = Lifecycle.Paused, agentId = "retired-agent"),
      defaultAgentId = "claude",
    )
    val requestedAgents = mutableListOf<String?>()
    val repository = object : WorkControlRepository by FakeWorkControlRepository() {
      override suspend fun launch(item: WorkItem, agentId: String?): ControlOutcome {
        requestedAgents += agentId
        return ControlOutcome.RequestAccepted
      }
    }
    val controls = DetailControls(backgroundScope, repository, ToastQueue(), {}, {}) { current }
    val view = runActionsView(current)

    assertTrue(view.launch!!.enabled)
    assertEquals(null, view.resumeAgentId)
    assertEquals("claude", view.implementationAgentId)
    assertEquals("Choose agent", view.agent?.label)
    assertTrue(controls.onAction(ControlsAction.Launch))
    assertTrue(controls.state.value.agentMenuOpen)
    runCurrent()
    assertEquals(emptyList(), requestedAgents)

    assertTrue(controls.onAction(ControlsAction.ChooseAgent("codex")))
    current = current.copy(controls = controls.state.value)
    assertEquals("Agent: Codex", runActionsView(current).agent?.label)
    assertTrue(controls.onAction(ControlsAction.Launch))
    runCurrent()
    assertEquals(listOf<String?>("codex"), requestedAgents)
  }

  @Test
  fun `Implement uses its supported configured default without a historical header fallback`() = runTest {
    val options = RunOptions(false, false, "Run again", null, listOf("claude", "codex"), null, false)
    val plan = finishedPlanState(options)
    val finished = plan.copy(
      header = plan.header.copy(agentId = "retired-agent"),
      defaultAgentId = "claude",
    )
    val agents = mutableListOf<String?>()
    val repository = object : WorkControlRepository by FakeWorkControlRepository() {
      override suspend fun startGoal(repoRoot: String, issueKey: String, agentId: String?): ControlOutcome {
        agents += agentId
        return ControlOutcome.RequestAccepted
      }
    }
    val controls = DetailControls(backgroundScope, repository, ToastQueue(), {}, {}) { finished }

    assertEquals("claude", runActionsView(finished).implementationAgentId)
    assertTrue(controls.onAction(ControlsAction.ImplementPlan))
    runCurrent()
    assertEquals(listOf<String?>("claude"), agents)
  }

  @Test
  fun `Implement starts the goal with the agent picked next to it`() = runTest {
    var current = finishedPlanState(planOptions(goalStarted = false))
    val agents = mutableListOf<String?>()
    val repository = object : WorkControlRepository by FakeWorkControlRepository() {
      override suspend fun startGoal(repoRoot: String, issueKey: String, agentId: String?): ControlOutcome {
        agents += agentId
        return ControlOutcome.RequestAccepted
      }
    }
    val controls = DetailControls(backgroundScope, repository, ToastQueue(), {}, {}) { current }
    val view = runActionsView(current)
    assertEquals(ControlButton("Agent: Claude", true), view.agent)
    assertTrue(view.implement!!.enabled)

    assertTrue(controls.onAction(ControlsAction.OpenAgentMenu))
    assertTrue(controls.state.value.agentMenuOpen)
    assertTrue(controls.onAction(ControlsAction.ChooseAgent("codex")))
    current = current.copy(controls = controls.state.value)
    assertEquals("Agent: Codex", runActionsView(current).agent?.label)
    assertTrue(controls.onAction(ControlsAction.ImplementPlan))
    runCurrent()

    assertEquals(listOf<String?>("codex"), agents)
  }

  @Test
  fun `a plan with units and no report opens the derived manifest path`() = runComposeUiTest {
    val plan = StandalonePlanProgress(
      null, emptyList(), 0, 1, 1, listOf(1), 5, null, null,
      listOf(StandalonePlanUnit(1, "Detail view", "/specs/X/spec_subtask_1.md", emptyList(), "writing", epoch, null, null, null)),
      null,
    )
    val detail = emptyDetail.copy(standalonePlan = plan)
    val base = workItem("plan-invocation", kind = WorkKind.Phase)
    val item = base.copy(
      issueKey = "X",
      standaloneExecution = true,
      running = true,
      control = base.control.copy(phaseId = "plan", invocationId = "plan-invocation"),
    )
    val actions = mutableListOf<DetailAction>()
    setContent { SkillBillTheme(reducedMotion = true) {
      CompositionLocalProvider(LocalShellInput provides remember { ShellInput() }) {
        DetailScreen(DetailUiState(item, SectionsUi.Loaded(detail, null, emptyMap(), emptySet()), false, epoch), DetailScreenHost(false, "Ctrl I", {}, {}), actions::add)
      }
    } }

    onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("decomposition-manifest.yaml"))
    onNodeWithText("not yet").assertDoesNotExist()
    onNodeWithText("decomposition-manifest.yaml").performClick()
    runOnIdle { assertEquals(listOf<DetailAction>(DetailAction.OpenSpecFile("/specs/X/decomposition-manifest.yaml")), actions) }
  }

}

private fun completedReport() = PlanRunReport(
  "OAC-86", "/specs/OAC-86/spec.md", listOf(PlannedSubtask(1, "Detail view", "/specs/OAC-86/spec_subtask_1.md", emptyList())),
  listOf("/specs/OAC-86/decomposition-manifest.yaml", "/specs/OAC-86/spec.md", "/specs/OAC-86/spec_subtask_1.md"),
)

private fun planExecution(report: PlanRunReport) = ExecutionInstance("plan-invocation", "execution", "terminal", epoch, epoch, epoch,
  null, null, null, null, report)

private fun completedPlan(report: PlanRunReport) = StandalonePlanProgress(
  report, emptyList(), 1, 1, null, emptyList(), 3, null, null, listOf(
    StandalonePlanUnit(1, "Detail view", "/specs/OAC-86/spec_subtask_1.md", emptyList(), "saved", epoch, epoch, null, null),
  ), null,
)

private fun planOptions(goalStarted: Boolean) =
  RunOptions(false, false, "Run again", "claude", listOf("claude", "codex"), null, goalStarted)

private fun finishedPlanState(options: RunOptions): DetailUiState {
  val base = workItem("plan-invocation", kind = WorkKind.Phase)
  val item = base.copy(issueKey = "OAC-86", standaloneExecution = true, running = false, lifecycle = Lifecycle.Terminal,
    finishedAt = epoch, control = base.control.copy(phaseId = "plan", invocationId = "plan-invocation"))
  val report = completedReport()
  val detail = emptyDetail.copy(executionHistory = listOf(planExecution(report)), standalonePlan = completedPlan(report))
  return DetailUiState(item, SectionsUi.Loaded(detail, options, emptyMap(), emptySet()), false, epoch)
}
