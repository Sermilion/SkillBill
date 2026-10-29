package dev.skillbill.desktop.core.data.session

import dev.skillbill.desktop.core.common.ApplicationCoroutineScope
import dev.skillbill.desktop.core.common.DatabaseDispatcher
import dev.skillbill.desktop.core.common.Diagnostics
import dev.skillbill.desktop.core.common.di.AppScope
import dev.skillbill.desktop.core.data.runtime.BoardPage
import dev.skillbill.desktop.core.domain.model.Lifecycle
import dev.skillbill.desktop.core.domain.session.DatabaseSessionController
import dev.skillbill.desktop.core.domain.session.DatabaseSessionState
import dev.skillbill.desktop.core.domain.settings.AppSettingsRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/** Owns the database session: one runtime graph per selected database path and Claude config directory. */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class DatabaseSessionManager(
  factory: ComponentSessionGraphFactory,
  settings: AppSettingsRepository,
  dispatcher: DatabaseDispatcher,
  diagnostics: Diagnostics,
  private val reservations: LaunchReservations,
  appScope: ApplicationCoroutineScope,
) : DatabaseSessionController {
  private val lifecycle = SessionLifecycle(
    factory,
    settings,
    StorageFailureClassifier::classify,
    dispatcher,
    diagnostics,
    appScope.scope,
    reservations,
  )

  override val state: StateFlow<DatabaseSessionState> get() = lifecycle.state

  /** The open session's component, for the session-scoped repositories; null while opening or failed. */
  val sessions: StateFlow<DatabaseSessionComponent?> =
    lifecycle.current.map { it?.graph?.component }.stateIn(appScope.scope, SharingStarted.Eagerly, null)

  fun start(): Job = lifecycle.start()

  override fun reopen() = lifecycle.reopen()

  internal fun isCurrent(session: DatabaseSessionComponent): Boolean = lifecycle.coordinate { open ->
    (open?.graph as? RuntimeSessionGraph)?.component === session &&
      (state.value as? DatabaseSessionState.Open)?.sessionId == session.selection.selection.id
  }

  internal fun currentComponent(sessionId: Long): DatabaseSessionComponent? = lifecycle.coordinate { open ->
    if (open?.id != sessionId || (state.value as? DatabaseSessionState.Open)?.sessionId != sessionId) null
    else (open.graph as? RuntimeSessionGraph)?.component
  }

  internal fun admitReservation(
    session: DatabaseSessionComponent,
    key: String,
    target: LaunchBoardTarget,
    baseline: Lifecycle?,
    page: BoardPage,
  ): ReservationAdmission = lifecycle.coordinate { open ->
    if ((open?.graph as? RuntimeSessionGraph)?.component !== session ||
      (state.value as? DatabaseSessionState.Open)?.sessionId != session.selection.selection.id
    ) {
      ReservationAdmission.ChangedSession
    } else {
      reservations.reserve(key, session.selection.selection.id, target, baseline, page)
    }
  }

  internal fun acceptReservationBoard(sessionId: Long, page: BoardPage): Boolean = lifecycle.coordinate { open ->
    if (open?.id != sessionId || (state.value as? DatabaseSessionState.Open)?.sessionId != sessionId) false
    else {
      reservations.onBoard(sessionId, page)
      true
    }
  }
}
