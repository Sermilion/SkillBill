package dev.skillbill.desktop.app.shell

import dev.skillbill.desktop.core.common.Outcome
import dev.skillbill.desktop.core.domain.model.DatabaseFileIdentity
import dev.skillbill.desktop.core.domain.model.WorkItem
import dev.skillbill.desktop.core.domain.repository.WorkBoardRepository
import dev.skillbill.desktop.core.domain.repository.WorkDetailRepository
import dev.skillbill.desktop.core.domain.repository.WorkDetailResolution
import dev.skillbill.desktop.core.domain.session.DatabaseSessionController
import dev.skillbill.desktop.core.domain.session.DatabaseSessionState
import dev.skillbill.desktop.core.domain.settings.AppSettingsRepository
import dev.skillbill.desktop.core.domain.settings.FavoriteWorkKey
import dev.skillbill.desktop.core.domain.settings.orderedFavorites
import dev.skillbill.desktop.core.ui.di.WindowScope
import dev.skillbill.desktop.core.ui.feedback.ToastMessage
import dev.skillbill.desktop.core.ui.feedback.ToastQueue
import dev.skillbill.desktop.core.ui.feedback.ToastTone
import dev.skillbill.desktop.core.ui.navigation.WindowCoroutineScope
import dev.skillbill.desktop.core.ui.board.ShellBoard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import java.nio.file.Path

sealed interface FavoriteSidebarEntry {
  val key: FavoriteWorkKey
  data class Loading(override val key: FavoriteWorkKey) : FavoriteSidebarEntry
  data class Resolved(override val key: FavoriteWorkKey, val item: WorkItem) : FavoriteSidebarEntry
  data class Missing(override val key: FavoriteWorkKey) : FavoriteSidebarEntry
  data class Failed(override val key: FavoriteWorkKey) : FavoriteSidebarEntry
  data class OtherDatabase(override val key: FavoriteWorkKey) : FavoriteSidebarEntry
}

@Inject
@SingleIn(WindowScope::class)
class FavoritesSidebarState(
  private val settings: AppSettingsRepository,
  session: DatabaseSessionController,
  board: WorkBoardRepository,
  details: WorkDetailRepository,
  private val scope: WindowCoroutineScope,
  private val toasts: ToastQueue,
) {
  private val mutableEntries = MutableStateFlow<List<FavoriteSidebarEntry>>(emptyList())
  val entries: StateFlow<List<FavoriteSidebarEntry>> = mutableEntries

  init {
    scope.scope.launch {
      var confirmedKeys: Set<FavoriteWorkKey> = emptySet()
      combine(settings.favoriteWork, session.state, board.observeRefreshes(ShellBoard.query)) { keys, current, _ -> keys to current }
        .collectLatest { (keysResult, current) ->
          if (keysResult is Outcome.Success) confirmedKeys = keysResult.value
          val keys = confirmedKeys.orderedFavorites(::normalized)
          val open = current as? DatabaseSessionState.Open
          val sessionId = open?.sessionId
          val identity = open?.databaseFile?.normalized()
          mutableEntries.value = keys.map { key ->
            if (open == null || open.databaseFile == null) FavoriteSidebarEntry.Failed(key)
            else if (identity == null || normalized(key.databaseFile.path) != identity.path || key.databaseFile.fileKey != identity.fileKey)
              FavoriteSidebarEntry.OtherDatabase(key)
            else FavoriteSidebarEntry.Loading(key)
          }
          val semaphore = Semaphore(4)
          val resolved = kotlinx.coroutines.coroutineScope {
            keys.map { key -> async {
              val currentIdentity = open?.databaseFile?.normalized()
              if (open == null || open.databaseFile == null) {
                FavoriteSidebarEntry.Failed(key)
              } else if (currentIdentity == null || normalized(key.databaseFile.path) != currentIdentity.path || key.databaseFile.fileKey != currentIdentity.fileKey) {
                FavoriteSidebarEntry.OtherDatabase(key)
              } else semaphore.withPermit {
                when (val result = try { details.resolve(key.workItemId) } catch (failure: CancellationException) { throw failure } catch (_: Exception) { null }) {
                  is WorkDetailResolution.Resolved -> FavoriteSidebarEntry.Resolved(key, result.item)
                  WorkDetailResolution.Missing -> FavoriteSidebarEntry.Missing(key)
                  is WorkDetailResolution.Failed, null -> FavoriteSidebarEntry.Failed(key)
                }
              }
            } }.awaitAll()
          }
          val currentOpen = session.state.value as? DatabaseSessionState.Open
          if (currentOpen != null && currentOpen.sessionId == sessionId && currentOpen.databaseFile?.normalized() == identity) {
            mutableEntries.value = resolved
          }
        }
    }
  }

  fun remove(key: FavoriteWorkKey) {
    scope.scope.launch {
      when (try { settings.setFavorite(key, false) is Outcome.Success } catch (failure: CancellationException) { throw failure } catch (_: Exception) { false }) {
        true -> Unit
        false -> toasts.show(ToastMessage(ToastTone.Problem, "Favorite could not be removed. It remains saved."))
      }
    }
  }

  private fun normalized(path: String): String = runCatching { Path.of(path).toAbsolutePath().normalize().toString() }.getOrDefault(path)
  private fun DatabaseFileIdentity.normalized() = DatabaseFileIdentity(normalized(path), fileKey)
}
