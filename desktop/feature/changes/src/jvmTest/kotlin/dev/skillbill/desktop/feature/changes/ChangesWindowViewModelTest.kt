package dev.skillbill.desktop.feature.changes

import dev.skillbill.desktop.core.domain.model.ChangeBaseline
import dev.skillbill.desktop.core.domain.model.ChangeScope
import dev.skillbill.desktop.core.domain.model.ChangeScopeTarget
import dev.skillbill.desktop.core.domain.model.ChangeSet
import dev.skillbill.desktop.core.domain.model.ChangeStatus
import dev.skillbill.desktop.core.domain.model.ChangedFile
import dev.skillbill.desktop.core.domain.model.Detail
import dev.skillbill.desktop.core.domain.model.DetailAttempts
import dev.skillbill.desktop.core.domain.model.DetailSections
import dev.skillbill.desktop.core.domain.model.DetailState
import dev.skillbill.desktop.core.domain.model.FileDiffState
import dev.skillbill.desktop.core.domain.model.LatestActivity
import dev.skillbill.desktop.core.domain.model.ScopeChangesState
import dev.skillbill.desktop.core.domain.model.ScopeSubtask
import dev.skillbill.desktop.core.domain.model.ScopeSubtaskState
import dev.skillbill.desktop.core.domain.model.SubtaskScopesState
import dev.skillbill.desktop.core.domain.model.SubtaskStats
import dev.skillbill.desktop.core.domain.model.WorkItem
import dev.skillbill.desktop.core.domain.model.WorkKind
import dev.skillbill.desktop.core.domain.repository.WorkChangeScopesRepository
import dev.skillbill.desktop.core.testing.FakeWorkDetailRepository
import dev.skillbill.desktop.core.testing.workItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class ChangesWindowViewModelTest {
  private val details = FakeWorkDetailRepository()
  private val scopes = FakeChangeScopes()
  private val baseline = ChangeBaseline.RunStart("/repo", "base", emptyList())

  @AfterTest
  fun tearDown() = Dispatchers.resetMain()

  @Test
  fun `a scope the execution does not offer falls back to all changes`() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val phase = workItem("P", kind = WorkKind.Phase)
    detailOf(phase)

    val viewModel = ChangesWindowViewModel(phase, ChangeScope.Subtasks(2, 2), details, scopes)
    advanceUntilIdle()

    assertEquals(ChangeScope.All, viewModel.state.value.scope)
    assertEquals(listOf<ChangeScope>(ChangeScope.All), scopes.observed)
  }

  @Test
  fun `a refresh keeps a still-listed selection and otherwise selects the first file`() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val phase = workItem("P", kind = WorkKind.Phase)
    detailOf(phase)
    val viewModel = ChangesWindowViewModel(phase, ChangeScope.All, details, scopes)
    scopes.list(ChangeScope.All).value = listed(file("a/A.kt"), file("b/B.kt"))
    advanceUntilIdle()

    viewModel.onAction(ChangesWindowAction.SelectFile("b/B.kt"))
    scopes.list(ChangeScope.All).value = listed(file("a/A.kt"), file("b/B.kt", insertions = 4))
    advanceUntilIdle()
    assertEquals("b/B.kt", viewModel.state.value.selectedPath)

    scopes.list(ChangeScope.All).value = listed(file("a/A.kt"), file("c/C.kt"))
    advanceUntilIdle()
    assertEquals("a/A.kt", viewModel.state.value.selectedPath)
  }

  @Test
  fun `a subtask chip narrows the scope, shows loading, and keeps the file selected`() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val goal = workItem("G")
    detailOf(goal)
    val foo = file("a/Foo.kt")
    scopes.subtasks.value = SubtaskScopesState.Listed(
      worktreeRoot = "/repo",
      targetBranch = "main",
      rangesSupported = true,
      subtasks = listOf(subtask(3), subtask(5)),
      stats = mapOf(3 to SubtaskStats(listOf(foo), 1, 0), 5 to SubtaskStats(listOf(foo), 1, 0)),
    )
    val viewModel = ChangesWindowViewModel(goal, ChangeScope.All, details, scopes)
    scopes.list(ChangeScope.All).value = listed(file("a/Bar.kt"), foo)
    advanceUntilIdle()
    viewModel.onAction(ChangesWindowAction.SelectFile(foo.path))
    advanceUntilIdle()
    assertEquals(2, viewModel.state.value.groups.single().rows.single { it.file == foo }.touchCount)
    assertEquals(listOf(3, 5), (viewModel.state.value.diff as DiffPaneUi.Shown).chips.map { it.id })

    viewModel.onAction(ChangesWindowAction.NarrowToSubtask(5))
    assertEquals(ChangeScope.Subtasks(5, 5), viewModel.state.value.scope)
    assertEquals(ScopeListUi.Loading, viewModel.state.value.list)

    scopes.list(ChangeScope.Subtasks(5, 5)).value = listed(foo)
    advanceUntilIdle()
    assertEquals(ChangeScope.Subtasks(5, 5), scopes.observed.last())
    assertEquals(foo.path, viewModel.state.value.selectedPath)
    assertEquals(ChangeScope.Subtasks(5, 5) to foo.path, scopes.diffReads.last())
  }

  @Test
  fun `a subtask scope with a lost base shows the reason and a chip scope loads its own list`() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val goal = workItem("G")
    detailOf(goal)
    val foo = file("a/Foo.kt")
    scopes.subtasks.value = SubtaskScopesState.Listed(
      worktreeRoot = "/repo",
      targetBranch = "main",
      rangesSupported = true,
      subtasks = listOf(subtask(3), subtask(5)),
      stats = mapOf(3 to SubtaskStats(listOf(foo), 1, 0), 5 to SubtaskStats(listOf(foo), 1, 0)),
    )
    scopes.list(ChangeScope.Subtasks(3, 3)).value = ScopeChangesState.BaseUnavailable("start3 is not in the repository")
    val viewModel = ChangesWindowViewModel(goal, ChangeScope.Subtasks(3, 3), details, scopes)
    advanceUntilIdle()

    assertEquals(ChangeScope.Subtasks(3, 3), viewModel.state.value.scope)
    assertEquals<ScopeListUi>(ScopeListUi.BaseUnavailable("start3 is not in the repository"), viewModel.state.value.list)

    viewModel.onAction(ChangesWindowAction.NarrowToSubtask(5))
    scopes.list(ChangeScope.Subtasks(5, 5)).value = listed(foo)
    advanceUntilIdle()

    assertEquals(listOf<ChangeScope>(ChangeScope.Subtasks(3, 3), ChangeScope.Subtasks(5, 5)), scopes.observed)
    assertEquals(ScopeListUi.Listed(ChangeSet("base", listOf(foo), 1, 0)), viewModel.state.value.list)
    assertEquals(foo.path, viewModel.state.value.selectedPath)
  }

  @Test
  fun `a missing execution shows the missing state and stops change reads`() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val phase = workItem("P", kind = WorkKind.Phase)
    val detail = detailOf(phase)
    val viewModel = ChangesWindowViewModel(phase, ChangeScope.All, details, scopes)
    advanceUntilIdle()

    detail.value = DetailState(phase, DetailSections.Gone)
    scopes.list(ChangeScope.All).value = listed(file("a/A.kt"))
    advanceUntilIdle()

    assertEquals(WindowContent.Missing, viewModel.state.value.content)
    assertEquals(ScopeListUi.Loading, viewModel.state.value.list)
    assertEquals(listOf<ChangeScope>(ChangeScope.All), scopes.observed)
  }

  private fun detailOf(item: WorkItem): MutableStateFlow<DetailState> {
    val flow = MutableStateFlow(DetailState(item, DetailSections.Loaded(detail(baseline), null, emptyMap(), emptySet(), stale = false)))
    details.details = mapOf(item.id to flow)
    return flow
  }

  private fun file(path: String, insertions: Int = 1) = ChangedFile(path, null, ChangeStatus.Modified, insertions, 0)

  private fun listed(vararg files: ChangedFile) = ScopeChangesState.Listed(ChangeSet("base", files.toList(), files.sumOf { it.insertions ?: 0 }, 0))

  private fun subtask(id: Int) = ScopeSubtask(id, "Subtask $id", ScopeSubtaskState.DONE, "start$id", "end$id", emptyList())

  private class FakeChangeScopes : WorkChangeScopesRepository {
    val subtasks = MutableStateFlow<SubtaskScopesState>(SubtaskScopesState.Loading)
    val observed = mutableListOf<ChangeScope>()
    val diffReads = mutableListOf<Pair<ChangeScope, String>>()
    private val lists = mutableMapOf<ChangeScope, MutableStateFlow<ScopeChangesState>>()

    fun list(scope: ChangeScope): MutableStateFlow<ScopeChangesState> = lists.getOrPut(scope) { MutableStateFlow(ScopeChangesState.Loading) }

    override fun observeSubtasks(goalWorkflowId: String, running: Boolean): Flow<SubtaskScopesState> = subtasks

    override fun observeScope(target: ChangeScopeTarget, scope: ChangeScope, running: Boolean): Flow<ScopeChangesState> {
      observed += scope
      return list(scope)
    }

    override suspend fun fileDiff(target: ChangeScopeTarget, scope: ChangeScope, file: ChangedFile): FileDiffState {
      diffReads += scope to file.path
      return FileDiffState.Binary
    }
  }

  private companion object {
    val epoch: Instant = Instant.fromEpochMilliseconds(0)

    fun detail(baseline: ChangeBaseline) = Detail(
      featureTitle = null, workflowId = null, invocationId = null, activeAgent = null, executionLiveness = null,
      updatedAt = epoch, lastAgentActivityLabel = null, lastAgentActivityAt = null, currentSubtaskStartedAt = null,
      subtaskActiveDurationMs = null, subtaskActiveDurationAsOf = null, qaVerdict = null, qaOutcome = null, stepPipeline = emptyList(),
      goalFinishPipeline = emptyList(), currentPhase = null, completeCount = 0, pendingCount = 0, blockedCount = 0, subtasks = emptyList(),
      phases = emptyList(), dependencies = emptyList(), attempts = DetailAttempts(emptyMap(), emptyMap(), emptyMap(), null, 0),
      validation = emptyList(), phaseResults = emptyList(), goalQa = null, goalAssessment = null, latestActivity = LatestActivity(null, null),
      executionHistory = emptyList(), incident = null, incidentHistory = emptyList(), goalSpecBundle = null, observedAt = epoch,
      changeBaseline = baseline,
    )
  }
}
