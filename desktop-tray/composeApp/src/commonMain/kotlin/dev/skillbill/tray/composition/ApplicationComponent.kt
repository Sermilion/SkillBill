package dev.skillbill.tray.composition

import dev.skillbill.tray.core.common.DispatcherProvider
import dev.skillbill.tray.core.common.HostEnvironment
import dev.skillbill.tray.core.common.di.AppScope
import dev.skillbill.tray.core.datastore.LegacyPreferencesSource
import dev.skillbill.tray.core.datastore.SettingsLifetime
import dev.skillbill.tray.core.domain.repository.SettingsRepository
import dev.skillbill.tray.core.domain.repository.WorkBoardRepository
import dev.skillbill.tray.core.domain.repository.WorkControlRepository
import dev.skillbill.tray.core.domain.repository.WorkDetailRepository
import dev.skillbill.tray.core.domain.repository.WorkPolicyRepository
import dev.skillbill.tray.core.domain.repository.WorkStoreSession
import me.tatarka.inject.annotations.Inject
import me.tatarka.inject.annotations.Provides
import software.amazon.lastmile.kotlin.inject.anvil.MergeComponent
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import kotlin.time.Clock

/**
 * The application's dependency graph. Every module contributes its own bindings to [AppScope]; this component merges
 * them, so no code outside generated wiring decides which implementation a repository has. The process facts arrive as
 * the [host] argument and the retired preferences as [legacySettings], so a test can give the whole graph a disposable
 * home, environment and preferences node.
 */
@MergeComponent(AppScope::class)
@SingleIn(AppScope::class)
abstract class ApplicationComponent(
  @get:Provides val host: HostEnvironment,
  @get:Provides val legacySettings: LegacyPreferencesSource,
) {
  abstract val boards: WorkBoardRepository
  abstract val details: WorkDetailRepository
  abstract val controls: WorkControlRepository
  abstract val policy: WorkPolicyRepository
  abstract val storeSession: WorkStoreSession
  abstract val settings: SettingsRepository
  abstract val dispatchers: DispatcherProvider
  abstract val clock: Clock
  abstract val shutdown: ApplicationShutdown
  abstract val viewModels: ViewModelFactory
}

/** Releases what the application owns for its whole run: the selected database session and the settings store. */
@Inject
@SingleIn(AppScope::class)
class ApplicationShutdown(
  private val storeSession: WorkStoreSession,
  private val settingsLifetime: SettingsLifetime,
) {
  suspend fun closeAndJoin() {
    try {
      storeSession.closeAndJoin()
    } finally {
      settingsLifetime.closeAndJoin()
    }
  }
}
