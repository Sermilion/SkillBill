package dev.skillbill.desktop.feature.detail

import dev.skillbill.desktop.core.common.Outcome
import dev.skillbill.desktop.core.domain.model.DetailList
import dev.skillbill.desktop.core.domain.model.DetailListOutcome
import dev.skillbill.desktop.core.domain.model.DetailSections
import dev.skillbill.desktop.core.domain.model.DetailState
import dev.skillbill.desktop.core.domain.model.OpenOutcome
import dev.skillbill.desktop.core.domain.model.ReadProblem
import dev.skillbill.desktop.core.domain.model.RunOptions
import dev.skillbill.desktop.core.domain.model.SpecBundleRef
import dev.skillbill.desktop.core.domain.model.SpecControlState
import dev.skillbill.desktop.core.domain.model.SpecProject
import dev.skillbill.desktop.core.domain.model.SpecTreeState
import dev.skillbill.desktop.core.domain.model.WorkItem
import dev.skillbill.desktop.core.domain.repository.SpecRepository
import dev.skillbill.desktop.core.domain.repository.WorkDetailRepository
import dev.skillbill.desktop.core.testing.FakeWorkBoardRepository
import dev.skillbill.desktop.core.testing.FakeWorkControlRepository
import dev.skillbill.desktop.core.testing.workItem
import dev.skillbill.desktop.core.ui.feedback.ToastQueue
import dev.skillbill.desktop.core.ui.selection.SelectionHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class DetailViewModelTest {
  private val item = workItem("SKILL-1")
  private val details = RecordingDetailRepository()
  private val clock = object : Clock { override fun now() = Instant.fromEpochMilliseconds(0) }

  @AfterTest
  fun tearDown() = Dispatchers.resetMain()

  private fun viewModel() = DetailViewModel(item, details, FakeWorkBoardRepository(), NoSpecs, NoWebPages, SelectionHolder(), clock, FakeWorkControlRepository(), ToastQueue())

  @Test
  fun `the first state holds the row's header before the repository emits`() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))

    val state = viewModel().state.value

    assertEquals(item, state.header)
    assertEquals(SectionsUi.Pending, state.sections)
  }

  @Test
  fun `each page request carries the anchor the previous page returned, once`() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val viewModel = viewModel()
    advanceUntilIdle()

    details.flow.emit(loaded("a1"))
    advanceUntilIdle()
    viewModel.onAction(DetailAction.LoadMore(DetailList.Subtasks))
    viewModel.onAction(DetailAction.LoadMore(DetailList.Subtasks))
    advanceUntilIdle()
    details.flow.emit(loaded("a2"))
    advanceUntilIdle()
    viewModel.onAction(DetailAction.LoadMore(DetailList.Subtasks))
    advanceUntilIdle()

    val expected = listOf<Triple<String, DetailList, String?>>(
      Triple("SKILL-1", DetailList.Subtasks, "a1"),
      Triple("SKILL-1", DetailList.Subtasks, "a2"),
    )
    assertEquals(expected, details.loads.toList())
  }

  private fun loaded(anchor: String) = DetailState(
    item,
    DetailSections.Loaded(emptyDetail, null, mapOf(DetailList.Subtasks to anchor), setOf(DetailList.Subtasks), stale = false),
  )

  private class RecordingDetailRepository : WorkDetailRepository {
    val flow = MutableSharedFlow<DetailState>()
    val loads = mutableListOf<Triple<String, DetailList, String?>>()

    override fun observe(item: WorkItem): Flow<DetailState> = flow

    override suspend fun loadList(id: String, list: DetailList, anchor: String?): DetailListOutcome {
      loads += Triple(id, list, anchor)
      return DetailListOutcome.Appended
    }

    override suspend fun prefetch(id: String): RunOptions? = null
  }

  private object NoSpecs : SpecRepository {
    override suspend fun projects(): Outcome<List<SpecProject>, ReadProblem> = error("unused")
    override suspend fun projectFor(repoRoot: String): Outcome<SpecProject, ReadProblem> = error("unused")
    override fun observeTree(projectId: String): Flow<SpecTreeState> = emptyFlow()
    override fun refreshTree(projectId: String) = Unit
    override fun control(bundle: SpecBundleRef): Flow<SpecControlState> = emptyFlow()
    override suspend fun openFile(path: String) = OpenOutcome.Opened
    override suspend fun revealFolder(path: String) = OpenOutcome.Opened
  }

  private object NoWebPages : RepositoryWebPages {
    override suspend fun openBranch(repositoryRoot: String, branch: String) = OpenOutcome.Opened
  }
}
