package dev.skillbill.desktop.feature.specs

import dev.skillbill.desktop.core.common.AppDispatchers
import dev.skillbill.desktop.core.common.Outcome
import dev.skillbill.desktop.core.domain.model.OpenOutcome
import dev.skillbill.desktop.core.domain.model.SpecBundleControlView
import dev.skillbill.desktop.core.domain.model.SpecBundleRef
import dev.skillbill.desktop.core.domain.model.SpecControlState
import dev.skillbill.desktop.core.domain.model.SpecCounts
import dev.skillbill.desktop.core.domain.model.SpecFileKind
import dev.skillbill.desktop.core.domain.model.SpecGroup
import dev.skillbill.desktop.core.domain.model.SpecNode
import dev.skillbill.desktop.core.domain.model.SpecProject
import dev.skillbill.desktop.core.domain.model.SpecRunState
import dev.skillbill.desktop.core.domain.model.SpecStatus
import dev.skillbill.desktop.core.domain.model.SpecTree
import dev.skillbill.desktop.core.domain.model.SpecTreeState
import dev.skillbill.desktop.core.domain.repository.SpecRepository
import dev.skillbill.desktop.core.testing.FakeWorkControlRepository
import dev.skillbill.desktop.core.testing.workItem
import dev.skillbill.desktop.core.ui.feedback.ToastQueue
import dev.skillbill.desktop.core.ui.selection.SelectionHolder
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SpecsPaneModelTest {
  @Test
  fun `newly composed unfocused row cannot clear another row's tree focus`() {
    assertFalse(shouldClearSpecsTreeFocus("bundle/subtask-1.md", "bundle/subtask-2.md", hasFocus = false))
    assertTrue(shouldClearSpecsTreeFocus("bundle/subtask-2.md", "bundle/subtask-2.md", hasFocus = false))
    assertFalse(shouldClearSpecsTreeFocus("bundle/subtask-2.md", "bundle/subtask-2.md", hasFocus = true))
  }

  @Test
  fun `picked specs project sends its checkout to the terminal pane`() {
    val selections = mutableListOf<Path?>()

    notifySpecsProjectSelection(listOf(project), project.projectId) { selections += it }
    notifySpecsProjectSelection(listOf(project.copy(repoRoot = null)), project.projectId) { selections += it }
    notifySpecsProjectSelection(listOf(project), "other-project") { selections += it }

    assertEquals(listOf(Path.of("/repo"), null), selections)
  }

  private val project = SpecProject("p1", "Project", "/repo/.skill-bill/specs", "/repo")
  private val goal = workItem("SKILL-1")
  private val specs = FakeSpecs()
  private val controls = FakeWorkControlRepository()
  private val models = mutableListOf<SpecsPaneModel>()

  @AfterTest
  fun tearDown() {
    models.forEach(SpecsPaneModel::close)
    Dispatchers.resetMain()
  }

  @Test
  fun `a file inside a bundle cannot start or control its parent`() = runTest {
    val dispatcher = StandardTestDispatcher(testScheduler)
    Dispatchers.setMain(dispatcher)
    val model = model(dispatcher)
    model.open()
    advanceUntilIdle()

    model.onAction(SpecsAction.Select("SKILL-1/spec.md"))
    advanceUntilIdle()
    assertEquals(ToolbarSelection.FileInBundle("SKILL-1"), model.state.value.toolbar.selection)
    model.onAction(SpecsAction.Start)
    model.onAction(SpecsAction.Pause)
    model.onAction(SpecsAction.RequestCancel)
    advanceUntilIdle()

    assertEquals(0, specs.controlReads)
    assertEquals(emptyList(), controls.calls)
  }

  @Test
  fun `cancel reaches the repository only after confirmation`() = runTest {
    val dispatcher = StandardTestDispatcher(testScheduler)
    Dispatchers.setMain(dispatcher)
    specs.controlState.value = SpecControlState.Loaded(control(canCancel = true), goal)
    val model = model(dispatcher)
    model.open()
    advanceUntilIdle()
    model.onAction(SpecsAction.Select("SKILL-1"))
    advanceUntilIdle()

    model.onAction(SpecsAction.RequestCancel)
    model.onAction(SpecsAction.KeepRunning)
    advanceUntilIdle()
    assertEquals(emptyList(), controls.calls)

    model.onAction(SpecsAction.RequestCancel)
    model.onAction(SpecsAction.ConfirmCancel)
    advanceUntilIdle()
    assertEquals(listOf("cancel"), controls.calls.map { it.control })
  }

  @Test
  fun `resume uses the loaded work item instead of startGoal`() = runTest {
    val dispatcher = StandardTestDispatcher(testScheduler)
    Dispatchers.setMain(dispatcher)
    specs.controlState.value = SpecControlState.Loaded(control(runState = SpecRunState.Paused, canStart = true), goal)
    val model = model(dispatcher)
    model.open()
    advanceUntilIdle()
    model.onAction(SpecsAction.Select("SKILL-1"))
    advanceUntilIdle()
    model.onAction(SpecsAction.ChooseAgent("claude"))
    model.onAction(SpecsAction.Start)
    advanceUntilIdle()

    assertEquals(listOf("launch"), controls.calls.map { it.control })
    assertEquals("claude", controls.calls.single().agentId)
  }

  private fun model(dispatcher: TestDispatcher) = SpecsPaneModel(
    specs,
    controls,
    SelectionHolder(),
    ToastQueue(),
    AppDispatchers(dispatcher, dispatcher, dispatcher),
  ).also(models::add)

  private fun control(
    runState: SpecRunState = SpecRunState.NotStarted,
    canStart: Boolean = false,
    canCancel: Boolean = false,
  ) = SpecBundleControlView(
    issueKey = "SKILL-1",
    runState = runState,
    everStarted = runState != SpecRunState.NotStarted,
    workflowId = "SKILL-1",
    repositoryIdentity = "repo",
    canStart = canStart,
    startLabel = if (runState == SpecRunState.NotStarted) "Start" else "Resume",
    canPause = false,
    pauseRequested = false,
    canCancel = canCancel,
    purgeVisible = false,
    canPurge = false,
    agentId = "claude",
    agentChoices = listOf("claude"),
    unavailableReason = null,
  )

  private inner class FakeSpecs : SpecRepository {
    var controlReads = 0
    val controlState = MutableStateFlow<SpecControlState>(SpecControlState.Loaded(control(), goal))
    private val bundle = SpecNode.Bundle(
      "SKILL-1", "SKILL-1", "/repo/.skill-bill/specs/SKILL-1", "SKILL-1", "Example feature",
      SpecStatus.Pending, SpecGroup.Pending, 0, 1,
      listOf(SpecNode.File("spec.md", "SKILL-1/spec.md", "/repo/.skill-bill/specs/SKILL-1/spec.md", SpecFileKind.ParentSpec, null, null, null)),
    )
    private val tree = SpecTree(project, listOf(bundle), SpecCounts(1, 0, 1, 0), false)
    private val state = MutableStateFlow<SpecTreeState>(SpecTreeState.Loaded(tree, project.specsRoot))

    override suspend fun projects() = Outcome.Success(listOf(project))
    override suspend fun projectFor(repoRoot: String) = Outcome.Success(project)
    override fun observeTree(projectId: String): Flow<SpecTreeState> = state
    override fun refreshTree(projectId: String) = Unit
    override fun control(bundle: SpecBundleRef): Flow<SpecControlState> {
      controlReads++
      return controlState
    }
    override suspend fun openFile(path: String) = OpenOutcome.Opened
    override suspend fun openFileInDefaultApp(path: String) = OpenOutcome.NotOpened
    override suspend fun revealFolder(path: String) = OpenOutcome.Opened
  }
}
