package dev.skillbill.desktop.app

import dev.skillbill.desktop.core.common.ApplicationCoroutineScope
import dev.skillbill.desktop.core.common.DatabaseDispatcher
import dev.skillbill.desktop.core.common.DiagnosticsSwitch
import dev.skillbill.desktop.core.common.HostEnvironment
import dev.skillbill.desktop.core.common.di.AppScope
import dev.skillbill.desktop.core.data.session.DatabaseSessionManager
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
