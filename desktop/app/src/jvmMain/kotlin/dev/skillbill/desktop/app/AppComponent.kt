package dev.skillbill.desktop.app

import dev.skillbill.desktop.app.di.WindowComponent
import dev.skillbill.desktop.app.settings.StartAtLoginSync
import dev.skillbill.desktop.core.common.ApplicationCoroutineScope
import dev.skillbill.desktop.core.common.DatabaseDispatcher
import dev.skillbill.desktop.core.common.DiagnosticsSwitch
import dev.skillbill.desktop.core.common.HostEnvironment
import dev.skillbill.desktop.core.common.di.AppScope
import dev.skillbill.desktop.core.data.session.DatabaseSessionManager
import dev.skillbill.desktop.core.domain.repository.SpecRepository
import dev.skillbill.desktop.core.domain.repository.WorkBoardRepository
import dev.skillbill.desktop.core.domain.repository.WorkControlRepository
import dev.skillbill.desktop.core.domain.repository.WorkDetailRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import me.tatarka.inject.annotations.Provides
import software.amazon.lastmile.kotlin.inject.anvil.ContributesTo
import software.amazon.lastmile.kotlin.inject.anvil.MergeComponent
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import kotlin.time.Clock

/** The app's dependency graph. Modules contribute their bindings to [AppScope]; the process facts arrive as arguments. */
@MergeComponent(AppScope::class)
@SingleIn(AppScope::class)
abstract class AppComponent(
  @get:Provides val host: HostEnvironment,
  @get:Provides val diagnosticsSwitch: DiagnosticsSwitch,
) {
  abstract val databaseSessionManager: DatabaseSessionManager
  abstract val startAtLoginSync: StartAtLoginSync
  abstract val workBoardRepository: WorkBoardRepository
  abstract val workControlRepository: WorkControlRepository
  abstract val workDetailRepository: WorkDetailRepository
  abstract val specRepository: SpecRepository
  abstract val windowComponentFactory: WindowComponent.Factory
}

@ContributesTo(AppScope::class)
interface AppProviders {
  @Provides
  @SingleIn(AppScope::class)
  fun databaseDispatcher(): DatabaseDispatcher = DatabaseDispatcher()

  @Provides
  fun clock(): Clock = Clock.System

  @Provides
  @SingleIn(AppScope::class)
  fun applicationScope(): ApplicationCoroutineScope = ApplicationCoroutineScope(CoroutineScope(SupervisorJob() + Dispatchers.Default))
}
