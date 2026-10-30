package dev.skillbill.desktop.feature.work

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.focus.FocusRequester
import dev.skillbill.desktop.core.designsystem.component.ToastData
import dev.skillbill.desktop.core.designsystem.component.ToastLead
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.ui.di.WindowScope
import dev.skillbill.desktop.core.ui.feedback.ToastQueue
import dev.skillbill.desktop.core.ui.input.ScreenKey
import dev.skillbill.desktop.core.ui.input.ScreenKeyHandler
import dev.skillbill.desktop.core.ui.navigation.AppNavigator
import dev.skillbill.desktop.core.ui.navigation.AppRoute
import dev.skillbill.desktop.core.ui.navigation.DetailRoute
import dev.skillbill.desktop.core.ui.navigation.FeatureEntry
import dev.skillbill.desktop.core.ui.navigation.WorkRoute
import dev.skillbill.desktop.core.ui.navigation.rememberEntryViewModel
import dev.skillbill.desktop.core.ui.pane.PaneCommands
import dev.skillbill.desktop.core.ui.shell.LocalShellLayout
import dev.skillbill.desktop.core.ui.shell.ShellPanes
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.nio.file.Path
import me.tatarka.inject.annotations.Inject
import me.tatarka.inject.annotations.IntoSet
import me.tatarka.inject.annotations.Provides
import software.amazon.lastmile.kotlin.inject.anvil.ContributesTo
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/** Renders [WorkRoute]. Every section shares one ViewModel, so choosing a section keeps the filter and focus. */
@Inject
@SingleIn(WindowScope::class)
class WorkFeatureEntry(
  private val createViewModel: () -> WorkViewModel,
  private val navigator: AppNavigator,
  private val toasts: ToastQueue,
  private val shellPanes: ShellPanes,
  private val paneCommands: PaneCommands,
) : FeatureEntry {
  override fun renders(route: AppRoute): Boolean = route is WorkRoute

  @Composable
  override fun Content(route: AppRoute) {
    val viewModel = rememberEntryViewModel("work") { createViewModel() }
    val section = (route as WorkRoute).section
    LaunchedEffect(section) { viewModel.onAction(WorkAction.ShowSection(section)) }
    LaunchedEffect(viewModel) {
      viewModel.effects.collect { effect ->
        when (effect) {
          is WorkEffect.OpenDetail -> navigator.navigate(DetailRoute(effect.item))
          is WorkEffect.ShowToast -> toasts.show(effect.message)
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
        workKeyAction(key)?.let(viewModel::onAction) != null
      }
    }
    val state by viewModel.state.collectAsState()
    val terminalOpen by paneCommands.terminalOpen.collectAsState()
    val host = WorkScreenHost(
      specsOpen = shellPanes.specsOpen,
      terminalOpen = terminalOpen,
      onToggleSpecs = { shellPanes.specsOpen = !shellPanes.specsOpen },
      onToggleTerminal = paneCommands::toggleTerminal,
      onShowAllWork = { navigator.navigate(WorkRoute(null)) },
      onOpenTerminal = { paneCommands.openTerminal(Path.of(it.repositoryRoot)) },
      onCopyReason = ::copyReason,
    )
    WorkScreen(state, filterFocus, host, viewModel::onAction)
  }

  private fun copyReason(reason: String) {
    val selection = StringSelection(reason)
    Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, selection)
    toasts.show(ToastData("Copied", reason, ToastLead.Icon(LucideIcons.copy)))
  }
}

@ContributesTo(WindowScope::class)
interface WorkEntryBinding {
  @Provides
  @IntoSet
  fun workEntry(entry: WorkFeatureEntry): FeatureEntry = entry
}
