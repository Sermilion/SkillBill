package dev.skillbill.desktop.app.shell

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import dev.skillbill.desktop.app.navigation.RouteEntry
import dev.skillbill.desktop.app.navigation.WindowNavigator
import dev.skillbill.desktop.core.designsystem.component.EmptyState
import dev.skillbill.desktop.core.ui.navigation.AppRoute
import dev.skillbill.desktop.core.ui.navigation.FeatureEntry
import dev.skillbill.desktop.core.ui.navigation.LocalEntryKey
import dev.skillbill.desktop.core.ui.navigation.SettingsRoute

/**
 * The window's routes, each entry with its own saved state and ViewModel store. Work, History and Detail show [problem] while
 * there is one; each route renders through the first feature entry that claims it.
 */
@Composable
fun AppNavHost(navigator: WindowNavigator, entries: Set<FeatureEntry>, problem: ShellProblem?, problemPanel: @Composable (ShellProblem) -> Unit) {
  val none = ContentTransform(targetContentEnter = EnterTransition.None, initialContentExit = ExitTransition.None)
  // NavDisplay keeps each entry's content for as long as the back stack is unchanged, so the content reads these through state.
  val currentProblem by rememberUpdatedState(problem)
  val currentProblemPanel by rememberUpdatedState(problemPanel)
  val currentSettingsRoute by rememberUpdatedState(navigator.top.route as? SettingsRoute)
  NavDisplay(
    backStack = navigator.backStack,
    modifier = Modifier.fillMaxSize(),
    onBack = { navigator.pop() },
    entryDecorators = listOf(
      rememberSaveableStateHolderNavEntryDecorator<RouteEntry>(),
      rememberViewModelStoreNavEntryDecorator<RouteEntry>(),
    ),
    transitionSpec = { none },
    popTransitionSpec = { none },
    entryProvider = { entry ->
      NavEntry(entry, contentKey = entry.contentKey) { current ->
        CompositionLocalProvider(LocalEntryKey provides current.contentKey) {
          val shown = currentProblem
          val route = if (current.route is SettingsRoute) currentSettingsRoute ?: current.route else current.route
          if (shown != null && route !is SettingsRoute) currentProblemPanel(shown) else RouteContent(route, entries)
        }
      }
    },
  )
}

@Composable
private fun RouteContent(route: AppRoute, entries: Set<FeatureEntry>) {
  val entry = entries.firstOrNull { it.renders(route) }
  if (entry != null) {
    entry.Content(route)
    return
  }
  LaunchedEffect(route.entryKey) {
    System.getLogger("dev.skillbill.desktop").log(System.Logger.Level.WARNING, "desktop:route-unrendered entry=${route.entryKey}")
  }
  EmptyState("This view is not available")
}
