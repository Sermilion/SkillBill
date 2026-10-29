package dev.skillbill.desktop.app.di

import dev.skillbill.desktop.app.command.CommandCatalog
import dev.skillbill.desktop.app.command.CommandMenuState
import dev.skillbill.desktop.app.keyboard.ShellKeyboard
import dev.skillbill.desktop.app.keyboard.TerminalTriggers
import dev.skillbill.desktop.app.navigation.WindowNavigator
import dev.skillbill.desktop.app.shell.ShellBoardState
import dev.skillbill.desktop.core.common.AppDispatchers
import dev.skillbill.desktop.core.common.DatabaseDispatcher
import dev.skillbill.desktop.feature.specs.SpecsPaneModel
import dev.skillbill.desktop.core.domain.repository.WorkBoardRepository
import dev.skillbill.desktop.core.domain.repository.WorkControlRepository
import dev.skillbill.desktop.core.domain.repository.WorkDetailRepository
import dev.skillbill.desktop.core.domain.session.DatabaseSessionController
import dev.skillbill.desktop.core.common.di.AppScope
import dev.skillbill.desktop.core.ui.di.WindowScope
import dev.skillbill.desktop.core.ui.feedback.ToastQueue
import dev.skillbill.desktop.core.ui.input.ShellInput
import dev.skillbill.desktop.core.ui.navigation.AppNavigator
import dev.skillbill.desktop.core.ui.navigation.FeatureEntry
import dev.skillbill.desktop.core.ui.selection.SelectionHolder
import dev.skillbill.desktop.core.ui.shell.ShellPanes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import me.tatarka.inject.annotations.Provides
import software.amazon.lastmile.kotlin.inject.anvil.ContributesSubcomponent
import software.amazon.lastmile.kotlin.inject.anvil.ContributesTo
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Everything one window owns. It outlives database switches: the session's objects are rebuilt under it, while the window, its
 * panes and its terminal shells stay. Each back-stack entry gets its own ViewModel store from the navigation host.
 */
@ContributesSubcomponent(WindowScope::class)
@SingleIn(WindowScope::class)
interface WindowComponent {
  val scope: WindowCoroutineScope
  val navigator: WindowNavigator
  val selection: SelectionHolder
  val panes: ShellPanes
  val input: ShellInput
  val toasts: ToastQueue
  val menu: CommandMenuState
  val catalog: CommandCatalog
  val keyboard: ShellKeyboard
  val terminal: TerminalTriggers
  val specsPane: SpecsPaneModel
  val board: ShellBoardState
  val boardRepository: WorkBoardRepository
  val details: WorkDetailRepository
  val controls: WorkControlRepository
  val session: DatabaseSessionController
  val entries: Set<FeatureEntry>

  @ContributesSubcomponent.Factory(AppScope::class)
  interface Factory {
    fun createWindowComponent(): WindowComponent
  }
}

/** The window's coroutines: control calls and the shared board observation. Cancelled when the window is disposed. */
class WindowCoroutineScope(val scope: CoroutineScope)

@ContributesTo(WindowScope::class)
interface WindowProviders {
  @Provides
  @SingleIn(WindowScope::class)
  fun windowScope(): WindowCoroutineScope = WindowCoroutineScope(CoroutineScope(SupervisorJob() + Dispatchers.Main))

  @Provides
  fun appNavigator(navigator: WindowNavigator): AppNavigator = navigator

  @Provides
  fun appDispatchers(database: DatabaseDispatcher): AppDispatchers =
    AppDispatchers(Dispatchers.Main, Dispatchers.IO, database.dispatcher)
}
