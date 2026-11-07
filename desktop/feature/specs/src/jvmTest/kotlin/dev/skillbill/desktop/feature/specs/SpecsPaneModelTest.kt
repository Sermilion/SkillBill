package dev.skillbill.desktop.feature.specs

import dev.skillbill.desktop.core.common.AppDispatchers
import dev.skillbill.desktop.core.common.Outcome
import dev.skillbill.desktop.core.domain.model.OpenOutcome
import dev.skillbill.desktop.core.domain.model.SpecBundleControlView
import dev.skillbill.desktop.core.domain.model.SpecBundleRef
import dev.skillbill.desktop.core.domain.model.SpecControlState
import dev.skillbill.desktop.core.domain.model.SpecCounts
import dev.skillbill.desktop.core.domain.model.SpecDeleteOutcome
import dev.skillbill.desktop.core.domain.model.SpecFileKind
import dev.skillbill.desktop.core.domain.model.SpecGroup
import dev.skillbill.desktop.core.domain.model.SpecNode
import dev.skillbill.desktop.core.domain.model.SpecProject
import dev.skillbill.desktop.core.domain.model.SpecRunState
import dev.skillbill.desktop.core.domain.model.SpecStatus
import dev.skillbill.desktop.core.domain.model.SpecTree
import dev.skillbill.desktop.core.domain.model.SpecTreeState
import dev.skillbill.desktop.core.domain.model.ExportPreflightOutcome
import dev.skillbill.desktop.core.domain.model.ExportOutcome
import dev.skillbill.desktop.core.domain.model.ExportRefusalCategory
import dev.skillbill.desktop.core.domain.repository.SpecRepository
import dev.skillbill.desktop.core.domain.settings.FavoriteSpecKey
import dev.skillbill.desktop.core.testing.FakeAppSettingsRepository
import dev.skillbill.desktop.core.testing.FakeWorkControlRepository
import dev.skillbill.desktop.core.testing.FakeArchiveFileChooser
import dev.skillbill.desktop.core.testing.FakeDesktopClipboard
import dev.skillbill.desktop.core.testing.FakeWorkBoardRepository
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
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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
  private val settings = FakeAppSettingsRepository()
  private val specs = FakeSpecs()
  private val controls = FakeWorkControlRepository()
  private val toasts = ToastQueue()
  private val models = mutableListOf<SpecsPaneModel>()

  @AfterTest
  fun tearDown() {
    models.forEach(SpecsPaneModel::close)
    Dispatchers.resetMain()
  }

  @Test
  fun `a selected file uses its parent control while retaining its own row selection`() = runTest {
    val dispatcher = StandardTestDispatcher(testScheduler)
    Dispatchers.setMain(dispatcher)
    specs.controlState.value = SpecControlState.Loaded(control(runState = SpecRunState.Paused, canStart = true), goal)
    val model = model(dispatcher)
    model.open()
    advanceUntilIdle()

    model.onAction(SpecsAction.Select("SKILL-1/spec.md"))
    advanceUntilIdle()
    assertEquals(ToolbarSelection.Bundle("SKILL-1"), model.state.value.toolbar.selection)
    assertEquals("SKILL-1/spec.md", model.state.value.selectedPath)
    assertEquals(1, specs.controlReads)
    model.onAction(SpecsAction.Select("SKILL-1"))
    model.onAction(SpecsAction.Select("SKILL-1/spec.md"))
    advanceUntilIdle()
    assertEquals(1, specs.controlReads)
    model.onAction(SpecsAction.Start)
    model.onAction(SpecsAction.Pause)
    model.onAction(SpecsAction.RequestCancel)
    advanceUntilIdle()

    assertEquals(1, specs.controlReads)
    assertEquals(listOf("launch"), controls.calls.map { it.control })
    assertEquals("SKILL-1", controls.calls.single().item.issueKey)
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
  fun `purge uses the resolved run owner checkout`() = runTest {
    val dispatcher = StandardTestDispatcher(testScheduler)
    Dispatchers.setMain(dispatcher)
    specs.controlState.value = SpecControlState.Loaded(
      control(runState = SpecRunState.Complete).copy(
        ownerRepoRoot = "/repo-b",
        purgeVisible = true,
        canPurge = true,
      ),
      goal,
    )
    val model = model(dispatcher)
    model.open()
    advanceUntilIdle()
    model.onAction(SpecsAction.Select("SKILL-1"))
    advanceUntilIdle()

    model.onAction(SpecsAction.RequestPurge)
    model.onAction(SpecsAction.ConfirmPurge)
    advanceUntilIdle()

    assertEquals(listOf<Pair<String, String?>>("SKILL-1" to "/repo-b"), controls.purgeRequests)
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

  @Test
  fun `choosing an agent resumes only a start that opened the picker`() = runTest {
    val dispatcher = StandardTestDispatcher(testScheduler)
    Dispatchers.setMain(dispatcher)
    specs.controlState.value = SpecControlState.Loaded(
      control(runState = SpecRunState.Paused, canStart = true).copy(agentId = null, agentChoices = listOf("codex")),
      goal,
    )
    val model = model(dispatcher)
    model.open()
    advanceUntilIdle()
    model.onAction(SpecsAction.Select("SKILL-1"))
    advanceUntilIdle()

    model.onAction(SpecsAction.Start)
    advanceUntilIdle()
    assertTrue(model.state.value.toolbar.agentMenuOpen)
    model.onAction(SpecsAction.ChooseAgent("codex"))
    advanceUntilIdle()

    assertEquals(listOf("launch"), controls.calls.map { it.control })
    assertEquals("codex", controls.calls.single().agentId)
  }

  @Test
  fun `ordinary agent choice does not start and a forbidden agent menu stays closed`() = runTest {
    val dispatcher = StandardTestDispatcher(testScheduler)
    Dispatchers.setMain(dispatcher)
    specs.controlState.value = SpecControlState.Loaded(
      control(runState = SpecRunState.Paused, canStart = false).copy(agentId = null, agentChoices = listOf("codex")),
      goal,
    )
    val model = model(dispatcher)
    model.open()
    advanceUntilIdle()
    model.onAction(SpecsAction.Select("SKILL-1"))
    advanceUntilIdle()

    model.onAction(SpecsAction.OpenAgentMenu)
    assertFalse(model.state.value.toolbar.agentMenuOpen)
    model.onAction(SpecsAction.ChooseAgent("codex"))
    advanceUntilIdle()
    assertTrue(controls.calls.isEmpty())

    model.onAction(SpecsAction.Deselect)
    advanceUntilIdle()
    assertNull(model.state.value.selectedPath)
    assertEquals(ToolbarSelection.None, model.state.value.toolbar.selection)
  }

  @Test
  fun `a bundle click selects without changing its expansion`() = runTest {
    val dispatcher = StandardTestDispatcher(testScheduler)
    Dispatchers.setMain(dispatcher)
    val model = model(dispatcher)
    model.open()
    advanceUntilIdle()
    fun paths() = model.state.value.rows.map { it.node.path }

    model.onAction(SpecsAction.Click("SKILL-1"))
    advanceUntilIdle()
    assertEquals(listOf("SKILL-1"), paths())
    assertEquals("SKILL-1", model.state.value.selectedPath)

    model.onAction(SpecsAction.Toggle("SKILL-1"))
    advanceUntilIdle()
    assertEquals(listOf("SKILL-1", "SKILL-1/spec.md"), paths())
  }

  @Test
  fun `file context menu selects and targets the parent bundle`() = runTest {
    val model = openedModel()

    model.onAction(SpecsAction.OpenRowMenu("SKILL-1/spec.md"))
    advanceUntilIdle()
    assertEquals("SKILL-1", model.state.value.rowMenuPath)
    assertEquals("SKILL-1/spec.md", model.state.value.rowMenuAnchorPath)
    assertEquals("SKILL-1/spec.md", model.state.value.selectedPath)
    specs.treeState.value = SpecTreeState.Loaded(specs.loadedTree, project.specsRoot)
    advanceUntilIdle()
    assertEquals("SKILL-1", model.state.value.rowMenuPath)
    assertEquals("SKILL-1/spec.md", model.state.value.rowMenuAnchorPath)
    model.onAction(SpecsAction.DismissRowMenu)

    model.onAction(SpecsAction.OpenRowMenu("SKILL-1"))
    advanceUntilIdle()
    assertEquals("SKILL-1", model.state.value.rowMenuPath)
    model.onAction(SpecsAction.RequestDelete("SKILL-1"))
    advanceUntilIdle()

    assertNull(model.state.value.rowMenuPath)
    assertEquals(
      SpecDeleteTarget("SKILL-1", "SKILL-1", "Example feature", "/repo/.skill-bill/specs/SKILL-1"),
      model.state.value.deleteTarget,
    )
    assertEquals(emptyList(), specs.deletes)
  }

  @Test
  fun `shared spec actions preserve order and disabled runtime reasons`() {
    val bundle = specs.loadedTree.roots.single() as SpecNode.Bundle
    val control = control(canStart = true, canCancel = true, canDelete = false).copy(
      canPause = true,
      pauseRequested = true,
      purgeVisible = true,
      canPurge = false,
    )
    val state = SpecsUiState(
      project = project,
      selectedPath = bundle.path,
      toolbar = SpecToolbarState(
        selection = ToolbarSelection.Bundle(bundle.path),
        control = SpecControlState.Loaded(control, goal),
        favorite = true,
        favoriteAvailable = true,
      ),
    )

    val entries = specMenuActions(bundle, state).filterIsInstance<SpecMenuAction.Item>()

    assertEquals(
      listOf("Open spec", "Reveal folder", "Copy key", "Remove favorite", "Export work", "Start", "Pause requested", "Choose agent", "Cancel goal…", "Purge run history…", DELETE_SPEC_FILES),
      entries.map { it.label },
    )
    assertFalse(entries.first { it.label == "Pause requested" }.enabled)
    assertEquals(PAUSE_NOTICE, entries.first { it.label == "Pause requested" }.reason)
    assertFalse(entries.last().enabled)
    assertEquals(DELETE_LIVE, entries.last().reason)
    assertEquals("F", entries.first { it.action == SpecsAction.ToggleFavorite }.shortcut)
    assertEquals("R", entries.first { it.action == SpecsAction.Start }.shortcut)
    assertEquals("A", entries.first { it.action == SpecsAction.OpenAgentMenu }.shortcut)
  }

  @Test
  fun `command palette maps the shared spec actions in their menu order`() = runTest {
    val model = model(StandardTestDispatcher(testScheduler))
    val bundle = specs.loadedTree.roots.single() as SpecNode.Bundle
    val state = SpecsUiState(
      project = project,
      tree = SpecTreeState.Loaded(specs.loadedTree, project.specsRoot),
      selectedPath = bundle.path,
      toolbar = SpecToolbarState(
        selection = ToolbarSelection.Bundle(bundle.path),
        control = SpecControlState.Loaded(
          control(canStart = true, canCancel = true, canDelete = false).copy(pauseRequested = true),
          goal,
        ),
      ),
    )

    val menuActions = specMenuActions(bundle, state).filterIsInstance<SpecMenuAction.Item>()
    val paletteCommands = model.commandSection(state)!!.commands

    assertEquals(menuActions.map { it.label }, paletteCommands.map { it.item.label })
    assertEquals(menuActions.map { it.enabled }, paletteCommands.map { it.item.enabled })
    assertEquals(PAUSE_NOTICE, paletteCommands.single { it.item.label == "Pause requested" }.item.end)
  }

  @Test
  fun `delete reaches the repository once, and only after confirmation`() = runTest {
    val model = openedModel()

    model.onAction(SpecsAction.RequestDelete("SKILL-1"))
    model.onAction(SpecsAction.KeepSpecFiles)
    advanceUntilIdle()
    assertNull(model.state.value.deleteTarget)
    assertEquals(emptyList(), specs.deletes)

    model.onAction(SpecsAction.RequestDelete("SKILL-1"))
    model.onAction(SpecsAction.ConfirmDelete)
    model.onAction(SpecsAction.ConfirmDelete)
    advanceUntilIdle()

    assertEquals(listOf(SpecBundleRef("p1", "SKILL-1", "/repo")), specs.deletes)
    assertFalse(model.state.value.deleting)
  }

  @Test
  fun `same bundle loaded false delete verdict prevents opening confirmation`() = runTest {
    val dispatcher = StandardTestDispatcher(testScheduler)
    Dispatchers.setMain(dispatcher)
    specs.controlState.value = SpecControlState.Loaded(control(canDelete = false), goal)
    val model = model(dispatcher)
    model.open()
    advanceUntilIdle()
    model.onAction(SpecsAction.Select("SKILL-1"))
    advanceUntilIdle()

    model.onAction(SpecsAction.RequestDelete("SKILL-1"))
    advanceUntilIdle()

    assertNull(model.state.value.deleteTarget)
    assertEquals(emptyList(), specs.deletes)
  }

  @Test
  fun `delete stays available without loaded control`() = runTest {
    val dispatcher = StandardTestDispatcher(testScheduler)
    Dispatchers.setMain(dispatcher)
    specs.controlState.value = SpecControlState.Loading
    val model = model(dispatcher)
    model.open()
    advanceUntilIdle()

    model.onAction(SpecsAction.RequestDelete("SKILL-1"))
    advanceUntilIdle()

    assertEquals("SKILL-1", model.state.value.deleteTarget?.path)
    assertEquals(emptyList(), specs.deletes)
  }

  @Test
  fun `a refused delete shows why and keeps the row`() = runTest {
    specs.deleteOutcome = SpecDeleteOutcome.Refused("The goal for this spec is running.")
    val model = openedModel()

    model.onAction(SpecsAction.RequestDelete("SKILL-1"))
    model.onAction(SpecsAction.ConfirmDelete)
    advanceUntilIdle()

    assertEquals(listOf("Couldn't delete spec files: The goal for this spec is running."), toasts.host.toasts.map { it.data.title })
    assertEquals(listOf("SKILL-1"), model.state.value.rows.map { it.node.path })
    assertEquals(0, specs.refreshes)
  }

  @Test
  fun `branch refusals with live in the branch name show the reason at preflight and export`() = runTest {
    val reason = "Current branch 'main' is not the recorded feature branch 'feature/delivery'; export refused."
    controls.exportPreflightOutcome = ExportPreflightOutcome.Refused(reason, ExportRefusalCategory.BRANCH)
    val chooser = FakeArchiveFileChooser().apply { nextSave = "/tmp/SKILL-1.zip" }
    val dispatcher = StandardTestDispatcher(testScheduler)
    Dispatchers.setMain(dispatcher)
    val model = model(dispatcher, chooser)
    model.open()
    advanceUntilIdle()
    model.onAction(SpecsAction.Select("SKILL-1"))
    advanceUntilIdle()

    model.onAction(SpecsAction.RequestExport)
    advanceUntilIdle()
    assertFalse(model.state.value.toolbar.exportLiveDialogOpen)
    assertTrue(toasts.host.toasts.any { "feature/delivery" in (it.data.body ?: it.data.title) })

    toasts.host.toasts.toList().forEach { toasts.host.dismiss(it.id) }
    controls.exportPreflightOutcome = ExportPreflightOutcome.Ready("feature/delivery", "abc123")
    controls.exportOutcome = ExportOutcome.Refused(reason, ExportRefusalCategory.BRANCH)
    model.onAction(SpecsAction.RequestExport)
    advanceUntilIdle()

    assertFalse(model.state.value.toolbar.exportLiveDialogOpen)
    assertTrue(toasts.host.toasts.any { "feature/delivery" in (it.data.body ?: it.data.title) })
  }

  @Test
  fun `export reaches its existing preflight while run control is loading`() = runTest {
    val dispatcher = StandardTestDispatcher(testScheduler)
    Dispatchers.setMain(dispatcher)
    specs.controlState.value = SpecControlState.Loading
    controls.exportPreflightOutcome = ExportPreflightOutcome.Refused("Branch refused", ExportRefusalCategory.BRANCH)
    val model = model(dispatcher)
    model.open()
    advanceUntilIdle()
    model.onAction(SpecsAction.Select("SKILL-1"))
    model.onAction(SpecsAction.RequestExport)
    advanceUntilIdle()

    assertEquals(listOf<Pair<String, String?>>("SKILL-1" to "/repo"), controls.preflightRequests)
    assertFalse(model.state.value.toolbar.exportLiveDialogOpen)
  }

  @Test
  fun `a live worker refusal still opens the export pause presentation`() = runTest {
    controls.exportPreflightOutcome = ExportPreflightOutcome.Refused(
      "Goal is live; export refused.",
      ExportRefusalCategory.LIVENESS,
    )
    val dispatcher = StandardTestDispatcher(testScheduler)
    Dispatchers.setMain(dispatcher)
    val model = model(dispatcher)
    model.open()
    advanceUntilIdle()
    model.onAction(SpecsAction.Select("SKILL-1"))
    advanceUntilIdle()
    model.onAction(SpecsAction.RequestExport)
    advanceUntilIdle()

    assertTrue(model.state.value.toolbar.exportLiveDialogOpen)
  }

  @Test
  fun `a deleted bundle refreshes the tree and drops its selection`() = runTest {
    val model = openedModel()
    model.onAction(SpecsAction.Select("SKILL-1"))
    advanceUntilIdle()
    assertEquals("SKILL-1", model.state.value.selectedPath)

    model.onAction(SpecsAction.RequestDelete("SKILL-1"))
    model.onAction(SpecsAction.ConfirmDelete)
    advanceUntilIdle()

    assertEquals(1, specs.refreshes)
    assertNull(model.state.value.selectedPath)
    assertEquals(ToolbarSelection.None, model.state.value.toolbar.selection)
    assertEquals(emptyList(), toasts.host.toasts)
  }

  private fun TestScope.openedModel(): SpecsPaneModel {
    val dispatcher = StandardTestDispatcher(testScheduler)
    Dispatchers.setMain(dispatcher)
    val model = model(dispatcher)
    model.open()
    advanceUntilIdle()
    return model
  }

  @Test
  fun `file selection toggles the parent bundle favorite`() = runTest {
    val dispatcher = StandardTestDispatcher(testScheduler)
    Dispatchers.setMain(dispatcher)
    val model = model(dispatcher)
    model.open()
    advanceUntilIdle()
    model.onAction(SpecsAction.Select("SKILL-1/spec.md"))
    advanceUntilIdle()
    model.onAction(SpecsAction.ToggleFavorite)
    advanceUntilIdle()
    assertEquals(setOf(FavoriteSpecKey("p1", "SKILL-1")), settings.settings.value.favoriteSpecs)
    assertTrue(model.state.value.toolbar.favorite)
  }

  @Test
  fun `revealFavorite selects the bundle after the tree loads`() = runTest {
    val dispatcher = StandardTestDispatcher(testScheduler)
    Dispatchers.setMain(dispatcher)
    specs.treeState.value = SpecTreeState.Loading
    val model = model(dispatcher)
    model.open()
    advanceUntilIdle()
    model.revealFavorite("p1", "SKILL-1")
    specs.treeState.value = SpecTreeState.Loaded(specs.loadedTree, project.specsRoot)
    advanceUntilIdle()
    assertEquals(ToolbarSelection.Bundle("SKILL-1"), model.state.value.toolbar.selection)
  }

  @Test
  fun `manual project choice cancels a pending favorite reveal`() = runTest {
    val dispatcher = StandardTestDispatcher(testScheduler)
    Dispatchers.setMain(dispatcher)
    specs.treeState.value = SpecTreeState.Loading
    val model = model(dispatcher)
    model.open()
    advanceUntilIdle()
    model.revealFavorite("p1", "SKILL-1")
    model.onAction(SpecsAction.PickProject("p1"))
    specs.treeState.value = SpecTreeState.Loaded(specs.loadedTree, project.specsRoot)
    advanceUntilIdle()

    assertEquals(ToolbarSelection.None, model.state.value.toolbar.selection)
  }

  @Test
  fun `favorite reveal ignores a path now occupied by a file`() = runTest {
    val dispatcher = StandardTestDispatcher(testScheduler)
    Dispatchers.setMain(dispatcher)
    specs.treeState.value = SpecTreeState.Loading
    val model = model(dispatcher)
    model.open()
    advanceUntilIdle()
    model.revealFavorite("p1", "SKILL-1")
    val file = SpecNode.File("SKILL-1", "SKILL-1", "/repo/.skill-bill/specs/SKILL-1", SpecFileKind.ParentSpec, null, null, null)
    specs.treeState.value = SpecTreeState.Loaded(specs.loadedTree.copy(roots = listOf(file)), project.specsRoot)
    advanceUntilIdle()

    assertEquals(ToolbarSelection.None, model.state.value.toolbar.selection)
    assertNull(model.state.value.selectedPath)
  }

  private fun model(dispatcher: TestDispatcher, archiveChooser: FakeArchiveFileChooser = FakeArchiveFileChooser()) = SpecsPaneModel(
    specs,
    controls,
    settings,
    SelectionHolder(),
    toasts,
    AppDispatchers(dispatcher, dispatcher, dispatcher),
    archiveChooser = archiveChooser,
    board = FakeWorkBoardRepository(),
    clipboard = FakeDesktopClipboard(),
  ).also(models::add)

  private fun control(
    runState: SpecRunState = SpecRunState.NotStarted,
    canStart: Boolean = false,
    canCancel: Boolean = false,
    canDelete: Boolean = true,
  ) = SpecBundleControlView(
    issueKey = "SKILL-1",
    runState = runState,
    everStarted = runState != SpecRunState.NotStarted,
    workflowId = "SKILL-1",
    repositoryIdentity = "repo",
    ownerRepoRoot = "/repo",
    canStart = canStart,
    startLabel = if (runState == SpecRunState.NotStarted) "Start" else "Resume",
    canPause = false,
    pauseRequested = false,
    canCancel = canCancel,
    canDelete = canDelete,
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
    val loadedTree = tree
    val treeState = MutableStateFlow<SpecTreeState>(SpecTreeState.Loaded(tree, project.specsRoot))

    override suspend fun projects() = Outcome.Success(listOf(project))
    override suspend fun projectFor(repoRoot: String) = Outcome.Success(project)
    override fun observeTree(projectId: String): Flow<SpecTreeState> = treeState
    var refreshes = 0
    val deletes = mutableListOf<SpecBundleRef>()
    var deleteOutcome: SpecDeleteOutcome = SpecDeleteOutcome.Deleted

    override fun refreshTree(projectId: String) {
      refreshes++
    }
    override suspend fun deleteBundle(bundle: SpecBundleRef): SpecDeleteOutcome {
      deletes += bundle
      return deleteOutcome
    }
    override fun control(bundle: SpecBundleRef): Flow<SpecControlState> {
      controlReads++
      return controlState
    }
    override suspend fun openFile(path: String) = OpenOutcome.Opened
    override suspend fun openFileInDefaultApp(path: String) = OpenOutcome.NotOpened
    override suspend fun openFileUnder(path: String, directory: String) = OpenOutcome.NotOpened
    override suspend fun revealFolder(path: String) = OpenOutcome.Opened
  }
}
