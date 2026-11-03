package dev.skillbill.desktop.app.shell

import dev.skillbill.desktop.core.domain.model.DatabaseFileIdentity
import dev.skillbill.desktop.core.domain.repository.WorkDetailResolution
import dev.skillbill.desktop.core.domain.session.DatabaseSessionController
import dev.skillbill.desktop.core.domain.session.DatabaseSessionState
import dev.skillbill.desktop.core.domain.settings.AppSettings
import dev.skillbill.desktop.core.domain.settings.FavoriteWorkKey
import dev.skillbill.desktop.core.testing.FakeAppSettingsRepository
import dev.skillbill.desktop.core.testing.FakeWorkBoardRepository
import dev.skillbill.desktop.core.testing.FakeWorkDetailRepository
import dev.skillbill.desktop.core.testing.workItem
import dev.skillbill.desktop.core.ui.feedback.ToastQueue
import dev.skillbill.desktop.core.ui.navigation.WindowCoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.CompletableDeferred
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
    val details = FakeWorkDetailRepository().apply { favoriteResolver = { pending.await() } }
    val state = FavoritesSidebarState(settings, session, FakeWorkBoardRepository(), details, WindowCoroutineScope(backgroundScope), ToastQueue())

    runCurrent()
    session.state.value = DatabaseSessionState.Open(newFile.path, 2, newFile)
    runCurrent()
    pending.complete(WorkDetailResolution.Resolved(workItem("same-id").copy(title = "Old database")))
    advanceUntilIdle()

    assertIs<FavoriteSidebarEntry.OtherDatabase>(state.entries.value.single())
  }
}
