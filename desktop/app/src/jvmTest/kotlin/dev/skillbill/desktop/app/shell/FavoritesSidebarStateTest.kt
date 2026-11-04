package dev.skillbill.desktop.app.shell

import dev.skillbill.desktop.core.common.Outcome
import dev.skillbill.desktop.core.domain.model.DatabaseFileIdentity
import dev.skillbill.desktop.core.domain.model.OpenOutcome
import dev.skillbill.desktop.core.domain.model.ReadProblem
import dev.skillbill.desktop.core.domain.model.SpecBundleRef
import dev.skillbill.desktop.core.domain.model.SpecControlState
import dev.skillbill.desktop.core.domain.model.SpecCounts
import dev.skillbill.desktop.core.domain.model.SpecDeleteOutcome
import dev.skillbill.desktop.core.domain.model.SpecGroup
import dev.skillbill.desktop.core.domain.model.SpecNode
import dev.skillbill.desktop.core.domain.model.SpecProject
import dev.skillbill.desktop.core.domain.model.SpecStatus
import dev.skillbill.desktop.core.domain.model.SpecTree
import dev.skillbill.desktop.core.domain.model.SpecTreeState
import dev.skillbill.desktop.core.domain.repository.SpecRepository
import dev.skillbill.desktop.core.domain.repository.WorkDetailResolution
import dev.skillbill.desktop.core.domain.session.DatabaseSessionController
import dev.skillbill.desktop.core.domain.session.DatabaseSessionState
import dev.skillbill.desktop.core.domain.settings.AppSettings
import dev.skillbill.desktop.core.domain.settings.FavoriteSpecKey
import dev.skillbill.desktop.core.domain.settings.FavoriteWorkKey
import dev.skillbill.desktop.core.testing.FakeAppSettingsRepository
import dev.skillbill.desktop.core.testing.FakeWorkBoardRepository
import dev.skillbill.desktop.core.testing.FakeWorkDetailRepository
import dev.skillbill.desktop.core.testing.workItem
import dev.skillbill.desktop.core.ui.feedback.ToastQueue
import dev.skillbill.desktop.core.ui.navigation.WindowCoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class FavoritesSidebarStateTest {
  @Test
  fun `favorites match the selected file identity and use the current detail header in saved tuple order`() = runTest {
    val currentFile = DatabaseFileIdentity("/tmp/current.db", "file-current")
    val first = FavoriteWorkKey(currentFile, "b")
    val second = FavoriteWorkKey(currentFile, "a")
    val other = FavoriteWorkKey(DatabaseFileIdentity("/tmp/other.db", "file-other"), "c")
    val settings = FakeAppSettingsRepository(AppSettings.DEFAULT.copy(favoriteWork = setOf(first, second, other)))
    val session = object : DatabaseSessionController {
      override val state = MutableStateFlow<DatabaseSessionState>(DatabaseSessionState.Open("/tmp/current.db", 1, currentFile))
      override fun reopen() = Unit
    }
    val details = FakeWorkDetailRepository().apply {
      val reads = mutableMapOf<String, Int>()
      favoriteResolver = { id ->
        val revision = (reads[id] ?: 0) + 1
        reads[id] = revision
        WorkDetailResolution.Resolved(workItem(id).copy(title = "Current $revision $id"))
      }
    }
    val board = FakeWorkBoardRepository()
    val state = FavoritesSidebarState(
      settings,
      session,
      board,
      details,
      EmptySpecsRepository(),
      WindowCoroutineScope(backgroundScope),
      ToastQueue(),
    )

    runCurrent()

    assertEquals(listOf("a", "b", "c"), state.entries.value.map { it.key.workItemId })
    assertEquals("Current 1 a", assertIs<FavoriteSidebarEntry.Resolved>(state.entries.value[0]).item.title)
    assertIs<FavoriteSidebarEntry.OtherDatabase>(state.entries.value[2])
    board.refresh()
    runCurrent()
    assertEquals("Current 2 a", assertIs<FavoriteSidebarEntry.Resolved>(state.entries.value[0]).item.title)
    settings.favoriteWriteFailure = true
    state.remove(other)
    runCurrent()
    assertEquals(other, state.entries.value[2].key)
  }

  @Test
  fun `a pending result from the previous database cannot replace the new identity row`() = runTest {
    val oldFile = DatabaseFileIdentity("/tmp/old.db", "old-file")
    val newFile = DatabaseFileIdentity("/tmp/new.db", "new-file")
    val key = FavoriteWorkKey(oldFile, "same-id")
    val settings = FakeAppSettingsRepository(AppSettings.DEFAULT.copy(favoriteWork = setOf(key)))
    val session = object : DatabaseSessionController {
      override val state = MutableStateFlow<DatabaseSessionState>(DatabaseSessionState.Open(oldFile.path, 1, oldFile))
      override fun reopen() = Unit
    }
    val pending = CompletableDeferred<WorkDetailResolution>()
    val details = FakeWorkDetailRepository().apply { favoriteResolver = { withContext(NonCancellable) { pending.await() } } }
    val state = FavoritesSidebarState(settings, session, FakeWorkBoardRepository(), details, EmptySpecsRepository(), WindowCoroutineScope(backgroundScope), ToastQueue())

    runCurrent()
    session.state.value = DatabaseSessionState.Open(newFile.path, 2, newFile)
    runCurrent()
    pending.complete(WorkDetailResolution.Resolved(workItem("same-id").copy(title = "Old database")))
    advanceUntilIdle()

    assertIs<FavoriteSidebarEntry.OtherDatabase>(state.entries.value.single())
  }

  @Test
  fun `completed favorites stay selectable while repeated refreshes coalesce into a newer pass`() = runTest {
    val file = DatabaseFileIdentity("/tmp/current.db", "current-file")
    val fast = FavoriteWorkKey(file, "fast")
    val slow = FavoriteWorkKey(file, "slow")
    val settings = FakeAppSettingsRepository(AppSettings.DEFAULT.copy(favoriteWork = setOf(fast, slow)))
    val session = object : DatabaseSessionController {
      override val state = MutableStateFlow<DatabaseSessionState>(DatabaseSessionState.Open(file.path, 1, file))
      override fun reopen() = Unit
    }
    val reads = mutableMapOf<String, MutableList<CompletableDeferred<WorkDetailResolution>>>()
    val details = FakeWorkDetailRepository().apply {
      favoriteResolver = { id ->
        val read = CompletableDeferred<WorkDetailResolution>()
        reads.getOrPut(id) { mutableListOf() } += read
        read.await()
      }
    }
    val board = FakeWorkBoardRepository()
    val state = FavoritesSidebarState(settings, session, board, details, EmptySpecsRepository(), WindowCoroutineScope(backgroundScope), ToastQueue())

    runCurrent()
    reads.getValue("fast").single().complete(WorkDetailResolution.Resolved(workItem("fast").copy(title = "Fast 1")))
    runCurrent()
    assertEquals("Fast 1", assertIs<FavoriteSidebarEntry.Resolved>(state.entries.value.first { it.key == fast }).item.title)

    board.refresh()
    board.refresh()
    runCurrent()
    assertEquals("Fast 1", assertIs<FavoriteSidebarEntry.Resolved>(state.entries.value.first { it.key == fast }).item.title)
    assertEquals(1, reads.getValue("fast").size)

    reads.getValue("slow").single().complete(WorkDetailResolution.Resolved(workItem("slow").copy(title = "Slow 1")))
    runCurrent()
    assertEquals(2, reads.getValue("fast").size)
    assertEquals(2, reads.getValue("slow").size)

    reads.getValue("fast")[1].complete(WorkDetailResolution.Resolved(workItem("fast").copy(title = "Fast 2")))
    runCurrent()
    assertEquals("Fast 2", assertIs<FavoriteSidebarEntry.Resolved>(state.entries.value.first { it.key == fast }).item.title)
    reads.getValue("slow")[1].complete(WorkDetailResolution.Resolved(workItem("slow").copy(title = "Slow 2")))
    runCurrent()
    assertEquals("Slow 2", assertIs<FavoriteSidebarEntry.Resolved>(state.entries.value.first { it.key == slow }).item.title)
  }

  @Test
  fun `a pending favorite removed before its read completes is not published`() = runTest {
    val file = DatabaseFileIdentity("/tmp/current.db", "current-file")
    val key = FavoriteWorkKey(file, "removed")
    val settings = FakeAppSettingsRepository(AppSettings.DEFAULT.copy(favoriteWork = setOf(key)))
    val session = object : DatabaseSessionController {
      override val state = MutableStateFlow<DatabaseSessionState>(DatabaseSessionState.Open(file.path, 1, file))
      override fun reopen() = Unit
    }
    val pending = CompletableDeferred<WorkDetailResolution>()
    val details = FakeWorkDetailRepository().apply { favoriteResolver = { withContext(NonCancellable) { pending.await() } } }
    val state = FavoritesSidebarState(settings, session, FakeWorkBoardRepository(), details, EmptySpecsRepository(), WindowCoroutineScope(backgroundScope), ToastQueue())

    runCurrent()
    state.remove(key)
    runCurrent()
    assertEquals(emptyList(), state.entries.value)
    pending.complete(WorkDetailResolution.Resolved(workItem("removed").copy(title = "Removed after completion")))
    advanceUntilIdle()

    assertEquals(emptyList(), state.entries.value)
  }

  @Test
  fun `spec and work favorites both resolve and spec rows skip OtherDatabase on session change`() = runTest {
    val file = DatabaseFileIdentity("/tmp/current.db", "file-current")
    val workKey = FavoriteWorkKey(file, "work-1")
    val specKey = FavoriteSpecKey("p1", "SKILL-1")
    val settings = FakeAppSettingsRepository(AppSettings.DEFAULT.copy(favoriteWork = setOf(workKey), favoriteSpecs = setOf(specKey)))
    val session = object : DatabaseSessionController {
      override val state = MutableStateFlow<DatabaseSessionState>(DatabaseSessionState.Open(file.path, 1, file))
      override fun reopen() = Unit
    }
    val details = FakeWorkDetailRepository().apply {
      favoriteResolver = { WorkDetailResolution.Resolved(workItem(it).copy(title = "Work $it")) }
    }
    val project = SpecProject("p1", "Project", "/repo/specs", "/repo")
    val bundle = SpecNode.Bundle("SKILL-1", "SKILL-1", "/repo/specs/SKILL-1", "SKILL-1", "Spec title", SpecStatus.Pending, SpecGroup.Pending, 0, 1, emptyList())
    val tree = SpecTree(project, listOf(bundle), SpecCounts(1, 0, 1, 0), false)
    val specRepo = object : EmptySpecsRepository() {
      override suspend fun projects() = Outcome.Success(listOf(project))
      override fun observeTree(projectId: String) = flowOf(SpecTreeState.Loaded(tree, project.specsRoot))
    }
    val state = FavoritesSidebarState(settings, session, FakeWorkBoardRepository(), details, specRepo, WindowCoroutineScope(backgroundScope), ToastQueue())

    runCurrent()
    assertEquals(1, state.entries.value.size)
    assertEquals(1, state.specEntries.value.size)
    assertIs<FavoriteSpecSidebarEntry.Resolved>(state.specEntries.value.single())

    session.state.value = DatabaseSessionState.Open("/tmp/other.db", 2, DatabaseFileIdentity("/tmp/other.db", "other"))
    runCurrent()
    assertIs<FavoriteSidebarEntry.OtherDatabase>(state.entries.value.single())
    assertIs<FavoriteSpecSidebarEntry.Resolved>(state.specEntries.value.single())

    settings.specFavoriteWriteFailure = true
    state.removeSpec(specKey)
    runCurrent()
    assertEquals(specKey, state.specEntries.value.single().key)
    settings.specFavoriteWriteFailure = false
    state.removeSpec(specKey)
    runCurrent()
    assertEquals(emptyList(), state.specEntries.value)
  }
}

private open class EmptySpecsRepository : SpecRepository {
  override suspend fun projects(): Outcome<List<SpecProject>, ReadProblem> = Outcome.Success(emptyList())
  override suspend fun projectFor(repoRoot: String) = Outcome.Failure(ReadProblem.Runtime("missing"))
  override fun observeTree(projectId: String): Flow<SpecTreeState> = flowOf(SpecTreeState.Loading)
  override fun refreshTree(projectId: String) = Unit
  override fun control(bundle: SpecBundleRef) = flowOf(SpecControlState.Loading)
  override suspend fun openFile(path: String) = OpenOutcome.NotOpened
  override suspend fun openFileInDefaultApp(path: String) = OpenOutcome.NotOpened
  override suspend fun openFileUnder(path: String, directory: String) = OpenOutcome.NotOpened
  override suspend fun revealFolder(path: String) = OpenOutcome.NotOpened
  override suspend fun deleteBundle(bundle: SpecBundleRef) = SpecDeleteOutcome.Refused("no")
}
