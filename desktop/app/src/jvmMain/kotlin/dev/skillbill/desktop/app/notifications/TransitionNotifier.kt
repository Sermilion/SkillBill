package dev.skillbill.desktop.app.notifications

import dev.skillbill.desktop.app.host.HostIntent
import dev.skillbill.desktop.app.host.HostIntents
import dev.skillbill.desktop.core.common.ApplicationCoroutineScope
import dev.skillbill.desktop.core.domain.model.BoardState
import dev.skillbill.desktop.core.domain.repository.WorkBoardRepository
import dev.skillbill.desktop.core.domain.session.DatabaseSessionController
import dev.skillbill.desktop.core.domain.session.DatabaseSessionState
import dev.skillbill.desktop.core.domain.settings.AppSettingsRepository
import dev.skillbill.desktop.core.ui.board.ShellBoard
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicBoolean
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import dev.skillbill.desktop.core.common.di.AppScope

@Inject
@SingleIn(AppScope::class)
class TransitionNotifier(
  private val boards: WorkBoardRepository,
  private val sessions: DatabaseSessionController,
  private val settings: AppSettingsRepository,
  private val sink: NotificationSink,
  private val intents: HostIntents,
  private val appScope: ApplicationCoroutineScope,
) {
  private val tracker = TransitionTracker()
  @Volatile private var currentSession: Long? = null
  private val started = AtomicBoolean(false)

  @OptIn(ExperimentalCoroutinesApi::class)
  fun start() {
    if (!started.compareAndSet(false, true)) return
    appScope.scope.launch {
      sessions.state.flatMapLatest { session ->
        if (session is DatabaseSessionState.Open) {
          tracker.clear()
          baselineSession = null
          currentSession = session.sessionId
          boards.observe(ShellBoard.query).map { session.sessionId to it }
        } else {
          tracker.clear()
          baselineSession = null
          currentSession = null
          kotlinx.coroutines.flow.emptyFlow()
        }
      }.onEach { (sessionId, board) ->
        if (currentSession != sessionId) {
          tracker.clear()
          currentSession = sessionId
        }
        if (board !is BoardState.Loaded || board.stale) return@onEach
        if (baselineSession != sessionId) {
          tracker.baseline(board.items)
          baselineSession = sessionId
          return@onEach
        }
        tracker.observe(board.items).forEach { (item, kind) ->
          sink.sendAuthorized(notificationTitle(item), notificationBody(item, kind), { send ->
            val enabled = runBlocking { settings.settings.first().notifications[kind] == true }
            val activeSession = (sessions.state.value as? DatabaseSessionState.Open)?.sessionId
            if (enabled && activeSession == sessionId) send()
          }) {
            if ((sessions.state.value as? DatabaseSessionState.Open)?.sessionId == sessionId) intents.send(HostIntent.OpenDetail(item))
            else intents.send(HostIntent.ShowMain)
          }
        }
      }.collect()
    }
  }

  private var baselineSession: Long? = null
}
