package dev.skillbill.desktop.feature.history

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.focus.FocusRequester
import dev.skillbill.desktop.core.ui.di.WindowScope
import dev.skillbill.desktop.core.ui.input.ScreenKey
import dev.skillbill.desktop.core.ui.input.ScreenKeyHandler
import dev.skillbill.desktop.core.ui.navigation.AppNavigator
import dev.skillbill.desktop.core.ui.navigation.AppRoute
import dev.skillbill.desktop.core.ui.navigation.DetailRoute
import dev.skillbill.desktop.core.ui.navigation.FeatureEntry
import dev.skillbill.desktop.core.ui.navigation.HistoryRoute
import dev.skillbill.desktop.core.ui.navigation.rememberEntryViewModel
import dev.skillbill.desktop.core.ui.pane.PaneCommands
import dev.skillbill.desktop.core.ui.shell.LocalShellLayout
import dev.skillbill.desktop.core.ui.shell.ShellPanes
import me.tatarka.inject.annotations.Inject
import me.tatarka.inject.annotations.IntoSet
import me.tatarka.inject.annotations.Provides
import software.amazon.lastmile.kotlin.inject.anvil.ContributesTo
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/** Renders [HistoryRoute]; a row opens its detail with the row's item, so the detail header paints from it. */
@Inject
@SingleIn(WindowScope::class)
class HistoryFeatureEntry(
  private val createViewModel: () -> HistoryViewModel,
  private val navigator: AppNavigator,
  private val shellPanes: ShellPanes,
  private val paneCommands: PaneCommands,
) : FeatureEntry {
  override fun renders(route: AppRoute): Boolean = route == HistoryRoute

  @Composable
  override fun Content(route: AppRoute) {
    val viewModel = rememberEntryViewModel("history") { createViewModel() }
    LaunchedEffect(viewModel) {
      viewModel.effects.collect { effect ->
        when (effect) {
          is HistoryEffect.OpenDetail -> navigator.navigate(DetailRoute(effect.item))
        }
      }
    }
    val filterFocus = remember { FocusRequester() }
    // Narrow windows hide the filter with the header's middle slot, so "/" has nothing to focus there.
    val filterShown by rememberUpdatedState(!LocalShellLayout.current.drawerMode)
    ScreenKeyHandler { key ->
      if (key == ScreenKey.FocusFilter) {
        if (filterShown) filterFocus.requestFocus()
        filterShown
      } else {
        historyKeyAction(key)?.let(viewModel::onAction) != null
      }
    }
    val state by viewModel.state.collectAsState()
    val terminalOpen by paneCommands.terminalOpen.collectAsState()
    val host = HistoryScreenHost(
      specsOpen = shellPanes.specsOpen,
      terminalOpen = terminalOpen,
      onToggleSpecs = { shellPanes.specsOpen = !shellPanes.specsOpen },
      onToggleTerminal = paneCommands::toggleTerminal,
    )
    HistoryScreen(state, filterFocus, host, viewModel::onAction)
  }
}

@ContributesTo(WindowScope::class)
interface HistoryEntryBinding {
  @Provides
  @IntoSet
  fun historyEntry(entry: HistoryFeatureEntry): FeatureEntry = entry
}
