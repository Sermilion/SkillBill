package dev.skillbill.tray.composition

import dev.skillbill.tray.core.common.di.AppScope
import dev.skillbill.tray.core.common.di.EntryScope
import dev.skillbill.tray.core.common.di.WindowScope
import dev.skillbill.tray.core.domain.repository.SettingsRepository
import dev.skillbill.tray.core.domain.repository.WorkBoardRepository
import dev.skillbill.tray.core.domain.repository.WorkControlRepository
import dev.skillbill.tray.core.domain.repository.WorkDetailRepository
import dev.skillbill.tray.core.domain.repository.WorkStoreStatusRepository
import dev.skillbill.tray.feature.detail.DetailArgs
import dev.skillbill.tray.feature.detail.DetailViewModel
import dev.skillbill.tray.feature.history.HistoryViewModel
import dev.skillbill.tray.feature.settings.SettingsViewModel
import dev.skillbill.tray.feature.work.WorkViewModel
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesSubcomponent
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import kotlin.time.Clock

/**
 * Everything one window owns. A component is created when a window opens and dropped when it is disposed; hiding a window keeps
 * it. The window's entries each get their own [EntryComponent] from [entries].
 */
@ContributesSubcomponent(WindowScope::class)
@SingleIn(WindowScope::class)
interface WindowComponent {
  val entries: EntryComponents

  @ContributesSubcomponent.Factory(AppScope::class)
  interface Factory {
    fun createWindowComponent(): WindowComponent
  }
}

/** The app-scope way to open a window's graph. */
@Inject
@SingleIn(AppScope::class)
class WindowComponents(private val factory: WindowComponent.Factory) {
  fun create(): WindowComponent = factory.createWindowComponent()
}

/**
 * What one live navigation entry builds its ViewModels from. A component is created when the entry first composes, is held by
 * the entry's ViewModelStore, and goes with the entry's ViewModels when the entry leaves the stack, its window is disposed or
 * its database generation ends.
 */
@ContributesSubcomponent(EntryScope::class)
@SingleIn(EntryScope::class)
interface EntryComponent {
  val viewModels: EntryViewModels

  @ContributesSubcomponent.Factory(WindowScope::class)
  interface Factory {
    fun createEntryComponent(): EntryComponent
  }
}

/** The window-scope way to open an entry's graph. */
@Inject
@SingleIn(WindowScope::class)
class EntryComponents(private val factory: EntryComponent.Factory) {
  fun create(): EntryComponent = factory.createEntryComponent()
}

/**
 * The one place the screens' ViewModels are built. Each call returns a new instance, and the entry that asked holds it: one
 * ViewModel per live entry, with the entry's typed arguments as the only input that is not from the graph.
 */
@Inject
@SingleIn(EntryScope::class)
class EntryViewModels(
  private val boards: WorkBoardRepository,
  private val details: WorkDetailRepository,
  private val controls: WorkControlRepository,
  private val settings: SettingsRepository,
  private val storeStatus: WorkStoreStatusRepository,
  private val clock: Clock,
) {
  fun work() = WorkViewModel(boards, settings)

  fun history() = HistoryViewModel(boards, settings)

  fun detail(args: DetailArgs) = DetailViewModel(args.identity, details, controls, boards, clock)

  fun settings() = SettingsViewModel(settings, storeStatus)
}
