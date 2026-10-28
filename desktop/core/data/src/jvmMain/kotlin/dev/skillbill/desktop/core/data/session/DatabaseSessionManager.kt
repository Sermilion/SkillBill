package dev.skillbill.desktop.core.data.session

import dev.skillbill.desktop.core.common.ApplicationCoroutineScope
import dev.skillbill.desktop.core.common.DatabaseDispatcher
import dev.skillbill.desktop.core.common.Diagnostics
import dev.skillbill.desktop.core.common.di.AppScope
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
  appScope: ApplicationCoroutineScope,
) : DatabaseSessionController {
  private val lifecycle = SessionLifecycle(factory, settings, StorageFailureClassifier::classify, dispatcher, diagnostics, appScope.scope)

  override val state: StateFlow<DatabaseSessionState> get() = lifecycle.state

  /** The open session's component, for the session-scoped repositories; null while opening or failed. */
  val sessions: StateFlow<DatabaseSessionComponent?> =
    lifecycle.current.map { it?.graph?.component }.stateIn(appScope.scope, SharingStarted.Eagerly, null)

  fun start(): Job = lifecycle.start()

  override fun reopen() = lifecycle.reopen()
}
